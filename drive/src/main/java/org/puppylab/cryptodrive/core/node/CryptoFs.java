package org.puppylab.cryptodrive.core.node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.SecretKey;

import org.puppylab.cryptodrive.util.FileUtils;
import org.puppylab.cryptodrive.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory file structure tree.
 */
public class CryptoFs {
    final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Dir inode starts from:
     */
    public static final int INODE_DIR_START = 0x01_00_00_00;

    final SecretKey key;
    final Path      rootPath;
    final Path      metaPath;
    final CryptoDir root;

    final AtomicInteger nextFileInode;
    final AtomicInteger nextDirInode;

    public CryptoFs(SecretKey key, Path rootPath) {
        this.key = key;
        this.rootPath = rootPath;
        this.metaPath = rootPath.resolve("files.json");
        this.nextDirInode = new AtomicInteger(INODE_DIR_START);
        this.nextFileInode = new AtomicInteger(1);
        if (!Files.isRegularFile(this.metaPath)) {
            logger.warn("Missing files.json. Create empty meta.");
            try (var _ = new CryptoFsContext(this.key, this.nextDirInode, this.nextFileInode)) {
                var dir = new CryptoDir();
                dir.setName(""); // root has special file name ""
                JsonUtils.writeJson(dir, metaPath);
            }
        }
        try (var _ = new CryptoFsContext(this.key, this.nextDirInode, this.nextFileInode)) {
            Map<Integer, Path> foundFiles = FileUtils.scanC9eFiles(this.rootPath);
            Optional<Integer> max = foundFiles.keySet().stream().max(Integer::compareTo);
            this.nextFileInode.set(1 + max.orElse(0));
            this.root = JsonUtils.readJson(this.metaPath, CryptoDir.class);
            this.root.validate(foundFiles);
            // all unlinked files are put under "/lost+found":
            if (!foundFiles.isEmpty()) {
                CryptoDir lostFound = this.root.findDir("lost+found");
                if (lostFound == null) {
                    lostFound = this.root.createDir("lost+found");
                }
                for (Integer inode : foundFiles.keySet()) {
                    String foundName = String.format("%06x", inode);
                    logger.info("add inode {} to lost+found: {}", inode, foundName);
                    lostFound.addFile(inode, foundName);
                }
            }
        }
    }

    public void save() {
        JsonUtils.writeJson(root, this.metaPath);
    }

    public SecretKey getKey() {
        return key;
    }

    public Path getRootPath() {
        return rootPath;
    }

    public CryptoDir getRoot() {
        return root;
    }

    public CryptoFsContext newContext() {
        return new CryptoFsContext(key, nextDirInode, nextFileInode);
    }

    public void print() {
        System.out.printf("FS: %s\n", this.rootPath);
        this.root.print(0);
    }
}
