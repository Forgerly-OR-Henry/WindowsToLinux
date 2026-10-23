package gold.debug.windowstolinux.app.ui.mod;

import java.awt.AWTEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.SwingUtilities;
import javax.swing.plaf.LayerUI;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.ui.extension.UiAction;
import gold.debug.windowstolinux.appapi.ui.extension.UiActionPresentation;
import gold.debug.windowstolinux.appapi.ui.extension.UiComponent;
import gold.debug.windowstolinux.appapi.ui.extension.UiComponentFactory;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionHost;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionKind;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionMount;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionPoint;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionPointRegistration;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionRegistration;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionService;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionSpec;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionState;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;

/** Central EDT-confined extension publication and physical ownership. / 集中且限定 EDT 的界面扩展发布及物理归属。 */
public final class UiExtensionRegistry {
    /** Exact activation bindings. / 精确启用绑定。 */
    private final Map<String, Binding> bindings = new LinkedHashMap<>();

    /** Current physical child identities. / 当前物理子项身份。 */
    private final Map<JComponent, Item<?>> components = new IdentityHashMap<>();

    /** Actual component lifecycle owners, unique per physical mount. / 每次物理挂载唯一的实际组件生命周期所有者。 */
    private final Map<UiComponent<?>, Item<?>> authors = new IdentityHashMap<>();

    /** Current host environment. / 当前宿主环境。 */
    private UiEnvironment environment;

    /** Nested isolated preview callbacks cannot publish ordinary extensions. / 嵌套隔离预览回调不能发布普通扩展。 */
    private int isolatedDepth;

    /** Safe host diagnostic sink. / 安全宿主诊断出口。 */
    private Consumer<Problem> diagnostics = problem -> {
    };

    /** Creates the APP binding and initial environment. / 创建 APP 绑定及初始环境。
     * @param environment actual appearance and lock / 实际外观及锁定 */
    public UiExtensionRegistry(UiEnvironment environment) {
        this.environment = Objects.requireNonNull(environment);
        var platform = new Binding("platform", false, UiExtensionValues.PLATFORM);
        platform.published = true;
        bindings.put(platform.owner, platform);
    }

    /** Installs actual host feedback. / 安装实际宿主反馈。
     * @param sink safe diagnostic consumer / 安全诊断消费者
     * @throws ApiException if dispatch fails / 调度失败时 */
    public void diagnostics(Consumer<Problem> sink) throws ApiException {
        UiExtensionDispatch.call(() -> {
            diagnostics = Objects.requireNonNull(sink);
            return null;
        });
    }

    /** Returns the original APP provider capability. / 返回原 APP 提供方能力。
     * @return bound APP extensions / 绑定的 APP 扩展 */
    public UiExtensionService platform() {
        return bindings.get("platform");
    }

    /** Runs an isolated author preview callback on its existing EDT. / 在现有 EDT 上运行隔离作者预览回调。
     * @param <T> callback result / 回调结果
     * @param action original callback / 原回调
     * @return callback result / 回调结果
     * @throws Exception if the author callback fails / 作者回调失败时 */
    <T> T isolated(Callable<T> action) throws Exception {
        isolatedDepth++;
        try {
            return action.call();
        } finally {
            isolatedDepth--;
        }
    }

    /** Creates an unpublished exact owner. / 创建尚未发布的精确所属者。
     * @param owner original activation / 原启用
     * @param recovery whether ordinary extensions are forbidden / 是否禁止普通扩展
     * @param access injected shared-type policy / 注入的共享类型策略
     * @return original activation binding / 原启用绑定
     * @throws ApiException if an owner is retained / 所属者仍被保留时 */
    public Binding begin(String owner, boolean recovery, UiExtensionAccess access) throws ApiException {
        return UiExtensionDispatch.call(() -> {
            if (owner.equals("platform") || bindings.containsKey(owner))
                throw UiExtensionDispatch.failure(ProblemCode.BUSY, "UI_EXTENSION_OWNER_RETAINED");
            var binding = new Binding(owner, recovery, access);
            bindings.put(owner, binding);
            return binding;
        });
    }

    /** Updates existing instances instead of rebuilding controls. / 更新现有实例，不重建控件。
     * @param next actual environment / 实际环境
     * @throws ApiException if dispatch fails / 调度失败时 */
    public void environment(UiEnvironment next) throws ApiException {
        UiExtensionDispatch.call(() -> {
            environment = Objects.requireNonNull(next);
            for (var mount : mounts()) mount.environmentChanged();
            return null;
        });
    }

    /** Revokes mounts belonging to a closing page. / 撤销属于关闭页面的挂载。
     * @param root original author root / 原作者根
     * @throws ApiException if cleanup remains pending / 清理仍待完成时 */
    public void closeWithin(JComponent root) throws ApiException {
        UiExtensionDispatch.call(() -> {
            ApiException first = null;
            for (var mount : mounts()) {
                if (mount.anchor == root || SwingUtilities.isDescendingFrom(mount.anchor, root)) {
                    try {
                        mount.close();
                    } catch (ApiException failure) {
                        if (first == null) first = failure;
                        else first.addSuppressed(failure);
                    }
                }
            }
            if (first != null) throw first;
            return null;
        });
    }

    /** Lists retained physical mounts without running callbacks. / 不运行回调地列出保留物理挂载。
     * @return snapshot of mounts / 挂载快照 */
    private List<Mount<?>> mounts() {
        return bindings.values().stream().flatMap(binding -> binding.points.values().stream())
            .flatMap(point -> point.mounts.stream()).<Mount<?>>map(mount -> mount).toList();
    }

    /** Resolves only exact provider-local position. / 仅解析精确提供方内位置。
     * @param target requested point / 请求位置
     * @return actual point or null / 实际位置或空 */
    private Point<?> point(UiExtensionPoint<?> target) {
        var provider = bindings.get(target.providerId());
        return provider == null ? null : provider.points.get(target.id());
    }

    /** Refreshes matching mounts from snapshots. / 通过快照更新匹配挂载。
     * @param target exact destination / 精确目的地 */
    private void refresh(UiExtensionPoint<?> target) {
        var point = point(target);
        if (point != null) for (var mount : List.copyOf(point.mounts)) mount.refresh(false);
    }

    /** One staged activation, shared with page publication. / 与页面发布共用的单个暂存启用。 */
    public final class Binding implements UiExtensionService, AutoCloseable {
        /** Actual bound owner. / 实际绑定所属者。 */
        private final String owner;

        /** Recovery-only lifetime. / 仅恢复寿命。 */
        private final boolean recovery;

        /** Actual loader policy. / 实际加载器策略。 */
        final UiExtensionAccess access;

        /** Owned point declarations. / 所属位置声明。 */
        private final Map<String, Point<?>> points = new LinkedHashMap<>();

        /** Owned contribution registrations. / 所属扩展登记。 */
        private final Map<String, Contribution<?>> entries = new LinkedHashMap<>();

        /** Publication and stop gates. / 发布及停止门禁。 */
        private boolean published, stopping, closed;

        /** Captures an actual activation. / 捕获实际启用。
         * @param owner bound owner / 绑定所属者
         * @param recovery restricted lifetime / 受限寿命
         * @param access original policy / 原策略 */
        private Binding(String owner, boolean recovery, UiExtensionAccess access) {
            this.owner = Objects.requireNonNull(owner);
            this.recovery = recovery;
            this.access = Objects.requireNonNull(access);
        }

        /** Publishes staged points and contents together. / 一起发布暂存位置及内容。
         * @throws ApiException if the activation is sealed / 启用已封闭时 */
        public void publish() throws ApiException {
            UiExtensionDispatch.call(() -> {
                requireOpen();
                published = true;
                for (var mount : mounts()) mount.refresh(false);
                return null;
            });
        }

        /** Disables callbacks immediately while keeping retryable resources. / 立即禁用回调，保留可重试资源。
         * @throws ApiException if dispatch fails / 调度失败时 */
        public void seal() throws ApiException {
            UiExtensionDispatch.call(() -> {
                stopping = true;
                for (var mount : mounts()) mount.refresh(false);
                return null;
            });
        }

        /** Reports actual callback availability. / 报告实际回调可用性。
         * @return whether normal publication remains active / 普通发布是否仍活动 */
        private boolean live() {
            return published && !recovery && !stopping && !closed && access.active();
        }

        /** Checks new owner operations. / 核对所属者新操作。
         * @throws ApiException if owner or mode is unavailable / 所属者或模式不可用时 */
        private void requireOpen() throws ApiException {
            if (stopping || closed || !access.active() || recovery || isolatedDepth != 0)
                throw UiExtensionDispatch.failure(ProblemCode.UNAVAILABLE, "UI_EXTENSION_OWNER_UNAVAILABLE");
        }

        /** Declares a position under this actual owner. / 在当前真实所属者下声明位置。
         * @param <C> shared context / 共享上下文
         * @param id local position / 本地位置
         * @param type shared type / 共享类型
         * @param kind accepted form / 接受形式
         * @return owned point / 所属位置
         * @throws ApiException if validation fails / 校验失败时 */
        @Override
        public <C> UiExtensionPointRegistration<C> provide(String id, Class<C> type, UiExtensionKind kind) throws ApiException {
            return UiExtensionDispatch.call(() -> {
                requireOpen();
                access.checkContextType(type, true);
                var spec = new UiExtensionPoint<>(owner, id, type, kind);
                if (points.containsKey(id))
                    throw UiExtensionDispatch.failure(ProblemCode.BUSY, "UI_EXTENSION_POINT_EXISTS");
                var point = new Point<>(this, spec);
                points.put(id, point);
                return point;
            });
        }

        /** Registers a deferred action. / 登记延后动作。
         * @param <C> shared context / 共享上下文
         * @param point exact target / 精确目标
         * @param spec identity and order / 身份及排序
         * @param action author action / 作者动作
         * @return owned registration / 所属登记
         * @throws ApiException if validation fails / 校验失败时 */
        @Override
        public <C> UiExtensionRegistration registerAction(UiExtensionPoint<C> point, UiExtensionSpec spec, UiAction<C> action) throws ApiException {
            Objects.requireNonNull(action);
            return register(point, spec, UiExtensionKind.ACTION, action, null);
        }

        /** Registers a deferred fresh component. / 登记延后新组件。
         * @param <C> shared context / 共享上下文
         * @param point exact target / 精确目标
         * @param spec identity and order / 身份及排序
         * @param factory fresh factory / 新实例工厂
         * @return owned registration / 所属登记
         * @throws ApiException if validation fails / 校验失败时 */
        @Override
        public <C> UiExtensionRegistration registerComponent(UiExtensionPoint<C> point, UiExtensionSpec spec, UiComponentFactory<C> factory) throws ApiException {
            Objects.requireNonNull(factory);
            return register(point, spec, UiExtensionKind.COMPONENT, null, factory);
        }

        /** Stages one exact typed contribution. / 暂存一个精确类型扩展。
         * @param <C> context type / 上下文类型
         * @param target exact target / 精确目标
         * @param spec identity / 身份
         * @param kind accepted form / 接受形式
         * @param action optional action / 可选动作
         * @param factory optional factory / 可选工厂
         * @return original registration / 原登记
         * @throws ApiException if validation fails / 校验失败时 */
        private <C> Contribution<C> register(UiExtensionPoint<C> target, UiExtensionSpec spec, UiExtensionKind kind,
                                             UiAction<C> action, UiComponentFactory<C> factory) throws ApiException {
            return UiExtensionDispatch.call(() -> {
                requireOpen();
                Objects.requireNonNull(spec);
                if (target.kind() != kind || entries.containsKey(spec.id()))
                    throw UiExtensionDispatch.failure(ProblemCode.INVALID_INPUT, "UI_EXTENSION_ID_OR_KIND_INVALID");
                var actual = point(target);
                access.checkTarget(target, actual == null ? Optional.empty() : Optional.of(actual.owner.access.version()));
                if (actual != null && !actual.spec.equals(target))
                    throw UiExtensionDispatch.failure(ProblemCode.INCOMPATIBLE, "UI_EXTENSION_POINT_TYPE_MISMATCH");
                var entry = new Contribution<>(this, target, spec, action, factory);
                entries.put(spec.id(), entry);
                refresh(target);
                return entry;
            });
        }

        /** Cleans owned contributions and points before releasing the activation. / 释放启用前清理所属内容及位置。
         * @throws ApiException if any actual cleanup remains pending / 实际清理仍待完成时 */
        @Override
        public void close() throws ApiException {
            UiExtensionDispatch.call(() -> {
                seal();
                ApiException first = null;
                var registrations = new ArrayList<AutoCloseable>(entries.values());
                registrations.addAll(points.values());
                for (var registration : registrations) {
                    try {
                        registration.close();
                    } catch (Exception failure) {
                        var problem = failure instanceof ApiException publicFailure ? publicFailure
                            : UiExtensionDispatch.failure(ProblemCode.BUSY, "UI_EXTENSION_CLEANUP_PENDING");
                        if (first == null) first = problem;
                        else first.addSuppressed(problem);
                    }
                }
                if (first != null) throw first;
                closed = true;
                bindings.remove(owner, this);
                return null;
            });
        }
    }

    /** Exact provider declaration with independent page mounts. / 具有独立页面挂载的精确提供方声明。
     * @param <C> shared context / 共享上下文 */
    private final class Point<C> implements UiExtensionPointRegistration<C> {
        /** Actual provider. / 实际提供方。 */
        private final Binding owner;

        /** Exact declaration. / 精确声明。 */
        private final UiExtensionPoint<C> spec;

        /** Physical mounts. / 物理挂载。 */
        private final List<Mount<C>> mounts = new ArrayList<>();

        /** Revocation gate. / 撤销门禁。 */
        private boolean closing;

        /** Captures the bound declaration. / 捕获绑定声明。
         * @param owner actual provider / 实际提供方
         * @param spec declaration / 声明 */
        private Point(Binding owner, UiExtensionPoint<C> spec) {
            this.owner = owner;
            this.spec = spec;
        }

        /** Returns the exact destination. / 返回精确目的地。
         * @return destination / 目的地 */
        @Override
        public UiExtensionPoint<C> point() {
            return spec;
        }

        /** Mounts once into this owned anchor. / 在所属位置挂载一次。
         * @param host display adapter / 展示适配器
         * @param context initial context / 初始上下文
         * @return physical mount / 物理挂载
         * @throws ApiException if lifetime or context differs / 寿命或上下文不符时 */
        @Override
        public UiExtensionMount<C> mount(UiExtensionHost host, Optional<C> context) throws ApiException {
            return UiExtensionDispatch.call(() -> {
                owner.requireOpen();
                if (closing) throw UiExtensionDispatch.failure(ProblemCode.UNAVAILABLE, "UI_EXTENSION_POINT_CLOSED");
                var anchor = Objects.requireNonNull(host.anchor());
                if (UiExtensionRegistry.this.mounts().stream().anyMatch(mount -> mount.anchor == anchor))
                    throw UiExtensionDispatch.failure(ProblemCode.BUSY, "UI_EXTENSION_ANCHOR_OCCUPIED");
                var mount = new Mount<>(this, host, anchor, UiExtensionValues.copy(spec.contextType(), context, owner.access));
                mounts.add(mount);
                mount.refresh(false);
                return mount;
            });
        }

        /** Withdraws only after all physical resources close. / 全部物理资源关闭后才撤销。
         * @throws ApiException if any child cleanup fails / 子项清理失败时 */
        @Override
        public void close() throws ApiException {
            UiExtensionDispatch.call(() -> {
                closing = true;
                ApiException first = null;
                for (var mount : List.copyOf(mounts)) {
                    try {
                        mount.close();
                    } catch (ApiException failure) {
                        if (first == null) first = failure;
                        else first.addSuppressed(failure);
                    }
                }
                if (first != null) throw first;
                owner.points.remove(spec.id(), this);
                return null;
            });
        }
    }

    /** One lazy owned contribution across physical mounts. / 跨物理挂载的单个惰性所属扩展。
     * @param <C> shared context / 共享上下文 */
    private final class Contribution<C> implements UiExtensionRegistration {
        /** Actual contributor. / 实际贡献者。 */
        private final Binding owner;

        /** Exact target. / 精确目标。 */
        private final UiExtensionPoint<C> target;

        /** Stable local metadata. / 稳定本地元数据。 */
        private final UiExtensionSpec spec;

        /** Action callbacks, if applicable. / 适用时的动作回调。 */
        private final UiAction<C> action;

        /** Component factory, if applicable. / 适用时的组件工厂。 */
        private final UiComponentFactory<C> factory;

        /** Active or retained child resources. / 活动或保留的子项资源。 */
        private final List<Item<C>> items = new ArrayList<>();

        /** Safe current diagnostic. / 安全当前诊断。 */
        private Problem problem;

        /** Callback revocation and complete release. / 回调撤销及完整释放。 */
        private boolean closing, closed;

        /** Captures original metadata without instantiation. / 捕获原元数据，不实例化。
         * @param owner contributor / 贡献者
         * @param target destination / 目的地
         * @param spec metadata / 元数据
         * @param action action / 动作
         * @param factory factory / 工厂 */
        private Contribution(Binding owner, UiExtensionPoint<C> target, UiExtensionSpec spec,
                             UiAction<C> action, UiComponentFactory<C> factory) {
            this.owner = owner;
            this.target = target;
            this.spec = spec;
            this.action = action;
            this.factory = factory;
        }

        /** Returns local identity. / 返回本地身份。
         * @return identity / 身份 */
        @Override
        public String id() {
            return spec.id();
        }

        /** Reports observable actual state. / 报告可观测实际状态。
         * @return current state / 当前状态
         * @throws ApiException if dispatch fails / 调度失败时 */
        @Override
        public UiExtensionState state() throws ApiException {
            return UiExtensionDispatch.call(() -> closed ? UiExtensionState.CLOSED : problem != null
                ? UiExtensionState.FAILED : items.stream().anyMatch(Item::live) ? UiExtensionState.MOUNTED : UiExtensionState.WAITING);
        }

        /** Returns safe current failure. / 返回安全当前故障。
         * @return diagnostic / 诊断
         * @throws ApiException if dispatch fails / 调度失败时 */
        @Override
        public Optional<Problem> problem() throws ApiException {
            return UiExtensionDispatch.call(() -> Optional.ofNullable(problem));
        }

        /** Records safe fixed failure without author text. / 记录不含作者文本的固定安全故障。
         * @param failure original failure / 原故障
         * @param event stable diagnostic event / 稳定诊断事件 */
        private void failed(Throwable failure, String event) {
            UiExtensionDispatch.fatal(failure);
            problem = failure instanceof ApiException publicFailure ? publicFailure.problem()
                : UiExtensionDispatch.failure(ProblemCode.CONTRACT_VIOLATION, event).problem();
            diagnostics.accept(problem);
        }

        /** Revokes first and retries incomplete children only. / 先撤销，仅重试未完成子项。
         * @throws ApiException if physical cleanup remains pending / 物理清理仍待完成时 */
        @Override
        public void close() throws ApiException {
            UiExtensionDispatch.call(() -> {
                closing = true;
                ApiException first = null;
                for (var item : List.copyOf(items)) {
                    try {
                        item.close();
                    } catch (ApiException failure) {
                        if (first == null) first = failure;
                        else first.addSuppressed(failure);
                    }
                }
                refresh(target);
                if (first != null) throw first;
                closed = true;
                owner.entries.remove(spec.id(), this);
                return null;
            });
        }
    }

    /** One physical point mount and retained ordered children. / 单个物理位置挂载及保留有序子项。
     * @param <C> shared context / 共享上下文 */
    private final class Mount<C> implements UiExtensionMount<C> {
        /** Exact declaration. / 精确声明。 */
        private final Point<C> point;

        /** Provider adapter. / 提供方适配器。 */
        private final UiExtensionHost host;

        /** Original cleanup anchor. / 原清理位置。 */
        private final JComponent anchor;

        /** Managed child identities. / 受管子项身份。 */
        private final Map<Contribution<C>, Item<C>> items = new LinkedHashMap<>();

        /** Latest detached selection. / 最新分离选择。 */
        private Optional<C> context;

        /** Provider readiness and revocation. / 提供方可用性及撤销。 */
        private boolean enabled = true, closing, closed, refreshing;

        /** Host mount failure blocks new interaction. / 宿主挂载故障阻止新交互。 */
        private boolean failed;

        /** Captures one physical lifetime. / 捕获单次物理寿命。
         * @param point original declaration / 原声明
         * @param host original adapter / 原适配器
         * @param anchor original root / 原根
         * @param context initial context / 初始上下文 */
        private Mount(Point<C> point, UiExtensionHost host, JComponent anchor, Optional<C> context) {
            this.point = point;
            this.host = host;
            this.anchor = anchor;
            this.context = context;
        }

        /** Reports host readiness without author callbacks. / 不调用作者地报告宿主可用性。
         * @return current interaction gate / 当前交互门禁 */
        private boolean live() {
            return !closing && !closed && !failed && !point.closing && point.owner.live();
        }

        /** Updates current context without losing existing widgets. / 更新上下文，不丢失已有控件。
         * @param context new context / 新上下文
         * @param enabled provider readiness / 提供方可用性
         * @throws ApiException if updating a closed mount or invalid value / 更新关闭挂载或无效值时 */
        @Override
        public void update(Optional<C> context, boolean enabled) throws ApiException {
            UiExtensionDispatch.call(() -> {
                if (closing || closed)
                    throw UiExtensionDispatch.failure(ProblemCode.UNAVAILABLE, "UI_EXTENSION_MOUNT_CLOSED");
                var next = UiExtensionValues.copy(point.spec.contextType(), context, point.owner.access);
                boolean changed = !this.context.equals(next);
                this.context = next;
                this.enabled = enabled;
                refresh(changed);
                return null;
            });
        }

        /** Updates appearance on existing child owners. / 在现有子项所有者上更新外观。 */
        private void environmentChanged() {
            for (var item : List.copyOf(items.values())) item.environmentChanged();
            refresh(false);
        }

        /** Finds matching published contents in stable order. / 以稳定顺序查找匹配已发布内容。
         * @return matching snapshot / 匹配快照 */
        @SuppressWarnings("unchecked")
        private List<Contribution<C>> contributions() {
            return bindings.values().stream().filter(Binding::live).flatMap(binding -> binding.entries.values().stream())
                .filter(entry -> !entry.closing && entry.target.providerId().equals(point.spec.providerId())
                    && entry.target.id().equals(point.spec.id()))
                .sorted(Comparator.comparingInt((Contribution<?> entry) -> entry.spec.order())
                    .thenComparing(entry -> entry.owner.owner).thenComparing(entry -> entry.spec.id()))
                .map(entry -> (Contribution<C>) entry).toList();
        }

        /** Refreshes individual children and isolates failed callbacks. / 更新独立子项并隔离失败回调。
         * @param contextChanged whether selection actually changed / 选择是否实际变化 */
        private void refresh(boolean contextChanged) {
            if (closed || refreshing) return;
            refreshing = true;
            try {
                if (live()) {
                    for (var entry : contributions()) ensure(entry);
                }
                for (var item : List.copyOf(items.values())) item.update(contextChanged);
                render();
            } finally {
                refreshing = false;
            }
        }

        /** Validates and creates one child at most once. / 校验并至多一次创建单个子项。
         * @param entry original contribution / 原扩展 */
        private void ensure(Contribution<C> entry) {
            if (items.containsKey(entry)) return;
            var item = new Item<>(this, entry);
            items.put(entry, item);
            entry.items.add(item);
            try {
                if (!entry.target.equals(point.spec))
                    throw UiExtensionDispatch.failure(ProblemCode.INCOMPATIBLE, "UI_EXTENSION_POINT_TYPE_MISMATCH");
                entry.owner.access.checkTarget(point.spec, Optional.of(point.owner.access.version()));
                item.create();
            } catch (Exception | Error failure) {
                if (!item.failed) item.fail(failure, "UI_EXTENSION_CREATION_FAILED");
            }
        }

        /** Installs only visible live or cleanup-retained children. / 仅安装可见活动或清理保留子项。 */
        private void render() {
            try {
                var ordered = items.values().stream().filter(item -> item.control != null && item.visible)
                    .sorted(Comparator.comparingInt((Item<C> item) -> item.entry.spec.order())
                        .thenComparing(item -> item.entry.owner.owner).thenComparing(item -> item.entry.spec.id()))
                    .map(item -> item.control).toList();
                host.setContents(ordered);
            } catch (Exception | Error failure) {
                UiExtensionDispatch.fatal(failure);
                failed = true;
                for (var item : List.copyOf(items.values())) {
                    item.disable();
                    item.entry.failed(failure, "UI_EXTENSION_MOUNT_FAILED");
                }
            }
        }

        /** Closes children before detaching the original host. / 分离原宿主前关闭子项。
         * @throws ApiException if child or host cleanup remains pending / 子项或宿主清理仍待完成时 */
        @Override
        public void close() throws ApiException {
            UiExtensionDispatch.call(() -> {
                if (closed) return null;
                closing = true;
                ApiException first = null;
                for (var item : List.copyOf(items.values())) {
                    try {
                        item.close();
                    } catch (ApiException failure) {
                        if (first == null) first = failure;
                        else first.addSuppressed(failure);
                    }
                }
                if (first != null) {
                    render();
                    throw first;
                }
                try {
                    host.setContents(List.of());
                } catch (Exception failure) {
                    throw UiExtensionDispatch.failure(ProblemCode.BUSY, "UI_EXTENSION_HOST_CLEANUP_PENDING");
                }
                closed = true;
                point.mounts.remove(this);
                return null;
            });
        }
    }

    /** Managed child with original callbacks and retryable physical cleanup. / 持有原回调及可重试物理清理的受管子项。
     * @param <C> shared context / 共享上下文 */
    private final class Item<C> {
        /** Original physical mount. / 原物理挂载。 */
        private final Mount<C> mount;

        /** Original owner registration. / 原所属者登记。 */
        private final Contribution<C> entry;

        /** Author component owner, if allocated. / 创建时的作者组件所有者。 */
        private UiComponent<C> author;

        /** Original author body. / 原作者内容。 */
        private JComponent body;

        /** Host-managed control or gated wrapper. / 宿主管理控件或门禁包装。 */
        private JComponent control;

        /** Component event gate. / 组件事件门禁。 */
        private Gate gate;

        /** Revocable host callback without retained author after close. / 关闭后不保留作者的可撤销宿主回调。 */
        private Callback callback;

        /** Current display state. / 当前展示状态。 */
        private boolean visible = true, authorEnabled, failed, closed, authorClosed;

        /** Captures the exact original owner. / 捕获精确原所属者。
         * @param mount original mount / 原挂载
         * @param entry original registration / 原登记 */
        private Item(Mount<C> mount, Contribution<C> entry) {
            this.mount = mount;
            this.entry = entry;
        }

        /** Reports callback availability, excluding business input gates. / 报告回调可用性，不含业务输入门禁。
         * @return whether this owner remains published / 所属者是否仍发布 */
        private boolean live() {
            return !closed && !failed && !entry.closing && entry.owner.live() && mount.live();
        }

        /** Copies current context separately for each recipient. / 为每个接收方独立复制当前上下文。
         * @return detached target / 分离目标
         * @throws Exception if public value copying fails / 公开值复制失败时 */
        private Optional<C> context() throws Exception {
            return UiExtensionValues.copy(entry.target.contextType(), mount.context, entry.owner.access);
        }

        /** Creates one independent child without attaching a reused body. / 创建独立子项，不挂载复用内容。
         * @throws Exception if creation fails / 创建失败时 */
        private void create() throws Exception {
            try {
                if (entry.action != null) {
                    var presentation = entry.action.presentation(context(), environment);
                    callback = new Callback(this::invoke);
                    body = Objects.requireNonNull(mount.host.createAction(effective(presentation), callback));
                    if (components.containsKey(body) || body.getParent() != null) {
                        body = null;
                        throw new IllegalArgumentException("Extension action is already mounted");
                    }
                    control = body;
                } else {
                    var candidate = Objects.requireNonNull(entry.factory.create());
                    if (authors.containsKey(candidate))
                        throw new IllegalArgumentException("Extension owner is already mounted");
                    author = candidate;
                    authors.put(author, this);
                    body = Objects.requireNonNull(author.create(context(), environment));
                    if (components.containsKey(body) || body.getParent() != null) {
                        body = null;
                        throw new IllegalArgumentException("Extension body is already mounted");
                    }
                    gate = new Gate();
                    control = new JLayer<>(body, gate);
                }
                components.put(body, this);
                entry.problem = null;
                if (!live()) close();
            } catch (Exception | Error failure) {
                fail(failure, "UI_EXTENSION_CREATION_FAILED");
                throw failure;
            }
        }

        /** Combines author display with actual host gates. / 结合作者展示与实际宿主门禁。
         * @param presentation original state / 原状态
         * @return effective state / 生效状态 */
        private UiActionPresentation effective(UiActionPresentation presentation) {
            visible = presentation.visible();
            authorEnabled = presentation.enabled();
            return new UiActionPresentation(presentation.text(), visible,
                authorEnabled && live() && mount.enabled && !environment.locked());
        }

        /** Updates controls and author context without recreation. / 更新控件及作者上下文，不重建。
         * @param contextChanged whether selection changed / 选择是否变化 */
        private void update(boolean contextChanged) {
            if (!live()) {
                disable();
                return;
            }
            try {
                if (entry.action != null) {
                    mount.host.updateAction(control, effective(entry.action.presentation(context(), environment)));
                } else {
                    if (contextChanged) author.contextChanged(context());
                    gate.enabled = mount.enabled && !environment.locked();
                    control.setEnabled(gate.enabled);
                    ((JLayer<?>) control).getGlassPane().setVisible(!gate.enabled);
                }
            } catch (Exception | Error failure) {
                fail(failure, "UI_EXTENSION_UPDATE_FAILED");
            }
        }

        /** Notifies existing authors of environment changes. / 通知现有作者环境变化。 */
        private void environmentChanged() {
            if (!live()) return;
            try {
                if (author != null) author.environmentChanged(environment);
                else SwingUtilities.updateComponentTreeUI(control);
            } catch (Exception | Error failure) {
                fail(failure, "UI_EXTENSION_ENVIRONMENT_FAILED");
            }
        }

        /** Captures click identity and rechecks publication and display. / 固定点击身份并复核发布及展示。 */
        private void invoke() {
            if (!live() || !mount.enabled || environment.locked()) return;
            try {
                var captured = context();
                var presentation = entry.action.presentation(captured, environment);
                if (!live() || !presentation.visible() || !presentation.enabled()) return;
                entry.owner.access.checkTarget(entry.target, Optional.of(mount.point.owner.access.version()));
                entry.action.invoke(captured);
                entry.problem = null;
            } catch (Exception | Error failure) {
                entry.failed(failure, "UI_EXTENSION_INVOKE_FAILED");
            }
        }

        /** Isolates a failed callback and keeps original resources for close. / 隔离失败回调，保留原资源供关闭。
         * @param failure original failure / 原故障
         * @param event stable diagnostic / 稳定诊断 */
        private void fail(Throwable failure, String event) {
            failed = true;
            disable();
            entry.failed(failure, event);
        }

        /** Seals actual event input without discarding author state. / 封闭实际事件输入，不丢弃作者状态。 */
        private void disable() {
            if (control != null) control.setEnabled(false);
            if (gate != null) gate.enabled = false;
            if (control instanceof JLayer<?> layer) layer.getGlassPane().setVisible(true);
        }

        /** Releases author first and then exact physical identity. / 先释放作者，再释放精确物理身份。
         * @throws ApiException if cleanup remains pending / 清理仍待完成时 */
        private void close() throws ApiException {
            disable();
            if (callback != null) callback.action = null;
            try {
                if (author != null && !authorClosed) {
                    author.close();
                    authorClosed = true;
                }
                if (control != null && control.getParent() != null) control.getParent().remove(control);
                components.remove(body, this);
                authors.remove(author, this);
                if (control instanceof JLayer<?> layer) {
                    layer.setView(null);
                    layer.setUI(null);
                }
                mount.items.remove(entry, this);
                entry.items.remove(this);
                closed = true;
            } catch (Exception | Error failure) {
                fail(failure, "UI_EXTENSION_CLEANUP_PENDING");
                throw UiExtensionDispatch.failure(ProblemCode.BUSY, "UI_EXTENSION_CLEANUP_PENDING");
            }
        }
    }

    /** EDT action bridge that drops its author reference on revocation. / 撤销时丢弃作者引用的 EDT 动作桥。 */
    private static final class Callback implements Runnable {
        /** Current callback, absent after release. / 当前回调，释放后不存在。 */
        private Runnable action;

        /** Captures the live callback until its owned close. / 捕获活动回调直至所属关闭。
         * @param action current author dispatch / 当前作者调度 */
        private Callback(Runnable action) {
            this.action = action;
        }

        /** Rechecks revocation on the EDT, even for a provider's worker trigger. / 即使提供方由线程触发，也在 EDT 复核撤销。 */
        @Override
        public void run() {
            try {
                UiExtensionDispatch.call(() -> {
                    if (action != null) action.run();
                    return null;
                });
            } catch (ApiException failure) {
                throw new IllegalStateException("UI action dispatch failed", failure);
            }
        }
    }

    /** Input shield preserving component drafts and original enabled flags. / 保留组件草稿及原启用标记的输入遮罩。 */
    private static final class Gate extends LayerUI<JComponent> {
        /** Exact removable mouse shield, retained across look-and-feel updates. / 跨外观更新保留的精确可撤销鼠标遮罩。 */
        private final MouseAdapter mouseShield = new MouseAdapter() {
        };

        /** Actual input permission. / 实际输入许可。 */
        private boolean enabled;

        /** Enables event observation on the original layer. / 在原层启用事件观察。
         * @param component original wrapper / 原包装 */
        @Override
        public void installUI(JComponent component) {
            super.installUI(component);
            ((JLayer<?>) component).getGlassPane().addMouseListener(mouseShield);
            ((JLayer<?>) component).getGlassPane().setVisible(true);
            ((JLayer<?>) component).setLayerEventMask(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
                | AWTEvent.MOUSE_WHEEL_EVENT_MASK | AWTEvent.KEY_EVENT_MASK | AWTEvent.FOCUS_EVENT_MASK);
        }

        /** Releases event observation with the original wrapper. / 随原包装释放事件观察。
         * @param component original wrapper / 原包装 */
        @Override
        public void uninstallUI(JComponent component) {
            ((JLayer<?>) component).getGlassPane().removeMouseListener(mouseShield);
            ((JLayer<?>) component).setLayerEventMask(0);
            super.uninstallUI(component);
        }

        /** Blocks business input while unavailable. / 不可用时阻止业务输入。
         * @param event original event / 原事件
         * @param layer original wrapper / 原包装 */
        @Override
        public void eventDispatched(AWTEvent event, JLayer<? extends JComponent> layer) {
            if (!enabled && event instanceof InputEvent input) input.consume();
        }
    }
}
