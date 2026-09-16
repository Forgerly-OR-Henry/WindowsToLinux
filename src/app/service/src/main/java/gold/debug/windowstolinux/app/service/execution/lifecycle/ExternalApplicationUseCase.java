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

/** Attaches existing applications without modifying runtime configuration or weakening managed ownership. / 接入既有应用，不修改运行配置或削弱受管归属。 */
public final class ExternalApplicationUseCase {
    private final ExternalApplicationRepository applications;
    private final ManagedApplicationRepository managed;
    private final ServerUseCaseFacade servers;
    private final DeploymentLinuxGateway gateway;
    private final ServerOperationLockRegistry locks;

    /** Uses the existing session, secret and server-operation boundaries. / 复用既有会话、秘密及服务器操作边界。 */
    public ExternalApplicationUseCase(ExternalApplicationRepository applications, ManagedApplicationRepository managed,
                                      ServerUseCaseFacade servers, DeploymentLinuxGateway gateway, ServerOperationLockRegistry locks) {
        this.applications = applications; this.managed = managed; this.servers = servers; this.gateway = gateway; this.locks = locks;
    }

    /** Scans metadata only and maps already registered identities. / 仅扫描元数据，并映射已登记身份。 */
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

    /** Rechecks the selected scan identity before atomically importing or explicitly reattaching it. / 原子导入或明确重新接管前复核选定扫描身份。 */
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

    /** Revalidates the adopted endpoint and exact runtime before every external operation. / 每次外部操作前复核接管端点及精确运行时。 */
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

    private ServerProfile requireServer(String id) throws java.sql.SQLException {
        return servers.find(id).orElseThrow(() -> ApplicationServiceException.create(ApplicationServiceFailureType.SERVER_PROFILE_MISSING, "Saved server profile is required"));
    }

    private <T> T withSession(ServerProfile server, char[] master, Predicate<String> confirmation, RemoteWork<T> work) throws Exception {
        try (var store = servers.secrets().open(server.credentialMode(), master);
             var session = gateway.connect(server.endpoint(), servers.loadPassword(server, store), servers.hostKeyVerifier(server, confirmation))) {
            return work.run(session.externalApplications());
        }
    }
    private static LinuxOperationException changed() {
        return LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_IDENTITY_CHANGED, "Selected runtime or server identity changed; scan again");
    }
    private static LinuxOperationException ownershipRequired() {
        return LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_OWNERSHIP_REQUIRED, "A managed application requires its original ownership contract");
    }
    @FunctionalInterface private interface RemoteWork<T> { T run(ExternalApplicationPort port) throws Exception; }
}
