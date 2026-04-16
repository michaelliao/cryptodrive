package org.puppylab.cryptodrive.core.node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.puppylab.cryptodrive.core.exception.CryptoFsException;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class CryptoDir extends CryptoNode {

    /**
     * Dir inode is automatically assigned when load fs, so skip serialize to json
     */
    @JsonIgnore
    public int inode; // unique inode

    public long createdAt;
    public long updatedAt;
    public long accessedAt;

    // null is treat as empty list:
    public List<CryptoDir> dirs = null; // sub dirs
    // null is treat as empty list:
    public List<CryptoFile> files = null; // files

    public CryptoDir() {
        this.inode = CryptoFsContext.allocNextDirInode();
    }

    /**
     * Find dir by name, or null if not found.
     */
    public CryptoDir findDir(String name) {
        if (this.dirs != null) {
            for (CryptoDir dir : this.dirs) {
                if (dir.getName().equalsIgnoreCase(name)) {
                    return dir;
                }
            }
        }
        return null;
    }

    /**
     * Find file by name, or null if not found.
     */
    public CryptoFile findFile(String name) {
        if (this.files != null) {
            for (CryptoFile file : this.files) {
                if (file.getName().equalsIgnoreCase(name)) {
                    return file;
                }
            }
        }
        return null;
    }

    // Used internal to add exist file to lost+found:
    CryptoFile addFile(int inode, String targetName) {
        if (findDir(targetName) != null || findFile(targetName) != null) {
            for (int i = 1;; i++) {
                String rename = targetName + "(" + i + ")";
                if (findDir(rename) != null || findFile(rename) != null) {
                    targetName = rename;
                    break;
                }
            }
        }
        CryptoFile sub = new CryptoFile();
        sub.inode = inode;
        sub.setName(targetName);
        sub.parent = this;
        safeAdd(sub);
        return sub;
    }

    public synchronized CryptoDir createDir(String targetName) {
        if (findDir(targetName) != null || findFile(targetName) != null) {
            throw new CryptoFsException("Name exists.");
        }
        CryptoDir sub = new CryptoDir();
        sub.setName(targetName);
        sub.parent = this;
        safeAdd(sub);
        return sub;
    }

    public synchronized CryptoFile createFile(String targetName) {
        if (findDir(targetName) != null || findFile(targetName) != null) {
            throw new CryptoFsException("Name exists.");
        }
        CryptoFile sub = new CryptoFile();
        sub.inode = CryptoFsContext.allocNextFileInode();
        sub.setName(targetName);
        sub.parent = this;
        safeAdd(sub);
        return sub;
    }

    private void safeAdd(CryptoDir dir) {
        if (this.dirs == null) {
            this.dirs = new ArrayList<>();
        }
        this.dirs.add(dir);
    }

    private void safeAdd(CryptoFile file) {
        if (this.files == null) {
            this.files = new ArrayList<>();
        }
        this.files.add(file);
    }

    @Override
    public void validate(Map<Integer, Path> foundFiles) {
        this.inode = CryptoFsContext.allocNextDirInode();
        if (this.dirs != null) {
            for (CryptoDir dir : dirs) {
                dir.parent = this;
                dir.validate(foundFiles);
            }
        }
        if (this.files != null) {
            for (CryptoFile file : files) {
                file.parent = this;
                file.validate(foundFiles);
            }
        }
    }

    @Override
    public void print(int indent) {
        String ss = "  ".repeat(indent);
        System.out.printf("%sD: name=%s, inode=0x%x\n", ss, this.getName(), this.inode);
        if (this.files != null) {
            this.files.forEach(f -> f.print(indent + 1));
        }
        if (this.dirs != null) {
            this.dirs.forEach(f -> f.print(indent + 1));
        }
    }
}
