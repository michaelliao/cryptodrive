package org.puppylab.cryptodrive.core.node;

import java.io.File;

public class CryptoFile extends CryptoNode {

    public CryptoFile() {
    }

    public CryptoFile(CryptoDir parent, long inode, String name) {
        this.parent = parent;
        this.inode = inode;
        this.setName(name);
    }

    /**
     * get physical file path from inode.
     */
    public String getPhysicalFilePath() {
        long n = this.inode;
        long i3 = n & 0xff;
        n = n >>> 8;
        long i2 = n & 0xff;
        n = n >>> 8;
        long i1 = n & 0xff;
        String sep = File.pathSeparator;
        return String.format("%s%02x%s%02x%s%02x.c9e", sep, i1, sep, i2, sep, i3);
    }
}
