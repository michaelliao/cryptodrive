package org.puppylab.cryptodrive.ui.view.dialog;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import java.util.Arrays;
import java.util.function.Function;

/**
 * Modal dialog asking for the master password to unlock a vault. The
 * {@code onUnlock} callback returns {@code null} on success or a user-facing
 * error to display in the dialog.
 */
public class UnlockVaultDialog {

    private final Shell shell;
    private final Text passwordField;
    private final Label errorLabel;
    private final Button unlockBtn;
    private boolean unlocked = false;

    public UnlockVaultDialog(Shell parent, String vaultName) {
        this.shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        shell.setText("Unlock Vault");
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 16;
        layout.marginHeight = 16;
        layout.horizontalSpacing = 8;
        layout.verticalSpacing = 10;
        shell.setLayout(layout);

        Label header = new Label(shell, SWT.NONE);
        header.setText("Enter master password for \"" + vaultName + "\":");
        GridData hd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hd.horizontalSpan = 2;
        header.setLayoutData(hd);

        label("Master Password:");
        passwordField = new Text(shell, SWT.BORDER | SWT.PASSWORD);
        GridData pd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        pd.widthHint = 260;
        passwordField.setLayoutData(pd);

        new Label(shell, SWT.NONE);
        errorLabel = new Label(shell, SWT.NONE);
        errorLabel.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_RED));
        errorLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite btns = new Composite(shell, SWT.NONE);
        GridData bd = new GridData(SWT.END, SWT.CENTER, true, false);
        bd.horizontalSpan = 2;
        btns.setLayoutData(bd);
        GridLayout bl = new GridLayout(2, false);
        bl.marginWidth = 0;
        bl.marginHeight = 0;
        btns.setLayout(bl);

        unlockBtn = new Button(btns, SWT.PUSH);
        unlockBtn.setText("Unlock");
        GridData cd = new GridData();
        cd.widthHint = 90;
        unlockBtn.setLayoutData(cd);

        Button cancelBtn = new Button(btns, SWT.PUSH);
        cancelBtn.setText("Cancel");
        GridData xd = new GridData();
        xd.widthHint = 90;
        cancelBtn.setLayoutData(xd);
        cancelBtn.addListener(SWT.Selection, _ -> shell.close());

        shell.setDefaultButton(unlockBtn);
        shell.pack();
        centerOnParent(parent);
    }

    public boolean open(Function<char[], String> onUnlock) {
        unlockBtn.addListener(SWT.Selection, _ -> onUnlockClicked(onUnlock));

        shell.open();
        Display display = shell.getDisplay();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        return unlocked;
    }

    private void onUnlockClicked(Function<char[], String> onUnlock) {
        errorLabel.setText("");
        char[] pw = passwordField.getText().toCharArray();
        if (pw.length == 0) {
            errorLabel.setText("Please enter the master password.");
            return;
        }
        try {
            String err = onUnlock.apply(pw);
            if (err != null) {
                errorLabel.setText(err);
                return;
            }
            unlocked = true;
            shell.close();
        } finally {
            Arrays.fill(pw, '\0');
        }
    }

    private Label label(String text) {
        Label l = new Label(shell, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        return l;
    }

    private void centerOnParent(Shell parent) {
        var p = parent.getBounds();
        var s = shell.getBounds();
        shell.setLocation(p.x + (p.width - s.width) / 2, p.y + (p.height - s.height) / 2);
    }
}
