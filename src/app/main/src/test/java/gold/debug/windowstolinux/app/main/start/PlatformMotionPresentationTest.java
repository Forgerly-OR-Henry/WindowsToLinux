package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.swing.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.TaskManagement;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.server.PlatformServersPane;
import gold.debug.windowstolinux.app.ui.task.TaskRecoveryPane;
import gold.debug.windowstolinux.appapi.task.model.*;
import gold.debug.windowstolinux.appapi.task.control.*;
import gold.debug.windowstolinux.appapi.task.result.*;

/** Real Swing frames and geometry, using isolated storage and no remote calls. */
class PlatformMotionPresentationTest {
    @TempDir
    Path directory;

    @Test
    void motionPreferenceReopensAndInvalidValueIsRetainedWithoutAddingPhysicalColumns() throws Exception {
        try (var runtime = PlatformRuntime.open(directory, PlatformStartupTest.PROTECTION)) {
            PlatformServerServiceTest.setup(runtime);
            var preferences = runtime.persistence().preferences();
            assertTrue(DesktopDisplayConfig.fromStoredValues("en", "LIGHT", "100",
                preferences.find("ui_animation_enabled").orElse(null), "false", Locale.ENGLISH).animationEnabled());
            preferences.save("ui_animation_enabled", "false");
        }
        try (var runtime = PlatformRuntime.open(directory, PlatformStartupTest.PROTECTION)) {
            var preferences = runtime.persistence().preferences();
            assertFalse(DesktopDisplayConfig.fromStoredValues("en", "LIGHT", "100",
                preferences.find("ui_animation_enabled").orElse(null), "false", Locale.ENGLISH).animationEnabled());
            try (var connection = runtime.persistence().database().openConnection(); var statement = connection.createStatement();
                 var columns = statement.executeQuery("SELECT column_name, ordinal_position FROM information_schema.columns "
                     + "WHERE table_schema='app' AND table_name='desktop_preference' ORDER BY ordinal_position")) {
                assertTrue(columns.next());
                assertEquals("preference_key", columns.getString(1));
                assertEquals(1, columns.getInt(2));
                assertTrue(columns.next());
                assertEquals("preference_value", columns.getString(1));
                assertEquals(2, columns.getInt(2));
                assertFalse(columns.next());
            }
            preferences.save("ui_animation_enabled", "malformed");
            assertThrows(IllegalArgumentException.class, () -> DesktopDisplayConfig.fromStoredValues("en", "LIGHT", "100",
                preferences.find("ui_animation_enabled").orElse(null), "false", Locale.ENGLISH));
            assertEquals("malformed", preferences.find("ui_animation_enabled").orElseThrow());
        }
    }

    @Test
    void actualServerAndTaskDrawersReverseFinishAndRenderEveryDisplayCombination() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean original = DesktopMotion.preference();
        System.setProperty("flatlaf.animation", "true");
        try (var runtime = PlatformRuntime.open(directory, PlatformStartupTest.PROTECTION)) {
            var service = PlatformServerServiceTest.setup(runtime);
            runtime.started();
            service.saveServerProfile(new ServerProfile("motion", "192.0.2.10", 22, "deploy", "motion/credential",
                "生产服务器 / Production server"), "isolated-motion-fixture".toCharArray());
            for (String language : new String[]{"zh-CN", "en"})
                for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                    for (int scale : new int[]{100, 150})
                        for (boolean minimum : new boolean[]{false, true}) {
                            var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag(language));
                            JFrame frame = onEdt(() -> {
                                DesktopThemeService.install(theme, scale);
                                DesktopMotion.preference(true);
                                var window = new JFrame("Motion verification");
                                window.setSize(minimum ? 1120 : 1440, minimum ? 630 : 810);
                                window.setLocation(30, 30);
                                return window;
                            });
                            var components = new DesktopPartFactory(theme == ThemeMode.LIGHT ? ThemePalette.light() : ThemePalette.dark());
                            PlatformServersPane servers = onEdt(() -> {
                                var pane = new PlatformServersPane(frame, service, components, messages);
                                frame.setContentPane(pane);
                                frame.setVisible(true);
                                pane.setLocked(false);
                                return pane;
                            });
                            try {
                                await(() -> ((JTable) named(servers, "servers.table")).getRowCount() == 1
                                    && named(servers, "servers.refresh").isEnabled());
                                exercise(frame, servers, (DesktopSplitPane) named(servers, "servers.workspace"),
                                    () -> ((JTable) named(servers, "servers.table")).setRowSelectionInterval(0, 0),
                                    () -> ((JTable) named(servers, "servers.table")).clearSelection(),
                                    language.equals("zh-CN") && theme == ThemeMode.LIGHT && scale == 100 && !minimum ? "servers" : null);
                                onEdt(() -> {
                                    servers.close();
                                    return null;
                                });
                                TaskRecoveryPane tasks = onEdt(() -> {
                                    var pane = new TaskRecoveryPane(new Tasks(), components, messages, () -> {
                                    });
                                    frame.setContentPane(pane);
                                    frame.validate();
                                    pane.setLocked(false);
                                    return pane;
                                });
                                try {
                                    await(() -> ((JList<?>) named(tasks, "tasks.list")).getModel().getSize() > 0
                                        && named(tasks, "tasks.reload").isEnabled());
                                    var split = onEdt(() -> descendants(tasks).filter(DesktopSplitPane.class::isInstance)
                                        .map(DesktopSplitPane.class::cast).findFirst().orElseThrow());
                                    exercise(frame, tasks, split, () -> {
                                            var list = (JList<?>) named(tasks, "tasks.list");
                                            for (int i = 0; i < list.getModel().getSize(); i++)
                                                if (list.getModel().getElementAt(i) instanceof TaskManagement.Entry) {
                                                    list.setSelectedIndex(i);
                                                    break;
                                                }
                                        }, () -> ((JList<?>) named(tasks, "tasks.list")).clearSelection(),
                                        language.equals("zh-CN") && theme == ThemeMode.LIGHT && scale == 100 && !minimum ? "tasks" : null);
                                } finally {
                                    onEdt(() -> {
                                        tasks.close();
                                        return null;
                                    });
                                }
                            } finally {
                                onEdt(() -> {
                                    servers.close();
                                    frame.dispose();
                                    return null;
                                });
                            }
                        }
        } finally {
            onEdt(() -> {
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(original);
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                return null;
            });
        }
    }

    private static void exercise(JFrame frame, JComponent pane, DesktopSplitPane split,
                                 Runnable open, Runnable close, String capture) throws Exception {
        var frames = new ArrayList<BufferedImage>();
        onEdt(() -> {
            layout(frame);
            open.run();
            layout(frame);
            return null;
        });
        if (onEdt(DesktopMotion::enabled)) {
            Thread.sleep(55);
            double midway = onEdt(split::progress);
            assertTrue(midway > 0 && midway < 1, "Visible drawer must have an intermediate frame: " + midway);
            onEdt(() -> {
                double before = split.progress();
                close.run();
                assertEquals(before, split.progress(), .000001, "Fast reversal must retain the current pose");
                return null;
            });
            Thread.sleep(280);
            assertEquals(0, onEdt(split::progress), .000001);
            onEdt(() -> {
                open.run();
                return null;
            });
            for (int i = 0; i < 10; i++) {
                if (capture != null) frames.add(onEdt(() -> image(pane)));
                Thread.sleep(32);
            }
            assertEquals(1, onEdt(split::progress), .000001);
            onEdt(() -> {
                close.run();
                return null;
            });
            for (int i = 0; i < 10; i++) {
                if (capture != null) frames.add(onEdt(() -> image(pane)));
                Thread.sleep(32);
            }
        }
        onEdt(() -> {
            DesktopMotion.preference(false);
            open.run();
            layout(frame);
            assertEquals(1, split.progress(), .000001);
            close.run();
            layout(frame);
            assertEquals(0, split.progress(), .000001);
            DesktopMotion.preference(true);
            return null;
        });
        String output = System.getProperty("desktop.motion.capture");
        if (capture != null && output != null && !frames.isEmpty())
            gif(frames, Path.of(output).resolve(capture + ".gif"));
    }

    private static void layout(Component component) {
        if (component instanceof Container parent) {
            parent.doLayout();
            for (var child : parent.getComponents()) layout(child);
        }
    }

    private static BufferedImage image(JComponent pane) {
        layout(pane);
        var image = new BufferedImage(pane.getWidth(), pane.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        pane.printAll(graphics);
        graphics.dispose();
        return image;
    }

    private static void gif(List<BufferedImage> frames, Path output) throws Exception {
        Files.createDirectories(output.getParent());
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var stream = ImageIO.createImageOutputStream(output.toFile())) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (int index = 0; index < frames.size(); index++) {
                var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frames.get(index)), null);
                String format = metadata.getNativeMetadataFormatName();
                var root = (IIOMetadataNode) metadata.getAsTree(format);
                var control = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("delayTime", index == frames.size() - 1 ? "70" : "3");
                control.setAttribute("disposalMethod", "none");
                if (index == 0) {
                    var extensions = new IIOMetadataNode("ApplicationExtensions");
                    var loop = new IIOMetadataNode("ApplicationExtension");
                    loop.setAttribute("applicationID", "NETSCAPE");
                    loop.setAttribute("authenticationCode", "2.0");
                    loop.setUserObject(new byte[]{1, 0, 0});
                    extensions.appendChild(loop);
                    root.appendChild(extensions);
                }
                metadata.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(frames.get(index), null, metadata), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static final class Tasks implements TaskManagement {
        private final TaskSnapshot snapshot = new TaskSnapshot(UUID.randomUUID(), "gold.debug.windowstolinux.mod.deployment",
            "1.0.0", "deployment", TaskState.RUNNING, Optional.empty(), "发布应用 / Publishing application",
            Instant.now(), Optional.empty(), List.of());

        public List<Entry> list() {
            var controls = new TaskControlSnapshot(TaskControlSnapshot.State.RUNNING, true, false, true, "");
            return List.of(new Entry(snapshot, true, RecoveryState.NOT_READY, "部署应用 · inventory-api",
                TaskManagement.projection(snapshot, RecoveryState.NOT_READY, Optional.of(.6), controls)));
        }

        public TaskSnapshot requestCancel(UUID id) {
            throw new AssertionError("Presentation cannot cancel business");
        }

        public TaskSnapshot continueWaiting(UUID id) {
            throw new AssertionError("Presentation cannot approve business");
        }

        public CompletionStage<TaskSnapshot> reconcile(UUID id, boolean confirmed) {
            throw new AssertionError("Presentation cannot recover business");
        }
    }
}
