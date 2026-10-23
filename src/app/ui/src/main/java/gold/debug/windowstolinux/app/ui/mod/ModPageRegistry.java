package gold.debug.windowstolinux.app.ui.mod;

import java.util.*;
import java.util.concurrent.*;

import javax.swing.*;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.PageFactory;
import gold.debug.windowstolinux.appapi.ui.page.PageRegistration;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.appapi.ui.page.UiNavigationRequest;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionService;

/** Owns staged page contributions and EDT-confined author view lifetimes. / 持有暂存页面扩展及限定在 EDT 的作者视图生命周期。 */
public final class ModPageRegistry {
    /** Host mounting surface, called exclusively on the EDT. / 仅在 EDT 调用的宿主挂载界面。 */
    private final PageHost host;

    /** Current environment copied to new and already open pages. / 传递到新页面及已打开页面的当前环境。 */
    private UiEnvironment environment;

    /** Shared extension ownership, following the same activation lifetime. / 遵循同一启用寿命的共享扩展归属。 */
    private final UiExtensionRegistry extensions;

    /** Current startup scopes, including unpublished and failed-cleanup scopes. / 当前启动作用域，包含未发布及清理失败的作用域。 */
    private final Map<String, PageScope> scopes = new LinkedHashMap<>();

    /** Host-only publication observers, confined to the event thread. / 仅宿主使用且限定在事件线程的发布观察器。 */
    private final Set<Runnable> listeners = new LinkedHashSet<>();

    /** Coalesces publication notifications without running host callbacks inside author registration. / 合并发布通知，不在作者登记中执行宿主回调。 */
    private boolean notificationQueued;

    /** CLASS-only isolated preview surface, absent in packaged modes. / 仅CLASS的隔离预览界面；打包模式不存在。 */
    private PageHost previewHost;

    /** Installs the host-selected CLASS preview surface exactly once. / 精确安装一次宿主选择的CLASS预览界面。
     * @param surface owned preview dialogs / 所属预览对话框
     * @throws ApiException if installation is duplicated / 重复安装时 */
    public void installPreviews(PageHost surface) throws ApiException {
        onEdt(() -> {
            if (previewHost != null)
                throw failure(ProblemCode.BUSY, "PREVIEW_HOST_EXISTS", "Preview host is already installed");
            previewHost = Objects.requireNonNull(surface);
            return null;
        });
    }

    /** Lists committed preview metadata without creating author views. / 不创建作者视图地列出已提交预览元数据。
     * @return CLASS-only visible previews / 仅CLASS可见预览
     * @throws ApiException if EDT dispatch fails / EDT调度失败时 */
    public List<PageContribution> previews() throws ApiException {
        return onEdt(() -> previewHost == null
            ? List.of()
            : scopes.values().stream().filter(PageScope::visible).flatMap(scope -> scope.entries.values().stream()
                .filter(entry -> entry.preview).map(entry -> new PageContribution(entry.key, entry.spec)))
            .toList());
    }

    /** Opens only a committed preview through the CLASS host. / 仅经CLASS宿主打开已提交预览。
     * @param key exact author identity / 精确作者身份
     * @throws ApiException if CLASS, owner or unlock is unavailable / CLASS、所有者或解锁不可用时 */
    public void openPreview(PageKey key) throws ApiException {
        onEdt(() -> {
            if (previewHost == null)
                throw failure(ProblemCode.ACCESS_DENIED, "PREVIEW_CLASS_REQUIRED", "Previews require CLASS mode");
            var scope = scopes.get(key.ownerId());
            if (scope == null)
                throw failure(ProblemCode.NOT_FOUND, "PAGE_NOT_FOUND", "Preview owner is unavailable");
            scope.open(key.pageId(), true);
            return null;
        });
    }

    /** Releases every opened preview while retaining the original registrations. / 释放全部已打开预览并保留原登记。
     * @throws ApiException if author cleanup fails / 作者清理失败时 */
    public void closePreviews() throws ApiException {
        onEdt(() -> {
            ApiException first = null;
            for (var scope : List.copyOf(scopes.values()))
                for (var entry : List.copyOf(scope.entries.values()))
                    if (entry.preview)
                        try {
                            entry.closeView();
                        } catch (ApiException problem) {
                            if (first == null)
                                first = problem;
                            else
                                first.addSuppressed(problem);
                        }
            if (first != null)
                throw first;
            return null;
        });
    }

    /**
     * Binds the host surface and initial environment. / 绑定宿主界面及初始环境。
     * @param host actual mounting surface / 实际挂载界面
     * @param environment initial language, theme and lock state / 初始语言、主题及锁定状态
     */
    public ModPageRegistry(PageHost host, UiEnvironment environment) {
        this.host = Objects.requireNonNull(host);
        this.environment = Objects.requireNonNull(environment);
        extensions = new UiExtensionRegistry(environment);
    }

    /** Returns the host-only extension composition. / 返回仅宿主使用的扩展装配。
     * @return central extension registry / 集中扩展登记器 */
    public UiExtensionRegistry extensions() {
        return extensions;
    }

    /**
     * Creates a private registration scope for one normal or recovery startup. / 为一次普通或恢复启动创建私有登记作用域。
     * @param ownerId exact stable Mod identity / 精确稳定的 Mod 身份
     * @param recoveryOnly whether contributions must stay hidden from business UI / 扩展是否必须对业务界面保持隐藏
     * @return caller-bound scope staged until commit / 提交前保持暂存的调用方作用域
     * @throws ApiException if that owner still holds another scope / 该所有者仍持有其他作用域时
     */
    public PageScope begin(String ownerId, boolean recoveryOnly) throws ApiException {
        return begin(ownerId, recoveryOnly, UiExtensionValues.PLATFORM);
    }

    /** Binds page and extension registration to an actual injected owner policy. / 将页面及扩展登记绑定到实际注入的所属者策略。
     * @param ownerId actual owner / 实际所属者
     * @param recoveryOnly restricted recovery lifetime / 受限恢复寿命
     * @param access actual type and dependency policy / 实际类型及依赖策略
     * @return original scope / 原作用域
     * @throws ApiException if the owner remains retained / 所属者仍被保留时 */
    public PageScope begin(String ownerId, boolean recoveryOnly, UiExtensionAccess access) throws ApiException {
        Objects.requireNonNull(ownerId);
        return onEdt(() -> {
            if (ownerId.isBlank() || scopes.containsKey(ownerId))
                throw failure(ProblemCode.BUSY, "PAGE_SCOPE_EXISTS", "The Mod still owns a page scope");
            var scope = new PageScope(ownerId, recoveryOnly, extensions.begin(ownerId, recoveryOnly, access));
            scopes.put(ownerId, scope);
            return scope;
        });
    }

    /**
     * Lists published contributions without creating views or pinning navigation. / 列出已发布扩展，不创建视图或固定导航。
     * @return immutable visible contribution metadata / 不可变可见扩展元数据
     * @throws ApiException if EDT dispatch fails / EDT 调度失败时
     */
    public List<PageContribution> contributions() throws ApiException {
        return onEdt(() -> scopes
            .values().stream().filter(PageScope::visible).flatMap(scope -> scope.entries.values().stream()
                .filter(entry -> !entry.preview).map(entry -> new PageContribution(entry.key, entry.spec)))
            .toList());
    }

    /** Opens an exact published page through its original owner scope. / 经原所有者作用域打开精确已发布页面。
     * @param key exact host-selected identity / 宿主选择的精确身份
     * @throws ApiException if the page is unavailable or locked / 页面不可用或锁定时 */
    public void openView(PageKey key) throws ApiException {
        onEdt(() -> {
            var scope = scopes.get(key.ownerId());
            if (scope == null)
                throw failure(ProblemCode.NOT_FOUND, "PAGE_NOT_FOUND", "The page owner is unavailable");
            scope.open(key.pageId());
            return null;
        });
    }

    /** Opens a host navigation target in its current locked or unlocked environment. / 在当前锁定或解锁环境中打开宿主导航目标。
     * @param key exact host-selected identity / 宿主选择的精确身份
     * @throws ApiException if unavailable or creation fails / 不可用或创建失败时 */
    public void navigateView(PageKey key) throws ApiException {
        navigateView(key, null);
    }

    /** Opens a host startup target with optional stable internal navigation. / 打开宿主启动目标及可选稳定内部导航。
     * @param key exact published identity / 精确已发布身份
     * @param navigationId internal identity or null for normal navigation / 内部身份，普通导航时为 null
     * @throws ApiException if unavailable or creation fails / 不可用或创建失败时 */
    public void navigateView(PageKey key, String navigationId) throws ApiException {
        onEdt(() -> {
            var scope = scopes.get(key.ownerId());
            if (scope == null || !scope.visible())
                throw failure(ProblemCode.UNAVAILABLE, "PAGE_NOT_PUBLISHED", "The page owner is unavailable");
            var entry = scope.entries.get(key.pageId());
            if (entry == null || entry.preview)
                throw failure(ProblemCode.NOT_FOUND, "PAGE_NOT_FOUND", "The page is not registered");
            entry.open(navigationId);
            return null;
        });
    }

    /** Captures a bounded internal identity without creating a view or reading business data. / 捕获有界内部身份，不创建视图或读取业务数据。
     * @param key exact mounted identity / 精确挂载身份
     * @return internal navigation identity or empty / 内部导航身份或空
     * @throws ApiException if author metadata cannot be captured / 作者元数据无法捕获时 */
    public String navigationId(PageKey key) throws ApiException {
        return onEdt(() -> {
            var scope = scopes.get(key.ownerId());
            var entry = scope == null ? null : scope.entries.get(key.pageId());
            if (entry == null || entry.page == null || !entry.mounted) return "";
            String id = entry.page.navigationId();
            if (id == null || !id.isEmpty() && !id.matches("[a-z][a-z0-9_.-]{0,127}"))
                throw failure(ProblemCode.CONTRACT_VIOLATION, "NAVIGATION_ID_INVALID", "Invalid internal navigation identity");
            return id;
        });
    }

    /** Observes published metadata without creating pages or retaining removed listeners. / 观察发布元数据，不创建页面或保留已移除监听器。
     * @param listener host refresh callback on the EDT / EDT 上的宿主刷新回调
     * @return removable host subscription / 可移除宿主订阅
     * @throws ApiException if registration cannot reach the EDT / 登记无法到达 EDT 时 */
    public AutoCloseable onChange(Runnable listener) throws ApiException {
        Objects.requireNonNull(listener);
        onEdt(() -> {
            listeners.add(listener);
            return null;
        });
        return () -> onEdt(() -> {
            listeners.remove(listener);
            return null;
        });
    }

    /** Delivers the latest publication state after the current mutation returns. / 当前修改返回后交付最新发布状态。 */
    private void changed() {
        if (notificationQueued)
            return;
        notificationQueued = true;
        SwingUtilities.invokeLater(() -> {
            notificationQueued = false;
            for (var listener : List.copyOf(listeners))
                listener.run();
        });
    }

    /**
     * Closes an open view while retaining its registration for later reopening. / 关闭打开的视图，同时保留登记供以后重新打开。
     * @param key exact owner and local page identity / 精确所有者及本地页面身份
     * @throws ApiException if the page is absent or cleanup remains incomplete / 页面不存在或清理尚未完成时
     */
    public void closeView(PageKey key) throws ApiException {
        onEdt(() -> {
            var scope = scopes.get(key.ownerId());
            if (scope == null || !scope.entries.containsKey(key.pageId()))
                throw failure(ProblemCode.NOT_FOUND, "PAGE_NOT_FOUND", "The page is not registered");
            scope.entries.get(key.pageId()).closeView();
            return null;
        });
    }

    /**
     * Applies the host lock surface before notifying all author pages. / 先应用宿主锁定界面，再通知所有作者页面。
     * @param next new immutable UI environment / 新的不可变界面环境
     * @throws ApiException if any notification fails after other pages were attempted / 尝试通知其他页面后仍有通知失败时
     */
    public void environment(UiEnvironment next) throws ApiException {
        Objects.requireNonNull(next);
        onEdt(() -> {
            environment = next;
            extensions.environment(next);
            host.environment(next);
            if (previewHost != null)
                previewHost.environment(next);
            ApiException failure = null;
            if (next.locked())
                try {
                    closePreviews();
                } catch (ApiException problem) {
                    failure = problem;
                }
            for (var scope : List.copyOf(scopes.values()))
                for (var entry : List.copyOf(scope.entries.values()))
                    if (entry.page != null && !entry.pageClosed)
                        try {
                            if (entry.preview) extensions.isolated(() -> {
                                entry.page.environmentChanged(next);
                                return null;
                            });
                            else entry.page.environmentChanged(next);
                        } catch (Exception | Error problem) {
                            rethrowFatal(problem);
                            if (failure == null)
                                failure = failure(ProblemCode.CONTRACT_VIOLATION, "PAGE_ENVIRONMENT_FAILED",
                                    "A page environment notification failed");
                        }
            changed();
            if (failure != null)
                throw failure;
            return null;
        });
    }

    /**
     * Dispatches without abandoning a queued mutation when its waiting thread is interrupted. / 等待线程被中断时仍不丢弃已排队的修改调度。
     * @param <T> result type / 结果类型
     * @param action EDT-confined operation / 限定在 EDT 的操作
     * @return completed operation result / 已完成操作结果
     * @throws ApiException if the action fails / 操作失败时
     */
    private static <T> T onEdt(Callable<T> action) throws ApiException {
        var future = new FutureTask<>(action);
        if (SwingUtilities.isEventDispatchThread())
            future.run();
        else
            SwingUtilities.invokeLater(future);
        boolean interrupted = false;
        try {
            while (true)
                try {
                    return future.get();
                } catch (InterruptedException waiting) {
                    interrupted = true;
                } catch (ExecutionException problem) {
                    rethrowFatal(problem.getCause());
                    if (problem.getCause() instanceof ApiException publicFailure)
                        throw publicFailure;
                    throw failure(ProblemCode.CONTRACT_VIOLATION, "PAGE_HOST_FAILED",
                        "The page operation did not complete");
                }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    /** Propagates fatal JVM errors, including reflective author construction. / 传播致命 JVM 错误，包括反射作者构造失败。
     * @param failure original callback failure / 原回调失败 */
    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof java.lang.reflect.InvocationTargetException invocation)
            failure = invocation.getCause();
        if (failure instanceof VirtualMachineError fatal)
            throw fatal;
        if (failure instanceof ThreadDeath fatal)
            throw fatal;
    }

    /**
     * Builds a non-secret public lifecycle diagnostic. / 构造不含秘密的公开生命周期诊断。
     * @param code public category / 公开类别
     * @param event stable diagnostic event / 稳定诊断事件
     * @param message non-secret message / 不含秘密的消息
     * @return structured public failure / 结构化公开失败
     */
    private static ApiException failure(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /**
     * Exact global page identity without concatenation ambiguity. / 没有拼接歧义的精确全局页面身份。
     * @param ownerId exact owning Mod identity / 精确所属 Mod 身份
     * @param pageId exact owner-local identity / 精确所有者内部身份
     */
    public record PageKey(String ownerId, String pageId) {
        /**
         * Requires both immutable identity components. / 要求两个不可变身份部分均存在。
         * @param ownerId exact owning Mod identity / 精确所属 Mod 身份
         * @param pageId exact owner-local identity / 精确所有者内部身份
         */
        public PageKey {
            Objects.requireNonNull(ownerId);
            Objects.requireNonNull(pageId);
        }
    }

    /**
     * Visible contribution metadata, independent of an instantiated page. / 独立于已实例化页面的可见扩展元数据。
     * @param key exact global identity / 精确全局身份
     * @param spec immutable author presentation metadata / 不可变作者展示元数据
     */
    public record PageContribution(PageKey key, PageSpec spec) {
    }

    /** Host surface operations that cannot instantiate or pin author pages implicitly. / 不能隐式实例化或固定作者页面的宿主界面操作。 */
    public interface PageHost {
        /**
         * Mounts one freshly created component in its appropriate host section. / 将一个新建组件挂载到适当的宿主分区。
         * @param key exact global page identity / 精确全局页面身份
         * @param spec presentation and settings classification / 展示信息及设置分类
         * @param component fresh author view / 新的作者视图
         */
        void show(PageKey key, PageSpec spec, JComponent component);

        /**
         * Focuses the already mounted view. / 聚焦已挂载视图。
         * @param key exact global page identity / 精确全局页面身份
         */
        void focus(PageKey key);

        /** Selects an already composed platform surface; mount-only hosts report unavailable. / 选择已装配平台界面；仅提供挂载的宿主报告不可用。
         * @param target fixed platform destination / 固定平台目的地
         * @throws ApiException if the actual platform destination is unavailable / 实际平台目的地不可用时 */
        default void openPlatform(PlatformPageKind target) throws ApiException {
            throw failure(ProblemCode.UNAVAILABLE, "PLATFORM_PAGE_UNAVAILABLE", "The platform destination is not composed");
        }

        /**
         * Removes an already closed author view; repeated removal must be safe. / 移除作者已关闭的视图；重复移除必须安全。
         * @param key exact global page identity / 精确全局页面身份
         */
        void remove(PageKey key);

        /**
         * Applies host language, theme and lock surface before author notifications. / 作者通知前应用宿主语言、主题及锁定界面。
         * @param environment new host environment / 新宿主环境
         */
        void environment(UiEnvironment environment);
    }

    /** Caller-bound staged page registration and cleanup scope. / 绑定调用方的暂存页面登记及清理作用域。 */
    public final class PageScope implements UiService, AutoCloseable {
        /** Exact owning Mod identity. / 精确所属 Mod 身份。 */
        private final String owner;

        /** Whether normal UI publication is forbidden. / 是否禁止普通界面发布。 */
        private final boolean recoveryOnly;

        /** Original owner-bound extensions. / 原所属者绑定扩展。 */
        private final UiExtensionRegistry.Binding extensionBinding;

        /** Author factories and any retained open view state. / 作者工厂及保留的打开视图状态。 */
        private final Map<String, PageEntry> entries = new LinkedHashMap<>();

        /** Whether this startup committed its contributions. / 此次启动是否已提交扩展。 */
        private boolean committed;

        /** Whether cleanup has sealed further registration and opening. / 清理是否已封闭后续登记及打开。 */
        private boolean stopping;

        /** Whether every registration and instance was actually released. / 全部登记及实例是否已实际释放。 */
        private boolean closed;

        /**
         * Creates an unpublished caller scope. / 创建未发布的调用方作用域。
         * @param owner exact owner identity / 精确所有者身份
         * @param recoveryOnly whether publication is forbidden / 是否禁止发布
         * @param extensionBinding original owned extension registry / 原所属扩展登记
         */
        private PageScope(String owner, boolean recoveryOnly, UiExtensionRegistry.Binding extensionBinding) {
            this.owner = owner;
            this.recoveryOnly = recoveryOnly;
            this.extensionBinding = extensionBinding;
        }

        /**
         * Publishes all staged metadata together after successful Mod startup. / Mod 成功启动后整体发布暂存元数据。
         * @throws ApiException if the scope is already stopping / 作用域已在停止时
         */
        public void commit() throws ApiException {
            onEdt(() -> {
                if (stopping)
                    throw failure(ProblemCode.UNAVAILABLE, "PAGE_SCOPE_STOPPING", "Page scope is stopping");
                committed = true;
                if (!recoveryOnly) extensionBinding.publish();
                changed();
                return null;
            });
        }

        /** Reports normal visible contribution state on the EDT. / 在 EDT 报告普通可见扩展状态。
         * @return whether business contributions are visible / 业务扩展是否可见 */
        private boolean visible() {
            return committed && !recoveryOnly && !stopping;
        }

        /**
         * Stages a unique exact local identity without creating its view. / 暂存唯一的精确本地身份，不创建其视图。
         * @param spec immutable page metadata / 不可变页面元数据
         * @param factory lazy page factory / 惰性页面工厂
         * @return owned registration / 所属登记
         * @throws ApiException if identity is invalid, duplicated or scope is stopping / 身份无效、重复或作用域停止时
         */
        @Override
        public PageRegistration register(PageSpec spec, PageFactory factory) throws ApiException {
            return register(spec, factory, false);
        }

        /** Stages a pure preview without publishing it to normal navigation. / 暂存纯预览，不发布到普通导航。
         * @param spec exact author metadata / 精确作者元数据
         * @param factory isolated pure view / 隔离纯视图
         * @return original registration / 原登记
         * @throws ApiException if unavailable / 不可用时 */
        @Override
        public PageRegistration registerPreview(PageSpec spec, PageFactory factory) throws ApiException {
            return register(spec, factory, true);
        }

        /** Stages one distinct local identity in its original category. / 在原分类暂存一个独立本地身份。
         * @param spec metadata / 元数据
         * @param factory fresh factory / 新工厂
         * @param preview whether the contribution is isolated / 扩展是否隔离
         * @return owned registration / 所属登记
         * @throws ApiException if unavailable / 不可用时 */
        private PageRegistration register(PageSpec spec, PageFactory factory, boolean preview) throws ApiException {
            Objects.requireNonNull(spec);
            Objects.requireNonNull(factory);
            return onEdt(() -> {
                if (stopping)
                    throw failure(ProblemCode.UNAVAILABLE, "PAGE_SCOPE_STOPPING", "Page scope is stopping");
                if (spec.id().isBlank() || spec.id().length() > 1024 || spec.id().indexOf('\0') >= 0 || entries.containsKey(spec.id()))
                    throw failure(ProblemCode.INVALID_INPUT, "PAGE_ID_INVALID",
                        "Page identity is empty, invalid or already registered");
                if (!preview && !spec.settingsPage() && entries.values().stream()
                    .anyMatch(entry -> !entry.preview && !entry.spec.settingsPage()))
                    throw failure(ProblemCode.CONTRACT_VIOLATION, "WORKSPACE_ALREADY_REGISTERED",
                        "A Mod can register only one root workspace");
                var entry = new PageEntry(this, spec, factory, preview);
                entries.put(spec.id(), entry);
                if (visible())
                    changed();
                return entry;
            });
        }

        /**
         * Opens only this owner's published page, or focuses its cached instance. / 仅打开此所有者的已发布页面，或聚焦其缓存实例。
         * @param localPageId exact local identity / 精确本地身份
         * @throws ApiException if unavailable, locked or page creation fails / 不可用、已锁定或创建页面失败时
         */
        @Override
        public void open(String localPageId) throws ApiException {
            open(localPageId, false);
        }

        /** Returns actual owner-bound extensions. / 返回实际所属者绑定扩展。
         * @return original extensions / 原扩展 */
        @Override
        public UiExtensionService extensions() {
            return extensionBinding;
        }

        /** Delivers one detached request after creating or focusing the original view. / 创建或聚焦原视图后交付一次分离请求。
         * @param localPageId original owned page / 原所属页面
         * @param request explicit transient navigation / 明确临时导航
         * @throws ApiException if owner, payload or navigation is unavailable / 所属者、载荷或导航不可用时 */
        @Override
        public void open(String localPageId, UiNavigationRequest<?> request) throws ApiException {
            onEdt(() -> {
                var detached = UiExtensionValues.navigation(Objects.requireNonNull(request), extensionBinding.access);
                open(localPageId);
                try {
                    entries.get(localPageId).page.navigate(detached);
                } catch (ApiException rejected) {
                    throw rejected;
                } catch (Exception | Error failure) {
                    rethrowFatal(failure);
                    throw failure(ProblemCode.CONTRACT_VIOLATION, "PAGE_NAVIGATION_FAILED", "Contextual navigation failed");
                }
                return null;
            });
        }

        /** Seals callbacks before the original owner stop callback. / 原所属者停止回调前封闭界面回调。
         * @throws ApiException if dispatch fails / 调度失败时 */
        public void seal() throws ApiException {
            onEdt(() -> {
                stopping = true;
                extensionBinding.seal();
                changed();
                return null;
            });
        }

        /** Navigates only after normal publication and original scope/lock checks. / 仅在普通发布及原作用域、锁定核验后导航。
         * @param target fixed host destination / 固定宿主目的地
         * @throws ApiException if original scope or host destination is unavailable / 原作用域或宿主目的地不可用时 */
        @Override
        public void openPlatform(PlatformPageKind target) throws ApiException {
            Objects.requireNonNull(target);
            onEdt(() -> {
                if (!visible())
                    throw failure(ProblemCode.UNAVAILABLE, "PAGE_NOT_PUBLISHED", "Page scope is not available to business UI");
                if (environment.locked())
                    throw failure(ProblemCode.ACCESS_DENIED, "PAGE_LOCKED", "Unlock the application before opening this page");
                host.openPlatform(target);
                return null;
            });
        }

        /** Checks the exact category before creating an owned view. / 创建所属视图前核对精确分类。
         * @param localPageId original local identity / 原本地身份
         * @param preview requested category / 请求分类
         * @throws ApiException if admission fails / 准入失败时 */
        private void open(String localPageId, boolean preview) throws ApiException {
            onEdt(() -> {
                if (!visible())
                    throw failure(ProblemCode.UNAVAILABLE, "PAGE_NOT_PUBLISHED",
                        "Page scope is not available to business UI");
                if (environment.locked())
                    throw failure(ProblemCode.ACCESS_DENIED, "PAGE_LOCKED",
                        "Unlock the application before opening this page");
                var entry = entries.get(localPageId);
                if (entry == null || entry.preview != preview)
                    throw failure(ProblemCode.NOT_FOUND, "PAGE_NOT_FOUND", "The owner has no such page");
                entry.open();
                return null;
            });
        }

        /** Returns the latest immutable environment without touching author views. / 返回最新不可变环境，不访问作者视图。
         * @return current environment / 当前环境 */
        @Override
        public UiEnvironment environment() {
            try {
                return onEdt(() -> environment);
            } catch (ApiException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        /**
         * Revokes contributions and retries only resources that were not released. / 撤销扩展，仅重试尚未释放的资源。
         * @throws ApiException if any view or host mount remains / 仍有视图或宿主挂载残留时
         */
        @Override
        public void close() throws ApiException {
            onEdt(() -> {
                if (closed)
                    return null;
                stopping = true;
                extensionBinding.seal();
                changed();
                ApiException first = null;
                var pending = new ArrayList<>(entries.values());
                Collections.reverse(pending);
                for (var entry : pending)
                    try {
                        entry.close();
                    } catch (ApiException failure) {
                        if (first == null)
                            first = failure;
                        else
                            first.addSuppressed(failure);
                    }
                if (first != null)
                    throw first;
                extensionBinding.close();
                closed = true;
                scopes.remove(owner, this);
                return null;
            });
        }
    }

    /** One exact registration and its retryable EDT-confined view state. / 单个精确登记及其可重试的 EDT 视图状态。 */
    private final class PageEntry implements PageRegistration {
        /** Owning startup scope. / 所属启动作用域。 */
        private final PageScope scope;

        /** Exact global identity. / 精确全局身份。 */
        private final PageKey key;

        /** Immutable author metadata. / 不可变作者元数据。 */
        private final PageSpec spec;

        /** Lazy fresh-instance factory. / 惰性的新实例工厂。 */
        private final PageFactory factory;

        /** Distinct isolated preview category. / 独立隔离预览分类。 */
        private final boolean preview;

        /** Retained author instance, including failed creation cleanup. / 保留的作者实例，包含创建失败后的清理状态。 */
        private ModPage page;

        /** Original mounted author root for extension cleanup. / 用于扩展清理的原挂载作者根。 */
        private JComponent component;

        /** Whether a host mount may need removal. / 是否可能需要移除宿主挂载。 */
        private boolean mounted;

        /** Whether author close succeeded before any host removal failure. / 作者关闭是否先于宿主移除失败而成功。 */
        private boolean pageClosed;

        /** Whether failed creation or close must finish before the view can reopen. / 视图重开前是否必须完成失败的创建或关闭清理。 */
        private boolean cleanupPending;

        /** Whether an author close callback is already executing for this view. / 此视图是否已有作者关闭回调正在执行。 */
        private boolean closingView;

        /**
         * Stores an uninstantiated contribution. / 保存尚未实例化的扩展。
         * @param scope owning startup scope / 所属启动作用域
         * @param spec page metadata / 页面元数据
         * @param factory fresh view factory / 新视图工厂
         * @param preview isolated preview category / 隔离预览分类
         */
        private PageEntry(PageScope scope, PageSpec spec, PageFactory factory, boolean preview) {
            this.scope = scope;
            this.spec = spec;
            this.factory = factory;
            this.preview = preview;
            key = new PageKey(scope.owner, spec.id());
        }

        /** Returns the exact owner-local identity. / 返回精确所有者内部身份。
         * @return local identity / 本地身份 */
        @Override
        public String id() {
            return spec.id();
        }

        /**
         * Creates and mounts once, preserving failed cleanup instead of losing the instance. / 创建并挂载一次，保留清理失败状态而不丢失实例。
         * @throws ApiException if creation or pending cleanup prevents opening / 创建失败或待清理状态阻止打开时
         */
        private void open() throws ApiException {
            open(null);
        }

        /** Applies only an explicit restoration while preserving normal cached navigation. / 仅应用明确恢复，普通缓存导航保持原选择。
         * @param navigationId internal identity or null / 内部身份或 null
         * @throws ApiException if author creation or restoration fails / 作者创建或恢复失败时 */
        private void open(String navigationId) throws ApiException {
            if (page != null) {
                if (cleanupPending || pageClosed || !mounted)
                    throw failure(ProblemCode.BUSY, "PAGE_CLEANUP_PENDING",
                        "A previous page instance still requires cleanup");
                if (navigationId != null) page.restoreNavigation(navigationId);
                (preview ? previewHost : host).focus(key);
                return;
            }
            try {
                page = Objects.requireNonNull(preview ? extensions.isolated(factory::create) : factory.create());
                if (navigationId != null) page.restoreNavigation(navigationId);
                component = Objects.requireNonNull(preview ? extensions.isolated(() -> page.create(environment)) : page.create(environment));
                if (page == null || scope.entries.get(spec.id()) != this || scope.stopping)
                    throw new IllegalStateException("Page registration was revoked during creation");
                if (component.getParent() != null)
                    throw new IllegalStateException("Page component is already attached");
                mounted = true;
                (preview ? previewHost : host).show(key, spec, component);
            } catch (Exception | Error creation) {
                rethrowFatal(creation);
                var problem = failure(ProblemCode.CONTRACT_VIOLATION, "PAGE_CREATION_FAILED",
                    "Page creation or mounting failed");
                try {
                    closeView();
                } catch (ApiException closing) {
                    problem.addSuppressed(closing);
                }
                throw problem;
            }
        }

        /**
         * Closes the author before removing the host mount, preserving each completed step. / 先关闭作者对象再移除宿主挂载，并保留每个已完成步骤。
         * @throws ApiException if actual cleanup is incomplete / 实际清理尚未完成时
         */
        private void closeView() throws ApiException {
            if (closingView)
                throw failure(ProblemCode.BUSY, "PAGE_CLOSE_REENTRANT", "The page close callback is already running");
            closingView = true;
            cleanupPending = true;
            try {
                if (page != null && !pageClosed) {
                    if (component != null) extensions.closeWithin(component);
                    if (preview) extensions.isolated(() -> {
                        page.close();
                        return null;
                    });
                    else page.close();
                    pageClosed = true;
                }
                if (mounted) {
                    (preview ? previewHost : host).remove(key);
                    mounted = false;
                }
                page = null;
                component = null;
                pageClosed = false;
                cleanupPending = false;
            } catch (Exception | Error closing) {
                rethrowFatal(closing);
                throw failure(ProblemCode.BUSY, "PAGE_CLEANUP_PENDING",
                    "Page resources or host mounting still require cleanup");
            } finally {
                closingView = false;
            }
        }

        /**
         * Removes this registration only after its instance is actually released. / 仅在实例实际释放后移除此登记。
         * @throws ApiException if view cleanup fails / 视图清理失败时
         */
        @Override
        public void close() throws ApiException {
            onEdt(() -> {
                closeView();
                scope.entries.remove(spec.id(), this);
                changed();
                return null;
            });
        }
    }
}
