package gold.debug.windowstolinux.mod.deployment.db;

import static gold.debug.windowstolinux.mod.deployment.db.DeploymentJsonDocument.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.service.core.config.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentCurrentRelease;

/** Encodes a complete non-secret original business template before any remote publish. / 任一远端发布前编码完整非秘密原业务模板。 */
final class DeploymentPublishTemplateCodec {
    /** Retains the original one-unit document bound. / 保留原单单元文档上限。 */
    static final int SINGLE_LIMIT = 32 * 1_048_576;

    /** Covers a 16 MiB graph and at most sixteen admitted build/runtime configurations with JSON escaping. / 容纳16MiB图及至多十六份已准入构建和运行配置的JSON转义。 */
    static final int LIMIT = 128 * 1_048_576;

    /** Prevents utility construction. / 防止构造工具类。 */
    private DeploymentPublishTemplateCodec() {
    }

    /** Freezes the exact graph, configuration and publication time without secret plaintext. / 冻结精确图、配置和发布时间，不包含秘密明文。
     * @param publication original proposed business result / 原拟提交业务结果
     * @return bounded canonical JSON / 有界规范JSON
     * @throws SQLException if the template exceeds the supported business bounds / 模板超过受支持业务边界时 */
    static String encodeTemplate(DeploymentPublishProposal publication) throws SQLException {
        int count = publication.units().size();
        if (count < 1 || count > 16 || publication.graph().components().size() != count)
            throw new SQLException("Publish template requires one complete executable graph");
        var snapshot = DeploymentReleaseSnapshot.capture(publication.graph(), publication.units());
        var node = object().put("formatVersion", count == 1 ? 1 : 2).put("applicationId",
            publication.graph().applicationId());
        if (count == 1)
            node.put("publishedAt", publication.units().getFirst().release().publishedAt().toEpochMilli());
        node.set("runtime", parse(DeploymentReleaseCodec.runtime(snapshot), DeploymentReleaseCodec.LIMIT));
        node.set("health", parse(DeploymentReleaseCodec.health(snapshot)));
        if (count == 1) {
            var unit = publication.units().getFirst();
            configuration(node.putObject("configuration"), unit.configuration());
        } else {
            var units = node.putArray("units");
            for (var unit : publication.units().stream()
                .sorted(java.util.Comparator.comparing(value -> value.unit().id())).toList()) {
                var entry = units.addObject().put("unitId", unit.unit().id()).put("publishedAt",
                    unit.release().publishedAt().toEpochMilli());
                configuration(entry.putObject("configuration"), unit.configuration());
            }
        }
        return encode(node, count == 1 ? SINGLE_LIMIT : LIMIT);
    }

    /** Writes one exact configuration without copying the whole graph for every unit. / 写入一份精确配置，不为各单元复制整图。
     * @param configuration target JSON object / 目标JSON对象
     * @param config immutable reviewed configuration / 不可变已审阅配置 */
    static void configuration(com.fasterxml.jackson.databind.node.ObjectNode configuration,
                              DeploymentConfigSnapshot config) {
        configuration.put("revision", config.revision()).put("schemaVersion", config.schemaVersion())
            .put("createdAt", config.createdAt().toEpochMilli()).put("sha256", config.sha256());
        var entries = configuration.putArray("entries");
        for (var entry : config.entries()) {
            var value = entries.addObject().put("key", entry.key()).put("scope", entry.scope().name());
            if (entry.value() instanceof DeploymentConfigValue.Text text)
                value.put("type", "TEXT").put("value", text.value());
            else if (entry.value() instanceof DeploymentConfigValue.Number number)
                value.put("type", "NUMBER").put("value", number.value());
            else if (entry.value() instanceof DeploymentConfigValue.Flag flag)
                value.put("type", "FLAG").put("value", flag.value());
            else
                throw new IllegalArgumentException("Unsupported configuration value");
        }
    }

    /** Rebuilds only the exact persisted proposal and rejects any noncanonical or incomplete field. / 仅重建精确已持久提案并拒绝非规范或缺失字段。
     * @param text original bounded request field / 原有界请求字段
     * @param executionId original execution identity / 原执行身份
     * @return complete immutable business template / 完整不可变业务模板
     * @throws SQLException if the evidence is malformed or inconsistent / 依据畸形或不一致时 */
    static DeploymentPublishProposal decodeTemplate(String text, UUID executionId) throws SQLException {
        var node = parse(text, LIMIT);
        int version = node.path("formatVersion").intValue();
        if (version == 1)
            fields(node, Set.of("formatVersion", "applicationId", "runtime", "health", "publishedAt", "configuration"));
        else if (version == 2)
            fields(node, Set.of("formatVersion", "applicationId", "runtime", "health", "units"));
        else
            throw new SQLException("Unsupported publication template");
        String applicationId = string(node, "applicationId");
        if (!node.path("runtime").isObject() || !node.path("health").isObject())
            throw new SQLException("Publish manifest documents are absent");
        String runtime = node.path("runtime").toString();
        String health = node.path("health").toString();
        var identity = new DeploymentReleaseIdentity(executionId, DeploymentReleaseCodec.digest(runtime, health));
        var retained = new DeploymentReleaseRecord(applicationId, identity, executionId,
            DeploymentReleaseRecord.DeploymentReleaseState.CANDIDATE, runtime, health, Instant.EPOCH);
        var snapshot = DeploymentReleaseCodec.snapshot(retained);
        if (snapshot.bindings().size() != snapshot.graph().components().size() || snapshot.bindings().size() < 1
            || snapshot.bindings().size() > 16 || (version == 1) != (snapshot.bindings().size() == 1))
            throw new SQLException("Publish template must contain one complete executable graph");
        try {
            Map<String, JsonNode> entries;
            if (version == 1) {
                if (!node.path("publishedAt").canConvertToLong())
                    throw new SQLException("Publish time is invalid");
                entries = Map.of(snapshot.bindings().getFirst().unitId(), node);
            } else {
                var list = array(node, "units", 16);
                var mapped = new java.util.LinkedHashMap<String, JsonNode>();
                for (var entry : list) {
                    fields(entry, Set.of("unitId", "publishedAt", "configuration"));
                    if (!entry.path("publishedAt").canConvertToLong()
                        || mapped.putIfAbsent(string(entry, "unitId"), entry) != null)
                        throw new SQLException("Publish unit identity or time is invalid");
                }
                entries = Map.copyOf(mapped);
            }
            var components = snapshot.graph().components().stream()
                .collect(Collectors.toMap(value -> value.unit().id(), value -> value));
            if (entries.size() != snapshot.bindings().size() || !entries.keySet().equals(components.keySet()))
                throw new SQLException("Publish units differ from original graph");
            var units = new ArrayList<DeploymentUnitCommit>();
            for (var binding : snapshot.bindings()) {
                var component = components.get(binding.unitId());
                var entry = entries.get(binding.unitId());
                var config = configuration(entry.path("configuration"), binding.unitId());
                if (binding.configurationRevision() != config.revision()
                    || !binding.configurationSha256().equals(config.sha256()))
                    throw new SQLException("Publish configuration differs from original graph");
                units.add(new DeploymentUnitCommit(component.unit(), component.runtime(),
                    new DeploymentCurrentRelease(component.unit().id(), binding.releaseSha256(),
                        Instant.ofEpochMilli(entry.path("publishedAt").longValue())),
                    config, binding.secrets()));
            }
            var result = new DeploymentPublishProposal(snapshot.graph(), List.copyOf(units));
            if (!encodeTemplate(result).equals(text))
                throw new SQLException("Publish template is not canonical");
            return result;
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Publish template is invalid", invalid);
        }
    }

    /** Reads exact typed normal configuration; its own canonical digest is rechecked. / 读取精确类型化普通配置并重新核对其规范摘要。
     * @param node original configuration document / 原配置文档
     * @param applicationId original application / 原应用
     * @return verified immutable configuration / 已核实不可变配置
     * @throws SQLException if fields, values or digest differ / 字段、值或摘要不符时 */
    static DeploymentConfigSnapshot configuration(JsonNode node, String applicationId)
        throws SQLException {
        fields(node, Set.of("revision", "schemaVersion", "createdAt", "sha256", "entries"));
        if (!node.path("revision").canConvertToLong() || !node.path("createdAt").canConvertToLong())
            throw new SQLException("Publish configuration time or revision is invalid");
        var entries = new ArrayList<DeploymentConfigEntry>();
        try {
            for (var item : array(node, "entries", LIMIT)) {
                fields(item, Set.of("key", "scope", "type", "value"));
                var type = string(item, "type");
                DeploymentConfigValue value = switch (type) {
                    case "TEXT" -> new DeploymentConfigValue.Text(string(item, "value"));
                    case "NUMBER" -> {
                        if (!item.path("value").canConvertToLong())
                            throw new SQLException("Publish configuration number is invalid");
                        yield new DeploymentConfigValue.Number(item.path("value").longValue());
                    }
                    case "FLAG" -> {
                        if (!item.path("value").isBoolean())
                            throw new SQLException("Publish configuration flag is invalid");
                        yield new DeploymentConfigValue.Flag(item.path("value").booleanValue());
                    }
                    default -> throw new SQLException("Publish configuration type is invalid");
                };
                entries.add(new DeploymentConfigEntry(string(item, "key"),
                    DeploymentConfigScopeType.valueOf(string(item, "scope")), value));
            }
            return new DeploymentConfigSnapshot(applicationId, node.path("revision").longValue(),
                string(node, "schemaVersion"), Instant.ofEpochMilli(node.path("createdAt").longValue()), entries,
                string(node, "sha256"));
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Publish configuration is invalid", invalid);
        }
    }
}
