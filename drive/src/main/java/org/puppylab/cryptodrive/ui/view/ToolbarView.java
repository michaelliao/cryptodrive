package org.puppylab.cryptodrive.ui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.puppylab.cryptodrive.ui.Icons;
import org.puppylab.cryptodrive.ui.controller.MainController;
import org.puppylab.cryptodrive.ui.view.dialog.NewVaultDialog;

/**
 * Top toolbar: [New Vault] [Import Vault] [Settings] [Help]. Wraps the
 * {@link ToolBar} in a padded {@link Composite}.
 */
public class ToolbarView {

    private static final int MARGIN_H = 8;
    private static final int MARGIN_V = 8;

    private final Composite container;
    private final ToolBar   toolbar;

    public ToolbarView(Composite parent, MainController controller) {
        this.container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = MARGIN_H;
        layout.marginHeight = MARGIN_V;
        layout.horizontalSpacing = 0;
        layout.verticalSpacing = 0;
        container.setLayout(layout);

        this.toolbar = new ToolBar(container, SWT.FLAT | SWT.WRAP | SWT.RIGHT);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        addItem("New Vault", "add", _ -> openNewVaultDialog(controller));
        addItem("Import Vault", "import", _ -> controller.onImportVault());
        addItem("Settings", "settings", _ -> controller.onSettings());
        addItem("Help", "help", _ -> controller.onHelp());
    }

    private void addItem(String label, String iconName, org.eclipse.swt.widgets.Listener onClick) {
        ToolItem item = new ToolItem(toolbar, SWT.PUSH);
        item.setText(label);
        item.setImage(Icons.getTextTinted(iconName));
        item.addListener(SWT.Selection, onClick);
    }

    private void openNewVaultDialog(MainController controller) {
        NewVaultDialog dialog = new NewVaultDialog(container.getShell());
        dialog.open(controller::createVault);
    }

    public Control getControl() {
        return container;
    }
}
