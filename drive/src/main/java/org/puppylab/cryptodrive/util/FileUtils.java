package org.puppylab.cryptodrive.util;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileUtils {

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

}
