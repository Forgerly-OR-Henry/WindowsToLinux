package gold.debug.windowstolinux.app.main.start;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.FutureTask;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.init.DatabaseProtection;
import gold.debug.windowstolinux.app.main.error.DesktopFailureReportStore;
import gold.debug.windowstolinux.app.main.error.DesktopStartupException;
import gold.debug.windowstolinux.app.main.error.DesktopSystemFailureType;
import gold.debug.windowstolinux.app.main.error.DesktopUncaughtFailureBoundary;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfig;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.SystemThemeResolver;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.error.DesktopFailurePresenter;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.app.windows.security.CurrentUserProtection;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;

/** Production platform bootstrap using fixed paths, current-user protection and H2. / 使用固定路径、当前用户保护及 H2 的生产平台引导。 */
public final class ApplicationLauncher {
    /** Minimum free space required before opening the platform store. / 打开平台存储前要求的最小剩余空间。 */
    private static final long MINIMUM_FREE_BYTES = 1024L * 1024L;

    /** Prevents instantiation of the fixed application entry. / 防止实例化固定应用入口。 */
    private ApplicationLauncher() {
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
        java.util.Optional<ApplicationRestartReceipt> acknowledgement;
        try {
            acknowledgement = ApplicationRestartReceipt.accept(arguments);
        } catch (java.io.IOException failure) {
            showStartupFailure(FailureReportStore.disabled(), MessageCatalog.forLanguageTag(Locale.getDefault().toLanguageTag()),
                DesktopStartupException.create(DesktopSystemFailureType.STARTUP_LAYOUT_INVALID,
                    "The internal restart acknowledgement is invalid", failure));
            return;
        }
        boolean ready = false;
        try {
            ready = launchDesktop();
        } finally {
            if (acknowledgement.isPresent()) {
                try {
                    acknowledgement.get().signal(ready ? "READY" : "FAILED");
                } catch (java.io.IOException failure) {
                    showStartupFailure(FailureReportStore.disabled(), MessageCatalog.forLanguageTag(Locale.getDefault().toLanguageTag()),
                        DesktopStartupException.create(DesktopSystemFailureType.UI_INITIALIZATION_FAILED,
                            "The original restart acknowledgement could not be completed", failure));
                }
            }
        }
    }

    /** Performs one normal fresh-process startup through all existing Mod and navigation results. / 经过全部现有 Mod 与导航结果执行一次正常的新进程启动。
     * @return whether the actual startup completed / 实际启动是否完成 */
    private static boolean launchDesktop() {
        var initialMessages = MessageCatalog.forLanguageTag(Locale.getDefault().toLanguageTag());
        PlatformRuntime.Startup startup;
        try {
            startup = readStartup();
        } catch (DesktopStartupException failure) {
            showStartupFailure(FailureReportStore.disabled(), initialMessages, failure);
            return false;
        }
        var layout = startup.layout();
        var config = startup.config();
        String diagnosticVersion;
        try {
            diagnosticVersion = PlatformBuildIdentity.readVersion();
        } catch (java.io.IOException unavailable) {
            diagnosticVersion = "unavailable";
        }
        var reports = new DesktopFailureReportStore(layout.dataDirectory(), diagnosticVersion, config.emergencyRecords());
        try {
            verifyDataDirectory(layout.dataDirectory());
            if (GraphicsEnvironment.isHeadless())
                throw new IllegalStateException("An interactive desktop is required");
        } catch (Exception failure) {
            showStartupFailure(reports, initialMessages,
                DesktopStartupException.create(DesktopSystemFailureType.DATA_DIRECTORY_UNAVAILABLE,
                    "The fixed application data directory or desktop is unavailable", failure));
            return false;
        }
        PlatformRuntime runtime;
        try {
            var protection = new CurrentUserProtection();
            runtime = PlatformRuntime.open(layout, new DatabaseProtection() {
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
            }, config);
        } catch (Exception failure) {
            showStartupFailure(reports, initialMessages,
                DesktopStartupException.create(DesktopSystemFailureType.DATABASE_INITIALIZATION_FAILED,
                    "Platform persistence failed its bootstrap or schema checks", failure));
            return false;
        }
        reports.attach(runtime.logs(), runtime.version());
        attachShutdown(runtime, reports);
        PlatformFrame frame = null;
        try {
            var preferences = runtime.persistence().preferences();
            var appearance = DesktopDisplayConfig.fromStoredValues(preferences.find("ui_locale").orElse(config.defaults().locale().equals("system") ? null : config.defaults().locale()),
                preferences.find("ui_theme").orElse(config.defaults().theme()),
                preferences.find("ui_scale").orElse(null), preferences.find("ui_restore_last_page").orElse(null), Locale.getDefault());
            var messages = MessageCatalog.forLanguageTag(appearance.localeTag());
            var theme = SystemThemeResolver.effectiveTheme(appearance.themeMode());
            new DesktopUncaughtFailureBoundary(reports, messages).install();
            var models = runtime.models();
            frame = onEdt(() -> createWindow(runtime, messages, appearance, theme, reports, models));
            restoreStartupPage(runtime, appearance, frame);
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
            onEdt(() -> {
                created.finishStartupNavigation();
                return null;
            });
            return onEdt(created::isDisplayable);
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
            return false;
        }
    }

    /** Attaches runtime cleanup after persistence is successfully opened. / 存储成功打开后接入运行时清理。
     * @param runtime opened runtime owner / 已打开运行时所有者
     * @param reports diagnostic sink / 诊断接收器 */
    private static void attachShutdown(PlatformRuntime runtime, DesktopFailureReportStore reports) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                runtime.close();
            } catch (Exception failure) {
                reports.record(DesktopStartupException.create(DesktopSystemFailureType.SHUTDOWN_FAILED,
                    "Platform resources did not close cleanly", failure));
            }
        }, "windowstolinux-platform-close"));
    }

    /** Loads page identity away from the EDT and applies only the explicit startup preference. / 离开 EDT 加载页面身份，只应用明确的启动偏好。
     * @param runtime current storage owner / 当前存储所有者
     * @param appearance confirmed display and startup choice / 已确认显示及启动选择
     * @param window assembled window / 已装配窗口
     * @throws Exception if retained preferences cannot be verified / 保留偏好无法核实时 */
    static void restoreStartupPage(PlatformRuntime runtime, DesktopDisplayConfig appearance, PlatformFrame window) throws Exception {
        var lastPage = appearance.restoreLastPage()
            ? new gold.debug.windowstolinux.app.service.setting.StartupPageService(runtime.persistence()).read()
            : java.util.Optional.<gold.debug.windowstolinux.app.service.setting.StartupPageLocation>empty();
        onEdt(() -> {
            window.resumePage(lastPage.orElse(gold.debug.windowstolinux.app.service.setting.StartupPageLocation.platform("shell.home")));
            return null;
        });
    }

    /** Assembles the original desktop services on the existing EDT before marking the host started. / 标记宿主已启动前，在既有EDT装配原桌面服务。
     * @param runtime original platform runtime / 原平台运行时
     * @param messages current host messages / 当前宿主消息
     * @param appearance stored display selection / 存储外观选择
     * @param theme resolved initial palette / 已解析初始配色
     * @param reports original bounded diagnostic writer / 原有界诊断写入器
     * @param models original already-resolved model settings / 原已解析模型设置
     * @return original window with its actual services / 带实际服务的原窗口
     * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if original host capability assembly is rejected / 原宿主能力装配被拒绝时 */
    static PlatformFrame createWindow(PlatformRuntime runtime, MessageCatalog messages,
                                      DesktopDisplayConfig appearance, ThemeMode theme, FailureReportStore reports,
                                      gold.debug.windowstolinux.app.service.ai.PlatformModelService models)
        throws gold.debug.windowstolinux.appapi.task.result.ApiException {
        DesktopThemeService.install(theme, appearance.scalePercent());
        var window = new PlatformFrame(runtime.security(), messages,
            theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light(),
            new UiEnvironment(Locale.forLanguageTag(appearance.localeTag()), theme.name(),
                runtime.security().securityState().configured()),
            reports, runtime.startup().config());
        window.installUiDebug(runtime.startup().layout().mode() == RunModeResolver.RunMode.RUN_CLASS);
        window.onExit(runtime);
        window.onRestart(new ApplicationRestart(runtime));
        window.rememberPage(new gold.debug.windowstolinux.app.service.setting.StartupPageService(runtime.persistence())::remember);
        var presenter = new gold.debug.windowstolinux.app.ui.task.TaskInteractionPresenter(window, messages);
        runtime.setInteractionPresenter(presenter);
        runtime.setSourcePicker(presenter);
        window.installServers(runtime.servers());
        var recovery = runtime.recovery();
        window.installRecovery(recovery);
        window.installModels(models);
        window.installTransfers(runtime.transfers());
        window.installFileSettings(runtime.fileSettings());
        window.installAppearance(appearance, requested -> {
            try (var authorized = runtime.security().beginTask()) {
                authorized.execute(() -> {
                    runtime.persistence().preferences().saveAll(java.util.Map.of("ui_locale", requested.localeTag(),
                        "ui_theme", requested.themeMode().name(), "ui_scale", Integer.toString(requested.scalePercent()),
                        "ui_restore_last_page", Boolean.toString(requested.restoreLastPage())));
                    return null;
                });
            }
        });
        window.installLogs(runtime.logs());
        window.installTasks(new gold.debug.windowstolinux.app.service.task.PlatformTaskManagement(runtime.tasks(),
            runtime.security(), id -> recovery.reconcile(id, true), runtime.modRecovery()::release));
        window.installNavigationOrder(new gold.debug.windowstolinux.app.service.setting.NavigationOrderService(
            runtime.persistence().navigation(), runtime.security()));
        return window;
    }

    /** Resolves anchors then validates YAML before touching application storage. / 先解析锚点，再在读取应用存储前校验 YAML。
     * @return configured startup / 已配置启动
     * @throws DesktopStartupException if layout or configuration is invalid / 布局或配置无效时 */
    static PlatformRuntime.Startup readStartup() {
        RunModeResolver.RuntimeLayout anchors;
        try {
            anchors = RunModeResolver.resolve();
        } catch (RuntimeException failure) {
            throw DesktopStartupException.create(DesktopSystemFailureType.STARTUP_LAYOUT_INVALID,
                "The application runtime layout could not be resolved safely", failure);
        }
        try {
            var config = gold.debug.windowstolinux.app.config.ConfigLoader.load(
                anchors.mode().name().substring(4).toLowerCase(java.util.Locale.ROOT));
            return new PlatformRuntime.Startup(config, RunModeResolver.configured(anchors, config));
        } catch (RuntimeException | java.io.IOException failure) {
            throw DesktopStartupException.create(DesktopSystemFailureType.CONFIGURATION_INVALID,
                gold.debug.windowstolinux.app.config.ConfigLoader.RESOURCE + ": " + failure.getMessage(), failure);
        }
    }

    /** Connects the default management page to original runtime operations outside the EDT. / 在 EDT 外将默认管理页面接入原运行时操作。
     * @param runtime original platform runtime / 原平台运行时
     * @param frame original desktop window / 原桌面窗口
     * @throws Exception if original service or view cannot be installed / 无法安装原服务或视图时 */
    static void installModManagement(PlatformRuntime runtime, PlatformFrame frame) throws Exception {
        var service = runtime.modIntents();
        var installation = runtime.startup().layout().mode() == RunModeResolver.RunMode.RUN_CLASS
            ? java.util.Optional.<gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation>empty()
            : java.util.Optional.of(runtime.modInstallation());
        var store = new gold.debug.windowstolinux.app.service.mod.store.ModStoreService(
            runtime.startup().config().storeUrl(),
            new gold.debug.windowstolinux.app.service.mod.store.HttpModStoreGateway(), installation,
            runtime.security(), runtime.persistence().database(), runtime.startup().layout().applicationHome(),
            PlatformBuildIdentity.readApiVersion());
        var actions = new gold.debug.windowstolinux.app.ui.mod.PlatformModsPane.SchemaActions() {
            /** Delegates exact source review to the original graph. / 将精确来源审阅交给原图。
             * @param owner selected owner / 已选所有者
             * @param source confirmed source / 已确认来源
             * @return original review / 原审阅
             * @throws Exception if unavailable / 不可用时 */
            @Override
            public gold.debug.windowstolinux.app.service.mod.PlatformModIntents.RetryReview review(String owner,
                                                                                                   java.util.Optional<gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity> source) throws Exception {
                return runtime.modRecovery().review(owner, source);
            }

            /** Delegates only the confirmed original retry. / 仅委派已确认原重试。
             * @param review original review / 原审阅
             * @return observed data result / 已观察数据结果
             * @throws Exception if unconfirmed / 未确认时 */
            @Override
            public gold.debug.windowstolinux.appapi.task.result.TaskResult retry(
                gold.debug.windowstolinux.app.service.mod.PlatformModIntents.RetryReview review) throws Exception {
                return runtime.modRecovery().retry(review);
            }

            /** Delegates original read-only reconciliation. / 委派原只读核对。
             * @param operation original preparation / 原准备
             * @return verified data result / 已核实数据结果
             * @throws Exception if unconfirmed / 未确认时 */
            @Override
            public gold.debug.windowstolinux.appapi.task.result.TaskResult reconcile(java.util.UUID operation) throws Exception {
                return runtime.modRecovery().reconcile(operation);
            }
        };
        onEdt(() -> {
            if (frame.isDisplayable()) {
                frame.installMods(service, actions);
                frame.installOriginalPackages(installation);
                frame.installStore(store);
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
    static gold.debug.windowstolinux.app.main.mod.PlatformModFlow.Report startMods(PlatformRuntime runtime,
                                                                                   RunModeResolver.RuntimeLayout layout, String declared,
                                                                                   gold.debug.windowstolinux.app.ui.mod.ModPageRegistry pages) {
        if (!runtime.startup().config().modLoad())
            return new gold.debug.windowstolinux.app.main.mod.PlatformModFlow.Report(java.util.Set.of(), java.util.List.of());
        try {
            if (!runtime.persistence().database().root().equals(layout.dataDirectory().toRealPath()))
                throw new java.io.IOException("Layout does not match the running platform database");
            if (layout.mode() != RunModeResolver.RunMode.RUN_CLASS)
                return runtime.startInstalledMods(layout.applicationHome(), pages);
            if (declared == null || declared.isBlank())
                throw new java.io.IOException("Explicit CLASS workspace is missing");
            Path root = gold.debug.windowstolinux.app.mod.dev.DevelopmentInfo
                .workspace(Path.of(declared));
            if (!root.equals(layout.applicationHome().toRealPath())
                || !runtime.persistence().database().root().equals(layout.dataDirectory().toRealPath()))
                throw new java.io.IOException("Explicit workspace does not match the running platform");
            return runtime.startDevelopmentMods(root, pages);
        } catch (Exception | LinkageError | AssertionError failure) {
            var problem = failure instanceof gold.debug.windowstolinux.appapi.task.result.ApiException api
                ? api.problem()
                : new gold.debug.windowstolinux.appapi.task.result.Problem(
                gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE, "MOD_WORKSPACE_UNAVAILABLE",
                "Mod startup is unavailable; verify the program layout and original lifecycle state",
                java.util.Map.of(), java.util.Optional.of(java.util.UUID.randomUUID()));
            return new gold.debug.windowstolinux.app.main.mod.PlatformModFlow.Report(java.util.Set.of(),
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
        String text = new DesktopFailurePresenter(messages::text, reports).present(failure, failure.problem());
        if (GraphicsEnvironment.isHeadless())
            System.err.println(text);
        else
            SwingUtilities.invokeLater(
                () -> JOptionPane.showMessageDialog(null, text, "WindowsToLinux", JOptionPane.ERROR_MESSAGE));
    }
}
