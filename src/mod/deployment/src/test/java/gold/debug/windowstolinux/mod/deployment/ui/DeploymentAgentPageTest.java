package gold.debug.windowstolinux.mod.deployment.ui;

import gold.debug.windowstolinux.appapi.task.TaskSnapshot;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

/** Uses the real lazy CLASS page and original host task while switching language, locking and cancelling. / 切换语言、锁定和取消时使用真实惰性 CLASS 页面及原宿主任务。 */
class DeploymentAgentPageTest extends DeploymentRuntimeFixture {
    private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

    private final AtomicReference<TaskSnapshot> selected = new AtomicReference<>();

    private final AtomicReference<CancelToken> original = new AtomicReference<>();

    private final AtomicBoolean cancelled = new AtomicBoolean();

    private JFrame window;

    @Override
    protected gold.debug.windowstolinux.app.main.start.PlatformRuntime open() throws Exception {
        var runtime = gold.debug.windowstolinux.app.main.start.PlatformRuntime.open(root.resolve("data"), PROTECTION);
        if (runtime.security().securityState().configured())
            runtime.security().unlock(PASSWORD.toCharArray());
        else
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.setInteractionPresenter(interactionPrompts());
        runtime.setSourcePicker((title, snapshot, token) -> {
            original.set(token);
            selected.set(snapshot);
            entered.countDown();
            try {
                assertTrue(release.await(20, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            cancelled.set(token.requested());
            return root.resolve("input");
        });
        runtime.started();
        return runtime;
    }

    @Test
    void lazyOwnerPagePreservesDraftAndSettlesOriginalCancellationAcrossLock() throws Exception {
        Files.createDirectories(root.resolve("input"));
        Files.writeString(root.resolve("input/README.md"), "Controlled original source\n");
        enable();
        try (var runtime = open()) {
            // Fixture metadata satisfies the existing pre-source model admission; no model call is made. / 夹具元数据满足既有来源前模型准入，不调用模型。
            try (var operation = runtime.security().enterOperation();
                 var credentials = runtime.security().openCredentials()) {
                credentials.save("ai/visual-fixture", "fixture-only".toCharArray());
            }
            runtime.persistence().aiProfiles().saveVerified(
                new gold.debug.windowstolinux.app.db.ai.StoredAiProviderProfile("visual-model",
                    "https://example.invalid/v1/chat/completions", "fixture", "ai/visual-fixture"),
                "Visual fixture", java.time.Instant.now());
            runtime.persistence().aiProfiles().purposes().save(
                gold.debug.windowstolinux.app.ai.model.AiPurposeType.DEPLOYMENT,
                List.of(new gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment("visual-model", true)));
            runtime.servers()
                .saveServerProfile(ServerProfile.fromStored(
                        new StoredServerProfile("visual-agent", "127.0.0.1", 22, "root", "fixture-password")),
                    "fixture-password".toCharArray());
            var mounted = new AtomicReference<JComponent>();
            var pages = new ModPageRegistry(new ModPageRegistry.PageHost() {
                public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
                    mounted.set(component);
                }

                public void focus(ModPageRegistry.PageKey key) {
                }

                public void remove(ModPageRegistry.PageKey key) {
                    mounted.set(null);
                }

                public void environment(UiEnvironment environment) {
                }
            }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            var report = runtime.startDevelopmentMods(workspace(), pages);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            var key = new ModPageRegistry.PageKey(OWNER, "agent-deployment");
            assertTrue(pages.contributions().stream().anyMatch(value -> value.key().equals(key)));
            assertNull(mounted.get(), "Agent registration must remain lazy");
            pages.openView(key);
            JComponent first = mounted.get();
            assertNotNull(first);
            var mode = (JComboBox<?>) named(first, "deployment.preference.approval");
            var source = (JComboBox<?>) named(first, "deployment.agent.source");
            SwingUtilities.invokeAndWait(() -> {
                window = new JFrame();
                window.setContentPane(first);
                window.setSize(1000, 720);
                ((JButton) named(first, "deployment.advanced")).doClick(0);
            });
            var advanced = window.getOwnedWindows()[0];
            var project = (JTextField) named(advanced, "deployment.agent.projectName");
            var url = (JTextField) named(advanced, "deployment.agent.gitUrl");
            var requirements = (JTextArea) ((JScrollPane) named(advanced, "deployment.agent.requirements"))
                .getViewport().getView();
            var start = button(first, "start", Locale.SIMPLIFIED_CHINESE);
            var cancel = button(first, "cancel", Locale.SIMPLIFIED_CHINESE);
            awaitEnabled(start, true);
            SwingUtilities.invokeAndWait(() -> {
                source.setSelectedItem("GIT");
                project.setText("unsubmitted-project");
                url.setText("https://example.invalid/repository.git");
                requirements.setText("goal=retained human choice");
                mode.setSelectedItem("FULL_CONTROL");
            });
            pages.environment(new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
            capture(first);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Start autonomous deployment", start.getText());
                assertEquals("FULL_CONTROL", mode.getSelectedItem());
                assertEquals("unsubmitted-project", project.getText());
                assertEquals("https://example.invalid/repository.git", url.getText());
                assertEquals("goal=retained human choice", requirements.getText());
                source.setSelectedItem("LOCAL");
                mode.setSelectedItem("MANUAL_REVIEW");
            });
            awaitEnabled(start, true);
            SwingUtilities.invokeAndWait(() -> {
                start.doClick();
                assertFalse(start.isEnabled());
            });
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            assertEquals(OWNER, selected.get().ownerId());
            assertEquals(VERSION, selected.get().ownerVersion());
            assertEquals("deployment-agent", selected.get().type());
            pages.environment(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", true));
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(start.isEnabled());
                assertFalse(cancel.isEnabled());
            });
            assertFalse(original.get().requested(), "Locking the view cannot cancel its admitted task");
            pages.environment(new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
            awaitEnabled(cancel, true);
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(start.isEnabled());
                cancel.doClick();
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!original.get().requested() && System.nanoTime() < until)
                Thread.sleep(20);
            assertTrue(original.get().requested());
            release.countDown();
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (scalar(runtime,
                "SELECT COUNT(*) FROM platform_task WHERE task_id='" + selected.get().id() + "'") != 0
                && System.nanoTime() < until)
                Thread.sleep(20);
            assertEquals(0,
                scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE task_id='" + selected.get().id() + "'"),
                diagnostic(runtime, selected.get().id()));
            assertTrue(cancelled.get());
            awaitEnabled(start, true);
            pages.closeView(key);
            assertNull(mounted.get());
            pages.openView(key);
            assertNotSame(first, mounted.get());
            awaitEnabled(button(mounted.get(), "start", Locale.ENGLISH), true);
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE owner_id='" + OWNER + "'") != 0
                && System.nanoTime() < until)
                Thread.sleep(20);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE owner_id='" + OWNER + "'"));
            pages.closeView(key);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(() -> {
                if (window != null)
                    window.dispose();
            });
        }
    }

    private String diagnostic(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime, UUID task)
        throws Exception {
        var details = new StringBuilder();
        try (var connection = runtime.persistence().database().openConnection()) {
            for (String query : List.of("SELECT state,outcome,progress_text FROM platform_task WHERE task_id=?",
                "SELECT resource_kind,resource_id FROM platform_task_resource WHERE task_id=? AND released_at IS NULL"))
                try (var statement = connection.prepareStatement(query)) {
                    statement.setObject(1, task);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next())
                            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                                details.append(rows.getMetaData().getColumnName(i)).append('=')
                                    .append(rows.getString(i)).append(' ');
                    }
                }
        }
        return details.toString();
    }

    private void capture(JComponent page) throws Exception {
        String directory = System.getProperty("agent.page.screenshot.dir");
        if (directory == null)
            return;
        assertFalse(GraphicsEnvironment.isHeadless());
        var failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                if (window == null)
                    window = new JFrame("Actual CLASS autonomous deployment");
                window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                window.setContentPane(page);
                window.pack();
                window.setSize(1000, 720);
                window.setVisible(true);
                window.validate();
                var image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    window.printAll(graphics);
                } finally {
                    graphics.dispose();
                }
                Path file = Path.of(directory).resolve("agent-class-page-en.png");
                Files.createDirectories(file.getParent());
                assertTrue(ImageIO.write(image, "png", file.toFile()));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null)
            throw new AssertionError("Actual visible CLASS page capture failed", failure.get());
    }

    private static Component named(Container root, String name) {
        return descendants(root).stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }

    private static JButton button(JComponent root, String key, Locale locale) {
        String expected = ResourceBundle
            .getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-agent-page", locale).getString(key);
        return descendants(root).stream()
            .filter(value -> value instanceof JButton button && button.getText().equals(expected))
            .map(JButton.class::cast).findFirst().orElseThrow();
    }

    private static List<Component> descendants(Container root) {
        var result = new ArrayList<Component>();
        for (Component child : root.getComponents()) {
            result.add(child);
            if (child instanceof Container container)
                result.addAll(descendants(container));
        }
        return result;
    }

    private static void awaitEnabled(JButton button, boolean expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        var observed = new AtomicBoolean();
        do {
            SwingUtilities.invokeAndWait(() -> observed.set(button.isEnabled()));
            if (observed.get() == expected)
                return;
            Thread.sleep(20);
        } while (System.nanoTime() < until);
        assertEquals(expected, observed.get());
    }
}
