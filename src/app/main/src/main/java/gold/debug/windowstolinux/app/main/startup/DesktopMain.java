package gold.debug.windowstolinux.app.main.startup;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.FutureTask;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.bootstrap.DatabaseProtection;
import gold.debug.windowstolinux.app.main.diagnostic.*;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver;
import gold.debug.windowstolinux.app.ui.diagnostic.DesktopFailurePresenter;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.shell.PlatformFrame;
import gold.debug.windowstolinux.app.windows.security.CurrentUserDataProtection;
import gold.debug.windowstolinux.appapi.ui.UiEnvironment;

/** Production platform bootstrap using fixed paths, current-user protection and H2. / 使用固定路径、当前用户保护及 H2 的生产平台引导。 */
public final class DesktopMain {
    /** Minimum free space required before opening the platform store. / 打开平台存储前要求的最小剩余空间。 */
    private static final long MINIMUM_FREE_BYTES = 1024L * 1024L;

    /** Prevents instantiation of the fixed application entry. / 防止实例化固定应用入口。 */
    private DesktopMain() {
    }

    /**
     * Opens the platform and creates the desktop only after storage and security initialize.
     * / 仅在存储及安全初始化后打开平台并创建桌面。
     * @param arguments application launch arguments / 应用启动参数
     */
    public static void launch(String[] arguments) {
        if (SwingUtilities.isEventDispatchThread()) {
            String[] copied = arguments.clone();
            Thread.ofVirtual().name("platform-startup").start(() -> launch(copied));
            return;
        }
        var initialMessages = MessageCatalog.forLanguageTag(Locale.getDefault().toLanguageTag());
        RunModeResolver.RuntimeLayout layout;
        try {
            layout = RunModeResolver.resolve();
        } catch (RuntimeException failure) {
            showStartupFailure(FailureReportStore.disabled(), initialMessages,
                    DesktopStartupException.create(DesktopSystemFailureType.STARTUP_LAYOUT_INVALID,
                            "The application runtime layout could not be resolved safely", failure));
            return;
        }
        var reports = new DesktopFailureReportStore(layout.dataDirectory());
        try {
            verifyDataDirectory(layout.dataDirectory());
            if (GraphicsEnvironment.isHeadless())
                throw new IllegalStateException("An interactive desktop is required");
        } catch (Exception failure) {
            showStartupFailure(reports, initialMessages,
                    DesktopStartupException.create(DesktopSystemFailureType.DATA_DIRECTORY_UNAVAILABLE,
                            "The fixed application data directory or desktop is unavailable", failure));
            return;
        }
        PlatformRuntime runtime;
        try {
            var protection = new CurrentUserDataProtection();
            runtime = PlatformRuntime.open(layout.dataDirectory(), new DatabaseProtection() {
                /** Protects bootstrap bytes for the current Windows user. / 为当前 Windows 用户保护引导字节。
                 * @param clear temporary plaintext / 临时明文
                 * @return protected bytes / 受保护字节 */
                @Override
                public byte[] protect(byte[] clear) {
                    return protection.protect(clear);
                }

                /** Restores bootstrap bytes for the current Windows user. / 为当前 Windows 用户还原引导字节。
                 * @param encrypted protected bytes / 受保护字节
                 * @return temporary plaintext / 临时明文 */
                @Override
                public byte[] unprotect(byte[] encrypted) {
                    return protection.unprotect(encrypted);
                }
            });
        } catch (Exception failure) {
            showStartupFailure(reports, initialMessages,
                    DesktopStartupException.create(DesktopSystemFailureType.DATABASE_INITIALIZATION_FAILED,
                            "Platform persistence failed its bootstrap or schema checks", failure));
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                runtime.close();
            } catch (Exception failure) {
                reports.record(DesktopStartupException.create(DesktopSystemFailureType.SHUTDOWN_FAILED,
                        "Platform resources did not close cleanly", failure));
            }
        }, "windowstolinux-platform-close"));
        PlatformFrame frame = null;
        try {
            var preferences = runtime.persistence().preferences();
            var appearance = DesktopDisplayConfiguration.fromStoredValues(preferences.find("ui_locale").orElse(null),
                    preferences.find("ui_theme").orElse(null), Locale.getDefault());
            var messages = MessageCatalog.forLanguageTag(appearance.localeTag());
            var theme = SystemThemeResolver.effectiveTheme(appearance.themeMode());
            new DesktopUncaughtFailureBoundary(reports, messages).install();
            var models = runtime.models();
            frame = onEdt(() -> {
                DesktopThemeService.install(theme);
                var window = new PlatformFrame(runtime.security(), messages,
                        theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light(),
                        new UiEnvironment(Locale.forLanguageTag(appearance.localeTag()), theme.name(),
                                runtime.security().securityState().configured()),
                        reports);
                window.onExit(runtime);
                var presenter = new gold.debug.windowstolinux.app.ui.shell.TaskInteractionPresenter(window, messages);
                runtime.setInteractionPresenter(presenter);
                runtime.setSourcePicker(presenter);
                window.installServers(runtime.servers());
                var recovery = runtime.recovery();
                window.installRecovery(recovery);
                window.installModels(models);
                window.installTransfers(runtime.transfers());
                window.installAuthorization(runtime.authorization());
                window.installLogs(runtime.logs());
                window.installTasks(new gold.debug.windowstolinux.app.service.task.PlatformTaskManagement(
                        runtime.tasks(), runtime.security(), id -> recovery.reconcile(id, true)));
                return window;
            });
            runtime.started();
            PlatformFrame created = frame;
            onEdt(() -> {
                created.setVisible(true);
                return null;
            });
            var modReport = startMods(runtime, layout, System.getProperty("wtl.workspace"), created.pages());
            installModManagement(runtime, created);
            onEdt(() -> {
                if (created.isDisplayable())
                    created.showModStartup(modReport.running().size(), modReport.problems());
                return null;
            });
        } catch (Exception failure) {
            if (frame != null) {
                PlatformFrame created = frame;
                try {
                    onEdt(() -> {
                        created.dispose();
                        return null;
                    });
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            try {
                runtime.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            showStartupFailure(reports, initialMessages,
                    DesktopStartupException.create(DesktopSystemFailureType.UI_INITIALIZATION_FAILED,
                            "The platform desktop could not be initialized", failure));
        }
    }

    /** Connects the default management page to original runtime operations outside the EDT. / 在 EDT 外将默认管理页面接入原运行时操作。
     * @param runtime original platform runtime / 原平台运行时
     * @param frame original desktop window / 原桌面窗口
     * @throws Exception if original service or view cannot be installed / 无法安装原服务或视图时 */
    static void installModManagement(PlatformRuntime runtime, PlatformFrame frame) throws Exception {
        var service = runtime.modIntents();
        var installation = RunModeResolver.resolve().mode() == RunModeResolver.RunMode.RUN_CLASS
                ? java.util.Optional.<gold.debug.windowstolinux.app.service.mod.PlatformModInstallation>empty()
                : java.util.Optional.of(runtime.modInstallation());
        var actions = new gold.debug.windowstolinux.app.ui.mod.PlatformModsPane.SchemaActions() {
            /** Delegates exact source review to the original graph. / 将精确来源审阅交给原图。
             * @param owner selected owner / 已选所有者
             * @param source confirmed source / 已确认来源
             * @return original review / 原审阅
             * @throws Exception if unavailable / 不可用时 */
            @Override
            public gold.debug.windowstolinux.app.service.mod.PlatformModIntents.RetryReview review(String owner,
                    java.util.Optional<gold.debug.windowstolinux.appapi.db.SchemaIdentity> source) throws Exception {
                return runtime.reviewModSchemaRetry(owner, source);
            }

            /** Delegates only the confirmed original retry. / 仅委派已确认原重试。
             * @param review original review / 原审阅
             * @return observed data result / 已观察数据结果
             * @throws Exception if unconfirmed / 未确认时 */
            @Override
            public gold.debug.windowstolinux.appapi.task.Outcome retry(
                    gold.debug.windowstolinux.app.service.mod.PlatformModIntents.RetryReview review) throws Exception {
                return runtime.retryModSchema(review);
            }

            /** Delegates original read-only reconciliation. / 委派原只读核对。
             * @param operation original preparation / 原准备
             * @return verified data result / 已核实数据结果
             * @throws Exception if unconfirmed / 未确认时 */
            @Override
            public gold.debug.windowstolinux.appapi.task.Outcome reconcile(java.util.UUID operation) throws Exception {
                return runtime.reconcileModSchema(operation);
            }
        };
        onEdt(() -> {
            if (frame.isDisplayable()) {
                frame.installMods(service, actions);
                frame.installOriginalPackages(installation);
            }
            return null;
        });
    }

    /** Applies default startup to the verified source-mode-specific program root. / 对已验证的对应来源模式程序根应用默认启动。
     * @param runtime original platform runtime / 原平台运行时
     * @param layout verified executable and data anchors / 已验证程序及数据锚点
     * @param declared literal wtl.workspace value / 原样 wtl.workspace 值
     * @param pages actual desktop page registry / 实际桌面页面登记
     * @return safe startup facts without making the platform shell unavailable / 安全启动事实，不使平台外壳不可用 */
    static gold.debug.windowstolinux.app.main.mod.PlatformModCoordinator.Report startMods(PlatformRuntime runtime,
            RunModeResolver.RuntimeLayout layout, String declared,
            gold.debug.windowstolinux.app.ui.mod.ModPageRegistry pages) {
        try {
            if (!runtime.persistence().database().root().equals(layout.dataDirectory().toRealPath()))
                throw new java.io.IOException("Layout does not match the running platform database");
            if (layout.mode() != RunModeResolver.RunMode.RUN_CLASS)
                return runtime.startInstalledMods(layout.applicationHome(), pages);
            if (declared == null || declared.isBlank())
                throw new java.io.IOException("Explicit CLASS workspace is missing");
            Path root = gold.debug.windowstolinux.app.mod.development.DevelopmentDescriptor
                    .workspace(Path.of(declared));
            if (!root.equals(layout.applicationHome().toRealPath())
                    || !runtime.persistence().database().root().equals(layout.dataDirectory().toRealPath()))
                throw new java.io.IOException("Explicit workspace does not match the running platform");
            return runtime.startDevelopmentMods(root, pages);
        } catch (Exception | LinkageError | AssertionError failure) {
            var problem = failure instanceof gold.debug.windowstolinux.appapi.task.ApiException api
                    ? api.problem()
                    : new gold.debug.windowstolinux.appapi.task.Problem(
                            gold.debug.windowstolinux.appapi.task.ProblemCode.UNAVAILABLE, "MOD_WORKSPACE_UNAVAILABLE",
                            "Mod startup is unavailable; verify the program layout and original lifecycle state",
                            java.util.Map.of(), java.util.Optional.of(java.util.UUID.randomUUID()));
            return new gold.debug.windowstolinux.app.main.mod.PlatformModCoordinator.Report(java.util.Set.of(),
                    java.util.List.of(problem));
        }
    }

    /**
     * Waits for actual EDT completion even if the startup worker is interrupted.
     * / 即使启动工作线程被中断也等待 EDT 实际完成。
     * @param <T> returned value type / 返回值类型
     * @param action event-thread action / 事件线程动作
     * @return actual completed value / 实际完成的值
     * @throws Exception if the event-thread operation fails / 事件线程操作失败时
     */
    private static <T> T onEdt(java.util.concurrent.Callable<T> action) throws Exception {
        var pending = new FutureTask<>(action);
        SwingUtilities.invokeLater(pending);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return pending.get();
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    /**
     * Validates the fixed data root without creating business directories. / 校验固定数据根，不创建业务目录。
     * @param root fixed runtime data directory / 固定运行数据目录
     * @throws java.io.IOException if the directory is unsafe or unavailable / 目录不安全或不可用时
     */
    private static void verifyDataDirectory(Path root) throws java.io.IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(root)
                || Files.getFileStore(root).getUsableSpace() < MINIMUM_FREE_BYTES)
            throw new java.io.IOException("Fixed data directory failed writable-directory checks");
    }

    /**
     * Presents a bounded startup failure without exposing raw exception details. / 展示有界启动失败，不暴露原始异常细节。
     * @param reports bounded diagnostic writer / 有界诊断写入器
     * @param messages localized messages / 本地化消息
     * @param failure structured startup failure / 结构化启动失败
     */
    private static void showStartupFailure(FailureReportStore reports, MessageCatalog messages,
            DesktopStartupException failure) {
        String text = new DesktopFailurePresenter(messages::text, reports).present(failure);
        if (GraphicsEnvironment.isHeadless())
            System.err.println(text);
        else
            SwingUtilities.invokeLater(
                    () -> JOptionPane.showMessageDialog(null, text, "WindowsToLinux", JOptionPane.ERROR_MESSAGE));
    }
}
