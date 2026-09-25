package gold.debug.windowstolinux.app.mod.lifecycle;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.api.mod.*;
import gold.debug.windowstolinux.api.task.*;
import gold.debug.windowstolinux.app.mod.loader.*;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;

/** Activates one prepared Mod and preserves partial-stop ownership until actual cleanup. / 启用一个已准备的 Mod，并保留部分停止归属直至实际清理。 */
public final class ModRuntimeSession {
    /** Prepared loader owned until the final cleanup step. / 持有到最终清理步骤的已准备加载器。 */
    private final ModClassLoader loader;

    /** Host capabilities and staged registrations bound to this exact startup. / 绑定此次精确启动的宿主能力及暂存登记。 */
    private final ContextLease lease;

    /** Actual host API release used for compatibility checks. / 用于兼容校验的实际宿主 API 版本。 */
    private final SemanticVersion platformApi;

    /** Prevents concurrent lifecycle callbacks without holding a monitor across author code. / 不跨作者代码持有监视器的生命周期回调互斥标记。 */
    private final AtomicBoolean operation = new AtomicBoolean();

    /** Current in-process session state, independent of saved user intent. / 独立于已保存用户意图的当前进程会话状态。 */
    private volatile SessionState state = SessionState.PREPARED;

    /** Whether activation was attempted, forbidding replay on this loader. / 是否已尝试启用，用于禁止在此加载器上重放。 */
    private boolean startAttempted;

    /** Constructed entry retained even when its start callback fails. / 即使启动回调失败也保留的已构造入口。 */
    private ModEntrypoint entry;

    /** Whether the entry stop callback actually succeeded. / 入口停止回调是否实际成功。 */
    private boolean entryStopped;

    /** Whether all host registrations and owned resources actually closed. / 所有宿主登记及所属资源是否实际关闭。 */
    private boolean contextClosed;

    /** Whether the final class-loader close succeeded. / 最终类加载器关闭是否成功。 */
    private volatile boolean resourcesClosed;

    /**
     * Takes ownership of dependency-checked loading and host context preparation. / 接管已完成依赖校验的加载器及宿主上下文准备结果。
     * @param loader prepared owner loader / 已准备的所有者加载器
     * @param lease host context and registration ownership / 宿主上下文及登记归属
     * @param platformApi actual host API release / 实际宿主 API 版本
     */
    public ModRuntimeSession(ModClassLoader loader, ContextLease lease, SemanticVersion platformApi) {
        this.loader = Objects.requireNonNull(loader);
        this.lease = Objects.requireNonNull(lease);
        this.platformApi = Objects.requireNonNull(platformApi);
    }

    /**
     * Starts the entry once and publishes contributions only after its callback succeeds. / 仅启动入口一次，并仅在回调成功后发布扩展。
     * @throws Exception if compatibility, author startup, publication or rollback cleanup fails / 兼容性、作者启动、发布或回退清理失败时
     */
    public void start() throws Exception {
        enter();
        try {
            if (startAttempted || resourcesClosed)
                throw new IllegalStateException("This Mod session cannot be started again");
            startAttempted = true;
            state = SessionState.STARTING;
            try {
                var manifest = loader.manifest();
                if (Runtime.version().feature() < manifest.minJavaVersion()
                        || !manifest.platformApiRange().contains(platformApi))
                    throw new IllegalStateException("Mod runtime compatibility requirements are not met");
                var context = Objects.requireNonNull(lease.context());
                if (!new ModIdentity(manifest.id(), manifest.version().toString(), manifest.schemaVersion())
                        .equals(context.identity()))
                    throw new IllegalStateException("Host context does not match the prepared Mod identity");
                Objects.requireNonNull(context.launchMode());
                entry = ModEntryFactory.create(loader);
                entry.start(context);
                lease.commit();
                state = SessionState.RUNNING;
            } catch (Exception | LinkageError | AssertionError failure) {
                state = SessionState.FAILED;
                try {
                    drain(StopReason.START_FAILURE);
                } catch (Exception | LinkageError | AssertionError cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        } finally {
            operation.set(false);
        }
    }

    /**
     * Seals new admission and stops only after task/resource blockers have drained. / 封闭新准入，并仅在任务及资源阻断已排空后停止。
     * @param reason host-selected stop cause / 宿主选定的停止原因
     * @throws Exception if work remains or any actual cleanup fails / 仍有工作或任一实际清理失败时
     */
    public void stop(StopReason reason) throws Exception {
        Objects.requireNonNull(reason);
        enter();
        try {
            if (resourcesClosed)
                return;
            state = SessionState.STOPPING;
            try {
                drain(reason);
                state = SessionState.STOPPED;
            } catch (Exception | LinkageError | AssertionError failure) {
                state = failure instanceof ApiException api && api.problem().code() == ProblemCode.BUSY
                        ? SessionState.STOPPING
                        : SessionState.FAILED;
                throw failure;
            }
        } finally {
            operation.set(false);
        }
    }

    /**
     * Executes cleanup in entry, context and loader order, skipping confirmed completed steps. / 按入口、上下文及加载器顺序清理，跳过已确认完成的步骤。
     * @param reason explicit stop cause / 明确停止原因
     * @throws Exception if occupancy or cleanup remains unresolved / 占用或清理仍未解决时
     */
    private void drain(StopReason reason) throws Exception {
        lease.seal();
        var blockers = List.copyOf(lease.blockers());
        if (!blockers.isEmpty())
            throw new ApiException(new Problem(ProblemCode.BUSY, "MOD_STOP_BLOCKED",
                    "Mod cleanup is blocked: " + String.join("; ", blockers), Map.of("ownerId", loader.manifest().id()),
                    Optional.empty()));
        if (entry != null && !entryStopped) {
            entry.stop(() -> reason);
            entryStopped = true;
        }
        if (!contextClosed) {
            lease.close();
            contextClosed = true;
        }
        if (!resourcesClosed) {
            loader.close();
            resourcesClosed = true;
        }
    }

    /**
     * Acquires lifecycle ownership while rejecting EDT blocking and reentry. / 取得生命周期所有权，并拒绝阻塞 EDT 及重入。
     */
    private void enter() {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Mod lifecycle callbacks require a background thread");
        if (!operation.compareAndSet(false, true))
            throw new IllegalStateException("Another Mod lifecycle operation is still running");
    }

    /** Returns the latest process-local state without changing persisted user choices. / 返回最新进程内状态，不改变持久化用户选择。
     * @return current session state / 当前会话状态 */
    public SessionState state() {
        return state;
    }

    /** Reports whether every owned close step actually succeeded. / 报告每个所属关闭步骤是否实际成功。
     * @return true only after entry, context and loader cleanup / 仅在入口、上下文及加载器清理后为真 */
    public boolean resourcesClosed() {
        return resourcesClosed;
    }

    /** Process-local activation state, not a replacement for persistent Mod intent. / 进程内启用状态，不替代持久化 Mod 意图。 */
    public enum SessionState {
        /** Prepared resources have not invoked author startup. / 已准备资源尚未调用作者启动。 */
        PREPARED,
        /** Author startup and registration publication are in progress. / 作者启动及登记发布正在进行。 */
        STARTING,
        /** Startup and contribution publication succeeded. / 启动及扩展发布成功。 */
        RUNNING,
        /** Admission is sealing and resources are draining. / 正在封闭准入及释放资源。 */
        STOPPING,
        /** Startup or cleanup failed with ownership retained as needed. / 启动或清理失败，并按需保留归属。 */
        FAILED,
        /** All owned resources closed after a requested stop. / 请求停止后所有所属资源已关闭。 */
        STOPPED
    }

    /** Host-supplied lifecycle ownership, keeping app/mod independent of service and Swing implementations. / 宿主提供的生命周期归属，使 app/mod 独立于服务及 Swing 实现。 */
    public interface ContextLease extends AutoCloseable {
        /** Returns the exact caller-bound public capabilities. / 返回精确绑定调用方的公开能力。
         * @return prepared public context / 已准备的公开上下文 */
        ModContext context();

        /**
         * Publishes staged registrations only after author start succeeded. / 仅在作者启动成功后发布暂存登记。
         * @throws Exception if any publication cannot be confirmed / 无法确认任一发布时
         */
        void commit() throws Exception;

        /**
         * Prevents new work before checking the current occupied resource set. / 检查当前占用资源集合前阻止新工作。
         * @throws Exception if admission could not be sealed / 无法封闭准入时
         */
        void seal() throws Exception;

        /**
         * Reports work and resources that must drain before author stop. / 报告作者停止前必须排空的工作及资源。
         * @return immutable non-secret blocking reasons / 不可变且不含秘密的阻断原因
         * @throws Exception if resource state cannot be verified / 无法核实资源状态时
         */
        List<String> blockers() throws Exception;

        /**
         * Closes registrations and remaining initialization resources after author stop. / 作者停止后关闭登记及剩余初始化资源。
         * @throws Exception if actual resource cleanup remains incomplete / 实际资源清理仍未完成时
         */
        @Override
        void close() throws Exception;
    }
}
