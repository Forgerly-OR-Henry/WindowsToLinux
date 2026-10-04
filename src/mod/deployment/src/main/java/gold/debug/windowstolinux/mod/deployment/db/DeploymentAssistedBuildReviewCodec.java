package gold.debug.windowstolinux.mod.deployment.db;

import static gold.debug.windowstolinux.mod.deployment.db.DeploymentJsonDocument.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement;

/** Persists complete non-secret build reviews while checkpoints hold only bounded pointers. / 持久化完整非秘密构建审阅，检查点仅持有有界指针。 */
public final class DeploymentAssistedBuildReviewCodec {
    /** Two complete descriptions remain bounded independently of publication metadata. / 两份完整描述独立于发布元数据保持有界。 */
    public static final int LIMIT = 16 * 1_048_576;

    /** Only the originally permitted typed correction fields. / 仅原允许的类型化纠正字段。 */
    private static final Set<String> INPUTS = Set.of("primary", "secondary", "version", "jvmTarget");

    /** Fixed source identity without live platform handles or local absolute paths. / 不含活动平台句柄或本地绝对路径的固定来源身份。 */
    private static final Set<String> SOURCE = Set.of("snapshot", "manifest", "archive", "relativeRoot", "bytes", "uncompressedBytes");

    /** Fixed deterministic facts required by a build description. / 构建描述所需的固定确定性事实。 */
    private static final Set<String> FACTS = Set.of("projectType", "buildTool", "buildDirectory", "language", "support", "architecture", "framework");

    /** Prevents construction. / 禁止构造。 */
    private DeploymentAssistedBuildReviewCodec() {
    }

    /** Complete durable typed description; source authority remains with the original task. / 完整持久类型化描述，来源权限仍属于原任务。
     * @param applicationId original unit / 原单元
     * @param serverId original server / 原服务器
     * @param source exact source origin / 精确来源出处
     * @param facts fixed deterministic facts / 固定确定性事实
     * @param requirements original typed source declarations / 原类型化源码声明
     * @param userAccessUrl original reviewed URL / 原审阅URL
     * @param configuration original complete immutable configuration / 原完整不可变配置
     * @param secrets original revision identities / 原修订身份
     * @param resources original typed bindings / 原类型化绑定
     * @param runtime complete newly reviewed runtime / 完整新审阅运行定义
     * @param limits original limits / 原限额
     * @param steps original deterministic stages / 原确定性阶段
     * @param inputs four bounded typed input values / 四项有界类型化输入值
     * @param reviewedAt actual new human review time / 实际新人工审阅时刻
     * @param containerRisk original explicit risk choice / 原明确风险选择
     * @param adapterRisk original explicit adapter choice / 原明确适配器选择
     * @param reviewedRevision exact complete reviewed plan SHA / 精确完整审阅计划SHA */
    public record Description(String applicationId, String serverId, Map<String, String> source,
                              Map<String, String> facts, List<ToolchainRequirement> requirements,
                              Optional<DeploymentAccessUrl> userAccessUrl,
                              DeploymentConfigSnapshot configuration,
                              List<DeploymentSecretRef> secrets, DeploymentResourceBindings resources,
                              DeploymentRuntimeDefinition runtime, DeploymentBuildLimits limits, List<String> steps,
                              Map<String, String> inputs, Instant reviewedAt, boolean containerRisk,
                              boolean adapterRisk,
                              String reviewedRevision) {
        /** Copies and validates the complete durable values. / 复制并校验完整持久值。
         * @param applicationId original unit / 原单元
         * @param serverId original server / 原服务器
         * @param source exact source identity / 精确来源身份
         * @param facts original facts / 原事实
         * @param requirements original typed source declarations / 原类型化源码声明
         * @param userAccessUrl original URL / 原URL
         * @param configuration original complete configuration / 原完整配置
         * @param secrets original revisions / 原修订
         * @param resources original bindings / 原绑定
         * @param runtime complete runtime / 完整运行定义
         * @param limits original limits / 原限额
         * @param steps original stages / 原阶段
         * @param inputs typed corrections / 类型化纠正
         * @param reviewedAt actual review time / 实际审阅时刻
         * @param containerRisk original risk / 原风险
         * @param adapterRisk original risk / 原风险
         * @param reviewedRevision exact plan SHA / 精确计划SHA */
        public Description {
            source = Map.copyOf(source);
            facts = Map.copyOf(facts);
            secrets = List.copyOf(secrets);
            requirements = List.copyOf(requirements);
            Objects.requireNonNull(userAccessUrl);
            steps = List.copyOf(steps);
            inputs = Map.copyOf(inputs);
            if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}") || serverId.isBlank() || serverId.length() > 512
                || !source.keySet().equals(SOURCE) || !facts.keySet().equals(FACTS)
                || !inputs.keySet().equals(INPUTS) || inputs.values().stream().anyMatch(value -> value.length() > 4096 || value.indexOf('\0') >= 0)
                || !reviewedRevision.matches("[0-9a-f]{64}") || !configuration.applicationId().equals(applicationId)
                || limits.runAsRoot() || !runtime.workload().buildDirectory().equals(facts.get("buildDirectory")))
                throw new IllegalArgumentException("Invalid complete assisted build description");
            if (!UUID.fromString(source.get("snapshot")).toString().equals(source.get("snapshot"))
                || !DeploymentCommand.relative(source.get("relativeRoot"), true).equals(source.get("relativeRoot")))
                throw new IllegalArgumentException("Invalid original source identity");
            for (String key : List.of("bytes", "uncompressedBytes"))
                if (!source.get(key).matches("[0-9]{1,19}") || !Long.toString(Long.parseLong(source.get(key))).equals(source.get(key))
                    || Long.parseLong(source.get(key)) < (key.equals("bytes") ? 1 : 0))
                    throw new IllegalArgumentException("Invalid original source size");
            if (!source.get("manifest").matches("[0-9a-f]{64}") || !source.get("archive").matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid original source digest");
            DeploymentProjectKind.valueOf(facts.get("projectType"));
            DeploymentBuildToolType.valueOf(facts.get("buildTool"));
            Objects.requireNonNull(reviewedAt);
            Objects.requireNonNull(resources);
            if (!reviewedRevision.equals(DeploymentReviewedPlanDigest.from(hashValues(applicationId, serverId, source, facts,
                requirements, userAccessUrl, configuration, secrets, resources, runtime, limits, reviewedAt, containerRisk, adapterRisk))))
                throw new IllegalArgumentException("Complete reviewed description digest changed");
        }
    }

    /** Writes full typed data using the existing runtime, health, resources and configuration codecs. / 使用既有运行、健康、资源及配置codec写入完整类型化数据。
     * @param value complete reviewed description / 完整审阅描述
     * @return bounded canonical description / 有界规范描述
     * @throws SQLException if any original codec or bound rejects it / 任一原codec或边界拒绝时 */
    public static String encodeDescription(Description value) throws SQLException {
        try {
            var node = object().put("formatVersion", 1).put("applicationId", value.applicationId()).put("serverId", value.serverId())
                .put("reviewedRevision", value.reviewedRevision()).put("reviewedAt", value.reviewedAt().toString())
                .put("containerRisk", value.containerRisk()).put("adapterRisk", value.adapterRisk());
            textMap(node.putObject("source"), value.source());
            textMap(node.putObject("facts"), value.facts());
            textMap(node.putObject("inputs"), value.inputs());
            value.userAccessUrl().ifPresentOrElse(url -> node.put("userAccessUrl", url.url().toString()), () -> node.putNull("userAccessUrl"));
            var declarations = node.putArray("requirements");
            for (var declaration : value.requirements()) {
                var item = declarations.addObject().put("ecosystem", declaration.ecosystem().name()).put("declaration", declaration.declaration())
                    .put("source", declaration.source()).put("purpose", declaration.purpose().name()).put("constraint", declaration.constraint().name());
                declaration.version().ifPresentOrElse(version -> item.put("version", version.text()), () -> item.putNull("version"));
            }
            DeploymentPublishTemplateCodec.configuration(node.putObject("configuration"), value.configuration());
            var secrets = node.putArray("secrets");
            value.secrets().forEach(secret -> secrets.addObject().put("identifier", secret.identifier()).put("revision", secret.revision()));
            var steps = node.putArray("steps");
            value.steps().forEach(steps::add);
            node.put("runtime", Base64.getEncoder().encodeToString(new DeploymentRuntimeCodec().write(value.runtime())));
            node.put("health", Base64.getEncoder().encodeToString(new DeploymentHealthCodec().write(value.runtime().healthCheck())));
            node.put("resources", Base64.getEncoder().encodeToString(new DeploymentResourceCodec().write(value.resources())));
            var limits = node.putObject("limits").put("timeout", value.limits().timeoutSeconds()).put("processes", value.limits().maxProcesses())
                .put("memory", value.limits().maxMemoryMiB()).put("output", value.limits().maxOutputBytes()).put("workspace", value.limits().maxWorkspaceBytes());
            limits.put("root", false);
            return encode(node, LIMIT);
        } catch (java.io.IOException invalid) {
            throw new SQLException("Complete build description cannot be encoded", invalid);
        }
    }

    /** Reconstructs complete typed data and requires byte-for-byte canonical framing. / 重建完整类型化数据并要求逐字节规范信封。
     * @param text stored full description / 已存完整描述
     * @return exactly reconstructed description / 精确重建描述
     * @throws SQLException if fields, types, codecs or canonical identity conflict / 字段、类型、codec或规范身份冲突时 */
    public static Description decodeDescription(String text) throws SQLException {
        var node = parse(text, LIMIT);
        fields(node, Set.of("formatVersion", "applicationId", "serverId", "reviewedRevision", "reviewedAt", "containerRisk", "adapterRisk",
            "source", "facts", "requirements", "userAccessUrl", "inputs", "configuration", "secrets", "steps", "runtime", "health", "resources", "limits"));
        if (!node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1 || !node.path("reviewedAt").isTextual()
            || !node.path("containerRisk").isBoolean() || !node.path("adapterRisk").isBoolean())
            throw new SQLException("Invalid typed build description header");
        try {
            var health = new DeploymentHealthCodec().read(Base64.getDecoder().decode(string(node, "health")));
            var runtime = new DeploymentRuntimeCodec().read(Base64.getDecoder().decode(string(node, "runtime")), health);
            var resources = new DeploymentResourceCodec().read(Base64.getDecoder().decode(string(node, "resources")));
            var secrets = new ArrayList<DeploymentSecretRef>();
            for (var entry : array(node, "secrets", 4096)) {
                fields(entry, Set.of("identifier", "revision"));
                if (!entry.path("revision").isIntegralNumber() || !entry.path("revision").canConvertToLong())
                    throw new SQLException("Invalid secret revision");
                secrets.add(new DeploymentSecretRef(string(entry, "identifier"), entry.path("revision").longValue()));
            }
            var steps = new ArrayList<String>();
            for (var entry : array(node, "steps", 64)) {
                if (!entry.isTextual()) throw new SQLException("Invalid fixed stage");
                steps.add(entry.textValue());
            }
            var limits = node.path("limits");
            fields(limits, Set.of("timeout", "processes", "memory", "output", "workspace", "root"));
            for (String name : List.of("timeout", "processes", "memory"))
                if (!limits.path(name).isInt()) throw new SQLException("Invalid build limit");
            for (String name : List.of("output", "workspace"))
                if (!limits.path(name).isIntegralNumber() || !limits.path(name).canConvertToLong())
                    throw new SQLException("Invalid build byte limit");
            if (!limits.path("root").isBoolean() || limits.path("root").booleanValue())
                throw new SQLException("Invalid build root privilege");
            var value = new Description(string(node, "applicationId"), string(node, "serverId"), readMap(node.path("source")), readMap(node.path("facts")),
                requirements(node), node.path("userAccessUrl").isNull() ? Optional.empty() : Optional.of(new DeploymentAccessUrl(java.net.URI.create(string(node, "userAccessUrl")))),
                DeploymentPublishTemplateCodec.configuration(node.path("configuration"), string(node, "applicationId")), secrets, resources, runtime,
                new DeploymentBuildLimits(limits.path("timeout").intValue(), limits.path("processes").intValue(), limits.path("memory").intValue(), limits.path("output").longValue(), limits.path("workspace").longValue(), false),
                steps, readMap(node.path("inputs")), Instant.parse(string(node, "reviewedAt")), node.path("containerRisk").booleanValue(), node.path("adapterRisk").booleanValue(), string(node, "reviewedRevision"));
            if (!encodeDescription(value).equals(text))
                throw new SQLException("Noncanonical complete build description");
            return value;
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            throw new SQLException("Invalid complete typed build description", invalid);
        }
    }

    /** Reads complete typed declarations with the original precise version constraints. / 读取带原精确版本约束的完整类型化声明。
     * @param node original description / 原描述
     * @return copied exact declarations / 已复制精确声明
     * @throws SQLException if declarations are malformed / 声明无效时 */
    private static List<ToolchainRequirement> requirements(JsonNode node) throws SQLException {
        var result = new ArrayList<ToolchainRequirement>();
        for (var entry : array(node, "requirements", 64)) {
            fields(entry, Set.of("ecosystem", "declaration", "source", "purpose", "constraint", "version"));
            var ecosystem = DeploymentToolchainType.valueOf(string(entry, "ecosystem"));
            var version = entry.path("version").isNull() ? Optional.<DeploymentToolchainVersion>empty()
                : Optional.of(DeploymentToolchainVersion.parse(ecosystem, string(entry, "version")).orElseThrow());
            result.add(new ToolchainRequirement(ecosystem, string(entry, "declaration"), string(entry, "source"),
                ToolchainRequirement.PurposeType.valueOf(string(entry, "purpose")), ToolchainRequirement.ConstraintType.valueOf(string(entry, "constraint")), version));
        }
        return List.copyOf(result);
    }

    /** Persists the complete original graph needed to prove a single-child root transition. / 持久完整原图，以证明单子节点根转换。
     * @param value original graph framing / 原图分界
     * @return canonical bounded graph / 规范有界图
     * @throws SQLException if original health encoding fails / 原健康编码失败时 */
    public static String encodeGraph(DeploymentReviewedPlanDigest.GraphValues value) throws SQLException {
        try {
            var node = object().put("formatVersion", 1).put("application", value.applicationId()).put("healthOwner", value.healthOwner())
                .put("health", Base64.getEncoder().encodeToString(new DeploymentHealthCodec().write(value.health())));
            var order = node.putArray("order");
            value.order().forEach(order::add);
            textMap(node.putObject("namespaces"), value.namespaces());
            textMap(node.putObject("identities"), value.identities());
            var dependencies = node.putObject("dependencies");
            for (var id : new TreeSet<>(value.dependencies().keySet())) {
                var prior = dependencies.putArray(id);
                value.dependencies().get(id).forEach(prior::add);
            }
            return encode(node, 65536);
        } catch (java.io.IOException invalid) {
            throw new SQLException("Complete reviewed graph cannot be encoded", invalid);
        }
    }

    /** Reconstructs exact original graph order, health, units and child identities. / 重建精确原图顺序、健康、单元与子身份。
     * @param text original durable graph / 原持久图
     * @return complete canonical graph / 完整规范图
     * @throws SQLException if framing or a child binding conflicts / 分界或子绑定冲突时 */
    public static DeploymentReviewedPlanDigest.GraphValues decodeGraph(String text) throws SQLException {
        var node = parse(text, 65536);
        fields(node, Set.of("formatVersion", "application", "healthOwner", "health", "order", "namespaces", "identities", "dependencies"));
        if (!node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1 || !node.path("dependencies").isObject())
            throw new SQLException("Invalid complete reviewed graph header");
        try {
            var order = new ArrayList<String>();
            for (var id : array(node, "order", 16)) {
                if (!id.isTextual()) throw new SQLException("Invalid graph component");
                order.add(id.textValue());
            }
            var dependencies = new LinkedHashMap<String, List<String>>();
            var entries = node.path("dependencies").fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                var prior = new ArrayList<String>();
                if (!entry.getValue().isArray() || entry.getValue().size() > 16)
                    throw new SQLException("Invalid graph predecessors");
                for (var id : entry.getValue()) {
                    if (!id.isTextual()) throw new SQLException("Invalid graph predecessor");
                    prior.add(id.textValue());
                }
                dependencies.put(entry.getKey(), List.copyOf(prior));
            }
            var value = new DeploymentReviewedPlanDigest.GraphValues(string(node, "application"), string(node, "healthOwner"),
                new DeploymentHealthCodec().read(Base64.getDecoder().decode(string(node, "health"))), order,
                readMap(node.path("namespaces")), dependencies, readMap(node.path("identities")));
            if (!encodeGraph(value).equals(text)) throw new SQLException("Noncanonical original reviewed graph");
            return value;
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            throw new SQLException("Invalid complete reviewed graph", invalid);
        }
    }

    /** Builds exact shared writer inputs without live source handles or invented facts. / 不使用活动来源句柄或臆造事实地构建精确共用写入器输入。
     * @param application original unit / 原单元
     * @param server original server / 原服务器
     * @param source original source / 原来源
     * @param facts original hash facts / 原哈希事实
     * @param requirements original declarations / 原声明
     * @param url original URL / 原URL
     * @param configuration full configuration / 完整配置
     * @param secrets original revisions / 原修订
     * @param resources original resources / 原资源
     * @param runtime complete runtime / 完整运行定义
     * @param limits original limits / 原限额
     * @param at exact review time / 精确审阅时刻
     * @param container original choice / 原选择
     * @param adapter original choice / 原选择
     * @return complete non-authorizing hash values / 完整非授权哈希值 */
    private static DeploymentReviewedPlanDigest.Values hashValues(String application, String server, Map<String, String> source,
                                                                  Map<String, String> facts, List<ToolchainRequirement> requirements, Optional<DeploymentAccessUrl> url,
                                                                  DeploymentConfigSnapshot configuration, List<DeploymentSecretRef> secrets, DeploymentResourceBindings resources,
                                                                  DeploymentRuntimeDefinition runtime, DeploymentBuildLimits limits, Instant at, boolean container, boolean adapter) {
        var hashFacts = new LinkedHashMap<>(facts);
        hashFacts.put("applicationId", application);
        return new DeploymentReviewedPlanDigest.Values(server, UUID.fromString(source.get("snapshot")), source.get("manifest"), source.get("archive"),
            Long.parseLong(source.get("bytes")), Long.parseLong(source.get("uncompressedBytes")), hashFacts, requirements, configuration,
            secrets, resources, runtime, url, limits, at, container, adapter);
    }

    /** Encodes sorted bounded text fields. / 编码排序有界文本字段。
     * @param node target object / 目标对象
     * @param values original typed text / 原类型化文本 */
    private static void textMap(com.fasterxml.jackson.databind.node.ObjectNode node, Map<String, String> values) {
        new TreeMap<>(values).forEach(node::put);
    }

    /** Reads strict bounded text fields without coercion. / 不强制转换地读取严格有界文本字段。
     * @param node original object / 原对象
     * @return exact fields / 精确字段
     * @throws SQLException if type or bounds differ / 类型或边界不符时 */
    private static Map<String, String> readMap(JsonNode node) throws SQLException {
        if (!node.isObject() || node.size() > 16) throw new SQLException("Invalid build description text map");
        var result = new LinkedHashMap<String, String>();
        var entries = node.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (!entry.getValue().isTextual() || entry.getValue().textValue().length() > 4096)
                throw new SQLException("Invalid typed input");
            result.put(entry.getKey(), entry.getValue().textValue());
        }
        return Map.copyOf(result);
    }
}
