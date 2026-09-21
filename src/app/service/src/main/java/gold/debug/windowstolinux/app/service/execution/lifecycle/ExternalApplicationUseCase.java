package gold.debug.windowstolinux.app.service.execution.lifecycle;

import gold.debug.windowstolinux.app.db.entity.StoredExternalApplication;
import gold.debug.windowstolinux.app.db.persistence.repository.ExternalApplicationRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.runtime.ExternalApplicationPort;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/**
 * Attaches existing applications without modifying runtime configuration or weakening managed ownership. / 接入既有应用，不修改运行配置或削弱受管归属。
 */
public final class ExternalApplicationUseCase {
    /**
     * Bound external application repository collaborator for applications.
     * <p>处理应用集合的外部应用仓库协作对象。
     */
    private final ExternalApplicationRepository applications;
    /**
     * Bound managed application repository collaborator for managed.
     * <p>处理受管的受管应用仓库协作对象。
     */
    private final ManagedApplicationRepository managed;
    /**
     * Bound server use case facade collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的服务器用例门面协作对象。
     */
    private final ServerUseCaseFacade servers;
    /**
     * Factory for authenticated Linux sessions.
     * <p>已认证 Linux 会话的工厂。
     */
    private final DeploymentLinuxGateway gateway;
    /**
     * Shared operation locks indexed by target identity.
     * <p>按目标身份索引的共享操作锁。
     */
    private final ServerOperationLockRegistry locks;

    /**
     * Uses the existing session, secret and server-operation boundaries. / 复用既有会话、秘密及服务器操作边界。
     *
     * @param applications applications / 应用集合
     * @param managed managed / 受管
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param locks shared operation locks indexed by target identity / 按目标身份索引的共享操作锁
     */
    public ExternalApplicationUseCase(ExternalApplicationRepository applications, ManagedApplicationRepository managed,
                                      ServerUseCaseFacade servers, DeploymentLinuxGateway gateway, ServerOperationLockRegistry locks) {
        this.applications = applications; this.managed = managed; this.servers = servers; this.gateway = gateway; this.locks = locks;
    }

    /**
     * Scans metadata only and maps already registered identities. / 仅扫描元数据，并映射已登记身份。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved application scan / 构造或解析得到的应用扫描
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public ApplicationScan scan(String serverId, char[] master, Predicate<String> confirmation) throws Exception {
        try {
            ServerProfile server = requireServer(serverId);
            var result = withSession(server, master, confirmation, ExternalApplicationPort::scan);
            Map<String, String> registrations = new HashMap<>();
            for (var app : managed.list()) if (app.server().id().equals(serverId)) {
                registrations.put("SYSTEMD/" + app.systemdUnit(), "managed:" + app.id());
                result.applications().stream().filter(value -> value.target().kind() == ExternalApplicationKind.DOCKER
                        && value.name().equals("windowstolinux-" + app.id())).forEach(value -> registrations.put(value.target().key(), "managed:" + app.id()));
            }
            for (var app : applications.list()) if (app.serverId().equals(serverId)) registrations.put(app.application().target().key(), app.id());
            return new ApplicationScan(server, result.applications(), result.issues(), registrations);
        } finally { Arrays.fill(master, '\0'); }
    }

    /**
     * Rechecks the selected scan identity before atomically importing or explicitly reattaching it. / 原子导入或明确重新接管前复核选定扫描身份。
     *
     * @param scan scan / 扫描
     * @param candidate candidate / 候选
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return adopt text / 接管文本
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public String adopt(ApplicationScan scan, DiscoveredApplication candidate, char[] master, Predicate<String> confirmation) throws Exception {
        var lock = locks.forServer(scan.server().id());
        try {
            lock.lockInterruptibly();
            if (!scan.candidates().contains(candidate) || !scan.adoptable(candidate)) throw ownershipRequired();
            ServerProfile server = requireServer(scan.server().id());
            if (!server.endpoint().equals(scan.server().endpoint())) throw changed();
            var fresh = withSession(server, master, confirmation, port -> port.execute(candidate.target(), LifecycleAction.REFRESH_STATUS));
            if (fresh.managed()) {
                String existing = scan.registrations().getOrDefault(fresh.target().key(), "");
                if (!existing.startsWith("managed:") || managed.find(existing.substring(8)).filter(app ->
                        app.server().id().equals(server.id()) && (fresh.target().kind() == ExternalApplicationKind.SYSTEMD
                                ? app.systemdUnit().equals(fresh.target().identity()) : fresh.name().equals("windowstolinux-" + app.id()))).isEmpty()) throw ownershipRequired();
                return existing;
            }
            Instant now = Instant.now();
            return applications.adopt(new StoredExternalApplication("external:" + UUID.randomUUID(), server.id(), server.host(),
                    server.sshPort(), server.username(), fresh, now, now), true);
        } finally { if (lock.isHeldByCurrentThread()) lock.unlock(); Arrays.fill(master, '\0'); }
    }

    /**
     * Revalidates the adopted endpoint and exact runtime before every external operation. / 每次外部操作前复核接管端点及精确运行时。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved application lifecycle result / 构造或解析得到的应用生命周期结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public ApplicationLifecycleResult execute(String id, LifecycleAction action, char[] master, Predicate<String> confirmation) throws Exception {
        try {
            var stored = applications.find(id).orElseThrow(() -> new IllegalArgumentException("external application no longer exists"));
            var lock = locks.forServer(stored.serverId()); lock.lockInterruptibly();
            try {
                var server = requireServer(stored.serverId());
                if (!server.host().equals(stored.host()) || server.sshPort() != stored.sshPort() || !server.username().equals(stored.username())) throw changed();
                var result = withSession(server, master, confirmation, port -> port.execute(stored.application().target(), action));
                if (result.managed()) throw ownershipRequired();
                Instant now = Instant.now(); applications.observe(id, result, now);
                return new ApplicationLifecycleResult(result.state(), now, Optional.empty());
            } finally { lock.unlock(); }
        } finally { Arrays.fill(master, '\0'); }
    }

    /**
     * Validates and returns server identity or selected server configuration.
     * <p>校验并返回服务器身份或所选服务器配置。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved server profile / 构造或解析得到的服务器配置资料
     * @throws java.sql.SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private ServerProfile requireServer(String id) throws java.sql.SQLException {
        return servers.find(id).orElseThrow(() -> ApplicationServiceException.create(ApplicationServiceFailureType.SERVER_PROFILE_MISSING, "Saved server profile is required"));
    }

    /**
     * Runs scoped work while owning the authenticated remote session's cleanup.
     * <p>执行限定作用域工作，并负责已认证远端会话的清理。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @param work work / 工作
     * @return constructed or resolved T / 构造或解析得到的T
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private <T> T withSession(ServerProfile server, char[] master, Predicate<String> confirmation, RemoteWork<T> work) throws Exception {
        try (var store = servers.secrets().open(server.credentialMode(), master);
             var session = gateway.connect(server.endpoint(), servers.loadPassword(server, store), servers.hostKeyVerifier(server, confirmation))) {
            return work.run(session.externalApplications());
        }
    }
    /**
     * Builds the structured failure descriptor for changed.
     * <p>为已变化构建结构化失败描述。
     *
     * @return the structured failure descriptor for changed / 为已变化构建结构化失败描述
     */
    private static LinuxOperationException changed() {
        return LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_IDENTITY_CHANGED, "Selected runtime or server identity changed; scan again");
    }
    /**
     * Builds the structured failure descriptor for ownership required.
     * <p>为归属必需构建结构化失败描述。
     *
     * @return the structured failure descriptor for ownership required / 为归属必需构建结构化失败描述
     */
    private static LinuxOperationException ownershipRequired() {
        return LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_OWNERSHIP_REQUIRED, "A managed application requires its original ownership contract");
    }
    /**
     * Executes application work inside an authenticated remote session.
     * <p>在已认证远端会话内执行应用工作。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     */
    @FunctionalInterface private interface RemoteWork<T> {
    /**
     * Runs T.
     * <p>运行T。
     *
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @return constructed or resolved T / 构造或解析得到的T
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
     T run(ExternalApplicationPort port) throws Exception; }
}
