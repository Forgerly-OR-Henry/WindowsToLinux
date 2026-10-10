package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainSetup;

/** Keeps Agent preparation effects separate while retaining the original approvals, grants and cumulative deadline. / 独立承载 Agent 准备副作用，保留原审批、许可及累计期限。 */
public final class DeploymentAgentToolchainCommands implements DeploymentToolchainSetup.Commands {
    private static final String ROOT = "/usr/local/lib/windowstolinux/toolchains";

    private final DeploymentBuildCommandChannel commands;

    private final ServerId server;

    private final Supplier<String> operations;

    public DeploymentAgentToolchainCommands(DeploymentBuildCommandChannel commands, ServerId server) {
        this(commands, server, () -> UUID.randomUUID().toString().replace("-", ""));
    }

    DeploymentAgentToolchainCommands(DeploymentBuildCommandChannel commands, ServerId server, Supplier<String> operations) {
        this.commands = Objects.requireNonNull(commands);
        this.server = Objects.requireNonNull(server);
        this.operations = Objects.requireNonNull(operations);
    }

    @Override
    public OperationResult<CommandResult> prepare(DeploymentToolchainType ecosystem, String branch, String exact,
                                                  int seconds, String javaHome) throws ApiException {
        requirePreparation(ecosystem, branch, exact, seconds, javaHome);
        if (commands.remainingSeconds() < 45)
            return budgetExhausted();
        if (Set.of(DeploymentToolchainType.PYTHON, DeploymentToolchainType.PHP, DeploymentToolchainType.RUBY).contains(ecosystem)) {
            var dependencies = dependencies(ecosystem.name());
            if (!complete(dependencies))
                return dependencies;
        }
        seconds = (int) Math.min(seconds, commands.remainingSeconds() - 15);
        if (seconds < 30)
            return budgetExhausted();
        String operation = operation();
        return send("deployment.agent.toolchain.prepare.v1", controllerTargets(server, operation),
            DeploymentAgentToolchainPrograms.controller(), List.of("prepare", ecosystem.name(), branch, exact,
                Integer.toString(seconds), javaHome, operation), seconds + 15L);
    }

    @Override
    public OperationResult<CommandResult> bind(String encoded) throws ApiException {
        requireBinding(encoded);
        long seconds = Math.min(180, commands.remainingSeconds() - 15);
        if (seconds < 30)
            return budgetExhausted();
        String operation = operation();
        return send("deployment.agent.toolchain.bind.v1", controllerTargets(server, operation),
            DeploymentAgentToolchainPrograms.controller(), List.of("bind", encoded, Long.toString(seconds), operation),
            seconds + 15);
    }

    /** Prepares fixed native prerequisites after real C/CPP requirements are admitted. / 真实 C/CPP 需求准入后准备固定原生依赖。 */
    public OperationResult<CommandResult> prepareNativeBuild() throws ApiException {
        return dependencies("NATIVE_BUILD");
    }

    private OperationResult<CommandResult> dependencies(String profile) throws ApiException {
        if (commands.remainingSeconds() < 45)
            return budgetExhausted();
        String operation = operation();
        var result = send("deployment.agent.toolchain.dependencies.v1", dependencyTargets(server, profile),
            DeploymentAgentToolchainPrograms.dependencies(), List.of(profile, operation), Math.min(7200, commands.remainingSeconds()));
        if (!complete(result))
            return result;
        return dependencyReceipt(result.value().orElseThrow().stdout(), profile, operation)
            ? result : unconfirmed("DEPLOYMENT_AGENT_TOOLCHAIN_DEPENDENCIES_UNCONFIRMED");
    }

    private OperationResult<CommandResult> send(String meaning, Set<ActionTarget> targets, String source,
                                                List<String> arguments, long seconds) throws ApiException {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        try {
            if (bytes.length == 0 || bytes.length > 1048576)
                throw new IllegalArgumentException("Agent toolchain source exceeds executable wire limit");
            String script = DeploymentAgentToolchainPrograms.command(arguments);
            return commands.send(meaning, targets, script, bytes, Duration.ofSeconds(seconds), 65536);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static Set<ActionTarget> controllerTargets(ServerId server, String operation) {
        return Set.of(target(server, "deployment-build", ROOT), target(server, "deployment-build", ROOT + "/versions"), target(server, "deployment-build", ROOT + "/bindings"),
            target(server, "deployment-build", ROOT + "/work/agent-" + operation), target(server, "deployment-build", ROOT + "/.prepare.lock"),
            target(server, "systemd-unit", "windowstolinux-toolchain-" + operation + ".service"));
    }

    static Set<ActionTarget> dependencyTargets(ServerId server, String profile) {
        Set<ActionTarget> targets = new LinkedHashSet<>();
        for (String manager : List.of("APT", "DNF"))
            for (String name : DeploymentAgentToolchainPrograms.packages(manager, profile))
                targets.add(target(server, "system-package", name));
        return Set.copyOf(targets);
    }

    private static ActionTarget target(ServerId server, String kind, String id) {
        return new ActionTarget(server.id(), kind, id);
    }

    private String operation() {
        String operation = operations.get();
        if (operation == null || !operation.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("Fresh operation identity required");
        return operation;
    }

    static void requirePreparation(DeploymentToolchainType ecosystem, String branch, String exact,
                                   int seconds, String javaHome) {
        Objects.requireNonNull(ecosystem);
        if (seconds < 30 || seconds > 7200 || branch == null || !branch.matches("[0-9]{1,3}(?:\\.[0-9]{1,3})?")
            || DeploymentToolchainCatalog.defaults().branches(ecosystem).stream().noneMatch(candidate -> candidate.version().equals(branch)
            && candidate.installation() != DeploymentToolchainCatalog.InstallationType.SYSTEM_COMPILER)
            || !("-".equals(exact) || exact != null && DeploymentToolchainVersion.parse(ecosystem, exact)
            .filter(version -> version.text().equals(exact) && !version.preview() && version.branch().equals(branch)).isPresent())
            || javaHome == null || !(javaHome.equals("-") || javaHome.matches(ROOT + "/versions/java-[0-9a-f]{64}")))
            throw new IllegalArgumentException("Invalid Agent toolchain preparation");
    }

    static void requireBinding(String encoded) {
        byte[] bytes = null;
        try {
            if (encoded == null || encoded.length() > 87384)
                throw new IllegalArgumentException("Invalid Agent binding size");
            bytes = Base64.getDecoder().decode(encoded);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var binding = DeploymentToolchainBindingCodec.decode(text);
            if (bytes.length > 65536 || binding.selections().isEmpty() || binding.selections().size() > 32
                || !Base64.getEncoder().encodeToString(bytes).equals(encoded)
                || !DeploymentToolchainBindingCodec.encode(binding).equals(text))
                throw new IllegalArgumentException("Noncanonical Agent binding");
        } catch (java.nio.charset.CharacterCodingException malformed) {
            throw new IllegalArgumentException("Invalid Agent binding encoding", malformed);
        } finally {
            if (bytes != null)
                Arrays.fill(bytes, (byte) 0);
        }
    }

    private static boolean dependencyReceipt(String stdout, String ecosystem, String operation) {
        String prefix = "TOOLCHAIN_DEPENDENCIES=";
        if (!stdout.startsWith(prefix) || !stdout.endsWith("\n") || stdout.indexOf('\n') != stdout.length() - 1)
            return false;
        try (var parser = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .createParser(stdout.substring(prefix.length()))) {
            JsonNode root = new ObjectMapper().readTree(parser);
            if (root == null || parser.nextToken() != null || !root.isObject() || root.size() != 4
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
            return actual.equals(Set.copyOf(DeploymentAgentToolchainPrograms.packages(manager, ecosystem)));
        } catch (java.io.IOException | IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean complete(OperationResult<CommandResult> result) {
        return result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent()
            && result.value().orElseThrow().exitCode().equals(Optional.of(0)) && !result.value().orElseThrow().truncated();
    }

    private static OperationResult<CommandResult> unconfirmed(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN, event,
            "Agent toolchain effect is unconfirmed", Map.of(), Optional.empty())));
    }

    private static OperationResult<CommandResult> budgetExhausted() {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.TIMEOUT,
            "DEPLOYMENT_BUILD_BUDGET_EXHAUSTED", "Reviewed build execution budget is exhausted", Map.of(), Optional.empty())));
    }
}
