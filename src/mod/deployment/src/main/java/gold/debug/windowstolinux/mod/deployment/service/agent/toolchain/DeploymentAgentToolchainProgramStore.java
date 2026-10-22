package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

/** A shipped, explicitly pinned Agent controller version; no installed-helper or arbitrary program fallback. */
public final class DeploymentAgentToolchainProgramStore {
    public static final String REVISION = "WTL_AGENT_CONTROLLER_STORE_1";

    public static final String FIELD = "agentToolchainInstalledInputBinding";

    public static final String PREFIX = "deployment.agent.toolchain.controller.";

    private static final String ROOT = "/gold/debug/windowstolinux/mod/deployment/service/agent/toolchain/";

    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private static final JsonNode MANIFEST = manifest();

    private DeploymentAgentToolchainProgramStore() {
    }

    private static JsonNode manifest() {
        try {
            var node = JSON.readTree(resource("agent-controller-program-manifest.json"));
            requireManifest(node, DeploymentAgentToolchainPrograms.controller(), resource("agent-controller-loader.py"),
                resource("agent-controller-install.py"), resource("installed-controller-review.md"));
            return node;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Shipped Agent controller review manifest unavailable", failure);
        }
    }

    static void requireManifest(JsonNode node, String controller, String loader, String installer, String contract) {
        if (node == null || !node.isObject() || node.size() != 5 || !node.path("revision").asText().equals(REVISION)
            || !node.path("defaultControllerSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(controller))
            || !node.path("loaderTemplateSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(loader))
            || !node.path("installerTemplateSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(installer))
            || !node.path("contractSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(contract)))
            throw new IllegalArgumentException("Shipped controller inputs differ from their explicit review manifest");
    }

    public static String version(DeploymentRemoteLayout layout) {
        Objects.requireNonNull(MANIFEST);
        return DeploymentAgentToolchainPrograms.sha256(REVISION + "\n" + DeploymentAgentToolchainPrograms.sha256(resource("agent-controller-program-manifest.json"))
            + "\n" + layout.digest() + "\n" + sourceSha256(layout));
    }

    static String sourceSha256(DeploymentRemoteLayout layout) {
        return DeploymentAgentToolchainPrograms.sha256(DeploymentAgentToolchainPrograms.controller(layout));
    }

    public static String root(DeploymentRemoteLayout layout) {
        return layout.toolchains() + "/agent-controller-programs";
    }

    public static String path(DeploymentRemoteLayout layout) {
        return root(layout) + "/" + version(layout);
    }

    public static String contract() {
        Objects.requireNonNull(MANIFEST);
        return resource("installed-controller-review.md");
    }

    static String loader(DeploymentRemoteLayout layout) {
        try {
            String constants = "STORE_ROOT = " + JSON.writeValueAsString(root(layout))
                + "\nSOURCE_SHA256 = " + JSON.writeValueAsString(sourceSha256(layout))
                + "\nVERSION_SHA256 = " + JSON.writeValueAsString(version(layout))
                + "\nSOURCE_BYTES = " + DeploymentAgentToolchainPrograms.controller(layout).getBytes(StandardCharsets.UTF_8).length
                + "\nLAYOUT_SHA256 = " + JSON.writeValueAsString(layout.digest())
                + "\nCONTRACT_REVISION = " + JSON.writeValueAsString(REVISION);
            return resource("agent-controller-loader.py").replace("# @WTL_CONTROLLER_CONSTANTS@", constants);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Controller loader cannot be rendered", failure);
        }
    }

    static String installer(DeploymentRemoteLayout layout) {
        try {
            return resource("agent-controller-install.py").replace("# @WTL_CONTROLLER_INSTALL_SOURCES@",
                "LOADER_SOURCE = " + JSON.writeValueAsString(loader(layout)) + "\nCONTROLLER_SOURCE = "
                    + JSON.writeValueAsString(DeploymentAgentToolchainPrograms.controller(layout)));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Controller installer cannot be rendered", failure);
        }
    }

    static String command(String phase, String task, String action, List<String> arguments, DeploymentRemoteLayout layout) {
        if (!Set.of("readiness", "prepare", "bind").contains(phase))
            throw new IllegalArgumentException("Unknown installed controller action");
        var argv = new ArrayList<>(List.of(phase.equals("readiness") ? "readiness" : "invoke", task, action));
        argv.addAll(arguments);
        return "/usr/bin/python3 -I -S -c " + quote(loader(layout)) + " " + argv.stream().map(DeploymentAgentToolchainProgramStore::quote)
            .collect(java.util.stream.Collectors.joining(" "));
    }

    static List<String> arguments(String script, DeploymentRemoteLayout layout) {
        String prefix = "/usr/bin/python3 -I -S -c " + quote(loader(layout)) + " ";
        if (!script.startsWith(prefix)) throw new IllegalArgumentException("Installed controller loader changed");
        var matcher = Pattern.compile("'([^']*)'(?: |$)").matcher(script.substring(prefix.length()));
        var argv = new ArrayList<String>();
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() != cursor) throw new IllegalArgumentException("Noncanonical controller argv");
            argv.add(matcher.group(1));
            cursor = matcher.end();
        }
        if (argv.size() < 3 || cursor != script.length() - prefix.length())
            throw new IllegalArgumentException("Incomplete installed controller frame");
        return List.copyOf(argv);
    }

    public static String trackedCommand(String verb, DeploymentAgentToolchainTracked.Recipe recipe) {
        if (!recipe.version().equals(version(recipe.layout())) || !recipe.sourceSha256().equals(sourceSha256(recipe.layout())))
            throw new IllegalArgumentException("Original controller version is unavailable");
        if (Set.of("prepare-tracked", "bind-tracked").contains(verb) && !recipe.invocationKind().equals("INSTALLED"))
            throw new IllegalArgumentException("Installed creation requires its original installed invocation");
        return "/usr/bin/python3 -I -S -c " + quote(loader(recipe.layout())) + " " + recipe.frame(verb).stream()
            .map(DeploymentAgentToolchainProgramStore::quote).collect(java.util.stream.Collectors.joining(" "));
    }

    static Set<ActionTarget> targets(ServerId server, String phase, String action, String operation, DeploymentRemoteLayout layout) {
        var result = new HashSet<ActionTarget>();
        result.add(new ActionTarget(server.id(), "agent-controller-program", path(layout)));
        if (phase.equals("install")) {
            for (String path : List.of(layout.toolchains(), root(layout), root(layout) + "/.install.lock", root(layout) + "/.install-" + version(layout) + "-" + action))
                result.add(new ActionTarget(server.id(), "deployment-build", path));
        } else if (!phase.equals("readiness"))
            result.addAll(DeploymentAgentToolchainCommands.controllerTargets(server, operation, layout));
        return Set.copyOf(result);
    }

    static JsonNode receipt(OperationResult<CommandResult> result, String task, String action, DeploymentRemoteLayout layout, boolean installation) {
        if (!successful(result) || !result.value().orElseThrow().stderr().isEmpty())
            throw new IllegalArgumentException("Controller store result is unconfirmed");
        return receipt(result.value().orElseThrow().stdout(), task, action, layout, installation);
    }

    static JsonNode receipt(String stdout, String task, String action, DeploymentRemoteLayout layout, boolean installation) {
        String prefix = "AGENT_CONTROLLER_STORE=";
        if (!stdout.startsWith(prefix) || !stdout.endsWith("\n") || stdout.indexOf('\n') != stdout.length() - 1 || stdout.length() > 4096)
            throw new IllegalArgumentException("Controller store receipt frame changed");
        try {
            var value = JSON.readTree(stdout.substring(prefix.length(), stdout.length() - 1));
            var fields = new HashSet<>(Set.of("format", "contract", "version", "sourceSha256", "sourceBytes", "layoutSha256", "path", "installOperation", "installTask", "task", "action", "status"));
            if (installation) fields.add("requestedInstallOperation");
            var observed = new HashSet<String>();
            value.fieldNames().forEachRemaining(observed::add);
            if (!value.isObject() || !observed.equals(fields) || fields.stream().anyMatch(field -> !field.equals("sourceBytes") && !value.path(field).isTextual())
                || !value.path("format").asText().equals(REVISION)
                || !value.path("contract").asText().equals(REVISION) || !value.path("version").asText().equals(version(layout))
                || !value.path("sourceSha256").asText().equals(sourceSha256(layout)) || !value.path("path").asText().equals(path(layout))
                || !value.path("layoutSha256").asText().equals(layout.digest()) || !value.path("task").asText().equals(task)
                || !value.path("action").asText().equals(action) || !value.path("sourceBytes").isIntegralNumber()
                || value.path("sourceBytes").longValue() != DeploymentAgentToolchainPrograms.controller(layout).getBytes(StandardCharsets.UTF_8).length)
                throw new IllegalArgumentException("Controller store receipt identity changed");
            String status = value.path("status").asText(), installed = value.path("installOperation").asText();
            if (status.equals("READY")) {
                if (!installed.matches("[a-f0-9]{32}") || !value.path("installTask").asText().matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
                    throw new IllegalArgumentException("Original controller installation owner missing");
            } else if (!status.equals("MISSING") || !installed.equals("-") || !value.path("installTask").asText().equals("-") || installation)
                throw new IllegalArgumentException("Controller store state is not confirmed");
            if (installation && !value.path("requestedInstallOperation").asText().equals(action))
                throw new IllegalArgumentException("Controller install attempt identity changed");
            return value;
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Controller store receipt is invalid", failure);
        }
    }

    static Map<String, Object> description(DeploymentRemoteLayout layout) {
        return Map.of("contractRevision", REVISION, "reviewManifest", MANIFEST.deepCopy(), "path", path(layout),
            "sourceSha256", sourceSha256(layout), "sourceBytes", DeploymentAgentToolchainPrograms.controller(layout).getBytes(StandardCharsets.UTF_8).length,
            "layout", Map.of("canonical", layout.canonical(), "sha256", layout.digest()),
            "loaderSha256", DeploymentAgentToolchainPrograms.sha256(loader(layout)),
            "meaning", "Pinned source and declared loader consistency, never a safety verdict or future execution receipt");
    }

    static boolean successful(OperationResult<CommandResult> result) {
        return result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent()
            && result.value().orElseThrow().exitCode().equals(Optional.of(0)) && !result.value().orElseThrow().truncated();
    }

    static OperationResult<CommandResult> unconfirmed() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            "DEPLOYMENT_AGENT_CONTROLLER_PROGRAM_UNCONFIRMED", "Original controller program state is unconfirmed", Map.of(), Optional.empty())));
    }

    private static String quote(String value) {
        return "'" + Objects.requireNonNull(value).replace("'", "'\\''") + "'";
    }

    static String resource(String name) {
        try (var stream = DeploymentAgentToolchainProgramStore.class.getResourceAsStream(ROOT + name)) {
            if (stream == null) throw new IllegalStateException("Shipped controller resource absent");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Shipped controller resource unavailable", failure);
        }
    }
}
