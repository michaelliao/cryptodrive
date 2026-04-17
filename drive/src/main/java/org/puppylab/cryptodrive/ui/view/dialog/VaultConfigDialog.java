package org.puppylab.cryptodrive.ui.view.dialog;

import static org.puppylab.cryptodrive.util.I18nUtils.i18n;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;

import javax.crypto.SecretKey;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.puppylab.cryptodrive.core.Vault;
import org.puppylab.cryptodrive.core.VaultConfig;
import org.puppylab.cryptodrive.ui.controller.MainController;
import org.puppylab.cryptodrive.util.Base64Utils;
import org.puppylab.cryptodrive.util.EncryptUtils;
import org.puppylab.cryptodrive.util.JsonUtils;
import org.puppylab.cryptodrive.util.MountUtils;
import org.puppylab.cryptodrive.util.S3Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Modal per-vault configuration dialog with three tabs: General, Sync,
 * Security. All changes in General take effect immediately (no OK/Cancel); the
 * Security tab has its own {@code Change Password} action button.
 */
public class VaultConfigDialog {

    final Logger logger = LoggerFactory.getLogger(getClass());

    private static final int MIN_PASSWORD_LEN = 8;

    private static final int[] AUTO_LOCK_MINUTES = { 5, 10, 15, 30, 60, 120, 0 };

    private final Shell          shell;
    private final Vault          vault;
    private final MainController controller;

    public VaultConfigDialog(Shell parent, Vault vault, MainController controller) {
        this.vault = vault;
        this.controller = controller;
        this.shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        shell.setText(i18n("vaultConfig.title", vault.getName()));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 12;
        shell.setLayout(layout);

        TabFolder tabs = new TabFolder(shell, SWT.NONE);
        GridData td = new GridData(SWT.FILL, SWT.FILL, true, true);
        td.widthHint = 420;
        tabs.setLayoutData(td);

        buildGeneralTab(tabs);
        buildSyncTab(tabs);
        buildSecurityTab(tabs);

        shell.pack();
        centerOnParent(parent);
    }

    public void open() {
        shell.open();
        Display display = shell.getDisplay();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch())
                display.sleep();
        }
    }

    // ── General ──────────────────────────────────────────────────────────────

    private void buildGeneralTab(TabFolder tabs) {
        TabItem item = new TabItem(tabs, SWT.NONE);
        item.setText(i18n("tab.general"));

        Composite body = tabComposite(tabs);
        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 14;
        gl.marginHeight = 14;
        gl.horizontalSpacing = 8;
        gl.verticalSpacing = 12;
        body.setLayout(gl);

        VaultConfig cfg = vault.getConfig();

        // Volume label
        label(body, i18n("vaultConfig.volume"));
        Text volumeField = new Text(body, SWT.BORDER);
        GridData vd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        vd.horizontalSpan = 2;
        vd.widthHint = 220;
        volumeField.setLayoutData(vd);
        volumeField.setText(cfg.volume == null ? "" : cfg.volume);
        volumeField.addListener(SWT.FocusOut, _ -> {
            String v = volumeField.getText().trim();
            if (!v.equals(cfg.volume == null ? "" : cfg.volume)) {
                cfg.volume = v;
                controller.saveVaultConfig(vault);
            }
        });

        // Auto-lock
        label(body, i18n("vaultConfig.autoLock"));
        Combo autoLock = new Combo(body, SWT.READ_ONLY | SWT.DROP_DOWN);
        autoLock.setItems(autoLockLabels());
        int selIdx = indexOfAutoLock(cfg.autoLock);
        autoLock.select(selIdx);
        GridData ad = new GridData(SWT.FILL, SWT.CENTER, true, false);
        ad.horizontalSpan = 2;
        autoLock.setLayoutData(ad);
        autoLock.addListener(SWT.Selection, _ -> {
            int i = autoLock.getSelectionIndex();
            if (i < 0)
                return;
            cfg.autoLock = AUTO_LOCK_MINUTES[i];
            controller.saveVaultConfig(vault);
        });

        // Mount point
        label(body, i18n("vaultConfig.mountPoint"));
        if (MountUtils.IS_WINDOWS) {
            Combo mount = new Combo(body, SWT.READ_ONLY | SWT.DROP_DOWN);
            String[] choices = new String[1 + ('Z' - 'D' + 1)];
            choices[0] = i18n("vaultConfig.auto");
            for (int i = 0; i < 'Z' - 'D' + 1; i++) {
                choices[i + 1] = ((char) ('D' + i)) + ":";
            }
            mount.setItems(choices);
            mount.select(indexOfMount(cfg.mount, choices));
            GridData md = new GridData(SWT.FILL, SWT.CENTER, true, false);
            md.horizontalSpan = 2;
            mount.setLayoutData(md);
            mount.addListener(SWT.Selection, _ -> {
                int i = mount.getSelectionIndex();
                if (i < 0)
                    return;
                cfg.mount = i == 0 ? "" : choices[i];
                controller.saveVaultConfig(vault);
            });
        } else {
            Text mountField = new Text(body, SWT.BORDER);
            mountField.setText(cfg.mount == null ? "" : cfg.mount);
            GridData mfd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            mountField.setLayoutData(mfd);
            mountField.addListener(SWT.FocusOut, _ -> {
                String v = mountField.getText().trim();
                if (!v.equals(cfg.mount == null ? "" : cfg.mount)) {
                    cfg.mount = v;
                    controller.saveVaultConfig(vault);
                }
            });
            Button select = new Button(body, SWT.PUSH);
            select.setText(i18n("btn.select"));
            select.addListener(SWT.Selection, _ -> {
                DirectoryDialog dd = new DirectoryDialog(shell, SWT.OPEN);
                dd.setText(i18n("vaultConfig.chooseMountDir"));
                String chosen = dd.open();
                if (chosen != null) {
                    mountField.setText(chosen);
                    cfg.mount = chosen;
                    controller.saveVaultConfig(vault);
                }
            });
        }

        item.setControl(body);
    }

    private static String[] autoLockLabels() {
        String[] labels = new String[AUTO_LOCK_MINUTES.length];
        for (int i = 0; i < AUTO_LOCK_MINUTES.length; i++) {
            int m = AUTO_LOCK_MINUTES[i];
            labels[i] = m == 0 ? i18n("vaultConfig.never") : i18n("vaultConfig.minutes", m);
        }
        return labels;
    }

    private static int indexOfAutoLock(int minutes) {
        for (int i = 0; i < AUTO_LOCK_MINUTES.length; i++) {
            if (AUTO_LOCK_MINUTES[i] == minutes)
                return i;
        }
        return AUTO_LOCK_MINUTES.length - 1; // default "Never" (last entry)
    }

    private static int indexOfMount(String mount, String[] choices) {
        if (mount == null || mount.isBlank())
            return 0;
        for (int i = 1; i < choices.length; i++) {
            if (choices[i].equalsIgnoreCase(mount))
                return i;
        }
        return 0;
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    private static final String   MASKED     = "\u2022\u2022\u2022\u2022\u2022\u2022";
    private static final String[] SYNC_TYPES = { "S3" };

    private void buildSyncTab(TabFolder tabs) {
        TabItem item = new TabItem(tabs, SWT.NONE);
        item.setText(i18n("tab.sync"));

        Composite body = tabComposite(tabs);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 14;
        gl.marginHeight = 14;
        gl.horizontalSpacing = 8;
        gl.verticalSpacing = 10;
        body.setLayout(gl);

        VaultConfig cfg = vault.getConfig();
        if (cfg.syncConfig == null) {
            cfg.syncConfig = new VaultConfig.SyncConfig();
        }
        VaultConfig.SyncConfig syncCfg = cfg.syncConfig;

        // ── Enable sync checkbox ─────────────────────────────────────────
        Button enableSync = new Button(body, SWT.CHECK);
        enableSync.setText(i18n("vaultConfig.sync.enabled"));
        enableSync.setSelection(syncCfg.enabled);
        GridData esd = new GridData(SWT.LEFT, SWT.CENTER, true, false);
        esd.horizontalSpan = 2;
        enableSync.setLayoutData(esd);
        enableSync.addListener(SWT.Selection, _ -> {
            syncCfg.enabled = enableSync.getSelection();
            controller.saveVaultConfig(vault);
        });

        // ── Type combo ───────────────────────────────────────────────────
        label(body, i18n("vaultConfig.sync.type"));
        Combo typeCombo = new Combo(body, SWT.READ_ONLY | SWT.DROP_DOWN);
        typeCombo.setItems(SYNC_TYPES);
        int typeIdx = 0;
        if (syncCfg.type != null) {
            for (int i = 0; i < SYNC_TYPES.length; i++) {
                if (SYNC_TYPES[i].equalsIgnoreCase(syncCfg.type)) {
                    typeIdx = i;
                    break;
                }
            }
        }
        typeCombo.select(typeIdx);
        typeCombo.setLayoutData(fillWide());
        typeCombo.addListener(SWT.Selection, _ -> {
            int i = typeCombo.getSelectionIndex();
            if (i >= 0) {
                syncCfg.type = SYNC_TYPES[i].toLowerCase();
                controller.saveVaultConfig(vault);
            }
        });

        // ── Config section header ────────────────────────────────────────
        Label configHeader = new Label(body, SWT.NONE);
        configHeader.setText(i18n("vaultConfig.sync.config"));
        GridData chd = new GridData(SWT.LEFT, SWT.CENTER, true, false);
        chd.horizontalSpan = 2;
        configHeader.setLayoutData(chd);

        // ── S3 config fields (initially masked) ──────────────────────────
        label(body, i18n("vaultConfig.sync.endpoint"));
        Text endpointField = new Text(body, SWT.BORDER);
        endpointField.setLayoutData(fillWide());
        endpointField.setText(MASKED);
        endpointField.setEditable(false);

        label(body, i18n("vaultConfig.sync.region"));
        Text regionField = new Text(body, SWT.BORDER);
        regionField.setLayoutData(fillWide());
        regionField.setText(MASKED);
        regionField.setEditable(false);

        label(body, i18n("vaultConfig.sync.bucket"));
        Text bucketField = new Text(body, SWT.BORDER);
        bucketField.setLayoutData(fillWide());
        bucketField.setText(MASKED);
        bucketField.setEditable(false);

        label(body, i18n("vaultConfig.sync.accessId"));
        Text accessIdField = new Text(body, SWT.BORDER);
        accessIdField.setLayoutData(fillWide());
        accessIdField.setText(MASKED);
        accessIdField.setEditable(false);

        label(body, i18n("vaultConfig.sync.accessSecret"));
        Text accessSecretField = new Text(body, SWT.BORDER);
        accessSecretField.setLayoutData(fillWide());
        accessSecretField.setText(MASKED);
        accessSecretField.setEditable(false);

        label(body, i18n("vaultConfig.sync.remotePath"));
        Text remotePathField = new Text(body, SWT.BORDER);
        remotePathField.setLayoutData(fillWide());
        remotePathField.setText(MASKED);
        remotePathField.setEditable(false);

        Text[] configFields = { endpointField, regionField, bucketField, accessIdField, accessSecretField,
                remotePathField };

        // ── Buttons row ──────────────────────────────────────────────────
        new Label(body, SWT.NONE); // spacer for label column
        Composite btnRow = new Composite(body, SWT.NONE);
        GridLayout btnLayout = new GridLayout(3, false);
        btnLayout.marginWidth = 0;
        btnLayout.marginHeight = 0;
        btnRow.setLayout(btnLayout);
        btnRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button changeBtn = new Button(btnRow, SWT.PUSH);
        changeBtn.setText(i18n("btn.change"));

        Button testBtn = new Button(btnRow, SWT.PUSH);
        testBtn.setText(i18n("btn.testConnection"));
        testBtn.setVisible(false);

        Button saveBtn = new Button(btnRow, SWT.PUSH);
        saveBtn.setText(i18n("btn.save"));
        saveBtn.setVisible(false);

        // ── Status label ─────────────────────────────────────────────────
        Label status = new Label(body, SWT.NONE);
        GridData sd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sd.horizontalSpan = 2;
        status.setLayoutData(sd);

        // ── Change button: prompt password and decrypt config ─────────
        changeBtn.addListener(SWT.Selection, _ -> {
            status.setText("");
            PasswordInputDialog pwDialog = new PasswordInputDialog(shell, i18n("vaultConfig.sync.enterPassword"));
            char[] password = pwDialog.open();
            if (password == null)
                return;
            try {
                VaultConfig.EncryptionConfig enc = cfg.encryption;
                byte[] salt = Base64Utils.b64(enc.pbeSaltB64);
                byte[] kekBytes = EncryptUtils.derivePbeKey(password, salt, enc.pbeIterations);
                SecretKey kek = EncryptUtils.bytesToAesKey(kekBytes);
                // verify password by unwrapping DEK:
                byte[] wrappedDek = Base64Utils.b64(enc.encryptedDekB64);
                try {
                    EncryptUtils.decrypt(wrappedDek, kek);
                } catch (RuntimeException e) {
                    status.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_RED));
                    status.setText(i18n("vaultConfig.sync.err.wrongPassword"));
                    return;
                }
                // decrypt S3 config if present:
                VaultConfig.S3Config s3 = new VaultConfig.S3Config();
                if (syncCfg.encryptedConfigJsonB64 != null && !syncCfg.encryptedConfigJsonB64.isBlank()) {
                    try {
                        byte[] encrypted = Base64Utils.b64(syncCfg.encryptedConfigJsonB64);
                        byte[] decrypted = EncryptUtils.decrypt(encrypted, kek);
                        s3 = JsonUtils.fromJson(new String(decrypted, StandardCharsets.UTF_8),
                                VaultConfig.S3Config.class);
                    } catch (RuntimeException e) {
                        // corrupted config — start fresh
                        s3 = new VaultConfig.S3Config();
                    }
                }
                // populate fields:
                endpointField.setText(s3.endpoint == null ? "" : s3.endpoint);
                regionField.setText(s3.region == null ? "" : s3.region);
                bucketField.setText(s3.bucket == null ? "" : s3.bucket);
                accessIdField.setText(s3.accessId == null ? "" : s3.accessId);
                accessSecretField.setText(s3.accessSecret == null ? "" : s3.accessSecret);
                remotePathField.setText(s3.remotePath == null ? "" : s3.remotePath);
                for (Text f : configFields)
                    f.setEditable(true);
                changeBtn.setVisible(false);
                testBtn.setVisible(true);
                saveBtn.setVisible(true);
                btnRow.layout();
                // stash kek for save:
                saveBtn.setData(kek);
            } finally {
                Arrays.fill(password, '\0');
            }
        });

        // ── Save button: encrypt and persist config ──────────────────
        saveBtn.addListener(SWT.Selection, _ -> {
            status.setText("");
            SecretKey kek = (SecretKey) saveBtn.getData();
            if (kek == null)
                return;
            VaultConfig.S3Config s3 = new VaultConfig.S3Config();
            s3.endpoint = endpointField.getText().trim();
            s3.region = regionField.getText().trim();
            s3.bucket = bucketField.getText().trim();
            s3.accessId = accessIdField.getText().trim();
            s3.accessSecret = accessSecretField.getText().trim();
            s3.remotePath = remotePathField.getText().trim();

            byte[] json = JsonUtils.toJson(s3).getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = EncryptUtils.encrypt(json, kek);
            syncCfg.encryptedConfigJsonB64 = Base64Utils.b64(encrypted);
            if (syncCfg.type == null || syncCfg.type.isBlank()) {
                syncCfg.type = "s3";
            }
            String err = controller.saveVaultConfig(vault);
            if (err != null) {
                status.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_RED));
                status.setText(err);
            } else {
                status.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
                status.setText(i18n("vaultConfig.sync.msg.saved"));
            }
        });

        // ── Test Connection button ───────────────────────────────────
        testBtn.addListener(SWT.Selection, _ -> {
            status.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
            status.setText(i18n("vaultConfig.sync.msg.testing"));
            body.layout();

            VaultConfig.S3Config s3 = new VaultConfig.S3Config();
            s3.endpoint = endpointField.getText().trim();
            s3.region = regionField.getText().trim();
            s3.bucket = bucketField.getText().trim();
            s3.accessId = accessIdField.getText().trim();
            s3.accessSecret = accessSecretField.getText().trim();
            s3.remotePath = remotePathField.getText().trim();

            Thread.ofVirtual().start(() -> {
                try {
                    String testKey = "__test__.tmp";
                    if (s3.remotePath != null && !s3.remotePath.isEmpty()) {
                        String prefix = S3Utils.normalizeObjectPath(s3.remotePath);
                        testKey = prefix + "/" + testKey;
                    }
                    logger.info("testing s3 connection for key: {}", testKey);
                    String content = "This file is generated at " + LocalDateTime.now().withNano(0).toString()
                            + " and used for test.\nYou can safely delete this file.";
                    try (S3Client client = S3Utils.createS3Client(s3)) {
                        logger.info("test pub object...");
                        client.putObject(PutObjectRequest.builder().bucket(s3.bucket).key(testKey).build(),
                                RequestBody.fromString(content));
                        logger.info("test get object...");
                        client.getObject(GetObjectRequest.builder().bucket(s3.bucket).key(testKey).build()).close();
                        logger.info("test delete object...");
                        client.deleteObject(DeleteObjectRequest.builder().bucket(s3.bucket).key(testKey).build());
                    }
                    shell.getDisplay().asyncExec(() -> {
                        if (status.isDisposed())
                            return;
                        status.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
                        status.setText(i18n("vaultConfig.sync.msg.connectionOk"));
                    });
                } catch (Exception ex) {
                    String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    shell.getDisplay().asyncExec(() -> {
                        if (status.isDisposed())
                            return;
                        status.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_RED));
                        status.setText(i18n("vaultConfig.sync.msg.connectionFailed", msg));
                    });
                }
            });
        });

        item.setControl(body);
    }

    // ── Security ─────────────────────────────────────────────────────────────

    private void buildSecurityTab(TabFolder tabs) {
        TabItem item = new TabItem(tabs, SWT.NONE);
        item.setText(i18n("tab.security"));

        Composite body = tabComposite(tabs);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 14;
        gl.marginHeight = 14;
        gl.horizontalSpacing = 8;
        gl.verticalSpacing = 12;
        body.setLayout(gl);

        label(body, i18n("vaultConfig.oldPassword"));
        Text oldPw = new Text(body, SWT.BORDER | SWT.PASSWORD);
        oldPw.setLayoutData(fillWide());

        label(body, i18n("vaultConfig.newPassword"));
        Text newPw = new Text(body, SWT.BORDER | SWT.PASSWORD);
        newPw.setLayoutData(fillWide());

        label(body, i18n("vaultConfig.confirmPassword"));
        Text confirmPw = new Text(body, SWT.BORDER | SWT.PASSWORD);
        confirmPw.setLayoutData(fillWide());

        // spacer for the label column so the button aligns under the fields
        new Label(body, SWT.NONE);
        Button change = new Button(body, SWT.PUSH);
        change.setText(i18n("btn.changePassword"));
        GridData cd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        cd.widthHint = 160;
        change.setLayoutData(cd);

        Label status = new Label(body, SWT.NONE);
        GridData sd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sd.horizontalSpan = 2;
        status.setLayoutData(sd);

        change.addListener(SWT.Selection, _ -> {
            status.setText("");
            status.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_RED));
            char[] o = oldPw.getText().toCharArray();
            char[] n = newPw.getText().toCharArray();
            char[] c = confirmPw.getText().toCharArray();
            try {
                if (o.length == 0) {
                    status.setText(i18n("vaultConfig.err.oldPasswordEmpty"));
                    return;
                }
                if (n.length < MIN_PASSWORD_LEN) {
                    status.setText(i18n("vaultConfig.err.newPasswordShort", MIN_PASSWORD_LEN));
                    return;
                }
                if (!Arrays.equals(n, c)) {
                    status.setText(i18n("vaultConfig.err.passwordsMismatch"));
                    return;
                }
                String err = controller.changeVaultPassword(vault, o, n);
                if (err != null) {
                    status.setText(err);
                    return;
                }
                status.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
                status.setText(i18n("vaultConfig.msg.passwordChanged"));
                oldPw.setText("");
                newPw.setText("");
                confirmPw.setText("");
            } finally {
                Arrays.fill(o, '\0');
                Arrays.fill(n, '\0');
                Arrays.fill(c, '\0');
            }
        });

        item.setControl(body);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Composite tabComposite(TabFolder tabs) {
        return new Composite(tabs, SWT.NONE);
    }

    private static Label label(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        return l;
    }

    private static GridData fillWide() {
        GridData d = new GridData(SWT.FILL, SWT.CENTER, true, false);
        d.widthHint = 220;
        return d;
    }

    private void centerOnParent(Shell parent) {
        var p = parent.getBounds();
        var s = shell.getBounds();
        shell.setLocation(p.x + (p.width - s.width) / 2, p.y + (p.height - s.height) / 2);
    }
}
