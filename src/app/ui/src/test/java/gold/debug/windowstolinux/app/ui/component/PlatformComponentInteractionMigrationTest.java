package gold.debug.windowstolinux.app.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;

import javax.swing.*;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.service.contract.spi.ServerProfileEditor;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.i18n.*;
import gold.debug.windowstolinux.app.ui.diagnostic.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.server.ServerProfileDialog;
import org.junit.jupiter.api.Test;

/** Migrates reusable interaction contracts to actual platform controls, without the retired drawer or toggle renderer. / 将通用交互合同迁到实际平台控件，不复造已退出的抽屉或开关绘制器。 */
class PlatformComponentInteractionMigrationTest {
    @Test
    void factoryLongLabelsHelpAndTrailingControlsFitAcrossLanguagesThemesAndScales() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "The migrated geometry contract requires the desktop");
        String previousScale = System.getProperty("flatlaf.uiScale");
        try {
            for (String scale : List.of("1.0", "1.5"))
                for (ThemeMode theme : List.of(ThemeMode.LIGHT, ThemeMode.DARK))
                    for (String language : List.of("zh-CN", "en"))
                        for (int width : List.of(640, 880)) {
                            onEdt(() -> {
                                System.setProperty("flatlaf.uiScale", scale);
                                DesktopThemeService.install(theme);
                                assertEquals(Float.parseFloat(scale), UIScale.getUserScaleFactor(), 0.02);
                                var catalog = MessageCatalog.forLanguageTag(language);
                                var messages = new PageMessagePresenter(catalog);
                                var factory = new DesktopComponentFactory(theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light());
                                var form = factory.card(new GridBagLayout());
                                var input = new JTextField("Unsubmitted database draft", 8);
                                var choice = new JCheckBox();
                                var help = factory.secondaryButton("?");
                                help.getAccessibleContext().setAccessibleName(messages.text("help.field.sshPort"));
                                var trailing = factory.transparent(new FlowLayout(FlowLayout.TRAILING, 6, 0));
                                trailing.add(choice); trailing.add(help);
                                factory.addField(form, 0, 0, messages, "field.databaseDetails", input);
                                factory.addField(form, 1, 0, messages, "field.configurationEntries", trailing);
                                var explanation = factory.informationCard(messages.text("field.sshPort"), messages.text("help.field.sshPort"));
                                var root = factory.pagePanel();
                                root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
                                root.add(form, BorderLayout.NORTH); root.add(explanation, BorderLayout.CENTER);
                                var frame = new JFrame("Actual platform form geometry");
                                try {
                                    frame.setContentPane(root);
                                    frame.setSize(UIScale.scale(width), UIScale.scale(520));
                                    frame.setVisible(true); layout(root);
                                    for (var child : form.getComponents()) assertInside(child, form);
                                    assertInside(choice, trailing); assertInside(help, trailing);
                                    for (var child : form.getComponents()) if (child instanceof JLabel label) assertLabelFits(label);
                                    var detail = descendants(explanation).filter(JTextArea.class::isInstance).map(JTextArea.class::cast).findFirst().orElseThrow();
                                    assertTrue(detail.getLineWrap()); assertFalse(detail.isEditable());
                                    assertEquals(messages.text("help.field.sshPort"), detail.getText());
                                    var last = detail.modelToView2D(detail.getDocument().getLength());
                                    assertNotNull(last); assertTrue(last.getMaxY() <= detail.getHeight(), "Help text must remain readable inside its actual card");
                                    assertEquals("Unsubmitted database draft", input.getText());
                                    assertFalse(choice.isSelected());
                                    assertFalse(help.getAccessibleContext().getAccessibleName().startsWith("help."));
                                } finally { frame.dispose(); }
                                return null;
                            });
                        }
        } finally {
            onEdt(() -> {
                if (previousScale == null) System.clearProperty("flatlaf.uiScale"); else System.setProperty("flatlaf.uiScale", previousScale);
                DesktopThemeService.install(ThemeMode.LIGHT);
                return null;
            });
        }
    }

    @Test
    void failedOwnedEditorSaveRestoresOriginallyDisabledInputsOnlyAfterWorkerCleanup() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "Actual editor ownership requires the desktop");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger(); var notifications = new AtomicInteger();
        var borrowed = new AtomicReference<char[]>();
        ServerProfileEditor service = (profile, password) -> {
            calls.incrementAndGet(); borrowed.set(password); entered.countDown();
            try {
                if (!release.await(8, TimeUnit.SECONDS)) throw new SQLException("Controlled worker did not finish");
                throw new SQLException("Controlled known save failure");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); throw new SQLException("Controlled worker interrupted", failure);
            } finally { Arrays.fill(password, '\0'); }
        };
        var owner = new AtomicReference<JFrame>(); var editor = new AtomicReference<ServerProfileDialog>();
        try {
            onEdt(() -> {
                var frame = new JFrame("Actual editor owner"); owner.set(frame); frame.setSize(760, 620); frame.setVisible(true);
                var dialog = new ServerProfileDialog(frame, service, new DesktopComponentFactory(ThemePalette.light()),
                        new PageMessagePresenter(MessageCatalog.forLanguageTag("en")),
                        new ServerProfile("saved", "example.test", 22, "tester", "custom/key", "Original"), value -> notifications.incrementAndGet());
                editor.set(dialog); dialog.setModalityType(Dialog.ModalityType.MODELESS); dialog.setVisible(true);
                ((JTextField) named(dialog, "server.name")).setText("Unsaved name");
                named(dialog, "server.port").setEnabled(false);
                ((JPasswordField) named(dialog, "server.password")).setText("temporary-draft");
                ((JButton) named(dialog, "server.save")).doClick(0);
                ((JButton) named(dialog, "server.save")).doClick(0);
                assertFalse(named(dialog, "server.name").isEnabled()); assertFalse(named(dialog, "server.port").isEnabled());
                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                assertTrue(dialog.isDisplayable(), "Accepted worker ownership prevents premature close");
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(DesktopTaskExecutor.hasActiveTasks()); assertEquals(1, calls.get());
            release.countDown();
            await(() -> !DesktopTaskExecutor.hasActiveTasks() && named(editor.get(), "server.save").isEnabled());
            onEdt(() -> {
                assertTrue(editor.get().isDisplayable()); assertTrue(named(editor.get(), "server.name").isEnabled());
                assertFalse(named(editor.get(), "server.port").isEnabled(), "Busy completion must not enable an originally disabled input");
                assertEquals("Unsaved name", ((JTextField) named(editor.get(), "server.name")).getText());
                assertEquals("example.test", ((JTextField) named(editor.get(), "server.host")).getText());
                assertEquals(0, ((JPasswordField) named(editor.get(), "server.password")).getPassword().length);
                owner.get().dispose(); return null;
            });
            await(() -> !editor.get().isDisplayable());
            assertArrayEquals(new char[borrowed.get().length], borrowed.get()); assertEquals(0, notifications.get());
        } finally {
            release.countDown(); await(() -> !DesktopTaskExecutor.hasActiveTasks());
            onEdt(() -> { if (editor.get() != null) editor.get().dispose(); if (owner.get() != null) owner.get().dispose(); return null; });
        }
    }

    private static void assertLabelFits(JLabel label) {
        var insets = label.getInsets(); int width = label.getWidth() - insets.left - insets.right;
        var html = (View) label.getClientProperty(BasicHTML.propertyKey);
        if (html == null) assertTrue(label.getFontMetrics(label.getFont()).stringWidth(label.getText()) <= width, "A localized field label cannot be clipped");
        else { html.setSize(width, 0); assertTrue(html.getPreferredSpan(View.Y_AXIS) <= label.getHeight() - insets.top - insets.bottom + 1, "Wrapped label must retain every line"); }
    }
    private static void assertInside(Component child, Container parent) {
        assertTrue(child.getWidth() > 0 && child.getHeight() > 0);
        assertTrue(child.getX() >= 0 && child.getY() >= 0 && child.getX() + child.getWidth() <= parent.getWidth() && child.getY() + child.getHeight() <= parent.getHeight(), "Visible control must fit its actual parent");
    }
    private static Component named(Component root, String name) { return descendants(root).filter(value -> name.equals(value.getName())).findFirst().orElseThrow(); }
    private static Stream<Component> descendants(Component root) { return Stream.concat(Stream.of(root), root instanceof Container parent ? Arrays.stream(parent.getComponents()).flatMap(PlatformComponentInteractionMigrationTest::descendants) : Stream.empty()); }
    private static void layout(Container root) { root.doLayout(); for (var child : root.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static <T> T onEdt(Callable<T> operation) throws Exception { var future = new FutureTask<>(operation); SwingUtilities.invokeAndWait(future); return future.get(); }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (!onEdt(condition::getAsBoolean) && System.nanoTime() < end) Thread.sleep(15); assertTrue(onEdt(condition::getAsBoolean)); }
}
