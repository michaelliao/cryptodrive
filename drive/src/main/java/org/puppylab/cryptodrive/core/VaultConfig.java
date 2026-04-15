package org.puppylab.cryptodrive.core;

import java.util.List;

/**
 * Vault config contains all encryption metadata for a vault.
 */
public class VaultConfig {

    /**
     * The volume name or null if not set.
     */
    public String volume;

    /**
     * Mount path like "/home/ubuntu/mydrive/photo".
     * 
     * Mount path is "D:" ~ "Z:" on Windows, or null for auto-select unused drive.
     */
    public String mount;

    public EncryptionConfig     encryption;
    public List<RecoveryConfig> recoveryConfigs;

    public static class EncryptionConfig {

        public String pbeAlg;        // only support "PBKDF2WithHmacSHA256"
        public String pbeSaltB64;    // salt as base64safe
        public int    pbeIterations; // iterations, suggest 1_000_000.
        public String aesAlg;        // only support "AES/GCM/NoPadding"

        public String encryptedDekB64;
    }

    public static class RecoveryConfig {

        public String oauthProvider;
        public String oauthConfigJson;

        public String oauthName;
        public String oauthEmail;

        // random hmac key:
        public String uidSaltB64;

        // AES-key = pbe(hmac-sha256(uid, uidSalt), pbeSalt, pbeIterations)
        public int    pbeIterations;
        public String pbeSaltB64;

        public String encryptedDekB64;
    }
}
