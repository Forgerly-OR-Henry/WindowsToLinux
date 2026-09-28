package gold.debug.windowstolinux.mod.deployment.db;

import static gold.debug.windowstolinux.mod.deployment.db.DeploymentJsonDocument.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.service.core.config.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentCurrentRelease;

/** Encodes a complete non-secret single-unit business template before a remote publish. / 远端发布前编码完整非秘密单单元业务模板。 */
final class DeploymentPublicationTemplateCodec {
    /** Holds a 16 MiB runtime graph plus escaped reviewed configuration, health and framing. / 容纳16 MiB运行图及已审阅配置转义、健康与信封。 */
    static final int LIMIT = 32 * 1_048_576;

    /** Prevents utility construction. / 防止构造工具类。 */
    private DeploymentPublicationTemplateCodec() {
    }

    /** Freezes the exact graph, configuration and publication time without secret plaintext. / 冻结精确图、配置和发布时间，不包含秘密明文。
     * @param publication original proposed business result / 原拟提交业务结果
     * @return bounded canonical JSON / 有界规范JSON
     * @throws SQLException if the template exceeds the supported business bounds / 模板超过受支持业务边界时 */
    static String encodeTemplate(DeploymentPublicationProposal publication) throws SQLException {
        if (publication.units().size() != 1 || publication.graph().components().size() != 1)
            throw new SQLException("Only a complete single-unit publication template is supported");
        var unit = publication.units().getFirst();
        var snapshot = DeploymentReleaseSnapshot.capture(publication.graph(), publication.units());
        var config = unit.configuration();
        var node = object().put("formatVersion", 1).put("applicationId", publication.graph().applicationId())
                .put("publishedAt", unit.release().publishedAt().toEpochMilli());
        node.set("runtime", parse(DeploymentReleaseCodec.runtime(snapshot), DeploymentReleaseCodec.LIMIT));
        node.set("health", parse(DeploymentReleaseCodec.health(snapshot)));
        var configuration = node.putObject("configuration").put("revision", config.revision())
                .put("schemaVersion", config.schemaVersion()).put("createdAt", config.createdAt().toEpochMilli())
                .put("sha256", config.sha256());
        var entries = configuration.putArray("entries");
        for (var entry : config.entries()) {
            var value = entries.addObject().put("key", entry.key()).put("scope", entry.scope().name());
            if (entry.value() instanceof DeploymentConfigurationValue.Text text)
                value.put("type", "TEXT").put("value", text.value());
            else if (entry.value() instanceof DeploymentConfigurationValue.Number number)
                value.put("type", "NUMBER").put("value", number.value());
            else if (entry.value() instanceof DeploymentConfigurationValue.Flag flag)
                value.put("type", "FLAG").put("value", flag.value());
            else
                throw new SQLException("Unsupported configuration value");
        }
        return encode(node, LIMIT);
    }

    /** Rebuilds only the exact persisted proposal and rejects any noncanonical or incomplete field. / 仅重建精确已持久提案并拒绝非规范或缺失字段。
     * @param text original bounded request field / 原有界请求字段
     * @param executionId original execution identity / 原执行身份
     * @return complete immutable business template / 完整不可变业务模板
     * @throws SQLException if the evidence is malformed or inconsistent / 依据畸形或不一致时 */
    static DeploymentPublicationProposal decodeTemplate(String text, UUID executionId) throws SQLException {
        var node = parse(text, LIMIT);
        fields(node, Set.of("formatVersion", "applicationId", "runtime", "health", "publishedAt", "configuration"));
        if (node.path("formatVersion").intValue() != 1 || !node.path("publishedAt").canConvertToLong())
            throw new SQLException("Unsupported publication template");
        String applicationId = string(node, "applicationId");
        if (!node.path("runtime").isObject() || !node.path("health").isObject())
            throw new SQLException("Publication manifest documents are absent");
        String runtime = node.path("runtime").toString();
        String health = node.path("health").toString();
        var identity = new DeploymentReleaseIdentity(executionId, DeploymentReleaseCodec.digest(runtime, health));
        var retained = new DeploymentReleaseRecord(applicationId, identity, executionId,
                DeploymentReleaseRecord.DeploymentReleaseState.CANDIDATE, runtime, health, Instant.EPOCH);
        var snapshot = DeploymentReleaseCodec.snapshot(retained);
        if (snapshot.bindings().size() != 1 || snapshot.graph().components().size() != 1)
            throw new SQLException("Publication template must contain one complete unit");
        var binding = snapshot.bindings().getFirst();
        var component = snapshot.graph().components().getFirst();
        var configuration = configuration(node.path("configuration"), applicationId);
        if (!binding.unitId().equals(component.unit().id())
                || binding.configurationRevision() != configuration.revision()
                || !binding.configurationSha256().equals(configuration.sha256()))
            throw new SQLException("Publication template configuration differs from original graph");
        try {
            var result = new DeploymentPublicationProposal(snapshot.graph(),
                    List.of(new DeploymentUnitCommit(component.unit(), component.runtime(),
                            new DeploymentCurrentRelease(component.unit().id(), binding.releaseSha256(),
                                    Instant.ofEpochMilli(node.path("publishedAt").longValue())),
                            configuration, binding.secrets())));
            if (!encodeTemplate(result).equals(text))
                throw new SQLException("Publication template is not canonical");
            return result;
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Publication template is invalid", invalid);
        }
    }

    /** Reads exact typed normal configuration; its own canonical digest is rechecked. / 读取精确类型化普通配置并重新核对其规范摘要。
     * @param node original configuration document / 原配置文档
     * @param applicationId original application / 原应用
     * @return verified immutable configuration / 已核实不可变配置
     * @throws SQLException if fields, values or digest differ / 字段、值或摘要不符时 */
    private static DeploymentConfigurationSnapshot configuration(JsonNode node, String applicationId)
            throws SQLException {
        fields(node, Set.of("revision", "schemaVersion", "createdAt", "sha256", "entries"));
        if (!node.path("revision").canConvertToLong() || !node.path("createdAt").canConvertToLong())
            throw new SQLException("Publication configuration time or revision is invalid");
        var entries = new ArrayList<DeploymentConfigurationEntry>();
        try {
            for (var item : array(node, "entries", LIMIT)) {
                fields(item, Set.of("key", "scope", "type", "value"));
                var type = string(item, "type");
                DeploymentConfigurationValue value = switch (type) {
                    case "TEXT" -> new DeploymentConfigurationValue.Text(string(item, "value"));
                    case "NUMBER" -> {
                        if (!item.path("value").canConvertToLong())
                            throw new SQLException("Publication configuration number is invalid");
                        yield new DeploymentConfigurationValue.Number(item.path("value").longValue());
                    }
                    case "FLAG" -> {
                        if (!item.path("value").isBoolean())
                            throw new SQLException("Publication configuration flag is invalid");
                        yield new DeploymentConfigurationValue.Flag(item.path("value").booleanValue());
                    }
                    default -> throw new SQLException("Publication configuration type is invalid");
                };
                entries.add(new DeploymentConfigurationEntry(string(item, "key"),
                        DeploymentConfigurationScopeType.valueOf(string(item, "scope")), value));
            }
            return new DeploymentConfigurationSnapshot(applicationId, node.path("revision").longValue(),
                    string(node, "schemaVersion"), Instant.ofEpochMilli(node.path("createdAt").longValue()), entries,
                    string(node, "sha256"));
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Publication configuration is invalid", invalid);
        }
    }
}
