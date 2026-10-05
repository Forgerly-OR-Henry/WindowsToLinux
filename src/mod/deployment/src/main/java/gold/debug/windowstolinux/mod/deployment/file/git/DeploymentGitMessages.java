package gold.debug.windowstolinux.mod.deployment.file.git;

import java.net.URI;
import java.io.IOException;
import java.util.*;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;

/** Deployment-owned source-choice codec, independent of Git worker messages. / 独立于 Git worker 消息的部署所属来源选择编解码。 */
public final class DeploymentGitMessages {
    /** Strict original source-choice parser. / 严格原来源选择解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(4)
            .maxStringLength(12000).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Prevents utility construction. / 禁止工具构造。 */
    private DeploymentGitMessages() {
    }

    /** Encodes original deployment choices and its independent archive budget. / 编码原部署选择及其独立归档预算。
     * @param request original reviewed choice / 原已审阅选择
     * @return exact bounded JSON / 精确有界 JSON */
    public static String encode(DeploymentGitRequest request) {
        var kind = switch (request.reference()) {
            case DeploymentGitRef.DefaultBranch ignored -> "DEFAULT";
            case DeploymentGitRef.Branch ignored -> "BRANCH";
            case DeploymentGitRef.Tag ignored -> "TAG";
            case DeploymentGitRef.Commit ignored -> "COMMIT";
        };
        var root = JSON.createObjectNode().put("remote", request.remote().location().toString()).put("kind", kind)
            .put("reference", request.reference().value()).put("maximumArchiveBytes", request.maximumArchiveBytes())
            .put("permitLocalFileRemote", request.permitLocalFileRemote());
        var hosts = root.putArray("allowedHosts");
        request.allowedHosts().stream().sorted().forEach(hosts::add);
        return root.toString();
    }

    /** Decodes only strict source choices, never a provider physical receipt. / 仅解码严格来源选择，不解码提供方物理回执。
     * @param text original bounded JSON / 原有界 JSON
     * @return checked source choice / 已核验来源选择
     * @throws IOException if original fields are invalid / 原字段无效时 */
    public static DeploymentGitRequest decode(String text) throws IOException {
        try {
            if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 12000)
                throw new IllegalArgumentException();
            var root = JSON.readTree(text);
            var keys = Set.of("remote", "kind", "reference", "allowedHosts", "maximumArchiveBytes", "permitLocalFileRemote");
            if (root == null || !root.isObject() || root.size() != keys.size()) throw new IllegalArgumentException();
            root.fieldNames().forEachRemaining(key -> {
                if (!keys.contains(key)) throw new IllegalArgumentException();
            });
            String value = text(root, "reference");
            DeploymentGitRef reference = switch (text(root, "kind")) {
                case "DEFAULT" -> {
                    if (!value.equals("HEAD")) throw new IllegalArgumentException();
                    yield new DeploymentGitRef.DefaultBranch();
                }
                case "BRANCH" -> new DeploymentGitRef.Branch(value);
                case "TAG" -> new DeploymentGitRef.Tag(value);
                case "COMMIT" -> new DeploymentGitRef.Commit(value);
                default -> throw new IllegalArgumentException();
            };
            if (!root.get("allowedHosts").isArray() || root.get("allowedHosts").size() > 1024
                || !root.get("maximumArchiveBytes").isIntegralNumber() || !root.get("maximumArchiveBytes").canConvertToLong()
                || !root.get("permitLocalFileRemote").isBoolean()) throw new IllegalArgumentException();
            var hosts = new HashSet<String>();
            for (var host : root.get("allowedHosts"))
                if (!host.isTextual() || !hosts.add(host.textValue())) throw new IllegalArgumentException();
            return new DeploymentGitRequest(new DeploymentGitRemote(URI.create(text(root, "remote"))), reference, hosts,
                root.get("maximumArchiveBytes").longValue(), root.get("permitLocalFileRemote").booleanValue());
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid original deployment source choice");
        }
    }

    /** Reads exact text without coercion. / 不强转地读取精确文本。
     * @param object original object / 原对象
     * @param key original key / 原键
     * @return exact text / 精确文本 */
    private static String text(JsonNode object, String key) {
        if (!object.path(key).isTextual()) throw new IllegalArgumentException();
        return object.get(key).textValue();
    }
}
