package org.puppylab.cryptodrive.core;

import javax.crypto.SecretKey;

/**
 * A VaultContext hold AES key which can be accessed by current thread.
 */
public class VaultContext implements AutoCloseable {

    private static ThreadLocal<SecretKey> currentKey = new ThreadLocal<>();

    public VaultContext(SecretKey key) {
        currentKey.set(key);
    }

    public static SecretKey getCurrentKey() {
        return currentKey.get();
    }

    @Override
    public void close() {
        currentKey.remove();
    }
}
