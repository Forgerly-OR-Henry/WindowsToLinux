package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;

/** Fixed immutable native-first compensation artifact. Builds requests and decodes receipts; grants and sends nothing. */
public final class DeploymentAgentPublicationRecoveryProgram {
    public static final String REVISION = "WTL_AGENT_PUBLICATION_RECOVERY_STORE_1";
    public static final String PREFIX = "deployment.agent.publication-recovery.";
    public static final String READINESS = PREFIX + "readiness.v1";
    public static final String INSTALL = PREFIX + "install.v1";
    public static final String ROLLBACK = PREFIX + "guarded-first-rollback.v1";
    private static final String ROOT = "/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/";
    private static final String INSTALL_RESOURCE = "/gold/debug/windowstolinux/mod/deployment/service/agent/toolchain/agent-controller-install.py";
    private static final Duration TIMEOUT = Duration.ofSeconds(180);
    private static final long OUTPUT_LIMIT = 8192;
    private static final String LAUNCHER = "/usr/bin/python3 -I -S - ";
    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static final String REVIEW_CONTRACT = """
        Fixed original-Agent native-first compensation only. Review the complete actual source, loader, installer,
        task/execution/unit/owner/candidate/release/plan/layout and exact request/targets. This description grants no permission.
        Installation publishes only a root-owned immutable version via no-follow FD operations and NOREPLACE;
        incomplete stages, missing receipts, unsafe paths and unknown transport authorize no repair or replay.
        The sole installed action acquires the original application locks, refuses every orphan job without cleanup,
        requires the exact original CURRENT, candidate task/deployer, absent snapshots, idle build/cgroup,
        exact owned maintenance marker, sole retained release, native DAEMON unit bytes/FragmentPath/no drop-ins;
        only then it stops the original unit, removes that release/CURRENT/unit and the rechecked original marker.
        Candidate workspace, persistent data, configurations, toolchains and identities are retained.
        Lock coordination can write metadata. Failure/timeout after any side effect remains UNKNOWN and cannot retry.
        A successful bounded receipt is not task settlement or proof of all resources released.
        Every complete action needs independent APP review and a fresh exact host grant; prior approvals never transfer.
        """;
    private final DeploymentRemoteLayout layout;
    private final String source;

    public DeploymentAgentPublicationRecoveryProgram(DeploymentRemoteLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.source = renderSource();
        if (source.getBytes(StandardCharsets.UTF_8).length > 1048576)
            throw new IllegalArgumentException("Fixed publication recovery source exceeds loader bound");
    }

    public record Rollback(UUID execution, String candidate, String expectedRelease, String planSha256) {
        public Rollback {
            Objects.requireNonNull(execution);
            Objects.requireNonNull(candidate);
            digest(expectedRelease);
            digest(planSha256);
        }
    }

    public record Request(String operation, Set<ActionTarget> resourceTargets, CommandRequest command,
                          DeploymentUnitIdentity unit, UUID task, UUID action, Optional<Rollback> rollback) implements AutoCloseable {
        public Request {
            resourceTargets = Set.copyOf(resourceTargets);
            Objects.requireNonNull(operation);
            Objects.requireNonNull(command);
            Objects.requireNonNull(unit);
            Objects.requireNonNull(task);
            Objects.requireNonNull(action);
            Objects.requireNonNull(rollback);
        }

        @Override
        public void close() {
            command.close();
        }
    }

    public record StoreReceipt(String status, String installTask, String installOperation) { }

    /** This receipt confirms only this one bounded rollback and marker removal, never task/resource settlement. */
    public record RollbackReceipt(UUID task, UUID action, UUID execution, String unit, String release, String planSha256) { }

    public Request readiness(DeploymentUnitIdentity unit, UUID task, UUID action) {
        return request(READINESS, unit, task, action, Optional.empty());
    }

    public Request install(DeploymentUnitIdentity unit, UUID task, UUID action) {
        return request(INSTALL, unit, task, action, Optional.empty());
    }

    public Request guardedFirstRollback(DeploymentUnitIdentity unit, UUID task, UUID execution, String candidate,
                                        String expectedRelease, String planSha256, UUID action) {
        return request(ROLLBACK, unit, task, action, Optional.of(new Rollback(execution, candidate, expectedRelease, planSha256)));
    }

    private Request request(String operation, DeploymentUnitIdentity unit, UUID task, UUID action, Optional<Rollback> rollback) {
        Objects.requireNonNull(task);
        Objects.requireNonNull(action);
        if (!Set.of(READINESS, INSTALL, ROLLBACK).contains(operation) || rollback.isPresent() != operation.equals(ROLLBACK))
            throw new IllegalArgumentException("Unknown publication recovery request mode");
        if (!unit.layout().equals(layout) || !unit.id().matches("[a-z0-9][a-z0-9-]{0,62}")
            || !unit.systemdUnit().equals("windowstolinux-" + unit.id() + ".service"))
            throw new IllegalArgumentException("Original native unit identity changed");
        var argv = new ArrayList<>(List.of(operation.equals(INSTALL) ? "install" : operation.equals(READINESS) ? "readiness" : "invoke",
            task.toString(), actionId(action)));
        rollback.ifPresent(value -> {
            if (!value.candidate().matches(java.util.regex.Pattern.quote(unit.id()) + "-[a-f0-9]{16}"))
                throw new IllegalArgumentException("Original candidate identity changed");
            argv.addAll(List.of(value.execution().toString(), unit.id(), unit.ownershipManifestSha256(), value.candidate(),
                value.expectedRelease(), value.planSha256()));
        });
        String script;
        byte[] stdin;
        if (operation.equals(INSTALL)) {
            script = LAUNCHER + quote(task.toString()) + " " + quote(actionId(action));
            stdin = installer().getBytes(StandardCharsets.UTF_8);
        } else {
            script = "/usr/bin/python3 -I -S -c " + quote(loader()) + " " + argv.stream().map(DeploymentAgentPublicationRecoveryProgram::quote)
                .collect(java.util.stream.Collectors.joining(" "));
            stdin = new byte[0];
        }
        return new Request(operation, targets(operation, unit.server().id(), task.toString(), actionId(action), unit.id(), rollback),
            new CommandRequest(script, stdin, TIMEOUT, OUTPUT_LIMIT), unit, task, action, rollback);
    }

    private Set<ActionTarget> targets(String operation, String server, String task, String action, String app, Optional<Rollback> rollback) {
        var targets = new HashSet<ActionTarget>();
        targets.add(new ActionTarget(server, "task", task));
        targets.add(new ActionTarget(server, "agent-publication-recovery-program", path()));
        if (operation.equals(INSTALL)) {
            for (String item : List.of(layout.toolchains(), root(), root() + "/.install.lock", root() + "/.install-" + version() + "-" + action))
                targets.add(new ActionTarget(server, "deployment-build", item));
        }
        rollback.ifPresent(value -> {
            String releaseRoot = layout.applicationRoot(app);
            targets.add(new ActionTarget(server, "application", app));
            targets.add(new ActionTarget(server, "deployment-release", releaseRoot));
            targets.add(new ActionTarget(server, "deployment-release", releaseRoot + "/releases/" + value.expectedRelease()));
            targets.add(new ActionTarget(server, "deployment-build", layout.candidateRoot(value.candidate())));
            targets.add(new ActionTarget(server, "systemd-unit", "windowstolinux-" + app + ".service"));
            targets.add(new ActionTarget(server, "deployment-build", layout.paths().stateRoot() + "/application-locks/" + app));
            targets.add(new ActionTarget(server, "deployment-maintenance", layout.paths().stateRoot() + "/application-locks/" + app + "/maintenance"));
            targets.add(new ActionTarget(server, "deployment-snapshot", layout.snapshotsRoot() + "/" + app + "-" + task));
        });
        return Set.copyOf(targets);
    }

    /** Exact target set decoded from a byte-validated request, without creating a task, grant or execution. */
    public static Set<ActionTarget> reviewedResourceTargets(String operation, CommandRequest command, DeploymentRemoteLayout layout,
                                                            gold.debug.windowstolinux.appapi.server.model.ServerId server) {
        var facts = reviewFacts(operation, command, layout);
        @SuppressWarnings("unchecked")
        var argv = (List<String>) facts.get("typedArguments");
        var rollback = operation.equals(ROLLBACK) ? Optional.of(new Rollback(uuid(argv.get(2)), argv.get(5), argv.get(6), argv.get(7)))
            : Optional.<Rollback>empty();
        return new DeploymentAgentPublicationRecoveryProgram(layout).targets(operation, server.id(), argv.get(0), argv.get(1),
            rollback.isPresent() ? argv.get(3) : null, rollback);
    }

    public String root() {
        return layout.toolchains() + "/agent-publication-recovery-programs";
    }

    public String path() {
        return root() + "/" + version();
    }

    public Map<String, String> manifest() {
        return Map.of("revision", REVISION, "globalHelperSha256", DeploymentManagedHelperBundle.EXPECTED_SHA256,
            "sourceSha256", sha256(source()), "loaderTemplateSha256", sha256(resource(ROOT + "publication-recovery-loader.py")),
            "installerTemplateSha256", sha256(resource(INSTALL_RESOURCE)), "guardSha256", sha256(resource(ROOT + "publication-recovery-guard.sh")),
            "layoutSha256", layout.digest(), "contractSha256", sha256(REVIEW_CONTRACT));
    }

    public String version() {
        return sha256(encode(new TreeMap<>(manifest())));
    }

    /** Complete decoded source is available to every independent action review, including installed invocations. */
    public String source() {
        return source;
    }

    private String renderSource() {
        return """
            import json
            import os
            import re
            import subprocess
            import sys
            BASH_SOURCE = %s
            if __name__ == '__main__':
                try:
                    args = sys.argv[1:]
                    if (len(args) != 8 or os.geteuid() != 0
                        or any(key in ('BASH_ENV', 'ENV') or key.startswith('BASH_FUNC_') for key in os.environ)):
                        raise ValueError()
                    task, action, execution, app, owner, candidate, release, plan = args
                    if (not re.fullmatch('[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}', task)
                        or not re.fullmatch('[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}', execution)
                        or not re.fullmatch('[a-f0-9]{32}', action)
                        or not re.fullmatch('[a-z0-9][a-z0-9-]{0,62}', app)
                        or not re.fullmatch(re.escape(app) + '-[a-f0-9]{16}', candidate)
                        or not all(re.fullmatch('[a-f0-9]{64}', value) for value in (owner, release, plan))):
                        raise ValueError()
                    # Fixed clean environment; no exported function, ENV or BASH_ENV can reach Bash.
                    result = subprocess.run(['/bin/bash', '--noprofile', '--norc', '-s', '--', 'guarded-first-rollback', *args],
                        input=BASH_SOURCE.encode('utf-8'), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                        env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C', 'LC_ALL': 'C'})
                    if result.returncode != 0 or result.stderr or result.stdout != b'ROLLED_BACK=1\\n':
                        raise ValueError()
                    value = dict(PROGRAM_IDENTITY)
                    value.update(format='WTL_AGENT_PUBLICATION_ROLLBACK_1', task=task, action=action, execution=execution,
                        unit=app, owner=owner, candidate=candidate, release=release, planSha256=plan,
                        currentAbsent=True, unitAbsent=True, releaseAbsent=True, maintenanceAbsent=True)
                    print('PUBLICATION_RECOVERY_ROLLBACK=' + json.dumps(value, sort_keys=True, separators=(',', ':')))
                except Exception:
                    print('PUBLICATION_RECOVERY_FAILURE=UNKNOWN')
                    sys.exit(70)
            """.formatted(encode(DeploymentManagedHelperBundle.renderPublicationRecovery(layout)));
    }

    public String loader() {
        String constants = "STORE_ROOT = " + encode(root()) + "\nSOURCE_SHA256 = " + encode(sha256(source()))
            + "\nVERSION_SHA256 = " + encode(version()) + "\nSOURCE_BYTES = " + source().getBytes(StandardCharsets.UTF_8).length
            + "\nLAYOUT_SHA256 = " + encode(layout.digest()) + "\nCONTRACT_REVISION = " + encode(REVISION);
        return resource(ROOT + "publication-recovery-loader.py").replace("# @WTL_CONTROLLER_CONSTANTS@", constants);
    }

    /** The old installer resource is read unchanged; only its declared fixed-source insertion is populated. */
    public String installer() {
        return resource(INSTALL_RESOURCE).replace("# @WTL_CONTROLLER_INSTALL_SOURCES@",
            "LOADER_SOURCE = " + encode(loader()) + "\nCONTROLLER_SOURCE = " + encode(source()));
    }

    public static final String INSTALL_EVIDENCE_FORMAT = "PUBLICATION_RECOVERY_PYTHON_LITERAL_V1";
    public static final String ROLLBACK_SOURCE_FORMAT = "PUBLICATION_RECOVERY_SOURCE_VIEW_V1";

    /** Readable source leaves reproduce the unchanged nested Python literals without executing them. */
    public Map<String, Object> installationEvidence() {
        var parts = sourceParts();
        String loader = loader(), controller = source(), installer = installer();
        String insertion = "LOADER_SOURCE = " + encode(loader) + "\nCONTROLLER_SOURCE = " + encode(controller);
        int installerAt = unique(installer, insertion);
        var result = new LinkedHashMap<String, Object>(bodyEvidence(installer,
            List.of(literal(installer.substring(0, installerAt) + "LOADER_SOURCE = "), jsonString("loader"),
                literal("\nCONTROLLER_SOURCE = "), jsonString("controller"), literal(installer.substring(installerAt + insertion.length())))));
        result.put("format", INSTALL_EVIDENCE_FORMAT);
        result.put("sources", parts.get("sources")); result.put("derived", parts.get("derived"));
        verifyInstallationEvidence(JSON.valueToTree(result), installer);
        return Collections.unmodifiableMap(result);
    }

    /** This non-stdin view cannot replace delivery of the current rollback launcher script. */
    public Map<String, Object> rollbackSourceEvidence() {
        var result = new LinkedHashMap<String, Object>(sourceParts());
        result.put("format", ROLLBACK_SOURCE_FORMAT);
        verifyRollbackSourceEvidence(JSON.valueToTree(result), source(), loader());
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> sourceParts() {
        String bash = DeploymentManagedHelperBundle.renderPublicationRecovery(layout), controller = source(), loader = loader();
        String inserted = "BASH_SOURCE = " + encode(bash); int at = unique(controller, inserted);
        return Map.of("sources", Map.of("bash", bash, "loader", loader),
            "derived", Map.of("controller", bodyEvidence(controller,
                List.of(literal(controller.substring(0, at) + "BASH_SOURCE = "), jsonString("bash"), literal(controller.substring(at + inserted.length()))))),
            "loaderIdentity", Map.of("characters", loader.length(), "utf8Bytes", loader.getBytes(StandardCharsets.UTF_8).length, "sha256", sha256(loader)));
    }

    private static int unique(String body, String literal) {
        int at = body.indexOf(literal);
        if (at < 0 || body.indexOf(literal, at + 1) >= 0) throw new IllegalArgumentException("Fixed source insertion changed");
        return at;
    }

    private static Map<String, String> literal(String value) { return Map.of("kind", "LITERAL", "text", value); }
    private static Map<String, String> jsonString(String name) { return Map.of("kind", "JSON_STRING", "source", name); }

    private static Map<String, Object> bodyEvidence(String body, List<Map<String, String>> segments) {
        return Map.of("segments", segments, "characters", body.length(), "utf8Bytes", body.getBytes(StandardCharsets.UTF_8).length,
            "sha256", sha256(body));
    }

    private static String sourceText(JsonNode node, String key) {
        String text = textual(node, key);
        if (text.length() > 1048576 || !StandardCharsets.UTF_8.newEncoder().canEncode(text))
            throw new IllegalArgumentException("Executable source is not bounded Unicode");
        return text;
    }

    private static void bodyIdentity(JsonNode node, String body) {
        for (String key : List.of("characters", "utf8Bytes"))
            if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToInt())
                throw new IllegalArgumentException("Executable body length missing");
        if (node.path("characters").intValue() != body.length()
            || node.path("utf8Bytes").intValue() != body.getBytes(StandardCharsets.UTF_8).length
            || !textual(node, "sha256").equals(sha256(body)))
            throw new IllegalArgumentException("Executable body identity changed");
    }

    private static String segments(JsonNode node, List<String> references, Map<String, String> sources) {
        if (!node.isArray() || node.size() != references.size() * 2 + 1)
            throw new IllegalArgumentException("Fixed Python source segments changed");
        var result = new StringBuilder();
        for (int i = 0; i < node.size(); i++) {
            var part = node.get(i);
            if (i % 2 == 0) {
                fields(part, Set.of("kind", "text")); text(part, "kind", "LITERAL");
                result.append(sourceText(part, "text"));
            } else {
                fields(part, Set.of("kind", "source")); text(part, "kind", "JSON_STRING");
                String reference = references.get(i / 2); text(part, "source", reference);
                result.append(encode(sources.get(reference)));
            }
            if (result.length() > 1048576) throw new IllegalArgumentException("Executable reconstruction exceeds bound");
        }
        return result.toString();
    }

    private static String sourceController(JsonNode evidence) {
        var sources = evidence.path("sources"); fields(sources, Set.of("bash", "loader"));
        String bash = sourceText(sources, "bash"); sourceText(sources, "loader");
        var derived = evidence.path("derived"); fields(derived, Set.of("controller"));
        var controller = derived.path("controller"); fields(controller, Set.of("segments", "characters", "utf8Bytes", "sha256"));
        String restored = segments(controller.path("segments"), List.of("bash"), Map.of("bash", bash));
        bodyIdentity(controller, restored); return restored;
    }

    /** Strict fixed-format reconstruction; JSON_STRING uses the same canonical encoder as the real request. */
    public static String reconstructInstallationEvidence(JsonNode evidence) {
        fields(evidence, Set.of("format", "sources", "derived", "segments", "characters", "utf8Bytes", "sha256"));
        text(evidence, "format", INSTALL_EVIDENCE_FORMAT);
        String controller = sourceController(evidence), loader = sourceText(evidence.path("sources"), "loader");
        String restored = segments(evidence.path("segments"), List.of("loader", "controller"), Map.of("loader", loader, "controller", controller));
        bodyIdentity(evidence, restored); return restored;
    }

    public static void verifyRollbackSourceEvidence(JsonNode evidence, String actualController, String actualLoader) {
        fields(evidence, Set.of("format", "sources", "derived", "loaderIdentity"));
        text(evidence, "format", ROLLBACK_SOURCE_FORMAT);
        String controller = sourceController(evidence), loader = sourceText(evidence.path("sources"), "loader");
        fields(evidence.path("loaderIdentity"), Set.of("characters", "utf8Bytes", "sha256"));
        bodyIdentity(evidence.path("loaderIdentity"), loader);
        if (!controller.equals(actualController) || !loader.equals(actualLoader))
            throw new IllegalArgumentException("Rollback source view differs from the actual fixed program");
    }

    public static void verifyInstallationEvidence(JsonNode evidence, String executable) {
        String restored = reconstructInstallationEvidence(evidence);
        if (!restored.equals(executable) || !Arrays.equals(restored.getBytes(StandardCharsets.UTF_8), executable.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Structured publication input differs from the exact executable");
    }

    private static Map<String, Object> sourcePointer(String pointer, String body) {
        return Map.of("representation", "LOSSLESS_RECONSTRUCTION_DATA_NOT_PERMISSION", "pointer", pointer,
            "characters", body.length(), "utf8Bytes", body.getBytes(StandardCharsets.UTF_8).length, "sha256", sha256(body));
    }

    /** Rebuilds the actual canonical request before exposing full executable facts. This method creates no approval. */
    public static Map<String, Object> reviewFacts(String operation, CommandRequest command, DeploymentRemoteLayout layout) {
        if (!Set.of(READINESS, INSTALL, ROLLBACK).contains(operation))
            throw new IllegalArgumentException("Unknown publication recovery operation");
        var program = new DeploymentAgentPublicationRecoveryProgram(layout);
        String prefix = operation.equals(INSTALL) ? LAUNCHER : "/usr/bin/python3 -I -S -c " + quote(program.loader()) + " ";
        if (!command.script().startsWith(prefix) || !command.timeout().equals(TIMEOUT) || command.outputLimitBytes() != OUTPUT_LIMIT)
            throw new IllegalArgumentException("Publication recovery request changed");
        String suffix = command.script().substring(prefix.length());
        var argv = new ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile("'([a-z0-9-]+)'(?: |$)").matcher(suffix);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() != cursor) throw new IllegalArgumentException("Noncanonical publication recovery arguments");
            argv.add(matcher.group(1));
            cursor = matcher.end();
        }
        int count = operation.equals(INSTALL) ? 2 : operation.equals(READINESS) ? 3 : 9;
        if (cursor != suffix.length() || argv.size() != count || !operation.equals(INSTALL)
            && !argv.removeFirst().equals(operation.equals(READINESS) ? "readiness" : "invoke"))
            throw new IllegalArgumentException("Publication recovery arguments changed");
        uuid(argv.get(0));
        if (!argv.get(1).matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Publication recovery action changed");
        if (operation.equals(ROLLBACK)) {
            uuid(argv.get(2));
            String app = argv.get(3);
            if (!app.matches("[a-z0-9][a-z0-9-]{0,62}") || !argv.get(5).matches(java.util.regex.Pattern.quote(app) + "-[a-f0-9]{16}"))
                throw new IllegalArgumentException("Publication recovery candidate changed");
            digest(argv.get(4)); digest(argv.get(6)); digest(argv.get(7));
        }
        byte[] input = command.stdin();
        try {
            byte[] expected = operation.equals(INSTALL) ? program.installer().getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (!Arrays.equals(input, expected)) throw new IllegalArgumentException("Publication recovery executable input changed");
            var facts = new LinkedHashMap<String, Object>();
            facts.putAll(Map.of("manifest", program.manifest(), "version", program.version(), "path", program.path(),
                "typedArguments", List.copyOf(argv), "stdinBytes", input.length, "stdinSha256", sha256(input),
                "completeDecodedSource", operation.equals(INSTALL)
                    ? sourcePointer("/completeExecutableInput/derived/controller", program.source()) : operation.equals(ROLLBACK)
                    ? sourcePointer("/publicationRecoveryProgram/sourceView/derived/controller", program.source()) : program.source(),
                "completeLoaderSource", operation.equals(INSTALL)
                    ? sourcePointer("/completeExecutableInput/sources/loader", program.loader()) : operation.equals(ROLLBACK)
                    ? sourcePointer("/publicationRecoveryProgram/sourceView/sources/loader", program.loader()) : program.loader(),
                "sourceBytes", program.source().getBytes(StandardCharsets.UTF_8).length,
                "scope", "One guarded native-first rollback plus its exact owned maintenance marker; not candidate cleanup, task settlement, permission or retry authority"));
            if (operation.equals(ROLLBACK)) facts.put("sourceView", program.rollbackSourceEvidence());
            facts.put("reviewContract", REVIEW_CONTRACT);
            return Collections.unmodifiableMap(facts);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    public StoreReceipt parseStoreReceipt(Request request, OperationResult<CommandResult> result) {
        requireRequest(request);
        if (!Set.of(INSTALL, READINESS).contains(request.operation())) throw new IllegalArgumentException("Not a store operation");
        var value = frame(result, "PUBLICATION_RECOVERY_STORE=");
        var fields = new HashSet<>(Set.of("format", "contract", "version", "sourceSha256", "sourceBytes", "layoutSha256", "path",
            "installOperation", "installTask", "task", "action", "status"));
        if (request.operation().equals(INSTALL)) fields.add("requestedInstallOperation");
        fields(value, fields);
        text(value, "format", REVISION); text(value, "contract", REVISION); identity(value);
        text(value, "task", request.task().toString()); text(value, "action", actionId(request.action()));
        if (!value.path("sourceBytes").isIntegralNumber() || !value.path("sourceBytes").canConvertToInt()
            || value.path("sourceBytes").intValue() != source().getBytes(StandardCharsets.UTF_8).length)
            throw new IllegalArgumentException("Recovery source length changed");
        String status = textual(value, "status"), task = textual(value, "installTask"), action = textual(value, "installOperation");
        if (status.equals("READY")) {
            uuid(task);
            if (!action.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Recovery installation identity missing");
        } else if (!status.equals("MISSING") || !task.equals("-") || !action.equals("-") || request.operation().equals(INSTALL))
            throw new IllegalArgumentException("Recovery store is unconfirmed");
        if (request.operation().equals(INSTALL)) text(value, "requestedInstallOperation", actionId(request.action()));
        return new StoreReceipt(status, task, action);
    }

    public RollbackReceipt parseRollbackReceipt(Request request, OperationResult<CommandResult> result) {
        requireRequest(request);
        if (!request.operation().equals(ROLLBACK)) throw new IllegalArgumentException("Not a guarded rollback");
        Rollback rollback = request.rollback().orElseThrow();
        var value = frame(result, "PUBLICATION_RECOVERY_ROLLBACK=");
        fields(value, Set.of("format", "version", "sourceSha256", "layoutSha256", "task", "action", "execution", "unit", "owner",
            "candidate", "release", "planSha256", "currentAbsent", "unitAbsent", "releaseAbsent", "maintenanceAbsent"));
        text(value, "format", "WTL_AGENT_PUBLICATION_ROLLBACK_1");
        text(value, "version", version()); text(value, "sourceSha256", sha256(source())); text(value, "layoutSha256", layout.digest());
        text(value, "task", request.task().toString()); text(value, "action", actionId(request.action()));
        text(value, "execution", rollback.execution().toString()); text(value, "unit", request.unit().id());
        text(value, "owner", request.unit().ownershipManifestSha256()); text(value, "candidate", rollback.candidate());
        text(value, "release", rollback.expectedRelease()); text(value, "planSha256", rollback.planSha256());
        for (String key : List.of("currentAbsent", "unitAbsent", "releaseAbsent", "maintenanceAbsent"))
            if (!value.path(key).isBoolean() || !value.path(key).booleanValue()) throw new IllegalArgumentException("Recovery result incomplete");
        return new RollbackReceipt(request.task(), request.action(), rollback.execution(), request.unit().id(), rollback.expectedRelease(), rollback.planSha256());
    }

    private void identity(JsonNode value) {
        text(value, "version", version()); text(value, "sourceSha256", sha256(source()));
        text(value, "layoutSha256", layout.digest()); text(value, "path", path());
    }

    private void requireRequest(Request request) {
        try (var expected = request(request.operation(), request.unit(), request.task(), request.action(), request.rollback())) {
            if (!request.resourceTargets().equals(expected.resourceTargets()) || !request.command().script().equals(expected.command().script())
                || !Arrays.equals(request.command().stdin(), expected.command().stdin()) || !request.command().timeout().equals(TIMEOUT)
                || request.command().outputLimitBytes() != OUTPUT_LIMIT)
                throw new IllegalArgumentException("Original recovery request changed");
        }
    }

    private static JsonNode frame(OperationResult<CommandResult> result, String prefix) {
        if (result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()) throw new IllegalArgumentException("Recovery outcome unknown");
        CommandResult command = result.value().orElseThrow();
        String body = command.stdout();
        if (!command.exitCode().equals(Optional.of(0)) || command.truncated() || !command.stderr().isEmpty()
            || body.length() > 4096 || !body.startsWith(prefix) || !body.endsWith("\n") || body.indexOf('\n') != body.length() - 1)
            throw new IllegalArgumentException("Recovery receipt unconfirmed");
        try {
            return JSON.readTree(body.substring(prefix.length(), body.length() - 1));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Recovery receipt invalid");
        }
    }

    private static void fields(JsonNode value, Set<String> expected) {
        var actual = new HashSet<String>();
        if (value == null || !value.isObject()) throw new IllegalArgumentException("Recovery receipt object missing");
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("Recovery receipt fields changed");
    }

    private static String textual(JsonNode node, String key) {
        if (!node.path(key).isTextual()) throw new IllegalArgumentException("Recovery receipt text missing");
        return node.path(key).textValue();
    }

    private static void text(JsonNode node, String key, String expected) {
        if (!textual(node, key).equals(expected)) throw new IllegalArgumentException("Recovery receipt identity changed");
    }

    private static UUID uuid(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Noncanonical original UUID");
        return id;
    }

    private static void digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Original SHA-256 missing");
    }

    private static String actionId(UUID action) {
        return Objects.requireNonNull(action).toString().replace("-", "");
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("Recovery source cannot be encoded", failure); }
    }

    public static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String resource(String name) {
        try (var stream = DeploymentAgentPublicationRecoveryProgram.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Fixed recovery resource absent");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (java.io.IOException failure) { throw new IllegalStateException("Fixed recovery resource unavailable", failure); }
    }
}
