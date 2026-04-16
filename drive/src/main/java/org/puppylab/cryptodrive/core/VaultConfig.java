package org.puppylab.cryptodrive.core;

import java.util.List;

/**
 * Vault config contains all encryption metadata for a vault.
 */
public class VaultConfig {

    /**
     * The volume name. NOTE this is not encrypted and can be displayed when Vault
     * is locked.
     */
    public String volume;

    /**
     * Auto lock when idle for N minutes. Disabled when autoLock <= 0.
     */
    public int autoLock;

    /**
     * Mount path like "/home/ubuntu/mydrive/photo".
     * 
     * Mount path is "D:" ~ "Z:" on Windows, or "" for auto-select unused drive.
     */
    public String mount;

    public SyncConfig           syncConfig;
    public EncryptionConfig     encryption;
    public List<RecoveryConfig> recoveryConfigs;

    public static class EncryptionConfig {

        public String pbeAlg;        // only support "PBKDF2WithHmacSHA256"
        public String pbeSaltB64;    // salt as base64safe
        public int    pbeIterations; // iterations, suggest 1_000_000.
        public String aesAlg;        // only support "AES/GCM/NoPadding"

        public String encryptedDekB64;
    }

    public static class SyncConfig {

        public boolean enabled;

        public String s3Provider;

        public String s3EndpointB64;

        public String s3RegionB64;

        public String s3BucketB64;

        public String s3AccessIdB64;

        public String s3AccessSecretB64;

        public String s3RemotePathB64;

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
