package gold.debug.windowstolinux.mod.deployment.linux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigScopeType;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Fixed helper scalars projected from reviewed non-secret publication facts. / 从已审阅非秘密发布事实投影的固定helper标量。 */
public final class DeploymentPublishArguments {
    /** Prevents instantiation of this protocol mapping. / 禁止实例化协议映射。 */
    private DeploymentPublishArguments() {
    }

    /** Renders one immutable runtime environment in the helper's format. / 按helper格式渲染一份不可变运行环境。
     * @param snapshot original reviewed configuration / 原已审阅配置
     * @param format fixed systemd or container format / 固定systemd或容器格式
     * @return bounded UTF-8 payload / 有界UTF-8载荷 */
    public static byte[] configuration(DeploymentConfigSnapshot snapshot, String format) {
        Objects.requireNonNull(snapshot);
        if (!List.of("systemd", "container").contains(format))
            throw new IllegalArgumentException("Unsupported runtime environment format");
        Map<String, String> values = new TreeMap<>();
        snapshot.entries().stream().filter(entry -> entry.scope() == DeploymentConfigScopeType.RUNTIME)
            .forEach(entry -> values.put(entry.key(), entry.value().canonicalValue()));
        StringBuilder content = new StringBuilder();
        values.forEach((key, value) -> {
            content.append(key).append('=');
            if (format.equals("systemd"))
                content.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            else
                content.append(value);
            content.append('\n');
        });
        byte[] payload = content.toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > 65536)
            throw new IllegalArgumentException("Runtime configuration exceeds helper limit");
        return payload;
    }

    /** Frames exact immutable secret digests after staging. / 暂存后封装精确不可变秘密摘要。
     * @param configurationSha256 original configuration identity / 原配置身份
     * @param secrets exact staged revisions / 精确已暂存修订
     * @return fixed input arguments / 固定输入参数 */
    public static List<String> inputs(String configurationSha256, Map<DeploymentSecretRef, String> secrets) {
        if (configurationSha256 == null || !configurationSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Configuration digest required");
        var sorted = new TreeMap<DeploymentSecretRef, String>(
            Comparator.comparing(DeploymentSecretRef::identifier)
                .thenComparingLong(DeploymentSecretRef::revision));
        sorted.putAll(Objects.requireNonNull(secrets));
        var values = new ArrayList<String>(List.of(configurationSha256, Integer.toString(sorted.size())));
        sorted.forEach((reference, digest) -> {
            if (digest == null || !digest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Exact staged secret digest required");
            values.add(reference.identifier());
            values.add(Long.toString(reference.revision()));
            values.add(digest);
        });
        return List.copyOf(values);
    }

    /** Encodes reviewed file and SQLite bindings in the old fixed helper order. / 按原固定helper顺序编码已审阅文件与SQLite绑定。
     * @param applicationId original application / 原应用
     * @param resources reviewed resources / 已审阅资源
     * @return managed content scalar sequence / 受管内容标量序列 */
    public static List<String> content(String applicationId, DeploymentResourceBindings resources) {
        Objects.requireNonNull(resources);
        var values = new ArrayList<String>();
        values.add(applicationId);
        values.add(applicationId);
        int count = resources.fileBindings().size() + (int) resources.databaseBindings().orElse(List.of()).stream()
            .filter(binding -> binding.connection() instanceof DeploymentDatabaseConnection.Sqlite).count();
        values.add(Integer.toString(count));
        for (var binding : resources.fileBindings())
            binding(values, binding.bindingId(), binding.dataPath(), binding.resourceType(), binding.location(), "",
                binding.seedFile(), List.of(), binding.contentSha256());
        for (var database : resources.databaseBindings().orElse(List.of())) {
            if (database.connection() instanceof DeploymentDatabaseConnection.Sqlite sqlite) {
                String path = sqlite.accessPath().isEmpty()
                    ? sqlite.physicalPath(applicationId, database.databaseId())
                    : sqlite.accessPath();
                binding(values, database.databaseId(),
                    new DeploymentStoragePath(path, DeploymentStoragePath.AccessMode.READ_WRITE, "sqlite", true),
                    DeploymentStorageLocation.StorageResourceType.DATABASE, sqlite.location(), sqlite.fileName(),
                    sqlite.seedFile(), sqlite.initializationFiles(), "");
            }
        }
        return List.copyOf(values);
    }

    /** Appends the helper's exact ten scalars for one binding. / 为一项绑定追加helper的精确十个标量。
     * @param values receiving list / 接收列表
     * @param id reviewed binding / 已审阅绑定
     * @param path reviewed logical path / 已审阅逻辑路径
     * @param type resource kind / 资源类别
     * @param location reviewed location / 已审阅位置
     * @param databaseFile database filename / 数据库文件名
     * @param seed source seed / 来源种子
     * @param initialization initialization files / 初始化文件
     * @param digest immutable configuration content / 不可变配置内容 */
    private static void binding(List<String> values, String id, DeploymentStoragePath path,
                                DeploymentStorageLocation.StorageResourceType type, DeploymentStorageLocation location, String databaseFile,
                                String seed, List<String> initialization, String digest) {
        values.add(id);
        values.add(path.path());
        values.add(path.access() == DeploymentStoragePath.AccessMode.READ_ONLY ? "ro" : "rw");
        values.add(type.name());
        values.add(location.type().name());
        values.add(location.path().isEmpty() ? "-" : location.path());
        values.add(databaseFile.isEmpty() ? "-" : databaseFile);
        values.add(seed.isEmpty() ? "-" : seed);
        values.add(initialization.isEmpty() ? "-" : String.join(",", initialization));
        values.add(digest.isEmpty() ? "-" : digest);
    }
}
