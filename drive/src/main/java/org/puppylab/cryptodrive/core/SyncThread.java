package org.puppylab.cryptodrive.core;

import org.puppylab.cryptodrive.core.VaultConfig.S3Config;
import org.puppylab.cryptodrive.core.VaultQueue.ChangedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SyncThread extends Thread {

    final Logger logger = LoggerFactory.getLogger(getClass());

    final Vault    vault;
    final S3Config syncConfig;

    volatile boolean running = true;

    public SyncThread(Vault vault, S3Config syncConfig) {
        this.vault = vault;
        this.syncConfig = syncConfig;
    }

    @Override
    public void run() {
        final VaultQueue queue = this.vault.queue;
        while (running) {
            ChangedFile cf = queue.fetchFirst();
            // TODO:
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
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

}
