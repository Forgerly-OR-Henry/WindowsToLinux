package gold.debug.windowstolinux.mod.deployment.db.execution;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.Comparator;
import java.util.HexFormat;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentHealthCodec;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentRuntimeCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseConnection;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentAccessUrl;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement;

/** Stable pre-execution review identity, distinct from the eventual release manifest identity. / 稳定的执行前审阅身份，区别于最终发布清单身份。 */
public final class DeploymentReviewedPlanDigest {

    /** Complete non-authorizing hash inputs shared by live and persisted reviews. / 活动及持久审阅共用的完整非授权哈希输入。
     * @param serverId original server / 原服务器
     * @param snapshotId original source snapshot / 原来源快照
     * @param manifestSha256 original source manifest / 原来源清单
     * @param archiveSha256 original archive / 原归档
     * @param byteCount original compressed bytes / 原压缩字节
     * @param uncompressedByteCount original uncompressed bytes / 原未压缩字节
     * @param facts exact original hash facts / 精确原哈希事实
     * @param requirements original typed tool declarations / 原类型化工具声明
     * @param configuration full original configuration / 完整原配置
     * @param secretReferences original secret revisions / 原秘密修订
     * @param resources original resources / 原资源
     * @param runtime complete reviewed runtime / 完整审阅运行定义
     * @param userAccessUrl original URL / 原URL
     * @param limits original limits / 原限额
     * @param confirmedAt exact original review time / 精确原审阅时刻
     * @param containerRisk original container choice / 原容器选择
     * @param adapterRisk original adapter choice / 原适配器选择 */
    public record Values(String serverId, UUID snapshotId, String manifestSha256, String archiveSha256,
                         long byteCount, long uncompressedByteCount, Map<String, String> facts,
                         List<ToolchainRequirement> requirements,
                         DeploymentConfigSnapshot configuration,
                         List<DeploymentSecretRef> secretReferences,
                         DeploymentResourceBindings resources, DeploymentRuntimeDefinition runtime,
                         Optional<DeploymentAccessUrl> userAccessUrl,
                         DeploymentBuildLimits limits, Instant confirmedAt, boolean containerRisk,
                         boolean adapterRisk) {
        /** Copies complete exact hash inputs without creating source authority. / 复制完整精确哈希输入，不创建来源权限。
         * @param serverId original server / 原服务器
         * @param snapshotId original snapshot / 原快照
         * @param manifestSha256 original manifest / 原清单
         * @param archiveSha256 original archive / 原归档
         * @param byteCount compressed bytes / 压缩字节
         * @param uncompressedByteCount uncompressed bytes / 未压缩字节
         * @param facts original hash facts / 原哈希事实
         * @param requirements original declarations / 原声明
         * @param configuration full configuration / 完整配置
         * @param secretReferences original revisions / 原修订
         * @param resources original resources / 原资源
         * @param runtime complete runtime / 完整运行定义
         * @param userAccessUrl original URL / 原URL
         * @param limits original limits / 原限额
         * @param confirmedAt exact review time / 精确审阅时刻
         * @param containerRisk container choice / 容器选择
         * @param adapterRisk adapter choice / 适配器选择 */
        public Values {
            facts = Map.copyOf(facts);
            requirements = List.copyOf(requirements);
            secretReferences = List.copyOf(secretReferences);
            Objects.requireNonNull(snapshotId);
            Objects.requireNonNull(configuration);
            Objects.requireNonNull(resources);
            Objects.requireNonNull(runtime);
            Objects.requireNonNull(userAccessUrl);
            Objects.requireNonNull(limits);
            Objects.requireNonNull(confirmedAt);
            if (!facts.keySet().equals(Set.of("applicationId", "projectType", "buildTool", "buildDirectory", "support", "language", "architecture", "framework")))
                throw new IllegalArgumentException("Incomplete reviewed hash facts");
        }
    }

    /** No instances are required. / 无需实例。 */
    private DeploymentReviewedPlanDigest() {
    }

    /** Complete original graph framing, without source or execution authority. / 完整原图分界，不含来源或执行权限。
     * @param applicationId original application / 原应用
     * @param healthOwner original whole-health owner / 原整体健康归属
     * @param health exact independent probe / 精确独立探测
     * @param order original topological order / 原拓扑顺序
     * @param namespaces exact component units / 精确组件单元
     * @param dependencies exact ordered predecessor lists / 精确有序前驱列表
     * @param identities complete child review digests / 完整子审阅摘要 */
    public record GraphValues(String applicationId, String healthOwner, DeploymentHealthCheck health,
                              List<String> order, Map<String, String> namespaces,
                              Map<String, List<String>> dependencies,
                              Map<String, String> identities) {
        /** Copies and checks one whole original graph. / 复制并核对一个完整原图。
         * @param applicationId original application / 原应用
         * @param healthOwner original health owner / 原健康归属
         * @param health exact probe / 精确探测
         * @param order original order / 原顺序
         * @param namespaces original units / 原单元
         * @param dependencies original dependencies / 原依赖
         * @param identities original child digests / 原子摘要 */
        public GraphValues {
            Objects.requireNonNull(health);
            order = List.copyOf(order);
            namespaces = Map.copyOf(namespaces);
            identities = Map.copyOf(identities);
            var copied = new LinkedHashMap<String, List<String>>();
            dependencies.forEach((key, value) -> copied.put(key, List.copyOf(value)));
            dependencies = Map.copyOf(copied);
            var ids = new HashSet<>(order);
            if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}") || order.size() < 2 || order.size() > 16
                || ids.size() != order.size() || !ids.contains(healthOwner) || !ids.equals(namespaces.keySet())
                || !ids.equals(dependencies.keySet()) || !ids.equals(identities.keySet())
                || new HashSet<>(namespaces.values()).size() != order.size())
                throw new IllegalArgumentException("Incomplete reviewed graph identity");
            var visited = new HashSet<String>();
            for (var id : order) {
                var prior = dependencies.get(id);
                if (!id.matches("[a-z0-9][a-z0-9-]{0,62}") || !namespaces.get(id).matches("[a-z0-9][a-z0-9-]{0,62}")
                    || !identities.get(id).matches("[0-9a-f]{64}") || new HashSet<>(prior).size() != prior.size()
                    || !visited.containsAll(prior))
                    throw new IllegalArgumentException("Invalid reviewed graph child binding");
                visited.add(id);
            }
        }
    }

    /** Uses the original graph writer byte for byte for live and durable descriptions. / 对活动和持久描述逐字节使用原图写入器。
     * @param value complete original graph / 完整原图
     * @return original lowercase graph SHA / 原小写图SHA */
    public static String graphFrom(GraphValues value) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeUTF("deployment-reviewed-graph-v1");
                output.writeUTF(value.applicationId());
                output.writeUTF(value.healthOwner());
                var probe = new DeploymentHealthCodec().write(value.health());
                output.writeInt(probe.length);
                output.write(probe);
                output.writeInt(value.order().size());
                for (var id : value.order()) {
                    output.writeUTF(id);
                    output.writeUTF(value.namespaces().get(id));
                    output.writeUTF(value.identities().get(id));
                    var prior = value.dependencies().get(id);
                    output.writeInt(prior.size());
                    for (var dependency : prior) output.writeUTF(dependency);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Reviewed graph identity cannot be encoded", failure);
        }
    }

    /** Hashes all reviewed inputs with explicit framing and the new plan domain. / 使用明确分界及新计划域散列全部已审阅输入。
     * @param value complete reviewed input / 完整审阅输入
     * @return lower-case SHA-256 plan identity / 小写SHA-256计划身份 */
    public static String from(Values value) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                text(out, "deployment-reviewed-plan-v1");
                text(out, value.serverId());
                text(out, value.snapshotId().toString());
                text(out, value.manifestSha256());
                text(out, value.archiveSha256());
                out.writeLong(value.byteCount());
                out.writeLong(value.uncompressedByteCount());
                facts(out, value);
                text(out, value.configuration().applicationId());
                out.writeLong(value.configuration().revision());
                text(out, value.configuration().schemaVersion());
                text(out, value.configuration().sha256());
                var secrets = value.secretReferences().stream()
                    .sorted(Comparator.comparing(DeploymentSecretRef::identifier)
                        .thenComparingLong(DeploymentSecretRef::revision))
                    .toList();
                out.writeInt(secrets.size());
                for (var secret : secrets) {
                    text(out, secret.identifier());
                    out.writeLong(secret.revision());
                }
                resources(out, value.resources());
                binary(out, new DeploymentRuntimeCodec().write(value.runtime()));
                binary(out, new DeploymentHealthCodec().write(value.runtime().healthCheck()));
                out.writeBoolean(value.userAccessUrl().isPresent());
                if (value.userAccessUrl().isPresent())
                    text(out, value.userAccessUrl().orElseThrow().url().toString());
                limits(out, value.limits());
                text(out, value.confirmedAt().toString());
                out.writeBoolean(value.containerRisk());
                out.writeBoolean(value.adapterRisk());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Reviewed plan identity cannot be encoded", failure);
        }
    }

    /** Encodes selected architecture and source toolchain declarations without a local source path. / 编码所选架构及源码工具链声明，不包含本机源码路径。
     * @param out framed output / 分界输出
     * @param value reviewed input / 审阅输入
     * @throws IOException when encoding fails / 编码失败时 */
    public static String from(Values value, DeploymentRemoteLayout layout) {
        Objects.requireNonNull(layout);
        String original = from(value);
        if (layout.equals(DeploymentRemoteLayout.original())) return original;
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                text(out, "deployment-reviewed-plan-layout-v2");
                text(out, original);
                text(out, layout.canonical());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Reviewed layout identity cannot be encoded", failure);
        }
    }

    private static void facts(DataOutputStream out, Values value) throws IOException {
        var facts = value.facts();
        text(out, facts.get("applicationId"));
        text(out, facts.get("projectType"));
        text(out, facts.get("buildTool"));
        text(out, facts.get("buildDirectory"));
        text(out, facts.get("support"));
        text(out, facts.get("language"));
        text(out, facts.get("architecture"));
        text(out, facts.get("framework"));
        out.writeInt(value.requirements().size());
        for (var requirement : value.requirements()) {
            text(out, requirement.ecosystem().name());
            text(out, requirement.declaration());
            text(out, requirement.source());
            text(out, requirement.purpose().name());
            text(out, requirement.constraint().name());
            out.writeBoolean(requirement.version().isPresent());
            if (requirement.version().isPresent())
                text(out, requirement.version().orElseThrow().text());
        }
    }

    /** Encodes exact sorted file and database bindings, preserving unreviewed versus reviewed-empty. / 编码精确排序的文件和数据库绑定，并保留未审阅与审阅为空的区别。
     * @param out framed output / 分界输出
     * @param bindings reviewed bindings / 已审阅绑定
     * @throws IOException when encoding fails / 编码失败时 */
    private static void resources(DataOutputStream out, DeploymentResourceBindings bindings) throws IOException {
        out.writeInt(bindings.fileBindings().size());
        for (var file : bindings.fileBindings()) {
            text(out, file.bindingId());
            text(out, file.dataPath().path());
            text(out, file.dataPath().access().name());
            text(out, file.dataPath().schemaId());
            out.writeBoolean(file.dataPath().reversible());
            text(out, file.location().type().name());
            text(out, file.location().path());
            text(out, file.resourceType().name());
            text(out, file.seedFile());
            text(out, file.contentSha256());
        }
        out.writeBoolean(bindings.databaseBindings().isPresent());
        if (bindings.databaseBindings().isEmpty())
            return;
        var databases = bindings.databaseBindings().orElseThrow();
        out.writeInt(databases.size());
        for (var database : databases) {
            text(out, database.databaseId());
            text(out, database.connection().engine().name());
            switch (database.connection()) {
                case DeploymentDatabaseConnection.Sqlite sqlite -> {
                    text(out, sqlite.fileName());
                    text(out, sqlite.location().type().name());
                    text(out, sqlite.location().path());
                    text(out, sqlite.accessPath());
                    text(out, sqlite.seedFile());
                    out.writeInt(sqlite.initializationFiles().size());
                    for (var file : sqlite.initializationFiles())
                        text(out, file);
                }
                case DeploymentDatabaseConnection.Server server -> {
                    text(out, server.host());
                    out.writeInt(server.port());
                    text(out, server.database());
                    text(out, server.username());
                    text(out, server.passwordReference().identifier());
                    out.writeLong(server.passwordReference().revision());
                    out.writeBoolean(server.tlsRequired());
                }
            }
        }
    }

    /** Encodes all enforced build limits. / 编码全部强制构建限制。
     * @param out framed output / 分界输出
     * @param limits reviewed limits / 已审阅限制
     * @throws IOException when encoding fails / 编码失败时 */
    private static void limits(DataOutputStream out, DeploymentBuildLimits limits) throws IOException {
        out.writeInt(limits.timeoutSeconds());
        out.writeInt(limits.maxProcesses());
        out.writeInt(limits.maxMemoryMiB());
        out.writeLong(limits.maxOutputBytes());
        out.writeLong(limits.maxWorkspaceBytes());
        out.writeLong(limits.hostReserveBytes());
        out.writeBoolean(limits.runAsRoot());
    }

    /** Writes one length-prefixed UTF-8 field. / 写入一个带长度前缀的UTF-8字段。
     * @param out framed output / 分界输出
     * @param value exact field / 精确字段
     * @throws IOException when encoding fails / 编码失败时 */
    private static void text(DataOutputStream out, String value) throws IOException {
        binary(out, value.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes one length-prefixed binary field. / 写入一个带长度前缀的二进制字段。
     * @param out framed output / 分界输出
     * @param value exact bytes / 精确字节
     * @throws IOException when encoding fails / 编码失败时 */
    private static void binary(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }
}

