package gold.debug.windowstolinux.mod.deployment.ai.review;

import static gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentEvidencePages.sha256;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.ai.model.AiMessage;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiRole;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.ai.model.AiToolDefinition;
import gold.debug.windowstolinux.appapi.ai.model.AiToolResult;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;

/** Lossless projection of fixed command source. The original snapshot and request retain execution identity. */
public final class DeploymentCommandSourceEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROJECTION = "toolchainReviewEvidence";
    private static final String INSTALLER = "installerReviewEvidence";
    private static final String MIRROR_RUNTIME = "/mirrorTransport/runtimeSource";
    private static final String INSTALLED_HELPER_SOURCE = "/" + PROJECTION + "/installedHelperSource";
    private static final String BINDING_SELECTION = "/" + PROJECTION + "/bindingSelection";
    private static final String PREPARATION_SELECTION = "/" + PROJECTION + "/preparationSelection";
    private static final String PUBLIC_MIRROR_POLICY = "/mirrorTransport/frozenPolicy";
    private static final String PREPARE = "deployment.toolchain.prepare.v1";
    private static final String MIRROR_BOOTSTRAP_SHELL = "/" + PROJECTION + "/mirrorBootstrapShell";
    private static final String MIRROR_POLICY_SOURCE = "/" + PROJECTION + "/mirrorPolicySource";
    private static final String DEPENDENCY_WORKER = "/" + PROJECTION + "/dependencyWorker";
    private static final String BIND = "deployment.toolchain.bind.v1";
    private static final String BUILD = "deployment.build.run.v1";
    private static final String BUILD_PROJECTION = "buildReviewEvidence";
    private static final String BUILD_POLICY = "/" + BUILD_PROJECTION + "/stagedMirrorPolicy";
    private static final String BUILD_SHELL = "/" + BUILD_PROJECTION + "/mirrorBootstrapShell";
    private static final String BUILD_BODY = "/" + BUILD_PROJECTION + "/buildBody";
    private static final String RAW_PAGE = "WTL-COMMAND-EVIDENCE-PAGE-1\n";
    private static final String RETAINED_SOURCE = "WTL-COMMAND-RETAINED-SOURCE-1\n";
    private static final String RESPONSES = "OPENAI_RESPONSES";
    private static final Pattern NEXT_PYTHON_SECTION = Pattern.compile("(?m)^(?:def |class |if __name__ == )");
    private static final String WORKER_ASSIGNMENT = "DEPENDENCY_WORKER_SOURCE = ";
    private static final int MAX_READS_PER_RESPONSE = 8;
    private static final int MAX_CORRECTABLE_BATCH = 16;
    // Limits optional prior evidence only; valid ranges requested in the latest batch always stay visible.
    private static final int RETAINED_SOURCE_BYTES = 60 * 1024;
    private static final int PREPARE_RETAINED_SOURCE_BYTES = 4 * 1024;
    private static final int BIND_RETAINED_SOURCE_BYTES = 24 * 1024;
    private static final int MAX_RETAINED_READS = 16;
    private static final Set<String> OPERATIONS = Set.of(PREPARE, BIND,
        DeploymentToolchainDependencies.OPERATION);
    private static final Set<String> INSTALLERS = Set.of("deployment.helper.install.v1", "deployment.helper.install.files.v1",
        "deployment.helper.install.tracked.v1");
    private static final List<String> MIRROR_LOCATIONS = List.of("class MirrorPolicy:", "def validate_url(",
        "def validate_redirect(", "class MirrorRedirect(", "class LockedRepositoryTransport:",
        "class MirrorCredentialMaterial:", "def mirror_wrapper(", "BOOTSTRAP_SHELL = ",
        "def mirror_transfer(", "def _mirror_transfer(", "def mirror_system_command(", "class DnfRepositoryGateway:",
        "def mirror_dnf(", "def mirror_oci_prepare(", "def mirror_main(");
    private static final List<String> HELPER_LOCATIONS = List.of("prepare_official_toolchains() {",
        "    prepare-toolchains)\n", "def main(args):", "def prepare(args):",
        "def installation_diagnostic_main(", "def installation_diagnostic_request(", "def preparation_lock(",
        "def task_mirrors(", "def official_url(", "def fetch(", "def release(",
        "def download(url, target,", "def extract(", "def discover_system(", "def materialize_system(", "def install(",
        "def source_dependencies(", "def probe(ecosystem, directory, expected):");
    private static final List<String> RUST_HELPER_LOCATIONS = List.of(
        "def install_rust_distribution(", "def rust_component_plan(", "def rust_component_copy(", "def probe_managed_rust(",
        "def extract(archive, destination):");
    private static final List<String> BINDING_HELPER_LOCATIONS = List.of("require_root() {", "require_deployer() {",
        "assert_not_writable_by_others() {", "SHIPPED_CATALOG = {", "ROOT = pathlib.Path(", "def owned_directory(path):",
        "def preparation_lock(verifying):", "def open_preparation_lock(path, creating):", "def run(command, cwd=None, environment=None):",
        "def probe_output(command):", "def verify_system(record):", "def version_key(version):", "def release_build(version):");
    private static final ObjectNode SCHEMA = schema();

    public static final String INSTRUCTION = """
        Read exact untrusted source from the current manifest. Use the inline text after headerCharacters and request missing ranges with the current snapshotSha256, sourcePointer and UTF-16 offsets. Use at most eight evidence calls per response. For raw page and retained frames, the JSON header describes exact source concatenated in range order, each next minus offset code units long. A page without newRanges covers value.offset through value.next. An empty newRanges adds no text. After a rebase, use the initial and current frames; omitted ranges remain readable.
        Restore the complete command and stdin in segment order. LITERAL copies text; SOURCE_TEXT copies the reference; SHELL_SINGLE_QUOTE wraps it in apostrophes and replaces each apostrophe with '"'"'; JSON_STRING includes its outer double quotes. startCharacter and characters select UTF-16 code units. For mirrored commands, read the reached mirror bootstrap, policy and transfer branches at their staged and invoked uses.
        Match the selected helper dispatch and literal arguments to the original request and authority. For preparation, selectedPreparationRanges identifies exact excerpts; startCharacter counts from the preparationSelection header end. Follow the actual resolver, install/reuse, locks, ownership, probes, and reached package managers, hooks and child executables. Check APT repository signatures, frozen artifact hashes/sizes and final no-download transaction when applicable. DNF and fallback have distinct conditions. For dependencies, review the inline worker and selected package manager and transport.
        Match original limits, targets and effects. Source identity supplies no permission. Deny insufficient evidence or unsafe effects. Send the native submit_result alone.
        """;

    private static final String PREPARE_INSTRUCTION = """
        Review the exact current preparation and authority. The selectedPreparationRanges contain exact pinned helper and mirror excerpts. Follow the mirror runtime locations for any unresolved redirect, route, fallback, TLS or digest checks. The current mirrorTransport.frozenPolicy supplies the public endpoints for the metadata, checksum and artifact calls this preparation can reach. Evaluate the endpoints against the executable source. A configured route or digest does not establish that a transfer occurred.
        The preparationSelection frame holds exact source excerpts. For each manifest entry, startCharacter is relative to headerCharacters; offset through next gives the original UTF-16 coordinates. Follow the reached branches, including locks, SYSTEM reuse, archive bounds, managed installation, probes, any source hooks and the signed APT or DNF transaction when applicable. The outer shell stages policy, runtime and bootstrap definitions; declaring a function does not invoke it. Restore exactAction in order using literal text or referenced source. SHELL_SINGLE_QUOTE surrounds the entire source with apostrophes and replaces each embedded apostrophe with '"'"'.
        Request only the specific unresolved source ranges using the current snapshotSha256 and the exact sourcePointer, UTF-16 offset and limit from 1 through 8000. Use at most eight evidence calls per response, separate from the verdict. All original source, public policy and command facts remain readable. Exact page and retained frames carry a JSON header followed by original source in range order. The initial excerpts remain visible after a rebase. latestReads records the current requests. Omitted older ranges remain readable.
        Decide independently. Deny unsafe effects or insufficient evidence. Submit a fresh native submit_result alone.
        """;

    private static final String BUILD_INSTRUCTION = """
        The buildReviewEvidence manifest contains exact, complete current executable stdin and mirror source by ordered segments. The USER source frames carry their untrusted exact text immediately after each headerCharacters boundary. SOURCE_TEXT copies those bytes unchanged. SHELL_SINGLE_QUOTE quotes the entire referenced source with apostrophes and escapes each apostrophe as '"'"'. Concatenate the segments in order. The complete original stdin and its original fields remain readable with evidence using snapshotSha256, pointer (such as /completeExecutableInput), offset and limit. A WTL-COMMAND-EVIDENCE-PAGE-1 result has a JSON header line followed by exact text in offset/next order. When newRanges is present, other requested bytes are already in the visible frames or results. All offsets are UTF-16 code units.
        Review the fixed collector, stdin digest/limits, candidate/authority, non-root isolation, bootstrap setup, execution and cleanup, actual mirror routes and invoked runtime branches, and every application build byte. The buildBody frame contains the complete application build program, including project hooks, package managers and any nested input. Each frame and every literal segment contributes to the exact approved stdin. Digests and projection checks grant no trust or execution permission. Submit a fresh independent verdict; deny missing necessary evidence or unsafe effects.
        """;

    private static final String BIND_INSTRUCTION = """
        Review the literal current bind invocation, exact wrapper segments, decodedToolchainBinding and original authority. If currentCommandEvidence.currentToolchainBindingStage is supplied, compare its catalog selections and separately deferred native prerequisites to this bind. A staged binding neither satisfies a deferred prerequisite nor makes the candidate ready.
        The selectedRangeFrame supplies exact pinned bind dispatch, Python launch, locking, ownership, marker comparison, SYSTEM file rehash, prepared binary probes and binding write logic. For each selectedRanges entry, startCharacter counts from the frame header end and offset through next gives its original source coordinates. Review those excerpts and the actual selected arguments, including interpreter/child execution boundaries and marker trust.
        The mirrored outer command creates protected task-private files, stages policy/runtime/bootstrap data, defines fixed shell functions and cleans the private root. Check these effects in exactAction and the current policy. The literal bind branch runs the admitted probes from the existing installation and binds the recorded rows. Neither declaring a shell function nor staging its source invokes that function. Inspect additional branches only if the exact current command can reach them. A staged runtime or unrelated install branch does not establish what a selected tool binary does.
        Restore exactAction in ordered segments: LITERAL copies text, SOURCE_TEXT copies the referenced source, and SHELL_SINGLE_QUOTE encloses source in apostrophes and replaces every apostrophe with '"'"'. Every staged source remains fully available by its snapshotSha256 and sourcePointer. Use evidence only for specific unresolved ranges, at most eight calls per response and no verdict in the same response. Read UTF-16 offsets from selectedRanges, locations or a prior page's next; use existing visible bytes rather than requesting them twice.
        A WTL-COMMAND-EVIDENCE-PAGE-1 result and a WTL-COMMAND-RETAINED-SOURCE-1 USER frame contain a JSON header then exact source text in header order. Each range spans next minus offset UTF-16 code units. newRanges in a page omits bytes already in visible frames or results. The retained frame may omit older bytes; they remain available from the original snapshot. latestReads reports successful or rejected requests. If latestBatchRejected is present, no page from that oversized batch was read; resubmit only the missing ranges in groups of at most eight. Review current evidence independently; missing evidence or unsafe effects require DENY. Submit exactly one fresh native submit_result alone.
        """;

    public static final AiToolDefinition TOOL = new AiToolDefinition("evidence",
        "Read an original fact from this exact snapshot. At most eight evidence calls per response, separate from the verdict. An empty pointer selects all original facts. No execution or protected stdin access.",
        SCHEMA.toString());

    private final JsonNode current;
    private final String originalFacts;
    private final String snapshot;
    private final List<AiMessage> messages;
    private final Map<String, String> sourceBodies;
    private final Map<String, NavigableMap<Integer, Integer>> initialRanges;

    private DeploymentCommandSourceEvidence(JsonNode current, String originalFacts, String snapshot, List<AiMessage> messages,
                                              Map<String, String> sourceBodies, Map<String, NavigableMap<Integer, Integer>> initialRanges) {
        this.current = current;
        this.originalFacts = originalFacts;
        this.snapshot = snapshot;
        this.messages = List.copyOf(messages);
        this.sourceBodies = Map.copyOf(sourceBodies);
        this.initialRanges = Map.copyOf(initialRanges);
    }

    public List<AiMessage> messages() {
        return messages;
    }

    public static boolean supports(ActionIntent intent) {
        return intent.semantics().filter(value -> BUILD.equals(value.operation())
            || OPERATIONS.contains(value.operation()) || INSTALLERS.contains(value.operation())).isPresent();
    }

    public static Optional<DeploymentCommandSourceEvidence> project(String original, ActionIntent intent, CommandRequest request,
                                                                      DeploymentRemoteLayout layout) throws IOException {
        String operation = intent.semantics().orElseThrow().operation();
        if (INSTALLERS.contains(operation)) return Optional.of(installer(original, intent, request));
        if (BUILD.equals(operation)) return build(original, intent, request);
        if (!OPERATIONS.contains(operation)) return Optional.empty();
        var mirror = DeploymentMirrorTasks.preparation(request.script());
        boolean dependency = DeploymentToolchainDependencies.OPERATION.equals(operation);
        if (mirror.isEmpty() && !dependency) return Optional.empty();

        var current = (ObjectNode) JSON.readTree(original);
        String exact = requiredText(current, "exactAction");
        String script = request.script();
        if (!operation.equals(current.path("operation").asText()) || !intent.exactAction().equals(exact)
            || !exact.endsWith("\nscript:\n" + script) || current.has(PROJECTION))
            throw new IOException("Original toolchain evidence changed before projection");

        var projected = current.deepCopy();
        String snapshot = sha256(original);
        var manifest = projected.putObject(PROJECTION).put("snapshotSha256", snapshot);
        var frames = manifest.putArray("frames");
        var messages = new ArrayList<AiMessage>();
        var sourceBodies = new LinkedHashMap<String, String>();
        var initialRanges = new LinkedHashMap<String, NavigableMap<Integer, Integer>>();
        if (mirror.isPresent())
            projectMirror(projected, manifest, messages, snapshot, exact, mirror.orElseThrow().policy(), dependency, sourceBodies);
        if (dependency) {
            projectDependency(current, projected, manifest, messages, snapshot, exact, script, request, sourceBodies);
        } else {
            String helper = DeploymentManagedHelperBundle.renderScript(layout);
            boolean binding = BIND.equals(operation);
            var descriptor = pagedSource(INSTALLED_HELPER_SOURCE, helper, helperLocations(current, binding));
            if (!descriptor.path("sha256").asText().equals(requiredText(current, "installedHelperSha256")))
                throw new IOException("Review helper source differs from original pin");
            if (binding) {
                var bindingRanges = BindingSourceRange.select(helper);
                String selected = bindingSelection(descriptor, bindingRanges);
                sourceBodies.put(BINDING_SELECTION, selected);
                frames.add(sourceFrame(messages, snapshot, BINDING_SELECTION, selected));
                var supplied = new TreeMap<Integer, Integer>();
                for (var range : bindingRanges) DeploymentEvidencePages.remember(supplied, range.offset(), range.next());
                initialRanges.put(INSTALLED_HELPER_SOURCE, supplied);
            }
            manifest.set("installedHelperSource", descriptor);
            sourceBodies.put(INSTALLED_HELPER_SOURCE, helper);
            if (PREPARE.equals(operation)) {
                projectPreparationPolicy(current, projected);
                addPreparationSelection(current, manifest, messages, snapshot, sourceBodies, initialRanges);
            }
        }
        messages.add(new AiMessage(AiRole.USER, JSON.writeValueAsString(projected), List.of()));
        return Optional.of(new DeploymentCommandSourceEvidence(current, original, snapshot, messages, sourceBodies, initialRanges));
    }

    private static List<String> helperLocations(ObjectNode current, boolean binding) throws IOException {
        if (binding) return BINDING_HELPER_LOCATIONS;
        if (!preparationEcosystem(current).equals("RUST")) return HELPER_LOCATIONS;
        var locations = new ArrayList<>(HELPER_LOCATIONS);
        locations.addAll(RUST_HELPER_LOCATIONS);
        return locations;
    }

    private static void addPreparationSelection(ObjectNode current, ObjectNode manifest, List<AiMessage> messages,
                                                String snapshot, Map<String, String> sources,
                                                Map<String, NavigableMap<Integer, Integer>> initialRanges) throws IOException {
        boolean diagnosed = current.has("originalToolchainPreparation");
        String ecosystem = preparationEcosystem(current);
        var body = new StringBuilder();
        var selected = manifest.putArray("selectedPreparationRanges");
        String helper = sources.get(INSTALLED_HELPER_SOURCE);
        appendSelection(body, selected, initialRanges, INSTALLED_HELPER_SOURCE, helper,
            PreparationSourceRange.helper(helper, ecosystem, diagnosed));
        boolean packageManager = PreparationSourceRange.buildsFromSource(ecosystem);
        if (packageManager) {
            String shell = sources.get(MIRROR_BOOTSTRAP_SHELL);
            appendSelection(body, selected, initialRanges, MIRROR_BOOTSTRAP_SHELL, shell, PreparationSourceRange.apt(shell));
        }
        String runtime = sources.get(MIRROR_RUNTIME);
        boolean credentials = current.at(PUBLIC_MIRROR_POLICY + "/points").findValues("credentialManaged").stream()
            .anyMatch(JsonNode::asBoolean);
        appendSelection(body, selected, initialRanges, MIRROR_RUNTIME, runtime,
            PreparationSourceRange.transfer(runtime, ecosystem, credentials, packageManager));
        String source = body.toString();
        sources.put(PREPARATION_SELECTION, source);
        var frame = sourceFrame(messages, snapshot, PREPARATION_SELECTION, source);
        manifest.withArray("frames").add(frame);
        manifest.set("preparationSelection", frame);
    }

    private static void projectPreparationPolicy(ObjectNode original, ObjectNode projected) throws IOException {
        String ecosystem = preparationEcosystem(original);
        if (!(original.at(PUBLIC_MIRROR_POLICY) instanceof ObjectNode frozen) || !frozen.path("points").isObject())
            throw new IOException("Original public toolchain mirror policy unavailable");
        var selected = frozen.deepCopy();
        var points = (ObjectNode) selected.get("points");
        var required = new HashSet<>(Set.of(ecosystem + "_METADATA", switch (ecosystem) {
            case "JAVA" -> "JAVA_JDK";
            case "DOTNET" -> "DOTNET_SDK";
            case "RUST" -> "RUST_TOOLCHAIN";
            default -> ecosystem + "_DISTRIBUTION";
        }));
        if (ecosystem.equals("RUBY")) required.add("RUBYGEMS");
        if (PreparationSourceRange.buildsFromSource(ecosystem)) {
            for (var point : DownloadPoint.values())
                if (point.name().startsWith("APT_") || point.name().startsWith("DNF_")) required.add(point.name());
        }
        if (required.stream().anyMatch(name -> !points.path(name).isArray()))
            throw new IOException("Current preparation mirror point unavailable");
        var allPoints = new ArrayList<String>();
        points.fieldNames().forEachRemaining(allPoints::add);
        for (String name : allPoints)
            if (!required.contains(name)) points.remove(name);
        var mirror = (ObjectNode) projected.path("mirrorTransport");
        mirror.set("frozenPolicy", selected);
        mirror.set("completePublicPolicy", pagedSource(PUBLIC_MIRROR_POLICY, frozen.toString(), List.of()));
        mirror.put("frozenPolicyScope", "Current preparation download points with original endpoint order and complete endpoint fields.");
        mirror.put("meaning", "Selected public mirror data and exact runtime excerpts apply to this preparation. The original command uses the complete frozen policy, including its bound credential check. Complete policy and runtime source remain readable.");
    }

    private static String preparationEcosystem(ObjectNode current) throws IOException {
        String input = current.has("originalToolchainPreparation") ? "originalToolchainPreparation" : "toolchainPreparation";
        String ecosystem = current.path(input).path("ecosystem").asText();
        if (!ecosystem.matches("JAVA|NODE|GO|DOTNET|KOTLIN|RUST|PYTHON|PHP|RUBY"))
            throw new IOException("Original toolchain ecosystem unavailable");
        return ecosystem;
    }

    private static void appendSelection(StringBuilder body, com.fasterxml.jackson.databind.node.ArrayNode selected,
                                        Map<String, NavigableMap<Integer, Integer>> initialRanges, String pointer,
                                        String source, List<PreparationSourceRange> ranges) {
        var supplied = initialRanges.computeIfAbsent(pointer, ignored -> new TreeMap<>());
        for (var range : ranges) {
            body.append("\n--- ").append(range.section()).append(" ---\n");
            selected.addObject().put("section", range.section()).put("sourcePointer", pointer).put("offset", range.offset())
                .put("next", range.next()).put("startCharacter", body.length());
            body.append(source, range.offset(), range.next());
            DeploymentEvidencePages.remember(supplied, range.offset(), range.next());
        }
    }

    private static String bindingSelection(ObjectNode descriptor, List<BindingSourceRange> ranges) {
        descriptor.put("selectedRangeFrame", BINDING_SELECTION);
        var locations = descriptor.putArray("selectedRanges");
        var body = new StringBuilder();
        for (var range : ranges) {
            body.append("\nWTL-BINDING-HELPER-RANGE ").append(range.section()).append(' ')
                .append(range.offset()).append(' ').append(range.next()).append('\n');
            locations.addObject().put("section", range.section()).put("offset", range.offset()).put("next", range.next())
                .put("startCharacter", body.length());
            body.append(range.text());
        }
        return body.toString();
    }

    /** References exact staged and executed material from the already validated wrapper. */
    private static void projectMirror(ObjectNode projected, ObjectNode manifest, List<AiMessage> messages, String snapshot,
                                      String exact, String policy, boolean dependency, Map<String, String> sourceBodies) throws IOException {
        String runtime = DeploymentMirrorRuntime.runtimeSource();
        if (!dependency && !runtime.equals(projected.at(MIRROR_RUNTIME).textValue()))
            throw new IOException("Original mirror runtime evidence changed");
        String source = dependency ? "/" + PROJECTION + "/mirrorRuntime" : MIRROR_RUNTIME;
        var descriptor = pagedSource(source, runtime, MIRROR_LOCATIONS);
        sourceBodies.put(source, runtime);
        if (dependency) manifest.set("mirrorRuntime", descriptor);
        else ((ObjectNode) projected.get("mirrorTransport")).set("runtimeSource", descriptor);

        String quoted = DeploymentHelperInstallerScript.quote(runtime);
        String prefix = "printf '%s' ";
        int runtimeStart = uniqueIndex(exact, prefix + quoted + " >\"$WTL_MIRROR_RUNTIME\"\n") + prefix.length();
        var parts = new ArrayList<Map<String, String>>();
        int policyEnd = projectMirrorPolicy(exact, policy, runtimeStart, parts, MIRROR_POLICY_SOURCE);
        var policyDescriptor = dependency ? sourceFrame(messages, snapshot, MIRROR_POLICY_SOURCE, policy)
            : pagedSource(MIRROR_POLICY_SOURCE, policy, List.of());
        if (dependency) manifest.withArray("frames").add(policyDescriptor);
        manifest.set("mirrorPolicySource", policyDescriptor);
        sourceBodies.put(MIRROR_POLICY_SOURCE, policy);
        parts.add(literal(exact.substring(policyEnd, runtimeStart)));
        parts.add(reference("SHELL_SINGLE_QUOTE", source));
        projectMirrorShell(exact, runtimeStart + quoted.length(), parts, manifest, sourceBodies,
            MIRROR_BOOTSTRAP_SHELL, "trap 'wtl_mirror_exit=");
        projected.set("exactAction", segments(exact, parts));
    }

    private static int projectMirrorPolicy(String exact, String policy, int runtimeStart,
                                           List<Map<String, String>> parts, String pointer) throws IOException {
        String quoted = DeploymentHelperInstallerScript.quote(policy);
        String prefix = "printf '%s' ";
        int start = uniqueIndex(exact, prefix + quoted + " >\"$WTL_MIRROR_POLICY\" || exit 65\n") + prefix.length();
        int end = start + quoted.length();
        if (end >= runtimeStart) throw new IOException("Original mirror policy insertion changed");
        parts.add(literal(exact.substring(0, start)));
        parts.add(reference("SHELL_SINGLE_QUOTE", pointer));
        return end;
    }

    /** The same generated definitions are written as shell-quoted data and then defined by the outer wrapper. */
    private static void projectMirrorShell(String exact, int runtimeEnd, List<Map<String, String>> parts, ObjectNode manifest,
                                            Map<String, String> sourceBodies, String pointer, String tailStart) throws IOException {
        String shellAssignment = "\nprintf '%s' ";
        int assignment = exact.indexOf(shellAssignment, runtimeEnd);
        int quotedStart = assignment + shellAssignment.length();
        int quotedEnd = exact.indexOf(" >\"$WTL_MIRROR_SHELL\" || exit 65\n", quotedStart);
        int definition = exact.indexOf("\nwtl_source_providers() {\n", quotedEnd) + 1;
        if (assignment < 0 || quotedEnd <= quotedStart + 1 || definition <= quotedEnd
            || exact.charAt(quotedStart) != '\'' || exact.charAt(quotedEnd - 1) != '\'')
            throw new IOException("Original mirror bootstrap shell insertion changed");
        String quoted = exact.substring(quotedStart, quotedEnd);
        String shell = quoted.substring(1, quoted.length() - 1).replace("'\"'\"'", "'");
        int end = definition + shell.length();
        if (!DeploymentHelperInstallerScript.quote(shell).equals(quoted) || !exact.startsWith(shell, definition)
            || !exact.startsWith(tailStart, end))
            throw new IOException("Original mirror bootstrap definitions differ from staged shell");
        manifest.set("mirrorBootstrapShell", pagedSource(pointer, shell, List.of(
            "wtl_source_providers() {", "wtl_source_routes() {", "wtl_source_repo_templates() {", "wtl_source_credential() {",
            "wtl_run_mirror_runtime() {", "wtl_system_package() {", "wtl_apt_install_frozen() {", "wtl_dnf() {")));
        sourceBodies.put(pointer, shell);
        parts.add(literal(exact.substring(runtimeEnd, quotedStart)));
        parts.add(reference("SHELL_SINGLE_QUOTE", pointer));
        parts.add(literal(exact.substring(quotedEnd, definition)));
        parts.add(reference("SOURCE_TEXT", pointer));
        if (end < exact.length()) parts.add(literal(exact.substring(end)));
    }

    private static Optional<DeploymentCommandSourceEvidence> build(String original, ActionIntent intent, CommandRequest request)
        throws IOException {
        var current = (ObjectNode) JSON.readTree(original);
        if (!current.has("mirrorInputTask")) return Optional.empty();
        String input = requiredText(current, "completeExecutableInput");
        var wrapper = DeploymentMirrorTasks.buildInput(input)
            .orElseThrow(() -> new IOException("Original mirrored build input unavailable"));
        verifyBuild(current, input, wrapper, intent, request);
        var projected = current.deepCopy();
        String snapshot = sha256(original);
        var manifest = projected.putObject(BUILD_PROJECTION).put("snapshotSha256", snapshot);
        var frames = manifest.putArray("frames");
        var messages = new ArrayList<AiMessage>();
        var sources = new LinkedHashMap<String, String>();
        String runtime = DeploymentMirrorRuntime.runtimeSource();
        String quoted = DeploymentHelperInstallerScript.quote(runtime);
        String prefix = "printf '%s' ";
        String bootstrap = DeploymentMirrorTasks.withMaterials(DeploymentMirrorRuntime.bootstrapPrefix(wrapper.policy()), wrapper.task());
        int runtimeStart = uniqueIndex(bootstrap, prefix + quoted + " >\"$WTL_MIRROR_RUNTIME\"\n") + prefix.length();
        var parts = new ArrayList<Map<String, String>>();
        int policyEnd = projectMirrorPolicy(bootstrap, wrapper.policy(), runtimeStart, parts, BUILD_POLICY);
        parts.add(literal(bootstrap.substring(policyEnd, runtimeStart)));
        parts.add(reference("SHELL_SINGLE_QUOTE", MIRROR_RUNTIME));
        projectMirrorShell(bootstrap, runtimeStart + quoted.length(), parts, manifest, sources, BUILD_SHELL, "");
        String tail = input.substring(bootstrap.length());
        String suffix = "\n)\n";
        int bodyStart = tail.length() - wrapper.original().length() - suffix.length();
        if (bodyStart < 1 || !tail.substring(bodyStart).equals(wrapper.original() + suffix))
            throw new IOException("Original build program boundary changed");
        parts.add(literal(tail.substring(0, bodyStart)));
        parts.add(reference("SOURCE_TEXT", BUILD_BODY));
        parts.add(literal(suffix));
        sources.put(BUILD_POLICY, wrapper.policy());
        sources.put(MIRROR_RUNTIME, runtime);
        sources.put(BUILD_BODY, wrapper.original());
        if (!input.equals(restoreBuild(parts, sources)))
            throw new IOException("Mirrored build projection does not restore the original executable input");
        for (String pointer : List.of(BUILD_POLICY, MIRROR_RUNTIME, BUILD_SHELL, BUILD_BODY)) {
            var frame = sourceFrame(messages, snapshot, pointer, sources.get(pointer));
            frames.add(frame);
            if (MIRROR_RUNTIME.equals(pointer))
                ((ObjectNode) projected.path("mirrorTransport")).set("runtimeSource", frame);
            else manifest.set(pointer.substring(pointer.lastIndexOf('/') + 1), frame);
        }
        projected.set("completeExecutableInput", segments(input, parts));
        messages.add(new AiMessage(AiRole.USER, JSON.writeValueAsString(projected), List.of()));
        return Optional.of(new DeploymentCommandSourceEvidence(current, original, snapshot, messages, sources, Map.of()));
    }

    private static String restoreBuild(List<Map<String, String>> parts, Map<String, String> sources) {
        var result = new StringBuilder();
        for (var part : parts) {
            result.append(switch (part.get("kind")) {
                case "LITERAL" -> part.get("text");
                case "SOURCE_TEXT" -> sources.get(part.get("sourcePointer"));
                case "SHELL_SINGLE_QUOTE" -> DeploymentHelperInstallerScript.quote(sources.get(part.get("sourcePointer")));
                default -> throw new IllegalArgumentException("Unknown mirrored build segment");
            });
        }
        return result.toString();
    }

    private static void verifyBuild(ObjectNode current, String input, DeploymentMirrorTasks.Preparation wrapper,
                                    ActionIntent intent, CommandRequest request) throws IOException {
        byte[] actual = request.stdin();
        try {
            if (current.has(BUILD_PROJECTION) || !BUILD.equals(requiredText(current, "operation"))
                || !intent.exactAction().equals(requiredText(current, "exactAction"))
                || !wrapper.task().toString().equals(requiredText(current, "mirrorInputTask"))
                || !DeploymentMirrorRuntime.runtimeSource().equals(current.at(MIRROR_RUNTIME).textValue())
                || !Arrays.equals(actual, input.getBytes(StandardCharsets.UTF_8)))
                throw new IOException("Original mirrored build evidence changed before projection");
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static DeploymentCommandSourceEvidence installer(String original, ActionIntent intent, CommandRequest request) throws IOException {
        var current = (ObjectNode) JSON.readTree(original);
        var executable = current.path("completeExecutableInput");
        String reconstructed = DeploymentInstallerEvidence.reconstruct(executable);
        byte[] stdin = request.stdin();
        try {
            if (!intent.exactAction().equals(requiredText(current, "exactAction"))
                || !intent.semantics().orElseThrow().operation().equals(requiredText(current, "operation"))
                || current.has(INSTALLER) || !Arrays.equals(stdin, reconstructed.getBytes(StandardCharsets.UTF_8)))
                throw new IOException("Original installer evidence changed before projection");
            DeploymentInstallerEvidence.verify(executable, reconstructed);
        } finally {
            Arrays.fill(stdin, (byte) 0);
        }
        var projected = JSON.createObjectNode();
        if (current.has("currentCommandEvidence")) projected.set("currentCommandEvidence", current.get("currentCommandEvidence"));
        current.fields().forEachRemaining(entry -> {
            if (!projected.has(entry.getKey())) projected.set(entry.getKey(), entry.getValue());
        });
        projected.set("completeExecutableInput", executable.deepCopy());
        String snapshot = sha256(original);
        projected.putObject(INSTALLER).put("snapshotSha256", snapshot);
        var bodies = new LinkedHashMap<String, String>();
        var sources = (ObjectNode) projected.at("/completeExecutableInput/sources");
        executable.path("sources").fields().forEachRemaining(entry -> {
            String pointer = "/completeExecutableInput/sources/" + entry.getKey();
            String text = entry.getValue().textValue();
            var locations = switch (entry.getKey()) {
                case "mirrorRuntime" -> MIRROR_LOCATIONS;
                case "managedHelper" -> HELPER_LOCATIONS;
                default -> List.<String>of();
            };
            bodies.put(pointer, text);
            sources.set(entry.getKey(), pagedSource(pointer, text, locations));
        });
        return new DeploymentCommandSourceEvidence(current, original, snapshot,
            List.of(new AiMessage(AiRole.USER, JSON.writeValueAsString(projected), List.of())), bodies, Map.of());
    }

    private static void projectDependency(ObjectNode current, ObjectNode projected, ObjectNode manifest, List<AiMessage> messages,
                                          String snapshot, String exact, String script, CommandRequest request,
                                          Map<String, String> sourceBodies) throws IOException {
        String input = requiredText(current, "completeExecutableInput");
        int newline = input.indexOf('\n');
        if (newline < 0 || !script.equals(requiredText(current, "commandScript")))
            throw new IOException("Original dependency script or worker changed");
        String worker = input.substring(newline + 1);
        byte[] bytes = request.stdin();
        try {
            if (worker.isEmpty() || !input.equals(WORKER_ASSIGNMENT + JSON.writeValueAsString(worker) + "\n" + worker)
                || !Arrays.equals(bytes, input.getBytes(StandardCharsets.UTF_8)))
                throw new IOException("Original dependency stdin differs from complete worker representation");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        var frame = sourceFrame(messages, snapshot, DEPENDENCY_WORKER, worker);
        sourceBodies.put(DEPENDENCY_WORKER, worker);
        manifest.withArray("frames").add(frame);
        manifest.set("dependencyWorker", frame);
        projected.set("completeExecutableInput", segments(input, List.of(literal(WORKER_ASSIGNMENT),
            reference("JSON_STRING", DEPENDENCY_WORKER), literal("\n"), reference("SOURCE_TEXT", DEPENDENCY_WORKER))));
        projected.set("commandScript", identity(script).put("sourcePointer", "/exactAction").put("startCharacter", exact.length() - script.length()));
        var transaction = projected.at("/currentCommandEvidence/currentNativeBuildPrerequisites");
        if (transaction instanceof ObjectNode prerequisites && current.path("toolchainDependencies").equals(prerequisites.path("fixedTransaction")))
            prerequisites.set("fixedTransaction", JSON.createObjectNode().put("sourcePointer", "/toolchainDependencies"));
    }

    public Reader reader(int attempts) {
        return new Reader(attempts);
    }

    public final class Reader {
        private final Set<String> seen = new HashSet<>();
        private final Map<String, NavigableMap<Integer, Integer>> delivered = new LinkedHashMap<>();
        private final Map<String, NavigableMap<Integer, Integer>> inline = new LinkedHashMap<>();
        private Optional<AiMessage> priorEvidence = Optional.empty();
        private List<ReadReceipt> latestReads = List.of();
        private List<ReadReceipt> priorReads = List.of();
        private boolean continuedResponses;
        private boolean rebased;
        private boolean batchCorrectionUsed;
        private boolean latestBatchRejected;
        private int remaining;

        private Reader(int attempts) {
            remaining = attempts;
            if (sourceBodies.containsKey(DEPENDENCY_WORKER)) {
                suppliedInline(DEPENDENCY_WORKER);
                suppliedInline(MIRROR_POLICY_SOURCE);
            }
            suppliedInline(BINDING_SELECTION);
            suppliedInline(PREPARATION_SELECTION);
            if (buildReview())
                for (String pointer : List.of(BUILD_POLICY, MIRROR_RUNTIME, BUILD_SHELL, BUILD_BODY)) suppliedInline(pointer);
            initialRanges.forEach((pointer, ranges) -> delivered.put(pointer, new TreeMap<>(ranges)));
            delivered.forEach((pointer, ranges) -> inline.put(pointer, new TreeMap<>(ranges)));
        }

        private void suppliedInline(String pointer) {
            String body = sourceBodies.get(pointer);
            if (body == null) return;
            var range = new TreeMap<Integer, Integer>();
            range.put(0, body.length());
            delivered.put(pointer, range);
        }

        public List<AiToolResult> read(AiResponse response) throws IOException {
            if (!response.finishReason().equals("tool_calls")) return List.of();
            var calls = response.toolCalls();
            for (var call : calls) {
                if (!seen.add(call.id()))
                    throw new IOException("Repeated command review call id");
            }
            long reads = calls.stream().filter(call -> TOOL.name().equals(call.name())).count();
            if (reads == 0) return List.of();
            if (reads != calls.size())
                throw new IOException("An evidence batch cannot include a verdict or another tool");
            if (calls.size() > remaining)
                throw new IOException("Current command evidence read budget exhausted");
            remaining -= calls.size();
            latestReads = new ArrayList<>();
            latestBatchRejected = false;
            if (calls.size() > MAX_READS_PER_RESPONSE) return correctReadBatch(calls);
            var results = new ArrayList<AiToolResult>(calls.size());
            for (var call : calls) results.add(read(call));
            return results;
        }

        private List<AiToolResult> correctReadBatch(List<AiToolCall> calls) throws IOException {
            if (!bindingReview() || batchCorrectionUsed || calls.size() > MAX_CORRECTABLE_BATCH)
                throw new IOException("Current command evidence read budget exhausted");
            batchCorrectionUsed = true;
            latestBatchRejected = true;
            for (var call : calls) latestReads.add(new ReadReceipt(call.id(), "", 0, 0, true));
            return calls.stream().map(call -> new AiToolResult(call.id(),
                "EVIDENCE_BATCH_REJECTED: no pages were read. Request at most eight evidence calls in one response, separate from the verdict.", true)).toList();
        }

        private AiToolResult read(AiToolCall call) throws IOException {
            try {
                var arguments = DeploymentAiJson.arguments(call);
                DeploymentToolSchemas.validate(SCHEMA, arguments);
                if (!snapshot.equals(arguments.path("snapshotSha256").textValue()))
                    throw new IllegalArgumentException("Original review evidence snapshot differs");
                String pointer = arguments.path("pointer").textValue();
                String text = sourceText(pointer);
                var page = DeploymentEvidencePages.page(text, arguments.path("offset").intValue(), arguments.path("limit").intValue());
                var ranges = delivered.computeIfAbsent(pointer, ignored -> new TreeMap<>());
                int offset = (int) page.get("offset"), next = (int) page.get("next");
                var contents = DeploymentEvidencePages.retainAndDescribe(text, page, ranges);
                var result = JSON.createObjectNode().put("trust", "UNTRUSTED_DATA").put("snapshotSha256", snapshot).put("pointer", pointer);
                String body = toolchainReview() || buildReview() ? rawSourcePage(result, JSON.valueToTree(contents))
                    : JSON.writeValueAsString(result.set("value", JSON.valueToTree(contents)));
                latestReads.add(new ReadReceipt(call.id(), pointer, offset, next, false));
                return new AiToolResult(call.id(), body, false);
            } catch (IllegalArgumentException invalid) {
                latestReads.add(new ReadReceipt(call.id(), "", 0, 0, true));
                return new AiToolResult(call.id(), "EVIDENCE_REQUEST_REJECTED: use the current snapshot, original pointer and a valid UTF-16 cursor. Limit is 1 through 8000. No action was authorized or executed.", true);
            }
        }

        /** A Responses rebase starts a new request with exact current evidence and no partial native history. */
        public void continueReview(List<AiMessage> messages, AiResponse response, List<AiToolResult> results) throws IOException {
            boolean responses = isResponses(response);
            if (rebased || responses && continuedResponses) {
                priorEvidence.ifPresent(messages::remove);
                int factsIndex = messages.lastIndexOf(DeploymentCommandSourceEvidence.this.messages.getLast());
                if (factsIndex < 0) throw new IOException("Current review facts unavailable");
                // A previous result-format correction can also leave a native batch after the current facts.
                messages.subList(factsIndex + 1, messages.size()).clear();
                var evidence = retainedSource();
                messages.add(factsIndex, evidence);
                priorEvidence = Optional.of(evidence);
                rebased = true;
                return;
            }
            messages.add(AiMessage.assistant(response));
            results.forEach(result -> messages.add(AiMessage.result(result)));
            if (responses) {
                priorReads = List.copyOf(latestReads);
                continuedResponses = true;
            }
        }

        private boolean isResponses(AiResponse response) {
            return toolchainReview() && response.continuation().isPresent() && !response.generationAttempts().isEmpty()
                && RESPONSES.equals(response.generationAttempts().getLast().protocol());
        }

        private AiMessage retainedSource() throws IOException {
            var header = JSON.createObjectNode().put("snapshotSha256", snapshot).put("trust", "UNTRUSTED_DATA");
            if (latestBatchRejected) header.put("latestBatchRejected", "NO_PAGES_READ_MAX_EIGHT_PER_RESPONSE");
            var reads = header.putArray("latestReads");
            for (var receipt : latestReads) {
                var read = reads.addObject().put("callId", receipt.callId()).put("rejected", receipt.rejected());
                if (!receipt.rejected())
                    read.put("pointer", receipt.pointer()).put("offset", receipt.offset()).put("next", receipt.next());
            }
            var entries = header.putArray("ranges");
            var body = new StringBuilder();
            var visible = new LinkedHashMap<String, NavigableMap<Integer, Integer>>();
            inline.forEach((pointer, ranges) -> visible.put(pointer, new TreeMap<>(ranges)));
            for (var read : latestReads) {
                if (read.rejected()) continue;
                appendVisibleRange(read.pointer(), read.offset(), read.next(), visible, entries, body);
            }
            int bytes = body.toString().getBytes(StandardCharsets.UTF_8).length;
            int omitted = 0;
            var retainedReads = new ArrayList<>(latestReads);
            int limit = switch (current.path("operation").asText()) {
                case BIND -> BIND_RETAINED_SOURCE_BYTES;
                case PREPARE -> PREPARE_RETAINED_SOURCE_BYTES;
                default -> RETAINED_SOURCE_BYTES;
            };
            for (var read : priorReads) {
                if (read.rejected()) continue;
                String pointer = read.pointer();
                String text = sourceText(pointer);
                var ranges = visible.computeIfAbsent(pointer, ignored -> new TreeMap<>());
                var missing = DeploymentEvidencePages.missing(text, read.offset(), read.next(), ranges);
                int size = missing.stream().mapToInt(part -> ((String) part.get("text")).getBytes(StandardCharsets.UTF_8).length).sum();
                if (size == 0) continue;
                if (size > limit - bytes || retainedReads.size() >= MAX_RETAINED_READS) {
                    omitted++;
                    continue;
                }
                appendVisibleRange(pointer, read.offset(), read.next(), visible, entries, body);
                retainedReads.add(read);
                bytes += size;
            }
            header.put("omittedRanges", omitted);
            delivered.clear();
            delivered.putAll(visible);
            priorReads = List.copyOf(retainedReads);
            return new AiMessage(AiRole.USER, RETAINED_SOURCE + JSON.writeValueAsString(header) + "\n" + body, List.of());
        }

        private void appendVisibleRange(String pointer, int offset, int next,
                                        Map<String, NavigableMap<Integer, Integer>> visible,
                                        com.fasterxml.jackson.databind.node.ArrayNode entries, StringBuilder body) throws IOException {
            var ranges = visible.computeIfAbsent(pointer, ignored -> new TreeMap<>());
            for (var part : DeploymentEvidencePages.missing(sourceText(pointer), offset, next, ranges)) {
                entries.addObject().put("pointer", pointer).put("offset", (int) part.get("offset")).put("next", (int) part.get("next"));
                body.append(part.get("text"));
            }
            if (next > offset) DeploymentEvidencePages.remember(ranges, offset, next);
        }
    }

    private record ReadReceipt(String callId, String pointer, int offset, int next, boolean rejected) { }

    public boolean toolchainReview() {
        return OPERATIONS.contains(current.path("operation").asText());
    }

    private boolean buildReview() {
        return BUILD.equals(current.path("operation").asText());
    }

    public boolean bindingReview() {
        return BIND.equals(current.path("operation").asText());
    }

    private boolean preparationReview() {
        return PREPARE.equals(current.path("operation").asText());
    }

    public String instructions() {
        if (buildReview()) return BUILD_INSTRUCTION;
        if (preparationReview()) return PREPARE_INSTRUCTION;
        return bindingReview() ? BIND_INSTRUCTION : INSTRUCTION;
    }

    private String sourceText(String pointer) throws IOException {
        var node = current.at(pointer);
        String text = pointer.isEmpty() ? originalFacts : sourceBodies.get(pointer);
        if (text == null && node.isMissingNode()) throw new IllegalArgumentException("Original review evidence pointer missing");
        return text != null ? text : node.isTextual() ? node.textValue() : JSON.writeValueAsString(node);
    }

    /** Avoid escaping source text twice inside the provider's JSON tool output. */
    private static String rawSourcePage(ObjectNode header, ObjectNode value) throws IOException {
        var body = new StringBuilder();
        var whole = value.remove("text");
        if (whole != null) {
            body.append(whole.textValue());
        } else {
            for (var range : value.withArray("newRanges")) body.append(((ObjectNode) range).remove("text").textValue());
        }
        header.set("value", value);
        return RAW_PAGE + JSON.writeValueAsString(header) + "\n" + body;
    }

    private static ObjectNode schema() {
        var schema = DeploymentToolSchemas.object("snapshotSha256", "pointer", "offset:integer", "limit:integer");
        ((ObjectNode) schema.at("/properties/snapshotSha256")).put("minLength", 64).put("maxLength", 64);
        ((ObjectNode) schema.at("/properties/pointer")).put("minLength", 0).put("maxLength", 512);
        ((ObjectNode) schema.at("/properties/offset")).put("maximum", 1048576);
        ((ObjectNode) schema.at("/properties/limit")).put("maximum", 8000);
        return schema;
    }

    private static String requiredText(JsonNode node, String name) throws IOException {
        var value = node.path(name);
        if (!value.isTextual()) throw new IOException("Complete original review text required");
        return value.textValue();
    }

    private static int uniqueIndex(String body, String text) throws IOException {
        int index = body.indexOf(text);
        if (index < 0 || body.indexOf(text, index + 1) >= 0) throw new IOException("Original mirror runtime insertion changed");
        return index;
    }

    private static ObjectNode sourceFrame(List<AiMessage> messages, String snapshot, String pointer, String text) throws IOException {
        var frame = identity(text).put("sourcePointer", pointer).put("ordinal", messages.size());
        String header = "WTL-TOOLCHAIN-REVIEW-SOURCE-1\n" + JSON.writeValueAsString(frame.deepCopy().put("snapshotSha256", snapshot)) + "\n";
        messages.add(new AiMessage(AiRole.USER, header + text, List.of()));
        return frame.put("headerCharacters", header.length());
    }

    private static ObjectNode pagedSource(String pointer, String source, List<String> anchors) {
        var descriptor = identity(source).put("sourcePointer", pointer).put("retrieval", "EVIDENCE_PAGES");
        var locations = descriptor.putObject("locations");
        var sections = descriptor.putObject("sections");
        for (String anchor : anchors) {
            int start = source.indexOf(anchor);
            if (start < 0 || source.indexOf(anchor, start + 1) >= 0) continue;
            locations.put(anchor, start);
            if (!anchor.startsWith("def ") && !anchor.startsWith("class ")) continue;
            if (start != 0 && source.charAt(start - 1) != '\n') continue;
            var nextSection = NEXT_PYTHON_SECTION.matcher(source);
            if (nextSection.find(start + anchor.length()))
                sections.putObject(anchor).put("offset", start).put("next", nextSection.start());
        }
        if (sections.isEmpty()) descriptor.remove("sections");
        return descriptor;
    }

    private static ObjectNode identity(String text) {
        return JSON.createObjectNode().put("sha256", sha256(text)).put("characters", text.length())
            .put("utf8Bytes", text.getBytes(StandardCharsets.UTF_8).length);
    }

    private static ObjectNode segments(String text, List<Map<String, String>> segments) {
        return identity(text).set("segments", JSON.valueToTree(segments));
    }

    private static Map<String, String> literal(String text) {
        return Map.of("kind", "LITERAL", "text", text);
    }

    private static Map<String, String> reference(String kind, String pointer) {
        return Map.of("kind", kind, "sourcePointer", pointer);
    }
}
