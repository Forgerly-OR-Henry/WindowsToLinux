package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.shared.model.project.*;
import gold.debug.windowstolinux.shared.model.health.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Resolves source-backed runtime defaults and validates the same domain objects as manual deployment. */
public final class AutomaticRuntimeResolver {
    /** Applies known declarations, without manufacturing unknown entry points or ports. */
    public Map<String, String> defaults(DeploymentProjectType type, DeploymentRuntimeAssessment suggested, Path root) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("healthMode", type == DeploymentProjectType.STATIC_SITE || type == DeploymentProjectType.SPRING_BOOT ? "HTTP" : "TCP");
        values.put("expectedStatus", "200"); values.put("timeout", "10"); values.put("stability", "5");
        values.put("containerEngine", "PODMAN");
        if (type == DeploymentProjectType.STATIC_SITE && Files.isRegularFile(root.resolve("package.json"))) values.put("nodeBuild", "true");
        if (suggested != null) {
            suggested.values().forEach((key, value) -> values.put(switch (key) {
                case JAVA_JAR_PATH, JAVA_SOURCE_ROOT, PYTHON_VERSION, STATIC_OUTPUT_DIRECTORY, SERVICE_ARTIFACT, CMAKE_ARTIFACT -> "primary";
                case JAVA_MAIN_CLASS, PYTHON_ENTRYPOINT, SERVICE_ENTRYPOINT, CMAKE_TARGET -> "secondary";
                case JAVA_VERSION, NODE_MAJOR_VERSION, SERVICE_VERSION, CMAKE_PRESET -> "version";
                case SERVICE_PORT -> "port";
            }, value));
            suggested.suggestedHealthPort().ifPresent(port -> values.put("port", port.toString()));
            if (!suggested.suggestedContainerPorts().isEmpty()) values.put("ports", suggested.suggestedContainerPorts().entrySet()
                    .stream().map(entry -> entry.getKey() + ":" + entry.getValue()).reduce((a, b) -> a + ";" + b).orElseThrow());
            if (!suggested.suggestedManagedVolumes().isEmpty()) values.put("volumes", suggested.suggestedManagedVolumes().stream()
                    .map(volume -> volume.name() + ":" + volume.containerPath() + (volume.readOnly() ? ":ro" : ":rw"))
                    .reduce((a, b) -> a + ";" + b).orElseThrow());
        }
        if (type == DeploymentProjectType.STATIC_SITE && Files.isRegularFile(root.resolve("index.html"))
                && !Files.exists(root.resolve("package.json"))) if (Files.isDirectory(root.resolve("public"))) values.put("primary", "public");
        return values;
    }

    /** Returns all missing fields together so a single dialog can collect them. */
    public List<DeploymentInputField> missing(String component, DeploymentProjectType type, Map<String, String> values) {
        List<String> required = new ArrayList<>(List.of("port"));
        switch (type) {
            case JAVA_JAR, JAVA_SOURCE, GO_SERVICE, RUST_SERVICE, DOTNET_SERVICE, KOTLIN_SERVICE,
                    PHP_SERVICE, RUBY_SERVICE, CMAKE_SERVICE -> required.addAll(List.of("primary", "secondary", "version"));
            case NODE_SERVICE -> required.add("version");
            case PYTHON_SERVICE -> required.addAll(List.of("primary", "secondary"));
            case STATIC_SITE -> { required.add("primary"); if (values.containsKey("nodeBuild")) required.add("version"); }
            case DOCKERFILE_CONTAINER -> required.add("ports");
            default -> { }
        }
        return required.stream().filter(key -> values.getOrDefault(key, "").isBlank())
                .map(key -> field(component, key, "", List.of())).toList();
    }

    /** Builds a bounded question descriptor for one runtime input. */
    public static DeploymentInputField field(String component, String key, String value, List<String> choices) {
        return new DeploymentInputField(component + "/" + key, "auto.field." + key, "auto.help." + key, value, choices);
    }

    /** Exposes every supplied runtime/configuration input that can fail local validation. */
    public List<DeploymentInputField> corrections(String component, Map<String, String> values) {
        Map<String, String> labels = Map.ofEntries(
                Map.entry("healthMode", "field.healthMode"), Map.entry("healthEndpoint", "field.healthEndpoint"),
                Map.entry("expectedStatus", "field.expectedStatus"), Map.entry("timeout", "field.timeout"),
                Map.entry("stability", "field.tcpStability"), Map.entry("accessUrl", "field.userAccessUrl"),
                Map.entry("configuration", "field.configurationEntries"), Map.entry("secrets", "field.secretReferences"),
                Map.entry("databaseMode", "field.databaseReviewMode"), Map.entry("databaseDetails", "field.databaseDetails"),
                Map.entry("containerEngine", "field.containerEngine"), Map.entry("volumes", "field.containerVolumes"),
                Map.entry("jvmArguments", "field.jvmArguments"), Map.entry("arguments", "field.applicationArguments"));
        List<DeploymentInputField> fields = new ArrayList<>();
        for (String key : List.of("port", "primary", "secondary", "version", "ports", "healthMode", "healthEndpoint",
                "expectedStatus", "timeout", "stability", "accessUrl", "configuration", "secrets", "databaseMode",
                "databaseDetails", "containerEngine", "volumes", "jvmArguments", "arguments")) {
            boolean missingDatabaseDetails = key.equals("databaseDetails") && values.containsKey("databaseMode")
                    && !Set.of("NONE", "UNREVIEWED").contains(values.get("databaseMode"));
            if (!values.containsKey(key) && !missingDatabaseDetails) continue;
            List<String> choices = switch (key) {
                case "healthMode" -> List.of("HTTP", "TCP");
                case "containerEngine" -> List.of("PODMAN", "DOCKER");
                case "databaseMode" -> Arrays.stream(DeploymentRuntimeParser.DatabaseReviewMode.values()).map(Enum::name).toList();
                default -> List.of();
            };
            fields.add(labels.containsKey(key) ? new DeploymentInputField(component + "/" + key,
                    labels.get(key), "help." + labels.get(key), values.getOrDefault(key, ""), choices)
                    : field(component, key, values.get(key), choices));
        }
        return List.copyOf(fields);
    }

    /** Converts the completed inputs to the existing typed runtime contract. */
    public DeploymentRuntimeSpecification runtime(DeploymentProjectType type, Map<String, String> v) {
        HealthCheck health = health(v);
        if (type == DeploymentProjectType.STATIC_SITE && !(health instanceof HealthCheck.Http))
            throw new IllegalArgumentException("static sites require HTTP health checks");
        String primary = v.getOrDefault("primary", ""), secondary = v.getOrDefault("secondary", ""), version = v.getOrDefault("version", "");
        return switch (type) {
            case SPRING_BOOT -> new DeploymentRuntimeSpecification.SpringBoot(health);
            case JAVA_JAR -> new DeploymentRuntimeSpecification.JavaJar(primary, secondary, version,
                    DeploymentRuntimeParser.arguments(v.getOrDefault("jvmArguments", "")), DeploymentRuntimeParser.arguments(v.getOrDefault("arguments", "")), health);
            case JAVA_SOURCE -> new DeploymentRuntimeSpecification.JavaSource(primary, secondary, version,
                    DeploymentRuntimeParser.arguments(v.getOrDefault("jvmArguments", "")), DeploymentRuntimeParser.arguments(v.getOrDefault("arguments", "")), health);
            case NODE_SERVICE -> new DeploymentRuntimeSpecification.NodeService(Integer.parseInt(version), health);
            case PYTHON_SERVICE -> new DeploymentRuntimeSpecification.PythonService(primary, secondary, health);
            case STATIC_SITE -> new DeploymentRuntimeSpecification.StaticSite(primary,
                    version.isBlank() ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(version)), (HealthCheck.Http) health);
            case DOCKERFILE_CONTAINER -> new DeploymentRuntimeSpecification.Container(
                    DeploymentRuntimeSpecification.ContainerEngineType.valueOf(v.getOrDefault("containerEngine", "PODMAN")),
                    DeploymentRuntimeParser.ports(v.getOrDefault("ports", "")), DeploymentRuntimeParser.volumes(v.getOrDefault("volumes", "")), health);
            case CMAKE_SERVICE -> new DeploymentRuntimeSpecification.CmakeService(version, secondary, primary, health);
            case GO_SERVICE, RUST_SERVICE, DOTNET_SERVICE, KOTLIN_SERVICE, PHP_SERVICE, RUBY_SERVICE ->
                    DeploymentRuntimeParser.service(type, version, primary, secondary, health);
            case RECOGNITION_PREVIEW -> throw new IllegalArgumentException("recognition preview cannot deploy");
        };
    }

    /** Matches the fixed build adapters' output contract, namespaced to the application root. */
    public List<String> artifacts(Path relativeRoot, DeploymentProjectFacts facts, Map<String, String> values) {
        String artifact = switch (facts.projectType()) {
            case NODE_SERVICE -> "package.json";
            case PYTHON_SERVICE -> ".venv";
            case SPRING_BOOT -> (facts.buildTool() == DeploymentBuildToolType.MAVEN || facts.buildTool() == DeploymentBuildToolType.MAVEN_WRAPPER) ? "target" : "build/libs";
            case STATIC_SITE, JAVA_JAR, GO_SERVICE, RUST_SERVICE, DOTNET_SERVICE, KOTLIN_SERVICE,
                    PHP_SERVICE, RUBY_SERVICE, CMAKE_SERVICE -> values.get("primary");
            case JAVA_SOURCE -> ".w2l/java/app.jar";
            case DOCKERFILE_CONTAINER -> "Dockerfile";
            case RECOGNITION_PREVIEW -> throw new IllegalArgumentException("preview has no artifact");
        };
        return List.of(relativeRoot.resolve(artifact).toString().replace('\\', '/'));
    }

    /** Builds a server-local health probe without claiming public reachability. */
    public HealthCheck health(Map<String, String> v) {
        int port = Integer.parseInt(v.get("port"));
        int timeout = Integer.parseInt(v.getOrDefault("timeout", "10"));
        String mode = v.getOrDefault("healthMode", "TCP");
        if (mode.equals("HTTP")) return new HealthCheck.Http(
                URI.create(v.getOrDefault("healthEndpoint", "http://127.0.0.1:" + port + "/")),
                Integer.parseInt(v.getOrDefault("expectedStatus", "200")), timeout);
        if (!mode.equals("TCP")) throw new IllegalArgumentException("unsupported health mode");
        return new HealthCheck.Tcp(port, timeout, Integer.parseInt(v.getOrDefault("stability", "5")));
    }

    /** Resolves an explicitly configured access URL or the direct server endpoint for an HTTP application. */
    public Optional<UserAccessUrl> access(Map<String, String> v, String host) {
        if (!v.getOrDefault("healthMode", "TCP").equals("HTTP")) {
            if (!v.getOrDefault("accessUrl", "").isBlank())
                throw new IllegalArgumentException("TCP services cannot declare an HTTP access URL");
            return Optional.empty();
        }
        String address = host.contains(":") ? "[" + host + "]" : host;
        return Optional.of(new UserAccessUrl(URI.create(v.getOrDefault("accessUrl", "http://" + address + ":" + v.get("port") + "/"))));
    }
}
