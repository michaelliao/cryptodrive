package org.puppylab.cryptodrive.ui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

/**
 * Placeholder shown when no vaults exist yet.
 */
public class EmptyView {

    private final Composite root;

    public EmptyView(Composite parent) {
        this.root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        Label label = new Label(root, SWT.CENTER | SWT.WRAP);
        label.setText("Click \"New Vault\" to create a new vault");
        label.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, true));
    }

    public Composite getControl() {
        return root;
    }
}
