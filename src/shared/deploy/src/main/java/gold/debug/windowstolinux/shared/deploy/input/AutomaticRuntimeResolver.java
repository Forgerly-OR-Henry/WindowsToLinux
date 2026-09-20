package gold.debug.windowstolinux.shared.deploy.input;

import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.shared.model.project.*;
import gold.debug.windowstolinux.shared.model.health.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Resolves source-backed runtime defaults and validates the same domain objects as manual deployment. / 解析有源码依据的运行时默认值，并校验与手动部署相同的领域对象。 */
public final class AutomaticRuntimeResolver {
    /** Applies known declarations, without manufacturing unknown entry points or ports. / 应用已知声明，不臆造未知入口或端口。 */
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
                case KOTLIN_JVM_TARGET -> "jvmTarget";
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
        values.put("executionMode", "DAEMON");
        if (type != DeploymentProjectType.STATIC_SITE && type != DeploymentProjectType.SPRING_BOOT && !values.containsKey("port"))
            values.put("healthMode", "PROCESS");
        if (type == DeploymentProjectType.STATIC_SITE) values.put("exposure", "EXTERNAL");
        ApplicationDeclaration.read(root, values);
        return values;
    }

    /** Returns all missing fields together so a single dialog can collect them. / 集中返回所有缺失字段，便于一个对话框统一收集。 */
    public List<DeploymentInputField> missing(String component, DeploymentProjectType type, Map<String, String> values) {
        var completed = ApplicationDeclaration.completed(values);
        return missingCompleted(component, type, completed);
    }

    private List<DeploymentInputField> missingCompleted(String component, DeploymentProjectType type, Map<String, String> values) {
        List<String> required = new ArrayList<>();
        if (!values.containsKey("application.mode") && !values.containsKey("application.endpoints")) required.add("exposure");
        if (Set.of("HTTP", "TCP", "UDP").contains(values.getOrDefault("healthMode", "TCP"))) {
            required.add("port");
        }
        switch (type) {
            case SPRING_BOOT -> required.add("version");
            case JAVA_JAR, JAVA_SOURCE, GO_SERVICE, RUST_SERVICE, DOTNET_SERVICE, KOTLIN_SERVICE,
                    PHP_SERVICE, RUBY_SERVICE, CMAKE_SERVICE -> required.addAll(List.of("primary", "secondary", "version"));
            case NODE_SERVICE -> required.add("version");
            case PYTHON_SERVICE -> required.addAll(List.of("primary", "secondary"));
            case STATIC_SITE -> { required.add("primary"); if (values.containsKey("nodeBuild")) required.add("version"); }
            case DOCKERFILE_CONTAINER -> { if (!values.containsKey("application.mode") && required.contains("port")) required.add("ports"); }
            default -> { }
        }
        if (type == DeploymentProjectType.KOTLIN_SERVICE) required.add("jvmTarget");
        return required.stream().filter(key -> values.getOrDefault(key, "").isBlank())
                .map(key -> field(component, key, key.equals("exposure") && !values.containsKey("port")
                        && values.getOrDefault("healthMode", "PROCESS").equals("PROCESS") ? "INTERNAL" : "", List.of())).toList();
    }

    /** Builds a bounded question descriptor for one runtime input. / 为一个运行输入构建有界问题描述。 */
    public static DeploymentInputField field(String component, String key, String value, List<String> choices) {
        if (key.equals("executionMode")) choices = List.of("DAEMON", "ON_DEMAND");
        if (key.equals("exposure")) choices = List.of("EXTERNAL", "INTERNAL");
        return new DeploymentInputField(component + "/" + key, "auto.field." + key, "auto.help." + key, value, choices);
    }

    /** Exposes every supplied runtime/configuration input that can fail local validation. / 暴露所有可能在本地校验失败的已提供运行时或配置输入。 */
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
        for (String key : List.of("applicationDeclaration", "executionMode", "exposure", "port", "primary", "secondary", "version", "ports", "healthMode", "healthEndpoint",
                "expectedStatus", "timeout", "stability", "accessUrl", "configuration", "secrets", "databaseMode",
                "databaseDetails", "containerEngine", "volumes", "jvmArguments", "arguments", "jvmTarget")) {
            boolean missingDatabaseDetails = key.equals("databaseDetails") && values.containsKey("databaseMode")
                    && !Set.of("NONE", "UNREVIEWED").contains(values.get("databaseMode"));
            if (!values.containsKey(key) && !missingDatabaseDetails && !key.equals("applicationDeclaration")) continue;
            List<String> choices = switch (key) {
                case "healthMode" -> List.of("HTTP", "TCP", "UDP", "PROCESS", "COMMAND");
                case "containerEngine" -> List.of("PODMAN", "DOCKER");
                case "databaseMode" -> Arrays.stream(DatabaseReviewMode.values()).map(Enum::name).toList();
                default -> List.of();
            };
            fields.add(labels.containsKey(key) ? new DeploymentInputField(component + "/" + key,
                    labels.get(key), "help." + labels.get(key), values.getOrDefault(key, ""), choices)
                    : field(component, key, values.getOrDefault(key, ""), choices));
        }
        return List.copyOf(fields);
    }

    /** Converts the completed inputs to the existing typed runtime contract. / 将补齐的输入转换为现有类型化运行时契约。 */
    public DeploymentRuntimeSpecification runtime(DeploymentProjectType type, Map<String, String> v) {
        v = ApplicationDeclaration.completed(v);
        HealthCheck health = health(v);
        if (type == DeploymentProjectType.STATIC_SITE && !(health instanceof HealthCheck.Http))
            throw new IllegalArgumentException("static sites require HTTP health checks");
        String primary = v.getOrDefault("primary", ""), secondary = v.getOrDefault("secondary", ""), version = v.getOrDefault("version", "");
        DeploymentRuntimeSpecification result = switch (type) {
            case SPRING_BOOT -> new DeploymentRuntimeSpecification.SpringBoot(version, health);
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
            case KOTLIN_SERVICE -> new DeploymentRuntimeSpecification.KotlinService(version, primary, secondary,
                    v.getOrDefault("jvmTarget", ""), health);
            case GO_SERVICE, RUST_SERVICE, DOTNET_SERVICE, PHP_SERVICE, RUBY_SERVICE ->
                    DeploymentRuntimeParser.service(type, version, primary, secondary, health);
            case RECOGNITION_PREVIEW -> throw new IllegalArgumentException("recognition preview cannot deploy");
        };
        return result.withWorkload(ApplicationDeclaration.resolve(v));
    }

    /** Matches the fixed build adapters' output contract, namespaced to the application root. / 匹配固定构建适配器的输出契约，并以应用根目录限定命名空间。 */
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
        return List.of(relativeRoot.resolve(facts.buildDirectory()).resolve(artifact).toString().replace('\\', '/'));
    }

    /** Builds a server-local health probe without claiming public reachability. / 构建服务器本地健康探针，不声称已验证公网可达性。 */
    public HealthCheck health(Map<String, String> v) {
        v = ApplicationDeclaration.completed(v);
        int timeout = Integer.parseInt(v.getOrDefault("timeout", "10"));
        String mode = v.getOrDefault("healthMode", "TCP");
        if (v.getOrDefault("executionMode", "DAEMON").equals("ON_DEMAND") || mode.equals("COMMAND"))
            return new HealthCheck.Command(ApplicationDeclaration.command(v, "verification"), v.getOrDefault("application.expectedOutput", ""), timeout);
        if (mode.equals("PROCESS")) return new HealthCheck.Process(timeout, Integer.parseInt(v.getOrDefault("stability", "5")));
        int port = Integer.parseInt(v.get("port"));
        if (mode.equals("UDP")) return new HealthCheck.Udp(port, v.getOrDefault("requestHex", ""), v.getOrDefault("responseHex", ""),
                v.keySet().stream().anyMatch(key -> key.startsWith("application.verification."))
                    ? Optional.of(ApplicationDeclaration.command(v, "verification")) : Optional.empty(), timeout);
        if (mode.equals("HTTP")) return new HealthCheck.Http(
                URI.create(v.getOrDefault("healthEndpoint", "http://127.0.0.1:" + port + "/")),
                Integer.parseInt(v.getOrDefault("expectedStatus", "200")), timeout);
        if (!mode.equals("TCP")) throw new IllegalArgumentException("unsupported health mode");
        return new HealthCheck.Tcp(port, timeout, Integer.parseInt(v.getOrDefault("stability", "5")));
    }

    /** Resolves an explicitly configured access URL or the direct server endpoint for an HTTP application. / 为 HTTP 应用解析显式访问地址或服务器直连端点。 */
    public Optional<UserAccessUrl> access(Map<String, String> v, String host) {
        v = ApplicationDeclaration.completed(v);
        var external = ApplicationDeclaration.resolve(v).endpoints().stream()
                .filter(endpoint -> endpoint.exposure() == gold.debug.windowstolinux.shared.model.project.application.ApplicationEndpoint.ExposureType.EXTERNAL)
                .filter(endpoint -> endpoint.protocol() == gold.debug.windowstolinux.shared.model.project.application.ApplicationEndpoint.ProtocolType.HTTP
                    || endpoint.protocol() == gold.debug.windowstolinux.shared.model.project.application.ApplicationEndpoint.ProtocolType.HTTPS).findFirst();
        if (external.isEmpty()) {
            if (!v.getOrDefault("accessUrl", "").isBlank()) throw new IllegalArgumentException("An access URL requires a declared external HTTP endpoint");
            return Optional.empty();
        }
        var endpoint = external.orElseThrow();
        String address = host.contains(":") ? "[" + host + "]" : host;
        String url = endpoint.accessUrl().isEmpty() ? endpoint.protocol().name().toLowerCase(java.util.Locale.ROOT)
                + "://" + address + ":" + endpoint.hostPort() + "/" : endpoint.accessUrl();
        return Optional.of(new UserAccessUrl(URI.create(url)));
    }
}
