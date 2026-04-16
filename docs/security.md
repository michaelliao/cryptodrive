# Security

This page describes exactly how CryptoDrive protects your files: which
algorithms and parameters are used, how keys are derived and wrapped, and how
the virtual filesystem maps the directories and files you see in the mount
onto the encrypted blobs on disk.

## Threat model

CryptoDrive is designed to protect the confidentiality and integrity of your
files **at rest** — on your local disk, on a lost laptop, or when synced to an
untrusted cloud provider. Specifically:

- **Protected:** an attacker who obtains the vault directory (ciphertext 
  blobs + `vault.json` + `files.json`) cannot read your file contents, cannot read
  your file or directory names, and cannot silently modify a file without
  detection.
- **Not protected:** an attacker with code execution on your machine *while a
  vault is unlocked* can read plaintext through the mounted drive like any
  other local process. Mounted drives are a local-user surface, not a
  sandboxed one.

Password strength matters. A weak master password can be brute-forced against
the stored salt and wrapped DEK; use a long, unique passphrase.

## Cryptographic primitives

| Purpose                     | Algorithm              | Parameters                                  |
|-----------------------------|------------------------|---------------------------------------------|
| Password-based key derivation | **PBKDF2-HMAC-SHA256** | 256-bit output, 1 000 000 iterations (default), 32-byte random salt |
| Data encryption             | **AES-256-GCM**        | 12-byte IV (random per encryption), 128-bit auth tag |
| Random number generation    | `java.security.SecureRandom` | OS-seeded CSPRNG                    |

Every encryption is authenticated — decryption verifies the 128-bit GCM tag
before returning plaintext. A single flipped bit on disk causes the affected
block to fail verification instead of silently returning corrupted data.

## Key hierarchy

CryptoDrive uses a three-level key hierarchy. Only the bottom two levels are
ever persisted, and both are persisted only in wrapped form.

```
master password  ──PBKDF2──►  KEK  ──wraps──►  DEK  ──wraps──►  FEK (per file)
  (never stored)              (derived          (random,          (random,
                               on unlock,        wrapped in        wrapped in
                               never stored)    vault.json)       each .c9e header)
```

### KEK — Key Encryption Key

Derived on demand from the master password and the vault's stored salt via
PBKDF2. The KEK exists only in memory and only long enough to unwrap the DEK
(on unlock) or rewrap it (on password change).

### DEK — Data Encryption Key

A random 256-bit key generated once when the vault is created. It never
changes over the lifetime of the vault. The DEK is stored in `vault.json`
*wrapped with the KEK* (AES-GCM), so changing the master password only has to
rewrap the DEK — all existing file blobs remain valid.

The DEK is used to:

- Wrap per-file FEKs (stored in each file's header).
- Encrypt file and directory names in `files.json`.

### FEK — File Encryption Key

A random 256-bit key generated once per file when the file is created. It is
stored *wrapped with the DEK* inside the first 64 bytes of the file's
ciphertext blob. The FEK encrypts the block payloads of that one file and
nothing else.

Per-file FEKs mean:

- Each file's ciphertext is independent — no chosen-plaintext leakage across
  files.
- Deleting a file is sufficient to render its contents unrecoverable even if
  the DEK later leaks, provided the wrapped FEK is gone.

## Vault layout on disk

A vault is a plain directory. Everything in it is either ciphertext or
encrypted metadata — plaintext never touches the vault folder.

```
<vault>/
├── vault.json        # KDF params, salt, wrapped DEK
├── files.json        # encrypted virtual tree (names + structure)
├── 00/
│   ├── 00/
│   │   ├── 65.c9e    # inode 101
│   │   └── 66.c9e    # inode 102
│   └── 23/
│       └── 29.c9e    # inode 9001
└── 0f/
    └── 65/
        └── 6a.c9e    # inode 1 009 002
```

### `vault.json`

Holds KDF parameters, the salt, and the wrapped DEK. All fields are
base64-encoded where binary. A snooper who reads this file learns the KDF
parameters (public by design) but cannot unwrap the DEK without the master
password.

### `files.json`

Holds the entire virtual directory tree the user sees inside the mount —
including all file and directory names. Names are AES-GCM encrypted with the
DEK and base64-encoded as `encryptedNameB64`. Directory structure (the
nesting of `dirs`/`files` arrays) leaks some shape information but not names.

### `<byte2>/<byte1>/<byte0>.c9e`

Each file's ciphertext lives at a path derived from its 24-bit file inode.
The two-level fan-out keeps any one directory under a few hundred entries
even at millions of files.

## File and directory model

The tree the user sees inside the mount is **virtual**. It lives entirely in
`files.json`, decrypted into an in-memory representation when the vault is
unlocked. The ciphertext directory on disk is flat (just the `xx/yy/` blob
layout above) and has no relationship to how files are organized in the
mount.

### Inode ranges

- **File inodes:** `0x000001` – `0xFFFFFF` (1 – 16 777 215). Persisted in
  `files.json`. Each file inode maps 1:1 to a `.c9e` blob.
- **Dir inodes:** `0x1000000` and above. **Not persisted.** Assigned at mount
  time from a monotonic sequence starting at `0x1000000` for the root. Dir
  inodes are ephemeral; they may change between sessions.

### Path resolution

Resolving a path like `/work/notes/todo.md` walks the in-memory tree one
component at a time. Each step:

1. Iterate the current directory's children.
2. Decrypt each `encryptedNameB64` with the DEK and compare to the next
   plaintext component.
3. If the match is a directory, descend. If it's a file, return its inode.

The plaintext path is never written anywhere, and plaintext names never live
outside of this transient comparison.

### Metadata vs. blob operations

Operations fall into two cost classes:

**Metadata-only — touches only `files.json`, no blob I/O**

- `mkdir`, `rmdir`
- `rename` / move (of both files and directories)
- directory listing
- path resolution

**Blob I/O — reads or writes a `.c9e` file**

- `create` — allocate a new inode, add a file node, write an empty-but-headered blob
- `open`, `read`, `write`, `truncate`
- `unlink` — remove the blob and the file node

Because rename is metadata-only, moving a file is a rewrite of `files.json`
regardless of file size. No plaintext is ever re-encrypted during rename.

### Startup and `lost+found`

On mount, CryptoDrive scans the vault directory for everything matching
`[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{2}.c9e`. For each blob it recovers the file
inode. If a blob's inode is not referenced by any node in the tree from
`files.json`, the blob is attached to a virtual `lost+found` directory at the
root. This protects against:

- A sync provider delivering a new blob before the updated `files.json`.
- A crash between writing a blob and writing `files.json`.
- A human accidentally deleting or corrupting `files.json`.

You can always read orphaned files through `lost+found` without losing data.

## Encryption file format (`.c9e`)

Each on-disk blob is a 64-byte header followed by a sequence of encrypted
blocks of up to 32 KiB plaintext each. Every block is independent — damage to
one block does not propagate to others.

### 64-byte header

```
┌──────────┬──────┬──────────────┬──────┐
│ 4 ver    │ 12   │ 32 enc FEK   │ 16   │
│ (LE u32) │ IV   │              │ tag  │
└──────────┴──────┴──────────────┴──────┘
```

- **version** (4 B, little-endian u32) — currently `1`. Reserves room for
  future format evolutions.
- **IV** (12 B) — random, used once for the FEK wrap.
- **enc FEK** (32 B) — the per-file key, AES-GCM encrypted with the DEK.
- **tag** (16 B) — GCM auth tag over the wrapped FEK.

Opening a file does an AES-GCM decrypt of this header with the DEK to recover
the FEK. The FEK is then cached for the lifetime of the open file handle.

### Encrypted block

One block per 32 KiB of plaintext, repeated until EOF:

```
┌──────────┬──────┬────────────────────┬──────┐
│ 4 meta   │ 12   │ up to 32 KiB       │ 16   │
│ (0x0…0)  │ IV   │ encrypted data     │ tag  │
└──────────┴──────┴────────────────────┴──────┘
```

- **meta** (4 B) — reserved, currently zero.
- **IV** (12 B) — fresh random per block. Re-encrypting a block on partial
  write always picks a new IV, so the same plaintext never produces the same
  ciphertext.
- **data** — AES-GCM ciphertext under the FEK. Full blocks are exactly
  32 KiB; only the final block may be shorter.
- **tag** (16 B) — GCM auth tag.

A full encrypted block on disk is **32 KiB + 32 B = 32 800 bytes**. The
per-block overhead is ~0.1% of file size.

### Partial writes and reads

Reads and writes at the FUSE layer are block-aligned. A partial write pulls
the containing 32 KiB block into memory, patches the changed bytes, generates
a fresh IV, re-encrypts the whole block under the FEK, and writes it back.
This means:

- No plaintext crosses a block boundary.
- The GCM tag is recomputed on every write, so in-place modification cannot
  desynchronize authentication.
- Bit rot or a truncated sync is contained to the affected block — the rest of
  the file still decrypts and verifies.

## Key lifetime and zeroization

- The **master password** is held in a `char[]` for the shortest possible time
  (dialog → PBKDF2 → zero) and is never copied into a `String`.
- The **KEK** is derived, used to unwrap the DEK, and discarded within the
  same method.
- The **DEK** lives in memory only while the vault is unlocked. Locking the
  vault — manually, at app exit, or via the shutdown hook — zeroizes the DEK
  buffer and clears the in-memory tree.
- Each **FEK** lives only as long as its corresponding open file handle. When
  the handle closes, the FEK buffer is zeroized.

Zeroization uses `Arrays.fill(key, (byte) 0)` on the backing byte array. JCE
key material is additionally wrapped in a `SecretKey` implementation whose
`destroy()` clears the buffer.

## What is *not* encrypted

For full transparency, these are observable by anyone with access to the vault
directory:

- The **number of files** (counted via `.c9e` blobs).
- The **size of each file** (to within one 32 KiB block and a few bytes of
  overhead).
- The **approximate shape of the directory tree** (from the nesting in
  `files.json`, even though names are encrypted).
- **Modification timestamps** of `.c9e` blobs (filesystem metadata, controlled
  by the OS).
- The **KDF parameters** and **salt** in `vault.json` (public by design).

If your threat model requires hiding file counts or sizes, CryptoDrive is not
the right tool — consider a block-level encrypted container (LUKS, VeraCrypt)
instead.
