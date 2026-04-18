package org.puppylab.cryptodrive.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Vault message queue for changed files. VaultQueue is serialized to
 * "sync.json"
 */
public class VaultQueue {

    public List<ChangedFile> queue = new ArrayList<>();

    public void addToQueue(String action, String path, long timestamp) {
        this.queue.add(new ChangedFile(action, path, timestamp));
    }

    public synchronized void addToQueue(ChangedFile cf) {
        this.queue.add(cf);
    }

    public synchronized ChangedFile fetchFirst() {
        if (queue.isEmpty())
            return null;
        return queue.getFirst();
    }

    public synchronized ChangedFile removeFirst() {
        if (queue.isEmpty())
            return null;
        return queue.removeFirst();
    }

    /**
     * action: "updated", "deleted"
     * 
     * path: "00/0f/b2.c9e"
     * 
     * timestamp: last changed timestamp.
     */
    public static record ChangedFile(String action, String path, long timestamp) {
    }

}
