package gold.debug.windowstolinux.shared.deploy.input;

import gold.debug.windowstolinux.shared.model.project.application.*;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Shared bounded application declaration and completion values. / 两端共用的有界应用声明与补填值。 */
public final class ApplicationDeclaration {
    public static final String FILE = "windowstolinux-application.properties";
    private ApplicationDeclaration() { }

    public static void read(Path root, Map<String, String> values) {
        Path file = root.resolve(FILE);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file) || Files.size(file) > 65536)
                throw new IllegalArgumentException("application declaration must be a bounded regular file");
            applyText(Files.readString(file), values);
        } catch (IOException failure) { throw new IllegalArgumentException("cannot read application declaration", failure); }
    }

    public static void applyText(String text, Map<String, String> values) {
        if (text.isBlank()) return;
        if (text.length() > 65536) throw new IllegalArgumentException("application declaration exceeds bounds");
        try {
            Properties declaration = new Properties() {
                @Override public synchronized Object put(Object key, Object value) {
                    if (containsKey(key)) throw new IllegalArgumentException("duplicate application declaration: " + key);
                    return super.put(key, value);
                }
            };
            declaration.load(new StringReader(text));
            if (!declaration.getProperty("version", "1").equals("1")) throw new IllegalArgumentException("unsupported application declaration version");
            declaration.stringPropertyNames().forEach(key -> {
                if (!key.matches("(?:version|mode|buildDirectory|projectType|source\\.include|runtime\\.(?:primary|secondary|version)|workingDirectory|endpoints|inputs|companions|workers|expectedOutput|health\\.(?:mode|port|timeout|stability|requestHex|responseHex|endpoint|expectedStatus)|(?:command|verification|client|worker\\.[a-z0-9-]+)\\.(?:entrypoint|arg\\.[0-9]+)|endpoint\\.[a-z0-9-]+\\.(?:protocol|bind|port|targetPort|exposure|url)|input\\.[a-z0-9-]+\\.(?:hostPath|accessPath)|companion\\.[a-z0-9-]+\\.(?:sourcePath|type|artifactPath|environment))"))
                    throw new IllegalArgumentException("unknown application declaration: " + key);
                String value = declaration.getProperty(key);
                if (value.contains("${") || value.contains("{{") || value.indexOf(0) >= 0)
                    throw new IllegalArgumentException("unresolved application declaration: " + key);
                values.put("application." + key, value);
            });
            for (String key : List.of("primary", "secondary", "version"))
                if (declaration.containsKey("runtime." + key)) values.put(key, declaration.getProperty("runtime." + key));
            if (declaration.containsKey("health.endpoint")) values.put("healthEndpoint", declaration.getProperty("health.endpoint"));
            if (declaration.containsKey("health.expectedStatus")) values.put("expectedStatus", declaration.getProperty("health.expectedStatus"));
            values.put("executionMode", declaration.getProperty("mode", "DAEMON"));
            values.put("healthMode", declaration.getProperty("health.mode",
                    values.get("executionMode").equals("ON_DEMAND") ? "COMMAND" : "PROCESS"));
            for (String key : List.of("port", "timeout", "stability", "requestHex", "responseHex"))
                if (declaration.containsKey("health." + key)) values.put(key, declaration.getProperty("health." + key));
        } catch (IOException failure) { throw new IllegalArgumentException("invalid application declaration", failure); }
    }

    public static ApplicationWorkload resolve(Map<String, String> values) {
        if (!values.containsKey("application.mode") && !values.containsKey("application.endpoints") && !values.containsKey("exposure"))
            throw new IllegalArgumentException("Confirm external service exposure or provide an application declaration");
        if (values.getOrDefault("exposure", "").equals("EXTERNAL") && !values.containsKey("application.endpoints")
                && !Set.of("HTTP", "HTTPS", "TCP", "UDP").contains(values.getOrDefault("healthMode", "PROCESS")))
            throw new IllegalArgumentException("External services require explicit endpoints and protocols");
        for (String resource : List.of("endpoint", "input", "companion", "worker")) {
            var declared = ids(values, resource + "s");
            for (String key : values.keySet()) {
                String prefix = "application." + resource + ".";
                if (key.startsWith(prefix) && !declared.contains(key.substring(prefix.length()).split("\\.", 2)[0]))
                    throw new IllegalArgumentException("application resource is not listed: " + key);
            }
        }
        var mode = ApplicationWorkload.ExecutionMode.valueOf(values.getOrDefault("executionMode", "DAEMON"));
        var endpoints = new ArrayList<ApplicationEndpoint>();
        for (String id : ids(values, "endpoints")) {
            String prefix = "endpoint." + id + ".";
            endpoints.add(new ApplicationEndpoint(id, ApplicationEndpoint.ProtocolType.valueOf(required(values, prefix + "protocol")),
                    get(values, prefix + "bind", "0.0.0.0"), integer(values, prefix + "port"),
                    Integer.parseInt(get(values, prefix + "targetPort", required(values, prefix + "port"))),
                    ApplicationEndpoint.ExposureType.valueOf(required(values, prefix + "exposure")), get(values, prefix + "url", "")));
        }
        if (endpoints.isEmpty() && !values.containsKey("application.mode") && values.containsKey("port")
                && Set.of("HTTP", "TCP", "UDP").contains(values.getOrDefault("healthMode", "TCP"))) {
            var protocol = ApplicationEndpoint.ProtocolType.valueOf(values.getOrDefault("healthMode", "TCP"));
            String exposure = values.getOrDefault("exposure", "INTERNAL");
            int port = Integer.parseInt(values.get("port"));
            var mappings = DeploymentRuntimeParser.ports(values.getOrDefault("ports", ""));
            if (mappings.isEmpty()) endpoints.add(new ApplicationEndpoint("service", protocol,
                    values.getOrDefault("bindAddress", "0.0.0.0"), port, port,
                    ApplicationEndpoint.ExposureType.valueOf(exposure), values.getOrDefault("accessUrl", "")));
            else mappings.forEach((host, target) -> endpoints.add(new ApplicationEndpoint("port-" + host, protocol,
                    values.getOrDefault("bindAddress", "0.0.0.0"), host, target,
                    ApplicationEndpoint.ExposureType.valueOf(exposure), host == port ? values.getOrDefault("accessUrl", "") : "")));
        }
        var inputs = new ArrayList<ApplicationInput>();
        for (String id : ids(values, "inputs")) inputs.add(new ApplicationInput(id,
                required(values, "input." + id + ".hostPath"), required(values, "input." + id + ".accessPath")));
        var companions = new ArrayList<ApplicationCompanion>();
        for (String id : ids(values, "companions")) companions.add(new ApplicationCompanion(id,
                required(values, "companion." + id + ".sourcePath"), ApplicationCompanion.BuildType.valueOf(required(values, "companion." + id + ".type")),
                required(values, "companion." + id + ".artifactPath"), required(values, "companion." + id + ".environment")));
        var workers = ids(values, "workers").stream().map(id -> new ApplicationWorker(id, command(values, "worker." + id))).toList();
        return new ApplicationWorkload(mode, true, command(values, "command"), get(values, "workingDirectory", ""), endpoints,
                optionalCommand(values, "verification"), get(values, "expectedOutput", ""), optionalCommand(values, "client"), inputs, companions, get(values, "buildDirectory", ""), workers);
    }

    public static Map<String, String> completed(Map<String, String> values) {
        var result = new LinkedHashMap<>(values);
        applyText(result.getOrDefault("applicationDeclaration", ""), result);
        return result;
    }

    /** Build ownership must match the source bundle that passed restricted static analysis. */
    public static void verifyBuildOwnership(gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts facts,
            gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification runtime) {
        var workload = runtime.workload();
        if (!facts.buildDirectory().equals(workload.buildDirectory()))
            throw new IllegalArgumentException("Build directory changed after source analysis; update the source declaration and reanalyze");
        var source = new LinkedHashMap<String, String>(); read(facts.sourceRoot(), source);
        if (!ids(source, "companions").equals(workload.companions().stream().map(ApplicationCompanion::id).toList())
                || !get(source, "buildDirectory", "").equals(workload.buildDirectory()))
            throw new IllegalArgumentException("Companion build ownership changed after source analysis; update the source declaration and reanalyze");
        for (var companion : workload.companions()) {
            String prefix = "companion." + companion.id() + ".";
            if (!companion.sourcePath().equals(get(source, prefix + "sourcePath", ""))
                    || !companion.artifactPath().equals(get(source, prefix + "artifactPath", ""))
                    || !companion.projectType().name().equals(get(source, prefix + "type", ""))
                    || !companion.environment().equals(get(source, prefix + "environment", "")))
                throw new IllegalArgumentException("Companion build declaration changed; reanalyze the source bundle");
        }
        for (var worker : workload.workers()) {
            Path entry = facts.sourceRoot().resolve(worker.command().entrypoint());
            try {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        || !entry.toRealPath().startsWith(facts.sourceRoot().toRealPath()))
                    throw new IllegalArgumentException("worker entrypoint must be a regular source file: " + worker.id());
            } catch (IOException failure) { throw new IllegalArgumentException("cannot inspect worker entrypoint: " + worker.id(), failure); }
        }
    }

    public static ApplicationCommand command(Map<String, String> values, String name) {
        var args = new ArrayList<String>();
        int count = 0;
        while (values.containsKey("application." + name + ".arg." + count)) args.add(values.get("application." + name + ".arg." + count++));
        final int size = count;
        if (values.keySet().stream().filter(key -> key.startsWith("application." + name + ".arg.")).count() != size)
            throw new IllegalArgumentException("application argument indices must be contiguous from zero");
        return new ApplicationCommand(get(values, name + ".entrypoint", ""), args);
    }
    private static Optional<ApplicationCommand> optionalCommand(Map<String, String> values, String name) {
        return values.keySet().stream().anyMatch(key -> key.startsWith("application." + name + "."))
                ? Optional.of(command(values, name)) : Optional.empty();
    }
    private static List<String> ids(Map<String, String> values, String field) {
        String value = get(values, field, ""); if (value.isBlank()) return List.of();
        List<String> ids = Arrays.stream(value.split(",", -1)).map(String::trim).toList();
        if (ids.size() > 32 || ids.stream().distinct().count() != ids.size()
                || ids.stream().anyMatch(id -> !id.matches("[a-z0-9][a-z0-9-]{0,62}")))
            throw new IllegalArgumentException("invalid application resource identifiers");
        return ids;
    }
    private static String get(Map<String, String> values, String key, String fallback) { return values.getOrDefault("application." + key, fallback); }
    private static String required(Map<String, String> values, String key) {
        String value = get(values, key, ""); if (value.isBlank()) throw new IllegalArgumentException("missing application declaration: " + key); return value;
    }
    private static int integer(Map<String, String> values, String key) { return Integer.parseInt(required(values, key)); }
}
