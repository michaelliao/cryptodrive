package org.puppylab.cryptodrive.ui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.puppylab.cryptodrive.core.Vault;
import org.puppylab.cryptodrive.ui.Icons;
import org.puppylab.cryptodrive.ui.controller.MainController;
import org.puppylab.cryptodrive.ui.view.dialog.UnlockVaultDialog;
import org.puppylab.cryptodrive.util.FileUtils;

/**
 * Right-pane detail view: ┌────────────────────────────────────────┐ │ [🔒]
 * Name (bold 120%) │ │ path (dim) │ │ ───────────────────────────────────── │ │
 * │ │ ┌──────────────────┐ │ │ │ Unlock Vault │ (120% font) │
 * └──────────────────┘ │ │ [ Options ] │ (or [ Lock ] when unlocked) │ │
 * └────────────────────────────────────────┘
 */
public class VaultDetailView {

    private static final int ICON_SIZE = 32;

    private final Composite      root;
    private final Canvas         stateIcon;
    private final Label          nameLabel;
    private final Label          pathLabel;
    private final Button         primaryButton;
    private final Button         secondaryButton;
    private final Font           nameFont;
    private final Font           bigFont;
    private final MainController controller;
    private Vault                current;

    public VaultDetailView(Composite parent, MainController controller) {
        this.controller = controller;
        Display display = parent.getDisplay();

        this.root = new Composite(parent, SWT.NONE);
        GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.marginWidth = 16;
        rootLayout.marginHeight = 16;
        rootLayout.verticalSpacing = 12;
        root.setLayout(rootLayout);

        // ── header: state icon + name / path ────────────────────────────────
        Composite header = new Composite(root, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout hLayout = new GridLayout(2, false);
        hLayout.marginWidth = 0;
        hLayout.marginHeight = 0;
        hLayout.horizontalSpacing = 10;
        header.setLayout(hLayout);

        stateIcon = new Canvas(header, SWT.NONE);
        GridData iconData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        iconData.widthHint = ICON_SIZE;
        iconData.heightHint = ICON_SIZE;
        stateIcon.setLayoutData(iconData);
        stateIcon.addListener(SWT.Paint, e -> {
            if (current == null)
                return;
            Image img = Icons.get(current.isLocked() ? "state-locked" : "state-unlocked");
            if (img == null)
                return;
            Rectangle b = img.getBounds();
            e.gc.drawImage(img, 0, 0, b.width, b.height, 0, 0, ICON_SIZE, ICON_SIZE);
        });

        Composite textBlock = new Composite(header, SWT.NONE);
        textBlock.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout tLayout = new GridLayout(1, false);
        tLayout.marginWidth = 0;
        tLayout.marginHeight = 0;
        tLayout.verticalSpacing = 4;
        textBlock.setLayout(tLayout);

        nameFont = buildScaledFont(display, root.getFont(), 1.2, true);
        nameLabel = new Label(textBlock, SWT.NONE);
        nameLabel.setFont(nameFont);
        nameLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        pathLabel = new Label(textBlock, SWT.NONE);
        pathLabel.setForeground(display.getSystemColor(SWT.COLOR_DARK_GRAY));
        pathLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // ── separator ───────────────────────────────────────────────────────
        Label sep = new Label(root, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // ── center action area ──────────────────────────────────────────────
        Composite center = new Composite(root, SWT.NONE);
        center.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, true));
        GridLayout cLayout = new GridLayout(1, false);
        cLayout.horizontalSpacing = 0;
        cLayout.verticalSpacing = 14;
        center.setLayout(cLayout);

        bigFont = buildScaledFont(display, root.getFont(), 1.2, true);
        primaryButton = new Button(center, SWT.PUSH);
        primaryButton.setFont(bigFont);
        primaryButton.setImage(Icons.get("drive"));
        GridData pbData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        pbData.widthHint = 200;
        pbData.heightHint = 64;
        primaryButton.setLayoutData(pbData);
        primaryButton.addListener(SWT.Selection, _ -> onPrimary());

        secondaryButton = new Button(center, SWT.PUSH);
        GridData sbData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        sbData.widthHint = 180;
        sbData.heightHint = 36;
        secondaryButton.setLayoutData(sbData);
        secondaryButton.addListener(SWT.Selection, _ -> onSecondary());

        root.addListener(SWT.Dispose, _ -> {
            nameFont.dispose();
            bigFont.dispose();
        });

        controller.addSelectionListener(this::update);
        update(controller.getSelected());
    }

    private static Font buildScaledFont(Display d, Font base, double scale, boolean bold) {
        FontData[] fd = base.getFontData();
        for (FontData f : fd) {
            f.setHeight((int) Math.round(f.getHeight() * scale));
            if (bold)
                f.setStyle(f.getStyle() | SWT.BOLD);
        }
        return new Font(d, fd);
    }

    private void update(Vault vault) {
        if (root.isDisposed())
            return;
        this.current = vault;
        if (vault == null) {
            nameLabel.setText("");
            pathLabel.setText("");
            setVisible(primaryButton, false);
            setVisible(secondaryButton, false);
        } else {
            nameLabel.setText(vault.getName());
            pathLabel.setText(FileUtils.prettyPath(vault.getPath()));
            if (vault.isLocked()) {
                primaryButton.setText("Unlock Vault");
                secondaryButton.setText("Options");
                secondaryButton.setImage(Icons.get("settings"));
            } else {
                primaryButton.setText("Reveal Drive");
                secondaryButton.setText("Lock");
                secondaryButton.setImage(Icons.get("lock"));
            }
            setVisible(primaryButton, true);
            setVisible(secondaryButton, true);
        }
        stateIcon.redraw();
        root.layout(true, true);
    }

    private static void setVisible(Button b, boolean visible) {
        b.setVisible(visible);
        GridData gd = (GridData) b.getLayoutData();
        gd.exclude = !visible;
    }

    private void onPrimary() {
        if (current == null)
            return;
        if (current.isLocked()) {
            UnlockVaultDialog dialog = new UnlockVaultDialog(root.getShell(), current.getName());
            dialog.open(pw -> controller.unlockVault(current, pw));
        } else {
            // TODO: open mount point in OS file explorer
        }
        update(current);
    }

    private void onSecondary() {
        if (current == null)
            return;
        if (current.isLocked()) {
            // TODO: open vault options dialog
        } else {
            // TODO: unmount + zeroize DEK
            current.setLocked();
            controller.notifySelectedChanged();
        }
        update(current);
    }

    public Composite getControl() {
        return root;
    }
}
