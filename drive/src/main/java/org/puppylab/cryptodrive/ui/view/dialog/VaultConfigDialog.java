package org.puppylab.cryptodrive.ui.view.dialog;

import java.util.Arrays;

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
import org.puppylab.cryptodrive.util.MountUtils;

/**
 * Modal per-vault configuration dialog with three tabs: General, Sync, Security.
 * All changes in General take effect immediately (no OK/Cancel); the Security
 * tab has its own {@code Change Password} action button.
 */
public class VaultConfigDialog {

    private static final int MIN_PASSWORD_LEN = 8;

    private static final int[]    AUTO_LOCK_MINUTES = { 5, 10, 15, 30, 60, 0 };
    private static final String[] AUTO_LOCK_LABELS  = { "5 minutes", "10 minutes", "15 minutes", "30 minutes",
            "60 minutes", "Never" };

    private final Shell          shell;
    private final Vault          vault;
    private final MainController controller;

    public VaultConfigDialog(Shell parent, Vault vault, MainController controller) {
        this.vault = vault;
        this.controller = controller;
        this.shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        shell.setText("Vault Config — " + vault.getName());
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
            if (!display.readAndDispatch()) display.sleep();
        }
    }

    // ── General ──────────────────────────────────────────────────────────────

    private void buildGeneralTab(TabFolder tabs) {
        TabItem item = new TabItem(tabs, SWT.NONE);
        item.setText("General");

        Composite body = tabComposite(tabs);
        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 14;
        gl.marginHeight = 14;
        gl.horizontalSpacing = 8;
        gl.verticalSpacing = 12;
        body.setLayout(gl);

        VaultConfig cfg = vault.getConfig();

        // Volume label
        label(body, "Volume:");
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
        label(body, "Lock vault when idle for:");
        Combo autoLock = new Combo(body, SWT.READ_ONLY | SWT.DROP_DOWN);
        autoLock.setItems(AUTO_LOCK_LABELS);
        int selIdx = indexOfAutoLock(cfg.autoLock);
        autoLock.select(selIdx);
        GridData ad = new GridData(SWT.FILL, SWT.CENTER, true, false);
        ad.horizontalSpan = 2;
        autoLock.setLayoutData(ad);
        autoLock.addListener(SWT.Selection, _ -> {
            int i = autoLock.getSelectionIndex();
            if (i < 0) return;
            cfg.autoLock = AUTO_LOCK_MINUTES[i];
            controller.saveVaultConfig(vault);
        });

        // Mount point
        label(body, "Mount point:");
        if (MountUtils.IS_WINDOWS) {
            Combo mount = new Combo(body, SWT.READ_ONLY | SWT.DROP_DOWN);
            String[] choices = new String[1 + ('Z' - 'D' + 1)];
            choices[0] = "Auto";
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
                if (i < 0) return;
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
            select.setText("Select");
            select.addListener(SWT.Selection, _ -> {
                DirectoryDialog dd = new DirectoryDialog(shell, SWT.OPEN);
                dd.setText("Choose mount directory");
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

    private static int indexOfAutoLock(int minutes) {
        for (int i = 0; i < AUTO_LOCK_MINUTES.length; i++) {
            if (AUTO_LOCK_MINUTES[i] == minutes) return i;
        }
        return AUTO_LOCK_LABELS.length - 1; // default "Never"
    }

    private static int indexOfMount(String mount, String[] choices) {
        if (mount == null || mount.isBlank()) return 0;
        for (int i = 1; i < choices.length; i++) {
            if (choices[i].equalsIgnoreCase(mount)) return i;
        }
        return 0;
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    private void buildSyncTab(TabFolder tabs) {
        TabItem item = new TabItem(tabs, SWT.NONE);
        item.setText("Sync");

        Composite body = tabComposite(tabs);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 14;
        gl.marginHeight = 14;
        body.setLayout(gl);

        Label placeholder = new Label(body, SWT.NONE);
        placeholder.setText("Cloud sync settings (coming soon).");
        placeholder.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
        placeholder.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        item.setControl(body);
    }

    // ── Security ─────────────────────────────────────────────────────────────

    private void buildSecurityTab(TabFolder tabs) {
        TabItem item = new TabItem(tabs, SWT.NONE);
        item.setText("Security");

        Composite body = tabComposite(tabs);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 14;
        gl.marginHeight = 14;
        gl.horizontalSpacing = 8;
        gl.verticalSpacing = 12;
        body.setLayout(gl);

        label(body, "Old password:");
        Text oldPw = new Text(body, SWT.BORDER | SWT.PASSWORD);
        oldPw.setLayoutData(fillWide());

        label(body, "New password:");
        Text newPw = new Text(body, SWT.BORDER | SWT.PASSWORD);
        newPw.setLayoutData(fillWide());

        label(body, "Confirm password:");
        Text confirmPw = new Text(body, SWT.BORDER | SWT.PASSWORD);
        confirmPw.setLayoutData(fillWide());

        // spacer for the label column so the button aligns under the fields
        new Label(body, SWT.NONE);
        Button change = new Button(body, SWT.PUSH);
        change.setText("Change Password");
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
                    status.setText("Please enter the old password.");
                    return;
                }
                if (n.length < MIN_PASSWORD_LEN) {
                    status.setText("New password must be at least " + MIN_PASSWORD_LEN + " characters.");
                    return;
                }
                if (!Arrays.equals(n, c)) {
                    status.setText("Passwords do not match.");
                    return;
                }
                String err = controller.changeVaultPassword(vault, o, n);
                if (err != null) {
                    status.setText(err);
                    return;
                }
                status.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
                status.setText("Password changed.");
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
