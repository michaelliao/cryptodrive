package org.puppylab.cryptodrive.core.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.SecretKey;

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
            Map<Integer, Path> foundFiles = scanC9eFiles();
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

    Map<Integer, Path> scanC9eFiles() {
        logger.info("scan files under: {}", this.rootPath);
        Map<Integer, Path> result = new HashMap<>();
        String dirPattern = "[0-9a-f][0-9a-f]";
        String filePattern = "[0-9a-f][0-9a-f].c9e";
        try (DirectoryStream<Path> level1Stream = Files.newDirectoryStream(this.rootPath, dirPattern)) {
            for (Path dir1 : level1Stream) {
                if (Files.isDirectory(dir1)) {
                    int level1 = HexFormat.fromHexDigits(dir1.getFileName().toString());
                    logger.info("scan dir: {}", dir1.getFileName());
                    try (DirectoryStream<Path> level2Stream = Files.newDirectoryStream(dir1, dirPattern)) {
                        for (Path dir2 : level2Stream) {
                            if (Files.isDirectory(dir2)) {
                                int level2 = HexFormat.fromHexDigits(dir2.getFileName().toString());
                                logger.info("scan dir: {}", dir2.getFileName());
                                try (DirectoryStream<Path> level3Stream = Files.newDirectoryStream(dir2, filePattern)) {
                                    for (Path file : level3Stream) {
                                        if (Files.isRegularFile(file)) {
                                            int level3 = HexFormat
                                                    .fromHexDigits(file.getFileName().toString().substring(0, 2));
                                            int inode = (level1 << 16) + (level2 << 8) + level3;
                                            logger.info("scan file: {}, inode: {}", file.getFileName(),
                                                    String.format("%06x", inode));
                                            result.put(inode, file);
                                        } else {
                                            logger.warn("matched entry is not a file: {}", file);
                                        }
                                    }
                                }
                            } else {
                                logger.warn("matched entry is not a directory: {}", dir2);
                            }
                        }
                    }
                } else {
                    logger.warn("matched entry is not a directory: {}", dir1);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    public void save() {
        JsonUtils.writeJson(root, this.metaPath);
    }

    public void print() {
        System.out.printf("FS: %s\n", this.rootPath);
        this.root.print(0);
    }
}
