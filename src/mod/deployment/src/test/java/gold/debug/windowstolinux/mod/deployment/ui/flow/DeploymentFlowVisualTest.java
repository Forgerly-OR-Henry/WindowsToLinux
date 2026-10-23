package gold.debug.windowstolinux.mod.deployment.ui.flow;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.*;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationsService;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.ui.DeploymentWorkspace;
import gold.debug.windowstolinux.mod.deployment.ui.application.DeploymentApplicationsPage;
import org.junit.jupiter.api.Test;

/** Actual Swing flow rendering from isolated local metadata; no remote deployment is started. */
class DeploymentFlowVisualTest extends DeploymentRuntimeFixture {
    private Path selected;

    @Override
    protected Path selectedSourceDirectory() {
        return selected;
    }

    @Test
    void rendersUnifiedFlowAcrossThemesScalesAndWindowSizes() throws Exception {
        enable();
        selected = Files.createDirectories(root.resolve("order-service"));
        Files.writeString(selected.resolve("README.txt"), "isolated actual source metadata");
        ThemeMode previousTheme = FlatLaf.isLafDark() ? ThemeMode.DARK : ThemeMode.LIGHT;
        int previousZoom = Math.round(UIScale.getZoomFactor() * 100);
        var flow = new AtomicReference<DeploymentFlowPage>();
        var workspace = new AtomicReference<DeploymentWorkspace>();
        var window = new AtomicReference<JFrame>();
        var content = new AtomicReference<JComponent>();
        var output = Path.of(System.getProperty("deployment.ui.screenshots", "target/deployment-ui-screenshots"));
        Files.createDirectories(output);
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile("production-server", "192.0.2.10", 22, "deploy", "fixture-password")), "fixture-password".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var service = DeploymentStandardEntryFixture.pages(context, new DeploymentStandardEntryFixture(context, new DeploymentRefFlow(context)));
            SwingUtilities.invokeAndWait(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                var page = new DeploymentFlowPage(service, new DeploymentAgentPageService(context, new DeploymentAgentWorkflow(context)));
                flow.set(page);
                var shell = new DeploymentWorkspace(() -> page, () -> new DeploymentApplicationsPage(new DeploymentApplicationsService(context)));
                workspace.set(shell);
                try {
                    content.set(shell.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false)));
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                var frame = new JFrame("部署流程 · 实际 Swing 验证");
                window.set(frame);
                frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                frame.setContentPane(content.get());
                content.get().setPreferredSize(new Dimension(1600, 900));
                frame.pack();
                frame.setLocation(0, 0);
                frame.setVisible(true);
            });
            await(() -> control(content.get(), "deployment.flow.mode", JButton.class).isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                var tabs = control(content.get(), "deployment.workspace.tabs", JTabbedPane.class);
                assertEquals(2, tabs.getTabCount());
                assertEquals("部署流程", tabs.getTitleAt(0));
                assertEquals("已部署应用", tabs.getTitleAt(1));
                assertNull(draft(flow.get()).server());
                control(content.get(), "deployment.local.choose", JButton.class).doClick(0);
            });
            await(() -> draft(flow.get()).ready() && draft(flow.get()).servers.getItemCount() == 1);
            SwingUtilities.invokeAndWait(() -> draft(flow.get()).servers.setSelectedIndex(0));
            for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                for (int zoom : new int[]{100, 150})
                    for (boolean minimum : new boolean[]{false, true}) {
                        SwingUtilities.invokeAndWait(() -> {
                            DesktopThemeService.install(theme, zoom);
                            SwingUtilities.updateComponentTreeUI(window.get());
                            workspace.get().environmentChanged(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, theme.name(), false));
                            content.get().setPreferredSize(new Dimension(minimum ? 1280 : 1600, minimum ? 720 : 900));
                            window.get().pack();
                            window.get().validate();
                        });
                        SwingUtilities.invokeAndWait(() -> {
                            layout(content.get());
                            assertEquals(selected.toUri(), draft(flow.get()).local.selection().orElseThrow().location());
                            assertEquals("production-server", draft(flow.get()).server().ref().id());
                            assertTrue(control(content.get(), "deployment.flow.start", JButton.class).isEnabled());
                            assertEquals("开始部署", control(content.get(), "deployment.flow.start", JButton.class).getText());
                            assertEquals(UIManager.getColor("Panel.background"), control(content.get(), "deployment.flow.selections.scroll", JScrollPane.class).getViewport().getBackground());
                            assertFalse(control(content.get(), "deployment.task.feedback.state", JTextArea.class).isVisible());
                            assertFalse(control(content.get(), "delivery.panel", JComponent.class).isVisible());
                            var columns = control(content.get(), "deployment.flow.columns", JSplitPane.class);
                            assertEquals(0.60, columns.getLeftComponent().getWidth() / (double) (columns.getWidth() - columns.getDividerSize()), 0.02);
                            for (String key : new String[]{"flow.source", "flow.target", "style.results"}) {
                                var title = control(content.get(), "deployment.flow.title." + key, JLabel.class);
                                assertTrue(title.getWidth() >= title.getFontMetrics(title.getFont()).stringWidth(title.getText()), key + " title must remain uncut");
                                assertTrue(title.getHeight() >= title.getPreferredSize().height, key + " title must retain full line height");
                            }
                            var startButton = control(content.get(), "deployment.flow.start", JButton.class);
                            assertTrue(startButton.getWidth() >= startButton.getPreferredSize().width);
                            assertTrue(startButton.getHeight() >= startButton.getPreferredSize().height);
                            assertTrue(control(content.get(), "deployment.flow.chooseServer", JButton.class).getHeight() > 0);
                            assertTrue(control(content.get(), "deployment.flow.refreshTasks", JButton.class).getHeight() > 0);
                            var image = new BufferedImage(content.get().getWidth(), content.get().getHeight(), BufferedImage.TYPE_INT_RGB);
                            var graphics = image.createGraphics();
                            try {
                                graphics.setColor(UIManager.getColor("Panel.background"));
                                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                                content.get().printAll(graphics);
                            } finally {
                                graphics.dispose();
                            }
                            try {
                                assertTrue(ImageIO.write(image, "png", output.resolve("flow-" + theme + "-" + zoom + "-" + (minimum ? "minimum" : "normal") + ".png").toFile()));
                            } catch (java.io.IOException failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
                    }
            assertTrue(service.publications().isEmpty());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    workspace.get().close();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                window.get().dispose();
            });
            await(() -> {
                try {
                    return scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL") == 0;
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (workspace.get() != null) try {
                    workspace.get().close();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                if (window.get() != null) window.get().dispose();
                DesktopThemeService.install(previousTheme, previousZoom);
            });
        }
    }

    private static DeploymentFlowDraft draft(DeploymentFlowPage page) {
        try {
            var field = DeploymentFlowPage.class.getDeclaredField("draft");
            field.setAccessible(true);
            return (DeploymentFlowDraft) field.get(page);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static <T extends Component> T control(Container parent, String name, Class<T> type) {
        if (name.equals(parent.getName())) return type.cast(parent);
        for (var child : parent.getComponents()) {
            if (name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container nested) {
                var found = control(nested, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (var child : parent.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var ready = new boolean[1];
        do {
            SwingUtilities.invokeAndWait(() -> ready[0] = condition.getAsBoolean());
            if (ready[0]) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Actual unified flow did not settle");
    }
}
