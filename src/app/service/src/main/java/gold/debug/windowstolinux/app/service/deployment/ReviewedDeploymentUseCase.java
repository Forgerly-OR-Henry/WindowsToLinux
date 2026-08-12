package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.app.db.DesktopDatabase;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.service.concurrency.ServerOperationLocks;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.transaction.ReviewedDeploymentService;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persists successful reviewed deployments in the same managed-application inventory as existing deployments.
 *
 * <p>将成功的经审阅部署持久化到与现有部署相同的受管应用清单中。
 */
public final class ReviewedDeploymentUseCase {
    private final DesktopDatabase database;
    private final ReviewedDeploymentService service;
    private final DeploymentLinuxGateway gateway;
    private final ServerOperationLocks locks;

    /** Creates the reviewed deployment use case. / 创建经审阅部署用例。 */
    public ReviewedDeploymentUseCase(DesktopDatabase database, ReviewedDeploymentService service,
                                     DeploymentLinuxGateway gateway, ServerOperationLocks locks) {
        this.database = Objects.requireNonNull(database, "database");
        this.service = Objects.requireNonNull(service, "service");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /**
     * Deploys a reviewed request and records the exact selected health contract on success.
     *
     * <p>部署经审阅请求，并在成功后记录精确选定的健康契约。
     */
    public DeploymentResult deploy(ReviewedDeploymentRequest request, SshEndpoint endpoint,
                                   SshCredential credential, HostKeyVerifier verifier) throws SQLException {
        request = Objects.requireNonNull(request, "request");
        ManagedApplication application = resolveApplication(request.facts().applicationId(), request.server());
        ReentrantLock lock = locks.forServer(application.server().id());
        lock.lock();
        try {
            DeploymentResult result = service.deploy(request, application, gateway, endpoint, credential, verifier);
            result.finalObservation().ifPresent(observation -> {
                try {
                    database.saveLastObservation(observation);
                } catch (SQLException ignored) {
                    // Remote truth remains authoritative when local history recording fails. / 本地历史记录失败时，远端事实仍然具有权威性。
                }
            });
            if (result.status() == DeploymentStatus.SUCCEEDED) {
                database.recordSuccessfulDeployment(application,
                        new ManagedApplicationRuntimeConfiguration(request.runtime().healthCheck(), request.userAccessUrl()),
                        new CurrentRelease(application.id(), result.publishedArtifactSha256().orElseThrow(), Instant.now()));
                database.bindApplicationReleaseSecrets(application.id(), result.publishedArtifactSha256().orElseThrow(),
                        request.secretReferences());
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    private ManagedApplication resolveApplication(String applicationId, ServerIdentity server) throws SQLException {
        Optional<ManagedApplication> saved = database.findManagedApplication(applicationId);
        if (saved.isEmpty()) {
            return ManagedApplication.forManaged(applicationId, server, randomDigest());
        }
        ManagedApplication existing = saved.orElseThrow();
        if (!existing.server().equals(server)) {
            throw new LocalizedOperationException(LocalizedMessage.of("deployment.applicationServerConflict", "application", applicationId),
                    "Managed application " + applicationId + " is bound to a different server identity");
        }
        ManagedApplication canonical = ManagedApplication.forManaged(applicationId, server, existing.ownershipManifestSha256());
        if (!existing.equals(canonical)) {
            throw new LocalizedOperationException(LocalizedMessage.of("deployment.applicationIdentityInvalid", "application", applicationId),
                    "Saved managed application identity violates managed-deployment rules");
        }
        return existing;
    }

    private static String randomDigest() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
