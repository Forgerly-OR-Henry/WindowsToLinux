package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

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
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainSetup;

/** Keeps Agent preparation effects separate while retaining the original approvals, grants and cumulative deadline. / 独立承载 Agent 准备副作用，保留原审批、许可及累计期限。 */
public final class DeploymentAgentToolchainCommands implements DeploymentToolchainSetup.VerifiedCommands {
    private final DeploymentRemoteLayout layout;

    private final boolean reuseExistingOnly;

    private final DeploymentBuildCommandChannel commands;

    private final ServerId server;

    private final Supplier<String> operations;

    private final DeploymentAgentToolchainProgramState programState;

    private JsonNode readyProgram;

    private OperationResult<CommandResult> readyObservation;

    private DeploymentAgentToolchainTracked.Observation lastTracked;

    @Override
    public OperationResult<DeploymentToolchainSetup.VerifiedSelection> prepareVerified(DeploymentToolchainType ecosystem,
            String branch, String exact, int seconds, String javaHome) throws ApiException {
        lastTracked = null;
        var result = prepare(ecosystem, branch, exact, seconds, javaHome);
        if (result.outcome() != TaskResult.SUCCEEDED) return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
        if (lastTracked == null || !lastTracked.succeeded()) return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(DeploymentAgentToolchainProgramState.unconfirmed().problem()));
        var selection = lastTracked.value().path("selection");
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new DeploymentToolchainSetup.VerifiedSelection(
            DeploymentToolchainType.valueOf(selection.path("ecosystem").asText()), selection.path("version").asText(),
            selection.path("directory").asText(), DeploymentResolvedToolchains.OriginType.valueOf(selection.path("origin").asText()),
            selection.path("source").asText(), selection.path("sha256").asText())), Optional.empty());
    }

    @Override
    public OperationResult<String> bindVerified(String encoded) throws ApiException {
        lastTracked = null;
        var result = bind(encoded);
        if (result.outcome() != TaskResult.SUCCEEDED) return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
        if (lastTracked == null || !lastTracked.succeeded()) return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(DeploymentAgentToolchainProgramState.unconfirmed().problem()));
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.ofNullable(lastTracked.bindingSha256()), Optional.empty());
    }

    public DeploymentAgentToolchainCommands(DeploymentBuildCommandChannel commands, ServerId server,
                                            gold.debug.windowstolinux.appapi.mod.ModContext context,
                                            gold.debug.windowstolinux.appapi.task.run.TaskContext task, DeploymentRemoteLayout layout) {
        this(commands, server, context, task, layout, false);
    }

    /** Isolation keeps existing immutable installations and may create only this task's probe/binding work. */
    public DeploymentAgentToolchainCommands(DeploymentBuildCommandChannel commands, ServerId server,
                                            gold.debug.windowstolinux.appapi.mod.ModContext context,
                                            gold.debug.windowstolinux.appapi.task.run.TaskContext task, DeploymentRemoteLayout layout,
                                            boolean reuseExistingOnly) {
        this(commands, server, () -> UUID.randomUUID().toString().replace("-", ""), layout,
            new DeploymentAgentToolchainProgramState(context, task, server, layout), reuseExistingOnly);
    }

    DeploymentAgentToolchainCommands(DeploymentBuildCommandChannel commands, ServerId server, Supplier<String> operations,
                                     DeploymentRemoteLayout layout, DeploymentAgentToolchainProgramState programState) {
        this(commands, server, operations, layout, programState, false);
    }

    private DeploymentAgentToolchainCommands(DeploymentBuildCommandChannel commands, ServerId server, Supplier<String> operations,
                                     DeploymentRemoteLayout layout, DeploymentAgentToolchainProgramState programState, boolean reuseExistingOnly) {
        this.reuseExistingOnly = reuseExistingOnly;
        this.layout = Objects.requireNonNull(layout);
        this.commands = Objects.requireNonNull(commands);
        this.server = Objects.requireNonNull(server);
        this.operations = Objects.requireNonNull(operations);
        this.programState = Objects.requireNonNull(programState);
    }

    @Override
    public OperationResult<CommandResult> prepare(DeploymentToolchainType ecosystem, String branch, String exact,
                                                  int seconds, String javaHome) throws ApiException {
        requirePreparation(ecosystem, branch, exact, seconds, javaHome, layout);
        if (commands.remainingSeconds() < 45)
            return budgetExhausted();
        if (!reuseExistingOnly && Set.of(DeploymentToolchainType.PYTHON, DeploymentToolchainType.PHP, DeploymentToolchainType.RUBY).contains(ecosystem)) {
            var dependencies = dependencies(ecosystem.name());
            if (!complete(dependencies))
                return dependencies;
        }
        if (commands.remainingSeconds() < 45)
            return budgetExhausted();
        String operation = operation();
        long lookupSeconds = Math.min(180, commands.remainingSeconds() - 15);
        var resolved = send("deployment.agent.toolchain.resolve.v1", resolverTargets(server, ecosystem, branch, exact),
            DeploymentAgentToolchainPrograms.resolver(layout), List.of("resolve", ecosystem.name(), branch, exact,
                Long.toString(lookupSeconds), operation), lookupSeconds + 15);
        if (!complete(resolved))
            return resolved;
        String encoded;
        try {
            String receipt = resolved.value().orElseThrow().stdout();
            if (!receipt.startsWith("TOOLCHAIN_RELEASE=") || !receipt.endsWith("\n") || receipt.indexOf('\n') != receipt.length() - 1)
                return unconfirmed("DEPLOYMENT_AGENT_TOOLCHAIN_RELEASE_UNCONFIRMED");
            encoded = receipt.substring("TOOLCHAIN_RELEASE=".length(), receipt.length() - 1);
            DeploymentAgentToolchainRelease.decode(encoded, ecosystem, branch, exact, operation, layout);
        } catch (IllegalArgumentException malformed) {
            return unconfirmed("DEPLOYMENT_AGENT_TOOLCHAIN_RELEASE_UNCONFIRMED");
        }
        var readiness = readyController();
        if (!complete(readiness)) return readiness;
        seconds = (int) Math.min(seconds, commands.remainingSeconds() - 15);
        if (seconds < 30)
            return budgetExhausted();
        var arguments = List.of("prepare", ecosystem.name(), branch, exact, Integer.toString(seconds), javaHome, operation, encoded);
        return stored("prepare", operation, arguments, seconds + 15L, readyProgram).result();
    }

    @Override
    public OperationResult<CommandResult> bind(String encoded) throws ApiException {
        requireBinding(encoded, layout);
        var readiness = readyController();
        if (!complete(readiness)) return readiness;
        long seconds = Math.min(180, commands.remainingSeconds() - 15);
        if (seconds < 30)
            return budgetExhausted();
        String operation = operation();
        return stored("bind", operation, List.of("bind", encoded, Long.toString(seconds), operation), seconds + 15, readyProgram).result();
    }

    private record StoredResult(OperationResult<CommandResult> result, JsonNode receipt) {
    }

    private OperationResult<CommandResult> readyController() throws ApiException {
        if (readyProgram != null) return readyObservation;
        if (commands.remainingSeconds() < 1) return budgetExhausted();
        String readiness = UUID.randomUUID().toString().replace("-", "");
        var observed = stored("readiness", readiness, List.of(), Math.min(30, commands.remainingSeconds()), null);
        if (!complete(observed.result())) return observed.result();
        JsonNode receipt = observed.receipt();
        var confirmedObservation = observed.result();
        if (receipt.path("status").asText().equals("MISSING") && !reuseExistingOnly) {
            String installation = UUID.randomUUID().toString().replace("-", "");
            var installed = stored("install", installation, List.of(), Math.min(60, commands.remainingSeconds()), receipt);
            if (!complete(installed.result())) return installed.result();
            receipt = installed.receipt();
            confirmedObservation = installed.result();
        }
        if (!receipt.path("status").asText().equals("READY")) return DeploymentAgentToolchainProgramStore.unconfirmed();
        readyProgram = receipt.deepCopy();
        readyObservation = confirmedObservation;
        return confirmedObservation;
    }

    private StoredResult stored(String phase, String operation, List<String> arguments, long seconds, JsonNode installation) throws ApiException {
        if (seconds < 1) return new StoredResult(budgetExhausted(), null);
        boolean existingPreparation = reuseExistingOnly && phase.equals("prepare");
        var recipe = Set.of("prepare", "bind").contains(phase) ? DeploymentAgentToolchainTracked.create(programState.taskId(), server.id(),
            commands.authenticatedFingerprint(), layout, phase.toUpperCase(java.util.Locale.ROOT), operation, arguments, existingPreparation) : null;
        String verb = phase + "-tracked";
        String script = recipe != null ? existingPreparation ? DeploymentAgentToolchainPrograms.trackedCommand(recipe)
            : DeploymentAgentToolchainProgramStore.trackedCommand(verb, recipe)
            : phase.equals("install") ? DeploymentAgentToolchainPrograms.command(List.of(programState.taskId(), operation))
            : DeploymentAgentToolchainProgramStore.command(phase, programState.taskId(), operation, arguments, layout);
        byte[] input = recipe != null ? existingPreparation ? DeploymentAgentToolchainPrograms.trackedInput(recipe) : recipe.bytes()
            : phase.equals("install") ? DeploymentAgentToolchainProgramStore.installer(layout).getBytes(StandardCharsets.UTF_8) : new byte[0];
        try (var request = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest(script, input,
            Duration.ofSeconds(Math.min(seconds, commands.remainingSeconds())), 65536)) {
            String exact = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, "controller-action-state", request).exactAction();
            var storeFrame = recipe == null ? DeploymentAgentToolchainTracked.JSON.createObjectNode().put("formatVersion", 1)
                .put("task", programState.taskId()).put("server", server.id()).put("fingerprintSha256", DeploymentAgentToolchainTracked.hash(commands.authenticatedFingerprint().getBytes(StandardCharsets.UTF_8)))
                .put("layout", layout.canonical()).put("phase", phase).put("operation", operation)
                .put("version", DeploymentAgentToolchainProgramStore.version(layout)).put("sourceSha256", DeploymentAgentToolchainProgramStore.sourceSha256(layout))
                .put("scriptSha256", DeploymentAgentToolchainPrograms.sha256(script)).put("stdinSha256", DeploymentAgentToolchainTracked.hash(input))
                .put("stdinBytes", input.length).put("timeout", request.timeout().toString()).put("outputLimit", request.outputLimitBytes()) : null;
            var retained = programState.begin(phase.toUpperCase(java.util.Locale.ROOT), operation, exact, installation, recipe, storeFrame);
            OperationResult<CommandResult> result;
            boolean[] dispatchBoundary = {false};
            try {
                result = commands.send(existingPreparation ? "deployment.agent.toolchain.prepare.v1" : DeploymentAgentToolchainProgramStore.PREFIX + phase + ".v1",
                    existingPreparation ? controllerTargets(server, operation, layout)
                        : DeploymentAgentToolchainProgramStore.targets(server, phase, operation, operation, layout), script, input, request.timeout(), 65536,
                    () -> {
                        try { programState.dispatched(retained); }
                        catch (ApiException failure) { throw new IllegalStateException("Original controller dispatch could not be retained", failure); }
                        dispatchBoundary[0] = true;
                    });
            } catch (ApiException failure) {
                try {
                    var failed = new OperationResult<CommandResult>(failure.problem().code() == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.UNKNOWN,
                        Optional.empty(), Optional.of(failure.problem()));
                    programState.transport(retained, failed);
                    programState.finish(retained, failed, false, null, !dispatchBoundary[0]);
                } catch (ApiException persistence) {
                    if (failure instanceof gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException) {
                        var uncertain = new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_CONTROLLER_STATE_UNCONFIRMED",
                            "Original denied controller action could not retain its NOT_SENT boundary", Map.of("task", programState.taskId(), "operation", operation), Optional.empty()));
                        uncertain.initCause(persistence);
                        uncertain.addSuppressed(failure);
                        throw uncertain;
                    }
                    failure.addSuppressed(persistence);
                }
                throw failure;
            }
            JsonNode receipt = null;
            boolean confirmed = DeploymentAgentToolchainProgramStore.successful(result);
            programState.transport(retained, result);
            if (recipe != null) {
                try {
                    lastTracked = DeploymentAgentToolchainTracked.receipt(result, recipe, verb);
                    receipt = lastTracked.value(); confirmed = lastTracked.settled();
                } catch (IllegalArgumentException invalid) { lastTracked = null; confirmed = false; }
            }
            if (confirmed && Set.of("readiness", "install").contains(phase)) {
                try {
                    receipt = DeploymentAgentToolchainProgramStore.receipt(result, programState.taskId(), operation, layout, phase.equals("install"));
                } catch (IllegalArgumentException invalid) {
                    confirmed = false;
                }
            }
            var refusal = recipe == null && dispatchBoundary[0] ? DeploymentAgentToolchainFailureEvidence.precreationRefusal(retained,
                phase, operation, programState.taskId(), server, layout, request, result).orElse(null) : null;
            programState.finish(retained, result, confirmed, receipt, !dispatchBoundary[0], refusal);
            if (recipe != null) return new StoredResult(DeploymentAgentToolchainFailureEvidence.tracked(result, lastTracked,
                retained.id(), retained.checkpoint().snapshot().released(), retained.checkpoint().snapshot().payload()), receipt);
            var reported = !confirmed && dispatchBoundary[0] && result.outcome() != TaskResult.CANCELLED
                ? DeploymentAgentToolchainProgramStore.unconfirmed() : result;
            if (Set.of("prepare", "bind").contains(phase))
                reported = DeploymentAgentToolchainFailureEvidence.retain(reported, phase, server, operation);
            return new StoredResult(reported, receipt);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    /** Prepares fixed native prerequisites after real C/CPP requirements are admitted. / 真实 C/CPP 需求准入后准备固定原生依赖。 */
    public OperationResult<CommandResult> prepareNativeBuild() throws ApiException {
        return dependencies("NATIVE_BUILD");
    }

    private OperationResult<CommandResult> dependencies(String profile) throws ApiException {
        if (reuseExistingOnly) return unconfirmed("DEPLOYMENT_AGENT_ISOLATION_SHARED_DEPENDENCIES_FORBIDDEN");
        if (commands.remainingSeconds() < 45)
            return budgetExhausted();
        String operation = operation();
        var result = send("deployment.agent.toolchain.dependencies.v1", dependencyTargets(server, profile),
            DeploymentAgentToolchainPrograms.dependencies(layout), List.of(profile, operation), Math.min(7200, commands.remainingSeconds()));
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
            var result = commands.send(meaning, targets, script, bytes, Duration.ofSeconds(seconds), 65536);
            String phase = meaning.substring("deployment.agent.toolchain.".length(), meaning.length() - 3);
            int identityIndex = switch (phase) {
                case "dependencies" -> 1;
                case "resolve" -> 5;
                case "prepare" -> 6;
                default -> 3;
            };
            return DeploymentAgentToolchainFailureEvidence.retain(result, phase, server, arguments.get(identityIndex));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static Set<ActionTarget> controllerTargets(ServerId server, String operation) {
        return controllerTargets(server, operation, DeploymentRemoteLayout.original());
    }

    static Set<ActionTarget> controllerTargets(ServerId server, String operation, DeploymentRemoteLayout layout) {
        String ROOT = layout.toolchains();
        return Set.of(target(server, "deployment-build", ROOT), target(server, "deployment-build", ROOT + "/versions"), target(server, "deployment-build", ROOT + "/bindings"),
            target(server, "deployment-build", ROOT + "/work/agent-" + operation), target(server, "deployment-build", ROOT + "/.prepare.lock"),
            target(server, "systemd-unit", "windowstolinux-toolchain-" + operation + ".service"));
    }

    static Set<ActionTarget> resolverTargets(ServerId server, DeploymentToolchainType ecosystem, String branch, String exact) {
        return Set.of(target(server, "deployment-host", server.id()),
            target(server, "toolchain-source", ecosystem.name() + ":" + branch + ":" + exact));
    }

    static Set<ActionTarget> dependencyTargets(ServerId server, String profile) {
        Set<ActionTarget> targets = new LinkedHashSet<>();
        for (String manager : List.of("APT", "DNF")) {
            targets.add(target(server, "system-package-index", manager + ":configured-authenticated-sources"));
            targets.add(target(server, "system-package-transaction", manager + ":authenticated-dependency-closure:" + profile));
            for (String name : DeploymentAgentToolchainPrograms.packages(manager, profile))
                targets.add(target(server, "system-package", name));
        }
        targets.add(target(server, "system-repository", "DNF:centos:crb:transaction-only"));
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
        requirePreparation(ecosystem, branch, exact, seconds, javaHome, DeploymentRemoteLayout.original());
    }

    static void requirePreparation(DeploymentToolchainType ecosystem, String branch, String exact,
                                   int seconds, String javaHome, DeploymentRemoteLayout layout) {
        Objects.requireNonNull(ecosystem);
        if (seconds < 30 || seconds > 7200 || branch == null || !branch.matches("[0-9]{1,3}(?:\\.[0-9]{1,3})?")
            || DeploymentToolchainCatalog.defaults().branches(ecosystem).stream().noneMatch(candidate -> candidate.version().equals(branch)
            && candidate.installation() != DeploymentToolchainCatalog.InstallationType.SYSTEM_COMPILER)
            || !("-".equals(exact) || exact != null && DeploymentToolchainVersion.parse(ecosystem, exact)
            .filter(version -> version.text().equals(exact) && !version.preview() && version.branch().equals(branch)).isPresent())
            || javaHome == null || !(javaHome.equals("-") || javaHome.matches(java.util.regex.Pattern.quote(layout.toolchains()) + "/versions/java-[0-9a-f]{64}")))
            throw new IllegalArgumentException("Invalid Agent toolchain preparation");
    }

    static DeploymentResolvedToolchains requireBinding(String encoded) {
        return requireBinding(encoded, DeploymentRemoteLayout.original());
    }

    static DeploymentResolvedToolchains requireBinding(String encoded, DeploymentRemoteLayout layout) {
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
            if (binding.selections().stream().anyMatch(selection -> selection.origin() == DeploymentResolvedToolchains.OriginType.MANAGED
                && !selection.directory().matches(java.util.regex.Pattern.quote(layout.toolchains()) + "/versions/[a-z_]+-[0-9a-f]{64}")))
                throw new IllegalArgumentException("Agent managed toolchain belongs to another layout");
            return binding;
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
