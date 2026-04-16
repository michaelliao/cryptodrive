# User Guide

This guide walks through the day-to-day tasks of using CryptoDrive: creating a
new vault, importing an existing one, unlocking and mounting it as a drive,
browsing files, configuring options, and locking it again.

## Overview

CryptoDrive manages one or more **vaults**. A vault is a local folder that
contains only encrypted data. When you unlock a vault with its master
password, CryptoDrive mounts it as a regular drive (for example `X:\` on
Windows, `/Volumes/<Name>` on macOS, a mount directory on Linux) and decrypts
reads and writes transparently as your applications use the drive.

The main window has two panes:

- **Left pane** — the list of vaults CryptoDrive knows about. An icon next to
  each entry shows whether it is currently locked or unlocked.
- **Right pane** — the detail view for whichever vault is selected, with
  buttons to unlock, lock, reveal the drive in your file manager, or open
  per-vault settings.

The toolbar at the top of the left pane has **New Vault**, **Import Vault**,
and **Remove Vault** actions; the app menu also exposes **Settings** for
application-wide options.

## Create a new vault

Use this when you want to start fresh with a brand-new, empty encrypted
folder.

1. Click **New Vault** in the toolbar.
2. In the **Vault Location** field, type or paste a path, or click
   **Select Folder** and pick an *empty* directory. This is where the
   encrypted `.c9e` blobs and the `vault.json` / `files.json` metadata will
   live. It can be anywhere — including inside a cloud-synced folder such as
   Dropbox, OneDrive, or Google Drive.
3. Enter a **Master Password** (at least 8 characters) and retype it in
   **Confirm Password**. Choose a long, unique passphrase — anyone who learns
   this password can decrypt everything in the vault. If you forget it,
   **there is no recovery**; the contents are irretrievable.
4. Click **Create**.

CryptoDrive writes `vault.json` into the chosen folder, adds the vault to the
left pane, and selects it. The vault starts in the **locked** state — you
still need to unlock it before you can use it.

## Import an existing vault

Use this when you already have a vault folder — for example one created on
another machine and synced here via Dropbox, or one you unmanaged previously.

1. Click **Import Vault** in the toolbar.
2. Select the existing vault folder (the one containing `vault.json`).
3. Click **OK**.

The vault appears in the left pane in its locked state. Importing does not
require the master password — only unlocking does.

## Unlock and mount

Unlocking a vault derives the key from your master password, decrypts the
stored DEK, and mounts the vault as an OS-visible drive.

1. Select the vault in the left pane.
2. On the right, click **Unlock Vault**.
3. Enter the master password and press **Unlock**.

On success:

- The lock icon flips to the unlocked state.
- The primary button changes to **Reveal Drive**.
- The secondary button changes to **Lock**.
- CryptoDrive mounts the vault at a drive letter (Windows) or path
  (macOS/Linux). The first available letter is chosen automatically unless
  you have pinned one in the vault's settings.

Click **Reveal Drive** at any time to open the mounted drive in your system
file manager (Explorer on Windows, Finder on macOS, your default manager on
Linux). From there, drag, drop, edit, and open files exactly as with any
other drive — every read and write is encrypted in place.

If unlocking fails with *"Incorrect password"*, the password you entered did
not unwrap the DEK. Retry — there is no lockout, but also no hint.

If unlocking succeeds but mounting fails, the status message explains why —
common causes are a drive letter already in use (Windows), a busy mount
directory (macOS/Linux), or a missing FUSE driver. Install WinFSP on Windows
or macFUSE on macOS if this is your first run.

## Browse and use files

Once mounted, the vault behaves like any other drive:

- File managers, editors, IDEs, media players, and backup tools all work
  unmodified.
- Files and folders you see through the mount are the **plaintext view**.
  The vault folder on disk contains only ciphertext blobs named like
  `00/23/29.c9e` — do not edit those directly.
- Partial writes are safe: CryptoDrive encrypts in fixed 32 KiB blocks, so
  appending to a large file only rewrites the affected blocks.

A hidden `lost+found` directory at the drive root may appear on mount. It
contains any encrypted blob that CryptoDrive found on disk but could not
match to an entry in the virtual tree — for example, because a cloud sync
delivered a new blob before the updated metadata. Files inside `lost+found`
are fully readable; move them somewhere else to keep them.

## Configure a vault

With the vault selected, click **Config** (shown when the vault is locked) or
open the vault's settings from the menu. The dialog has three tabs:

### General

- **Volume label** — the name shown for the mounted drive in your file
  manager. Defaults to the vault folder name.
- **Auto-lock** — how long to wait after the last activity before
  automatically locking the vault. Choose a preset (5 / 10 / 15 / 30 / 60 /
  120 minutes) or **Never**.
- **Mount point** —
    - On **Windows**, pick a specific drive letter (`D:` – `Z:`) or leave as
      *Auto* to use the first available letter.
    - On **macOS / Linux**, type or pick a directory to mount at. Leave blank
      to let the OS choose.

Changes in the General tab are saved as soon as the field loses focus or the
selection changes — there is no OK/Cancel.

### Sync

Reserved for cloud-sync provider configuration (Google Drive / OneDrive via
OAuth + PKCE, or an S3-compatible bucket). See [Sync](sync.md) for the
current state and setup steps.

### Security

Change the master password:

1. Enter your **current password** in the first field.
2. Enter the **new password** in the next two fields.
3. Click **Change Password**.

Because the DEK is only *rewrapped* — never rotated — all existing files in
the vault remain readable immediately under the new password. No re-encryption
of file contents takes place.

## Lock a vault

Click **Lock** on the detail view of an unlocked vault. CryptoDrive

1. unmounts the drive,
2. zeroizes the in-memory DEK and FEKs, and
3. flips the vault back to the locked state.

Locking is also triggered automatically when:

- the auto-lock timer elapses (if enabled),
- you quit the application,
- the OS shuts down or signals the process.

At that point the vault folder on disk is once again nothing but opaque
ciphertext.

## Remove a vault from the list

**Remove Vault** in the toolbar takes a vault off the left-pane list and out
of `settings.json`. It **does not delete the vault folder or any files on
disk** — you can re-import it later. The vault must be locked to be removed.

## Quit and restart

CryptoDrive lives in the system tray after you close the main window, keeping
unlocked vaults mounted. Click the tray icon to reopen the main window, or
choose **Quit** from its menu to fully exit — which also locks every unlocked
vault on the way out.

On next launch CryptoDrive reloads its vault list from `settings.json` and
shows every vault in the locked state. You unlock each one as needed.
