package gold.debug.windowstolinux.app.service.deployment;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.HexFormat;

import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

/**
 * Resolves one canonical desktop-managed identity without deployment-specific behavior. / 解析一个规范桌面受管身份，不包含部署专属行为。
 */
final class ManagedApplicationIdentityResolver {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ManagedApplicationIdentityResolver() {
    }

    /**
     * Returns the existing canonical identity or a new unpersisted identity. / 返回现有规范身份或新的未持久化身份。
     *
     * @param applications applications / 应用集合
     * @param applicationId managed application identifier / 受管应用标识
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @return the existing canonical identity or a new unpersisted identity / 现有规范身份或新的未持久化身份
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    static ManagedApplication resolve(ManagedApplicationRepository applications, String applicationId,
            ServerIdentity server) throws SQLException {
        var saved = applications.find(applicationId);
        if (saved.isEmpty())
            return ManagedApplication.forManaged(applicationId, server, randomDigest());
        ManagedApplication existing = saved.orElseThrow();
        if (!existing.server().equals(server)) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.APPLICATION_SERVER_CONFLICT,
                    java.util.Map.of("application", applicationId),
                    "Managed application " + applicationId + " is bound to a different server identity");
        }
        ManagedApplication canonical = ManagedApplication.forManaged(applicationId, server,
                existing.ownershipManifestSha256());
        if (!existing.equals(canonical)) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.APPLICATION_IDENTITY_INVALID,
                    java.util.Map.of("application", applicationId),
                    "Saved managed application identity violates managed-deployment rules");
        }
        return existing;
    }

    /**
     * Returns random digest.
     * <p>返回随机摘要。
     *
     * @return random digest / 随机摘要
     */
    private static String randomDigest() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
