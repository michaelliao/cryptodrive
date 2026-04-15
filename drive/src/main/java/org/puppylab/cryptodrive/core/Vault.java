package org.puppylab.cryptodrive.core;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Vault {

    final Logger logger = LoggerFactory.getLogger(getClass());

    final Path        path;
    final VaultConfig config;
    boolean           locked = true;

    public Vault(Path path, VaultConfig config) {
        this.path = path;
        this.config = config;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public String getName() {
        return this.path.getFileName().toString();
    }

    public Path getPath() {
        return this.path;
    }

}
