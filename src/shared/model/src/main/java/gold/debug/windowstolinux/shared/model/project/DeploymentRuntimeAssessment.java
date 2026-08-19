package gold.debug.windowstolinux.shared.model.project;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Source-backed, reviewable runtime values inferred without running project content.
 *
 * <p>在不运行项目内容的情况下推导出的、可审阅的源码依据运行时值。
 *
 * @param projectType the analyzed project type / 已分析的项目类型
 * @param values the type-specific suggested scalar values / 类型专属建议标量值
 * @param suggestedHealthPort the observed port suggestion when exactly one safe value exists / 恰有一个安全值时观察到的端口建议
 * @param suggestedContainerPorts the reviewed same-port container publication suggestions / 经审阅的同端口容器发布建议
 * @param suggestedManagedVolumes the source-declared managed-volume suggestions / 源码声明的受管卷建议
 * @param evidence the deterministic evidence for each suggestion / 每项建议的确定性证据
 * @param requiredUserInput unresolved values that remain an explicit human decision / 仍需人工明确决定的未解析值
 */
public record DeploymentRuntimeAssessment(
        DeploymentProjectType projectType,
        Map<RuntimeInputType, String> values,
        Optional<Integer> suggestedHealthPort,
        Map<Integer, Integer> suggestedContainerPorts,
        List<DeploymentRuntimeSpecification.ManagedVolume> suggestedManagedVolumes,
        List<AnalysisEvidence> evidence,
        List<LocalizedMessage> requiredUserInput
) {
    /** Creates a source-backed runtime suggestion. / 创建源码依据的运行时建议。 */
    public DeploymentRuntimeAssessment {
        projectType = Objects.requireNonNull(projectType, "projectType");
        DeploymentProjectType selectedType = projectType;
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
        values.forEach((input, value) -> {
            if (!allowedInputs(selectedType).contains(Objects.requireNonNull(input, "runtime input"))) {
                throw new IllegalArgumentException("runtime input does not match the analyzed project type");
            }
            if (value == null || value.isBlank() || value.length() > 255) {
                throw new IllegalArgumentException("runtime suggestion values must be bounded nonblank text");
            }
        });
        suggestedHealthPort = Objects.requireNonNull(suggestedHealthPort, "suggestedHealthPort");
        suggestedHealthPort.ifPresent(DeploymentRuntimeAssessment::requirePort);
        suggestedContainerPorts = Map.copyOf(Objects.requireNonNull(suggestedContainerPorts, "suggestedContainerPorts"));
        if (projectType != DeploymentProjectType.DOCKERFILE_CONTAINER && !suggestedContainerPorts.isEmpty()) {
            throw new IllegalArgumentException("only containers may suggest published ports");
        }
        suggestedContainerPorts.forEach((hostPort, containerPort) -> {
            requirePort(hostPort);
            requirePort(containerPort);
        });
        suggestedManagedVolumes = List.copyOf(Objects.requireNonNull(suggestedManagedVolumes, "suggestedManagedVolumes"));
        if (projectType != DeploymentProjectType.DOCKERFILE_CONTAINER && !suggestedManagedVolumes.isEmpty()) {
            throw new IllegalArgumentException("only containers may suggest managed volumes");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        requiredUserInput = List.copyOf(Objects.requireNonNull(requiredUserInput, "requiredUserInput"));
    }

    /** Returns one suggested scalar value when it was unambiguously observed. / 返回存在唯一观察结果时的一项建议标量值。 */
    public Optional<String> value(RuntimeInputType input) {
        return Optional.ofNullable(values.get(Objects.requireNonNull(input, "input")));
    }

    /** Creates a mutable builder map scoped to the selected type. / 创建限定于选定类型的可变构建映射。 */
    public static Map<RuntimeInputType, String> valuesFor(DeploymentProjectType projectType) {
        Objects.requireNonNull(projectType, "projectType");
        return new EnumMap<>(RuntimeInputType.class);
    }

    /** Stable scalar input names exposed by a typed source inference. / 类型化源码推导公开的稳定标量输入名称。 */
    public enum RuntimeInputType {
        /** Java JAR relative path. / Java JAR 相对路径。 */
        JAVA_JAR_PATH,
        /** Java binary main class. / Java 二进制主类。 */
        JAVA_MAIN_CLASS,
        /** Java major version. / Java 主版本。 */
        JAVA_VERSION,
        /** Node.js major version. / Node.js 主版本。 */
        NODE_MAJOR_VERSION,
        /** Python minor version. / Python 次版本。 */
        PYTHON_VERSION,
        /** Python module entrypoint. / Python 模块入口。 */
        PYTHON_ENTRYPOINT,
        /** Static-site generated output directory. / 静态站点生成输出目录。 */
        STATIC_OUTPUT_DIRECTORY,
        /** Ecosystem service language/toolchain version. / 生态服务语言或工具链版本。 */
        SERVICE_VERSION,
        /** Ecosystem service artifact or package name. / 生态服务产物或包名。 */
        SERVICE_ARTIFACT,
        /** Ecosystem service bounded entrypoint. / 生态服务有界入口。 */
        SERVICE_ENTRYPOINT,
        /** Ecosystem service fixed port. / 生态服务固定端口。 */
        SERVICE_PORT
    }

    private static EnumSet<RuntimeInputType> allowedInputs(DeploymentProjectType projectType) {
        return switch (projectType) {
            case SPRING_BOOT, DOCKERFILE_CONTAINER, RECOGNITION_PREVIEW -> EnumSet.noneOf(RuntimeInputType.class);
            case JAVA_JAR -> EnumSet.of(RuntimeInputType.JAVA_JAR_PATH, RuntimeInputType.JAVA_MAIN_CLASS,
                    RuntimeInputType.JAVA_VERSION);
            case NODE_SERVICE -> EnumSet.of(RuntimeInputType.NODE_MAJOR_VERSION);
            case PYTHON_SERVICE -> EnumSet.of(RuntimeInputType.PYTHON_VERSION, RuntimeInputType.PYTHON_ENTRYPOINT);
            case STATIC_SITE -> EnumSet.of(RuntimeInputType.STATIC_OUTPUT_DIRECTORY, RuntimeInputType.NODE_MAJOR_VERSION);
            case GO_SERVICE, RUST_SERVICE, DOTNET_SERVICE, KOTLIN_SERVICE ->
                    EnumSet.of(RuntimeInputType.SERVICE_VERSION, RuntimeInputType.SERVICE_ARTIFACT,
                            RuntimeInputType.SERVICE_ENTRYPOINT);
            case PHP_SERVICE, RUBY_SERVICE -> EnumSet.of(RuntimeInputType.SERVICE_VERSION,
                    RuntimeInputType.SERVICE_ARTIFACT, RuntimeInputType.SERVICE_ENTRYPOINT, RuntimeInputType.SERVICE_PORT);
        };
    }

    private static void requirePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("suggested ports must be in the TCP/UDP port range");
        }
    }
}
