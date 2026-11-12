package gold.debug.windowstolinux.mod.deployment.service.mirror;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentMirrorStore;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter;

/** Durable task snapshots; the activation-local cache never reads changing user settings during dispatch. */
public final class DeploymentMirrorTasks {
    public static final String CHECKPOINT = "deployment.mirrors.policy.v1";
    private static final String SELECTION = "deployment.mirrors.selection.v1";
    private static final Map<UUID, String> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<UUID, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout> LAYOUTS = new ConcurrentHashMap<>();
    private static final Set<UUID> CLEANED = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> ADMITTED = ConcurrentHashMap.newKeySet();
    private static volatile BooleanSupplier privacy = () -> false;
    private static final String PREPARATION_END = "\n)\n";
    private static final String COMPATIBILITY_LAUNCHER =
        "exec /usr/bin/env -i LC_ALL=C PATH=/usr/bin:/bin /bin/bash --noprofile --norc -p -s";
    private static final String COMPATIBILITY_SCRIPT = """
        set -eu
        while IFS='=' read -r key value; do
          [ "$key" = ID ] || continue
          value="$(builtin printf '%s' "$value" | /usr/bin/tr -d '\\042\\047')"
          case "$value" in ''|*[!a-z0-9_-]*) exit 64;; esac
          builtin printf '%s\\n' "$value"
          exit 0
        done < /etc/os-release
        exit 69
        """;
    private final ModContext context;

    public DeploymentMirrorTasks(ModContext context) { this.context = Objects.requireNonNull(context); }

    public static void privacyPrompt(BooleanSupplier prompt) { privacy = Objects.requireNonNull(prompt); }
    public static String policy(TaskContext task) { return policy(task.id()); }
    public static String policy(UUID task) { return ACTIVE.getOrDefault(task, ""); }

    /** Runs inside the host envelope's scrubbed child shell, not outside env -i. */
    public static String hostCommandBody(String script, TaskContext task, ServerId server, long seconds) {
        requireCaptured(task.id());
        Objects.requireNonNull(script);
        if (script.isBlank() || script.indexOf('\0') >= 0 || script.length() > 24000 || seconds < 1 || seconds > 7200)
            throw new IllegalArgumentException("Invalid authored host command or deadline");
        String json = policy(task);
        if (json.isEmpty()) return script;
        ResolvedMirrorPolicy.fromJson(json, task.id().toString(), server.id());
        String body = withMaterials(gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime
            .wrapBuild(script, json, seconds), task.id());
        if (body.getBytes(StandardCharsets.UTF_8).length > 1048576)
            throw new IllegalArgumentException("Mirror-bound host body exceeds execution limit");
        return body;
    }
    /** Preserves the original stdin and injects policy in the same root identity as the fixed helper. */
    public static String preparationCommand(String command, TaskContext task) {
        return preparationCommand(command, task.id());
    }
    public static String preparationCommand(String command, TaskContext task, long seconds) {
        return preparationCommand(command, task.id(), seconds);
    }
    public static String preparationCommand(String command, UUID task) {
        return preparationCommand(command, task, 7200);
    }
    public static String preparationCommand(String command, UUID task, long seconds) {
        requireCaptured(task);
        String policy = ACTIVE.getOrDefault(task, "");
        if (policy.isEmpty()) return command;
        if (seconds < 1 || seconds > 7200) throw new IllegalArgumentException("Invalid mirror preparation budget");
        String script = "# WTL_MIRROR_PREPARATION=" + task + "\n# WTL_MIRROR_BUDGET=" + seconds
            + "\nset -eu\nexport WTL_MIRROR_BUDGET_SECONDS=" + seconds + " WTL_MIRROR_STARTED_AT=\"$(/usr/bin/date +%s)\"\n"
            + "[ \"$(/usr/bin/id -u)\" -eq 0 ] || { printf 'MIRROR_REJECT=root-preparation-required\\n' >&2; exit 64; }\n"
            + withMaterials(gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime.bootstrapPrefix(policy), task)
            + "trap 'wtl_mirror_exit=$?; /usr/bin/rm -rf -- \"$wtl_mirror_root\" || exit 70; exit \"$wtl_mirror_exit\"' EXIT\n(\n"
            + command + PREPARATION_END;
        return script;
    }
    public record Preparation(UUID task, String original, String policy, long budgetSeconds) { }

    /** Only exact product-generated wrappers bound to the frozen task may expose an inner invocation. */
    public static Optional<Preparation> preparation(String command) {
        if (command == null || !command.startsWith("# WTL_MIRROR_PREPARATION=")) return Optional.empty();
        String body = command;
        var marker = java.util.regex.Pattern.compile("\\A# WTL_MIRROR_PREPARATION=([a-f0-9-]{36})\\n# WTL_MIRROR_BUDGET=([1-9][0-9]{0,4})\\n").matcher(body);
        if (!marker.find()) throw new IllegalArgumentException("Mirror preparation identity missing");
        UUID id = UUID.fromString(marker.group(1));
        long seconds = Long.parseLong(marker.group(2));
        String policy = ACTIVE.get(id);
        if (policy == null) throw new IllegalArgumentException("Original mirror policy is not bound");
        String emptyBody = preparationCommand("", id, seconds);
        String prefix = emptyBody.substring(0, emptyBody.length() - PREPARATION_END.length());
        if (!body.startsWith(prefix) || !body.endsWith(PREPARATION_END)) throw new IllegalArgumentException("Mirror preparation source changed");
        String original = body.substring(prefix.length(), body.length() - PREPARATION_END.length());
        if (original.isBlank() || !preparationCommand(original, id, seconds).equals(command))
            throw new IllegalArgumentException("Mirror preparation command changed");
        return Optional.of(new Preparation(id, original, policy, seconds));
    }

    /** Exact packaged bootstrap recognition, used only to describe the underlying installer source. */
    public static Optional<Preparation> executable(String script) {
        if (script == null) return Optional.empty();
        var marker = java.util.regex.Pattern.compile("\\A# WTL_MIRROR_TASK=([a-f0-9-]{36})\\n").matcher(script);
        if (!marker.find()) return Optional.empty();
        UUID id = UUID.fromString(marker.group(1));
        String policy = ACTIVE.get(id);
        if (policy == null) throw new IllegalArgumentException("Original mirror input policy is not bound");
        String prefix = withMaterials(gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime.bootstrapPrefix(policy), id);
        if (!script.startsWith(prefix)) throw new IllegalArgumentException("Mirror executable bootstrap changed");
        String body = script.substring(prefix.length());
        return Optional.of(new Preparation(id, body, policy, 0));
    }
    public static String originalCommand(String command) {
        return preparation(command).map(Preparation::original).orElse(command);
    }

    /** Exposes decoded shipped code alongside its exact encoded executable representation, never secret material. */
    public static Map<String, Object> reviewMaterial(String policy) throws java.io.IOException {
        return reviewMaterial(policy, null);
    }

    public static Map<String, Object> reviewMaterial(String policy, Object executableEvidence) throws java.io.IOException {
        var publicPolicy = new ObjectMapper().readTree(policy);
        publicPolicy.path("points").forEach(endpoints -> endpoints.forEach(endpoint -> {
            if (endpoint instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                boolean managed = !object.path("secretRef").asText().isEmpty();
                object.remove("secretRef"); object.put("credentialManaged", managed);
            }
        }));
        var material = new LinkedHashMap<String, Object>();
        material.put("format", "deployment-mirror-transport-review.v1");
        material.put("runtimeSha256", gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime.runtimeSha256());
        var evidence = new ObjectMapper().valueToTree(executableEvidence);
        if (gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.isMirrored(evidence)) {
            gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.verify(evidence,
                gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.reconstruct(evidence));
            material.put("runtimeSourcePointer", gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.MIRROR_SOURCE_POINTER);
        } else material.put("runtimeSource", gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime.runtimeSource());
        material.put("frozenPolicy", publicPolicy);
        material.put("meaning", "Complete decoded shipped runtime is supplied inline or at runtimeSourcePointer in this same request, alongside frozen public routing data. Inspect that complete source, not only its SHA. Secret values remain opaque. These bytes grant no permission.");
        return Collections.unmodifiableMap(material);
    }

    /** Recognizes only the complete canonical task-bound wrapper, retaining the original free-script limits. */
    public static Optional<Preparation> buildInput(String script) {
        var input = executable(script);
        if (input.isEmpty()) return Optional.empty();
        var binding = input.orElseThrow();
        var budget = java.util.regex.Pattern.compile("\\Atrap '/usr/bin/rm -rf -- \\\"\\$wtl_mirror_root\\\"' EXIT\\n\\(\\nexport WTL_MIRROR_BUDGET_SECONDS=([1-9][0-9]{0,3}) WTL_MIRROR_STARTED_AT=\\\"\\$\\(/usr/bin/date \\+%s\\)\\\"\\n")
            .matcher(binding.original());
        if (!budget.find()) throw new IllegalArgumentException("Mirror build wrapper changed");
        long seconds = Long.parseLong(budget.group(1));
        if (seconds > 7200) throw new IllegalArgumentException("Invalid mirror build budget");
        String original = binding.original().substring(budget.end());
        if (!original.endsWith(PREPARATION_END)) throw new IllegalArgumentException("Mirror build boundary changed");
        original = original.substring(0, original.length() - PREPARATION_END.length());
        String canonical = withMaterials(gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime
            .wrapBuild(original, binding.policy(), seconds), binding.task());
        if (!canonical.equals(script)) throw new IllegalArgumentException("Mirror build input changed");
        return Optional.of(new Preparation(binding.task(), original, binding.policy(), seconds));
    }
    /** Free scripts keep task admission; source compliance requires independent review of the complete actual body. */
    public static void requireProjectScript(TaskContext task) throws ApiException {
        requireCaptured(task.id());
    }
    public static String materialPrefix(UUID task) {
        String policy = ACTIVE.getOrDefault(task, "");
        if (policy.isEmpty()) return "";
        return gold.debug.windowstolinux.mod.deployment.linux.mirror.locked.DeploymentMirrorCredentials.environmentPrefix(policy,
            Objects.requireNonNull(LAYOUTS.get(task), "Original mirror layout missing"));
    }
    public static String withMaterials(String script, UUID task) {
        String material = materialPrefix(task);
        if (material.isEmpty()) return script;
        String marker = "# WTL_MIRROR_TASK=" + task + "\n";
        if (!script.startsWith(marker)) throw new IllegalArgumentException("Mirror executable task marker missing");
        return marker + material + script.substring(marker.length());
    }
    public static String installerRequestDigest(String original, TaskContext task) {
        String executable = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.withMirrors(original, policy(task));
        return sha256(executable.getBytes(StandardCharsets.UTF_8));
    }
    public static void requireCaptured(UUID task) {
        if (ADMITTED.contains(task) && !ACTIVE.containsKey(task))
            throw new IllegalStateException("Original task mirror policy was not committed; download preparation cannot use legacy sources");
    }
    public static void forget(TaskContext task) { ACTIVE.remove(task.id()); LAYOUTS.remove(task.id()); CLEANED.remove(task.id()); ADMITTED.remove(task.id()); }
    public static <T> TaskHandle<T> track(TaskHandle<T> handle) {
        handle.completion().whenComplete((result, failure) -> { ACTIVE.remove(handle.id()); LAYOUTS.remove(handle.id()); CLEANED.remove(handle.id()); ADMITTED.remove(handle.id()); });
        return handle;
    }
    public static void clear() { ACTIVE.clear(); LAYOUTS.clear(); CLEANED.clear(); ADMITTED.clear(); privacy = () -> false; }

    public record View(MirrorSettings settings, List<ServerInfo> servers, List<String> credentials) {
        public View { servers = List.copyOf(servers); credentials = List.copyOf(credentials); }
    }

    public TaskHandle<View> read() throws ApiException {
        return context.tasks().submit(new TaskRequest("deployment.mirrors.settings.read", "Mirror settings", List.of(), false), task -> {
            var servers = new ArrayList<ServerInfo>();
            Optional<String> cursor = Optional.empty();
            do {
                var page = context.servers().listConfigured(cursor, 200, task);
                servers.addAll(page.items()); cursor = page.nextCursor();
                if (task.cancelToken().requested()) return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
            } while (cursor.isPresent());
            var credentials = new gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentSecretStore(context.db())
                .listReferences(task, 1000).stream().filter(value -> value.purpose().equals("deployment.mirror.transport.v1"))
                .map(value -> value.reference().identifier() + ":" + value.reference().revision()).toList();
            return success(new View(new DeploymentMirrorStore(context.db()).read(task), servers, credentials));
        });
    }

    public TaskHandle<MirrorSettings> save(MirrorSettings settings) throws ApiException {
        return context.tasks().submit(new TaskRequest("deployment.mirrors.settings.save", "Save mirror settings", List.of(), false), task -> {
            validate(settings, MirrorCatalog.load());
            new DeploymentMirrorStore(context.db()).save(task, settings);
            return success(settings);
        });
    }

    /** Reuses the existing recoverable immutable secret-revision flow, never storing plaintext in preferences. */
    public TaskHandle<gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRevision>
        credential(ServerId server, byte[] owned) throws ApiException {
        try {
            var handle = new gold.debug.windowstolinux.mod.deployment.secret.DeploymentSecretProvisionFlow(context).submitOwned(server,
                new gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef("mirror-" + UUID.randomUUID().toString().substring(0, 12), 1),
                "deployment.mirror.transport.v1", java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), ignored -> owned);
            handle.completion().whenComplete((value, failure) -> Arrays.fill(owned, (byte) 0));
            return handle;
        } catch (ApiException | RuntimeException failure) { Arrays.fill(owned, (byte) 0); throw failure; }
    }

    /** Validates every configured row; hidden rows are retained, not silently repaired. */
    public static void validate(MirrorSettings settings, MirrorCatalog catalog) {
        for (var point : DownloadPoint.values()) for (var row : settings.rows(point)) {
            if (!row.automatic() && !row.providerId().isEmpty()
                && catalog.providersFor(point).stream().noneMatch(p -> p.id().equals(row.providerId())))
                throw new IllegalArgumentException("Source does not provide " + point + ": " + row.providerId());
            if (!row.automatic() && !row.secretRef().isEmpty() && row.customUrl().startsWith("http:"))
                throw new IllegalArgumentException("Authenticated mirror sources require HTTPS");
        }
        for (var country : settings.serverRegions().values())
            if (!catalog.countries().containsKey(country)) throw new IllegalArgumentException("Unknown country code: " + country);
    }

    /** Runs before any download/install. Consent precedes both remote IP queries and local region lookup. */
    public void capture(TaskContext task, ServerId server) throws Exception {
        capture(task, server, EnumSet.allOf(DownloadPoint.class), false);
    }

    public void capture(TaskContext task, ServerId server, Set<DownloadPoint> required) throws Exception {
        capture(task, server, required, true);
    }

    /** Freezes preferences at task admission, before source analysis can wait for user input. */
    public void prepare(TaskContext task, ServerId server) throws Exception {
        var saved = context.tasks().findCheckpoint(task, SELECTION);
        if (saved.isPresent()) { readSnapshot(task, SELECTION, saved.orElseThrow().snapshot().payload(), server.id()); ADMITTED.add(task.id()); return; }
        context.servers().get(server, task);
        var settings = new DeploymentMirrorStore(context.db()).read(task);
        validate(settings, MirrorCatalog.load());
        persist(task, SELECTION, new ObjectMapper().writeValueAsString(settings), server.id());
        ADMITTED.add(task.id());
    }

    private void capture(TaskContext task, ServerId server, Set<DownloadPoint> required, boolean scoped) throws Exception {
        if (context.tasks().findCheckpoint(task, CHECKPOINT).isPresent()) {
            restore(task);
            ResolvedMirrorPolicy.fromJson(policy(task), task.id().toString(), server.id());
            return;
        }
        var original = context.servers().get(server, task);
        prepare(task, server);
        var selected = context.tasks().findCheckpoint(task, SELECTION).orElseThrow().snapshot().payload();
        var settings = new ObjectMapper().readValue(readSnapshot(task, SELECTION, selected, server.id()), MirrorSettings.class);
        var catalog = MirrorCatalog.load();
        validate(settings, catalog);
        var active = EnumSet.noneOf(DownloadPoint.class);
        active.addAll(required);
        var system = EnumSet.noneOf(DownloadPoint.class);
        for (var point : DownloadPoint.values()) if (point.name().startsWith("APT_") || point.name().startsWith("DNF_")) system.add(point);
        if (scoped && active.stream().anyMatch(system::contains)) {
            var actual = packagePoint(task, server);
            active.removeAll(system); active.add(actual);
        }
        boolean automatic = hasAutomatic(settings, active);
        if (automatic && needsConsent(settings, active)) {
            if (task.cancelToken().requested() || !privacy.getAsBoolean())
                throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "MIRROR_PRIVACY_CONSENT_REQUIRED",
                    "Region matching requires explicit privacy consent; configure mirror settings to continue", Map.of(), Optional.empty()));
            var rows = new EnumMap<DownloadPoint, List<MirrorSettings.Row>>(DownloadPoint.class);
            for (var point : DownloadPoint.values()) rows.put(point, settings.rows(point).stream()
                .map(r -> new MirrorSettings.Row(r.providerId(), r.customUrl(), r.automatic(), r.automatic() || r.privacyConsent(), r.secretRef())).toList());
            var confirmed = new MirrorSettings(1, rows, settings.serverRegions());
            new DeploymentMirrorStore(context.db()).saveConsent(task, settings, confirmed);
            settings = confirmed;
        }
        if (task.cancelToken().requested()) throw cancelled();
        String country = "";
        if (automatic) {
            country = settings.serverRegions().getOrDefault(server.id(), "");
            if (country.isEmpty()) {
                var database = LocalCountryDatabase.load(catalog);
                String address = exitAddress(task, server, catalog).orElse(original.host());
                country = database.country(address).orElse("");
            }
        }
        var resolved = MirrorSelector.resolve(catalog, settings, server.id(), country, active).withTask(task.id().toString());
        var admitted = new EnumMap<DownloadPoint, List<ResolvedMirrorPolicy.Endpoint>>(DownloadPoint.class);
        resolved.points().forEach((point, endpoints) -> admitted.put(point, active.contains(point) ? endpoints : List.of()));
        resolved = new ResolvedMirrorPolicy(resolved.formatVersion(), resolved.task(), resolved.server(), resolved.catalogVersion(),
            resolved.country(), resolved.region(), resolved.timeouts(), admitted);
        String json = resolved.toJson();
        persist(task, CHECKPOINT, json, server.id());
        bind(task, json);
        var staged = gold.debug.windowstolinux.mod.deployment.linux.mirror.locked.DeploymentMirrorCredentials.stage(context, server, task, json, LAYOUTS.get(task.id()));
        if (staged.outcome() != TaskResult.SUCCEEDED) throw new ApiException(staged.problem().orElseGet(() ->
            new Problem(ProblemCode.UNKNOWN, "MIRROR_CREDENTIAL_STAGE_UNCONFIRMED", "Mirror credential staging was not confirmed", Map.of(), Optional.empty())));
        task.progress("Mirror policy captured: " + (country.isEmpty() ? "global/explicit" : country));
    }

    /** Absence identifies legacy tasks; recovery never manufactures a policy from current preferences. */
    public void restore(TaskContext task) throws Exception {
        var saved = context.tasks().findCheckpoint(task, CHECKPOINT);
        if (saved.isEmpty()) {
            forget(task);
            if (context.tasks().findCheckpoint(task, SELECTION).isPresent()
                || context.tasks().findCheckpoint(task, SELECTION + ".part.0").isPresent()) ADMITTED.add(task.id());
            if (context.tasks().findCheckpoint(task, CHECKPOINT + ".part.0").isPresent())
                throw new IllegalStateException("Original mirror snapshot is incomplete");
            return;
        }
        var manifest = new ObjectMapper().readTree(saved.orElseThrow().snapshot().payload());
        String json = readSnapshot(task, CHECKPOINT, saved.orElseThrow().snapshot().payload(), manifest.path("server").asText());
        var node = new ObjectMapper().readTree(json);
        String server = node.path("server").asText();
        context.servers().get(new ServerId(server), task);
        ResolvedMirrorPolicy.fromJson(json, task.id().toString(), server);
        bind(task, json);
    }

    private void bind(TaskContext task, String json) throws ApiException {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
        LAYOUTS.putIfAbsent(task.id(), layout);
        var previous = ACTIVE.putIfAbsent(task.id(), json);
        if (previous != null && !previous.equals(json)) throw new IllegalArgumentException("Frozen mirror policy changed");
    }

    /** Online successors inherit the original goal's policy, not later global settings. */
    public void inherit(TaskContext task, ServerId server, String parentJson) throws Exception {
        if (parentJson == null || parentJson.isEmpty()) return;
        var raw = new ObjectMapper().readTree(parentJson);
        var parent = ResolvedMirrorPolicy.fromJson(parentJson, raw.path("task").asText(), server.id());
        String json = parent.withTask(task.id().toString()).toJson();
        context.servers().get(server, task);
        persist(task, CHECKPOINT, json, server.id());
        bind(task, json);
        var staged = gold.debug.windowstolinux.mod.deployment.linux.mirror.locked.DeploymentMirrorCredentials.stage(context, server, task, json, LAYOUTS.get(task.id()));
        if (staged.outcome() != TaskResult.SUCCEEDED) throw new ApiException(staged.problem().orElseGet(() ->
            new Problem(ProblemCode.UNKNOWN, "MIRROR_CREDENTIAL_STAGE_UNCONFIRMED", "Mirror credential staging was not confirmed", Map.of(), Optional.empty())));
    }

    public <T> OperationResult<T> run(TaskContext task, TaskWork<T> work) throws Exception {
        try {
            var result = work.run(task);
            if (result.outcome() != TaskResult.UNKNOWN) {
                var cleaned = cleanup(task);
                if (cleaned.outcome() != TaskResult.SUCCEEDED)
                    return new OperationResult<>(TaskResult.UNKNOWN, result.value(), cleaned.problem());
            }
            return result;
        } finally { forget(task); }
    }

    /** Called while the owning workflow's current review scope is still open. */
    public OperationResult<Void> cleanup(TaskContext task) throws Exception {
        String json = policy(task);
        if (json.isEmpty() || CLEANED.contains(task.id())) return success(null);
        var policy = new ObjectMapper().readTree(json);
        var result = gold.debug.windowstolinux.mod.deployment.linux.mirror.locked.DeploymentMirrorCredentials.discard(context,
            new ServerId(policy.path("server").asText()), task, json, LAYOUTS.get(task.id()));
        if (result.outcome() == TaskResult.SUCCEEDED) CLEANED.add(task.id());
        return result;
    }

    /** Platform checkpoints are bounded to 12 KiB; an immutable manifest commits exact ordered chunks. */
    private void persist(TaskContext task, String key, String json, String server) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1_000_000) throw new IllegalArgumentException("Mirror policy exceeds task snapshot bound");
        String encoded = Base64.getEncoder().encodeToString(bytes);
        int count = (encoded.length() + 7999) / 8000;
        for (int index = 0; index < count; index++) {
            String part = new ObjectMapper().createObjectNode().put("index", index)
                .put("data", encoded.substring(index * 8000, Math.min(encoded.length(), (index + 1) * 8000))).toString();
            try (var checkpoint = context.tasks().beginCheckpoint(task, key + ".part." + index, part)) {
                if (!part.equals(checkpoint.snapshot().payload())) throw new IllegalStateException("Mirror snapshot chunk differs");
            }
        }
        String manifest = new ObjectMapper().createObjectNode().put("formatVersion", 1)
            .put("task", task.id().toString()).put("server", server).put("chunks", count)
            .put("bytes", bytes.length).put("sha256", sha256(bytes)).toString();
        try (var checkpoint = context.tasks().beginCheckpoint(task, key, manifest)) {
            if (!manifest.equals(checkpoint.snapshot().payload())) throw new IllegalStateException("Mirror snapshot manifest differs");
        }
    }

    private String readSnapshot(TaskContext task, String key, String manifest, String server) throws Exception {
        var root = new ObjectMapper().readTree(manifest);
        int count = root.path("chunks").asInt();
        if (root.path("formatVersion").asInt() != 1 || !root.path("task").asText().equals(task.id().toString()) || !root.path("server").asText().equals(server)
            || count < 1 || count > 167 || root.path("bytes").asInt() < 1 || root.path("bytes").asInt() > 1_000_000)
            throw new IllegalArgumentException("Mirror snapshot manifest invalid");
        var encoded = new StringBuilder();
        for (int index = 0; index < count; index++) {
            var saved = context.tasks().findCheckpoint(task, key + ".part." + index).orElseThrow();
            var part = new ObjectMapper().readTree(saved.snapshot().payload());
            if (part.path("index").asInt(-1) != index || part.path("data").asText().length() > 8000)
                throw new IllegalArgumentException("Mirror snapshot chunk invalid");
            encoded.append(part.path("data").asText());
        }
        byte[] bytes = Base64.getDecoder().decode(encoded.toString());
        if (bytes.length != root.path("bytes").asInt() || !sha256(bytes).equals(root.path("sha256").asText()))
            throw new IllegalArgumentException("Mirror snapshot digest differs");
        String json = new String(bytes, StandardCharsets.UTF_8);
        if (key.equals(CHECKPOINT)) ResolvedMirrorPolicy.fromJson(json, task.id().toString(), server);
        return json;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Deployment compatibility inspection is separate from region detection and performs no installation. */
    private DownloadPoint packagePoint(TaskContext task, ServerId server) throws ApiException {
        try (var request = new CommandRequest(COMPATIBILITY_LAUNCHER, COMPATIBILITY_SCRIPT.getBytes(StandardCharsets.UTF_8),
            Duration.ofSeconds(10), 1024)) {
            var intent = RemoteActions.command(server, "mirror-package-platform-v1", request).withSemantics(
                new gold.debug.windowstolinux.appapi.task.action.ActionSemantics("deployment.mirror.compatibility.v1", Set.of(
                    new gold.debug.windowstolinux.appapi.task.action.ActionTarget(server.id(), "task", task.id().toString()))));
            var grant = DeploymentCommandReviewRouter.approve(context.interaction(), intent, request, task);
            if (grant.outcome() == TaskResult.CANCELLED || task.cancelToken().requested()) throw cancelled();
            if (grant.outcome() != TaskResult.SUCCEEDED) throw new ApiException(grant.problem().orElseGet(() ->
                new Problem(ProblemCode.ACCESS_DENIED, "MIRROR_SYSTEM_SOURCE_SCOPE_NOT_CONFIRMED", "Distribution inspection was not approved", Map.of(), Optional.empty())));
            try (var session = context.linux().connect(server, task)) {
                var result = session.execute(request, grant.value().orElseThrow());
                if (result.outcome() == TaskResult.CANCELLED || task.cancelToken().requested()) throw cancelled();
                if (result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent()) {
                    var response = result.value().orElseThrow();
                    if (!response.truncated() && response.exitCode().orElse(-1) == 0) {
                        var point = switch (response.stdout().trim()) {
                            case "debian" -> DownloadPoint.APT_DEBIAN;
                            case "ubuntu" -> DownloadPoint.APT_UBUNTU;
                            case "fedora" -> DownloadPoint.DNF_FEDORA;
                            case "rocky" -> DownloadPoint.DNF_ROCKY;
                            case "almalinux" -> DownloadPoint.DNF_ALMA;
                            case "centos" -> DownloadPoint.DNF_CENTOS_STREAM;
                            case "ol" -> DownloadPoint.DNF_ORACLE;
                            default -> null;
                        };
                        if (point != null) return point;
                    }
                }
            }
        }
        throw new ApiException(new Problem(ProblemCode.INCOMPATIBLE, "MIRROR_SYSTEM_SOURCE_PLATFORM_UNKNOWN",
            "The target distribution has no admitted system repository policy", Map.of(), Optional.empty()));
    }

    /** Binds the fixed launcher and the decoded review text to the exact executable stdin. */
    public static Map<String, Object> compatibilityEvidence(String launcher, String executable, byte[] input) {
        if (!COMPATIBILITY_LAUNCHER.equals(launcher) || !COMPATIBILITY_SCRIPT.equals(executable)
            || !java.util.Arrays.equals(executable.getBytes(StandardCharsets.UTF_8), input))
            throw new IllegalArgumentException("Mirror compatibility command differs from the fixed launcher or script");
        return Map.of("launcher", launcher, "scriptPointer", "/completeExecutableInput", "stdinEncoding", "UTF-8",
            "stdinBytes", input.length, "stdinSha256", sha256(input),
            "sourceRepresentation", "Count and hash the decoded JSON string including its final newline, without JSON quotes or escape syntax.");
    }

    private Optional<String> exitAddress(TaskContext task, ServerId server, MirrorCatalog catalog) throws ApiException {
        var script = new StringBuilder("set -eu\nPATH=/usr/bin:/bin; export PATH\nunset BASH_ENV ENV\n");
        for (var url : catalog.ipProbeUrls()) script.append("if command -v curl >/dev/null 2>&1; then\n")
            .append("  value=$(command curl --disable --noproxy '*' --fail --silent --show-error --connect-timeout 3 --max-time 5 --max-filesize 128 --proto '=https' ")
            .append(quote(url)).append(" 2>/dev/null) && { printf '%s\\n' \"$value\"; exit 0; }\nfi\n");
        script.append("exit 69\n");
        try (var request = new CommandRequest("/bin/bash --noprofile --norc -s", script.toString().getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(15), 1024)) {
            var intent = RemoteActions.command(server, "mirror-ip-v1", request).withSemantics(
                new gold.debug.windowstolinux.appapi.task.action.ActionSemantics("deployment.mirror.ip.v1", Set.of(
                    new gold.debug.windowstolinux.appapi.task.action.ActionTarget(server.id(), "task", task.id().toString()))));
            var grant = DeploymentCommandReviewRouter.approve(context.interaction(), intent, request, task);
            if (grant.outcome() == TaskResult.CANCELLED || task.cancelToken().requested()) throw cancelled();
            if (grant.outcome() != TaskResult.SUCCEEDED) return Optional.empty();
            try (var session = context.linux().connect(server, task)) {
                var response = session.execute(request, grant.value().orElseThrow());
                if (response.outcome() == TaskResult.CANCELLED) throw cancelled();
                if (response.outcome() != TaskResult.SUCCEEDED || response.value().isEmpty()) return Optional.empty();
                var result = response.value().orElseThrow();
                String ip = result.stdout().trim();
                if (result.truncated() || result.exitCode().orElse(-1) != 0 || ip.length() > 64
                    || !ip.matches("[0-9A-Fa-f:.]+")) return Optional.empty();
                return Optional.of(ip);
            }
        } catch (ApiException unavailable) {
            if (unavailable.problem().code() == ProblemCode.CANCELLED || task.cancelToken().requested()) throw unavailable;
            return Optional.empty();
        }
    }

    private static boolean hasAutomatic(MirrorSettings settings, Set<DownloadPoint> active) {
        return active.stream().anyMatch(p -> settings.rows(p).stream().anyMatch(MirrorSettings.Row::automatic));
    }
    private static boolean needsConsent(MirrorSettings settings, Set<DownloadPoint> active) {
        return active.stream().anyMatch(p -> settings.rows(p).stream()
            .filter(MirrorSettings.Row::automatic).findFirst().map(r -> !r.privacyConsent()).orElse(false));
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    private static ApiException cancelled() {
        return new ApiException(new Problem(ProblemCode.CANCELLED, "MIRROR_SETUP_CANCELLED", "Mirror setup cancelled", Map.of(), Optional.empty()));
    }
    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.ofNullable(value), Optional.empty());
    }
}
