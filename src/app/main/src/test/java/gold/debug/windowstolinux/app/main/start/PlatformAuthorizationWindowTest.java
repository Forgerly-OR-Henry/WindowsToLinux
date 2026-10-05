package gold.debug.windowstolinux.app.main.start;

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
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.task.WorkflowLaunchButton;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.task.action.AuthorizationMode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformAuthorizationWindowTest {
    @TempDir
    Path root;

    @Test
    void actualPlatformSettingsHaveNoAuthorizationPageOrDeploymentPolicyPreference() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var models = runtime.models();
            var frame = onEdt(() -> {
                var window = ApplicationLauncher.createWindow(runtime, MessageCatalog.forLanguageTag("zh-CN"),
                    new DesktopDisplayConfig("zh-CN", ThemeMode.LIGHT), ThemeMode.LIGHT,
                    FailureReportStore.disabled(), models);
                window.setVisible(true);
                return window;
            });
            try {
                runtime.started();
                onEdt(() -> {
                    ((JButton) named(frame, "navigation.settings")).doClick(0);
                    assertNoPlatformAuthorization(frame);
                    ((JButton) named(frame, "navigation.security.settings")).doClick(0);
                    assertTrue(named(frame, "security.settings.password").isShowing(), "Actual platform settings remain reachable");
                    return null;
                });
                onEdt(() -> {
                    ((JButton) named(frame, "security.lock")).doClick(0);
                    return null;
                });
                await(() -> !runtime.security().securityState().unlocked());
                unlockPassword(frame, PASSWORD);
                await(() -> runtime.security().securityState().unlocked()
                    && !runtime.security().securityState().changing());
                await(() -> !DesktopTaskRunner.hasActiveTasks());
                onEdt(() -> {
                    assertNoPlatformAuthorization(frame);
                    return null;
                });
                for (var key : List.of("deploy_mode", "deploy_approval", "deploy_authorization", "platform_authorization_mode"))
                    assertTrue(runtime.persistence().preferences().find(key).isEmpty(), "APP settings must not own " + key);
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void assertNoPlatformAuthorization(PlatformFrame frame) {
        assertTrue(descendants(frame).noneMatch(component -> "platform.authorization".equals(component.getName())
            || "navigation.authorization.title".equals(component.getName())
            || component.getName() != null && component.getName().startsWith("authorization.")));
        var pages = (JTabbedPane) named(frame, "platform.pages");
        for (int index = 0; index < pages.getTabCount(); index++)
            assertNotEquals("平台授权", pages.getTitleAt(index));
        var categories = (JComboBox<?>) named(frame, "settings.category");
        for (int index = 0; index < categories.getItemCount(); index++) {
            var label = (JLabel) ((ListCellRenderer) categories.getRenderer()).getListCellRendererComponent(new JList<>(),
                categories.getItemAt(index), index, false, false);
            assertNotEquals("平台授权", label.getText(), "No detached authorization category may remain");
        }
    }

    @Test
    void nativeStartCapturesSelectedTargetAndAcceptedWorkflowSurvivesViewDetach() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var accepted = new CompletableFuture<TaskHandle<Void>>();
                var continueWork = new CountDownLatch(1);
                var reads = new AtomicInteger();
                var selection = new AtomicReference<>(APP);
                DesktopTaskRunner.bindSecurity(runtime.security());
                var button = onEdt(() -> new WorkflowLaunchButton<Void>("一键部署", capabilities.launcher(), () -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    reads.incrementAndGet();
                    return new Launch<>(request("server"), Set.of(selection.get()), AuthorizationMode.FULL, task -> {
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
                    assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
                    assertEquals(3, remote.commands.get());
                    assertEquals(0, prompts(reviews));
                    await(() -> !DesktopTaskRunner.hasActiveTasks());
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
                    DesktopTaskRunner.unbindSecurity(runtime.security());
                }
            }
        }
    }
}
