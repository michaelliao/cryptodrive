package org.puppylab.cryptodrive.ui;

import static org.puppylab.cryptodrive.util.I18nUtils.i18n;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tray;
import org.eclipse.swt.widgets.TrayItem;
import org.puppylab.cryptodrive.ui.controller.MainController;
import org.puppylab.cryptodrive.ui.view.EmptyView;
import org.puppylab.cryptodrive.ui.view.ToolbarView;
import org.puppylab.cryptodrive.ui.view.VaultDetailView;
import org.puppylab.cryptodrive.ui.view.VaultListView;
import org.puppylab.cryptodrive.util.I18nUtils;

public class MainWindow {

    private static final int VAULT_LIST_WIDTH = 200;

    public static void main(String[] args) {
        new MainWindow().open();
    }

    public void open() {
        Display display = new Display();
        I18nUtils.init("");

        MainController controller = MainController.init();

        // ── shell ────────────────────────────────────────────────────────────
        Shell shell = new Shell(display);
        shell.setText(i18n("app.name"));
        shell.setSize(900, 600);
        ShellUtils.setCenter(shell);
        shell.setImage(Icons.get("logo"));

        GridLayout shellLayout = new GridLayout(1, false);
        shellLayout.marginWidth = 0;
        shellLayout.marginHeight = 0;
        shellLayout.verticalSpacing = 0;
        shell.setLayout(shellLayout);

        // ── toolbar ──────────────────────────────────────────────────────────
        ToolbarView toolbar = new ToolbarView(shell, controller);
        toolbar.getControl().setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // ── body: [list | (empty | detail)] ─────────────────────────────────
        Composite body = new Composite(shell, SWT.NONE);
        body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout bodyLayout = new GridLayout(2, false);
        bodyLayout.marginWidth = 0;
        bodyLayout.marginHeight = 0;
        bodyLayout.horizontalSpacing = 0;
        body.setLayout(bodyLayout);

        VaultListView listView = new VaultListView(body, controller);
        GridData listData = new GridData(SWT.FILL, SWT.FILL, false, true);
        listData.widthHint = VAULT_LIST_WIDTH;
        listView.getControl().setLayoutData(listData);

        Composite rightPane = new Composite(body, SWT.NONE);
        rightPane.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        StackLayout rightStack = new StackLayout();
        rightPane.setLayout(rightStack);

        EmptyView emptyView = new EmptyView(rightPane);
        VaultDetailView detailView = new VaultDetailView(rightPane, controller);

        Runnable updateRight = () -> {
            Control top = controller.getVaults().isEmpty() ? emptyView.getControl() : detailView.getControl();
            if (rightStack.topControl != top) {
                rightStack.topControl = top;
                rightPane.layout();
            }
        };
        controller.addVaultsChangedListener(updateRight);
        updateRight.run();

        // ── system tray ──────────────────────────────────────────────────────
        Tray tray = display.getSystemTray();
        if (tray != null) {
            TrayItem trayItem = new TrayItem(tray, SWT.NONE);
            trayItem.setToolTipText(i18n("app.name"));
            trayItem.setImage(Icons.get("logo"));

            Menu trayMenu = new Menu(shell, SWT.POP_UP);
            MenuItem openItem = new MenuItem(trayMenu, SWT.PUSH);
            openItem.setText(i18n("tray.open"));
            openItem.addListener(SWT.Selection, _ -> {
                shell.setVisible(true);
                shell.forceActive();
            });
            new MenuItem(trayMenu, SWT.SEPARATOR);
            MenuItem exitItem = new MenuItem(trayMenu, SWT.PUSH);
            exitItem.setText(i18n("tray.exit"));
            exitItem.addListener(SWT.Selection, _ -> shell.dispose());

            trayItem.addListener(SWT.MenuDetect, _ -> trayMenu.setVisible(true));

            shell.addListener(SWT.Close, e -> {
                e.doit = false;
                shell.setVisible(false);
            });
        }

        // ── event loop ───────────────────────────────────────────────────────
        shell.open();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch())
                display.sleep();
        }
        display.dispose();
    }
}
