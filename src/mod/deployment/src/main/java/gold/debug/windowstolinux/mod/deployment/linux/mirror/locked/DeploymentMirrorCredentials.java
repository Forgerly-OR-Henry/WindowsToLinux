package gold.debug.windowstolinux.mod.deployment.linux.mirror.locked;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter;
import gold.debug.windowstolinux.mod.deployment.service.mirror.ResolvedMirrorPolicy;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentSecretStore;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;

/** Opaque, task-bound transport of mirror credentials before any optional remote runtime exists. */
public final class DeploymentMirrorCredentials {
    public static final String PURPOSE = "deployment.mirror.transport.v1";
    public static final String STAGE = "deployment.mirror.material.stage.v1";
    public static final String DISCARD = "deployment.mirror.material.discard.v1";
    private static final Pattern FRAME = Pattern.compile("\\A# WTL_MIRROR_MATERIAL=(stage|discard) ([0-9a-f-]{36}) ([0-9a-f]{64}) ([0-9a-f]{64}) ([0-9a-f]{64}|-) ([0-9a-f]{64}|-) ([0-9]{1,6})\\n");
    private static final String RESOURCE = "/gold/debug/windowstolinux/mod/deployment/linux/mirror/locked/stage-material.sh";

    private DeploymentMirrorCredentials() { }

    public static OperationResult<Void> stage(ModContext context, ServerId server, TaskContext task,
                                               String policyJson, DeploymentRemoteLayout layout) throws ApiException {
        ResolvedMirrorPolicy.fromJson(policyJson, task.id().toString(), server.id());
        var references = references(policyJson);
        if (references.isEmpty()) return success();
        try (var session = context.linux().connect(server, task)) {
            for (var reference : references.entrySet()) {
                var parts = reference.getKey().split(":", -1);
                if (parts.length != 2) throw new IllegalArgumentException("Mirror credentials require an immutable business revision");
                gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRevision revision;
                try {
                    revision = new DeploymentSecretStore(context.db()).findRevision(task,
                        new DeploymentSecretRef(parts[0], Long.parseLong(parts[1])))
                        .orElseThrow(() -> new IllegalArgumentException("Selected mirror credential revision is unavailable"));
                } catch (java.sql.SQLException failure) {
                    throw new IllegalArgumentException("Mirror credential revision could not be read");
                }
                if (!revision.purpose().orElse("").equals(PURPOSE))
                    throw new IllegalArgumentException("Selected credential has a different purpose");
                var observed = context.secrets().use(new SecretId(revision.credentialId(), PURPOSE), PURPOSE, task, secret -> {
                    byte[] material = material(secret, reference.getValue());
                    try (var request = request(task.id().toString(), server.id(), policyJson, layout, reference.getKey(), material)) {
                        var intent = RemoteActions.command(server, sha256(policyJson.getBytes(StandardCharsets.UTF_8)), request)
                            .withSemantics(new ActionSemantics(STAGE, targets(server.id(), task.id().toString(), directory(layout,
                                task.id().toString(), sha256(server.id().getBytes(StandardCharsets.UTF_8)), sha256(policyJson.getBytes(StandardCharsets.UTF_8))))));
                        var grant = DeploymentCommandReviewRouter.approve(context.interaction(), intent, request, task);
                        if (grant.outcome() != TaskResult.SUCCEEDED)
                            return new OperationResult<Void>(grant.outcome(), Optional.empty(), grant.problem());
                        var result = session.execute(request, grant.value().orElseThrow());
                        if (result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent()
                            && result.value().orElseThrow().exitCode().orElse(-1) == 0 && !result.value().orElseThrow().truncated()
                            && result.value().orElseThrow().stdout().equals("MIRROR_MATERIAL_STAGED=1\n")) return success();
                        return uncertain();
                    } finally { Arrays.fill(material, (byte) 0); }
                });
                if (observed.outcome() != TaskResult.SUCCEEDED) return observed;
            }
        }
        return success();
    }

    public static OperationResult<Void> discard(ModContext context, ServerId server, TaskContext task,
                                                 String policyJson, DeploymentRemoteLayout layout) throws ApiException {
        ResolvedMirrorPolicy.fromJson(policyJson, task.id().toString(), server.id());
        if (references(policyJson).isEmpty()) return success();
        String serverHash = sha256(server.id().getBytes(StandardCharsets.UTF_8));
        String policyHash = sha256(policyJson.getBytes(StandardCharsets.UTF_8));
        try (var request = new CommandRequest(script("discard", task.id().toString(), serverHash, policyHash, "-", "-", 0, layout),
            new byte[0], Duration.ofSeconds(30), 1024); var session = context.linux().connect(server, task)) {
            var intent = RemoteActions.command(server, policyHash, request).withSemantics(new ActionSemantics(DISCARD,
                targets(server.id(), task.id().toString(), directory(layout, task.id().toString(), serverHash, policyHash))));
            var grant = DeploymentCommandReviewRouter.approve(context.interaction(), intent, request, task);
            if (grant.outcome() != TaskResult.SUCCEEDED) return new OperationResult<>(grant.outcome(), Optional.empty(), grant.problem());
            var result = session.execute(request, grant.value().orElseThrow());
            if (result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent()
                && result.value().orElseThrow().exitCode().orElse(-1) == 0 && !result.value().orElseThrow().truncated()
                && result.value().orElseThrow().stdout().equals("MIRROR_MATERIAL_DISCARDED=1\n")) return success();
            return uncertain();
        }
    }

    public static CommandRequest request(String task, String server, String policyJson, DeploymentRemoteLayout layout,
                                          String reference, byte[] material) {
        UUID.fromString(task);
        if (material.length < 14 || material.length > 65536) throw new IllegalArgumentException("Mirror material size invalid");
        return new CommandRequest(script("stage", task, sha256(server.getBytes(StandardCharsets.UTF_8)),
            sha256(policyJson.getBytes(StandardCharsets.UTF_8)), sha256(reference.getBytes(StandardCharsets.UTF_8)),
            sha256(material), material.length, layout), material, Duration.ofSeconds(30), 1024);
    }

    public static Map<String, Object> inspect(ActionIntent intent, CommandRequest request, DeploymentRemoteLayout layout,
                                               TaskContext task, String frozenPolicyJson) {
        var match = FRAME.matcher(request.script());
        if (!match.find() || intent.serverIds().size() != 1) throw new IllegalArgumentException("Mirror material frame invalid");
        String mode = match.group(1), taskId = match.group(2), serverHash = match.group(3), policyHash = match.group(4);
        int bytes = Integer.parseInt(match.group(7));
        if (frozenPolicyJson == null || frozenPolicyJson.isBlank()
            || !policyHash.equals(sha256(frozenPolicyJson.getBytes(StandardCharsets.UTF_8))) || !policyHash.equals(intent.revision())
            || mode.equals("stage") && references(frozenPolicyJson).keySet().stream()
                .noneMatch(reference -> sha256(reference.getBytes(StandardCharsets.UTF_8)).equals(match.group(5)))
            || !task.id().toString().equals(taskId) || !serverHash.equals(sha256(intent.serverIds().getFirst().getBytes(StandardCharsets.UTF_8)))
            || !request.script().equals(script(mode, taskId, serverHash, policyHash, match.group(5), match.group(6), bytes, layout))
            || !intent.semantics().orElseThrow().operation().equals(mode.equals("stage") ? STAGE : DISCARD)
            || !intent.semantics().orElseThrow().targets().equals(targets(intent.serverIds().getFirst(), taskId, directory(layout, taskId, serverHash, policyHash))))
            throw new IllegalArgumentException("Mirror material task, server or destination differs");
        byte[] input = request.stdin();
        try {
            if (input.length != bytes || bytes > 65536 || (mode.equals("stage") && (bytes < 14 || !sha256(input).equals(match.group(6))))
                || (mode.equals("discard") && (bytes != 0 || !match.group(5).equals("-") || !match.group(6).equals("-"))))
                throw new IllegalArgumentException("Mirror material opaque input binding differs");
        } finally { Arrays.fill(input, (byte) 0); }
        return Map.of("task", taskId, "serverSha256", serverHash, "policySha256", policyHash,
            "referenceSha256", match.group(5), "payloadSha256", match.group(6), "payloadBytes", bytes,
            "destination", directory(layout, taskId, serverHash, policyHash), "inputKind", bytes == 0 ? "EMPTY" : "OPAQUE_BOUND_DATA",
            "meaning", "Exact fixed Bash receiver and task/server/policy/reference/length/digest binding. Protected input is copied as bytes, never sourced, evaluated, logged or supplied to a model. Root-owned task-private files are consumed only by the separately reviewed runtime and discarded at task completion. This is not permission or proof of remote state.");
    }

    public static String directory(DeploymentRemoteLayout layout, String task, String serverHash, String policyHash) {
        UUID.fromString(task);
        if (!serverHash.matches("[0-9a-f]{64}") || !policyHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Mirror binding invalid");
        return layout.paths().stateRoot() + "/mirror-material/" + task + "/" + serverHash + "/" + policyHash;
    }

    public static String environmentPrefix(String policyJson, DeploymentRemoteLayout layout) {
        if (policyJson == null || policyJson.isBlank() || references(policyJson).isEmpty()) return "";
        try {
            var document = new ObjectMapper().readTree(policyJson);
            String task = document.path("task").asText(), server = sha256(document.path("server").asText().getBytes(StandardCharsets.UTF_8));
            String policy = sha256(policyJson.getBytes(StandardCharsets.UTF_8));
            String path = directory(layout, task, server, policy);
            return "# WTL_MIRROR_BINDING=" + task + "/" + server + "/" + policy + "\nexport WTL_MIRROR_CREDENTIALS_SOURCE='" + path + "'\n";
        } catch (IOException failure) { throw new IllegalArgumentException("Mirror policy invalid"); }
    }

    static byte[] material(byte[] secret, boolean system) {
        try {
            if (secret.length > 65536) throw new IllegalArgumentException();
            var json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(secret);
            if (!json.isObject() || !json.path("formatVersion").isInt() || json.path("formatVersion").asInt() != 1
                || json.has("authorization") && !json.path("authorization").isTextual()
                || json.has("caPem") && !json.path("caPem").isTextual()) throw new IllegalArgumentException();
            var names = new HashSet<String>(); json.fieldNames().forEachRemaining(names::add);
            if (!Set.of("formatVersion", "authorization", "caPem").containsAll(names)) throw new IllegalArgumentException();
            String authorization = json.path("authorization").asText(""), ca = json.path("caPem").asText("");
            if (authorization.isEmpty() && ca.isEmpty()) throw new IllegalArgumentException();
            if (!authorization.isEmpty() && (!authorization.matches("(?:Basic [A-Za-z0-9+/]+=*|Bearer [A-Za-z0-9._~+/-]+=*)")
                || authorization.length() > 8192 || system && !authorization.startsWith("Basic "))) throw new IllegalArgumentException();
            if (authorization.startsWith("Basic ")) {
                byte[] basic = Base64.getDecoder().decode(authorization.substring(6));
                try {
                    boolean colon = false;
                    for (byte value : basic) {
                        if (value == ':') colon = true;
                        if (value < 32 || value > 126 || system && (value <= 32 || value == '"' || value == '\'' || value == '\\'))
                            throw new IllegalArgumentException();
                    }
                    if (!colon) throw new IllegalArgumentException();
                } finally { Arrays.fill(basic, (byte) 0); }
            }
            if (!ca.isEmpty()) {
                var certificates = java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificates(new java.io.ByteArrayInputStream(ca.getBytes(StandardCharsets.US_ASCII)));
                if (certificates.isEmpty() || !ca.matches("(?s)(?:\\s*-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\r\\n]+-----END CERTIFICATE-----\\s*)+"))
                    throw new IllegalArgumentException();
            }
            byte[] result = ("WTL-MIRROR-1\n" + authorization + "\n" + ca).getBytes(StandardCharsets.UTF_8);
            if (result.length > 65536) { Arrays.fill(result, (byte) 0); throw new IllegalArgumentException(); }
            return result;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Mirror credential must contain valid Authorization/CA material; system package sources require Basic authentication");
        }
    }

    private static Map<String, Boolean> references(String policyJson) {
        try {
            var references = new LinkedHashMap<String, Boolean>();
            var points = new ObjectMapper().readTree(policyJson).path("points");
            points.fields().forEachRemaining(point -> point.getValue().forEach(endpoint -> {
                String reference = endpoint.path("secretRef").asText("");
                if (!reference.isEmpty()) references.merge(reference, point.getKey().startsWith("APT_") || point.getKey().startsWith("DNF_"), Boolean::logicalOr);
            }));
            return references;
        } catch (IOException failure) { throw new IllegalArgumentException("Mirror policy invalid"); }
    }

    private static Set<ActionTarget> targets(String server, String task, String directory) {
        return Set.of(new ActionTarget(server, "task", task), new ActionTarget(server, "deployment-mirror-material", directory));
    }

    private static String script(String mode, String task, String server, String policy, String reference, String digest, int bytes, DeploymentRemoteLayout layout) {
        String directory = directory(layout, task, server, policy);
        try (var input = DeploymentMirrorCredentials.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Mirror material receiver absent");
            return "# WTL_MIRROR_MATERIAL=" + mode + " " + task + " " + server + " " + policy + " " + reference + " " + digest + " " + bytes + "\n"
                + "wtl_material_mode='" + mode + "'\nwtl_material_directory='" + directory + "'\nwtl_material_reference='" + reference
                + "'\nwtl_material_sha='" + digest + "'\nwtl_material_size=" + bytes + "\n"
                + new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException failure) { throw new IllegalStateException("Mirror material receiver unreadable", failure); }
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static OperationResult<Void> success() { return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty()); }
    private static OperationResult<Void> uncertain() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
            "DEPLOYMENT_MIRROR_MATERIAL_UNCONFIRMED", "Mirror credential staging or cleanup has no verified terminal receipt", Map.of(), Optional.empty())));
    }
}
