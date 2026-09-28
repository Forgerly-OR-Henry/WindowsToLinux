package gold.debug.windowstolinux.mod.deployment.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.task.TaskState;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentStaticRuntimeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the actual lazily registered Swing view, lock/close lifetime and optional visual evidence. / 检查真实惰性登记Swing视图、锁定关闭寿命及可选视觉证据。 */
class DeploymentStaticPageTest extends DeploymentRuntimeFixture {
    /** A closed page can reopen as a fresh instance without pinning or leaking its pending view. / 已关闭页面可作为新实例重开，不固定导航或泄漏待答视图。
     * @throws Exception if actual Mod registration or Swing rendering fails / 实际Mod登记或Swing渲染失败时 */
    @Test
    void normalModRegistersLazyLocalizedPageAndReopensAfterLock() throws Exception {
        enable();
        try (var runtime = open()) {
            runtime.servers()
                    .saveServerProfile(ServerProfile.fromStored(
                            new StoredServerProfile("visual-server", "127.0.0.1", 22, "root", "fixture-password")),
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
            var key = new ModPageRegistry.PageKey(OWNER, "static-deployment");
            assertEquals(List.of(key),
                    pages.contributions().stream().map(ModPageRegistry.PageContribution::key).toList());
            assertNull(mounted.get(), "Registration must remain lazy");
            pages.openView(key);
            var first = mounted.get();
            assertNotNull(first);
            var start = findButton(first, "开始静态部署");
            assertNotNull(start);
            awaitEnabled(start);
            SwingUtilities.invokeAndWait(() -> render(first, "page"));
            pages.environment(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", true));
            assertFalse(start.isEnabled());
            pages.closeView(key);
            assertNull(mounted.get());
            pages.environment(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            pages.openView(key);
            assertNotSame(first, mounted.get());
            awaitEnabled(findButton(mounted.get(), "开始静态部署"));
            pages.closeView(key);
            awaitServerTasks(runtime.modContexts().get(OWNER).context());
        }
    }

    /** Captures the same runtime form used in the modal review without starting a fake publication. / 抓取模态审阅实际复用的运行表单，不启动伪发布。
     * @throws Exception if actual Swing form cannot render / 实际Swing表单无法渲染时 */
    @Test
    void runtimeReviewFormRendersEditableConfigurationAndDatabaseScope() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var page = new DeploymentStaticPage(
                    new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                            new DeploymentStaticWorkflow(context,
                                    new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow(
                                            context))));
            SwingUtilities
                    .invokeAndWait(() -> page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false)));
            var defaults = new LinkedHashMap<String, String>();
            defaults.put("healthMode", "TCP");
            defaults.put("configuration", "RUNTIME:PORT=8080");
            defaults.put("secrets", "db-password:2");
            defaults.put("databaseMode", "NONE");
            defaults.put("databaseDetails", "");
            var fields = new DeploymentStaticRuntimeResolver().corrections("service", defaults);
            var prompt = new DeploymentStaticWorkflow.Prompt("service", "service", DeploymentProjectKind.GO_SERVICE,
                    defaults, fields);
            var formClass = Class.forName(DeploymentStaticPageReviewer.class.getName() + "$ComponentForm");
            var constructor = formClass.getDeclaredConstructor(DeploymentStaticPage.class,
                    DeploymentStaticWorkflow.Prompt.class);
            constructor.setAccessible(true);
            var panelMethod = formClass.getDeclaredMethod("panel");
            panelMethod.setAccessible(true);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var form = constructor.newInstance(page, prompt);
                    var panel = (JPanel) panelMethod.invoke(form);
                    assertNotNull(findLabel(panel, "普通配置条目"));
                    assertNotNull(findLabel(panel, "已存在的秘密修订"));
                    assertNotNull(findLabel(panel, "数据库绑定方式"));
                    render(new JOptionPane(new JScrollPane(panel), JOptionPane.QUESTION_MESSAGE,
                            JOptionPane.OK_CANCEL_OPTION), "runtime-review");
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            SwingUtilities.invokeAndWait(page::close);
        }
    }

    /** Locking or closing an unanswered modal invalidates its original view generation and releases the waiting worker. / 锁定或关闭未答模态框会撤销原视图代次并释放等待中的工作线程。
     * @param lock whether the owner is locked instead of closed / 是否以锁定代替关闭
     * @throws Exception if the real Swing modal or task fails / 真实 Swing 模态框或任务失败时 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unansweredReviewReturnsAfterLockOrClose(boolean lock) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var page = new DeploymentStaticPage(
                    new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                            new DeploymentStaticWorkflow(context,
                                    new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow(
                                            context))));
            SwingUtilities
                    .invokeAndWait(() -> page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false)));
            var handle = context.tasks().submit(new TaskRequest("modal-review-test", "Modal review", List.of(), true),
                    task -> new OperationResult<>(Outcome.SUCCEEDED,
                            Optional.of(page.approve(0, new JPanel(), "Review", task)), Optional.empty()));
            awaitDialog();
            SwingUtilities.invokeAndWait(() -> {
                if (lock)
                    page.environmentChanged(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", true));
                else
                    page.close();
            });
            var result = handle.completion().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(Outcome.SUCCEEDED, result.outcome());
            assertEquals(false, result.value().orElseThrow());
            if (lock)
                SwingUtilities.invokeAndWait(page::close);
            awaitServerTasks(context);
        }
    }

    /** An accepted final decision remains owned by its admitted task when the page closes. / 最终决定获接受后，页面关闭仍由已准入原任务持有。
     * @throws Exception if Swing approval or original task fails / Swing 审阅或原任务失败时 */
    @Test
    void closingAfterFinalConsentDoesNotCancelOriginalTask() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var page = new DeploymentStaticPage(
                    new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                            new DeploymentStaticWorkflow(context,
                                    new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow(
                                            context))));
            SwingUtilities
                    .invokeAndWait(() -> page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false)));
            var accepted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var cancelled = new AtomicBoolean();
            var handle = context.tasks().submit(new TaskRequest("final-review-test", "Final review", List.of(), true),
                    task -> {
                        boolean approved = page.approve(0, new JPanel(), "Final review", task, true);
                        accepted.countDown();
                        if (!release.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("Original task release timed out");
                        cancelled.set(task.cancellation().requested());
                        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(approved), Optional.empty());
                    });
            var field = DeploymentStaticPage.class.getDeclaredField("currentTask");
            field.setAccessible(true);
            field.set(page, handle);
            try {
                var dialog = awaitDialog("Final review");
                SwingUtilities.invokeAndWait(() -> {
                    var pane = findOptionPane(dialog);
                    assertNotNull(pane);
                    pane.setValue(JOptionPane.OK_OPTION);
                    dialog.dispose();
                });
                assertTrue(accepted.await(10, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(page::close);
            } finally {
                release.countDown();
            }
            var result = handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(Outcome.SUCCEEDED, result.outcome());
            assertTrue(result.value().orElseThrow());
            assertFalse(cancelled.get());
            awaitServerTasks(context);
        }
    }

    private static void awaitDialog() throws Exception {
        awaitDialog("Review");
    }

    private static JDialog awaitDialog(String title) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        var visible = new AtomicReference<JDialog>();
        while (System.nanoTime() < until) {
            SwingUtilities.invokeAndWait(() -> visible.set(Arrays.stream(Window.getWindows())
                    .filter(window -> window instanceof JDialog dialog && dialog.isVisible()
                            && dialog.getTitle().equals(title))
                    .map(window -> (JDialog) window).findFirst().orElse(null)));
            if (visible.get() != null)
                return visible.get();
            Thread.sleep(20);
        }
        fail("Original owner review dialog did not appear");
        return null;
    }

    private static JOptionPane findOptionPane(java.awt.Container root) {
        for (var child : root.getComponents()) {
            if (child instanceof JOptionPane pane)
                return pane;
            if (child instanceof java.awt.Container nested) {
                var found = findOptionPane(nested);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static JButton findButton(java.awt.Container root, String text) {
        for (var child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText()))
                return button;
            if (child instanceof java.awt.Container nested) {
                var found = findButton(nested, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static void awaitEnabled(JButton button) throws Exception {
        assertNotNull(button);
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        var enabled = new AtomicReference<Boolean>();
        while (System.nanoTime() < until) {
            SwingUtilities.invokeAndWait(() -> enabled.set(button.isEnabled()));
            if (Boolean.TRUE.equals(enabled.get()))
                return;
            Thread.sleep(20);
        }
        fail("Actual server-list task did not populate the page");
    }

    private static void awaitServerTasks(gold.debug.windowstolinux.appapi.mod.ModContext context) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            var tasks = context.tasks().list(Optional.empty(), 200).items();
            if (tasks.stream().filter(item -> item.type().equals("deployment-server-list"))
                    .allMatch(item -> item.state() == TaskState.ENDED))
                return;
            Thread.sleep(20);
        }
        fail("Original server-list tasks did not release their Mod occupancy");
    }

    private static JLabel findLabel(java.awt.Container root, String text) {
        for (var child : root.getComponents()) {
            if (child instanceof JLabel label && text.equals(label.getText()))
                return label;
            if (child instanceof java.awt.Container nested) {
                var found = findLabel(nested, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static void render(JComponent component, String name) {
        String directory = System.getProperty("static.page.screenshot.dir", "");
        if (directory.isBlank())
            return;
        try {
            var destination = Path.of(directory, name + ".png");
            Files.createDirectories(destination.getParent());
            var window = new JFrame("Deployment static page visual check");
            window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            window.setContentPane(component);
            window.setSize(860, 620);
            window.setLocationByPlatform(true);
            window.setVisible(true);
            var image = new BufferedImage(860, 620, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                window.printAll(graphics);
            } finally {
                graphics.dispose();
                window.dispose();
            }
            ImageIO.write(image, "png", destination.toFile());
        } catch (Exception failure) {
            throw new AssertionError("Actual Swing view could not render", failure);
        }
    }
}
