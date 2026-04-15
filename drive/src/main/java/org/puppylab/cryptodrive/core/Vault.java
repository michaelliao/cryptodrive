package org.puppylab.cryptodrive.core;

import java.nio.file.Path;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Vault {

    final Logger logger = LoggerFactory.getLogger(getClass());

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
