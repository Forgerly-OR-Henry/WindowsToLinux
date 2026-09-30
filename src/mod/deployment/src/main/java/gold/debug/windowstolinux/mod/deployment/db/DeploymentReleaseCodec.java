package gold.debug.windowstolinux.mod.deployment.db;

import static gold.debug.windowstolinux.mod.deployment.db.DeploymentJsonDocument.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Versioned complete release manifests using the existing binary runtime formats. / 使用既有二进制运行格式的版本化完整发布清单。 */
final class DeploymentReleaseCodec {
    /** Whole-application JSON bound, independent of each binary payload's bound. / 整应用JSON上限，独立于各二进制载荷上限。 */
    static final int LIMIT = 16 * 1_048_576;

    /** Prevents construction of the fixed codec. / 防止构造固定编解码器。 */
    private DeploymentReleaseCodec() {
    }

    /** Encodes stable component order and original immutable bindings. / 编码稳定组件顺序及原不可变绑定。
     * @param snapshot complete verified manifest / 完整已验证清单
     * @return original bounded runtime document / 原有界运行文档
     * @throws SQLException if encoding fails or exceeds bounds / 编码失败或超限时 */
    static String runtime(DeploymentReleaseSnapshot snapshot) throws SQLException {
        var node = object().put("formatVersion", 1).put("applicationId", snapshot.graph().applicationId());
        var components = node.putArray("components");
        try {
            for (var component : snapshot.graph().components()) {
                var unit = component.unit();
                var binding = snapshot.bindings().stream().filter(b -> b.unitId().equals(unit.id())).findFirst()
                        .orElseThrow();
                var entry = components.addObject().put("componentId", component.componentId()).put("unitId", unit.id())
                        .put("serverId", unit.server().id()).put("systemdUnit", unit.systemdUnit())
                        .put("releaseRoot", unit.releaseRoot())
                        .put("ownershipManifestSha256", unit.ownershipManifestSha256())
                        .put("runtime", base64(new DeploymentUnitRuntimeCodec().write(component.runtime())))
                        .put("reviewedRuntime", base64(new DeploymentRuntimeCodec().write(component.reviewedRuntime())))
                        .put("dataPaths", base64(new DeploymentStoragePathCodec().write(component.dataPaths())))
                        .put("resources", base64(new DeploymentResourceCodec().write(component.resources())))
                        .put("releaseSha256", binding.releaseSha256())
                        .put("configurationRevision", binding.configurationRevision())
                        .put("configurationSha256", binding.configurationSha256());
                var dependencies = entry.putArray("dependencies");
                component.dependencies().forEach(dependencies::add);
                var secrets = entry.putArray("secrets");
                binding.secrets().forEach(
                        s -> secrets.addObject().put("identifier", s.identifier()).put("revision", s.revision()));
            }
            return encode(node, LIMIT);
        } catch (IOException invalid) {
            throw new SQLException("Release runtime cannot be encoded", invalid);
        }
    }

    /** Encodes the independent whole-application health contract. / 编码独立的整应用健康契约。
     * @param snapshot complete original manifest / 完整原清单
     * @return bounded health document / 有界健康文档
     * @throws SQLException if health encoding fails / 健康编码失败时 */
    static String health(DeploymentReleaseSnapshot snapshot) throws SQLException {
        var graph = snapshot.graph();
        try {
            return encode(object().put("formatVersion", 1).put("applicationId", graph.applicationId())
                    .put("healthComponentId", graph.healthComponentId())
                    .put("health", base64(new DeploymentHealthCodec().write(graph.healthCheck()))));
        } catch (IOException invalid) {
            throw new SQLException("Release health cannot be encoded", invalid);
        }
    }

    /** Hashes both original documents with a distinct domain and unambiguous byte lengths. / 使用独立域和无歧义字节长度对两份原文档求摘要。
     * @param runtime original runtime JSON / 原运行JSON
     * @param health original health JSON / 原健康JSON
     * @return lowercase full-manifest SHA-256 / 小写完整清单SHA-256 */
    static String digest(String runtime, String health) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of("windowstolinux-application-release-v1", runtime, health)) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Required SHA-256 is unavailable", unavailable);
        }
    }

    /** Checks versions without interpreting unknown documents. / 检查版本，不解释未知文档。
     * @param record original history / 原历史
     * @return whether both formats are supported / 是否支持两种格式
     * @throws SQLException if JSON is malformed / JSON无效时 */
    static boolean supported(DeploymentReleaseRecord record) throws SQLException {
        int runtime = parse(record.runtimeJson(), LIMIT).path("formatVersion").intValue();
        int health = parse(record.healthJson()).path("formatVersion").intValue();
        return runtime == 1 && health == 1;
    }

    /** Reconstructs the complete original graph, never the current application's replacement. / 重建完整原图，绝不使用当前应用替代。
     * @param record original retained history / 原保留历史
     * @return validated immutable original snapshot / 已校验不可变原快照
     * @throws SQLException if unsupported, corrupt or inconsistent / 不支持、损坏或不一致时 */
    static DeploymentReleaseSnapshot snapshot(DeploymentReleaseRecord record) throws SQLException {
        if (!supported(record))
            throw new SQLException("Release format requires a matching reader", "0A000");
        if (!record.identity().sha256().equals(digest(record.runtimeJson(), record.healthJson())))
            throw new SQLException("Release manifest digest differs from original identity");
        var runtime = parse(record.runtimeJson(), LIMIT);
        var health = parse(record.healthJson());
        fields(runtime, Set.of("formatVersion", "applicationId", "components"));
        fields(health, Set.of("formatVersion", "applicationId", "healthComponentId", "health"));
        if (!string(runtime, "applicationId").equals(record.applicationId())
                || !string(health, "applicationId").equals(record.applicationId()))
            throw new SQLException("Release documents belong to another application");
        try {
            var components = new ArrayList<DeploymentApplicationGraph.Component>();
            var bindings = new ArrayList<DeploymentReleaseSnapshot.Binding>();
            for (var entry : array(runtime, "components", 64)) {
                components.add(component(entry));
                bindings.add(binding(entry));
            }
            var graph = new DeploymentApplicationGraph(record.applicationId(), string(health, "healthComponentId"),
                    new DeploymentHealthCodec().read(binary(health, "health", 65_536)), components);
            var snapshot = new DeploymentReleaseSnapshot(graph, bindings);
            if (!runtime(snapshot).equals(record.runtimeJson()) || !health(snapshot).equals(record.healthJson()))
                throw new SQLException("Release manifest is not in its canonical format");
            return snapshot;
        } catch (IOException | IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Release snapshot is invalid", invalid);
        }
    }

    /** Decodes one exact component using unchanged binary codecs. / 使用未变更的二进制编解码器解码一个精确组件。
     * @param entry original component node / 原组件节点
     * @return original component / 原组件
     * @throws SQLException if fields are invalid / 字段无效时
     * @throws IOException if binary formats are invalid / 二进制格式无效时 */
    private static DeploymentApplicationGraph.Component component(JsonNode entry) throws SQLException, IOException {
        fields(entry,
                Set.of("componentId", "unitId", "serverId", "systemdUnit", "releaseRoot", "ownershipManifestSha256",
                        "runtime", "reviewedRuntime", "dataPaths", "resources", "dependencies", "releaseSha256",
                        "configurationRevision", "configurationSha256", "secrets"));
        var dependencies = new ArrayList<String>();
        for (var dependency : array(entry, "dependencies", 64)) {
            if (!dependency.isTextual())
                throw new SQLException("Release dependency is not textual");
            dependencies.add(dependency.textValue());
        }
        var unit = new DeploymentUnitIdentity(string(entry, "unitId"), new ServerRef(string(entry, "serverId")),
                string(entry, "systemdUnit"), string(entry, "releaseRoot"), string(entry, "ownershipManifestSha256"));
        var runtime = new DeploymentUnitRuntimeCodec().read(binary(entry, "runtime", 1_048_576));
        return new DeploymentApplicationGraph.Component(string(entry, "componentId"), unit, runtime, dependencies,
                new DeploymentRuntimeCodec().read(binary(entry, "reviewedRuntime", 1_048_576), runtime.healthCheck()),
                new DeploymentStoragePathCodec().read(binary(entry, "dataPaths", 1_048_576)),
                new DeploymentResourceCodec().read(binary(entry, "resources", 1_048_576)));
    }

    /** Decodes the complete immutable configuration and secret references. / 解码完整不可变配置和秘密引用。
     * @param entry original component node / 原组件节点
     * @return original content bindings / 原内容绑定
     * @throws SQLException if a field is invalid / 字段无效时 */
    private static DeploymentReleaseSnapshot.Binding binding(JsonNode entry) throws SQLException {
        if (!entry.path("configurationRevision").isInt())
            throw new SQLException("Release configuration revision is invalid");
        var secrets = new ArrayList<DeploymentSecretReference>();
        for (var secret : array(entry, "secrets", LIMIT)) {
            fields(secret, Set.of("identifier", "revision"));
            if (!secret.path("revision").isInt())
                throw new SQLException("Release secret revision is invalid");
            secrets.add(
                    new DeploymentSecretReference(string(secret, "identifier"), secret.path("revision").intValue()));
        }
        return new DeploymentReleaseSnapshot.Binding(string(entry, "unitId"), string(entry, "releaseSha256"),
                entry.path("configurationRevision").intValue(), string(entry, "configurationSha256"), secrets);
    }

    /** Encodes original bytes without changing their format. / 编码原字节，不改变其格式。
     * @param bytes original codec bytes / 原编解码字节
     * @return canonical Base64 / 规范Base64 */
    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Reads canonical bounded binary content without permissive Base64 aliases. / 读取规范有界二进制内容，不接受宽松Base64别名。
     * @param node original node / 原节点
     * @param field fixed field / 固定字段
     * @param limit binary byte bound / 二进制字节上限
     * @return original bytes / 原字节
     * @throws SQLException if size or encoding is invalid / 大小或编码无效时 */
    private static byte[] binary(JsonNode node, String field, int limit) throws SQLException {
        String value = string(node, field);
        if (value.length() > ((limit + 2) / 3) * 4)
            throw new SQLException("Release binary exceeds its bound");
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length > limit || !base64(bytes).equals(value))
                throw new IllegalArgumentException();
            return bytes;
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Release binary is invalid", invalid);
        }
    }
}
