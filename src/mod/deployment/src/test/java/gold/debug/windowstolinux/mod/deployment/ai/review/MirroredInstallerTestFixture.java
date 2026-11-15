package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.server.model.*;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.linux.helper.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.service.mirror.*;

/** Public-input fixture: every platform capability is either in-memory or a refusing proxy. */
public final class MirroredInstallerTestFixture implements AutoCloseable {
    public final NativeAiFixture model = new NativeAiFixture();
    public final TaskContext task = model.task;
    public final ServerId server = new ServerId("mirrored-installer-fixture");
    public final DeploymentRemoteLayout layout = DeploymentRemoteLayout.original();
    public final UUID operation = UUID.randomUUID();
    public final String customOperation = "0123456789abcdef0123456789abcdef";
    public final ModContext context;
    private final Map<String, TaskCheckpoint> checkpoints = new HashMap<>();

    public MirroredInstallerTestFixture() throws Exception {
        this(DownloadPoint.APT_UBUNTU);
    }

    MirroredInstallerTestFixture(DownloadPoint... points) throws Exception {
        var fallback = NativeAiFixture.approvalCheckpoints(task);
        var tasks = (TaskService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskService.class}, (p,m,a) -> {
            if (a[0] != task) throw new AssertionError("Different task");
            String key = (String) a[1];
            if (key.startsWith("deployment.mirrors.")) {
                if (m.getName().equals("findCheckpoint")) return Optional.ofNullable(checkpoints.get(key));
                if (m.getName().equals("beginCheckpoint")) {
                    var snapshot = new TaskCheckpoint.Snapshot(0, (String)a[2], false);
                    var checkpoint = new TaskCheckpoint() {
                        public Snapshot snapshot() { return snapshot; }
                        public Snapshot advance(Snapshot expected, String next) { throw new AssertionError("No durable store"); }
                        public void close() { }
                    };
                    if (checkpoints.putIfAbsent(key, checkpoint) != null) throw new AssertionError("Repeated fixture checkpoint");
                    return checkpoint;
                }
                throw new AssertionError(m.getName());
            }
            return m.invoke(fallback, a);
        });
        var servers = (ServerService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ServerService.class}, (p,m,a) -> {
            if (m.getName().equals("get") && a[0].equals(server) && a[1] == task)
                return new ServerInfo(server, "fixture", "127.0.0.1", 1, "fixture", Optional.empty(), Optional.empty());
            throw new AssertionError("No server operation: " + m.getName());
        });
        var ui = (UiService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{UiService.class}, (p,m,a) -> {
            if (m.getName().equals("environment")) return new UiEnvironment(Locale.ENGLISH, "LIGHT", false);
            throw new AssertionError("No UI: " + m.getName());
        });
        context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (p,m,a) -> switch(m.getName()) {
            case "tasks" -> tasks;
            case "servers" -> servers;
            case "ui" -> ui;
            case "ai" -> model.context.ai();
            case "logs" -> model.context.logs();
            default -> throw new AssertionError("No Runtime/DB/network capability: " + m.getName());
        });
        var catalog = MirrorCatalog.load();
        var rows = new EnumMap<DownloadPoint,List<MirrorSettings.Row>>(DownloadPoint.class);
        for (var point : points) {
            var providers = catalog.providersFor(point).stream().filter(MirrorCatalog.Provider::official).toList();
            if (providers.size() != 1) throw new AssertionError("Exact official provider required");
            rows.put(point, List.of(new MirrorSettings.Row(providers.getFirst().id(), "", false, false, "")));
        }
        var selected = MirrorSelector.resolve(catalog, new MirrorSettings(1, rows, Map.of()), server.id(), "").withTask(task.id().toString());
        new DeploymentMirrorTasks(context).inherit(task, server, selected.toJson());
    }

    public String installer(String kind, boolean mirrored) throws Exception {
        String policy = mirrored ? DeploymentMirrorTasks.policy(task) : "";
        var point = DeploymentHelperInstallerScript.packagePoint(policy);
        String body = switch(kind) {
            case "PLAIN" -> DeploymentHelperInstallerScript.render(layout);
            case "FILES" -> DeploymentHelperInstallerScript.renderFiles(layout);
            case "TRACKED" -> DeploymentHelperInstallerScript.renderTracked(task.id(), operation, sha(server.id()),
                DeploymentHelperInstallerScript.bodyDigest(layout, point), layout, point);
            case "CUSTOM" -> DeploymentAgentHelperInstallerScript.render(customOperation, layout, server, task.id().toString());
            default -> throw new IllegalArgumentException(kind);
        };
        return DeploymentHelperInstallerScript.withMirrors(body, policy);
    }

    public CommandRequest request(String executable) {
        return new CommandRequest(DeploymentHelperInstallerScript.LAUNCHER, executable.getBytes(StandardCharsets.UTF_8), Duration.ofHours(2), 131072);
    }

    public ActionIntent intent(String kind, CommandRequest request) {
        String name = switch(kind) { case "PLAIN" -> "deployment.helper.install.v1"; case "FILES" -> "deployment.helper.install.files.v1"; case "TRACKED" -> "deployment.helper.install.tracked.v1"; case "CUSTOM" -> DeploymentAgentHelperBootstrapEvidence.OPERATION; default -> throw new IllegalArgumentException(kind); };
        boolean mirrored = new String(request.stdin(), StandardCharsets.UTF_8).startsWith("# WTL_MIRROR_TASK=");
        var point = DeploymentHelperInstallerScript.packagePoint(mirrored ? DeploymentMirrorTasks.policy(task) : "");
        Set<ActionTarget> targets = kind.equals("TRACKED")
            ? DeploymentManagedHelper.trackedInstallationTargets(server, layout, operation, point)
            : DeploymentManagedHelper.installationTargets(server, layout);
        var original = RemoteActions.command(server, model.configurationRevision, request).withSemantics(new ActionSemantics(name, targets));
        return kind.equals("CUSTOM") ? DeploymentAgentHelperBootstrapEvidence.upgrade(original, request, server, model.configurationRevision, customOperation, layout) : original;
    }

    public Map<String,Object> facts(String kind, CommandRequest request) throws Exception {
        var intent = intent(kind, request);
        return kind.equals("CUSTOM") ? new DeploymentAgentCommandProtocol(context,
            () -> { throw new AssertionError("No receipt store in pure installer fixture"); }).inspect(intent, request, layout, task)
            : new DeploymentFixedCommandProtocol(context).inspect(intent, request, layout, task);
    }

    public static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    public DeploymentAgentApprovalConversation conversation() {
        return new DeploymentAgentApprovalConversation(context, task, server, "SHA256:synthetic", "a".repeat(64), model.configurationRevision);
    }

    /** Independent navigation decoder: source ranges and encodings must match the original stdin range. */
    public static String navigationPart(com.fasterxml.jackson.databind.JsonNode current, com.fasterxml.jackson.databind.JsonNode part,
                                        byte[] stdin) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String source = current.at(part.path("sourcePointer").textValue()).textValue();
        int start = part.path("startCharacter").intValue();
        String fragment = source.substring(start, start + part.path("characters").intValue());
        if (fragment.getBytes(StandardCharsets.UTF_8).length != part.path("utf8Bytes").intValue()
            || !sha(fragment).equals(part.path("sha256").textValue())) throw new AssertionError("Exact source navigation range");
        var segment = current.at(part.path("segmentPointer").textValue());
        String encoding = part.path("encoding").textValue();
        if (!encoding.equals(segment.path("kind").textValue())) throw new AssertionError("Exact segment encoding");
        String encoded = switch(encoding) {
            case "LITERAL", "SOURCE_TEXT" -> fragment;
            case "JSON_STRING" -> json.writeValueAsString(fragment);
            case "SHELL_SINGLE_QUOTE" -> "'" + fragment.replace("'", "'\"'\"'") + "'";
            default -> throw new AssertionError("Unknown navigation encoding");
        };
        if (!encoding.equals("LITERAL") && !part.path("sourcePointer").textValue()
            .equals("/completeExecutableInput/sources/" + segment.path("source").textValue())) throw new AssertionError("Exact source reference");
        if (encoded.length() != part.path("encodedCharacters").intValue()
            || encoded.getBytes(StandardCharsets.UTF_8).length != part.path("encodedUtf8Bytes").intValue()
            || !sha(encoded).equals(part.path("encodedSha256").textValue())) throw new AssertionError("Exact encoded navigation range");
        int from = part.at("/stdinByteRange/startInclusive").intValue(), to = part.at("/stdinByteRange/endExclusive").intValue();
        if (!Arrays.equals(encoded.getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(stdin,from,to))) throw new AssertionError("Exact original stdin byte range");
        return encoded;
    }

    /** Independent test-side wire decoder; production must already have validated this transport. */
    public static com.fasterxml.jackson.databind.JsonNode restoreCarrier(java.util.List<gold.debug.windowstolinux.appapi.ai.model.AiMessage> messages) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var projection = json.readTree(messages.getLast().text());
        if (!projection.has("approvalSourceCarrier")) return projection;
        var frames = projection.at("/approvalSourceCarrier/frames");
        var texts = new ArrayList<String>();
        for (var row : frames) texts.add(messages.get(row.path("messageIndex").intValue()).text());
        return restoreCarrier(projection, texts);
    }

    public static com.fasterxml.jackson.databind.JsonNode restoreCarrier(com.fasterxml.jackson.databind.JsonNode projection, java.util.List<String> frameTexts) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode restored = projection.deepCopy();
        var manifest = projection.path("approvalSourceCarrier");
        var frames = manifest.path("frames");
        if (!manifest.path("format").asText().equals("WTL_APPROVAL_SOURCE_CARRIER_V1") || frames.size() != frameTexts.size()) throw new AssertionError("Exact carrier projection");
        for (int index = 0; index < frames.size(); index++) {
            var row = frames.get(index);
            var header = json.createObjectNode();
            for (String field : List.of("format", "currentSha256", "ordinal", "pointer", "characters", "utf8Bytes", "sha256")) header.set(field, row.get(field));
            String prefix = "WTL-APPROVAL-SOURCE-FRAME-1\n" + header + "\n";
            String text = frameTexts.get(index);
            int firstBody = row.path("headerCharacters").intValue();
            if (row.path("ordinal").intValue() != index || prefix.length() != firstBody || !text.startsWith(prefix)) throw new AssertionError("Exact frame header and order");
            String body = text.substring(firstBody);
            var insertion = row.path("jsonStringInsertion");
            if (insertion.isObject()) {
                int sourceFrame = -1;
                for (var source : frames) {
                    if (source.path("pointer").textValue().equals(insertion.path("sourcePointer").textValue()))
                        sourceFrame = source.path("ordinal").intValue();
                }
                if (sourceFrame < 0 || frames.get(sourceFrame).has("jsonStringInsertion"))
                    throw new AssertionError("Exact original source frame required for runtime string");
                int sourceHeader = frames.get(sourceFrame).path("headerCharacters").intValue();
                String source = frameTexts.get(sourceFrame).substring(sourceHeader);
                String encoded = json.writeValueAsString(source.substring(insertion.path("sourceOffset").intValue(),
                    insertion.path("sourceNext").intValue()));
                int offset = insertion.path("bodyOffset").intValue();
                if (encoded.length() != insertion.path("encodedCharacters").intValue())
                    throw new AssertionError("Exact encoded source characters");
                body = body.substring(0, offset) + encoded + body.substring(offset);
            }
            if (body.length() != row.path("characters").intValue() || body.getBytes(StandardCharsets.UTF_8).length != row.path("utf8Bytes").intValue() || !sha(body).equals(row.path("sha256").asText())) throw new AssertionError("Exact raw frame body");
            String pointer = row.path("pointer").asText();
            int slash = pointer.lastIndexOf('/');
            ((com.fasterxml.jackson.databind.node.ObjectNode)restored.at(pointer.substring(0,slash))).put(pointer.substring(slash+1),body);
        }
        restored.remove("approvalSourceCarrier");
        return restored;
    }

    public void close() { DeploymentMirrorTasks.forget(task); }
}
