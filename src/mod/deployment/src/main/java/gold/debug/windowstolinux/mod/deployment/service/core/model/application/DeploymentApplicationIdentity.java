package gold.debug.windowstolinux.mod.deployment.service.core.model.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.mod.deployment.api.DeploymentBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseConnection;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseEngineType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStoragePath;

/** Canonical pure application identities and bindings. / 规范纯应用身份及绑定。 */
public final class DeploymentApplicationIdentity {
    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentApplicationIdentity() {
    }

    /** Canonicalizes application resource bindings without changing their identities. / 规范化应用资源绑定，不改变身份。
     * @param graph complete reviewed application graph / 完整已审阅应用图
     * @return complete canonical resource binding set / 完整规范资源绑定集合 */
    public static List<DeploymentBinding> bindings(DeploymentApplicationGraph graph) {
        var values = new ArrayList<DeploymentBinding>();
        for (var component : graph.components()) {
            for (var file : component.resources().fileBindings())
                values.add(new DeploymentBinding(component.componentId() + ":" + file.bindingId(),
                    component.componentId(),
                    file.resourceType() == DeploymentStorageLocation.StorageResourceType.CONFIGURATION
                        ? "CONFIG"
                        : "FILE",
                    file.location().resolve(component.unit().id(), file.resourceType(), file.bindingId(), component.unit().layout()), "",
                    file.dataPath().access() == DeploymentStoragePath.AccessMode.READ_WRITE, Optional.empty()));
            for (var database : component.resources().databaseBindings().orElse(List.of())) {
                var connection = database.connection();
                String path = connection instanceof DeploymentDatabaseConnection.Sqlite sqlite
                    ? sqlite.location().resolve(component.unit().id(), DeploymentStorageLocation.StorageResourceType.DATABASE,
                        database.databaseId(), component.unit().layout()) + "/" + sqlite.fileName()
                    : connection instanceof DeploymentDatabaseConnection.Server remote
                    ? remote.engine().name().toLowerCase(java.util.Locale.ROOT) + "://" + remote.host()
                    + ":" + remote.port() + "/" + remote.database()
                    : throwUnsupportedDatabase(connection);
                Optional<String> secret = connection instanceof DeploymentDatabaseConnection.Server remote
                    ? Optional.of(remote.passwordReference().identifier())
                    : Optional.empty();
                values.add(new DeploymentBinding(component.componentId() + ":" + database.databaseId(),
                    component.componentId(), "DATABASE", path, connection.engine().name(),
                    connection.engine() == DeploymentDatabaseEngineType.MYSQL
                        || connection.engine() == DeploymentDatabaseEngineType.MARIADB,
                    secret));
            }
        }
        return values.stream()
            .sorted(Comparator.comparing(DeploymentBinding::componentId).thenComparing(DeploymentBinding::id))
            .toList();
    }

    /** Rejects unsupported database facts before application effects. / 在应用效果发生前拒绝不支持的数据库事实。
     * @param connection unsupported typed database connection / 不支持的类型化数据库连接
     * @return no result because the connection type is unsupported / 连接类型不支持而无结果 */
    private static String throwUnsupportedDatabase(DeploymentDatabaseConnection connection) {
        throw new IllegalArgumentException("Unsupported database connection: " + connection.getClass().getSimpleName());
    }

    /** Validates exact application ownership over resources and unit facts. / 校验应用对资源及单元事实的精确归属。
     * @param graph complete reviewed application graph / 完整已审阅应用图
     * @return verified exact application ownership / 已核实精确应用归属 */
    public static String ownership(DeploymentApplicationGraph graph) {
        var text = new StringBuilder("deployment-ownership-v1\n");
        for (var component : graph.components())
            text.append(component.componentId()).append('\n').append(component.unit().id()).append('\n')
                .append(component.unit().ownershipManifestSha256()).append('\n');
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Computes the canonical SHA256 digest for the original evidence bytes. / 为原依据字节计算规范 SHA256 摘要。
     * @param value caller-owned value validated by this contract / 当前合同校验的调用方所属值
     * @return canonical lowercase SHA256 / 规范小写 SHA256 */
    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }
}
