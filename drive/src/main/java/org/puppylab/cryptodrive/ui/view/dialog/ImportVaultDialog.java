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
import java.util.function.Function;

/**
 * Modal dialog to pick an existing vault directory and import it. The
 * {@code onImport} callback returns {@code null} on success or a user-facing
 * error to display in the dialog.
 */
public class ImportVaultDialog {

    private final Shell shell;
    private final Text pathField;
    private final Label errorLabel;
    private final Button importBtn;
    private boolean imported = false;

    public ImportVaultDialog(Shell parent) {
        this.shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        shell.setText("Import Vault");
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

        importBtn = new Button(btns, SWT.PUSH);
        importBtn.setText("Import");
        GridData cd = new GridData();
        cd.widthHint = 90;
        importBtn.setLayoutData(cd);

        Button cancelBtn = new Button(btns, SWT.PUSH);
        cancelBtn.setText("Cancel");
        GridData xd = new GridData();
        xd.widthHint = 90;
        cancelBtn.setLayoutData(xd);
        cancelBtn.addListener(SWT.Selection, _ -> shell.close());

        shell.setDefaultButton(importBtn);
        shell.pack();
        centerOnParent(parent);
    }

    public boolean open(Function<Path, String> onImport) {
        importBtn.addListener(SWT.Selection, _ -> onImportClicked(onImport));

        shell.open();
        Display display = shell.getDisplay();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        return imported;
    }

    private void onImportClicked(Function<Path, String> onImport) {
        errorLabel.setText("");
        String pathStr = pathField.getText().trim();
        if (pathStr.isEmpty()) {
            errorLabel.setText("Please choose a vault folder.");
            return;
        }
        String err = onImport.apply(Path.of(pathStr));
        if (err != null) {
            errorLabel.setText(err);
            return;
        }
        imported = true;
        shell.close();
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

    private void centerOnParent(Shell parent) {
        var p = parent.getBounds();
        var s = shell.getBounds();
        shell.setLocation(p.x + (p.width - s.width) / 2, p.y + (p.height - s.height) / 2);
    }
}
