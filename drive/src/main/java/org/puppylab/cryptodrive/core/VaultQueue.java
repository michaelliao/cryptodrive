package org.puppylab.cryptodrive.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Vault message queue for changed files. VaultQueue is serialized to
 * "sync.json"
 */
public class VaultQueue {

    List<ChangedFile> queue = new ArrayList<>();

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
