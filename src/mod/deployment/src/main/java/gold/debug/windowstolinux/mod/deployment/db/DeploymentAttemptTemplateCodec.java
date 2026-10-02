package gold.debug.windowstolinux.mod.deployment.db;

import static gold.debug.windowstolinux.mod.deployment.db.DeploymentJsonDocument.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Retains a later immutable proposal using the original complete fixed configurations. / 使用原完整固定配置留存后续不可变提案。 */
final class DeploymentAttemptTemplateCodec {
    /** Sixteen MiB runtime, one MiB health and bounded unit framing, without repeated configurations. / 十六MiB运行定义、一MiB健康及有界单元分界，不重复配置。 */
    static final int LIMIT = 18 * 1_048_576;

    /** Prevents construction. / 禁止构造。 */
    private DeploymentAttemptTemplateCodec() {
    }

    /** Freezes one later proposal while proving every original fixed unit and configuration. / 冻结后续提案并证明各原固定单元与配置。
     * @param first original complete proposal / 原完整提案
     * @param next actual newly reviewed proposal / 实际新审阅提案
     * @return canonical bounded compact proposal / 规范有界紧凑提案
     * @throws SQLException if fixed bindings or encoding differ / 固定绑定或编码不符时 */
    static String encodeCompact(DeploymentPublicationProposal first, DeploymentPublicationProposal next) throws SQLException {
        return encodeCompact(first, next, LIMIT);
    }

    /** Uses a smaller testable byte bound without enlarging any actual proposal limit. / 使用可测较小字节边界，不扩大实际提案上限。
     * @param first original full proposal / 原完整提案
     * @param next actual later proposal / 实际后续提案
     * @param limit admitted compact UTF-8 byte bound / 紧凑UTF-8字节边界
     * @return complete compact encoding / 完整紧凑编码
     * @throws SQLException if an identity or byte bound conflicts / 身份或字节边界冲突时 */
    static String encodeCompact(DeploymentPublicationProposal first, DeploymentPublicationProposal next, int limit) throws SQLException {
        if (limit < 1 || limit > LIMIT) throw new SQLException("Invalid compact proposal byte bound");
        requireFixed(first, next);
        String complete = DeploymentPublicationTemplateCodec.encodeTemplate(next);
        var template = parse(complete, DeploymentPublicationTemplateCodec.LIMIT);
        if (template.path("formatVersion").intValue() == 1) template.remove("configuration");
        else for (var unit : template.withArray("units")) ((ObjectNode) unit).remove("configuration");
        var node = object().put("formatVersion", 1).put("templateSha256", digest(complete));
        node.set("template", template);
        return encode(node, limit);
    }

    /** Rehydrates only from the original durable configurations and rechecks the full canonical SHA. / 仅从原持久配置重建并重核完整规范SHA。
     * @param first original complete proposal / 原完整提案
     * @param text retained compact document / 留存紧凑文档
     * @param executionId authentic original formal identity / 真实原正式身份
     * @return exact complete later immutable proposal / 精确完整后续不可变提案
     * @throws SQLException if any original field, configuration or SHA differs / 任一原字段、配置或SHA不符时 */
    static DeploymentPublicationProposal decodeCompact(DeploymentPublicationProposal first, String text, UUID executionId) throws SQLException {
        var node = parse(text, LIMIT);
        fields(node, Set.of("formatVersion", "templateSha256", "template"));
        if (node.path("formatVersion").intValue() != 1 || !node.path("template").isObject())
            throw new SQLException("Unsupported compact proposal");
        String expected = string(node, "templateSha256");
        if (!expected.matches("[0-9a-f]{64}")) throw new SQLException("Invalid compact proposal SHA");
        var template = ((ObjectNode) node.path("template")).deepCopy();
        var original = parse(DeploymentPublicationTemplateCodec.encodeTemplate(first), DeploymentPublicationTemplateCodec.LIMIT);
        int version = template.path("formatVersion").isInt() ? template.path("formatVersion").intValue() : -1;
        if (version != original.path("formatVersion").intValue())
            throw new SQLException("Compact proposal changed the complete unit scope");
        if (version == 1) {
            fields(template, Set.of("formatVersion", "applicationId", "runtime", "health", "publishedAt"));
            template.set("configuration", original.path("configuration").deepCopy());
        } else if (version == 2) {
            fields(template, Set.of("formatVersion", "applicationId", "runtime", "health", "units"));
            var configurations = new HashMap<String, JsonNode>();
            for (var unit : array(original, "units", 16))
                configurations.put(string(unit, "unitId"), unit.path("configuration"));
            var seen = new HashSet<String>();
            for (var unit : array(template, "units", 16)) {
                fields(unit, Set.of("unitId", "publishedAt"));
                String id = string(unit, "unitId");
                if (!seen.add(id) || !configurations.containsKey(id))
                    throw new SQLException("Compact proposal changed the original unit");
                ((ObjectNode) unit).set("configuration", configurations.get(id).deepCopy());
            }
            if (!seen.equals(configurations.keySet())) throw new SQLException("Compact proposal lost an original unit");
        } else throw new SQLException("Unsupported original complete proposal");
        String complete = encode(template, DeploymentPublicationTemplateCodec.LIMIT);
        if (!digest(complete).equals(expected))
            throw new SQLException("Compact proposal differs from its complete canonical SHA");
        var next = DeploymentPublicationTemplateCodec.decodeTemplate(complete, executionId);
        requireFixed(first, next);
        if (!encodeCompact(first, next).equals(text)) throw new SQLException("Compact proposal is not canonical");
        return next;
    }

    /** Checks original topology, owner identities, resources, exact configuration and secret revisions. / 核对原拓扑、归属身份、资源、精确配置及秘密修订。
     * @param first original complete proposal / 原完整提案
     * @param next actual later proposal / 实际后续提案
     * @throws SQLException if any fixed formal binding changed / 任一固定正式绑定改变时 */
    private static void requireFixed(DeploymentPublicationProposal first, DeploymentPublicationProposal next) throws SQLException {
        Objects.requireNonNull(first);
        Objects.requireNonNull(next);
        var before = first.graph();
        var after = next.graph();
        if (!before.applicationId().equals(after.applicationId()) || !before.healthComponentId().equals(after.healthComponentId())
            || !before.healthCheck().equals(after.healthCheck()))
            throw new SQLException("Attempt changed original application or whole health");
        var originals = before.components().stream().collect(Collectors.toMap(value -> value.componentId(), value -> value));
        var observed = after.components().stream().collect(Collectors.toMap(value -> value.componentId(), value -> value));
        if (!originals.keySet().equals(observed.keySet()))
            throw new SQLException("Attempt changed original graph components");
        for (var id : originals.keySet()) {
            var old = originals.get(id);
            var selected = observed.get(id);
            if (!old.unit().equals(selected.unit()) || !old.dependencies().equals(selected.dependencies()) || !old.resources().equals(selected.resources())
                || !old.dataPaths().equals(selected.dataPaths()) || !old.runtime().userAccessUrl().equals(selected.runtime().userAccessUrl())
                || !old.runtime().identityPolicy().equals(selected.runtime().identityPolicy()))
                throw new SQLException("Attempt changed original owner, topology or resources");
        }
        var units = first.units().stream().collect(Collectors.toMap(value -> value.unit().id(), value -> value));
        if (next.units().size() != units.size()) throw new SQLException("Attempt changed original unit count");
        var seen = new HashSet<String>();
        for (var unit : next.units()) {
            var original = units.get(unit.unit().id());
            if (original == null || !seen.add(unit.unit().id()) || !unit.unit().equals(original.unit()) || !new HashSet<>(unit.secrets()).equals(new HashSet<>(original.secrets())))
                throw new SQLException("Attempt changed an original unit or secret revision");
            var oldConfig = object();
            var nextConfig = object();
            DeploymentPublicationTemplateCodec.configuration(oldConfig, original.configuration());
            DeploymentPublicationTemplateCodec.configuration(nextConfig, unit.configuration());
            if (!oldConfig.equals(nextConfig))
                throw new SQLException("Attempt changed original complete configuration");
        }
        if (!seen.equals(units.keySet())) throw new SQLException("Attempt lost an original unit");
    }

    /** Hashes exactly the complete canonical UTF-8 proposal. / 哈希精确完整规范UTF-8提案。
     * @param text canonical complete proposal / 规范完整提案
     * @return lowercase SHA-256 / 小写SHA-256
     * @throws SQLException if SHA-256 is unavailable / SHA-256不可用时 */
    private static String digest(String text) throws SQLException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new SQLException("SHA-256 unavailable", unavailable);
        }
    }
}

