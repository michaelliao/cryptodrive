package org.puppylab.cryptodrive.ui.view.dialog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.puppylab.cryptodrive.core.AppSettings;
import org.puppylab.cryptodrive.ui.Icons;
import org.puppylab.cryptodrive.ui.controller.MainController;
import org.puppylab.cryptodrive.util.I18nUtils;

/**
 * Modal application settings dialog with General and About tabs. No OK/Cancel
 * buttons — General controls persist on commit per-control.
 */
public class AppSettingDialog {

    private static final String APP_NAME    = "Crypto Drive";
    private static final String APP_VERSION = "1.0";
    private static final String APP_WEBSITE = "https://cryptodrive.puppylab.org";
    private static final int    LOGO_SIZE   = 64;

    private static final String[] LANG_LABELS = { "System default", "English", "Chinese", "Japanese" };
    private static final String[] LANG_CODES  = { "", "en", "zh", "ja" };

    private final Shell          shell;
    private final MainController controller;

    public AppSettingDialog(Shell parent, MainController controller) {
        this.controller = controller;
        this.shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        shell.setText("Settings");
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 12;
        shell.setLayout(layout);

        TabFolder tabs = new TabFolder(shell, SWT.NONE);
        GridData td = new GridData(SWT.FILL, SWT.FILL, true, true);
        td.widthHint = 520;
        td.heightHint = 420;
        tabs.setLayoutData(td);

        buildGeneralTab(tabs);
        buildAboutTab(tabs);

        shell.pack();
        centerOnParent(parent);
    }

    public void open() {
        shell.open();
        Display display = shell.getDisplay();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch())
                display.sleep();
        }
    }

    // ── General ──────────────────────────────────────────────────────────────

    private void buildGeneralTab(TabFolder tabs) {
        TabItem item = new TabItem(tabs, SWT.NONE);
        item.setText("General");

        Composite body = new Composite(tabs, SWT.NONE);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 14;
        gl.marginHeight = 14;
        gl.horizontalSpacing = 8;
        gl.verticalSpacing = 12;
        body.setLayout(gl);

        AppSettings settings = controller.getAppSettings();

        Button tray = new Button(body, SWT.CHECK);
        tray.setText("Keep Crypto Drive in the notification area");
        tray.setSelection(settings.keepTrayIcon);
        GridData trayData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        trayData.horizontalSpan = 2;
        tray.setLayoutData(trayData);
        tray.addListener(SWT.Selection, _ -> {
            settings.keepTrayIcon = tray.getSelection();
            controller.saveAppSettings();
        });

        Label lang = new Label(body, SWT.NONE);
        lang.setText("Language:");
        lang.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Combo langCombo = new Combo(body, SWT.READ_ONLY | SWT.DROP_DOWN);
        langCombo.setItems(LANG_LABELS);
        langCombo.select(indexOfLanguage(settings.language));
        GridData ld = new GridData(SWT.FILL, SWT.CENTER, true, false);
        ld.widthHint = 200;
        langCombo.setLayoutData(ld);
        langCombo.addListener(SWT.Selection, _ -> {
            int i = langCombo.getSelectionIndex();
            if (i < 0)
                return;
            settings.language = LANG_CODES[i];
            I18nUtils.init(settings.language);
            controller.saveAppSettings();
        });

        item.setControl(body);
    }

    private static int indexOfLanguage(String code) {
        if (code == null)
            return 0;
        for (int i = 0; i < LANG_CODES.length; i++) {
            if (LANG_CODES[i].equals(code))
                return i;
        }
        return 0;
    }

    // ── About ────────────────────────────────────────────────────────────────

    private void buildAboutTab(TabFolder tabs) {
        TabItem item = new TabItem(tabs, SWT.NONE);
        item.setText("About");

        Composite body = new Composite(tabs, SWT.NONE);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 14;
        gl.marginHeight = 14;
        gl.horizontalSpacing = 12;
        gl.verticalSpacing = 10;
        body.setLayout(gl);

        Canvas logo = new Canvas(body, SWT.NONE);
        GridData logoData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        logoData.widthHint = LOGO_SIZE;
        logoData.heightHint = LOGO_SIZE;
        logo.setLayoutData(logoData);
        logo.addListener(SWT.Paint, e -> {
            Image img = Icons.get("logo-64");
            if (img == null)
                return;
            Rectangle b = img.getBounds();
            e.gc.drawImage(img, 0, 0, b.width, b.height, 0, 0, LOGO_SIZE, LOGO_SIZE);
        });

        Display display = shell.getDisplay();
        Font nameFont = buildScaledFont(display, body.getFont(), 1.3, true);

        Composite titleBox = new Composite(body, SWT.NONE);
        titleBox.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout tbLayout = new GridLayout(1, false);
        tbLayout.marginWidth = 0;
        tbLayout.marginHeight = 0;
        tbLayout.verticalSpacing = 4;
        titleBox.setLayout(tbLayout);

        Label name = new Label(titleBox, SWT.NONE);
        name.setText(APP_NAME);
        name.setFont(nameFont);
        name.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label version = new Label(titleBox, SWT.NONE);
        version.setText("Version " + APP_VERSION);
        version.setForeground(display.getSystemColor(SWT.COLOR_DARK_GRAY));
        version.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Link website = new Link(body, SWT.NONE);
        website.setText("Website: <a href=\"" + APP_WEBSITE + "\">" + APP_WEBSITE + "</a>");
        GridData wd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        wd.horizontalSpan = 2;
        website.setLayoutData(wd);
        website.addListener(SWT.Selection, e -> Program.launch(e.text));

        Label licHeader = new Label(body, SWT.NONE);
        licHeader.setText("License:");
        GridData lhd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        lhd.horizontalSpan = 2;
        licHeader.setLayoutData(lhd);

        Text license = new Text(body, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.READ_ONLY | SWT.WRAP);
        license.setText(loadLicenseText());
        GridData licData = new GridData(SWT.FILL, SWT.FILL, true, true);
        licData.horizontalSpan = 2;
        license.setLayoutData(licData);

        body.addListener(SWT.Dispose, _ -> nameFont.dispose());

        item.setControl(body);
    }

    private static String loadLicenseText() {
        try (InputStream is = AppSettingDialog.class.getResourceAsStream("/LICENSE")) {
            if (is == null) {
                return "LICENSE file not bundled.";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Failed to load LICENSE: " + e.getMessage();
        }
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

    private void centerOnParent(Shell parent) {
        var p = parent.getBounds();
        var s = shell.getBounds();
        shell.setLocation(p.x + (p.width - s.width) / 2, p.y + (p.height - s.height) / 2);
    }
}
