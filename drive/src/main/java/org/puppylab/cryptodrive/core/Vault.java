package org.puppylab.cryptodrive.core;

import java.nio.file.Path;

import javax.crypto.SecretKey;

public class Vault {

    final Path        path;
    final VaultConfig config;

    // Keep secret key if vault unlocked:
    SecretKey secretKey = null;

    public Vault(Path path, VaultConfig config) {
        this.path = path;
        this.config = config;
    }

    public boolean isLocked() {
        return secretKey == null;
    }

    public void setLocked() {
        this.secretKey = null;
    }

    public void unlock(SecretKey key) {
        this.secretKey = key;
    }

    public SecretKey getSecretKey() {
        return secretKey;
    }

    public String getName() {
        return this.path.getFileName().toString();
    }

    public Path getPath() {
        return this.path;
    }

    public VaultConfig getConfig() {
        return this.config;
    }

}
