package org.puppylab.cryptodrive.ui.view.dialog;

import static org.puppylab.cryptodrive.util.I18nUtils.i18n;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Simple modal dialog that prompts for a password. Returns the entered password
 * as a {@code char[]} from {@link #open()}, or {@code null} if cancelled. The
 * caller is responsible for zeroing the returned array.
 */
public class PasswordInputDialog {

    private final Shell shell;
    private final Text  passwordField;
    private char[]      result;

    public PasswordInputDialog(Shell parent, String message) {
        this.shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        shell.setText(i18n("vaultConfig.sync.passwordTitle"));
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 16;
        layout.marginHeight = 16;
        layout.horizontalSpacing = 8;
        layout.verticalSpacing = 10;
        shell.setLayout(layout);

        Label header = new Label(shell, SWT.NONE);
        header.setText(message);
        GridData hd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hd.horizontalSpan = 2;
        header.setLayoutData(hd);

        Label pwLabel = new Label(shell, SWT.NONE);
        pwLabel.setText(i18n("vaultConfig.sync.password"));
        pwLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        passwordField = new Text(shell, SWT.BORDER | SWT.PASSWORD);
        GridData pd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        pd.widthHint = 220;
        passwordField.setLayoutData(pd);

        Composite btns = new Composite(shell, SWT.NONE);
        GridData bd = new GridData(SWT.END, SWT.CENTER, true, false);
        bd.horizontalSpan = 2;
        btns.setLayoutData(bd);
        GridLayout bl = new GridLayout(2, false);
        bl.marginWidth = 0;
        bl.marginHeight = 0;
        btns.setLayout(bl);

        Button okBtn = new Button(btns, SWT.PUSH);
        okBtn.setText(i18n("btn.ok"));
        GridData od = new GridData();
        od.widthHint = 90;
        okBtn.setLayoutData(od);
        okBtn.addListener(SWT.Selection, _ -> {
            char[] pw = passwordField.getText().toCharArray();
            if (pw.length > 0) {
                result = pw;
                shell.close();
            }
        });

        Button cancelBtn = new Button(btns, SWT.PUSH);
        cancelBtn.setText(i18n("btn.cancel"));
        GridData cd = new GridData();
        cd.widthHint = 90;
        cancelBtn.setLayoutData(cd);
        cancelBtn.addListener(SWT.Selection, _ -> shell.close());

        shell.setDefaultButton(okBtn);
        shell.pack();
        centerOnParent(parent);
    }

    /**
     * Opens the dialog modally. Returns the entered password as a {@code char[]},
     * or {@code null} if the user cancelled.
     */
    public char[] open() {
        shell.open();
        Display display = shell.getDisplay();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch())
                display.sleep();
        }
        return result;
    }

    private void centerOnParent(Shell parent) {
        var p = parent.getBounds();
        var s = shell.getBounds();
        shell.setLocation(p.x + (p.width - s.width) / 2, p.y + (p.height - s.height) / 2);
    }
}
