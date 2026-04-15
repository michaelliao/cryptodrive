package org.puppylab.cryptodrive.core.fs;

import org.cryptomator.jfuse.api.DirFiller;
import org.cryptomator.jfuse.api.Errno;
import org.cryptomator.jfuse.api.FileInfo;
import org.cryptomator.jfuse.api.FileModes;
import org.cryptomator.jfuse.api.FuseConfig;
import org.cryptomator.jfuse.api.FuseConnInfo;
import org.cryptomator.jfuse.api.FuseOperations;
import org.cryptomator.jfuse.api.Stat;
import org.cryptomator.jfuse.api.Statvfs;
import org.cryptomator.jfuse.api.TimeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pass-through FUSE filesystem: every virtual path at the mount point is mapped
 * 1:1 to a file under {@code root}. No encryption. Used as a baseline before
 * wiring the crypto layer in.
 */
public class MirrorFileSystem implements FuseOperations {

    private static final Logger LOG = LoggerFactory.getLogger(MirrorFileSystem.class);

    private final Path root;
    private final Errno errno;
    private final FileStore fileStore;
    private final boolean posix;
    private final ConcurrentMap<Long, FileChannel> openFiles = new ConcurrentHashMap<>();
    private final AtomicLong fileHandleGen = new AtomicLong(1L);

    public MirrorFileSystem(Path root, Errno errno) throws IOException {
        this.root = root;
        this.errno = errno;
        this.fileStore = Files.getFileStore(root);
        this.posix = root.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private Path resolvePath(String absolutePath) {
        var rel = new StringBuilder(absolutePath);
        while (rel.length() > 0 && rel.charAt(0) == '/') {
            rel.deleteCharAt(0);
        }
        return root.resolve(rel.toString());
    }

    @Override
    public Errno errno() {
        return errno;
    }

    @Override
    public Set<Operation> supportedOperations() {
        var ops = EnumSet.of(
                Operation.ACCESS, Operation.CREATE, Operation.DESTROY,
                Operation.FLUSH, Operation.FSYNC, Operation.FSYNCDIR,
                Operation.GET_ATTR, Operation.INIT, Operation.MKDIR,
                Operation.OPEN, Operation.OPEN_DIR, Operation.READ,
                Operation.READ_DIR, Operation.RELEASE, Operation.RELEASE_DIR,
                Operation.RENAME, Operation.RMDIR, Operation.STATFS,
                Operation.TRUNCATE, Operation.UNLINK, Operation.UTIMENS,
                Operation.WRITE, Operation.READLINK);
        if (posix) {
            ops.add(Operation.SYMLINK);
        }
        return ops;
    }

    @Override
    public void init(FuseConnInfo conn, FuseConfig cfg) {
        conn.setWant(conn.want() | (conn.capable() & FuseConnInfo.FUSE_CAP_BIG_WRITES));
        conn.setMaxBackground(16);
        conn.setCongestionThreshold(4);
    }

    @Override
    public void destroy() {
        if (!openFiles.isEmpty()) {
            LOG.warn("Unmounting with {} open file(s); force-closing.", openFiles.size());
        }
        openFiles.forEach((fh, fc) -> {
            try { fc.close(); } catch (IOException e) { LOG.warn("Failed to close fh {}", fh); }
        });
        openFiles.clear();
    }

    @Override
    public int access(String path, int mask) {
        Path node = resolvePath(path);
        Set<AccessMode> modes = EnumSet.noneOf(AccessMode.class);
        if ((mask & 0x01) != 0) modes.add(AccessMode.EXECUTE);
        if ((mask & 0x02) != 0) modes.add(AccessMode.WRITE);
        if ((mask & 0x04) != 0) modes.add(AccessMode.READ);
        try {
            node.getFileSystem().provider().checkAccess(node, modes.toArray(AccessMode[]::new));
            return 0;
        } catch (NoSuchFileException e) {
            return -errno.enoent();
        } catch (AccessDeniedException e) {
            return -errno.eacces();
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int statfs(String path, Statvfs statvfs) {
        try {
            long bsize = 4096L;
            statvfs.setBsize(bsize);
            statvfs.setFrsize(bsize);
            statvfs.setBlocks(fileStore.getTotalSpace() / bsize);
            statvfs.setBavail(fileStore.getUsableSpace() / bsize);
            statvfs.setBfree(fileStore.getUnallocatedSpace() / bsize);
            statvfs.setNameMax(255);
            return 0;
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int getattr(String path, Stat stat, FileInfo fi) {
        Path node = resolvePath(path);
        try {
            BasicFileAttributes attrs = posix
                    ? Files.readAttributes(node, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    : Files.readAttributes(node, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            copyAttrsToStat(attrs, stat);
            return 0;
        } catch (NoSuchFileException e) {
            return -errno.enoent();
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @SuppressWarnings("OctalInteger")
    private void copyAttrsToStat(BasicFileAttributes attrs, Stat stat) {
        if (attrs instanceof PosixFileAttributes p) {
            stat.setPermissions(p.permissions());
        } else if (attrs instanceof DosFileAttributes d) {
            int mode = 0444;
            mode |= d.isReadOnly() ? 0000 : 0200;
            mode |= attrs.isDirectory() ? 0111 : 0000;
            stat.setMode(mode);
        }
        stat.setSize(attrs.size());
        stat.setNLink((short) 1);
        if (attrs.isDirectory()) {
            stat.setModeBits(Stat.S_IFDIR);
            stat.setNLink((short) 2);
        } else if (attrs.isSymbolicLink()) {
            stat.setModeBits(Stat.S_IFLNK);
        } else if (attrs.isRegularFile()) {
            stat.setModeBits(Stat.S_IFREG);
        }
        stat.aTime().set(attrs.lastAccessTime().toInstant());
        stat.mTime().set(attrs.lastModifiedTime().toInstant());
        stat.cTime().set(attrs.lastAccessTime().toInstant());
        stat.birthTime().set(attrs.creationTime().toInstant());
    }

    @Override
    public int utimens(String path, TimeSpec atime, TimeSpec mtime, FileInfo fi) {
        Path node = resolvePath(path);
        var view = Files.getFileAttributeView(node, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        var mTime = mtime.getOptional().map(FileTime::from).orElse(null);
        var aTime = atime.getOptional().map(FileTime::from).orElse(null);
        try {
            view.setTimes(mTime, aTime, null);
            return 0;
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int mkdir(String path, int mode) {
        Path node = resolvePath(path);
        try {
            if (posix) {
                Files.createDirectory(node, PosixFilePermissions.asFileAttribute(FileModes.toPermissions(mode)));
            } else {
                Files.createDirectory(node);
            }
            return 0;
        } catch (FileAlreadyExistsException e) {
            return -errno.eexist();
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int rmdir(String path) {
        Path node = resolvePath(path);
        if (!Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS)) {
            return -errno.enotdir();
        }
        try {
            Files.delete(node);
            return 0;
        } catch (NoSuchFileException e) {
            return -errno.enoent();
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int opendir(String path, FileInfo fi) {
        Path node = resolvePath(path);
        return Files.isDirectory(node) ? 0 : -errno.enotdir();
    }

    @Override
    public int readdir(String path, DirFiller filler, long offset, FileInfo fi, int flags) {
        Path node = resolvePath(path);
        try (var ds = Files.newDirectoryStream(node)) {
            filler.fill(".");
            filler.fill("..");
            for (var child : ds) {
                filler.fill(child.getFileName().toString());
            }
            return 0;
        } catch (NotDirectoryException e) {
            return -errno.enotdir();
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int releasedir(String path, FileInfo fi) {
        return 0;
    }

    @Override
    public int fsyncdir(String path, int datasync, FileInfo fi) {
        return 0;
    }

    @Override
    public int create(String path, int mode, FileInfo fi) {
        Path node = resolvePath(path);
        try {
            var fc = posix
                    ? FileChannel.open(node, fi.getOpenFlags(), PosixFilePermissions.asFileAttribute(FileModes.toPermissions(mode)))
                    : FileChannel.open(node, fi.getOpenFlags());
            registerHandle(fi, fc);
            return 0;
        } catch (FileAlreadyExistsException e) {
            return -errno.eexist();
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int open(String path, FileInfo fi) {
        Path node = resolvePath(path);
        try {
            var fc = FileChannel.open(node, fi.getOpenFlags());
            registerHandle(fi, fc);
            return 0;
        } catch (NoSuchFileException e) {
            return -errno.enoent();
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    private void registerHandle(FileInfo fi, FileChannel fc) {
        long fh = fileHandleGen.incrementAndGet();
        fi.setFh(fh);
        openFiles.put(fh, fc);
    }

    @Override
    public int read(String path, ByteBuffer buf, long size, long offset, FileInfo fi) {
        var fc = openFiles.get(fi.getFh());
        if (fc == null) return -errno.ebadf();
        try {
            int read = 0;
            int toRead = (int) Math.min(size, buf.limit());
            while (read < toRead) {
                int r = fc.read(buf, offset + read);
                if (r == -1) break;
                read += r;
            }
            return read;
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int write(String path, ByteBuffer buf, long size, long offset, FileInfo fi) {
        var fc = openFiles.get(fi.getFh());
        if (fc == null) return -errno.ebadf();
        try {
            int written = 0;
            int toWrite = (int) Math.min(size, buf.limit());
            while (written < toWrite) {
                written += fc.write(buf, offset + written);
            }
            return written;
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int truncate(String path, long size, FileInfo fi) {
        Path node = resolvePath(path);
        try (FileChannel fc = FileChannel.open(node, StandardOpenOption.WRITE)) {
            fc.truncate(size);
            return 0;
        } catch (NoSuchFileException e) {
            return -errno.enoent();
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int flush(String path, FileInfo fi) {
        var fc = openFiles.get(fi.getFh());
        if (fc == null) return -errno.ebadf();
        try {
            fc.force(false);
            return 0;
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int fsync(String path, int datasync, FileInfo fi) {
        var fc = openFiles.get(fi.getFh());
        if (fc == null) return -errno.ebadf();
        try {
            fc.force(datasync == 0);
            return 0;
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int release(String path, FileInfo fi) {
        var fc = openFiles.remove(fi.getFh());
        if (fc == null) return -errno.ebadf();
        try {
            fc.close();
            return 0;
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int unlink(String path) {
        Path node = resolvePath(path);
        if (Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS)) {
            return -errno.eisdir();
        }
        try {
            Files.delete(node);
            return 0;
        } catch (NoSuchFileException e) {
            return -errno.enoent();
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int rename(String oldpath, String newpath, int flags) {
        try {
            Files.move(resolvePath(oldpath), resolvePath(newpath), StandardCopyOption.REPLACE_EXISTING);
            return 0;
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int symlink(String linkname, String target) {
        try {
            Files.createSymbolicLink(resolvePath(linkname), Path.of(target));
            return 0;
        } catch (IOException e) {
            return -errno.eio();
        }
    }

    @Override
    public int readlink(String path, ByteBuffer buf, long len) {
        try {
            var target = Files.readSymbolicLink(resolvePath(path));
            buf.put(StandardCharsets.UTF_8.encode(target.toString())).put((byte) 0);
            return 0;
        } catch (BufferOverflowException e) {
            return -errno.enomem();
        } catch (NoSuchFileException e) {
            return -errno.enoent();
        } catch (IOException e) {
            return -errno.eio();
        }
    }

}
