package org.puppylab.cryptodrive.core;

import org.puppylab.cryptodrive.util.EncryptUtils;

public interface Constants {

    long INODE_ROOT       = 2;
    long INODE_LOST_FOUND = 11;

    long INODE_MAX = 0xff_ff_ff; // 16777215

    String VAULT_FILE_EXT = ".c9e";            // shortcut for ".cryptodrive"
    String VAULT_CONF     = "cryptodrive.conf";
    String VAULT_META     = "cryptodrive.meta";

    int FILE_HEADER = 64; // 4 bytes version + 12 bytes iv + (16+32) bytes encrypted kek.

    int BLOCK_PLAIN_DATA_SIZE  = 32 * 1024;                                             // 32K data size
    int BLOCK_ENCYPT_DATA_SIZE = 4 + EncryptUtils.AES_IV_BYTES + BLOCK_PLAIN_DATA_SIZE; // 4 + 12 + 32K

}
