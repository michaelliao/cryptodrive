package org.puppylab.cryptodrive.ui.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.crypto.SecretKey;

import org.cryptomator.jfuse.api.Fuse;
import org.puppylab.cryptodrive.core.AppSettings;
import org.puppylab.cryptodrive.core.Vault;
import org.puppylab.cryptodrive.core.VaultConfig;
import org.puppylab.cryptodrive.core.fs.CryptoFileSystem;
import org.puppylab.cryptodrive.core.node.CryptoFs;
import org.puppylab.cryptodrive.util.Base64Utils;
import org.puppylab.cryptodrive.util.EncryptUtils;
import org.puppylab.cryptodrive.util.FileUtils;
import org.puppylab.cryptodrive.util.JsonUtils;
import org.puppylab.cryptodrive.util.MountUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the vault list + current selection and exposes listener hooks for the
 * views. Views read state and register callbacks; the controller re-publishes
 * changes so list/detail views stay in sync.
 */
public class MainController {

    // singleton instance of MainController:
    public static MainController instance = null;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Path                  appPath;
    private final Path                  appSettingsPath;
    private final AppSettings           appSettings;
    private final Map<String, Vault>    vaults                 = new HashMap<>();
    private final Map<String, Fuse>     mounts                 = new HashMap<>();
    private final List<Consumer<Vault>> selectionListeners     = new ArrayList<>();
    private final List<Runnable>        vaultsChangedListeners = new ArrayList<>();
    private Vault                       selected;

    private MainController() {
        this.appPath = FileUtils.getAppDataDir();
        this.appSettingsPath = this.appPath.resolve("settings.json");
        // load AppSettings:
        if (Files.isRegularFile(this.appSettingsPath)) {
            this.appSettings = JsonUtils.fromJson(FileUtils.readString(this.appSettingsPath), AppSettings.class);
        } else {
            this.appSettings = new AppSettings();
        }
        for (String strPath : this.appSettings.vaults) {
            Path path = Path.of(strPath).toAbsolutePath().normalize();
            if (this.vaults.containsKey(path.toString())) {
                logger.warn("ignore duplicate vault path: {}", path);
            } else {
                logger.info("load vault from path: {}", path);
                Vault vault = loadVault(path);
                if (vault != null) {
                    this.vaults.put(path.toString(), vault);
                }
            }
        }
    }

    public AppSettings getAppSettings() {
        return this.appSettings;
    }

    public static MainController init() {
        instance = new MainController();
        return instance;
    }

    private Vault loadVault(Path path) {
        Path vaultConfigPath = path.resolve("vault.json");
        if (!Files.isRegularFile(vaultConfigPath)) {
            logger.error("invalid vault: config not found: {}", vaultConfigPath);
            return null;
        }
        VaultConfig config = JsonUtils.fromJson(FileUtils.readString(vaultConfigPath), VaultConfig.class);
        Vault vault = new Vault(path, config);
        return vault;
    }

    public List<Vault> getVaults() {
        return List.of(vaults.values().toArray(Vault[]::new));
    }

    public Vault getSelected() {
        return selected;
    }

    public void select(Vault vault) {
        if (this.selected == vault)
            return;
        this.selected = vault;
        selectionListeners.forEach(l -> l.accept(vault));
    }

    public void addSelectionListener(Consumer<Vault> listener) {
        selectionListeners.add(listener);
    }

    public void addVaultsChangedListener(Runnable listener) {
        vaultsChangedListeners.add(listener);
    }

    private void fireVaultsChanged() {
        vaultsChangedListeners.forEach(Runnable::run);
    }

    /**
     * Re-publish the current selection to listeners (used after a vault's mutable
     * state — e.g. locked/unlocked — changes but the selection did not).
     */
    public void notifySelectedChanged() {
        selectionListeners.forEach(l -> l.accept(selected));
    }

    /**
     * Remove a vault from the managed list and persist {@code settings.json}. Files
     * under the vault directory are <strong>not</strong> deleted.
     */
    public String removeVault(Vault vault) {
        if (vault == null)
            return "No vault selected.";
        if (!vault.isLocked())
            return "Vault must be locked before removal.";
        String key = vault.getPath().toAbsolutePath().normalize().toString();
        if (vaults.remove(key) == null)
            return "Vault not managed.";
        appSettings.vaults.removeIf(p -> Path.of(p).toAbsolutePath().normalize().toString().equals(key));
        try {
            FileUtils.writeString(appSettingsPath, JsonUtils.toJson(appSettings));
        } catch (RuntimeException e) {
            logger.error("failed to persist settings after remove", e);
        }
        if (this.selected == vault) {
            this.selected = null;
            selectionListeners.forEach(l -> l.accept(null));
        }
        fireVaultsChanged();
        return null;
    }

    /**
     * Create a new vault at {@code path} unlocked with {@code password}. Returns
     * {@code null} on success or a user-facing error message.
     */
    public String createVault(Path path, char[] password) {
        try {
            Path abs = path.toAbsolutePath().normalize();
            if (!Files.isDirectory(abs)) {
                return "Folder does not exist: " + FileUtils.prettyPath(abs);
            }
            Path vaultConfigPath = abs.resolve("vault.json");
            if (Files.exists(vaultConfigPath)) {
                return "A vault already exists in this folder.";
            }
            if (vaults.containsKey(abs.toString())) {
                return "Vault already managed: " + abs;
            }

            VaultConfig config = initVaultConfig(abs, password);
            FileUtils.writeString(vaultConfigPath, JsonUtils.toJson(config));

            Vault vault = new Vault(abs, config);
            vaults.put(abs.toString(), vault);
            appSettings.vaults.add(abs.toString());
            FileUtils.writeString(appSettingsPath, JsonUtils.toJson(appSettings));

            fireVaultsChanged();
            select(vault);
            return null;
        } catch (RuntimeException e) {
            logger.error("createVault failed", e);
            return "Failed to create vault: " + e.getMessage();
        }
    }

    private VaultConfig initVaultConfig(Path path, char[] password) {
        VaultConfig config = new VaultConfig();
        config.volume = path.getFileName().toString();
        config.encryption = new VaultConfig.EncryptionConfig();
        config.encryption.pbeAlg = EncryptUtils.PBE_ALG;
        config.encryption.pbeIterations = 1_000_000;
        config.encryption.aesAlg = EncryptUtils.AES_ALG;

        byte[] salt = EncryptUtils.generateSalt();
        config.encryption.pbeSaltB64 = Base64Utils.b64(salt);

        byte[] dek = EncryptUtils.generateKey();
        byte[] wrapKey = EncryptUtils.derivePbeKey(password, salt, config.encryption.pbeIterations);
        SecretKey kek = EncryptUtils.bytesToAesKey(wrapKey);
        byte[] wrappedDek = EncryptUtils.encrypt(dek, kek);
        config.encryption.encryptedDekB64 = Base64Utils.b64(wrappedDek);

        config.recoveryConfigs = new ArrayList<>();
        return config;
    }

    /**
     * Import an existing vault at {@code path} into the managed list. Returns
     * {@code null} on success or a user-facing error message.
     */
    public String importVault(Path path) {
        try {
            Path abs = path.toAbsolutePath().normalize();
            if (!Files.isDirectory(abs)) {
                return "Folder does not exist: " + FileUtils.prettyPath(abs);
            }
            Path vaultConfigPath = abs.resolve("vault.json");
            if (!Files.isRegularFile(vaultConfigPath)) {
                return "Not a vault folder (vault.json not found).";
            }
            if (vaults.containsKey(abs.toString())) {
                return "Vault already managed: " + abs;
            }
            Vault vault = loadVault(abs);
            if (vault == null) {
                return "Failed to load vault from: " + FileUtils.prettyPath(abs);
            }
            vaults.put(abs.toString(), vault);
            appSettings.vaults.add(abs.toString());
            FileUtils.writeString(appSettingsPath, JsonUtils.toJson(appSettings));

            fireVaultsChanged();
            select(vault);
            return null;
        } catch (RuntimeException e) {
            logger.error("importVault failed", e);
            return "Failed to import vault: " + e.getMessage();
        }
    }

    /**
     * Unlock {@code vault} by deriving the KEK from {@code password} and
     * unwrapping the DEK. Returns {@code null} on success or a user-facing
     * error message (e.g. wrong password).
     */
    public String unlockVault(Vault vault, char[] password) {
        if (vault == null)
            return "No vault selected.";
        if (!vault.isLocked())
            return null;
        try {
            VaultConfig.EncryptionConfig enc = vault.getConfig().encryption;
            byte[] salt = Base64Utils.b64(enc.pbeSaltB64);
            byte[] wrappedDek = Base64Utils.b64(enc.encryptedDekB64);
            byte[] kekBytes = EncryptUtils.derivePbeKey(password, salt, enc.pbeIterations);
            SecretKey kek = EncryptUtils.bytesToAesKey(kekBytes);
            byte[] dek;
            try {
                dek = EncryptUtils.decrypt(wrappedDek, kek);
            } catch (RuntimeException e) {
                logger.info("unlockVault: decryption failed for {}", vault.getPath());
                return "Incorrect password.";
            }
            vault.unlock(EncryptUtils.bytesToAesKey(dek));
            String mountErr = mountVault(vault);
            if (mountErr != null) {
                vault.setLocked();
                return mountErr;
            }
            notifySelectedChanged();
            return null;
        } catch (RuntimeException e) {
            logger.error("unlockVault failed", e);
            return "Failed to unlock vault: " + e.getMessage();
        }
    }

    /**
     * Lock {@code vault}: unmount the FUSE drive if mounted and zeroize the DEK.
     * Returns {@code null} on success or a user-facing error message.
     */
    public String lockVault(Vault vault) {
        if (vault == null)
            return "No vault selected.";
        Fuse fuse = mounts.remove(vaultKey(vault));
        if (fuse != null) {
            try {
                fuse.close();
            } catch (Exception e) {
                logger.warn("failed to unmount {}", vault.getPath(), e);
            }
        }
        vault.setLocked();
        notifySelectedChanged();
        return null;
    }

    private static String vaultKey(Vault vault) {
        return vault.getPath().toAbsolutePath().normalize().toString();
    }

    private String mountVault(Vault vault) {
        List<String> free = MountUtils.listAvailableDriveLetters();
        if (free.isEmpty()) {
            return "No available drive letter to mount.";
        }
        String letter = free.getFirst();
        try {
            CryptoFs cryptoFs = new CryptoFs(vault.getSecretKey(), vault.getPath());
            CryptoFileSystem fs = new CryptoFileSystem(cryptoFs, Fuse.builder().errno());
            Fuse fuse = MountUtils.mount(letter, fs, vault.getName());
            mounts.put(vaultKey(vault), fuse);
            logger.info("mounted vault '{}' at {}", vault.getName(), letter);
            return null;
        } catch (Exception e) {
            logger.error("mount failed for {}", vault.getPath(), e);
            return "Failed to mount at " + letter + ": " + e.getMessage();
        }
    }

    // ── toolbar actions ──────────────────────────────────────────────────────

    public void onNewVault() {
        logger.info("onNewVault: dialog opens from the view");
    }

    public void onImportVault() {
        logger.info("onImportVault: TODO pick existing vault dir");
    }

    public void onSettings() {
        logger.info("onSettings: TODO open settings dialog");
    }

    public void onHelp() {
        logger.info("onHelp: TODO open help / about");
    }
}
