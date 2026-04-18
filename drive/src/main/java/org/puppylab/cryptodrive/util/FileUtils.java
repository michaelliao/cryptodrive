package org.puppylab.cryptodrive.util;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileUtils {

    static final Logger logger = LoggerFactory.getLogger(FileUtils.class);

    private static final byte[] INVALID_BYTES = "\\/:*?\"<>|`".getBytes(StandardCharsets.UTF_8);

    public static boolean isValidName(String name) {
        if (name == null) {
            return false;
        }
        if (name.isEmpty()) {
            return false;
        }
        if (name.endsWith(".")) {
            return false;
        }
        byte[] utf8Name = name.getBytes(StandardCharsets.UTF_8);
        if (utf8Name.length > 255) {
            return false;
        }
        for (int i = 0; i < utf8Name.length; i++) {
            int b = utf8Name[i];
            if (b < 32) {
                return false;
            }
            for (int j = 0; j < INVALID_BYTES.length; j++) {
                if (b == INVALID_BYTES[j]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Path home = null;

    public static Path getUserHome() {
        if (home == null) {
            home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
        return home;
    }

    public static String prettyPath(Path path) {
        path = path.toAbsolutePath().normalize();
        if (path.equals(home)) {
            return "~";
        }
        if (path.startsWith(home)) {
            return "~" + File.separator + home.relativize(path);
        }
        return path.toString();
    }

    /**
     * Return {@code ~/.cryptodrive}, creating it if missing. Throws if the path
     * exists but is not a directory (e.g. a file named {@code .cryptodrive}).
     */
    public static Path getAppDataDir() {
        Path dir = getUserHome().resolve(".cryptodrive");
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(dir)) {
            throw new IllegalStateException(dir + " exists but is not a directory. Remove it and restart CryptoDrive.");
        }
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return dir;
    }

    public static Path getLogFile() {
        return getAppDataDir().resolve("cryptodrive.log");
    }

    public static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeString(Path path, String str) {
        try {
            Files.writeString(path, str);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Convert inode to file path as "01/f2/b3.c9e".
     */
    public static String inodeToPath(int inode) {
        int i3 = inode & 0xff;
        int i2 = (inode >>> 8) & 0xff;
        int i1 = (inode >>> 16) & 0xff;
        return String.format("%02x/%02x/%02x.c9e", i1, i2, i3);
    }

    /**
     * Scan and return all c9e files as Map:
     * 
     * key: inode as integer; value: full path on local disk.
     */
    public static Map<Integer, Path> scanC9eFiles(Path rootPath) {
        logger.info("scan files under: {}", rootPath);
        Map<Integer, Path> result = new HashMap<>();
        String dirPattern = "[0-9a-f][0-9a-f]";
        String filePattern = "[0-9a-f][0-9a-f].c9e";
        try (DirectoryStream<Path> level1Stream = Files.newDirectoryStream(rootPath, dirPattern)) {
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

    public static long getModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
