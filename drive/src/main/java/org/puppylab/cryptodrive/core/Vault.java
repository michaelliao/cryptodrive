package org.puppylab.cryptodrive.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import org.puppylab.cryptodrive.util.FileUtils;
import org.puppylab.cryptodrive.util.JsonUtils;

public class Vault {

    final Path        path;
    final VaultConfig config;

    // Keep secret key if vault unlocked:
    SecretKey secretKey = null;

    // Queue for sync:
    VaultQueue queue     = null;
    Path       queueFile = null;

    // Track sync thread if vault unlocked and sync enabled:
    SyncThread sync = null;

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

    public void addChangedFileToQueue(String action, String relativePath, long timestamp) {
        this.queue.addToQueue(action, relativePath, timestamp);
    }

    public void startSync(SyncThread sync) {
        // init queue:
        this.queueFile = this.path.resolve("sync.json");
        if (Files.isRegularFile(this.queueFile)) {
            this.queue = JsonUtils.fromJson(FileUtils.readString(this.queueFile), VaultQueue.class);
        } else {
            this.queue = new VaultQueue();
            Map<Integer, Path> files = FileUtils.scanC9eFiles(this.path);
            for (Integer inode : files.keySet()) {
                String relativePath = FileUtils.inodeToPath(inode.intValue());
                addChangedFileToQueue("updated", relativePath, //
                        0 // last modified time: does not matter for first sync
                );
            }
        }
        // add vault.json and files.json because they may be changed after vault
        // configuration changed:
        for (String file : List.of("vault.json", "files.json")) {
            addChangedFileToQueue("updated", file, FileUtils.getModifiedTime(this.path.resolve(file)));
        }
        // start thread:
        this.sync = sync;
        this.sync.start();
    }

    public void shutdownSync() {
        // shutdown thread:
        if (this.sync != null) {
            this.sync.shutdown();
            this.sync = null;
        }
        // shutdown queue:
        if (this.queue != null) {
            JsonUtils.writeJson(this.queue, this.queueFile);
            this.queue = null;
        }
        this.queueFile = null;
    }
}
