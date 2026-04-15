package org.puppylab.cryptodrive.ui.view.dialog;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.BiFunction;

/**
 * Modal dialog to collect a directory + password for a new vault. The
 * {@code onCreate} callback passed to {@link #open(BiFunction)} performs the
 * creation and returns {@code null} on success or a user-facing error to
 * display in the dialog.
 */
public class NewVaultDialog {

    private static final int MIN_PASSWORD_LEN = 8;

    private final Shell shell;
    private final Text pathField;
    private final Text passwordField;
    private final Text confirmField;
    private final Label errorLabel;
    private final Button createBtn;
    private boolean created = false;

    public NewVaultDialog(Shell parent) {
        this.shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        shell.setText("New Vault");
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 16;
        layout.marginHeight = 16;
        layout.horizontalSpacing = 8;
        layout.verticalSpacing = 10;
        shell.setLayout(layout);

        // Vault Location
        label("Vault Location:");
        pathField = new Text(shell, SWT.BORDER);
        GridData pd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        pd.widthHint = 320;
        pathField.setLayoutData(pd);
        Button selectBtn = new Button(shell, SWT.PUSH);
        selectBtn.setText("Select Folder");
        selectBtn.addListener(SWT.Selection, _ -> onSelectFolder());

        // Master Password
        label("Master Password:");
        passwordField = new Text(shell, SWT.BORDER | SWT.PASSWORD);
        passwordField.setLayoutData(spanTwo());

        // Confirm Password
        label("Confirm Password:");
        confirmField = new Text(shell, SWT.BORDER | SWT.PASSWORD);
        confirmField.setLayoutData(spanTwo());

        // Error row
        new Label(shell, SWT.NONE);
        errorLabel = new Label(shell, SWT.NONE);
        errorLabel.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_RED));
        GridData ed = new GridData(SWT.FILL, SWT.CENTER, true, false);
        ed.horizontalSpan = 2;
        errorLabel.setLayoutData(ed);

        // Buttons
        Composite btns = new Composite(shell, SWT.NONE);
        GridData bd = new GridData(SWT.END, SWT.CENTER, true, false);
        bd.horizontalSpan = 3;
        btns.setLayoutData(bd);
        GridLayout bl = new GridLayout(2, false);
        bl.marginWidth = 0;
        bl.marginHeight = 0;
        btns.setLayout(bl);

        createBtn = new Button(btns, SWT.PUSH);
        createBtn.setText("Create");
        GridData cd = new GridData();
        cd.widthHint = 90;
        createBtn.setLayoutData(cd);

        Button cancelBtn = new Button(btns, SWT.PUSH);
        cancelBtn.setText("Cancel");
        GridData xd = new GridData();
        xd.widthHint = 90;
        cancelBtn.setLayoutData(xd);
        cancelBtn.addListener(SWT.Selection, _ -> shell.close());

        shell.setDefaultButton(createBtn);
        shell.pack();
        centerOnParent(parent);
    }

    public boolean open(BiFunction<Path, char[], String> onCreate) {
        createBtn.addListener(SWT.Selection, _ -> onCreateClicked(onCreate));

        shell.open();
        Display display = shell.getDisplay();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        return created;
    }

    private void onCreateClicked(BiFunction<Path, char[], String> onCreate) {
        errorLabel.setText("");
        String pathStr = pathField.getText().trim();
        if (pathStr.isEmpty()) {
            errorLabel.setText("Please choose a vault folder.");
            return;
        }
        char[] pw = passwordField.getText().toCharArray();
        char[] confirm = confirmField.getText().toCharArray();
        try {
            if (pw.length < MIN_PASSWORD_LEN) {
                errorLabel.setText("Password must be at least " + MIN_PASSWORD_LEN + " characters.");
                return;
            }
            if (!Arrays.equals(pw, confirm)) {
                errorLabel.setText("Passwords do not match.");
                return;
            }
            String err = onCreate.apply(Path.of(pathStr), pw);
            if (err != null) {
                errorLabel.setText(err);
                return;
            }
            created = true;
            shell.close();
        } finally {
            Arrays.fill(confirm, '\0');
            // Don't wipe pw here — it was handed to onCreate which may still be using it.
            // On cancel/error paths the GC will collect it; dialog lifetime is short.
        }
    }

    private void onSelectFolder() {
        DirectoryDialog dd = new DirectoryDialog(shell, SWT.OPEN);
        dd.setText("Choose vault folder");
        String chosen = dd.open();
        if (chosen != null) {
            pathField.setText(chosen);
        }
    }

    private Label label(String text) {
        Label l = new Label(shell, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        return l;
    }

    private GridData spanTwo() {
        GridData d = new GridData(SWT.FILL, SWT.CENTER, true, false);
        d.horizontalSpan = 2;
        return d;
    }

    private void centerOnParent(Shell parent) {
        var p = parent.getBounds();
        var s = shell.getBounds();
        shell.setLocation(p.x + (p.width - s.width) / 2, p.y + (p.height - s.height) / 2);
    }
}
