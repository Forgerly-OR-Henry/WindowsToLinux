package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics;

/** Projects product reference metadata separately from untrusted output. / 将产品引用元数据与不可信输出分开投影。 */
public final class DeploymentAgentFeedback {
    private static final String ALTERNATIVE_PREFIX = "APPROVAL_DENIED: the exact action was not sent. Independent review feedback DATA:\n";

    private static final String ALTERNATIVE_AUTHORITY = "NONE; every replacement needs fresh exact independent review";

    /** Prevents construction. / 禁止构造。 */
    private DeploymentAgentFeedback() {
    }

    /** Validates metadata-only feedback; redacts all other raw observations. / 校验仅含元数据的反馈，脱敏其他原始观察。
     * @param tool actual tool / 实际工具
     * @param output actual feedback / 实际反馈
     * @return safe model input / 安全模型输入
     * @throws Exception malformed product metadata / 产品元数据错误 */
    public static String safe(String tool, String output) throws Exception {
        if (tool.equals("OBSERVATION")) return output;
        if (tool.equals("ALTERNATIVE_REQUIRED")) {
            try {
                return alternativeRequired(output);
            } catch (Exception invalid) {
                throw new IllegalArgumentException("Invalid alternative refusal feedback");
            }
        }
        if (Set.of("TOOL_database_inspect", "TOOL_database_select_source").contains(tool) && output.startsWith("{"))
            return databaseMetadata(tool, output);
        if (Set.of("TOOL_secrets", "TOOL_secret_select", "TOOL_secret_create").contains(tool)
            && output.startsWith("{")) {
            var json = new ObjectMapper();
            var root = json.readTree(output);
            DeploymentJsonFields.exact(root, Set.of("references"));
            var refs = root.get("references");
            if (!refs.isArray() || refs.size() > 256) throw new IllegalArgumentException("Invalid reference feedback");
            var values = new ArrayList<Map<String, Object>>();
            for (var node : refs) {
                DeploymentJsonFields.exact(node, Set.of("identifier", "revision", "purpose"));
                var ref = new DeploymentSecretRef(DeploymentJsonFields.text(node, "identifier", 64),
                    DeploymentJsonFields.number(node, "revision", 1, Integer.MAX_VALUE));
                String purpose = DeploymentJsonFields.text(node, "purpose", 128);
                if (!Set.of("deployment-runtime", "deployment-database").contains(purpose))
                    throw new IllegalArgumentException("Invalid reference purpose");
                values.add(Map.of("identifier", ref.identifier(), "revision", ref.revision(), "purpose", purpose));
            }
            return json.writeValueAsString(Map.of("references", values));
        }
        return output.replaceAll("(?im)^.*(?:password|passwd|secret|token|api[_-]?key|private[_-]?key).*[:=].*$",
            "[credential content excluded]");
    }

    /** Redacts untrusted refusal leaves, not their fixed non-authorizing JSON envelope. / 脱敏不可信拒绝叶文本，不抹去固定且不授权的JSON封装。
     * @param output fixed product refusal envelope / 固定产品拒绝封装
     * @return bounded structured data, never an approval / 有界结构化数据，绝非批准
     * @throws Exception malformed or oversized refusal / 拒绝结构错误或越界 */
    private static String alternativeRequired(String output) throws Exception {
        if (!output.startsWith(ALTERNATIVE_PREFIX) || output.length() - ALTERNATIVE_PREFIX.length() > 6000)
            throw new IllegalArgumentException("Invalid alternative refusal envelope");
        var json = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var root = json.readTree(output.substring(ALTERNATIVE_PREFIX.length()));
        DeploymentJsonFields.exact(root, Set.of("outcome", "dispatch", "reason", "suggestions", "authority"));
        if (!DeploymentJsonFields.text(root, "outcome", 4).equals("DENY")
            || !DeploymentJsonFields.text(root, "dispatch", 8).equals("NOT_SENT")
            || !DeploymentJsonFields.text(root, "authority", ALTERNATIVE_AUTHORITY.length()).equals(ALTERNATIVE_AUTHORITY))
            throw new IllegalArgumentException("Alternative refusal cannot authorize an action");
        String reason = DeploymentJsonFields.text(root, "reason", 512);
        var suggestions = root.get("suggestions");
        Set<String> allowed = Set.of("task", "missingEvidence", "adjustments", "candidateCommands", "reviewBinding", "discussionStatus");
        if (!suggestions.isObject() || suggestions.size() > allowed.size())
            throw new IllegalArgumentException("Invalid alternative refusal suggestions");
        var keys = new HashSet<String>();
        suggestions.fieldNames().forEachRemaining(keys::add);
        if (!allowed.containsAll(keys)) throw new IllegalArgumentException("Unexpected alternative refusal fields");
        String task = DeploymentJsonFields.text(suggestions, "task", 36);
        if (!UUID.fromString(task).toString().equals(task)) throw new IllegalArgumentException("Invalid refusal task");
        var safeSuggestions = json.createObjectNode().put("task", task);
        if (suggestions.has("reviewBinding")) {
            String binding = DeploymentJsonFields.text(suggestions, "reviewBinding", 64);
            if (!binding.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid refusal binding");
            safeSuggestions.put("reviewBinding", binding);
        }
        if (suggestions.has("discussionStatus")) {
            String status = DeploymentJsonFields.text(suggestions, "discussionStatus", 6);
            if (!Set.of("OPEN", "CLOSED").contains(status))
                throw new IllegalArgumentException("Invalid refusal discussion status");
            safeSuggestions.put("discussionStatus", status);
        }
        for (String key : List.of("missingEvidence", "adjustments", "candidateCommands")) {
            if (!suggestions.has(key)) continue;
            var values = json.readTree(DeploymentJsonFields.text(suggestions, key, 6000));
            if (!values.isArray() || values.size() > 4)
                throw new IllegalArgumentException("Invalid refusal suggestion list");
            var safeValues = json.createArrayNode();
            for (var value : values) {
                if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 512 || value.textValue().indexOf(0) >= 0)
                    throw new IllegalArgumentException("Invalid refusal suggestion");
                safeValues.add(gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReview.denialReason(value.textValue()));
            }
            safeSuggestions.put(key, json.writeValueAsString(safeValues));
        }
        var safe = json.createObjectNode().put("outcome", "DENY").put("dispatch", "NOT_SENT")
            .put("reason", gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReview.denialReason(reason));
        safe.set("suggestions", safeSuggestions);
        safe.put("authority", ALTERNATIVE_AUTHORITY);
        String result = json.writeValueAsString(safe);
        if (result.length() > 6000) throw new IllegalArgumentException("Alternative refusal exceeds feedback budget");
        return ALTERNATIVE_PREFIX + result;
    }

    /** Checks nonsecret declaration and inventory fields without hiding credential variable names. / 校验非秘密声明与库存字段，不隐藏凭据变量名称。
     * @param tool actual database observation tool / 实际数据库观测工具
     * @param output product metadata projection / 产品元数据投影
     * @return bounded verified metadata / 有界已校验元数据
     * @throws Exception malformed or unexpected fields / 字段错误或不符合预期时 */
    private static String databaseMetadata(String tool, String output) throws Exception {
        var root = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(output);
        DeploymentJsonFields.exact(root, Set.of("sourceRevision", "component", "declarationRoot", "scope", "serverInventory", "sourceCoverage", "status", "databases", "candidateManifests", "questions", "schemaReviewRequired", "endpointConfirmationRequired"));
        var scope = gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentDatabaseScope.parse(root);
        String observed = DeploymentJsonFields.text(root, "serverInventory", 32);
        if (!DeploymentJsonFields.text(root, "sourceCoverage", 64).equals("EXPLICIT_MANIFEST_AND_SUPPORTED_CONVENTIONS_ONLY")
            || !Set.of("NOT_OBSERVED", "OBSERVED", "NOT_APPLICABLE").contains(observed)
            || tool.equals("TOOL_database_select_source") && scope != gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentDatabaseScope.SOURCE)
            throw new IllegalArgumentException("Invalid database observation scope");
        boolean sourceOnly = scope == gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentDatabaseScope.SOURCE;
        if (!DeploymentJsonFields.text(root, "sourceRevision", 64).matches("[0-9a-f]{64}")
            || !root.path("schemaReviewRequired").isBoolean() || !root.path("endpointConfirmationRequired").isBoolean()
            || !root.path("databases").isArray() || root.path("databases").size() > 32)
            throw new IllegalArgumentException("Invalid database metadata");
        gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentIdentification.componentId(DeploymentJsonFields.text(root, "component", 63));
        gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentIdentification.sourceRoot(DeploymentJsonFields.text(root, "declarationRoot", 500));
        String status = DeploymentJsonFields.text(root, "status", 32);
        boolean resolved = Set.of("READY", "SOURCE_INSPECTED").contains(status);
        if (!Set.of("READY", "SOURCE_INSPECTED", "NEEDS_SOURCE_SELECTION", "NEEDS_DECLARATION").contains(status)
            || sourceOnly && (status.equals("READY") || !observed.equals("NOT_OBSERVED"))
            || !sourceOnly && status.equals("SOURCE_INSPECTED")
            || !resolved && !observed.equals("NOT_OBSERVED")
            || !root.path("candidateManifests").isArray() || root.path("candidateManifests").size() > 16
            || !root.path("questions").isArray() || root.path("questions").size() > 64 || output.length() > 11000
            || !resolved && !root.path("databases").isEmpty()
            || resolved && !root.path("questions").isEmpty()
            || status.equals("READY") && !root.path("candidateManifests").isEmpty())
            throw new IllegalArgumentException("Invalid database resolution metadata");
        for (var manifest : root.path("candidateManifests")) {
            DeploymentJsonFields.exact(manifest, Set.of("path", "declarationRoot", "databaseIds"));
            String directory = gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentIdentification.sourceRoot(DeploymentJsonFields.text(manifest, "declarationRoot", 500));
            String path = DeploymentJsonFields.text(manifest, "path", 500);
            if (!path.equals(directory.equals(".") ? "windowstolinux-db.properties" : directory + "/windowstolinux-db.properties")
                || !manifest.path("databaseIds").isArray() || manifest.path("databaseIds").size() > 16)
                throw new IllegalArgumentException("Invalid source manifest metadata");
            for (var id : manifest.path("databaseIds"))
                if (!id.isTextual() || !id.textValue().matches("[a-z][a-z0-9-]{0,31}"))
                    throw new IllegalArgumentException("Invalid declared database identity");
        }
        for (var question : root.path("questions")) {
            DeploymentJsonFields.exact(question, Set.of("key", "value", "choices"));
            if (!DeploymentJsonFields.text(question, "key", 128).matches("database\\.[a-z][a-z0-9-]{0,31}\\.(engine|database|username|environmentPrefix|passwordEnvironment|path|pathEnvironment|initialize)")
                || !question.path("value").isTextual() || question.path("value").asText().length() > 4000
                || question.path("value").asText().indexOf(0) >= 0 || !question.path("choices").isArray() || question.path("choices").size() > 64)
                throw new IllegalArgumentException("Invalid nonsecret declaration question");
            for (var choice : question.path("choices"))
                if (!choice.isTextual() || choice.textValue().isBlank() || choice.textValue().length() > 512 || choice.textValue().indexOf(0) >= 0)
                    throw new IllegalArgumentException("Invalid declaration choice");
        }
        boolean nativeDeclared = false;
        for (var database : root.path("databases")) {
            var fields = new HashSet<>(Set.of("databaseId", "engine", "initializationFiles", "environmentPrefix", "credentialEnvironmentName", "requestedVersion", "sqlitePath", "sqlitePathEnvironment", "sqliteSeedFile"));
            if (!sourceOnly) fields.addAll(Set.of("instanceIds", "instances"));
            DeploymentJsonFields.exact(database, fields);
            if (!DeploymentJsonFields.text(database, "databaseId", 32).matches("[a-z][a-z0-9-]{0,31}"))
                throw new IllegalArgumentException("Invalid database identity");
            var engine = gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseEngineType.valueOf(DeploymentJsonFields.text(database, "engine", 32));
            nativeDeclared |= engine != gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseEngineType.SQLITE;
            new gold.debug.windowstolinux.mod.deployment.service.core.model.database.DatabaseVersionRequirement(database.path("requestedVersion").asText());
            var instances = database.path("instances");
            if (!sourceOnly && (!instances.isArray() || instances.size() > 256))
                throw new IllegalArgumentException("Invalid instance metadata");
            for (var instance : instances) {
                DeploymentJsonFields.exact(instance, Set.of("id", "version", "port", "running", "fingerprint"));
                DeploymentJsonFields.text(instance, "id", 256);
                DeploymentJsonFields.text(instance, "version", 256);
                DeploymentJsonFields.number(instance, "port", 1, 65535);
                if (!instance.path("running").isBoolean() || !DeploymentJsonFields.text(instance, "fingerprint", 64).matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("Invalid observed instance identity");
            }
            for (String field : List.of("environmentPrefix", "credentialEnvironmentName", "sqlitePathEnvironment")) {
                var text = database.get(field);
                if (!text.isTextual() || !text.textValue().matches("[A-Za-z0-9_]{0,128}"))
                    throw new IllegalArgumentException("Invalid database variable metadata");
            }
            for (String field : List.of("sqlitePath", "sqliteSeedFile"))
                if (!database.path(field).isTextual() || database.path(field).asText().length() > 512 || database.path(field).asText().indexOf(0) >= 0)
                    throw new IllegalArgumentException("Invalid SQLite source metadata");
            for (String field : sourceOnly ? List.of("initializationFiles") : List.of("initializationFiles", "instanceIds")) {
                var values = database.get(field);
                if (!values.isArray() || values.size() > 256)
                    throw new IllegalArgumentException("Invalid database metadata list");
                for (var value : values)
                    if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 512 || value.textValue().indexOf(0) >= 0)
                        throw new IllegalArgumentException("Invalid database metadata member");
            }
        }
        if (!sourceOnly && status.equals("READY") && !observed.equals(nativeDeclared ? "OBSERVED" : "NOT_APPLICABLE"))
            throw new IllegalArgumentException("Remote database inventory must match actual observation coverage");
        return root.toString();
    }

    /** Resolves product-facing stage copy without displaying tool bodies. / 解析产品阶段文案，不展示工具正文。
     * @param context original activation / 原启用
     * @param tool actual observation / 实际观察
     * @return localized progress / 本地化进度 */
    public static String label(ModContext context, String tool) {
        String key = switch (tool) {
            case "IDENTIFY" -> "identify";
            case "PLAN", "PLAN_STATE" -> "plan";
            case "TOOL_analyze", "TOOL_check" -> "analyze";
            case "TOOL_prepare_tools" -> "tools";
            case "TOOL_build", "COMMAND_RESULT" -> "build";
            case "TOOL_database_inspect", "TOOL_database_select_source", "TOOL_databases" -> "database";
            case "TOOL_secrets", "TOOL_secret_select", "TOOL_secret_create" -> "reference";
            case "SOURCE_REVISION" -> "patch";
            case "SEALED" -> "seal";
            default -> "observe";
        };
        return DeploymentDiagnostics.text(context, "agent.stage." + key);
    }
}
