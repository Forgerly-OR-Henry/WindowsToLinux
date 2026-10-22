package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;

/** Proves current bundled input consistency, without claiming safety, installation or permission. / 证明当前随产品输入的一致性，不宣称安全、安装成功或许可。 */
public final class DeploymentAgentToolchainInputEvidence {
    public static final String FIELD = "agentToolchainInlineInputBinding";

    private static final String PREFIX = "deployment.agent.toolchain.";

    private static final Set<String> OPERATIONS = Set.of(PREFIX + "dependencies.v1", PREFIX + "resolve.v1", PREFIX + "prepare.v1", PREFIX + "bind.v1");

    private static final Pattern ACTION = Pattern.compile("\\AWTL-SSH-COMMAND-1\\ntimeout=([^\\n]+)\\noutputLimitBytes=([0-9]+)\\nstdinBytes=([0-9]+)\\nstdinSha256=([0-9a-f]{64})\\nscript:\\n([\\s\\S]+)\\z");

    private static final List<String> CONTROLLER_LOCATIONS = List.of("resolution_validate", "resolution_decode", "official_url", "download", "extract",
        "agent_regular", "agent_tool", "agent_os", "agent_require_space", "agent_create", "agent_release_source", "agent_sandbox_properties", "agent_setup",
        "agent_unit_identity", "agent_ready_file", "agent_publish_ready", "agent_wait_ready", "agent_unit_stopped",
        "agent_lifecycle_failure", "agent_lifecycle_diagnostic", "agent_identity_reject", "agent_exception_facts", "agent_lifecycle_cause", "agent_valid_lifecycle_cause", "agent_execute", "agent_worker", "agent_vendor", "agent_freeze", "agent_stopped", "agent_scan", "agent_copy_tree", "agent_promote",
        "agent_detach", "agent_installation_key", "agent_installation_selection", "agent_record", "agent_prepare", "agent_prepare_selected", "agent_bind", "agent_main", "agent_cli");

    private static final List<String> DEPENDENCY_LOCATIONS = List.of("dependency_protected", "dependency_bytes", "dependency_profile",
        "dependency_registered", "dependency_tool", "dependency_run", "dependency_packages", "dependency_url", "dependency_key",
        "dependency_apt_hook_owners", "dependency_apt_binaries", "dependency_apt_options", "dependency_validate_dpkg",
        "dependency_apt_source_authentication", "dependency_apt_sources", "dependency_validate_apt", "dependency_dnf_config",
        "dependency_validate_dnf", "dependency_versions", "dependency_main", "dependency_cli");

    private DeploymentAgentToolchainInputEvidence() {
    }

    /** Product-derived bindings and the pinned source for installed invocations; no safety verdict or permission. / 产品派生绑定及已安装调用的固定源，不代表安全裁决或许可。 */
    public record Evidence(Map<String, Object> facts) {
        public Evidence {
            facts = Map.copyOf(facts);
        }
    }

    public static Optional<Evidence> select(JsonNode current) {
        return select(current, DeploymentRemoteLayout.original());
    }

    public static Optional<Evidence> select(JsonNode current, DeploymentRemoteLayout layout) {
        if (current.has(DeploymentAgentToolchainTracked.FIELD)) return current.path("operation").asText().startsWith(DeploymentAgentToolchainProgramStore.PREFIX)
            ? Optional.empty() : Optional.of(trackedEvidence(current, layout));
        String operation = current.path("operation").asText();
        if (!OPERATIONS.contains(operation)) {
            if (current.has(FIELD))
                throw new IllegalArgumentException("Inline binding has no matching Agent entrypoint");
            return Optional.empty();
        }
        String phase = operation.substring(PREFIX.length(), operation.length() - 3);
        var servers = current.path("serverTargets");
        if (!servers.isArray() || servers.size() != 1 || !servers.get(0).isTextual())
            throw new IllegalArgumentException("One original Agent server is required");
        var server = new ServerId(servers.get(0).textValue());
        String revision = text(current, "businessRevision");
        if (!revision.matches("[0-9a-f]{64}") || !"EXECUTABLE_SCRIPT".equals(text(current, "inputKind")))
            throw new IllegalArgumentException("Agent inline revision or input kind changed");
        String body = text(current, "completeExecutableInput");
        String expected = phase.equals("dependencies") ? DeploymentAgentToolchainPrograms.dependencies(layout)
            : phase.equals("resolve") ? DeploymentAgentToolchainPrograms.resolver(layout) : DeploymentAgentToolchainPrograms.controller(layout);
        boolean reuseExistingOnly = phase.equals("prepare") && body.equals(DeploymentAgentToolchainPrograms.existingController(layout));
        if (!body.equals(expected) && !reuseExistingOnly)
            throw new IllegalArgumentException("Current input differs from the bundled Agent program");
        String exact = text(current, "exactAction");
        var match = ACTION.matcher(exact);
        if (!match.matches())
            throw new IllegalArgumentException("Agent inline exact action is incomplete");
        Duration timeout = Duration.parse(match.group(1));
        long outputLimit = Long.parseLong(match.group(2));
        if (timeout.getSeconds() < 1 || timeout.getSeconds() > 7200 || timeout.getNano() != 0 || outputLimit != 65536)
            throw new IllegalArgumentException("Agent inline receipt bounds changed");
        var arguments = DeploymentAgentToolchainPrograms.arguments(match.group(5));
        String identity;
        String profile;
        Set<ActionTarget> targets;
        Map<String, String> resolvedRelease = Map.of();
        DeploymentResolvedToolchains decodedBinding = null;
        if (phase.equals("dependencies")) {
            if (arguments.size() != 2)
                throw new IllegalArgumentException("Dependency frame changed");
            profile = arguments.get(0);
            identity = identity(arguments.get(1));
            targets = DeploymentAgentToolchainCommands.dependencyTargets(server, profile);
        } else if (phase.equals("resolve")) {
            if (arguments.size() != 6 || !arguments.get(0).equals("resolve"))
                throw new IllegalArgumentException("Read-only release frame changed");
            var ecosystem = DeploymentToolchainType.valueOf(arguments.get(1));
            DeploymentAgentToolchainCommands.requirePreparation(ecosystem, arguments.get(2), arguments.get(3), seconds(arguments.get(4), 180), "-", layout);
            profile = ecosystem.name();
            identity = identity(arguments.get(5));
            targets = DeploymentAgentToolchainCommands.resolverTargets(server, ecosystem, arguments.get(2), arguments.get(3));
        } else if (phase.equals("prepare")) {
            if (arguments.size() != 8 || !arguments.get(0).equals("prepare"))
                throw new IllegalArgumentException("Preparation frame changed");
            var ecosystem = DeploymentToolchainType.valueOf(arguments.get(1));
            DeploymentAgentToolchainCommands.requirePreparation(ecosystem, arguments.get(2), arguments.get(3), seconds(arguments.get(4), 7200), arguments.get(5), layout);
            profile = ecosystem.name();
            identity = identity(arguments.get(6));
            resolvedRelease = DeploymentAgentToolchainRelease.decode(arguments.get(7), ecosystem, arguments.get(2), arguments.get(3), identity, layout);
            targets = DeploymentAgentToolchainCommands.controllerTargets(server, identity, layout);
        } else {
            if (arguments.size() != 4 || !arguments.get(0).equals("bind"))
                throw new IllegalArgumentException("Binding frame changed");
            decodedBinding = DeploymentAgentToolchainCommands.requireBinding(arguments.get(1), layout);
            seconds(arguments.get(2), 180);
            profile = "EXACT_TOOLCHAIN_BINDING";
            identity = identity(arguments.get(3));
            targets = DeploymentAgentToolchainCommands.controllerTargets(server, identity, layout);
        }
        if (!targets.equals(targets(current.path("resourceTargets"))))
            throw new IllegalArgumentException("Agent inline effect targets changed");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            String digest = sha256(bytes);
            if (!match.group(3).equals(Integer.toString(bytes.length)) || !digest.equals(match.group(4)))
                throw new IllegalArgumentException("Agent input differs from exact action stdin");
            try (var request = new CommandRequest(match.group(5), bytes, timeout, outputLimit)) {
                if (!RemoteActions.command(server, revision, request).exactAction().equals(exact))
                    throw new IllegalArgumentException("Agent exact action is not canonical");
            }
            var facts = new LinkedHashMap<String, Object>();
            facts.put("originalRemoteLayout", Map.of("canonical", layout.canonical(), "sha256", layout.digest()));
            facts.put("sourcePointer", "/completeExecutableInput");
            facts.put("sourceKind", "COMPLETE_BUNDLED_INLINE_PYTHON");
            facts.put("phase", phase);
            if (reuseExistingOnly) facts.put("reuseExistingOnly", true);
            if (reuseExistingOnly) facts.put("existingOnlyMeaning", "Before any operation volume creation, missing immutable selection is refused; existing record/source identity is rechecked before and after the original task-owned PROBE worker. No INSTALL fallback, dependency transaction or shared controller installation is authorized.");
            facts.put("profile", profile);
            if (decodedBinding != null) {
                facts.put("decodedBinding", bindingFacts(decodedBinding));
                facts.put("bindingDigestMeaning", "Decoded from this exact canonical bind argv. MANAGED selection sha256 is the downloaded archive SHA-256; the directory key hashes ecosystem, version, official digest and any fixed build profile suffix in agent_installation_key. The key is not an archive or tree digest. agent_record checks the canonical directory and compares archive SHA-256 to official_digest only when official_algorithm is sha256. SYSTEM selection identity follows agent_record's protected host-file checks. These are declared input and current implementation facts, not observed installation or permission.");
            }
            if (!resolvedRelease.isEmpty()) {
                facts.put("resolvedRelease", resolvedRelease);
                facts.put("releaseBindingMeaning", "Current successful canonical resolver receipt is bound into this exact preparation argv; download must match this fixed official digest. No new metadata lookup or silent substitution is permitted. This is asset consistency evidence, not signature or safety proof.");
            }
            facts.put("operationId", identity);
            facts.put("server", server.id());
            facts.put("businessRevision", revision);
            facts.put("inputMatchesRenderer", true);
            facts.put("launcherAndArgumentsMatch", true);
            facts.put("targetsMatchTypedArguments", true);
            facts.put("utf8Bytes", bytes.length);
            facts.put("lineCount", Math.toIntExact(body.lines().count()));
            if (phase.equals("prepare") || phase.equals("bind"))
                facts.put("locations", sourceLocations(body, CONTROLLER_LOCATIONS));
            if (phase.equals("dependencies")) {
                facts.put("locations", sourceLocations(body, DEPENDENCY_LOCATIONS));
                facts.put("nativeTransaction", Map.of(
                    "evidenceKind", "CURRENT_SOURCE_AND_DECLARED_SCOPE_ONLY",
                    "requestedPackages", Map.of("APT", DeploymentAgentToolchainPrograms.packages("APT", profile),
                        "DNF", DeploymentAgentToolchainPrograms.packages("DNF", profile)),
                    "transactionTargets", targets.stream().filter(target -> target.kind().equals("system-package-transaction"))
                        .map(ActionTarget::id).sorted().toList(),
                    "effects", "Root package maintenance includes resolver-selected transitive dependencies, installation, upgrade, configuration, distribution maintainer scripts, triggers and registered OS hooks. Requested names are not the entire transaction.",
                    "osBaseline", "Existing OS interpreter and protected package database are bootstrap trust assumptions. Source-level tool/configuration guards are evaluated during execution; this request supplies no fixed OS binary SHA or observed download authentication result.",
                    "meaning", "Navigation and declared scope only. Inspect the current source and enforced authentication independently; these facts grant no permission and establish no execution result."));
            }
            facts.put("bundledSourceSha256", digest);
            facts.put("stdinSha256", digest);
            facts.put("exactActionSha256", sha256(exact.getBytes(StandardCharsets.UTF_8)));
            facts.put("timeout", timeout.toString());
            facts.put("outputLimitBytes", Math.toIntExact(outputLimit));
            facts.put("meaning", "Product-derived current byte/frame/target consistency only; no safety verdict, installation result or execution permission. Review the entire current source and every reachable effect independently.");
            return Optional.of(new Evidence(facts));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Recognizes only the pinned store entrypoint and the original durable action snapshot. */
    public static Optional<Evidence> selectInstalled(JsonNode current, DeploymentRemoteLayout layout, JsonNode receipts, String task) {
        if (current.has(DeploymentAgentToolchainTracked.FIELD)) {
            if (!current.path("operation").asText().startsWith(DeploymentAgentToolchainProgramStore.PREFIX)) return Optional.empty();
            requireTrackedCurrent(current, layout, receipts, task);
            return Optional.of(trackedEvidence(current, layout));
        }
        var recovery = storeRecovery(current, layout, receipts, task);
        if (recovery.isPresent()) return recovery;
        String operation = current.path("operation").asText();
        if (!Set.of("readiness", "install", "prepare", "bind").stream().anyMatch(phase -> operation.equals(DeploymentAgentToolchainProgramStore.PREFIX + phase + ".v1")))
            return Optional.empty();
        String phase = operation.substring(DeploymentAgentToolchainProgramStore.PREFIX.length(), operation.length() - 3);
        var servers = current.path("serverTargets");
        if (!servers.isArray() || servers.size() != 1 || !servers.get(0).isTextual()
            || !task.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("Original installed controller task/server required");
        var server = new ServerId(servers.get(0).textValue());
        String revision = text(current, "businessRevision"), exact = text(current, "exactAction");
        var match = ACTION.matcher(exact);
        if (!revision.matches("[a-f0-9]{64}") || !match.matches())
            throw new IllegalArgumentException("Original controller action frame required");
        Duration timeout = Duration.parse(match.group(1));
        long outputLimit = Long.parseLong(match.group(2));
        if (timeout.getNano() != 0 || timeout.getSeconds() < 1 || timeout.getSeconds() > 7200 || outputLimit != 65536)
            throw new IllegalArgumentException("Installed controller bounds changed");
        var pending = DeploymentAgentToolchainProgramState.current(receipts, task, server.id(), layout, sha256(exact.getBytes(StandardCharsets.UTF_8)));
        var index = pending.path("index");
        String action = identity(index.path("operation").asText());
        if (!index.path("phase").asText().equals(phase.toUpperCase(java.util.Locale.ROOT))
            || !index.path("version").asText().equals(DeploymentAgentToolchainProgramStore.version(layout))
            || !index.path("sourceSha256").asText().equals(DeploymentAgentToolchainProgramStore.sourceSha256(layout))
            || !index.path("path").asText().equals(DeploymentAgentToolchainProgramStore.path(layout)))
            throw new IllegalArgumentException("Original controller checkpoint frame changed");
        String body = phase.equals("install") ? text(current, "completeExecutableInput") : "";
        List<String> arguments;
        Map<String, ?> decoded = Map.of();
        if (phase.equals("install")) {
            if (!text(current, "inputKind").equals("EXECUTABLE_SCRIPT") || !body.equals(DeploymentAgentToolchainProgramStore.installer(layout)))
                throw new IllegalArgumentException("Complete pinned installer source required");
            arguments = DeploymentAgentToolchainPrograms.arguments(match.group(5));
            if (!arguments.equals(List.of(task, action)))
                throw new IllegalArgumentException("Original installer identity changed");
        } else {
            if (!text(current, "inputKind").equals("EMPTY"))
                throw new IllegalArgumentException("Installed invocation stdin must be empty");
            var frame = DeploymentAgentToolchainProgramStore.arguments(match.group(5), layout);
            if (!frame.subList(0, 3).equals(List.of(phase.equals("readiness") ? "readiness" : "invoke", task, action)))
                throw new IllegalArgumentException("Installed invocation task/action changed");
            arguments = frame.subList(3, frame.size());
            if (phase.equals("readiness")) {
                if (!arguments.isEmpty()) throw new IllegalArgumentException("Readiness cannot invoke controller");
            } else if (phase.equals("prepare")) {
                if (arguments.size() != 8 || !arguments.get(0).equals("prepare") || !arguments.get(6).equals(action))
                    throw new IllegalArgumentException("Installed preparation frame changed");
                var ecosystem = DeploymentToolchainType.valueOf(arguments.get(1));
                DeploymentAgentToolchainCommands.requirePreparation(ecosystem, arguments.get(2), arguments.get(3), seconds(arguments.get(4), 7200), arguments.get(5), layout);
                decoded = DeploymentAgentToolchainRelease.decode(arguments.get(7), ecosystem, arguments.get(2), arguments.get(3), action, layout);
            } else {
                if (arguments.size() != 4 || !arguments.get(0).equals("bind") || !arguments.get(3).equals(action))
                    throw new IllegalArgumentException("Installed binding frame changed");
                decoded = bindingFacts(DeploymentAgentToolchainCommands.requireBinding(arguments.get(1), layout));
                seconds(arguments.get(2), 180);
            }
        }
        String canonical = phase.equals("install") ? DeploymentAgentToolchainPrograms.command(arguments)
            : DeploymentAgentToolchainProgramStore.command(phase, task, action, arguments, layout);
        if (!canonical.equals(match.group(5)) || !DeploymentAgentToolchainProgramStore.targets(server, phase, action, action, layout).equals(targets(current.path("resourceTargets"))))
            throw new IllegalArgumentException("Installed controller launcher/targets changed");
        if (!phase.equals("readiness")) {
            var installation = pending.path("installation");
            String status = phase.equals("install") ? "MISSING" : "READY";
            boolean original = false;
            for (var row : receipts.path(DeploymentAgentToolchainProgramState.FIELD)) {
                var payload = row.path("payload");
                if (row.path("released").asBoolean() && payload.path("state").asText().equals("CONFIRMED")
                    && Set.of("READINESS", "INSTALL").contains(payload.path("index").path("phase").asText())
                    && payload.path("receipt").equals(installation)) original = true;
            }
            if (!original || !installation.path("status").asText().equals(status)
                || !installation.path("version").asText().equals(DeploymentAgentToolchainProgramStore.version(layout)))
                throw new IllegalArgumentException("Original confirmed store observation required");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            if (!match.group(3).equals(Integer.toString(bytes.length)) || !match.group(4).equals(sha256(bytes)))
                throw new IllegalArgumentException("Installed controller original stdin changed");
            try (var request = new CommandRequest(canonical, bytes, timeout, outputLimit)) {
                if (!RemoteActions.command(server, revision, request).exactAction().equals(exact))
                    throw new IllegalArgumentException("Noncanonical installed exact action");
            }
            var facts = new LinkedHashMap<String, Object>(DeploymentAgentToolchainProgramStore.description(layout));
            facts.put("phase", phase);
            facts.put("task", task);
            facts.put("server", server.id());
            facts.put("timeout", timeout.toString());
            facts.put("outputLimitBytes", outputLimit);
            facts.put("stdinBytes", bytes.length);
            facts.put("stdinSha256", sha256(bytes));
            facts.put("typedArguments", arguments);
            facts.put("decodedTypedInput", decoded);
            facts.put("originalAction", pending.deepCopy());
            facts.put("sourcePointer", phase.equals("install") ? "/completeExecutableInput" : "/exactAction");
            if (phase.equals("prepare") || phase.equals("bind")) {
                String source = DeploymentAgentToolchainPrograms.controller(layout);
                byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
                String sourceSha = sha256(sourceBytes);
                if (!sourceSha.equals(facts.get("sourceSha256")) || !sourceSha.equals(index.path("sourceSha256").asText())
                    || !Integer.valueOf(sourceBytes.length).equals(facts.get("sourceBytes")))
                    throw new IllegalArgumentException("Rendered installed source differs from its original binding");
                facts.put("controllerSource", Map.of("text", source, "sha256", sourceSha, "utf8Bytes", sourceBytes.length,
                    "evidencePointer", "/" + DeploymentAgentToolchainProgramStore.FIELD + "/controllerSource/text"));
            }
            facts.put("exactActionSha256", sha256(exact.getBytes(StandardCharsets.UTF_8)));
            return Optional.of(new Evidence(facts));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static Optional<Evidence> storeRecovery(JsonNode current, DeploymentRemoteLayout layout, JsonNode receipts, String task) {
        if (!current.path("operation").asText().equals(DeploymentAgentToolchainProgramStore.PREFIX + "readiness.v1")) return Optional.empty();
        String exact = text(current, "exactAction"), hash = sha256(exact.getBytes(StandardCharsets.UTF_8));
        var matches = new ArrayList<JsonNode>();
        for (var row : receipts.path(DeploymentAgentToolchainProgramState.FIELD)) {
            var history = row.path("recovery");
            if (!row.path("released").asBoolean() && row.path("payload").has("storeFrame") && history.isArray() && !history.isEmpty()) {
                var latest = history.get(history.size() - 1);
                if (latest.path("state").asText().equals("INTENT") && latest.path("verb").asText().equals("readiness")
                    && latest.path("exactActionSha256").asText().equals(hash)) matches.add(row);
            }
        }
        if (matches.isEmpty()) return Optional.empty();
        if (matches.size() != 1) throw new IllegalArgumentException("Ambiguous original store recovery");
        var original = matches.getFirst(); var payload = original.path("payload");
        DeploymentAgentToolchainProgramState.validateStoreFrame(payload);
        var frame = payload.path("storeFrame");
        if (!frame.path("task").asText().equals(task) || !frame.path("layout").asText().equals(layout.canonical())
            || !text(current, "inputKind").equals("EMPTY") || !current.path("serverTargets").isArray()
            || current.path("serverTargets").size() != 1 || !current.path("serverTargets").get(0).equals(frame.path("server")))
            throw new IllegalArgumentException("Foreign original store recovery");
        var match = ACTION.matcher(exact); if (!match.matches()) throw new IllegalArgumentException("Store observation exact action missing");
        var server = new ServerId(frame.path("server").asText()); String operation = frame.path("operation").asText();
        String script = DeploymentAgentToolchainProgramStore.command("readiness", task, operation, List.of(), layout);
        if (!DeploymentAgentToolchainProgramStore.targets(server, "readiness", operation, operation, layout).equals(targets(current.path("resourceTargets"))))
            throw new IllegalArgumentException("Store observation targets changed");
        try (var request = new CommandRequest(script, new byte[0], Duration.ofSeconds(30), 65536)) {
            if (!RemoteActions.command(server, text(current, "businessRevision"), request).exactAction().equals(exact))
                throw new IllegalArgumentException("Store observation must use the complete fixed read-only frame");
        }
        return Optional.of(new Evidence(Map.of("phase", "readiness", "originalStoreRecovery", true, "originalAction", original.deepCopy(),
            "fixedStore", DeploymentAgentToolchainProgramStore.description(layout), "meaning", "Read-only observation of the exact original version; never reinstall or delete shared stage")));
    }

    /** Reconstructs the entire hybrid or data request; executable bytes are never confused with recipe bytes. */
    public static Optional<Map<String, Object>> trackedFacts(String operation, CommandRequest request, DeploymentRemoteLayout layout) {
        boolean inline = operation.equals(PREFIX + "prepare.v1");
        boolean installed = List.of("prepare", "bind", "observe", "cleanup").stream()
            .anyMatch(phase -> operation.equals(DeploymentAgentToolchainProgramStore.PREFIX + phase + ".v1"));
        if (!inline && !installed) return Optional.empty();
        byte[] input = request.stdin();
        try {
            if (input.length == 0) return Optional.empty();
            byte[] source = inline ? DeploymentAgentToolchainPrograms.existingController(layout).getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (inline && input.length == source.length && Arrays.equals(input, source)) return Optional.empty();
            if (inline && (input.length <= source.length || !Arrays.equals(source, Arrays.copyOf(input, source.length))))
                throw new IllegalArgumentException("Existing inline tracked source changed");
            var recipe = DeploymentAgentToolchainTracked.decode(Arrays.copyOfRange(input, source.length, input.length));
            String phase = inline ? "prepare" : operation.substring(DeploymentAgentToolchainProgramStore.PREFIX.length(), operation.length() - 3);
            var facts = new LinkedHashMap<String, Object>();
            facts.put("recipeUtf8", DeploymentAgentToolchainTracked.utf8(recipe.bytes())); facts.put("verb", phase + "-tracked");
            facts.put("invocationKind", inline ? "EXISTING_INLINE" : "INSTALLED_LOADER");
            facts.put("executedSourceSha256", recipe.executedSourceSha256());
            return Optional.of(Map.copyOf(facts));
        } finally { Arrays.fill(input, (byte) 0); }
    }

    private static Evidence trackedEvidence(JsonNode current, DeploymentRemoteLayout layout) {
        var facts = current.path(DeploymentAgentToolchainTracked.FIELD);
        DeploymentAgentToolchainTracked.fields(facts, Set.of("recipeUtf8", "verb", "invocationKind", "executedSourceSha256"));
        var recipe = DeploymentAgentToolchainTracked.decode(text(facts, "recipeUtf8").getBytes(StandardCharsets.UTF_8));
        if (!Set.of("EXISTING_INLINE", "INSTALLED_LOADER").contains(text(facts, "invocationKind"))
            || !text(facts, "executedSourceSha256").equals(recipe.executedSourceSha256()))
            throw new IllegalArgumentException("Tracked invocation metadata changed");
        if (!recipe.layout().equals(layout) || !current.path("serverTargets").isArray() || current.path("serverTargets").size() != 1
            || !current.path("serverTargets").get(0).asText().equals(recipe.serverId())) throw new IllegalArgumentException("Tracked task server/layout changed");
        String verb = text(facts, "verb");
        boolean inline = text(facts, "invocationKind").equals("EXISTING_INLINE");
        String expectedOperation = inline ? PREFIX + "prepare.v1" : DeploymentAgentToolchainProgramStore.PREFIX + verb.replace("-tracked", "") + ".v1";
        if (!text(current, "operation").equals(expectedOperation) || !text(current, "inputKind").equals("TRACKED_TOOLCHAIN_REQUEST")
            || !recipe.version().equals(DeploymentAgentToolchainProgramStore.version(layout))
            || !recipe.sourceSha256().equals(DeploymentAgentToolchainProgramStore.sourceSha256(layout)))
            throw new IllegalArgumentException("Original tracked controller identity changed");
        var match = ACTION.matcher(text(current, "exactAction"));
        if (!match.matches()) throw new IllegalArgumentException("Complete tracked exact action required");
        Duration timeout = Duration.parse(match.group(1)); long outputLimit = Long.parseLong(match.group(2));
        if (timeout.getNano() != 0 || timeout.getSeconds() < 1 || timeout.getSeconds() > 7200 || outputLimit != 65536)
            throw new IllegalArgumentException("Tracked command bounds changed");
        byte[] input = inline ? DeploymentAgentToolchainPrograms.trackedInput(recipe) : recipe.bytes();
        String script = inline ? DeploymentAgentToolchainPrograms.trackedCommand(recipe) : DeploymentAgentToolchainProgramStore.trackedCommand(verb, recipe);
        var server = new ServerId(recipe.serverId());
        var expectedTargets = inline ? DeploymentAgentToolchainCommands.controllerTargets(server, recipe.operationId(), layout)
            : DeploymentAgentToolchainProgramStore.targets(server, verb.replace("-tracked", ""), recipe.operationId(), recipe.operationId(), layout);
        if (!expectedTargets.equals(targets(current.path("resourceTargets")))) throw new IllegalArgumentException("Tracked resource scope changed");
        try (var request = new CommandRequest(script, input, timeout, outputLimit)) {
            if (!RemoteActions.command(server, text(current, "businessRevision"), request).exactAction().equals(text(current, "exactAction")))
                throw new IllegalArgumentException("Tracked raw source/recipe/argv changed");
        } finally { Arrays.fill(input, (byte) 0); }
        var result = new LinkedHashMap<String, Object>();
        result.put("originalRecipe", recipe.value()); result.put("requestSha256", recipe.sha256()); result.put("verb", verb);
        result.put("originalController", DeploymentAgentToolchainProgramStore.description(layout));
        if (inline) {
            String source = DeploymentAgentToolchainPrograms.existingController(layout);
            if (!source.equals(text(current, "completeExecutableInput")) || !DeploymentAgentToolchainPrograms.sha256(source).equals(recipe.executedSourceSha256()))
                throw new IllegalArgumentException("Actual guarded source identity changed");
            result.put("controllerSource", source);
        } else result.put("controllerSource", DeploymentAgentToolchainPrograms.controller(layout));
        result.put("meaning", "Exact original bytes, identity and declared effects only; physical results and each fresh approval remain independent");
        return new Evidence(result);
    }

    public static void requireTrackedCurrent(JsonNode current, DeploymentRemoteLayout layout, JsonNode receipts, String task) {
        trackedEvidence(current, layout);
        var facts = current.path(DeploymentAgentToolchainTracked.FIELD);
        var recipe = DeploymentAgentToolchainTracked.decode(text(facts, "recipeUtf8").getBytes(StandardCharsets.UTF_8));
        if (!task.equals(recipe.taskId()) || !receipts.path(DeploymentAgentToolchainProgramState.FIELD).isArray())
            throw new IllegalArgumentException("Original tracked task evidence unavailable");
        String verb = text(facts, "verb"), exactSha = sha256(text(current, "exactAction").getBytes(StandardCharsets.UTF_8));
        var matching = new ArrayList<JsonNode>();
        for (var row : receipts.path(DeploymentAgentToolchainProgramState.FIELD))
            if (!row.path("released").asBoolean() && row.path("trackedRequest").equals(recipe.value())) matching.add(row);
        if (matching.size() != 1) throw new IllegalArgumentException("One original pending tracked action required");
        var original = matching.getFirst();
        if (Set.of("prepare-tracked", "bind-tracked").contains(verb)) {
            if (!original.path("payload").path("state").asText().equals("INTENT") || original.path("dispatched").asBoolean()
                || !original.path("index").path("requestSha256").asText().equals(exactSha))
                throw new IllegalArgumentException("Original tracked creation cannot replay");
            var installation = original.path("payload").path("installation");
            boolean confirmed = false;
            for (var row : receipts.path(DeploymentAgentToolchainProgramState.FIELD))
                if (row.path("released").asBoolean() && row.path("payload").path("state").asText().equals("CONFIRMED")
                    && Set.of("READINESS", "INSTALL").contains(row.path("index").path("phase").asText())
                    && row.path("payload").path("receipt").equals(installation)) confirmed = true;
            if (!confirmed || !installation.path("status").asText().equals("READY")) throw new IllegalArgumentException("Original ready controller proof missing");
        } else {
            var history = original.path("recovery");
            if (!history.isArray() || history.isEmpty() || !history.get(history.size() - 1).path("state").asText().equals("INTENT")
                || !history.get(history.size() - 1).path("verb").asText().equals(verb)
                || !history.get(history.size() - 1).path("exactActionSha256").asText().equals(exactSha))
                throw new IllegalArgumentException("Original tracked recovery request is not current");
        }
    }

    private static List<Map<String, Object>> sourceLocations(String body, List<String> required) {
        return sourceLocations(body, required, "/completeExecutableInput");
    }

    private static Map<String, Object> bindingFacts(DeploymentResolvedToolchains binding) {
        var selections = binding.selections().stream().map(selection -> {
            var requirement = selection.requirement();
            return Map.<String, Object>of("requirement", Map.of(
                    "ecosystem", requirement.ecosystem().name(), "purpose", requirement.purpose().name(),
                    "declaration", requirement.declaration(), "source", requirement.source(),
                    "constraint", requirement.constraint().name(),
                    "version", requirement.version().map(version -> version.text()).orElse("-")),
                "version", selection.version().text(), "directory", selection.directory(), "origin", selection.origin().name(),
                "source", selection.source(), "sha256", selection.sha256());
        }).toList();
        return Map.of("catalogRevision", binding.catalogRevision(), "bindingSha256", DeploymentToolchainBindingCodec.identity(binding),
            "selectionCount", selections.size(), "selections", selections);
    }

    /** Function navigation in the complete current dependency source, including a canonical installer literal. */
    public static List<Map<String, Object>> dependencySourceLocations(String body, String sourcePointer) {
        return sourceLocations(body, DEPENDENCY_LOCATIONS, sourcePointer);
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

    private static int seconds(String value, int maximum) {
        if (!value.matches("[1-9][0-9]{0,3}"))
            throw new IllegalArgumentException("Noncanonical program deadline");
        int seconds = Integer.parseInt(value);
        if (seconds < 30 || seconds > maximum)
            throw new IllegalArgumentException("Program deadline is outside its typed bounds");
        return seconds;
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
}
