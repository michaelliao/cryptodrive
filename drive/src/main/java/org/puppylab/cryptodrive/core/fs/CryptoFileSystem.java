package org.puppylab.cryptodrive.core.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.SecretKey;

import org.cryptomator.jfuse.api.DirFiller;
import org.cryptomator.jfuse.api.Errno;
import org.cryptomator.jfuse.api.FileInfo;
import org.cryptomator.jfuse.api.FuseConfig;
import org.cryptomator.jfuse.api.FuseConnInfo;
import org.cryptomator.jfuse.api.FuseOperations;
import org.cryptomator.jfuse.api.Stat;
import org.cryptomator.jfuse.api.Statvfs;
import org.cryptomator.jfuse.api.TimeSpec;
import org.puppylab.cryptodrive.core.exception.CryptoFsException;
import org.puppylab.cryptodrive.core.node.CryptoDir;
import org.puppylab.cryptodrive.core.node.CryptoFile;
import org.puppylab.cryptodrive.core.node.CryptoFs;
import org.puppylab.cryptodrive.core.node.CryptoNode;
import org.puppylab.cryptodrive.util.CryptoFileUtils;
import org.puppylab.cryptodrive.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FUSE filesystem backed by a {@link CryptoFs} in-memory tree.
 * <p>
 * Scope: basic read / write, mkdir / rmdir, create / open / release, unlink,
 * rename, truncate, getattr, readdir, statfs. Similar feature set to exFAT — no
 * chmod/chown, no xattr, no symlinks, no hard links. {@code utimens} is a no-op
 * so {@code touch}/editor saves don't fail.
 * <p>
 * Every FUSE callback catches {@link Exception} and converts it to an errno —
 * jFUSE will hard-crash the JVM on any uncaught exception thrown out of a
 * native callback ("Unrecoverable uncaught exception encountered. The VM will
 * now exit"), so we must trap RuntimeExceptions (e.g.
 * {@code AEADBadTagException} surfaced via {@code EncryptException}) too, not
 * only {@link IOException}.
 */
public class CryptoFileSystem implements FuseOperations {

    private static final Logger logger = LoggerFactory.getLogger(CryptoFileSystem.class);

    private static final int MODE_DIR  = 0755; // octal integer
    private static final int MODE_FILE = 0644; // octal integer

    private final CryptoFs  cfs;
    private final Errno     errno;
    private final SecretKey dek;
    private final FileStore fileStore;

    private final ConcurrentMap<Long, OpenFileHandle> openFiles     = new ConcurrentHashMap<>();
    private final AtomicLong                          fileHandleGen = new AtomicLong(1L);

    public CryptoFileSystem(CryptoFs cfs, Errno errno) throws IOException {
        this.cfs = cfs;
        this.errno = errno;
        this.dek = cfs.getKey();
        this.fileStore = Files.getFileStore(cfs.getRootPath());
    }

    @Override
    public Errno errno() {
        return errno;
    }

    // ─── lifecycle ─────────────────────────────────────────────────────────

    @Override
    public java.util.Set<Operation> supportedOperations() {
        return java.util.EnumSet.of(Operation.CREATE, Operation.DESTROY, Operation.FLUSH, Operation.FSYNC,
                Operation.FSYNCDIR, Operation.GET_ATTR, Operation.INIT, Operation.MKDIR, Operation.OPEN,
                Operation.OPEN_DIR, Operation.READ, Operation.READ_DIR, Operation.RELEASE, Operation.RELEASE_DIR,
                Operation.RENAME, Operation.RMDIR, Operation.STATFS, Operation.TRUNCATE, Operation.UNLINK,
                Operation.UTIMENS, Operation.WRITE);
    }

    @Override
    public void init(FuseConnInfo conn, FuseConfig cfg) {
        logger.debug("init");
        try {
            conn.setWant(conn.want() | (conn.capable() & FuseConnInfo.FUSE_CAP_BIG_WRITES));
            conn.setMaxBackground(16);
            conn.setCongestionThreshold(4);
        } catch (Exception e) {
            logger.error("init failed", e);
        }
    }

    @Override
    public void destroy() {
        logger.debug("destroy: openFiles={}", openFiles.size());
        try {
            openFiles.forEach((fh, h) -> {
                try {
                    h.channel.close();
                } catch (Exception e) {
                    logger.warn("close fh {} failed", fh, e);
                }
            });
            openFiles.clear();
            cfs.save();
        } catch (Exception e) {
            logger.error("destroy failed", e);
        }
    }

    // ─── statfs / getattr / utimens ────────────────────────────────────────

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
        } catch (Exception e) {
            logger.error("statfs {} failed", path, e);
            return -errno.eio();
        }
    }

    @Override
    public int getattr(String path, Stat stat, FileInfo fi) {
        try (var _ = cfs.newContext()) {
            CryptoNode node = resolveNode(path);
            if (node == null)
                return -errno.enoent();
            if (node instanceof CryptoDir d) {
                fillDirStat(d, stat);
            } else if (node instanceof CryptoFile f) {
                fillFileStat(f, stat);
            } else {
                return -errno.eio();
            }
            return 0;
        } catch (Exception e) {
            logger.error("getattr {} failed", path, e);
            return -errno.eio();
        }
    }

    @Override
    public int utimens(String path, TimeSpec atime, TimeSpec mtime, FileInfo fi) {
        try (var _ = cfs.newContext()) {
            CryptoNode node = resolveNode(path);
            if (node == null)
                return -errno.enoent();
            if (node instanceof CryptoDir d) {
                long now = System.currentTimeMillis();
                atime.getOptional().ifPresentOrElse(t -> d.accessedAt = t.toEpochMilli(), () -> d.accessedAt = now);
                mtime.getOptional().ifPresentOrElse(t -> d.updatedAt = t.toEpochMilli(), () -> d.updatedAt = now);
            }
            return 0;
        } catch (Exception e) {
            logger.error("utimens {} failed", path, e);
            return -errno.eio();
        }
    }

    // ─── directories ───────────────────────────────────────────────────────

    @Override
    public int mkdir(String path, int mode) {
        logger.debug("mkdir path={} mode={}", path, Integer.toOctalString(mode));
        try (var _ = cfs.newContext()) {
            CryptoDir parent = resolveParent(path);
            if (parent == null)
                return -errno.enoent();
            String name = leafName(path);
            try {
                long now = System.currentTimeMillis();
                CryptoDir d = parent.createDir(name);
                d.createdAt = d.updatedAt = d.accessedAt = now;
                parent.updatedAt = now;
                cfs.save();
                return 0;
            } catch (CryptoFsException e) {
                return -errno.eexist();
            }
        } catch (Exception e) {
            logger.error("mkdir {} failed", path, e);
            return -errno.eio();
        }
    }

    @Override
    public int rmdir(String path) {
        logger.debug("rmdir path={}", path);
        try (var _ = cfs.newContext()) {
            CryptoDir parent = resolveParent(path);
            if (parent == null || parent.dirs == null)
                return -errno.enoent();
            String name = leafName(path);
            CryptoDir d = parent.findDir(name);
            if (d == null)
                return -errno.enoent();
            boolean hasDirs = d.dirs != null && !d.dirs.isEmpty();
            boolean hasFiles = d.files != null && !d.files.isEmpty();
            if (hasDirs || hasFiles)
                return -errno.enotempty();
            parent.dirs.remove(d);
            parent.updatedAt = System.currentTimeMillis();
            cfs.save();
            return 0;
        } catch (Exception e) {
            logger.error("rmdir {} failed", path, e);
            return -errno.eio();
        }
    }

    @Override
    public int opendir(String path, FileInfo fi) {
        try (var _ = cfs.newContext()) {
            CryptoNode node = resolveNode(path);
            if (node == null)
                return -errno.enoent();
            if (!(node instanceof CryptoDir))
                return -errno.enotdir();
            return 0;
        } catch (Exception e) {
            logger.error("opendir {} failed", path, e);
            return -errno.eio();
        }
    }

    @Override
    public int readdir(String path, DirFiller filler, long offset, FileInfo fi, int flags) {
        try (var _ = cfs.newContext()) {
            CryptoNode node = resolveNode(path);
            if (node == null)
                return -errno.enoent();
            if (!(node instanceof CryptoDir d))
                return -errno.enotdir();
            filler.fill(".");
            filler.fill("..");
            if (d.dirs != null) {
                for (CryptoDir sub : d.dirs)
                    filler.fill(sub.getName());
            }
            if (d.files != null) {
                for (CryptoFile f : d.files)
                    filler.fill(f.getName());
            }
            d.accessedAt = System.currentTimeMillis();
            return 0;
        } catch (Exception e) {
            logger.error("readdir {} failed", path, e);
            return -errno.eio();
        }
    }

    @Override
    public int releasedir(String path, FileInfo fi) {
        return 0;
    }

    @Override
    public int fsyncdir(String path, int datasync, FileInfo fi) {
        logger.debug("fsyncdir path={} datasync={}", path, datasync);
        try {
            cfs.save();
            return 0;
        } catch (Exception e) {
            logger.error("fsyncdir {} failed", path, e);
            return -errno.eio();
        }
    }

    // ─── files ─────────────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("resource")
    public int create(String path, int mode, FileInfo fi) {
        logger.debug("create path={} mode={}", path, Integer.toOctalString(mode));
        try (var _ = cfs.newContext()) {
            CryptoDir parent = resolveParent(path);
            if (parent == null)
                return -errno.enoent();
            String name = leafName(path);
            if (parent.findDir(name) != null || parent.findFile(name) != null) {
                return -errno.eexist();
            }
            CryptoFile f;
            try {
                f = parent.createFile(name);
            } catch (CryptoFsException e) {
                return -errno.eexist();
            }
            Path pp = physicalPath(f.inode);
            try {
                Files.createDirectories(pp.getParent());
                var init = CryptoFileUtils.generateHeader(dek);
                FileChannel ch = FileChannel.open(pp, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
                CryptoFileUtils.writeHeader(ch, init.header());
                long fh = fileHandleGen.getAndIncrement();
                openFiles.put(fh, new OpenFileHandle(f.inode, ch, init.fek()));
                fi.setFh(fh);
                parent.updatedAt = System.currentTimeMillis();
                cfs.save();
                logger.debug("create path={} inode={} fh={}", path, f.inode, fh);
                return 0;
            } catch (FileAlreadyExistsException e) {
                // inode collision — unlikely but possible
                return -errno.eexist();
            }
        } catch (Exception e) {
            logger.error("create {} failed", path, e);
            return -errno.eio();
        }
    }

    @Override
    @SuppressWarnings("resource")
    public int open(String path, FileInfo fi) {
        logger.debug("open path={}", path);
        try (var _ = cfs.newContext()) {
            CryptoNode node = resolveNode(path);
            if (node == null)
                return -errno.enoent();
            if (!(node instanceof CryptoFile f))
                return -errno.eisdir();
            Path pp = physicalPath(f.inode);
            FileChannel ch;
            SecretKey fek;
            if (Files.isRegularFile(pp)) {
                ch = FileChannel.open(pp, StandardOpenOption.READ, StandardOpenOption.WRITE);
                fek = CryptoFileUtils.readHeader(ch, dek);
            } else {
                // Metadata says it exists but blob is missing — materialize with fresh header.
                Files.createDirectories(pp.getParent());
                var init = CryptoFileUtils.generateHeader(dek);
                ch = FileChannel.open(pp, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
                CryptoFileUtils.writeHeader(ch, init.header());
                fek = init.fek();
            }
            long fh = fileHandleGen.getAndIncrement();
            openFiles.put(fh, new OpenFileHandle(f.inode, ch, fek));
            fi.setFh(fh);
            logger.debug("open path={} inode={} fh={}", path, f.inode, fh);
            return 0;
        } catch (Exception e) {
            logger.error("open {} failed", path, e);
            return -errno.eio();
        }
    }

    @Override
    public int read(String path, ByteBuffer buf, long size, long offset, FileInfo fi) {
        logger.debug("read path={} size={} offset={} fh={}", path, size, offset, fi.getFh());
        OpenFileHandle h = openFiles.get(fi.getFh());
        if (h == null)
            return -errno.ebadf();
        logger.debug("read fh={} inode={}", fi.getFh(), h.inode);
        try {
            long plainLen = CryptoFileUtils.getPlainFileSize(h.channel.size());
            if (offset >= plainLen)
                return 0;
            long toRead = Math.min(size, plainLen - offset);
            int total = 0;
            long pos = offset;
            while (total < toRead) {
                long blockIdx = pos / CryptoFileUtils.BLOCK_PLAIN_SIZE;
                int blockOff = (int) (pos % CryptoFileUtils.BLOCK_PLAIN_SIZE);
                byte[] plain = CryptoFileUtils.readBlock(h.channel, h.fek, blockIdx);
                if (plain.length <= blockOff)
                    break;
                int copy = (int) Math.min(plain.length - blockOff, toRead - total);
                buf.put(plain, blockOff, copy);
                total += copy;
                pos += copy;
            }
            return total;
        } catch (Exception e) {
            logger.error("read {} size={} offset={} fh={} failed", path, size, offset, fi.getFh(), e);
            return -errno.eio();
        }
    }

    @Override
    public int write(String path, ByteBuffer buf, long size, long offset, FileInfo fi) {
        logger.debug("write path={} size={} offset={} fh={}", path, size, offset, fi.getFh());
        OpenFileHandle h = openFiles.get(fi.getFh());
        if (h == null)
            return -errno.ebadf();
        logger.debug("write fh={} inode={}", fi.getFh(), h.inode);
        try {
            int toWrite = (int) size;
            int written = 0;
            long pos = offset;
            while (written < toWrite) {
                long blockIdx = pos / CryptoFileUtils.BLOCK_PLAIN_SIZE;
                int blockOff = (int) (pos % CryptoFileUtils.BLOCK_PLAIN_SIZE);
                int spaceInBlock = CryptoFileUtils.BLOCK_PLAIN_SIZE - blockOff;
                int n = Math.min(spaceInBlock, toWrite - written);

                byte[] existing = CryptoFileUtils.readBlock(h.channel, h.fek, blockIdx);
                int newLen = Math.max(existing.length, blockOff + n);
                byte[] plain = new byte[newLen];
                System.arraycopy(existing, 0, plain, 0, existing.length);
                buf.get(plain, blockOff, n);
                CryptoFileUtils.writeBlock(h.channel, h.fek, blockIdx, plain);

                written += n;
                pos += n;
            }
            return written;
        } catch (Exception e) {
            logger.error("write {} size={} offset={} fh={} failed", path, size, offset, fi.getFh(), e);
            return -errno.eio();
        }
    }

    @Override
    @SuppressWarnings("resource")
    public int truncate(String path, long size, FileInfo fi) {
        logger.debug("truncate path={} size={} fh={}", path, size, fi == null ? -1 : fi.getFh());
        try (var _ = cfs.newContext()) {
            CryptoNode node = resolveNode(path);
            if (node == null)
                return -errno.enoent();
            if (!(node instanceof CryptoFile f))
                return -errno.eisdir();

            OpenFileHandle open = (fi != null) ? openFiles.get(fi.getFh()) : null;
            FileChannel ch;
            SecretKey fek;
            boolean closeAfter;
            if (open != null) {
                ch = open.channel;
                fek = open.fek;
                closeAfter = false;
            } else {
                Path pp = physicalPath(f.inode);
                try {
                    ch = FileChannel.open(pp, StandardOpenOption.READ, StandardOpenOption.WRITE);
                    fek = CryptoFileUtils.readHeader(ch, dek);
                } catch (NoSuchFileException e) {
                    return -errno.enoent();
                }
                closeAfter = true;
            }
            try {
                long curPlain = CryptoFileUtils.getPlainFileSize(ch.size());
                if (size == curPlain) {
                    return 0;
                }
                long lastBlock = size / CryptoFileUtils.BLOCK_PLAIN_SIZE;
                int lastTail = (int) (size % CryptoFileUtils.BLOCK_PLAIN_SIZE);
                if (size < curPlain) {
                    if (lastTail > 0) {
                        byte[] cur = CryptoFileUtils.readBlock(ch, fek, lastBlock);
                        byte[] cut = new byte[lastTail];
                        System.arraycopy(cur, 0, cut, 0, Math.min(cur.length, lastTail));
                        CryptoFileUtils.writeBlock(ch, fek, lastBlock, cut);
                    }
                } else {
                    // extending: fill gap in last block + add zero blocks
                    long curLastIdx = curPlain / CryptoFileUtils.BLOCK_PLAIN_SIZE;
                    int curTail = (int) (curPlain % CryptoFileUtils.BLOCK_PLAIN_SIZE);
                    if (curTail > 0) {
                        byte[] cur = CryptoFileUtils.readBlock(ch, fek, curLastIdx);
                        int fillTo = (curLastIdx == lastBlock) ? lastTail : CryptoFileUtils.BLOCK_PLAIN_SIZE;
                        byte[] grown = new byte[fillTo];
                        System.arraycopy(cur, 0, grown, 0, Math.min(cur.length, fillTo));
                        CryptoFileUtils.writeBlock(ch, fek, curLastIdx, grown);
                    }
                    long startIdx = (curTail > 0) ? curLastIdx + 1 : curLastIdx;
                    byte[] zeroFull = new byte[CryptoFileUtils.BLOCK_PLAIN_SIZE];
                    for (long idx = startIdx; idx < lastBlock; idx++) {
                        CryptoFileUtils.writeBlock(ch, fek, idx, zeroFull);
                    }
                    if (lastTail > 0 && startIdx <= lastBlock) {
                        CryptoFileUtils.writeBlock(ch, fek, lastBlock, new byte[lastTail]);
                    }
                }
                ch.truncate(CryptoFileUtils.getEncryptionFileSize(size));
                return 0;
            } finally {
                if (closeAfter) {
                    try {
                        ch.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            logger.error("truncate {} size={} failed", path, size, e);
            return -errno.eio();
        }
    }

    @Override
    public int flush(String path, FileInfo fi) {
        logger.debug("flush path={} fh={}", path, fi.getFh());
        OpenFileHandle h = openFiles.get(fi.getFh());
        if (h == null)
            return -errno.ebadf();
        try {
            h.channel.force(false);
            return 0;
        } catch (Exception e) {
            logger.error("flush {} failed", path, e);
            return -errno.eio();
        }
    }

    @Override
    public int fsync(String path, int datasync, FileInfo fi) {
        logger.debug("fsync path={} datasync={} fh={}", path, datasync, fi.getFh());
        OpenFileHandle h = openFiles.get(fi.getFh());
        if (h == null)
            return -errno.ebadf();
        try {
            h.channel.force(datasync == 0);
            return 0;
        } catch (Exception e) {
            logger.error("fsync {} failed", path, e);
            return -errno.eio();
        }
    }

    @Override
    public int release(String path, FileInfo fi) {
        logger.debug("release path={} fh={}", path, fi.getFh());
        OpenFileHandle h = openFiles.remove(fi.getFh());
        if (h == null)
            return -errno.ebadf();
        try {
            h.channel.close();
            return 0;
        } catch (Exception e) {
            logger.error("release {} failed", path, e);
            return -errno.eio();
        }
    }

    @Override
    public int unlink(String path) {
        logger.debug("unlink path={}", path);
        try (var _ = cfs.newContext()) {
            CryptoDir parent = resolveParent(path);
            if (parent == null || parent.files == null)
                return -errno.enoent();
            String name = leafName(path);
            CryptoFile f = parent.findFile(name);
            if (f == null)
                return -errno.enoent();
            parent.files.remove(f);
            parent.updatedAt = System.currentTimeMillis();
            try {
                Files.deleteIfExists(physicalPath(f.inode));
            } catch (Exception e) {
                logger.warn("deleting blob for inode {} failed", f.inode, e);
            }
            cfs.save();
            return 0;
        } catch (Exception e) {
            logger.error("unlink {} failed", path, e);
            return -errno.eio();
        }
    }

    @Override
    public int rename(String oldpath, String newpath, int flags) {
        logger.debug("rename old={} new={} flags={}", oldpath, newpath, flags);
        try (var _ = cfs.newContext()) {
            CryptoDir oldParent = resolveParent(oldpath);
            CryptoDir newParent = resolveParent(newpath);
            if (oldParent == null || newParent == null)
                return -errno.enoent();
            String oldName = leafName(oldpath);
            String newName = leafName(newpath);

            CryptoFile movingFile = oldParent.findFile(oldName);
            CryptoDir movingDir = (movingFile == null) ? oldParent.findDir(oldName) : null;
            if (movingFile == null && movingDir == null)
                return -errno.enoent();

            // Collision handling: replace file, fail on dir.
            CryptoFile dstFile = newParent.findFile(newName);
            CryptoDir dstDir = newParent.findDir(newName);
            if (dstDir != null)
                return -errno.eexist();
            if (dstFile != null) {
                if (movingDir != null)
                    return -errno.enotdir();
                newParent.files.remove(dstFile);
                try {
                    Files.deleteIfExists(physicalPath(dstFile.inode));
                } catch (Exception e) {
                    logger.warn("deleting overwritten blob {} failed", dstFile.inode, e);
                }
            }

            if (movingFile != null) {
                oldParent.files.remove(movingFile);
                movingFile.setName(newName);
                movingFile.parent = newParent;
                if (newParent.files == null)
                    newParent.files = new java.util.ArrayList<>();
                newParent.files.add(movingFile);
            } else {
                oldParent.dirs.remove(movingDir);
                movingDir.setName(newName);
                movingDir.parent = newParent;
                if (newParent.dirs == null)
                    newParent.dirs = new java.util.ArrayList<>();
                newParent.dirs.add(movingDir);
            }
            long now = System.currentTimeMillis();
            oldParent.updatedAt = now;
            newParent.updatedAt = now;
            cfs.save();
            return 0;
        } catch (Exception e) {
            logger.error("rename {} -> {} failed", oldpath, newpath, e);
            return -errno.eio();
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private CryptoNode resolveNode(String absPath) {
        if ("/".equals(absPath) || absPath.isEmpty())
            return cfs.getRoot();
        String[] parts = absPath.substring(1).split("/");
        CryptoDir cur = cfs.getRoot();
        for (int i = 0; i < parts.length - 1; i++) {
            CryptoDir next = cur.findDir(parts[i]);
            if (next == null)
                return null;
            cur = next;
        }
        String leaf = parts[parts.length - 1];
        CryptoDir d = cur.findDir(leaf);
        if (d != null)
            return d;
        return cur.findFile(leaf);
    }

    private CryptoDir resolveParent(String absPath) {
        if ("/".equals(absPath))
            return null;
        int cut = absPath.lastIndexOf('/');
        if (cut <= 0)
            return cfs.getRoot();
        return resolveDir(absPath.substring(0, cut));
    }

    private CryptoDir resolveDir(String absPath) {
        if ("/".equals(absPath) || absPath.isEmpty())
            return cfs.getRoot();
        String[] parts = absPath.substring(1).split("/");
        CryptoDir cur = cfs.getRoot();
        for (String p : parts) {
            CryptoDir next = cur.findDir(p);
            if (next == null)
                return null;
            cur = next;
        }
        return cur;
    }

    private static String leafName(String absPath) {
        int i = absPath.lastIndexOf('/');
        return absPath.substring(i + 1);
    }

    private Path physicalPath(int inode) {
        return cfs.getRootPath().resolve(FileUtils.inodeToPath(inode));
    }

    private void fillDirStat(CryptoDir d, Stat stat) {
        stat.setMode(Stat.S_IFDIR | MODE_DIR);
        stat.setNLink((short) 2);
        stat.setSize(0);
        stat.aTime().set(Instant.ofEpochMilli(d.accessedAt));
        stat.mTime().set(Instant.ofEpochMilli(d.updatedAt));
        stat.cTime().set(Instant.ofEpochMilli(d.updatedAt));
        stat.birthTime().set(Instant.ofEpochMilli(d.createdAt));
    }

    private void fillFileStat(CryptoFile f, Stat stat) throws IOException {
        stat.setMode(Stat.S_IFREG | MODE_FILE);
        stat.setNLink((short) 1);
        Path pp = physicalPath(f.inode);
        long plainSize = 0;
        Instant atime = Instant.EPOCH, mtime = Instant.EPOCH, btime = Instant.EPOCH;
        if (Files.isRegularFile(pp)) {
            BasicFileAttributes a = Files.readAttributes(pp, BasicFileAttributes.class);
            plainSize = CryptoFileUtils.getPlainFileSize(a.size());
            atime = a.lastAccessTime().toInstant();
            mtime = a.lastModifiedTime().toInstant();
            btime = a.creationTime().toInstant();
        }
        stat.setSize(plainSize);
        stat.aTime().set(atime);
        stat.mTime().set(mtime);
        stat.cTime().set(mtime);
        stat.birthTime().set(btime);
    }

    // ─── state ─────────────────────────────────────────────────────────────

    /** State kept per open file descriptor. */
    static final class OpenFileHandle {
        final int         inode;
        final FileChannel channel;
        final SecretKey   fek;

        OpenFileHandle(int inode, FileChannel channel, SecretKey fek) {
            this.inode = inode;
            this.channel = channel;
            this.fek = fek;
        }
    }

}
