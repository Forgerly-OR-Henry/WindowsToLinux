package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;

/** Independent followup diagnostic. Old source identities and the consumed old attempt remain unchanged. */
public final class DeploymentAgentPublicationContractDiagnosticFollowupProgram {
    public static final String OPERATION = "deployment.agent.publication-contract-diagnostic.followup.v1";
    public static final String FORMAT = "WTL_AGENT_PUBLICATION_CONTRACT_DIAGNOSTIC_FOLLOWUP_1";
    public static final String FRAME = "PUBLICATION_CONTRACT_DIAGNOSTIC_FOLLOWUP=";
    public static final List<String> CHECK_IDS = List.of("FULL_PARAMETER_VALIDATION", "MANAGED_DATA_LINKS", "UNIT_RENDER_MATCH");
    private static final Set<String> PHASES = Set.of("ENVIRONMENT", "ARGUMENTS", "ROOT_IDENTITY", "LOCKS", "CANDIDATE", "FINGERPRINTS", "BUILD", "SNAPSHOTS", "CURRENT", "MAINTENANCE", "FULL_PARAMETER_VALIDATION", "MANAGED_DATA_LINKS", "UNIT_RENDER_MATCH", "STABILITY", "COMPLETE", "CONTROL");
    private static final Set<String> CONTROL_REASONS = Set.of("EMPTY_CONTROL", "STDOUT_PRESENT", "FRAME_SHAPE", "FRAME_ORDER", "NO_ROOT_EXIT", "EXIT_MISMATCH", "INCOMPLETE_CONTROL", "LIMIT");
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final long OUTPUT_LIMIT = 16384;
    private static final String ROOT = "/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/";
    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final DeploymentRemoteLayout layout;
    private final String source;

    public record PriorDiagnostic(UUID action, long requestEvent, long endEvent, String requestDetailSha256,
                                    String receiptSha256, String sourceVersion) {
        public PriorDiagnostic {
            Objects.requireNonNull(action);
            if (requestEvent <= 0 || endEvent <= requestEvent) throw invalid();
            digest(requestDetailSha256); digest(receiptSha256); digest(sourceVersion);
        }
    }
    public record Binding(DeploymentAgentPublicationContractDiagnosticProgram.Binding diagnostic, PriorDiagnostic prior,
                          long observationEnd, String observationSha256) {
        public Binding {
            Objects.requireNonNull(diagnostic); Objects.requireNonNull(prior); digest(observationSha256);
            var c=diagnostic.continuation();
            if (prior.requestEvent() <= diagnostic.observationEnd() || observationEnd <= prior.endEvent()
                || prior.action().equals(c.original().pendingAction()) || prior.action().equals(c.preflight().action())
                || prior.action().equals(diagnostic.prior().action())) throw invalid();
        }
    }
    public record Request(CommandRequest command, Set<ActionTarget> resourceTargets, Binding binding, UUID action) implements AutoCloseable {
        public Request { Objects.requireNonNull(command); resourceTargets = Set.copyOf(resourceTargets); Objects.requireNonNull(binding); Objects.requireNonNull(action); }
        @Override public void close() { command.close(); }
    }
    public record Receipt(String canonicalJson) { public Receipt { Objects.requireNonNull(canonicalJson); } }

    public DeploymentAgentPublicationContractDiagnosticFollowupProgram(DeploymentRemoteLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        for (String root : List.of(layout.paths().helperDirectory(), layout.paths().applicationsRoot(), layout.paths().stateRoot(), layout.paths().configurationRoot(), layout.paths().dataRoot()))
            if (!root.matches("/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+") || root.contains("..")) throw invalid();
        String original = DeploymentManagedHelperBundle.renderPublicationRecovery(layout);
        String suffix = "\n" + DeploymentRemoteLayoutTemplate.render(resource("publication-recovery-guard.sh"), layout);
        int cut = original.length() - suffix.length();
        if (cut < 0 || !original.endsWith(suffix) || original.indexOf(suffix) != cut) throw invalid();
        // Exact, unique known suffix only. No old immediate entry is executed or included.
        // The unchanged recovery base ends inside native_database's Python here-document.
        // Complete that original definition with its two original continuation resources.
        String base = original.substring(0, cut);
        for (String fragment : List.of("20-native-targets.sh", "21-native-operations.sh"))
            base += DeploymentRemoteLayoutTemplate.render(resourcePath("/gold/debug/windowstolinux/mod/deployment/linux/helper/database/" + fragment), layout);
        String bash = base + "\n" + DeploymentRemoteLayoutTemplate.render(resource("publication-contract-diagnostic-guard.sh"), layout);
        String template = resource("publication-contract-diagnostic-followup.py"), marker = "# @WTL_CONTRACT_DIAGNOSTIC_BASH@";
        if (template.indexOf(marker) < 0 || template.indexOf(marker) != template.lastIndexOf(marker)) throw invalid();
        source = template.replace(marker, "BASH_SOURCE = " + JSON.valueToTree(bash).toString());
        if (source.getBytes(StandardCharsets.UTF_8).length > 524288) throw invalid();
    }
    public String source() { return source; }
    /** Product-bound expected stdin SHA, never an independent remote measurement. */
    public String sourceVersion() { return sha(source.getBytes(StandardCharsets.UTF_8)); }
    public Request request(Binding binding, UUID action) {
        Objects.requireNonNull(binding); Objects.requireNonNull(action);
        var d = binding.diagnostic(); var c = d.continuation(); var o = c.original();
        var priorProgram = new DeploymentAgentPublicationContractDiagnosticProgram(layout);
        if (!o.layoutSha256().equals(layout.digest()) || !binding.prior().sourceVersion().equals(priorProgram.sourceVersion())
            || Set.of(o.pendingAction(), c.preflight().action(), d.prior().action(), binding.prior().action()).contains(action)) throw invalid();
        try (var priorRequest = priorProgram.request(d, binding.prior().action())) {
            // Reuse the old fixed factory's nested source/identity rules without sending it.
        }
        var n = JSON.createObjectNode(); n.set("binding", JSON.valueToTree(binding)); n.put("action", action.toString()).put("sourceVersion", sourceVersion());
        String argument = n.toString(); if (argument.getBytes(StandardCharsets.UTF_8).length > 12000) throw invalid();
        return new Request(new CommandRequest(script(argument, n), source.getBytes(StandardCharsets.UTF_8), TIMEOUT, OUTPUT_LIMIT), targets(binding), binding, action);
    }
    private String script(String argument, JsonNode envelope) {
        var refused = JSON.createObjectNode().put("format", FORMAT).put("sourceVersion", sourceVersion()).put("action", text(envelope, "action"));
        refused.set("binding", envelope.path("binding")); refused.put("status", "REFUSED").put("phase", "ENVIRONMENT").put("reason", "REJECTED").putNull("exitCode");
        var checks = refused.putArray("checks"); for (String key : CHECK_IDS) checks.addObject().put("id", key).put("status", "UNKNOWN").put("reason", "NOT_CHECKED").putNull("exitCode");
        String fail = "builtin printf '%s\\n' " + quote(FRAME + refused) + "; exit 0";
        return "wtl_argument=" + quote(argument) + "\nset -fu\nLC_ALL=C\nbuiltin export LC_ALL\nif [[ ${BASH_ENV+x} != x || ${ENV+x} != x || \"$BASH_ENV\" != /dev/null || \"$ENV\" != /dev/null || ${LD_PRELOAD+x} || ${LD_LIBRARY_PATH+x} ]]; then " + fail + "; fi\nbuiltin unset BASH_ENV ENV || { " + fail + "; }\nexec /usr/bin/python3 -I -S - \"$wtl_argument\" 'SDK_DEV_NULL_SENTINELS_REMOVED_CONTRACT_DIAGNOSTIC_FOLLOWUP_V1'\n";
    }
    private Set<ActionTarget> targets(Binding b) {
        var o = b.diagnostic().continuation().original(); String s = o.server(), u = o.unit();
        return Set.of(new ActionTarget(s, "task", o.task().toString()), new ActionTarget(s, "agent-publication-contract-diagnostic-followup", sourceVersion()),
            new ActionTarget(s, "application", u), new ActionTarget(s, "deployment-release", layout.applicationRoot(u) + "/releases/" + o.releaseSha256()),
            new ActionTarget(s, "deployment-build", layout.candidateRoot(o.candidate())), new ActionTarget(s, "deployment-build", layout.applicationLockRoot(u)),
            new ActionTarget(s, "deployment-snapshot", layout.snapshotsRoot()), new ActionTarget(s, "systemd-unit", "windowstolinux-" + u + ".service"),
            new ActionTarget(s, "systemd-unit", "windowstolinux-build-" + o.candidate() + ".service"));
    }
    public static Map<String, Object> reviewFacts(CommandRequest command, DeploymentRemoteLayout layout) {
        var p = new DeploymentAgentPublicationContractDiagnosticFollowupProgram(layout);
        try (var r = p.decode(command)) {
            return Map.of("operation", OPERATION, "binding", JSON.valueToTree(r.binding()), "action", r.action().toString(), "expectedSourceVersion", p.sourceVersion(),
                "executableInputReference", "commandEvidence.completeExecutableInput", "scope", "Independent read-only followup, never rollback/lifecycle/cleanup/install/retry/ACK/goal or settlement. The old diagnostic source/receipt and newer observation are mandatory; a new version never resets its once gate. Historical six fingerprints are bound prior facts, not newly measured by observationEnd. Existing locks opened read-only; actual original parameter, managed-data and unit-render contracts run directly with errexit and a dedicated inherited control pipe. SDK sentinels and LD presence checked before Python; complete bounded function/environment classification after Python startup before diagnostic IO. No raw native stderr or reject arguments are receipt data. Full fixed stdin delivered once. sourceVersion is product-bound expected stdin SHA.");
        }
    }
    public static Set<ActionTarget> reviewedResourceTargets(CommandRequest command, DeploymentRemoteLayout layout, ServerId server) {
        var p = new DeploymentAgentPublicationContractDiagnosticFollowupProgram(layout);
        try (var r = p.decode(command)) { if (!r.binding().diagnostic().continuation().original().server().equals(server.id())) throw invalid(); return r.resourceTargets(); }
    }
    public Request decode(CommandRequest command) {
        String prefix = "wtl_argument='";
        if (!command.script().startsWith(prefix) || !command.timeout().equals(TIMEOUT) || command.outputLimitBytes() != OUTPUT_LIMIT || !Arrays.equals(command.stdin(), source.getBytes(StandardCharsets.UTF_8))) throw invalid();
        int end = command.script().indexOf("'\n", prefix.length()); if (end < 0) throw invalid();
        String argument = command.script().substring(prefix.length(), end);
        try {
            var n = JSON.readTree(argument); fields(n, Set.of("binding", "action", "sourceVersion"));
            if (!n.toString().equals(argument) || !text(n, "sourceVersion").equals(sourceVersion())) throw invalid();
            var r = request(readBinding(n.path("binding")), uuid(text(n, "action")));
            if (!r.command().script().equals(command.script())) { r.close(); throw invalid(); } return r;
        } catch (IOException e) { throw invalid(); }
    }
    public Receipt parse(Request request, OperationResult<CommandResult> actual) {
        if (actual.outcome() != TaskResult.SUCCEEDED || actual.value().isEmpty()) throw invalid();
        var r = actual.value().orElseThrow(); String out = r.stdout();
        if (r.truncated() || r.exitCode().isEmpty() || r.exitCode().orElseThrow() != 0 || !r.stderr().isEmpty() || !out.startsWith(FRAME)
            || !out.endsWith("\n") || out.indexOf('\n') != out.length() - 1 || out.indexOf('\r') >= 0 || out.getBytes(StandardCharsets.UTF_8).length > OUTPUT_LIMIT) throw invalid();
        return validateReceipt(request, out.substring(FRAME.length(), out.length() - 1));
    }
    public Receipt validateReceipt(Request request, String canonicalJson) {
        try (var r = decode(request.command())) {
            if (!r.binding().equals(request.binding()) || !r.action().equals(request.action()) || !r.resourceTargets().equals(request.resourceTargets())
                || canonicalJson == null || canonicalJson.getBytes(StandardCharsets.UTF_8).length + FRAME.length() + 1 > OUTPUT_LIMIT) throw invalid();
            var n = JSON.readTree(canonicalJson); fields(n, Set.of("format", "sourceVersion", "action", "binding", "status", "phase", "reason", "exitCode", "checks"));
            if (!n.toString().equals(canonicalJson) || !text(n, "format").equals(FORMAT) || !text(n, "sourceVersion").equals(sourceVersion())
                || !uuid(text(n, "action")).equals(request.action()) || !readBinding(n.path("binding")).equals(request.binding())) throw invalid();
            String status = text(n, "status"), phase = text(n, "phase"), reason = text(n, "reason"); Integer exit = exit(n);
            if (!Set.of("OBSERVED", "REFUSED", "UNKNOWN").contains(status) || !PHASES.contains(phase)
                || !(Set.of("MATCH", "REJECTED", "NOT_CHECKED", "UNCONFIRMED").contains(reason) || CONTROL_REASONS.contains(reason)) || !n.path("checks").isArray() || n.path("checks").size() != 3) throw invalid();
            int passes = 0; boolean stopped = false; int failed = -1; boolean unresolved = false;
            for (int i = 0; i < 3; i++) {
                var check = n.path("checks").get(i); fields(check, Set.of("id", "status", "reason", "exitCode"));
                if (!text(check, "id").equals(CHECK_IDS.get(i))) throw invalid();
                String state = text(check, "status"), why = text(check, "reason"); Integer code = exit(check);
                if (state.equals("PASS")) { if (stopped || !why.equals("MATCH") || !Objects.equals(code, 0)) throw invalid(); passes++; }
                else if (state.equals("FAIL")) { if (stopped || !why.equals("REJECTED") || !Objects.equals(code, 64)) throw invalid(); stopped = true; failed = i; }
                else if (state.equals("UNKNOWN")) {
                    if (why.equals("NOT_CHECKED") && code == null) stopped = true;
                    else if (!stopped && why.equals("UNCONFIRMED") && code != null && code > 0 && Objects.equals(code, exit)) { stopped = true; unresolved = true; }
                    else throw invalid();
                } else throw invalid();
            }
            if (status.equals("OBSERVED")) {
                if (passes != 3 || !phase.equals("COMPLETE") || !reason.equals("MATCH") || !Objects.equals(exit, 0)) throw invalid();
            } else if (status.equals("REFUSED")) {
                if (!reason.equals("REJECTED") || phase.equals("COMPLETE") || unresolved) throw invalid();
                if (CHECK_IDS.contains(phase)) { if (failed != CHECK_IDS.indexOf(phase) || passes != failed || !Objects.equals(exit, 64)) throw invalid(); }
                else if (failed != -1 || passes != (phase.equals("STABILITY") ? 3 : 0) || !(exit == null && Set.of("ENVIRONMENT", "ROOT_IDENTITY").contains(phase) || Objects.equals(exit, 64))) throw invalid();
            } else {
                if (failed != -1 || phase.equals("COMPLETE") || !(phase.equals("CONTROL") ? CONTROL_REASONS.contains(reason) : reason.equals("UNCONFIRMED"))) throw invalid();
                if (phase.equals("CONTROL")) { if (passes != 0 || unresolved) throw invalid(); }
                else if (unresolved) { if (!CHECK_IDS.contains(phase) || passes != CHECK_IDS.indexOf(phase) || exit == null || exit <= 0) throw invalid(); }
                else if (CHECK_IDS.contains(phase) || passes != (phase.equals("STABILITY") ? 3 : 0) || exit == null || exit <= 0) throw invalid();
            }
            return new Receipt(n.toString());
        } catch (IOException e) { throw invalid(); }
    }
    private static Binding readBinding(JsonNode n) throws IOException {
        fields(n, Set.of("diagnostic", "prior", "observationEnd", "observationSha256"));
        var d=n.path("diagnostic");fields(d,Set.of("continuation","prior","observationEnd","observationSha256"));
        var c=d.path("continuation");fields(c,Set.of("original","pendingResultEvent","pendingResultSha256","preflight","current"));
        fields(c.path("original"),Set.of("server","task","execution","pendingAction","pendingIntentEvent","pendingIntentSha256","planSha256","unit","ownerSha256","candidate","releaseSha256","layoutSha256"));
        fields(c.path("preflight"),Set.of("action","requestEvent","endEvent","requestDetailSha256","receiptSha256"));
        fields(c.path("current"),Set.of("candidateOwnerSha256","candidateTaskSha256","maintenanceSha256","releaseOwnerSha256","parametersSha256","unitSha256"));
        for(var p:List.of(d.path("prior"),n.path("prior"))) {
            fields(p,Set.of("action","requestEvent","endEvent","requestDetailSha256","receiptSha256","sourceVersion"));
            positive(p,"requestEvent");positive(p,"endEvent");uuid(text(p,"action"));
        }
        positive(n,"observationEnd");positive(d,"observationEnd");positive(c,"pendingResultEvent");positive(c.path("original"),"pendingIntentEvent");
        positive(c.path("preflight"),"requestEvent");positive(c.path("preflight"),"endEvent");uuid(text(c.path("preflight"),"action"));
        for(String k:List.of("task","execution","pendingAction"))uuid(text(c.path("original"),k));
        var b=JSON.treeToValue(n,Binding.class);
        if(!JSON.readTree(JSON.writeValueAsBytes(b)).equals(n))throw invalid();return b;
    }
    private static Integer exit(JsonNode n) { var v = n.path("exitCode"); if (v.isNull()) return null; if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < -64 || v.intValue() > 255) throw invalid(); return v.intValue(); }
    private static void positive(JsonNode n, String k) { if (!n.path(k).isIntegralNumber() || !n.path(k).canConvertToLong() || n.path(k).longValue() <= 0) throw invalid(); }
    private static void fields(JsonNode n, Set<String> expected) { if (!n.isObject()) throw invalid(); var keys = new HashSet<String>(); n.fieldNames().forEachRemaining(keys::add); if (!keys.equals(expected)) throw invalid(); }
    private static String text(JsonNode n, String k) { if (!n.path(k).isTextual()) throw invalid(); return n.path(k).textValue(); }
    private static UUID uuid(String s) { var u = UUID.fromString(s); if (!u.toString().equals(s)) throw invalid(); return u; }
    private static void digest(String s) { if (s == null || !s.matches("[a-f0-9]{64}")) throw invalid(); }
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private static String sha(byte[] b) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private static String resource(String name) { return resourcePath(ROOT + name); }
    private static String resourcePath(String path) { try (var in = DeploymentAgentPublicationContractDiagnosticFollowupProgram.class.getResourceAsStream(path)) { if (in == null) throw invalid(); byte[] b = in.readNBytes(1048577); if (b.length > 1048576) throw invalid(); return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString().replace("\r\n", "\n"); } catch (IOException e) { throw new IllegalStateException("Fixed diagnostic resource unavailable", e); } }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Fixed followup contract diagnostic evidence invalid"); }
}
