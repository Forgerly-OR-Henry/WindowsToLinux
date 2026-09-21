package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime;

import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Translates one validated non-container runtime definition into helper-validated scalar arguments.
 *
 *  <p>将一个已验证的非容器运行定义转换为辅助程序验证的标量参数。
 */
public final class DeploymentRuntimeArguments {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DeploymentRuntimeArguments() {
    }

    /**
     * Maps each supported deployment runtime variant into the fixed managed-helper argument contract.
     * <p>将每种受支持部署运行变体映射为固定受管 helper 参数契约。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static List<String> from(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime) {
        facts = Objects.requireNonNull(facts, "facts");
        runtime = Objects.requireNonNull(runtime, "runtime");
        return switch (runtime) {
            case DeploymentRuntimeSpecification.SpringBoot ignored -> List.of("springboot", facts.buildTool().name());
            case DeploymentRuntimeSpecification.JavaJar javaJar -> javaArguments(javaJar);
            case DeploymentRuntimeSpecification.JavaSource javaSource -> javaSourceArguments(javaSource);
            case DeploymentRuntimeSpecification.NodeService node ->
                    List.of("node", Integer.toString(node.nodeMajorVersion()), facts.buildTool().name());
            case DeploymentRuntimeSpecification.PythonService python ->
                    List.of("python", python.pythonVersion(), python.entrypoint(), facts.buildTool().name());
            case DeploymentRuntimeSpecification.StaticSite staticSite -> List.of("static", staticSite.outputDirectory(),
                    Integer.toString(httpPort(staticSite)), facts.buildTool().name());
            case DeploymentRuntimeSpecification.Container ignored -> throw new IllegalArgumentException(
                    "container runtimes require the container-specific release protocol");
            case DeploymentRuntimeSpecification.GoService service -> serviceArguments("go", service.version(), service.artifactName(), service.entrypoint(), null);
            case DeploymentRuntimeSpecification.RustService service -> serviceArguments("rust", service.version(), service.artifactName(), service.entrypoint(), null);
            case DeploymentRuntimeSpecification.DotNetService service -> serviceArguments("dotnet", service.version(), service.artifactName(), service.entrypoint(), null);
            case DeploymentRuntimeSpecification.KotlinService service -> List.of("kotlin", service.jvmTarget(), service.artifactName(),
                    service.entrypoint(), facts.buildTool().name());
            case DeploymentRuntimeSpecification.PhpService service -> serviceArguments(
                    facts.buildTool() == gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType.PHP_CLI
                            ? "phpcli" : "php", service.version(), service.artifactName(), service.entrypoint(), service.servicePort());
            case DeploymentRuntimeSpecification.RubyService service -> serviceArguments(
                    facts.buildTool() == gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType.RUBY_CLI
                            ? "rubycli" : "ruby", service.version(), service.artifactName(), service.entrypoint(), service.servicePort());
            case DeploymentRuntimeSpecification.CmakeService service ->
                    List.of("cmake", service.preset(), service.target(), service.artifactName());
        };
    }

    /**
     * Builds the fixed service argument sequence with an optional port value.
     * <p>构建包含可选端口值的固定服务参数序列。
     *
     * @param ecosystem ecosystem / 生态
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @return the fixed service argument sequence with an optional port value / 包含可选端口值的固定服务参数序列
     */
    private static List<String> serviceArguments(String ecosystem, String version, String artifact, String entrypoint,
                                                 Integer port) {
        List<String> values = new ArrayList<>(List.of(ecosystem, version, artifact, entrypoint));
        if (port != null) values.add(Integer.toString(port));
        return List.copyOf(values);
    }

    /**
     * Serializes the reviewed JAR path, main class and counted JVM/application arguments.
     * <p>序列化已审阅 JAR 路径、主类及带计数的 JVM 和应用参数。
     *
     * @param javaJar java jar / JavaJar 对应的输入或状态
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    private static List<String> javaArguments(DeploymentRuntimeSpecification.JavaJar javaJar) {
        List<String> values = new ArrayList<>();
        values.add("java");
        values.add(javaJar.jarRelativePath());
        values.add(javaJar.mainClass());
        values.add(Integer.toString(javaJar.jvmArguments().size()));
        values.addAll(javaJar.jvmArguments());
        values.add(Integer.toString(javaJar.applicationArguments().size()));
        values.addAll(javaJar.applicationArguments());
        return List.copyOf(values);
    }

    /**
     * Serializes the source-built Java artifact and counted JVM/application arguments.
     * <p>序列化由源码构建的 Java 制品及带计数的 JVM 和应用参数。
     *
     * @param javaSource java source / Java源码
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    private static List<String> javaSourceArguments(DeploymentRuntimeSpecification.JavaSource javaSource) {
        List<String> values = new ArrayList<>();
        values.add("javasource");
        values.add(".w2l/java/app.jar");
        values.add(javaSource.mainClass());
        values.add(Integer.toString(javaSource.jvmArguments().size()));
        values.addAll(javaSource.jvmArguments());
        values.add(Integer.toString(javaSource.applicationArguments().size()));
        values.addAll(javaSource.applicationArguments());
        return List.copyOf(values);
    }

    /**
     * Uses an explicit HTTP endpoint port or the scheme default of 443 for HTTPS and 80 otherwise.
     * <p>使用显式 HTTP 端点端口，或采用协议默认端口：HTTPS 为 443，其他为 80。
     *
     * @param staticSite static site / 静态Site
     * @return http port as a numeric result / HTTP端口的数值结果
     */
    private static int httpPort(DeploymentRuntimeSpecification.StaticSite staticSite) {
        int port = staticSite.healthCheck().endpoint().getPort();
        if (port >= 1) {
            return port;
        }
        return "https".equalsIgnoreCase(staticSite.healthCheck().endpoint().getScheme()) ? 443 : 80;
    }
}
