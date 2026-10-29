package gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;

/** Shared fixed OS dependency transaction; source consistency never grants permission. */
public final class DeploymentToolchainDependencies {
    public static final String OPERATION = "deployment.toolchain.dependencies.v1";
    public static final String PROFILE = "NATIVE_BUILD";
    private static final String ROOT = "/gold/debug/windowstolinux/mod/deployment/service/deploy/toolchain/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> PROFILES = List.of("PYTHON", "PHP", "RUBY", "NATIVE_BUILD", "HELPER_RUNTIME");
    private static final String PACKAGES_TEXT = resource("dependency-packages.json");
    private static final JsonNode PACKAGES = packages();
    public static final String BOOTSTRAP = "import sys;sys.dont_write_bytecode=True;code=sys.stdin.buffer.read();exec(compile(code,'<wtl-agent-toolchain>','exec'),{'__name__':'__main__','PROGRAM_SOURCE':code})";
    private static final String COMMAND_PREFIX = "/usr/bin/python3 -I -S -c " + quote(BOOTSTRAP) + " ";
    private static final Pattern ACTION = Pattern.compile("\\AWTL-SSH-COMMAND-1\\ntimeout=([^\\n]+)\\noutputLimitBytes=([0-9]+)\\nstdinBytes=([0-9]+)\\nstdinSha256=([0-9a-f]{64})\\nscript:\\n([\\s\\S]+)\\z");
    private static final List<String> DEPENDENCY_LOCATIONS = List.of("dependency_protected", "dependency_bytes", "dependency_profile",
        "dependency_registered", "dependency_tool", "dependency_run", "dependency_packages", "dependency_url", "dependency_key",
        "dependency_apt_hook_owners", "dependency_apt_binaries", "dependency_apt_options", "dependency_validate_dpkg",
        "dependency_apt_source_authentication", "dependency_apt_sources", "dependency_validate_apt", "dependency_dnf_config",
        "dependency_validate_dnf", "dependency_versions", "dependency_transaction", "dependency_frame", "dependency_remaining",
        "dependency_worker", "dependency_supervise", "dependency_unit_identity", "dependency_group", "dependency_main", "dependency_cli");
    private DeploymentToolchainDependencies() {}

    public static String dependencies() {
        return dependencies(DeploymentRemoteLayout.original());
    }

    public static String dependencies(DeploymentRemoteLayout layout) {
        String worker = workerSource(layout);
        try {
            return "DEPENDENCY_WORKER_SOURCE = " + JSON.writeValueAsString(worker) + "\n" + worker;
        } catch (IOException failure) {
            throw new IllegalStateException("Dependency worker source cannot be rendered", failure);
        }
    }

    private static String workerSource(DeploymentRemoteLayout layout) {
        Objects.requireNonNull(layout);
        try {
            return "import json\nDEPENDENCY_PACKAGES = json.loads(" + JSON.writeValueAsString(PACKAGES_TEXT)
                + ")\nDEPENDENCY_OPERATIONS_ROOT = " + JSON.writeValueAsString(operationsRoot(layout)) + "\n"
                + resource("dependency-lifecycle.py") + "\n" + resource("agent-toolchain-dependencies.py");
        } catch (IOException failure) {
            throw new IllegalStateException("Dependency source cannot be rendered", failure);
        }
    }

    public static String operationsRoot(DeploymentRemoteLayout layout) {
        return layout.paths().stateRoot() + "/dependency-operations";
    }

    public static String workerDigest(DeploymentRemoteLayout layout) {
        return sha256(workerSource(layout).getBytes(StandardCharsets.UTF_8));
    }

    public static String unit(String operation) {
        return "windowstolinux-dependency-" + identity(operation) + ".service";
    }

    public static List<String> frame(String profile, String operation, long seconds, ServerId server, String task) {
        if (!PROFILES.contains(profile) || seconds < 45 || seconds > 7200
            || !server.id().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
            || !UUID.fromString(task).toString().equals(task))
            throw new IllegalArgumentException("Original dependency budget/server/task required");
        return List.of(profile, identity(operation), Long.toString(seconds), server.id(), task);
    }

    /** A narrowed outer channel cannot execute a frame approved for a different total budget. */
    public static void requireTimeout(CommandRequest request) {
        var args = arguments(request.script());
        if (args.size() != 5 || !args.get(2).matches("[1-9][0-9]{0,3}") || request.timeout().getNano() != 0
            || !Long.toString(request.timeout().getSeconds()).equals(args.get(2))
            || request.timeout().getSeconds() < 45 || request.timeout().getSeconds() > 7200)
            throw new IllegalArgumentException("Dependency frame differs from final command timeout");
    }

    public static void requireOriginal(CommandRequest request, ServerId server, String task) {
        requireTimeout(request);
        var args = arguments(request.script());
        if (!args.equals(frame(args.getFirst(), args.get(1), request.timeout().getSeconds(), server, task)))
            throw new IllegalArgumentException("Dependency original server/task changed");
    }

    public static List<String> packages(String manager, DeploymentToolchainType ecosystem) {
        return packages(manager, ecosystem.name());
    }

    public static List<String> packages(String manager, String profile) {
        JsonNode names = PACKAGES.path(manager).path(profile);
        if (!names.isArray())
            throw new IllegalArgumentException("Unsupported dependency manager or ecosystem");
        var result = new java.util.ArrayList<String>();
        names.forEach(name -> result.add(name.textValue()));
        return List.copyOf(result);
    }

    public static String command(List<String> arguments) {
        return COMMAND_PREFIX + List.copyOf(arguments).stream().map(DeploymentToolchainDependencies::quote)
            .collect(java.util.stream.Collectors.joining(" "));
    }

    static List<String> arguments(String command) {
        if (command == null || !command.startsWith(COMMAND_PREFIX))
            throw new IllegalArgumentException("Agent program launcher changed");
        String tail = command.substring(COMMAND_PREFIX.length());
        var matcher = java.util.regex.Pattern.compile("'([^']*)'(?: |$)").matcher(tail);
        var result = new java.util.ArrayList<String>();
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() != cursor)
                throw new IllegalArgumentException("Noncanonical Agent program arguments");
            result.add(matcher.group(1));
            cursor = matcher.end();
        }
        if (result.isEmpty() || cursor != tail.length() || !command(result).equals(command))
            throw new IllegalArgumentException("Agent program command frame changed");
        return List.copyOf(result);
    }

    private static String quote(String value) {
        return "'" + Objects.requireNonNull(value).replace("'", "'\\''") + "'";
    }

    private static JsonNode packages() {
        try {
            JsonNode result = JSON.readTree(PACKAGES_TEXT);
            if (result.size() != 2 || !result.has("APT") || !result.has("DNF"))
                throw new IllegalStateException("Unexpected dependency managers");
            for (String manager : List.of("APT", "DNF")) {
                if (result.path(manager).size() != PROFILES.size())
                    throw new IllegalStateException("Unexpected dependency ecosystems");
                for (String ecosystem : PROFILES) {
                    var names = result.path(manager).path(ecosystem);
                    var unique = new java.util.HashSet<String>();
                    if (!names.isArray() || names.isEmpty() || names.size() > 24)
                        throw new IllegalStateException("Invalid dependency package collection");
                    for (var name : names)
                        if (!name.isTextual() || !name.textValue().matches("[a-z][a-z0-9+.-]{0,63}")
                            || !unique.add(name.textValue()))
                            throw new IllegalStateException("Invalid dependency package name");
                }
            }
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("Dependency manifest unreadable", failure);
        }
    }

    private static String resource(String name) {
        try (var input = DeploymentToolchainDependencies.class.getResourceAsStream(ROOT + name)) {
            if (input == null)
                throw new IllegalStateException("Agent toolchain program unavailable: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException failure) {
            throw new IllegalStateException("Agent toolchain program unreadable: " + name, failure);
        }
    }

    public static Set<ActionTarget> dependencyTargets(ServerId server, String profile) {
        Set<ActionTarget> targets = new LinkedHashSet<>();
        for (String manager : List.of("APT", "DNF")) {
            targets.add(target(server, "system-package-index", manager + ":configured-authenticated-sources"));
            targets.add(target(server, "system-package-transaction", manager + ":authenticated-dependency-closure:" + profile));
            for (String name : DeploymentToolchainDependencies.packages(manager, profile))
                targets.add(target(server, "system-package", name));
        }
        targets.add(target(server, "system-repository", "DNF:centos:crb:transaction-only"));
        return Set.copyOf(targets);
    }

    public static Set<ActionTarget> lifecycleTargets(ServerId server, String operation, DeploymentRemoteLayout layout) {
        String base = operationsRoot(layout) + "/" + identity(operation);
        var targets = new LinkedHashSet<ActionTarget>();
        targets.add(target(server, "systemd-unit", unit(operation)));
        targets.add(target(server, "directory", operationsRoot(layout)));
        targets.add(target(server, "directory", base));
        for (String file : List.of("manifest.json", "worker.py", "ready.json", "result.json", "receipt.json"))
            targets.add(target(server, "file", base + "/" + file));
        return Set.copyOf(targets);
    }

    public static Set<ActionTarget> dependencyTargets(ServerId server, String profile, String operation, DeploymentRemoteLayout layout) {
        var targets = new LinkedHashSet<>(dependencyTargets(server, profile));
        targets.addAll(lifecycleTargets(server, operation, layout));
        return Set.copyOf(targets);
    }

    private static ActionTarget target(ServerId server, String kind, String id) {
        return new ActionTarget(server.id(), kind, id);
    }

    public static boolean dependencyReceipt(String stdout, String ecosystem, String operation, long seconds,
                                             ServerId server, String task, DeploymentRemoteLayout layout) {
        String prefix = "TOOLCHAIN_DEPENDENCIES=";
        if (!stdout.startsWith(prefix) || !stdout.endsWith("\n") || stdout.indexOf('\n') != stdout.length() - 1)
            return false;
        try (var parser = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .createParser(stdout.substring(prefix.length()))) {
            JsonNode root = new ObjectMapper().readTree(parser);
            if (root == null || parser.nextToken() != null || !root.isObject() || root.size() != 5
                || !root.path("operation").isTextual() || !root.path("ecosystem").isTextual() || !root.path("manager").isTextual()
                || !operation.equals(root.path("operation").asText()) || !ecosystem.equals(root.path("ecosystem").asText()))
                return false;
            String manager = root.path("manager").asText();
            if (!List.of("APT", "DNF").contains(manager) || !root.path("packages").isObject())
                return false;
            var actual = new java.util.HashSet<String>();
            var fields = root.path("packages").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getValue().isTextual() || field.getValue().textValue().isBlank()
                    || field.getValue().textValue().length() > 256 || field.getValue().textValue().chars().anyMatch(Character::isISOControl))
                    return false;
                actual.add(field.getKey());
            }
            JsonNode life = root.path("lifecycle");
            if (!life.isObject() || life.size() != 13 || !life.path("formatVersion").isInt() || life.path("formatVersion").intValue() != 1
                || !life.path("unit").isTextual() || !life.path("invocationId").isTextual() || !life.path("workerSourceSha256").isTextual()
                || !life.path("server").isTextual() || !life.path("task").isTextual()
                || !unit(operation).equals(life.path("unit").asText()) || !life.path("invocationId").asText().matches("[a-f0-9]{32}")
                || life.path("invocationId").asText().equals("0".repeat(32))
                || !workerDigest(layout).equals(life.path("workerSourceSha256").asText())
                || !life.path("totalSeconds").isInt() || life.path("totalSeconds").intValue() != seconds
                || !life.path("runtimeSeconds").isInt() || life.path("runtimeSeconds").intValue() < 1
                || life.path("runtimeSeconds").intValue() > seconds - 30 || !life.path("stopSeconds").isInt() || life.path("stopSeconds").intValue() != 5
                || !life.path("reserveSeconds").isInt() || life.path("reserveSeconds").intValue() != 25
                || !server.id().equals(life.path("server").asText()) || !task.equals(life.path("task").asText())) return false;
            for (String proof : List.of("loadedCompletionObserved", "recursiveCgroupEmptyObserved", "stopCompleted"))
                if (!life.path(proof).isBoolean() || !life.path(proof).booleanValue()) return false;
            return seconds >= 45 && seconds <= 7200 && actual.equals(Set.copyOf(DeploymentToolchainDependencies.packages(manager, ecosystem)));
        } catch (java.io.IOException | IllegalArgumentException malformed) {
            return false;
        }
    }

    private static List<Map<String, Object>> sourceLocations(String body, List<String> required, String sourcePointer) {
        var definitions = Pattern.compile("(?m)^def ([A-Za-z_][A-Za-z0-9_]*)\\(").matcher(body);
        var offsets = new ArrayList<Integer>();
        var names = new ArrayList<String>();
        while (definitions.find()) {
            offsets.add(definitions.start());
            names.add(definitions.group(1));
        }
        var locations = new ArrayList<Map<String, Object>>();
        for (String name : required) {
            int position = names.indexOf(name);
            if (position < 0 || position != names.lastIndexOf(name))
                throw new IllegalArgumentException("Bundled Agent source location is missing or ambiguous: " + name);
            int start = offsets.get(position);
            int end = position + 1 < offsets.size() ? offsets.get(position + 1) : body.length();
            int entry = body.indexOf("\nif __name__ == '__main__':", start);
            if (entry >= 0 && entry < end) end = entry + 1;
            int first = Math.toIntExact(1 + body.substring(0, start).chars().filter(value -> value == '\n').count());
            int last = Math.toIntExact(first + body.substring(start, end).stripTrailing().chars().filter(value -> value == '\n').count());
            locations.add(Map.of("function", name, "sourcePointer", sourcePointer, "startLine", first, "endLine", last));
        }
        return List.copyOf(locations);
    }

    private static Set<ActionTarget> targets(JsonNode values) {
        if (!values.isArray())
            throw new IllegalArgumentException("Current targets must be an array");
        var result = new HashSet<ActionTarget>();
        for (var value : values) {
            if (!value.isObject() || value.size() != 3)
                throw new IllegalArgumentException("Current target shape changed");
            var target = new ActionTarget(text(value, "serverId"), text(value, "kind"), text(value, "id"));
            if (!result.add(target))
                throw new IllegalArgumentException("Duplicate current target");
        }
        return Set.copyOf(result);
    }

    private static String identity(String identity) {
        if (!identity.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("Original operation identity required");
        return identity;
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isTextual())
            throw new IllegalArgumentException("Missing textual Agent field: " + field);
        return value.textValue();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
    public static List<Map<String, Object>> dependencySourceLocations(String body, String pointer) {
        return sourceLocations(body, DEPENDENCY_LOCATIONS, pointer);
    }

    public static Map<String, Object> inspect(gold.debug.windowstolinux.appapi.task.action.ActionIntent intent,
            CommandRequest request, DeploymentRemoteLayout layout) {
        if (intent.serverIds().size() != 1 || intent.semantics().isEmpty())
            throw new IllegalArgumentException("One original dependency action required");
        var server = new ServerId(intent.serverIds().getFirst());
        if (!RemoteActions.command(server, intent.revision(), request).withSemantics(intent.semantics().orElseThrow()).equals(intent))
            throw new IllegalArgumentException("Dependency request identity changed");
        byte[] bytes = request.stdin();
        try {
            String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return inspect(JSON.valueToTree(Map.of("operation", intent.semantics().orElseThrow().operation(),
                "serverTargets", intent.serverIds(), "businessRevision", intent.revision(), "inputKind", "EXECUTABLE_SCRIPT",
                "completeExecutableInput", source, "exactAction", intent.exactAction(),
                "resourceTargets", intent.semantics().orElseThrow().targets())), layout, true);
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("Complete UTF-8 dependency source required", invalid);
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    /** Validates complete current source, canonical request and exact effect scope. */
    public static Map<String, Object> inspect(JsonNode current, DeploymentRemoteLayout layout, boolean common) {
        String operation = text(current, "operation");
        if (!(common ? OPERATION : "deployment.agent.toolchain.dependencies.v1").equals(operation))
            throw new IllegalArgumentException("Dependency operation changed");
        var servers = current.path("serverTargets");
        if (!servers.isArray() || servers.size() != 1 || !servers.get(0).isTextual())
            throw new IllegalArgumentException("One original server required");
        var server = new ServerId(servers.get(0).textValue());
        String revision = text(current, "businessRevision"), body = text(current, "completeExecutableInput");
        if (!revision.matches("[0-9a-f]{64}") || !text(current, "inputKind").equals("EXECUTABLE_SCRIPT")
            || !body.equals(dependencies(layout))) throw new IllegalArgumentException("Complete bundled dependency source required");
        String exact = text(current, "exactAction");
        var match = ACTION.matcher(exact);
        if (!match.matches()) throw new IllegalArgumentException("Dependency exact action incomplete");
        Duration timeout = Duration.parse(match.group(1));
        long outputLimit = Long.parseLong(match.group(2));
        if (timeout.getNano() != 0 || timeout.getSeconds() < 45 || timeout.getSeconds() > 7200 || outputLimit != 65536)
            throw new IllegalArgumentException("Dependency bounds changed");
        var arguments = arguments(match.group(5));
        if (arguments.size() != 5 || common && !arguments.getFirst().equals(PROFILE))
            throw new IllegalArgumentException("Dependency profile/frame changed");
        String profile = arguments.getFirst(), identity = identity(arguments.get(1));
        if (!arguments.equals(frame(profile, identity, timeout.getSeconds(), server, arguments.get(4))))
            throw new IllegalArgumentException("Dependency budget/server/task frame changed");
        var targets = dependencyTargets(server, profile, identity, layout);
        if (!targets.equals(targets(current.path("resourceTargets"))))
            throw new IllegalArgumentException("Dependency effect targets changed");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            String digest = sha256(bytes);
            if (!match.group(3).equals(Integer.toString(bytes.length)) || !digest.equals(match.group(4)))
                throw new IllegalArgumentException("Dependency exact stdin changed");
            try (var request = new CommandRequest(match.group(5), bytes, timeout, outputLimit)) {
                if (!RemoteActions.command(server, revision, request).exactAction().equals(exact))
                    throw new IllegalArgumentException("Dependency exact action is not canonical");
            }
            var facts = new LinkedHashMap<String, Object>();
            facts.put("originalRemoteLayout", Map.of("canonical", layout.canonical(), "sha256", layout.digest()));
            facts.put("sourcePointer", "/completeExecutableInput");
            facts.put("sourceKind", "COMPLETE_BUNDLED_INLINE_PYTHON");
            facts.put("phase", "dependencies");
            facts.put("profile", profile);
            facts.put("operationId", identity);
            facts.put("task", arguments.get(4));
            facts.put("serviceLifecycle", Map.of("unit", unit(identity), "manifest", operationsRoot(layout) + "/" + identity + "/manifest.json",
                "totalSeconds", timeout.getSeconds(), "maximumRuntimeSeconds", timeout.getSeconds() - 30,
                "stopSeconds", 5, "observationAndStartupReserveSeconds", 25,
                "workerSourceSha256", workerDigest(layout),
                "termination", "Type=exec root-owned transient service; independent RuntimeMaxSec, KillMode=control-group and SendSIGKILL. Completion requires loaded unit identity, actual recursive empty-cgroup evidence, exact stop and launcher wait. Missing cgroup or unconfirmed cancellation/disconnect remains UNKNOWN; no global apt/dpkg kill or replay."));
            facts.put("server", server.id());
            facts.put("businessRevision", revision);
            facts.put("inputMatchesRenderer", true);
            facts.put("launcherAndArgumentsMatch", true);
            facts.put("targetsMatchTypedArguments", true);
            facts.put("utf8Bytes", bytes.length);
            facts.put("lineCount", Math.toIntExact(body.lines().count()));
            facts.put("locations", dependencySourceLocations(body, "/completeExecutableInput"));
                facts.put("nativeTransaction", Map.of(
                    "evidenceKind", "CURRENT_SOURCE_AND_DECLARED_SCOPE_ONLY",
                    "requestedPackages", Map.of("APT", DeploymentToolchainDependencies.packages("APT", profile),
                        "DNF", DeploymentToolchainDependencies.packages("DNF", profile)),
                    "transactionTargets", targets.stream().filter(target -> target.kind().equals("system-package-transaction"))
                        .map(ActionTarget::id).sorted().toList(),
                    "effects", "Root package maintenance includes resolver-selected transitive dependencies, installation, upgrade, configuration, distribution maintainer scripts, triggers and registered OS hooks. Requested names are not the entire transaction.",
                    "osBaseline", "Existing OS interpreter and protected package database are bootstrap trust assumptions. Source-level tool/configuration guards are evaluated during execution; this request supplies no fixed OS binary SHA or observed download authentication result.",
                    "meaning", "Navigation and declared scope only. Inspect the current source and enforced authentication independently; these facts grant no permission and establish no execution result."));
            facts.put("bundledSourceSha256", digest);
            facts.put("stdinSha256", digest);
            facts.put("exactActionSha256", sha256(exact.getBytes(StandardCharsets.UTF_8)));
            facts.put("timeout", timeout.toString());
            facts.put("outputLimitBytes", Math.toIntExact(outputLimit));
            facts.put("meaning", "Product-derived current byte/frame/target consistency only; no safety verdict, installation result or execution permission. Review the entire current source and every reachable effect independently.");
            return Map.copyOf(facts);
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
}
