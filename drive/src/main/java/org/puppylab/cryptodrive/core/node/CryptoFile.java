package org.puppylab.cryptodrive.core.node;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

public class CryptoFile extends CryptoNode {

    public int inode; // unique inode

    public CryptoFile() {
    }

    public CryptoFile(int inode, String name) {
        this.inode = inode;
        this.setName(name);
    }

    /**
     * get physical file path.
     */
    public String getPhysicalFilePath() {
        int n = this.inode;
        int i3 = n & 0xff;
        n = n >>> 8;
        int i2 = n & 0xff;
        n = n >>> 8;
        int i1 = n & 0xff;
        String sep = File.pathSeparator;
        return String.format("%s%02x%s%02x%s%02x.c9e", sep, i1, sep, i2, sep, i3);
    }

    @Override
    public void validate(Map<Integer, Path> foundFiles) {
        if (inode < 1 || inode > 0xff_ff_ff) {
            throw new IllegalArgumentException("Invalid file inode: " + inode);
        }
        if (foundFiles.remove(this.inode) != null) {
            // physical file exist.
        }
    }

    @Override
    public void print(int indent) {
        String ss = "  ".repeat(indent);
        System.out.printf("%sF: name=%s, inode=0x%06x\n", ss, this.getName(), this.inode);
    }
}
