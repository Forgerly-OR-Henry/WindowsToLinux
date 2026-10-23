package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPlan;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;

/** Explicit build recipe parameters do not replace the Agent's publication workload. / 明确构建配方参数不替代 Agent 发布工作负载。 */
final class DeploymentAgentRecipeRuntime {
    /** Prevents construction / 禁止构造。
     */
    private DeploymentAgentRecipeRuntime() {
    }

    /** Validates model-selected recipe parameters with existing runtime contracts. / 以现有运行合同验证模型选定配方参数。
     * @param facts current deterministic facts / 当前确定性事实
     * @param part current component plan / 当前组件计划
     * @param node explicit recipe parameters / 明确配方参数
     * @return existing typed recipe runtime / 现有类型化配方运行
     * @throws Exception invalid or unsupported recipe / 无效或不支持配方 */
    static DeploymentRuntimeDefinition resolve(DeploymentProjectFacts facts, DeploymentAgentPlan.Part part, JsonNode node) throws Exception {
        try {
            return resolveChecked(facts, part, node);
        } catch (IllegalArgumentException rejected) {
            String reason = rejected instanceof NumberFormatException ? "version must be the decimal major version required by this recipe"
                : Objects.requireNonNullElse(rejected.getMessage(), "Invalid recipe arguments");
            reason = gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentFeedback.safe("TOOL_prepare_tools", reason)
                .replaceAll("[\\p{Cntrl}]", " ");
            reason = reason.substring(0, Math.min(reason.length(), 200));
            String feedback = "CAPABILITY_NOT_APPLICABLE: recipe for " + facts.projectType() + " rejected:\n" + reason + "\nFields: "
                + gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol.recipeContract(facts.projectType());
            throw new IllegalArgumentException(feedback.substring(0, Math.min(feedback.length(), 1000)), rejected);
        }
    }

    private static DeploymentRuntimeDefinition resolveChecked(DeploymentProjectFacts facts, DeploymentAgentPlan.Part part, JsonNode node) throws Exception {
        DeploymentJsonFields.exact(node, Set.of("version", "primary", "secondary", "jvmTarget", "servicePort"));
        String version = text(node, "version"), primary = text(node, "primary"), secondary = text(node, "secondary");
        text(node, "jvmTarget");
        DeploymentJsonFields.number(node, "servicePort", 0, 65535);
        var health = part.runtime().healthCheck();
        DeploymentRuntimeDefinition result = switch (facts.projectType()) {
            case SPRING_BOOT -> new DeploymentRuntimeDefinition.SpringBoot(version, health);
            case JAVA_JAR ->
                new DeploymentRuntimeDefinition.JavaJar(primary, secondary, version, List.of(), List.of(), health);
            case JAVA_SOURCE ->
                new DeploymentRuntimeDefinition.JavaSource(primary, secondary, version, List.of(), List.of(), health);
            case NODE_SERVICE -> new DeploymentRuntimeDefinition.NodeService(Integer.parseInt(version), health);
            case PYTHON_SERVICE -> new DeploymentRuntimeDefinition.PythonService(primary, secondary, health);
            case STATIC_SITE -> {
                if (!(health instanceof DeploymentHealthCheck.Http http))
                    throw new IllegalArgumentException("Static recipe requires HTTP health");
                yield new DeploymentRuntimeDefinition.StaticSite(primary, version.isEmpty() ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(version)), http);
            }
            case GO_SERVICE -> new DeploymentRuntimeDefinition.GoService(version, primary, secondary, health);
            case RUST_SERVICE -> new DeploymentRuntimeDefinition.RustService(version, primary, secondary, health);
            case DOTNET_SERVICE -> new DeploymentRuntimeDefinition.DotNetService(version, primary, secondary, health);
            case KOTLIN_SERVICE ->
                new DeploymentRuntimeDefinition.KotlinService(version, primary, secondary, text(node, "jvmTarget"), health);
            case PHP_SERVICE ->
                new DeploymentRuntimeDefinition.PhpService(version, primary, secondary, DeploymentJsonFields.number(node, "servicePort", 0, 65535), health);
            case RUBY_SERVICE ->
                new DeploymentRuntimeDefinition.RubyService(version, primary, secondary, DeploymentJsonFields.number(node, "servicePort", 0, 65535), health);
            case CMAKE_SERVICE -> new DeploymentRuntimeDefinition.CmakeService(version, secondary, primary, health);
            case DOCKERFILE_CONTAINER -> {
                if (!(part.runtime() instanceof DeploymentRuntimeDefinition.Container container))
                    throw new IllegalArgumentException("Container recipe requires selected container backend");
                yield container;
            }
            default -> throw new IllegalArgumentException("CAPABILITY_NOT_APPLICABLE: unsupported recipe");
        };
        return result.withWorkload(part.runtime().workload());
    }

    /** Reads a bounded explicit recipe field / 读取有界明确配方字段。
     * @param node node contract input / node合同输入
     * @param key original indexed checkpoint key / 原索引检查点键
     * @return checked current result / 已校验当前结果
     */
    private static String text(JsonNode node, String key) {
        var value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().length() > 512 || value.textValue().indexOf('\0') >= 0)
            throw new IllegalArgumentException("Explicit recipe text required: " + key);
        return value.textValue();
    }
}
