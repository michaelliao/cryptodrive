package org.puppylab.cryptodrive.core.node;

import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.SecretKey;

/**
 * A FsContext hold AES key and inode info which can be accessed by current
 * thread.
 */
@SuppressWarnings("resource")
public class CryptoFsContext implements AutoCloseable {

    private static ThreadLocal<CryptoFsContext> current = new ThreadLocal<>();

    private final SecretKey key;
    private AtomicInteger   nextDirInode;
    private AtomicInteger   nextFileInode;

    public CryptoFsContext(SecretKey key, AtomicInteger nextDirInode, AtomicInteger nextFileInode) {
        this.key = key;
        this.nextDirInode = nextDirInode;
        this.nextFileInode = nextFileInode;
        current.set(this);
    }

    public static SecretKey getCurrentKey() {
        return current.get().key;
    }

    public static int allocNextDirInode() {
        return current.get().nextDirInode.getAndIncrement();
    }

    public static int allocNextFileInode() {
        return current.get().nextFileInode.getAndIncrement();
    }

    @Override
    public void close() {
        current.remove();
    }
}
