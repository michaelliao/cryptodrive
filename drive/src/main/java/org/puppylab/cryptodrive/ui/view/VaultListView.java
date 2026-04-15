package org.puppylab.cryptodrive.ui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.puppylab.cryptodrive.core.Vault;
import org.puppylab.cryptodrive.ui.Icons;
import org.puppylab.cryptodrive.ui.controller.MainController;
import org.puppylab.cryptodrive.util.FileUtils;

/**
 * Owner-drawn vault list. Each row: 32×32 state icon (locked/unlocked) on the
 * left, then two lines — bold 120% name on top, dimmed path below.
 */
public class VaultListView {

    private static final int ROW_HEIGHT = 56;
    private static final int ICON_SIZE  = 32;
    private static final int ICON_LEFT  = 10;
    private static final int TEXT_LEFT  = ICON_LEFT + ICON_SIZE + 10;
    private static final int NAME_Y     = 8;
    private static final int PATH_Y     = 30;

    private final Table          table;
    private final MainController controller;
    private final Font           nameFont;

    public VaultListView(Composite parent, MainController controller) {
        this.controller = controller;
        this.table = new Table(parent, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        this.table.setHeaderVisible(false);
        this.table.setLinesVisible(false);

        this.nameFont = buildNameFont(parent.getDisplay(), table.getFont());

        TableColumn col = new TableColumn(table, SWT.NONE);
        col.setWidth(200);
        table.addControlListener(ControlListener.controlResizedAdapter(_ -> col.setWidth(table.getClientArea().width)));

        table.addListener(SWT.MeasureItem, e -> e.height = ROW_HEIGHT);
        table.addListener(SWT.EraseItem, this::onEraseItem);
        table.addListener(SWT.PaintItem, this::onPaintItem);

        table.addListener(SWT.Selection, _ -> {
            int idx = table.getSelectionIndex();
            if (idx >= 0 && idx < controller.getVaults().size()) {
                controller.select(controller.getVaults().get(idx));
            }
        });

        table.addListener(SWT.Dispose, _ -> nameFont.dispose());

        controller.addVaultsChangedListener(this::refresh);
        refresh();
    }

    private static Font buildNameFont(Display display, Font base) {
        FontData[] fd = base.getFontData();
        for (FontData d : fd) {
            d.setHeight((int) Math.round(d.getHeight() * 1.2));
            d.setStyle(d.getStyle() | SWT.BOLD);
        }
        return new Font(display, fd);
    }

    public Control getControl() {
        return table;
    }

    private void refresh() {
        table.removeAll();
        for (Vault v : controller.getVaults()) {
            TableItem ti = new TableItem(table, SWT.NONE);
            ti.setData(v);
        }
        if (table.getItemCount() > 0) {
            table.setSelection(0);
            controller.select(controller.getVaults().get(0));
        }
    }

    // ── owner-draw ───────────────────────────────────────────────────────────

    private void onEraseItem(Event e) {
        e.detail &= ~SWT.FOREGROUND;
        if ((e.detail & SWT.SELECTED) == 0)
            return;
        e.gc.setBackground(e.display.getSystemColor(SWT.COLOR_LIST_SELECTION));
        e.gc.fillRectangle(e.x, e.y, e.width, e.height);
        e.detail &= ~SWT.SELECTED;
    }

    private void onPaintItem(Event e) {
        TableItem item = (TableItem) e.item;
        Vault vault = (Vault) item.getData();
        if (vault == null)
            return;

        GC gc = e.gc;
        Display display = e.display;
        boolean selected = table.getSelectionIndex() == table.indexOf(item);
        Color savedBg = gc.getBackground();
        Color savedFg = gc.getForeground();
        Font savedFont = gc.getFont();

        int iconY = e.y + (ROW_HEIGHT - ICON_SIZE) / 2;
        drawStateIcon(gc, e.x + ICON_LEFT, iconY, vault);

        int textX = e.x + TEXT_LEFT;
        int textW = Math.max(0, e.width - TEXT_LEFT - 8);

        gc.setFont(nameFont);
        gc.setForeground(display.getSystemColor(selected ? SWT.COLOR_LIST_SELECTION_TEXT : SWT.COLOR_LIST_FOREGROUND));
        gc.drawText(ellipsize(gc, vault.getName(), textW), textX, e.y + NAME_Y, true);

        gc.setFont(savedFont);
        gc.setForeground(display.getSystemColor(selected ? SWT.COLOR_LIST_SELECTION_TEXT : SWT.COLOR_DARK_GRAY));
        gc.drawText(ellipsize(gc, FileUtils.prettyPath(vault.getPath()), textW), textX, e.y + PATH_Y, true);

        gc.setBackground(savedBg);
        gc.setForeground(savedFg);
    }

    private static void drawStateIcon(GC gc, int x, int y, Vault vault) {
        Image icon = Icons.get(vault.isLocked() ? "state-locked" : "state-unlocked");
        if (icon == null)
            return;
        Rectangle b = icon.getBounds();
        gc.drawImage(icon, 0, 0, b.width, b.height, x, y, ICON_SIZE, ICON_SIZE);
    }

    private static String ellipsize(GC gc, String text, int maxWidth) {
        if (maxWidth <= 0 || gc.textExtent(text).x <= maxWidth)
            return text;
        String ell = "…";
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (gc.textExtent(text.substring(0, mid) + ell).x <= maxWidth)
                lo = mid;
            else
                hi = mid - 1;
        }
        return text.substring(0, lo) + ell;
    }
}
