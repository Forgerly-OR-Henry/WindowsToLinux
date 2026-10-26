package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCommand;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.DeploymentStandardRuntimeDraft;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs.Prompt;

/** Shares literal launch edits and the anticipated command in deployment reviews. / 部署审阅共用的字面启动编辑及预计命令。 */
public final class DeploymentLaunchSettings {
    private DeploymentLaunchSettings() {
    }

    public static Prompt edit(Prompt prompt, String port, String accessUrl, String entrypoint, String arguments) {
        var changes = new LinkedHashMap<String, String>();
        if (!port.equals(prompt.defaults().getOrDefault("port", ""))) {
            int number = Integer.parseInt(port);
            if (number < 1 || number > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
            changes.put("port", port);
        }
        if (!accessUrl.equals(prompt.defaults().getOrDefault("accessUrl", ""))) changes.put("accessUrl", accessUrl);
        var updated = DeploymentStandardRuntimeDraft.apply(prompt, changes);
        var values = new LinkedHashMap<>(updated.defaults());
        if (accessUrl.isBlank()) {
            values.put("accessUrl", "");
            values.replaceAll((key, value) -> key.matches("application\\.endpoint\\.[a-z0-9-]+\\.url") ? "" : value);
        }
        var command = new DeploymentCommand(entrypoint, DeploymentCommandText.parse(arguments));
        values.put("commandEntrypoint", command.entrypoint());
        values.put("commandArguments", DeploymentCommandText.format(command.arguments()));
        return new Prompt(prompt.componentId(), prompt.applicationId(), prompt.projectType(), values, updated.fields());
    }

    public static String preview(DeploymentProjectKind type, Map<String, String> values) {
        var workload = ApplicationSpec.resolve(values);
        var command = workload.command();
        String primary = values.getOrDefault("primary", ""), secondary = values.getOrDefault("secondary", "");
        var argv = new ArrayList<String>();
        if (!command.entrypoint().isBlank()) {
            argv.addAll(switch (type) {
                case PYTHON_SERVICE -> List.of("source/.venv/bin/python");
                case NODE_SERVICE -> List.of("node");
                case PHP_SERVICE -> List.of("php");
                case RUBY_SERVICE -> List.of("ruby");
                case DOTNET_SERVICE -> List.of("dotnet");
                case JAVA_JAR, JAVA_SOURCE, SPRING_BOOT, KOTLIN_SERVICE -> List.of("java", "-jar");
                default -> List.of();
            });
            argv.add("source/" + command.entrypoint());
        } else {
            String port = values.getOrDefault("port", "0");
            String bind = workload.endpoints().stream().filter(endpoint -> Integer.toString(endpoint.hostPort()).equals(port))
                .map(endpoint -> endpoint.bindAddress()).findFirst().orElse("0.0.0.0");
            argv.addAll(switch (type) {
                case SPRING_BOOT -> List.of("java", "-jar", "app.jar");
                case JAVA_JAR, JAVA_SOURCE -> javaCommand(values, secondary);
                case NODE_SERVICE -> switch (values.getOrDefault("buildTool", "NPM")) {
                    case "PNPM" -> List.of("pnpm", "--dir", "source", "start");
                    case "YARN" -> List.of("yarn", "--cwd", "source", "start");
                    default -> List.of("npm", "--prefix", "source", "start");
                };
                case PYTHON_SERVICE -> List.of("source/.venv/bin/python", "-m", secondary);
                case STATIC_SITE -> List.of("python", "-m", "http.server", port, "--directory", "source/" + primary);
                case GO_SERVICE, RUST_SERVICE, CMAKE_SERVICE -> List.of("source/.w2l/bin/" + primary);
                case DOTNET_SERVICE -> List.of("dotnet", "source/.w2l/dotnet/" + primary + ".dll");
                case KOTLIN_SERVICE -> List.of("java", "-cp", "source/.w2l/kotlin/lib/*", secondary);
                case PHP_SERVICE -> port.equals("0") || !values.containsKey("port")
                    ? List.of("php", "source/" + secondary)
                    : List.of("php", "-S", bind + ":" + port, "-t", "source/public", "source/" + secondary);
                case RUBY_SERVICE -> secondary.equals("config.ru") && !port.equals("0")
                    ? List.of("bundle", "exec", "rackup", "--server", "webrick", "--host", bind, "--port", port, "source/config.ru")
                    : primary.equals("bundle") ? List.of("bundle", "exec", "ruby", "source/" + secondary)
                        : List.of("ruby", "source/" + secondary);
                case DOCKERFILE_CONTAINER -> List.of("[container image entrypoint]");
                default -> List.of("[application entrypoint]");
            });
        }
        argv.addAll(command.arguments());
        return DeploymentCommandText.format(argv);
    }

    private static List<String> javaCommand(Map<String, String> values, String main) {
        var argv = new ArrayList<String>();
        argv.add("java");
        argv.addAll(DeploymentStandardInputParser.arguments(values.getOrDefault("jvmArguments", "")));
        argv.addAll(List.of("-cp", "app.jar", main));
        argv.addAll(DeploymentStandardInputParser.arguments(values.getOrDefault("arguments", "")));
        return argv;
    }
}
