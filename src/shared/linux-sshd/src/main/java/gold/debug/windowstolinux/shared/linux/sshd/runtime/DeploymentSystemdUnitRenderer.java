package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Objects;

/**
 * Renders the fixed systemd unit shape for non-container reviewed deployment types.
 *
 * <p>为非容器经审阅部署类型渲染固定的 systemd unit 形状。
 */
public final class DeploymentSystemdUnitRenderer {
    private DeploymentSystemdUnitRenderer() {
    }

    /**
     * Renders one type-specific unit without accepting a shell fragment.
     *
     * <p>渲染一个类型专属 unit，不接受 Shell 片段。
     */
    public static String render(String username, ManagedApplication application, DeploymentRuntimeSpecification runtime) {
        username = requireUser(username);
        application = Objects.requireNonNull(application, "application");
        runtime = Objects.requireNonNull(runtime, "runtime");
        if (runtime instanceof DeploymentRuntimeSpecification.Container) {
            throw new IllegalArgumentException("containers use their engine-specific managed runtime");
        }
        String root = application.releaseRoot();
        String command = switch (runtime) {
            case DeploymentRuntimeSpecification.GradleSpringBoot ignored -> "/usr/bin/java -jar " + root + "/current/app.jar";
            case DeploymentRuntimeSpecification.JavaJar javaJar -> javaCommand(root, javaJar);
            case DeploymentRuntimeSpecification.NodeService ignored -> "/usr/bin/npm --prefix " + root + "/current/source start";
            case DeploymentRuntimeSpecification.PythonService python -> root + "/current/source/.venv/bin/python -m " + python.entrypoint();
            case DeploymentRuntimeSpecification.StaticSite staticSite -> staticCommand(root, staticSite);
            case DeploymentRuntimeSpecification.Container ignored -> throw new IllegalStateException("handled above");
        };
        return """
                [Unit]
                Description=WindowsToLinux managed %s
                After=network.target

                [Service]
                Type=simple
                User=%s
                WorkingDirectory=%s/current/source
                ExecStart=%s
                Restart=on-failure
                RestartSec=5
                SuccessExitStatus=143

                [Install]
                WantedBy=multi-user.target
                """.formatted(application.id(), username, root, command);
    }

    private static String javaCommand(String root, DeploymentRuntimeSpecification.JavaJar runtime) {
        StringBuilder command = new StringBuilder("/usr/bin/java");
        runtime.jvmArguments().forEach(argument -> command.append(' ').append(argument));
        command.append(" -cp ").append(root).append("/current/app.jar ").append(runtime.mainClass());
        runtime.applicationArguments().forEach(argument -> command.append(' ').append(argument));
        return command.toString();
    }

    private static String staticCommand(String root, DeploymentRuntimeSpecification.StaticSite runtime) {
        int port = runtime.healthCheck().endpoint().getPort();
        if (port < 1) {
            port = "https".equalsIgnoreCase(runtime.healthCheck().endpoint().getScheme()) ? 443 : 80;
        }
        return "/usr/bin/python3 -m http.server " + port + " --directory " + root + "/current/source/"
                + runtime.outputDirectory();
    }

    private static String requireUser(String username) {
        username = Objects.requireNonNull(username, "username").trim();
        if (!username.matches("[a-z_][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("username is not a supported Linux account name");
        }
        return username;
    }
}
