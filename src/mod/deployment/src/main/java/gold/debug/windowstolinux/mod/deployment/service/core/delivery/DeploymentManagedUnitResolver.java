package gold.debug.windowstolinux.mod.deployment.service.core.delivery;

import java.security.SecureRandom;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentPublicationRepository;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentUnitIdentity;

/** Resolves canonical ownership for either deployment mode without using source or plan hashes. / 为两种部署模式解析规范归属，不使用来源或计划散列。 */
public final class DeploymentManagedUnitResolver {
    /** Existing exact business identity reader. / 既有精确业务身份读取器。 */
    private final DeploymentPublicationRepository publications;

    /** Binds the real deployment database. / 绑定真实部署数据库。
     * @param database original task database / 原任务数据库 */
    public DeploymentManagedUnitResolver(ModDatabase database) {
        publications = new DeploymentPublicationRepository(Objects.requireNonNull(database));
    }

    /** Reuses saved ownership or proposes a random first-publication manifest. / 复用已存归属，或为初次发布提出随机清单。
     * @param task original task / 原任务
     * @param unitId exact managed unit / 精确受管单元
     * @param server selected server / 所选服务器
     * @return saved or fresh unpersisted identity / 已存或新未持久身份
     * @throws Exception on conflicting or unreadable ownership / 归属冲突或不可读时 */
    public DeploymentUnitIdentity resolve(TaskContext task, String unitId, ServerRef server) throws Exception {
        var saved = publications.findUnit(task, unitId);
        if (saved.isPresent()) {
            var existing = saved.orElseThrow();
            if (!existing.server().equals(server) || !existing
                    .equals(DeploymentUnitIdentity.forManaged(unitId, server, existing.ownershipManifestSha256())))
                throw new ApiException(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_OWNER_CONFLICT",
                        "Managed unit belongs to another target or has invalid ownership", Map.of(), Optional.empty()));
            return existing;
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        try {
            return DeploymentUnitIdentity.forManaged(unitId, server, HexFormat.of().formatHex(random));
        } finally {
            Arrays.fill(random, (byte) 0);
        }
    }

    /** Derives the same bounded managed unit name for one graph component. / 为一个图组件派生同一有界受管单元名。
     * @param applicationId application identity / 应用身份
     * @param componentId component identity / 组件身份
     * @return managed unit ID / 受管单元标识 */
    public static String managedId(String applicationId, String componentId) {
        String value = applicationId + "-" + componentId;
        if (value.length() <= 63)
            return value;
        try {
            String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 8);
            return value.substring(0, 54).replaceAll("-+$", "") + "-" + digest;
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
