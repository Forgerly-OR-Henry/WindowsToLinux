package gold.debug.windowstolinux.mod.deployment.db;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretRef;

/** Versioned keys tied to exact retained deployment rows. / 绑定精确保留部署业务行的版本化键。 */
public final class DeploymentRefKey {
    /** Reserved domain. / 保留域。 */
    private static final String PREFIX = "deployment:v1:";

    /** Utility owner. / 工具类。 */
    private DeploymentRefKey() {
    }

    /** Creates an application server intent. / 创建应用服务器意图。
     * @param server original server / 原服务器
     * @param applicationId exact application ID / 精确应用ID
     * @return original association / 原关联 */
    public static DeploymentRefIntent application(ServerId server, String applicationId) {
        return DeploymentRefIntent.server(server, applicationKey(applicationId));
    }

    /** Creates a unit server intent. / 创建单元服务器意图。
     * @param server original server / 原服务器
     * @param unitId exact unit ID / 精确单元ID
     * @return original association / 原关联 */
    public static DeploymentRefIntent unit(ServerId server, String unitId) {
        return DeploymentRefIntent.server(server, PREFIX + "unit:" + id(unitId));
    }

    /** Creates an immutable secret-revision intent. / 创建不可变秘密修订意图。
     * @param secret original platform identity and purpose / 原平台身份及用途
     * @param server original task server scope / 原任务服务器作用域
     * @param revision exact durable secret revision / 精确持久秘密修订
     * @return original association / 原关联 */
    public static DeploymentRefIntent secretRevision(ServerId server, SecretId secret,
                                                     DeploymentSecretRef revision) {
        Objects.requireNonNull(revision);
        if (revision.revision() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Secret revision exceeds H2 INTEGER range");
        return DeploymentRefIntent.secret(secret, PREFIX + "secret-revision:" + serverScope(server) + ":"
            + revision.identifier() + ":" + revision.revision());
    }

    /** Derives the application key without normalizing its identity. / 不规范化身份地派生应用键。
     * @param applicationId original application / 原应用
     * @return versioned key / 版本化键 */
    public static String applicationKey(String applicationId) {
        return PREFIX + "application:" + id(applicationId);
    }

    /** Validates a retained intent against its original execution. / 按原执行核验留存意图。
     * @param intent retained original intent / 原留存意图
     * @param identity original execution / 原执行 */
    public static void requireOriginal(DeploymentRefIntent intent, DeploymentExecutionIdentity identity) {
        requireOriginal(intent, identity.applicationId(), identity.server());
    }

    /** Validates the original row key without inventing a task identity. / 不虚构任务身份地核验原行键。
     * @param intent retained intent / 留存意图
     * @param applicationId original application / 原应用
     * @param server original server / 原服务器 */
    public static void requireOriginal(DeploymentRefIntent intent, String applicationId, ServerId server) {
        Objects.requireNonNull(intent);
        Objects.requireNonNull(server);
        String key = intent.businessKey();
        if (intent.kind() == DeploymentRefIntent.DeploymentRefKind.SERVER) {
            if (!intent.resourceId().equals(server.id()))
                throw new IllegalArgumentException("Reference belongs to another server");
            if (key.equals(applicationKey(applicationId)))
                return;
            if (key.startsWith(PREFIX + "unit:")
                && key.equals(unit(server, key.substring((PREFIX + "unit:").length())).businessKey()))
                return;
        } else if (key.startsWith(PREFIX + "secret-revision:")) {
            var suffix = key.substring((PREFIX + "secret-revision:").length());
            int scopeEnd = suffix.indexOf(':');
            int separator = suffix.lastIndexOf(':');
            if (scopeEnd > 0 && separator > scopeEnd) {
                try {
                    var revision = new DeploymentSecretRef(suffix.substring(scopeEnd + 1, separator),
                        Long.parseLong(suffix.substring(separator + 1)));
                    if (revision.revision() <= Integer.MAX_VALUE && key.equals(
                        secretRevision(server, new SecretId(intent.resourceId(), intent.purpose().orElseThrow()),
                            revision).businessKey()))
                        return;
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("Unsupported secret reference key", invalid);
                }
            }
        }
        throw new IllegalArgumentException("Unsupported deployment reference key");
    }

    /** Reads a supported secret revision key after exact validation. / 精确校验后读取受支持的秘密修订键。
     * @param intent original intent / 原意图
     * @return original secret revision / 原秘密修订 */
    static DeploymentSecretRef revision(DeploymentRefIntent intent) {
        var suffix = intent.businessKey().substring((PREFIX + "secret-revision:").length());
        int scopeEnd = suffix.indexOf(':');
        int separator = suffix.lastIndexOf(':');
        return new DeploymentSecretRef(suffix.substring(scopeEnd + 1, separator),
            Long.parseLong(suffix.substring(separator + 1)));
    }

    /** Derives a fixed-size exact server scope for a cross-server shared credential. / 为跨服务器共享凭据派生固定长度原服务器作用域。
     * @param server original platform server / 原平台服务器
     * @return stable lowercase SHA-256 / 稳定小写SHA-256 */
    private static String serverScope(ServerId server) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : new String[]{"deployment-secret-server-v1", Objects.requireNonNull(server).id()}) {
                for (int index = 0; index < value.length(); index++) {
                    digest.update((byte) (value.charAt(index) >>> 8));
                    digest.update((byte) value.charAt(index));
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", impossible);
        }
    }

    /** Validates the existing bounded exact business ID syntax. / 校验既有有界精确业务ID语法。
     * @param value original ID / 原ID
     * @return unchanged ID / 未改动ID */
    private static String id(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("Invalid deployment business identity");
        return value;
    }
}
