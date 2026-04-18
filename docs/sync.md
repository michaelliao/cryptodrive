# Sync

CryptoDrive can sync an unlocked vault to any S3-compatible cloud storage.
Sync is **one-way: local to cloud only**. Changes made in the cloud are not
pulled back to the local vault.

Because everything synced is already encrypted (ciphertext `.c9e` blobs,
encrypted `files.json`, and `vault.json` with a wrapped DEK), the cloud
provider never sees plaintext file names or contents.

## Configuration

Sync is configured per vault in the **Sync** tab of the vault configuration
dialog. The S3 credentials (endpoint, region, bucket, access ID, secret, and
remote path) are encrypted with the vault's DEK and stored in `vault.json` as
`syncConfig.encryptedConfigJsonB64`. This means:

- The S3 credentials survive a password change (the DEK doesn't change).
- An attacker who obtains the vault directory cannot read the S3 credentials
  without the master password.

Use **Test Connection** to verify that the credentials are correct before
saving. The test uploads a small `__test__.tmp` file to the configured bucket
and reads it back.

## What is synced

Only vault files are synced:

| File pattern                      | Description                            |
|-----------------------------------|----------------------------------------|
| `<xx>/<yy>/<zz>.c9e`              | Encrypted file blobs (ciphertext)      |
| `files.json`                      | Encrypted virtual directory tree       |
| `vault.json`                      | KDF parameters and wrapped DEK         |

All other files in the vault directory (e.g. `sync.json`, `upload.tmp`) are
local-only and never uploaded.

Files are stored under a configurable **remote path** prefix in the S3
bucket. For example, if the remote path is `backup/my-vault`, a file with
inode `9001` is uploaded to `backup/my-vault/00/23/29.c9e`.

## Lifecycle

### Unlock (start sync)

When a vault with sync enabled is unlocked:

1. The encrypted S3 config is decrypted with the DEK.
2. The sync queue (`sync.json`) is loaded from disk. If `sync.json` does not
   exist (first sync), every `.c9e` blob in the vault is added to the queue
   as `"updated"`, triggering a full upload.
3. `vault.json` and `files.json` are always added to the queue, since they
   may have changed while the vault was locked (e.g. password change, config
   edit).
4. A background `SyncThread` is started. It creates an S3 client and begins
   processing the queue.

### Lock (stop sync)

When the vault is locked:

1. The sync thread is interrupted and joined (waits for it to stop).
2. The remaining queue is persisted to `sync.json` so progress is not lost.
3. On next unlock, the queue picks up where it left off.

## Sync queue

The queue is an ordered list of pending changes, persisted as
`<vault>/sync.json`:

```json
{
    "queue": [
        {
            "action": "updated",
            "path": "00/0f/a5.c9e",
            "timestamp": 123455000
        },
        {
            "action": "deleted",
            "path": "00/9c/4f.c9e",
            "timestamp": 123456000
        }
    ]
}
```

- `action` — `"updated"` (file created or modified) or `"deleted"` (file
  removed).
- `path` — relative ciphertext path within the vault (e.g. `00/0f/a5.c9e`
  or `files.json`).
- `timestamp` — epoch millis when the change occurred.

### Deduplication

If a file is already in the queue and a new change arrives for the same
path, the existing entry is **replaced in place** (same queue position, new
action and timestamp). This prevents the queue from growing unboundedly when
a file is written repeatedly.

### FUSE integration

The FUSE filesystem automatically feeds the queue:

| FUSE operation         | Queue entries added                         |
|------------------------|---------------------------------------------|
| `create` (new file)    | `files.json` queued; file queued on release  |
| `write`                | File marked dirty; queued on `release`       |
| `unlink` (delete file) | `"deleted"` for the `.c9e` blob + `files.json` |
| `mkdir`                | `files.json`                                |
| `rmdir`                | `files.json`                                |
| `rename` (file or dir) | `files.json`                                |

File changes are only queued when the file handle is **released** (closed),
not on every individual write call.

## Upload process

The sync thread processes the queue one entry at a time.

### Startup: fetch remote metadata

Before processing any queue entries, the sync thread lists all objects in
the remote bucket under the configured prefix. For each object matching the
vault file pattern, it records the S3 **eTag** (MD5). This metadata map is
kept in memory for the duration of the sync session.

Some S3 providers return the eTag as hex (32 characters), others as base64
(24 characters). The sync thread normalizes hex eTags to base64 for
consistent comparison.

### Processing an `"updated"` entry

Upload happens in two phases to avoid blocking user operations:

**Phase 1 — local file read (under lock)**

1. Acquire `syncLock`.
2. Set `syncingPath` to the current file so the FUSE layer can detect
   conflicts.
3. Copy the source `.c9e` file to a temporary `upload.tmp`, computing the
   MD5 digest during the copy using a `DigestOutputStream`.
4. Release `syncLock` and clear `syncingPath`.

Both read and write use NIO `FileChannel`, so `Thread.interrupt()` from the
FUSE layer causes an immediate `ClosedByInterruptException` — the user's
write is never blocked for more than one buffer cycle.

If interrupted, the temp file is cleaned up and the entry is removed from
the queue (the FUSE write will re-queue it with fresh content).

**Phase 2 — S3 upload (no lock)**

1. Compare the local MD5 against the cached remote MD5. If they match, skip
   the upload.
2. Upload `upload.tmp` to S3 with `Content-MD5` set for integrity
   verification.
3. Update the in-memory metadata map with the new MD5.
4. Delete `upload.tmp`.

Because phase 2 operates on the temp file (not the original), the source
file is free for FUSE writes during the entire upload.

### Processing a `"deleted"` entry

1. Check the metadata map. If the file is not on the remote, skip.
2. Send a `DeleteObject` request to S3.
3. Remove the entry from the metadata map.

### Failure handling

- If an entry fails to process, it is moved to the **back of the queue** so
  other entries can proceed. The thread backs off for 5 seconds before
  retrying.
- After each successful or skipped entry, `sync.json` is rewritten to disk
  so progress survives a crash.
- When the queue is empty, the thread polls every 5 seconds for new entries.

## Concurrency: FUSE vs. sync thread

The sync thread only reads local files; the user writes and deletes through
FUSE. Two mechanisms ensure user operations are never blocked:

### 1. Interrupt on conflict

When the FUSE layer opens a file for writing or deletes a file, it calls
`vault.interruptSyncIfReading(relativePath)`. This checks the sync thread's
`syncingPath`:

- **No conflict:** returns immediately (no lock overhead).
- **Conflict:** calls `Thread.interrupt()` on the sync thread, then acquires
  `syncLock` to wait until the sync thread has closed the file. The wait is
  bounded by the local file read — never by the S3 network call, which runs
  outside the lock.

### 2. Temp file isolation

The S3 upload reads from `upload.tmp`, not the source file. Even if the
upload takes seconds, the source file is free for user writes. The next sync
cycle will pick up the new content.

### Sequence diagram

```
FUSE thread                  Sync thread
    │                            │
    │                     syncLock.lock()
    │                     syncingPath = "00/23/29.c9e"
    │                     copy file → upload.tmp (+ MD5)
    │                            │
    ├─ open("00/23/29.c9e", WRITE)
    ├─ interruptIfSyncing()      │
    │   ├─ interrupt() ──────────┤ ← ClosedByInterruptException
    │   └─ syncLock.lock()       │   syncingPath = null
    │      (waits)               │   syncLock.unlock()
    │   syncLock.unlock() ◄──────┤
    │   (returns)                │
    ├─ write data                │
    ├─ close                     │
    │   └─ queue "updated"       │
    │                     ...poll queue...
    │                     copy file → upload.tmp (new content)
    │                     upload upload.tmp to S3
```

## Status display

When a vault is unlocked with sync enabled, the detail view shows a live
sync status at the bottom:

- **All synced:** "All files in vault have been synchronized." (with a
  synced icon)
- **In progress:** "Synchronizing *N* files..." (with a syncing icon)

The status polls every 2 seconds.
