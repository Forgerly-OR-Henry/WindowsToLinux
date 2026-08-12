package gold.debug.windowstolinux.shared.linux.sshd.protocol;

import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Translates one validated non-container runtime definition into helper-validated scalar arguments.
 *
 * <p>将一个已验证的非容器运行定义转换为辅助程序验证的标量参数。
 */
final class DeploymentRuntimeArguments {
    private DeploymentRuntimeArguments() {
    }

    static List<String> from(DeploymentRuntimeSpecification runtime) {
        runtime = Objects.requireNonNull(runtime, "runtime");
        return switch (runtime) {
            case DeploymentRuntimeSpecification.GradleSpringBoot ignored -> List.of("gradle");
            case DeploymentRuntimeSpecification.JavaJar javaJar -> javaArguments(javaJar);
            case DeploymentRuntimeSpecification.NodeService node -> List.of("node", Integer.toString(node.nodeMajorVersion()));
            case DeploymentRuntimeSpecification.PythonService python -> List.of("python", python.pythonVersion(), python.entrypoint());
            case DeploymentRuntimeSpecification.StaticSite staticSite -> List.of("static", staticSite.outputDirectory(),
                    Integer.toString(httpPort(staticSite)));
            case DeploymentRuntimeSpecification.Container ignored -> throw new IllegalArgumentException(
                    "container runtimes require the container-specific release protocol");
        };
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
