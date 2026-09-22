package gold.debug.windowstolinux.shared.model.project.component;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/**
 * Complete static record for one component inside a mixed project.
 *
 *  <p>混合项目中一个组件的完整静态记录。
 *
 * @param componentId stable component identifier / 稳定组件标识符
 * @param sourceRoot normalized component source root / 规范化组件源码根
 * @param facts deterministic language, framework, build, and support facts / 确定性语言、框架、构建与支持事实
 * @param runtime reviewed typed runtime when deployable / 可部署时经审阅的类型化运行时
 * @param artifactPaths application-root-relative artifact paths / 相对应用根的产物路径
 * @param ports bound host ports / 绑定的宿主机端口
 * @param configurationKeys declared non-secret configuration keys / 声明的非秘密配置键
 * @param secretIdentifiers opaque secret identifiers / 透明秘密标识符
 * @param dataPaths persistent-data contracts / 持久化数据契约
 * @param dependencies component identifiers that must start first / 必须先启动的组件标识符
 * @param required whether the whole application requires this component / 整体应用是否需要此组件
 * @param isolation requested execution capabilities / 请求的执行能力
 */
public record DeploymentComponent(String componentId, Path sourceRoot, DeploymentProjectFacts facts,
        Optional<DeploymentRuntimeSpecification> runtime, List<String> artifactPaths, Set<Integer> ports,
        List<String> configurationKeys, List<String> secretIdentifiers, List<ComponentDataPath> dataPaths,
        Set<String> dependencies, boolean required, ComponentIsolationSpecification isolation) {
    /**
     * Validates a complete bounded component record. / 验证完整有界组件记录。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param artifactPaths application-root-relative artifact paths / 相对应用根的产物路径
     * @param ports bound host ports / 绑定的宿主机端口
     * @param configurationKeys declared non-secret configuration keys / 声明的非秘密配置键
     * @param secretIdentifiers opaque secret identifiers / 透明秘密标识符
     * @param dataPaths persistent-data contracts / 持久化数据契约
     * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
     * @param required whether the whole application requires this component / 整体应用是否需要此组件
     * @param isolation requested execution capabilities / 请求的执行能力
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentComponent {
        componentId = identifier(componentId, "componentId");
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
        facts = Objects.requireNonNull(facts, "facts");
        if (!sourceRoot.equals(facts.sourceRoot())) {
            throw new IllegalArgumentException("component source root must match analyzed project facts");
        }
        runtime = Objects.requireNonNull(runtime, "runtime");
        if (runtime.isPresent() != facts.support().level().deployable()) {
            throw new IllegalArgumentException("runtime presence must match the component support level");
        }
        if (runtime.isPresent() && runtime.orElseThrow().projectType() != facts.projectType()) {
            throw new IllegalArgumentException("component runtime must match analyzed project facts");
        }
        artifactPaths = sortedDistinct(artifactPaths, DeploymentComponent::relativePath, "artifactPaths");
        if (runtime.isPresent() && artifactPaths.isEmpty()) {
            throw new IllegalArgumentException("deployable components require at least one bounded artifact path");
        }
        TreeSet<Integer> normalizedPorts = new TreeSet<>(Objects.requireNonNull(ports, "ports"));
        normalizedPorts.forEach(DeploymentComponent::requirePort);
        ports = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(normalizedPorts));
        configurationKeys = sortedDistinct(configurationKeys, DeploymentComponent::configurationKey,
                "configurationKeys");
        secretIdentifiers = sortedDistinct(secretIdentifiers, value -> identifier(value, "secret identifier"),
                "secretIdentifiers");
        dataPaths = List.copyOf(Objects.requireNonNull(dataPaths, "dataPaths"));
        if (dataPaths.stream().map(ComponentDataPath::path).distinct().count() != dataPaths.size()) {
            throw new IllegalArgumentException("component data paths must be unique");
        }
        dependencies = java.util.Collections.unmodifiableSet(
                new java.util.LinkedHashSet<>(new TreeSet<>(Objects.requireNonNull(dependencies, "dependencies")
                        .stream().map(value -> identifier(value, "dependency")).toList())));
        isolation = Objects.requireNonNull(isolation, "isolation");
        if (runtime.isPresent() && runtime.orElseThrow().healthCheck().portNumber().isPresent()
                && !ports.contains(runtime.orElseThrow().healthCheck().portNumber().orElseThrow())) {
            throw new IllegalArgumentException("component ports must include the health-check port");
        }
    }

    /**
     * Returns the declared health policy when the component is deployable. / 在组件可部署时返回声明的健康策略。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    public Optional<HealthCheck> healthCheck() {
        return runtime.map(DeploymentRuntimeSpecification::healthCheck);
    }

    /**
     * Validates an identifier against the bounded syntax of the owning contract.
     * <p>按所属契约的有界语法验证标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return identifier text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a bounded managed identifier");
        }
        return value;
    }

    /**
     * Validates a relative path before it is joined to the controlled root.
     * <p>在与受控根目录拼接前验证相对路径。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return relative path text / 相对路径文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String relativePath(String value) {
        value = Objects.requireNonNull(value, "artifact path").trim().replace('\\', '/');
        if (value.equals(".") || !value.matches("[A-Za-z0-9._/-]{1,255}") || value.startsWith("/")
                || value.contains("..") || value.contains("//")) {
            throw new IllegalArgumentException("artifact paths must be bounded and relative to the application root");
        }
        return value;
    }

    /**
     * Checks configuration key syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查配置键语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return configuration key text / 配置键文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String configurationKey(String value) {
        value = Objects.requireNonNull(value, "configuration key").trim();
        if (!value.matches("[A-Z][A-Z0-9_]{0,63}") || value.matches(".*_(PASSWORD|SECRET|TOKEN|KEY)$")) {
            throw new IllegalArgumentException("configuration keys must be non-secret environment identifiers");
        }
        return value;
    }

    /**
     * Requires network port number in the reviewed endpoint and rejects inputs outside the declared constraints.
     * <p>要求已审阅端点中的网络端口号并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void requirePort(int value) {
        if (value < 1 || value > 65535)
            throw new IllegalArgumentException("component ports must be valid ports");
    }

    /**
     * Validates and produces sorted distinct for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的已排序去重。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param normalizer normalizer / 规范化器
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static List<String> sortedDistinct(List<String> values,
            java.util.function.Function<String, String> normalizer, String name) {
        Objects.requireNonNull(values, name);
        List<String> normalized = values.stream().map(normalizer).sorted().toList();
        if (normalized.stream().distinct().count() != normalized.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
        return normalized;
    }
}
