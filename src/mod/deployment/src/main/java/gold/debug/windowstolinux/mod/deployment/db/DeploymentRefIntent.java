package gold.debug.windowstolinux.mod.deployment.db;

import java.util.*;

import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.server.model.ServerId;

/** Non-secret facts persisted before a platform reference reservation. / 平台引用预留前保存的非秘密事实。
 * @param kind platform reference kind / 平台引用类别
 * @param resourceId original opaque resource ID / 原不透明资源ID
 * @param purpose original purpose for a secret only / 仅秘密的原用途
 * @param businessKey exact business association key / 精确业务关联键 */
public record DeploymentRefIntent(DeploymentRefKind kind, String resourceId, Optional<String> purpose,
                                  String businessKey) {
    /** Validates original metadata without deriving or normalizing identities. / 校验原元数据，不派生或规范化身份。
     * @param kind platform reference kind / 平台引用类别
     * @param resourceId original opaque resource ID / 原不透明资源ID
     * @param purpose original purpose for a secret only / 仅秘密的原用途
     * @param businessKey exact business association key / 精确业务关联键 */
    public DeploymentRefIntent {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(purpose);
        for (String value : List.of(resourceId, businessKey))
            if (value.isBlank() || value.length() > 512 || value.indexOf(0) >= 0)
                throw new IllegalArgumentException("Invalid reference metadata");
        if ((kind == DeploymentRefKind.SECRET) != purpose.isPresent()
            || purpose.filter(p -> p.isBlank() || p.length() > 128 || p.indexOf(0) >= 0).isPresent())
            throw new IllegalArgumentException("Invalid reference purpose");
    }

    /** Captures a server association before reserving it. / 预留前捕获服务器关联。
     * @param server original server / 原服务器
     * @param businessKey original association key / 原关联键
     * @return exact persisted intent / 精确持久意图 */
    public static DeploymentRefIntent server(ServerId server, String businessKey) {
        return new DeploymentRefIntent(DeploymentRefKind.SERVER, server.id(), Optional.empty(),
            businessKey);
    }

    /** Captures an already-created secret identity and purpose. / 捕获已创建秘密的身份和用途。
     * @param secret original opaque secret / 原不透明秘密
     * @param businessKey original association key / 原关联键
     * @return exact persisted intent / 精确持久意图 */
    public static DeploymentRefIntent secret(SecretId secret, String businessKey) {
        return new DeploymentRefIntent(DeploymentRefKind.SECRET, secret.id(), Optional.of(secret.purpose()),
            businessKey);
    }

    /** Platform resources supported by the reference protocol. / 引用协议支持的平台资源。 */
    public enum DeploymentRefKind {
        /** Shared server configuration. / 共享服务器配置。 */
        SERVER,
        /** Owned secret identity. / 所属秘密身份。 */
        SECRET
    }
}
