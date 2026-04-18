package org.puppylab.cryptodrive.core;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.puppylab.cryptodrive.core.VaultConfig.S3Config;
import org.puppylab.cryptodrive.core.VaultQueue.ChangedFile;
import org.puppylab.cryptodrive.util.JsonUtils;
import org.puppylab.cryptodrive.util.S3Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.utils.BinaryUtils;

public class SyncThread extends Thread {

    final Logger logger = LoggerFactory.getLogger(getClass());

    /** Matches vault files we sync: ##/##/##.c9e */
    static final Pattern SYNC_FILE_PATTERN = Pattern.compile("^[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{2}\\.c9e$");

    final Vault    vault;
    final S3Config syncConfig;

    volatile boolean            running     = true;
    volatile String             syncingPath = null;
    private final ReentrantLock syncLock    = new ReentrantLock();

    public SyncThread(Vault vault, S3Config syncConfig) {
        super("sync-" + vault.getName());
        setDaemon(true);
        this.vault = vault;
        this.syncConfig = syncConfig;
    }

    @Override
    public void run() {
        logger.info("sync thread started for vault '{}'", vault.getName());
        try (S3Client s3 = S3Utils.createS3Client(syncConfig)) {
            final VaultQueue queue = vault.queue;
            final Path queueFile = vault.queueFile;
            final String prefix = S3Utils.normalizeObjectPath(syncConfig.remotePath);
            // fetch all remote object meta once at startup:
            final Map<String, String> md5Map = fetchRemoteContentMD5(s3, prefix);
            logger.info("sync: fetched {} remote objects", md5Map.size());
            while (running) {
                ChangedFile cf = queue.fetchFirst();
                if (cf == null) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                try {
                    if (processEntry(s3, cf, prefix, md5Map)) {
                        queue.removeFirst();
                    } else {
                        // process failed, try later:
                        var first = queue.removeFirst();
                        queue.addToQueue(first);
                    }
                    JsonUtils.writeJson(queue, queueFile);
                } catch (Exception e) {
                    logger.error("sync failed for {} {}", cf.action(), cf.path(), e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }
        logger.info("sync thread stopped for vault '{}'", vault.getName());
    }

    /**
     * List all objects under remotePath that match the vault file pattern and
     * return a map of relative-path → contentMD5.
     */
    private Map<String, String> fetchRemoteContentMD5(S3Client s3, String prefix) {
        Map<String, String> map = new HashMap<>();
        String continuationToken = null;
        do {
            var reqBuilder = ListObjectsV2Request.builder().bucket(syncConfig.bucket);
            if (!prefix.isEmpty()) {
                reqBuilder.prefix(prefix);
            }
            if (continuationToken != null) {
                reqBuilder.continuationToken(continuationToken);
            }
            ListObjectsV2Response resp = s3.listObjectsV2(reqBuilder.build());
            for (S3Object obj : resp.contents()) {
                // strip prefix to get relative path:
                String relPath = obj.key();
                if (!prefix.isEmpty() && relPath.startsWith(prefix)) {
                    relPath = relPath.substring(prefix.length());
                }
                if (shouldSync(relPath)) {
                    // eTag is typically "\"md5hex\"" — strip quotes:
                    String eTag = obj.eTag();
                    if (eTag != null) {
                        eTag = eTag.replace("\"", "");
                    }
                    map.put(relPath, eTag);
                }
            }
            continuationToken = resp.isTruncated() ? resp.nextContinuationToken() : null;
        } while (continuationToken != null);
        return map;
    }

    /**
     * Called from FUSE threads before writing or deleting a file. If the sync
     * thread is currently reading the same local file, interrupt it and wait until
     * the file is closed. Returns quickly if no conflict.
     *
     * <p>
     * {@code syncLock} is held by the sync thread only during the local file read
     * (NIO {@code FileChannel} — responds to {@code Thread.interrupt()} with
     * {@code ClosedByInterruptException}). The subsequent S3 network call runs
     * outside the lock so this method never waits on network I/O.
     * </p>
     */
    public void interruptIfSyncing(String relativePath) {
        if (!relativePath.equals(syncingPath)) {
            return;
        }
        logger.info("sync: interrupting read of {} for user write/delete", relativePath);
        this.interrupt();
        // block until the sync thread releases the local file:
        syncLock.lock();
        syncLock.unlock();
    }

    private boolean processEntry(S3Client s3, ChangedFile cf, String prefix, Map<String, String> metaMap) {
        String key = remoteKey(prefix, cf.path());
        logger.info("sync {} file {} to {}...", cf.action(), cf.path(), key);
        switch (cf.action().toLowerCase()) {
        case "updated" -> {
            return processUpdated(s3, cf, key, metaMap);
        }
        case "deleted" -> {
            return processDeleted(s3, cf, key, metaMap);
        }
        default -> {
            logger.warn("sync: unknown action '{}' for {}", cf.action(), cf.path());
            return false;
        }
        }
    }

    private boolean processUpdated(S3Client s3, ChangedFile cf, String key, Map<String, String> metaMap) {
        Path localFile = vault.getPath().resolve(cf.path());
        Path tmpFile = vault.getPath().resolve("upload.tmp");
        // ── phase 1: copy to temp file under lock (interruptible via NIO) ──
        // Both read and write use FileChannel, so Thread.interrupt() causes
        // ClosedByInterruptException immediately — the FUSE thread never waits
        // longer than one buffer cycle.
        String localMD5;
        syncLock.lock();
        try {
            syncingPath = cf.path();
            if (!Files.isRegularFile(localFile)) {
                logger.warn("sync: local file missing, skip upload: {}", cf.path());
                return false;
            }
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            try (InputStream in = Files.newInputStream(localFile);
                    DigestOutputStream out = new DigestOutputStream(Files.newOutputStream(tmpFile), md5)) {
                in.transferTo(out);
            }
            localMD5 = BinaryUtils.toBase64(md5.digest());
        } catch (Exception e) {
            deleteTmpQuietly(tmpFile);
            if (Thread.interrupted()) {
                logger.info("sync: interrupted reading {}, will sync after user write", cf.path());
                return true;
            }
            logger.warn("sync: failed reading {}: {}", cf.path(), e.getMessage());
            return false;
        } finally {
            syncingPath = null;
            syncLock.unlock();
        }
        // ── phase 2: upload temp file to S3 (no lock, no source file handle) ──
        try {
            String remoteMD5 = metaMap.get(cf.path());
            if (remoteMD5 != null && remoteMD5.equals(localMD5)) {
                logger.info("sync: skip upload (md5 unchanged): {}", cf.path());
                return true;
            }
            s3.putObject(PutObjectRequest.builder().bucket(syncConfig.bucket).key(key).contentMD5(localMD5).build(),
                    tmpFile);
            metaMap.put(cf.path(), localMD5);
            return true;
        } catch (Exception e) {
            logger.warn("sync: upload failed for {}: {}", cf.path(), e.getMessage());
            return false;
        } finally {
            deleteTmpQuietly(tmpFile);
        }
    }

    private void deleteTmpQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (Exception e) {
            // ignore
        }
    }

    private boolean processDeleted(S3Client s3, ChangedFile cf, String key, Map<String, String> metaMap) {
        String remoteMD5 = metaMap.get(cf.path());
        if (remoteMD5 == null) {
            logger.info("sync: skip delete (not on remote): {}", cf.path());
            return true;
        }
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(syncConfig.bucket).key(key).build());
            metaMap.remove(cf.path());
            return true;
        } catch (Exception e) {
            logger.warn("sync: delete failed for {}: {}", cf.path(), e.getMessage());
            return false;
        }
    }

    public void shutdown() {
        running = false;
        this.interrupt();
        try {
            this.join();
        } catch (InterruptedException e) {
            logger.error("InterruptedException.", e);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private boolean shouldSync(String relativePath) {
        if (SYNC_FILE_PATTERN.matcher(relativePath).matches()) {
            return true;
        }
        return relativePath.equals("files.json") || relativePath.equals("vault.json");
    }

    private String remoteKey(String prefix, String relativePath) {
        if (prefix.isEmpty()) {
            return relativePath;
        }
        return prefix + "/" + relativePath;
    }
}
