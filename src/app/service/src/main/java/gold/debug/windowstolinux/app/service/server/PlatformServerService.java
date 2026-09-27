package gold.debug.windowstolinux.app.service.server;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletionStage;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.PlatformPersistence;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.service.contract.spi.ServerProfileEditor;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;

/** Manages platform server settings and task-owned authentication checks without deployment code. / 管理平台服务器设置及任务所属认证检查，不依赖部署代码。 */
public final class PlatformServerService implements ServerProfileEditor, AutoCloseable {
    /** Stable protocol for a check that never dispatches a remote command. / 从不发送远端命令的连接检查稳定协议。 */
    private static final String CHECK_TYPE = "platform.ssh.check.v1";

    /** Authoritative platform repositories. / 权威平台仓库。 */
    private final PlatformPersistence persistence;

    /** Current-user settings and retained credential authority. / 当前用户设置及保留凭据授权方。 */
    private final DesktopSecurityService security;

    /** Central task and recovery coordinator. / 中央任务及恢复协调器。 */
    private final PlatformTaskRuntime runtime;

    /** Actual host release binding for new checks. / 新检查使用的实际宿主版本绑定。 */
    private final TaskService tasks;

    /** Actual platform transport with saved credentials and trust. / 使用已保存凭据及信任的实际平台传输。 */
    private final LinuxService linux;

    /** Host cleanup for retained physical resources. / 保留物理资源的宿主清理。 */
    private final TaskCleanup cleanup;

    /** Original-version recovery registrations for this stable check protocol. / 此稳定检查协议的原版本恢复登记。 */
    private final List<RecoveryRegistration> recoveries = new ArrayList<>();

    /** Current-process handles used only for explicit cancellation. / 仅用于显式取消的当前进程句柄。 */
    private final Map<UUID, TaskHandle<RemoteFacts>> handles = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Binds configuration and real SSH checks to the platform-owned task identity. / 将配置及真实 SSH 检查绑定到平台所属任务身份。
     * @param persistence current H2 platform repositories / 当前 H2 平台仓库
     * @param security current-user authorization / 当前用户授权
     * @param runtime central task runtime / 中央任务运行时
     * @param version actual application artifact release / 实际应用产物版本
     * @param linux host-owned public transport adapter / 宿主持有的公开传输适配器
     * @param cleanup physical cleanup without command replay / 不重放命令的物理清理
     * @throws ApiException if recovery registration is unavailable / 恢复登记不可用时
     */
    public PlatformServerService(PlatformPersistence persistence, DesktopSecurityService security,
            PlatformTaskRuntime runtime, String version, LinuxService linux, TaskCleanup cleanup) throws ApiException {
        this.persistence = Objects.requireNonNull(persistence);
        this.security = Objects.requireNonNull(security);
        this.runtime = Objects.requireNonNull(runtime);
        this.tasks = runtime.bind(PlatformTaskAdmission.PLATFORM_OWNER, version, false);
        this.linux = Objects.requireNonNull(linux);
        this.cleanup = Objects.requireNonNull(cleanup);
        var versions = new LinkedHashSet<String>();
        versions.add(version);
        for (var task : checks())
            versions.add(task.ownerVersion());
        try {
            for (var original : versions)
                recoveries.add(runtime.bind(PlatformTaskAdmission.PLATFORM_OWNER, original, true)
                        .registerRecovery(CHECK_TYPE, (previous, task) -> {
                            if (!releasePreviousProcessResources(previous))
                                this.cleanup.close(task);
                            return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(new Problem(
                                    ProblemCode.UNAVAILABLE, "SERVER_CHECK_INTERRUPTED",
                                    "The earlier authentication check did not deliver a confirmed result; start a new check if needed",
                                    Map.of(), Optional.empty())));
                        }));
        } catch (ApiException failure) {
            try {
                close();
            } catch (ApiException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    /**
     * Returns saved connection settings and dated observations without secret values. / 返回已保存连接设置及带时间观测，不返回秘密值。
     * @return immutable server summaries / 不可变服务器摘要
     * @throws SQLException if configuration cannot be read / 无法读取配置时
     */
    public List<ServerSummary> list() throws SQLException {
        worker();
        security.requireUnlocked();
        var summaries = new ArrayList<ServerSummary>();
        for (var stored : persistence.servers().listServerProfiles()) {
            var observation = persistence.servers().observation(stored.id());
            summaries.add(new ServerSummary(ServerProfile.fromStored(stored), observation.checkedAt(),
                    observation.connected(), observation.operatingSystem()));
        }
        return List.copyOf(summaries);
    }

    /**
     * Saves settings and optional replacement credentials in one platform transaction. / 在同一平台事务内保存设置及可选替换凭据。
     * @param profile exact edited profile / 精确已编辑配置
     * @param password optional replacement, always cleared / 可选替换密码，始终清零
     * @throws SQLException if metadata cannot commit / 无法提交元数据时
     * @throws SecretStoreException if credentials cannot commit / 无法提交凭据时
     */
    @Override
    public void saveServerProfile(ServerProfile profile, char[] password) throws SQLException, SecretStoreException {
        Objects.requireNonNull(password);
        try {
            worker();
            try (var operation = security.enterOperation(); var credentials = security.openCredentials()) {
                credentials.transaction(() -> {
                    if (password.length == 0) {
                        var existing = persistence.servers().findServerProfile(profile.id())
                                .orElseThrow(() -> new IllegalArgumentException("Server password is required"));
                        if (!existing.credentialKey().equals(profile.credentialKey()))
                            throw new IllegalArgumentException("Changing credential storage requires a password");
                    } else {
                        credentials.save(profile.credentialKey(), password);
                    }
                    persistence.servers().saveServerProfile(profile.stored());
                });
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * Deletes an idle unreferenced profile and only an otherwise unowned credential. / 删除空闲无引用配置，并且仅清理没有其他所有者的凭据。
     * @param id exact saved server / 精确已保存服务器
     * @throws SQLException if tasks or business references block deletion / 任务或业务引用阻止删除时
     * @throws SecretStoreException if credential cleanup fails / 凭据清理失败时
     */
    public void delete(String id) throws SQLException, SecretStoreException {
        worker();
        try (var operation = security.enterOperation(); var credentials = security.openCredentials()) {
            credentials.transaction(() -> {
                var credential = persistence.servers().deleteServerProfile(id);
                if (credential.isPresent() && !persistence.security().hasOwners(credential.orElseThrow()))
                    credentials.delete(credential.orElseThrow());
            });
        }
    }

    /**
     * Submits an authentication-only check using the saved endpoint and complete server claim. / 使用已保存端点及完整服务器申请提交仅认证检查。
     * @param profile selected saved metadata / 选定的已保存元数据
     * @return original central task handle / 原始中央任务句柄
     * @throws ApiException if new checks are unavailable / 新检查不可用时
     */
    public TaskHandle<RemoteFacts> check(ServerProfile profile) throws ApiException {
        worker();
        var handle = tasks.<RemoteFacts>submit(
                new TaskRequest(CHECK_TYPE, "SSH: " + profile.displayName(), List.of(profile.id()), true), task -> {
                    var current = persistence.servers().findServerProfile(profile.id())
                            .orElseThrow(() -> new SQLException("Admitted server is missing"));
                    try (var session = linux.connect(new ServerRef(profile.id()), task)) {
                        var facts = session.facts();
                        persistence.servers().recordObservation(current, true, facts.operatingSystem());
                        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(facts), Optional.empty());
                    } catch (ApiException failure) {
                        if (failure.problem().code() != ProblemCode.CANCELLED)
                            persistence.servers().recordObservation(current, false, "");
                        return new OperationResult<>(
                                failure.problem().code() == ProblemCode.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED,
                                Optional.empty(), Optional.of(failure.problem()));
                    }
                });
        handles.put(handle.id(), handle);
        handle.completion().whenComplete((result, failure) -> handles.remove(handle.id()));
        return handle;
    }

    /**
     * Requests cooperative cancellation of a current-process authentication check. / 请求协作取消当前进程的认证检查。
     * @param id original check identity / 原始检查身份
     * @throws ApiException if no cancellable current handle exists / 不存在可取消的当前句柄时
     */
    public void cancel(UUID id) throws ApiException {
        worker();
        var handle = handles.get(id);
        if (handle == null)
            throw new ApiException(new Problem(ProblemCode.NOT_FOUND, "SERVER_CHECK_HANDLE",
                    "This check has no active cancellation handle", Map.of(), Optional.empty()));
        handle.requestCancel();
    }

    /**
     * Releases only vanished process-local resources of this authentication-only protocol. / 仅释放此仅认证协议已经消失的进程内资源。
     * @param task exact original recovery snapshot / 精确原始恢复快照
     * @return whether the task belongs to an earlier exclusive database boot / 任务是否属于更早的数据库独占启动
     * @throws SQLException if ownership or a resource cannot be proved / 无法证明归属或资源类型时
     */
    private boolean releasePreviousProcessResources(TaskSnapshot task) throws SQLException {
        return HostTaskRecovery.releasePreviousProcess(persistence.database(), task,
                Map.of("CONNECTION", "ssh-", "CUSTOM", "interaction-"));
    }

    /** Returns in-memory unfinished host checks without reading business tables. / 返回内存中的未结束宿主检查，不读取业务表。
     * @return immutable retained check snapshots / 不可变保留检查快照 */
    public List<TaskSnapshot> checks() {
        return runtime.snapshots().stream().filter(task -> task.ownerId().equals(PlatformTaskAdmission.PLATFORM_OWNER)
                && task.type().equals(CHECK_TYPE) && task.state() != TaskState.ENDED).toList();
    }

    /**
     * Reconciles only the stable authentication-only protocol and never repeats a connection. / 仅核对稳定的仅认证协议，绝不重复连接。
     * @param id original retained check identity / 原始保留检查身份
     * @return completion of explicit cleanup and interruption recording / 显式清理及中断记录的完成状态
     * @throws ApiException if the task is foreign or still executing / 任务不属于当前能力或仍在执行时
     */
    public CompletionStage<TaskSnapshot> reconcile(UUID id) throws ApiException {
        worker();
        if (checks().stream().noneMatch(task -> task.id().equals(id)))
            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "SERVER_CHECK_IDENTITY",
                    "The task is not a retained platform authentication check", Map.of(), Optional.empty()));
        return runtime.reconcile(id);
    }

    /**
     * Releases owned recovery callbacks after host work drains. / 宿主工作排空后释放所属恢复回调。
     * @throws ApiException if a callback is still in use / 回调仍被使用时
     */
    @Override
    public void close() throws ApiException {
        for (var registration : List.copyOf(recoveries)) {
            registration.close();
            recoveries.remove(registration);
        }
    }

    /** Host-only cleanup without remote business replay. / 不重放远端业务的宿主专用清理。 */
    @FunctionalInterface
    public interface TaskCleanup {
        /**
         * Releases physically finished resources for the original task. / 释放原任务的物理已结束资源。
         * @param task original retained task / 原始保留任务
         * @throws ApiException if cleanup remains incomplete / 清理仍未完成时
         */
        void close(TaskContext task) throws ApiException;
    }

    /** Rejects blocking configuration and network admission on the event thread. / 拒绝在事件线程执行阻塞配置及网络准入。 */
    private static void worker() {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Server operations require a background thread");
    }
}
