package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.app.db.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.HexFormat;

/** Resolves one canonical desktop-managed identity without deployment-specific behavior. / 解析一个规范桌面受管身份，不包含部署专属行为。 */
final class ManagedApplicationIdentity {
    private ManagedApplicationIdentity() { }

    /** Returns the existing canonical identity or a new unpersisted identity. / 返回现有规范身份或新的未持久化身份。 */
    static ManagedApplication resolve(ManagedApplicationRepository applications, String applicationId,
                                      ServerIdentity server) throws SQLException {
        var saved = applications.find(applicationId);
        if (saved.isEmpty()) return ManagedApplication.forManaged(applicationId, server, randomDigest());
        ManagedApplication existing = saved.orElseThrow();
        if (!existing.server().equals(server)) {
            throw new LocalizedOperationException(LocalizedMessage.of(
                    "deployment.applicationServerConflict", "application", applicationId),
                    "Managed application " + applicationId + " is bound to a different server identity");
        }
        ManagedApplication canonical = ManagedApplication.forManaged(
                applicationId, server, existing.ownershipManifestSha256());
        if (!existing.equals(canonical)) {
            throw new LocalizedOperationException(LocalizedMessage.of(
                    "deployment.applicationIdentityInvalid", "application", applicationId),
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
