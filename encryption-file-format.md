Here is the crypto file system design:

Suppose the crypto file system mounted at "R:" has files in user's view:

```
R:
├─ NOTES.txt
├─ README.txt
│
├─images
│   ├─ a.jpg
│   └─ b.jpg
│
└─lost+found
```

A `<vault>\files.json` is used to describe the entire crypto file system:

```json
{
    "inode": 2,
    "createdAt": 123450000,
    "updatedAt": 123450000,
    "accessedAt": 123450000,
    "encryptedNameB64": "QkFTRTY0RU5DT0RFRE5BTUU=",
    "dirs": [
        {
            "inode": 100,
            "createdAt": 123450000,
            "updatedAt": 123450000,
            "accessedAt": 123450000,
            "encryptedNameB64": "RGlyZWN0b3J5MQ==",
            "dirs": [],
            "files": [
                {
                    "inode": 101,
                    "encryptedNameB64": "RmlTeAPaeAAsZTE="
                },
                {
                    "inode": 102,
                    "encryptedNameB64": "ANhl7eVDRmlsZTI="
                }
            ]
        },
        {
            "inode": 11,
            "createdAt": 123450000,
            "updatedAt": 123450000,
            "accessedAt": 123450000,
            "encryptedNameB64": "Scf40b3J5RGlyZWNMQ==",
            "dirs": [],
            "files": []
        }
    ],
    "files": [
        {
            "inode": 9001,
            "encryptedNameB64": "W24rRmlTeAAsZTE="
        },
        {
            "inode": 1009002,
            "encryptedNameB64": "Sz9oAeVDRmlsZTI="
        }
    ]
}
```

inodes are unique. "encryptedNameB64" is file or dir name that encrypted by vault's DEK.

Here is how an inode of file map to physicial file path:

inode 101 = 0x65 => <vault>/00/00/65.c9e
inode 102 = 0x66 => <vault>/00/00/66.c9e
inode 9001 = 0x2329 => <vault>/00/23/29.c9e
inode 1009002 = 0xf656a => <vault>/0f/65/6a.c9e

That is to say, we only use file inode to locate the encryption file in vault. Create dir, rename, move files are NOT trigger real disk operation but only change the `files.json` content.

When user want to read "/README.txt", we found name (decrypted in memory) and locate its inode=9001, so the physical file can be found at <vault>/00/23/29.c9e.

Only the dir has attributes of "createdAt", "updatedAt" and "accessedAt". File attributes can be read from the physical file.

# File Encryption

Here is how file encrypted:

- Plain file: user's file data, unencrypted;
- Encryption file: encrypted file stores as physical file in vault's local folder;
- Virtual file: a file node with inode and encrypted filename in the `<vault>/files.json`.

Each plain file is encrypted with a random AES key, let's call it FEK (File Encryption Key);
FEK is encrypted by vault's DEK and stores as the encryption file header:

```
┌─┬────┬────────┬──────┐
│4│ 12 │   32   │  16  │
└─┴────┴────────┴──────┘
```

The encryption file header is 64 bytes:

- 4 bytes version: 0x01000000 = 1 (little endian)
- 12 bytes IV;
- 32 bytes encrypted FEK;
- 16 bytes tag.

The plain file is splitted into multiple blocks, each block size is 32K (the last block size may less than 32K).

Each block is encrypted as EncryptedBlock:

```
┌─┬────┬───────────────────┬──────┐
│4│ 12 │        32K        │  16  │
└─┴────┴───────────────────┴──────┘
```

The EncryptedBlock has:

- 4 bytes metadata: (current = 0x00000000)
- 12 bytes IV;
- 32K encrypted data;
- 16 bytes tag.

So a 32K plain data is encrypted to 32K + 32 = 32800 bytes as a EncryptedBlock.

The last EncryptedBlock size may less than 32800.

### Plain File

From the user's perspective the plain file is stores as '32K' allocation unit. Any read/write operation handles 32K as unit.

How to get the plain file size:

If the encryption file has a size of encLength:

```
static final long ENC_BLOCK_SIZE = 32 * 1024 + 32;

long getPlainFileSize(long encryptionFileLength) {
    if (encryptionFileLength > 0 && encryptionFileLength < 64) {
        throw error;
    }
    long lenWithoutHeader = encryptionFileLength - 64;
    if (lenWithoutHeader == 0) {
        return 0;
    }
    long numFullEncBlocks = lenWithoutHeader / ENC_BLOCK_SIZE;
    long lenLastEncBlock = lenWithoutHeader % ENC_BLOCK_SIZE;
    if (lenLastEncBlock < 32) {
        throw error;
    }
    long plainFileLength = 32768 * numFullEncBlocks + (lenLastEncBlock - 32)
    return plainFileLength;
}
```

### Special case

If the encryption file is not exist or has a length of zero: then the plain file's size is zero.
