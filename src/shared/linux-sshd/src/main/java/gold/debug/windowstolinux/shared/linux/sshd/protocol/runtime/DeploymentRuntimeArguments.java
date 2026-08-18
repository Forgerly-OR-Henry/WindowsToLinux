package gold.debug.windowstolinux.shared.linux.sshd.protocol.runtime;

import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Translates one validated non-container runtime definition into helper-validated scalar arguments.
 *
 * <p>将一个已验证的非容器运行定义转换为辅助程序验证的标量参数。
 */
public final class DeploymentRuntimeArguments {
    private DeploymentRuntimeArguments() {
    }

    /** Performs the {@code from} operation. / 执行 {@code from} 操作。 */
    public static List<String> from(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime) {
        facts = Objects.requireNonNull(facts, "facts");
        runtime = Objects.requireNonNull(runtime, "runtime");
        return switch (runtime) {
            case DeploymentRuntimeSpecification.SpringBoot ignored -> List.of("springboot", facts.buildTool().name());
            case DeploymentRuntimeSpecification.JavaJar javaJar -> javaArguments(javaJar);
            case DeploymentRuntimeSpecification.NodeService node -> List.of("node", Integer.toString(node.nodeMajorVersion()));
            case DeploymentRuntimeSpecification.PythonService python -> List.of("python", python.pythonVersion(), python.entrypoint());
            case DeploymentRuntimeSpecification.StaticSite staticSite -> List.of("static", staticSite.outputDirectory(),
                    Integer.toString(httpPort(staticSite)));
            case DeploymentRuntimeSpecification.Container ignored -> throw new IllegalArgumentException(
                    "container runtimes require the container-specific release protocol");
            case DeploymentRuntimeSpecification.GoService service -> serviceArguments("go", service.version(), service.artifactName(), service.entrypoint(), null);
            case DeploymentRuntimeSpecification.RustService service -> serviceArguments("rust", service.version(), service.artifactName(), service.entrypoint(), null);
            case DeploymentRuntimeSpecification.DotNetService service -> serviceArguments("dotnet", service.version(), service.artifactName(), service.entrypoint(), null);
            case DeploymentRuntimeSpecification.KotlinService service -> serviceArguments("kotlin", service.version(), service.artifactName(), service.entrypoint(), null);
            case DeploymentRuntimeSpecification.PhpService service -> serviceArguments("php", service.version(), service.artifactName(), service.entrypoint(), service.servicePort());
            case DeploymentRuntimeSpecification.RubyService service -> serviceArguments("ruby", service.version(), service.artifactName(), service.entrypoint(), service.servicePort());
        };
    }

    private static List<String> serviceArguments(String ecosystem, String version, String artifact, String entrypoint,
                                                 Integer port) {
        List<String> values = new ArrayList<>(List.of(ecosystem, version, artifact, entrypoint));
        if (port != null) values.add(Integer.toString(port));
        return List.copyOf(values);
    }

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

    private static int httpPort(DeploymentRuntimeSpecification.StaticSite staticSite) {
        int port = staticSite.healthCheck().endpoint().getPort();
        if (port >= 1) {
            return port;
        }
        return "https".equalsIgnoreCase(staticSite.healthCheck().endpoint().getScheme()) ? 443 : 80;
    }
}
