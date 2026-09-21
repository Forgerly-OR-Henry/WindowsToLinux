package gold.debug.windowstolinux.shared.analyze.component;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import gold.debug.windowstolinux.shared.model.project.component.ComponentIsolationSpecification;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * User-reviewed component boundaries supplied to deterministic mixed-project analysis.
 *
 *  <p>提供给确定性混合项目分析的用户审阅组件边界。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param relativeSourceRoot relative source root / 相对源码根目录
 * @param projectType supported project deployment category / 受支持的项目部署类别
 * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
 * @param artifactPaths application-root-relative artifact paths / 相对应用根的产物路径
 * @param ports bound host ports / 绑定的宿主机端口
 * @param configurationKeys declared non-secret configuration keys / 声明的非秘密配置键
 * @param secretIdentifiers opaque secret identifiers / 透明秘密标识符
 * @param dataPaths persistent-data contracts / 持久化数据契约
 * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
 * @param required whether the whole application requires this component / 整体应用是否需要此组件
 * @param isolation requested execution capabilities / 请求的执行能力
 */
public record ComponentAnalysisRequest(
        String componentId,
        String relativeSourceRoot,
        DeploymentProjectType projectType,
        Optional<DeploymentRuntimeSpecification> runtime,
        List<String> artifactPaths,
        Set<Integer> ports,
        List<String> configurationKeys,
        List<String> secretIdentifiers,
        List<ComponentDataPath> dataPaths,
        Set<String> dependencies,
        boolean required,
        ComponentIsolationSpecification isolation
) {
    /**
     * Validates a bounded explicit component selection without accessing source. / 在不访问源码的情况下验证有界显式组件选择。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param relativeSourceRoot relative source root / 相对源码根目录
     * @param projectType supported project deployment category / 受支持的项目部署类别
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
    public ComponentAnalysisRequest {
        componentId = identifier(componentId, "componentId");
        relativeSourceRoot = relativeRoot(relativeSourceRoot);
        projectType = Objects.requireNonNull(projectType, "projectType");
        runtime = Objects.requireNonNull(runtime, "runtime");
        if (projectType.deployable() != runtime.isPresent()) {
            throw new IllegalArgumentException("deployable component selections require a matching typed runtime");
        }
        if (runtime.isPresent() && runtime.orElseThrow().projectType() != projectType) {
            throw new IllegalArgumentException("selected component runtime must match its project type");
        }
        artifactPaths = List.copyOf(Objects.requireNonNull(artifactPaths, "artifactPaths"));
        ports = Set.copyOf(Objects.requireNonNull(ports, "ports"));
        configurationKeys = List.copyOf(Objects.requireNonNull(configurationKeys, "configurationKeys"));
        secretIdentifiers = List.copyOf(Objects.requireNonNull(secretIdentifiers, "secretIdentifiers"));
        dataPaths = List.copyOf(Objects.requireNonNull(dataPaths, "dataPaths"));
        dependencies = Set.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        isolation = Objects.requireNonNull(isolation, "isolation");
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
     * Checks relative root syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查相对根目录语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return relative root text / 相对根目录文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String relativeRoot(String value) {
        value = Objects.requireNonNull(value, "relativeSourceRoot").trim().replace('\\', '/');
        if (!(value.equals(".") || value.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*"))
                || value.startsWith("/") || value.contains("..") || value.contains("//")) {
            throw new IllegalArgumentException("relativeSourceRoot must stay inside the selected application root");
        }
        return value;
    }
}
