package org.puppylab.cryptodrive.util;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Shell;

/**
 * Small helpers for SWT {@link Shell} placement.
 */
public class ShellUtils {

    /**
     * Position {@code shell} at the center of the primary monitor. The shell must
     * already have its size set (via {@code setSize} or {@code pack}).
     */
    /**
     * Restore, show and bring {@code shell} to the foreground. Safe to call when
     * minimized, hidden (tray) or already visible.
     */
    public static void activate(Shell shell) {
        shell.setMinimized(false);
        shell.setVisible(true);
        shell.forceActive();
    }

    public static void setCenter(Shell shell) {
        Rectangle screen = shell.getDisplay().getPrimaryMonitor().getBounds();
        Point size = shell.getSize();
        shell.setLocation(screen.x + (screen.width - size.x) / 2, screen.y + (screen.height - size.y) / 2);
    }
}
