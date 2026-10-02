package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.appapi.task.AuthorizationMode;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskHandle;

import static gold.debug.windowstolinux.app.main.start.PlatformAuthorizationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformRemoteCapabilitiesTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.task.PlatformAuthorizationService.Launch;
import gold.debug.windowstolinux.app.ui.task.WorkflowLaunchButton;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;

import gold.debug.windowstolinux.appapi.ui.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformAuthorizationWindowTest {
    @TempDir
    Path root;

    @Test
    void realSelectorPersistsIndependentPreferenceAndRollsBackFailedWrite() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            runtime.persistence().preferences().save("deploy_approval", "MANUAL_REVIEW");
            var frame = onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT);
                var window = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                    ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                    FailureReportStore.disabled());
                window.installAuthorization(runtime.authorization());
                ((JTabbedPane) named(window, "platform.pages"))
                    .setSelectedComponent(named(window, "platform.authorization"));
                window.setVisible(true);
                return window;
            });
            try {
                await(() -> named(frame, "authorization.mode").isEnabled());
                onEdt(() -> {
                    @SuppressWarnings("unchecked")
                    var selector = (JComboBox<AuthorizationMode>) named(frame, "authorization.mode");
                    assertEquals(AuthorizationMode.GENERAL, selector.getSelectedItem());
                    for (var mode : AuthorizationMode.values()) {
                        var label = (JLabel) selector.getRenderer().getListCellRendererComponent(new JList<>(), mode, 0,
                            false, false);
                        assertNotNull(label.getIcon());
                        assertTrue(label.getText().endsWith("授权"));
                    }
                    selector.setSelectedItem(AuthorizationMode.FULL);
                    return null;
                });
                await(() -> ((JLabel) named(frame, "authorization.status")).getText().startsWith("已保存"));
                assertEquals(AuthorizationMode.FULL, runtime.authorization().mode());
                assertEquals("MANUAL_REVIEW",
                    runtime.persistence().preferences().find("deploy_approval").orElseThrow());
                sql(runtime.persistence().database(),
                    "ALTER TABLE desktop_preference ADD CONSTRAINT fixture_auth_save CHECK(preference_value <> 'ONCE')");
                try {
                    onEdt(() -> {
                        ((JComboBox<?>) named(frame, "authorization.mode")).setSelectedItem(AuthorizationMode.ONCE);
                        return null;
                    });
                    await(() -> ((JLabel) named(frame, "authorization.status")).getText().startsWith("操作未获确认"));
                    onEdt(() -> {
                        assertEquals(AuthorizationMode.FULL,
                            ((JComboBox<?>) named(frame, "authorization.mode")).getSelectedItem());
                        capture(frame, "platform-authorization-settings.png");
                        return null;
                    });
                    assertEquals(AuthorizationMode.FULL, runtime.authorization().mode());
                } finally {
                    sql(runtime.persistence().database(),
                        "ALTER TABLE desktop_preference DROP CONSTRAINT fixture_auth_save");
                }
                onEdt(() -> {
                    ((JButton) named(frame, "security.lock")).doClick(0);
                    return null;
                });
                await(() -> !named(frame, "authorization.mode").isEnabled());
                runtime.security().unlock(PASSWORD.toCharArray());
                await(() -> named(frame, "authorization.mode").isEnabled());
                await(() -> !DesktopTaskExecutor.hasActiveTasks());
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void nativeStartCapturesSelectedTargetAndAcceptedWorkflowSurvivesViewDetach() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.authorization().saveMode(AuthorizationMode.FULL);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var accepted = new CompletableFuture<TaskHandle<Void>>();
                var continueWork = new CountDownLatch(1);
                var reads = new AtomicInteger();
                var selection = new AtomicReference<>(APP);
                DesktopTaskExecutor.bindSecurity(runtime.security());
                var button = onEdt(() -> new WorkflowLaunchButton<Void>("一键部署", capabilities.launcher(), () -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    reads.incrementAndGet();
                    return new Launch<>(request("server"), Set.of(selection.get()), task -> {
                        assertTrue(continueWork.await(8, TimeUnit.SECONDS));
                        return repeat(capabilities, task);
                    });
                }, accepted::complete, accepted::completeExceptionally));
                var frame = onEdt(() -> {
                    var window = new JFrame();
                    window.add(button);
                    window.setSize(300, 120);
                    window.setVisible(true);
                    assertFalse(button.isEnabled());
                    button.doClick(0);
                    assertEquals(0, reads.get());
                    button.setLocked(false);
                    button.doClick(0);
                    button.doClick(0);
                    selection.set(OTHER);
                    assertEquals(1, reads.get());
                    return window;
                });
                try {
                    var handle = accepted.get(8, TimeUnit.SECONDS);
                    onEdt(() -> {
                        assertFalse(button.isEnabled());
                        button.close();
                        frame.dispose();
                        return null;
                    });
                    continueWork.countDown();
                    assertEquals(Outcome.SUCCEEDED, result(handle).outcome());
                    assertEquals(3, remote.commands.get());
                    assertEquals(0, prompts(reviews));
                    await(() -> !DesktopTaskExecutor.hasActiveTasks());
                    onEdt(() -> {
                        assertFalse(button.isEnabled());
                        button.doClick(0);
                        assertEquals(1, reads.get());
                        return null;
                    });
                } finally {
                    continueWork.countDown();
                    onEdt(() -> {
                        button.close();
                        frame.dispose();
                        return null;
                    });
                    DesktopTaskExecutor.unbindSecurity(runtime.security());
                }
            }
        }
    }
}
