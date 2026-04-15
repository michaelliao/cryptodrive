package org.puppylab.cryptodrive.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Pattern;

import org.cryptomator.jfuse.api.Fuse;
import org.cryptomator.jfuse.api.FuseOperations;

public class MountUtils {

    private static final boolean IS_WINDOWS   = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Pattern DRIVE_LETTER = Pattern.compile("^[D-Z]:$");

    /**
     * Mount a FUSE filesystem.
     * <ul>
     * <li>Linux/macOS: {@code mountPoint} must be an existing empty directory, e.g.
     * {@code /home/ubuntu/hello-fs/}.</li>
     * <li>Windows (WinFSP): {@code mountPoint} must be an unused drive letter, e.g.
     * {@code Z:}.</li>
     * </ul>
     * The returned {@link Fuse} handle must be closed to unmount.
     */
    public static Fuse mount(String mountPoint, FuseOperations fs, String volumeName, String... flags) {
        if (mountPoint == null || mountPoint.isBlank()) {
            throw new IllegalArgumentException("mountPoint is required");
        }
        Path target;
        try {
            target = resolveMountPoint(mountPoint);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var all = new ArrayList<String>();
        if (IS_WINDOWS) {
            all.add("-o");
            all.add("uid=-1");
            all.add("-o");
            all.add("gid=-1");
            all.add("-o");
            all.add("FileSystemName=CryptoDrive");
            all.add("-o");
            all.add("volname=" + volumeName);
        } else {
            all.add("-s"); // single-threaded default
        }
        Collections.addAll(all, flags);
        var builder = Fuse.builder();
        Fuse fuse = builder.build(fs);
        try {
            fuse.mount(volumeName, target, all.toArray(String[]::new));
            return fuse;
        } catch (RuntimeException e) {
            try {
                fuse.close();
            } catch (Exception ignored) {
            }
            throw e;
        } catch (Exception e) {
            try {
                fuse.close();
            } catch (Exception ignored) {
            }
            throw new RuntimeException(e);
        }
    }

    private static Path resolveMountPoint(String mountPoint) throws IOException {
        if (IS_WINDOWS) {
            if (!DRIVE_LETTER.matcher(mountPoint).matches()) {
                throw new IllegalArgumentException(
                        "Windows mountPoint must be a drive letter like 'Z:' but got: " + mountPoint);
            }
            // WinFSP expects the bare drive letter form, e.g. "Z:".
            String letter = mountPoint.toUpperCase();
            return Path.of(letter);
        } else {
            Path dir = Path.of(mountPoint);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            } else if (!Files.isDirectory(dir)) {
                throw new IllegalArgumentException("mountPoint exists and is not a directory: " + mountPoint);
            }
            return dir;
        }
    }
}
