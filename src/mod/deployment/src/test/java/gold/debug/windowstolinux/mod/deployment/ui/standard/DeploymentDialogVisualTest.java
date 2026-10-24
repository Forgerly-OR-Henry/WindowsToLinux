package gold.debug.windowstolinux.mod.deployment.ui.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentDialogs;
import org.junit.jupiter.api.Test;

/** Captures actual owner-bound dialogs without granting remote capabilities. / 不授予远端能力地抓取实际所属对话框。 */
class DeploymentDialogVisualTest {
    @Test
    void actualProductionDialogShellFitsThemesLanguagesScalesAndRestrictedWorkAreas() throws Exception {
        var failures = new AtomicReference<Throwable>();
        var previousTheme = com.formdev.flatlaf.FlatLaf.isLafDark() ? ThemeMode.DARK : ThemeMode.LIGHT;
        int previousZoom = Math.round(com.formdev.flatlaf.util.UIScale.getZoomFactor() * 100);
        int[] count = {0};
        Locale previousLocale = JComponent.getDefaultLocale();
        var previousLabels = new LinkedHashMap<String, Object>();
        for (String key : List.of("OptionPane.okButtonText", "OptionPane.cancelButtonText", "OptionPane.yesButtonText", "OptionPane.noButtonText"))
            previousLabels.put(key, UIManager.get(key));
        try {
            for (String language : new String[]{"zh-CN", "en"})
                for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                    for (int zoom : new int[]{100, 125, 150})
                        for (boolean restricted : new boolean[]{false, true})
                            for (var kind : DeploymentDialogPreview.Kind.values()) {
                                SwingUtilities.invokeAndWait(() -> {
                                    DesktopThemeService.install(theme, zoom);
                                    var hostMessages = gold.debug.windowstolinux.app.ui.language.MessageCatalog.forLanguageTag(language);
                                    JComponent.setDefaultLocale(hostMessages.locale());
                                    UIManager.put("OptionPane.okButtonText", hostMessages.text("button.ok"));
                                    UIManager.put("OptionPane.cancelButtonText", hostMessages.text("button.cancel"));
                                    UIManager.put("OptionPane.yesButtonText", hostMessages.text("value.yes"));
                                    UIManager.put("OptionPane.noButtonText", hostMessages.text("value.no"));
                                    var environment = new UiEnvironment(Locale.forLanguageTag(language), theme.name(), false);
                                    var messages = DeploymentDialogPreview.messages(kind, environment.locale());
                                    var owner = new JFrame("Dialog matrix");
                                    owner.setSize(1280, 720);
                                    owner.setVisible(true);
                                    var body = DeploymentDialogPreview.content(kind, environment);
                                    var dialog = DeploymentDialogPreview.window(owner.getRootPane(), kind, body, messages);
                                    DeploymentDialogs.refresh(dialog, messages);
                                    Rectangle area = restricted ? DeploymentDialogs.workArea(dialog).intersection(new Rectangle(20, 20, 800, 560))
                                        : DeploymentDialogs.workArea(dialog);
                                    if (restricted) DeploymentDialogs.constrain(dialog, area);
                                    dialog.getRootPane().putClientProperty("deployment.dialog.testWorkArea", area);
                                    Runnable verify = () -> {
                                        try {
                                            dialog.validate();
                                            if (language.equals("en")) {
                                                assertFalse(dialog.getTitle().codePoints().anyMatch(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN), "English must not fall back to the system Chinese locale");
                                                if (kind == DeploymentDialogPreview.Kind.COMPONENTS)
                                                    assertEquals("Review discovered components", dialog.getTitle());
                                            } else
                                                assertTrue(dialog.getTitle().codePoints().anyMatch(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN));
                                            assertTrue(area.contains(dialog.getBounds()), "The complete decorated window must fit " + area);
                                            if (kind.modal()) {
                                                var labels = descendants(dialog).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                                                    .filter(button -> "OptionPane.button".equals(button.getName())).map(JButton::getText).toList();
                                                assertTrue(labels.contains(hostMessages.text("button.ok")), "Actual localized OK decision");
                                                assertTrue(labels.contains(hostMessages.text("button.cancel")), "Actual localized cancel decision");
                                            }
                                            var footer = descendants(dialog).stream().filter(value -> "deployment.dialog.footer".equals(value.getName())).findFirst().orElse(null);
                                            if (footer != null) {
                                                assertTrue(footer.isShowing());
                                                var footerArea = SwingUtilities.convertRectangle(footer.getParent(), footer.getBounds(), dialog.getContentPane());
                                                assertTrue(new Rectangle(0, 0, dialog.getContentPane().getWidth(), dialog.getContentPane().getHeight()).contains(footerArea));
                                            }
                                            for (var child : descendants(dialog)) {
                                                if (child instanceof JTextField && child.isShowing())
                                                    assertEquals(child.getPreferredSize().height, child.getHeight(), "Single-line fields retain natural height: " + child.getName());
                                                if (child instanceof JScrollPane scroll)
                                                    assertFalse(scroll.getHorizontalScrollBar().isShowing(), "Forms must follow the viewport width");
                                                if (child instanceof JButton && child.isShowing() && footer != null && SwingUtilities.isDescendingFrom(child, footer)) {
                                                    assertTrue(child.getWidth() >= child.getPreferredSize().width, "Action width");
                                                    assertTrue(child.getHeight() >= child.getPreferredSize().height, "Action height");
                                                    assertTrue(new Rectangle(0, 0, footer.getWidth(), footer.getHeight()).contains(
                                                        SwingUtilities.convertRectangle(child.getParent(), child.getBounds(), footer)), "Every fixed action stays inside the footer");
                                                }
                                            }
                                            if (kind == DeploymentDialogPreview.Kind.RUNTIME) {
                                                var field = (JTextField) descendants(dialog).stream().filter(value -> "deployment.runtime.preview/port".equals(value.getName())).findFirst().orElseThrow();
                                                int height = field.getHeight();
                                                descendants(dialog).stream().filter(JToggleButton.class::isInstance).findFirst().ifPresent(value -> ((JToggleButton) value).doClick(0));
                                                dialog.validate();
                                                assertEquals(height, field.getHeight(), "Help never stretches unrelated rows");
                                            }
                                            assertInputsReachable(dialog);
                                            String variant = "after-" + kind.name().toLowerCase(Locale.ROOT) + "-" + language + "-" + theme + "-" + zoom + (restricted ? "-restricted" : "-normal");
                                            capture(dialog, variant);
                                            count[0]++;
                                        } catch (Throwable failure) {
                                            failures.compareAndSet(null, failure);
                                        } finally {
                                            dialog.dispose();
                                        }
                                    };
                                    if (kind.modal()) SwingUtilities.invokeLater(verify);
                                    try {
                                        dialog.setVisible(true);
                                        if (!kind.modal()) verify.run();
                                    } finally {
                                        dialog.dispose();
                                        owner.dispose();
                                    }
                                });
                                if (failures.get() != null)
                                    throw new AssertionError("Actual production dialog matrix failed", failures.get());
                            }
            assertEquals(DeploymentDialogPreview.Kind.values().length * 24, count[0]);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                DesktopThemeService.install(previousTheme, previousZoom);
                JComponent.setDefaultLocale(previousLocale);
                previousLabels.forEach(UIManager::put);
            });
        }
    }

    private static void assertInputsReachable(JDialog dialog) {
        var positions = new LinkedHashMap<JViewport, Point>();
        for (var child : descendants(dialog))
            if (child instanceof JViewport viewport) positions.put(viewport, viewport.getViewPosition());
        int checked = 0;
        for (var child : descendants(dialog)) {
            if (!(child instanceof JTextField || child instanceof JComboBox<?> || child instanceof JCheckBox) || !child.isShowing())
                continue;
            var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, child);
            if (viewport == null) continue;
            var control = (JComponent) child;
            assertTrue(control.getWidth() > 0 && control.getHeight() > 0, "Every input has a usable extent: " + control.getName());
            DeploymentDialogs.reveal(control);
            var bounds = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), viewport.getView());
            assertTrue(viewport.getViewRect().contains(bounds), "Scroll must reveal the complete input: " + control.getName() + " " + bounds + " in " + viewport.getViewRect());
            checked++;
        }
        positions.forEach(JViewport::setViewPosition);
        dialog.getRootPane().putClientProperty("deployment.dialog.reachableInputs", checked);
    }

    static JDialog awaitDialog(Window owner) throws Exception {
        var result = new AtomicReference<JDialog>();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            SwingUtilities.invokeAndWait(() -> result.set(Arrays.stream(owner.getOwnedWindows())
                .filter(value -> value instanceof JDialog && value.isShowing()).map(JDialog.class::cast).findFirst().orElse(null)));
            if (result.get() != null) return result.get();
            Thread.sleep(20);
        }
        throw new AssertionError("Original owner dialog did not appear");
    }

    static List<Component> descendants(Container root) {
        var result = new ArrayList<Component>();
        for (var child : root.getComponents()) {
            result.add(child);
            if (child instanceof Container nested) result.addAll(descendants(nested));
        }
        return result;
    }

    static void capture(JDialog dialog, String name) throws Exception {
        Path output = Path.of(System.getProperty("deployment.dialog.evidence", "target/deployment-dialog-screenshots"));
        Files.createDirectories(output);
        var bitmap = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = bitmap.createGraphics();
        try {
            dialog.paint(graphics);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(bitmap, "png", output.resolve(name + ".png").toFile());
        var geometry = new StringBuilder("dialog=" + dialog.getBounds() + "\nworkArea=" + dialog.getRootPane().getClientProperty("deployment.dialog.testWorkArea") + "\nreachableInputs=" + dialog.getRootPane().getClientProperty("deployment.dialog.reachableInputs") + "\n");
        for (var child : descendants(dialog))
            if (child instanceof JTextField || child instanceof JTextArea || child instanceof JButton || child instanceof JScrollPane)
                geometry.append(child.getClass().getSimpleName()).append(' ').append(child.getName()).append(' ')
                    .append(child.getBounds()).append(" text=").append(child instanceof AbstractButton button ? button.getText() : "").append(" preferred=").append(child.getPreferredSize()).append('\n');
        Files.writeString(output.resolve(name + ".txt"), geometry.toString());
    }
}
