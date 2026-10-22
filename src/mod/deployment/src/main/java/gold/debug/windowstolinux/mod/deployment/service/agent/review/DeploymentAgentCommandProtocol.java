package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandEvidence;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandProtocol;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandProtocol;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainInputEvidence;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainProgramStore;

/** Agent-owned fixed protocols; common commands retain the shared validator. */
public final class DeploymentAgentCommandProtocol implements DeploymentCommandProtocol {
    private static final Set<String> OPERATIONS = Set.of(
        "deployment.helper.install.files.custom.v1", "deployment.agent.helper.readiness.v1", "deployment.agent.helper.v1",
        "deployment.agent.preparation.absence.v1", "deployment.agent.preparation.partial-observation.v1",
        "deployment.agent.toolchain.dependencies.v1", "deployment.agent.toolchain.resolve.v1", "deployment.agent.toolchain.prepare.v1", "deployment.agent.toolchain.bind.v1",
        "deployment.agent.publication-recovery.readiness.v1", "deployment.agent.publication-recovery.install.v1", "deployment.agent.publication-recovery.guarded-first-rollback.v1",
        "deployment.agent.publication-recovery-diagnostic.v1", "deployment.agent.publication-recovery-preflight.v2", "deployment.agent.publication-continuation.v1",
        "deployment.agent.publication-contract-diagnostic.v1", "deployment.agent.publication-contract-diagnostic.followup.v1", "deployment.agent.publication-correction.v1", "deployment.agent.isolation.preflight.v1",
        "deployment.agent.toolchain.controller.readiness.v1", "deployment.agent.toolchain.controller.install.v1", "deployment.agent.toolchain.controller.prepare.v1", "deployment.agent.toolchain.controller.bind.v1");
    private static final Set<String> EMPTY_INPUT = Set.of("deployment.agent.toolchain.controller.readiness.v1",
        "deployment.agent.toolchain.controller.prepare.v1", "deployment.agent.toolchain.controller.bind.v1",
        "deployment.agent.publication-recovery.readiness.v1", "deployment.agent.publication-recovery.guarded-first-rollback.v1");
    private final DeploymentFixedCommandProtocol common;
    private final Supplier<?> receipts;

    public DeploymentAgentCommandProtocol(ModContext context, Supplier<?> receipts) {
        common = new DeploymentFixedCommandProtocol(context);
        this.receipts = Objects.requireNonNull(receipts);
    }
    public boolean registered(ActionIntent intent) {
        return intent.semantics().filter(value -> OPERATIONS.contains(value.operation())).isPresent() || common.registered(intent);
    }
    public Map<String, Object> inspect(ActionIntent intent, CommandRequest request, DeploymentRemoteLayout layout,
                                       TaskContext task) throws IOException, ApiException {
        String operation = intent.semantics().orElseThrow().operation();
        if (!OPERATIONS.contains(operation)) return common.inspect(intent, request, layout, task);
        DeploymentFixedCommandProtocol.requireTaskTargets(intent, task);
        if (operation.equals("deployment.agent.isolation.preflight.v1")) requireIsolationAllowed(request, layout);
        else DeploymentCommandStaticGate.requireFixedAllowed(request.script());
        var facts = facts(intent, request, layout);
        if (facts.containsKey("agentExecutableInputBinding")) {
            String invocation = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract.verifiedInvocation(request.script(), layout);
            var frame = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("'" + layout.helperPath() + "' 'agent-run' ")
                + "'[A-Za-z0-9._-]+' '[A-Za-z0-9._-]+' '([^']+)' ").matcher(invocation);
            if (!frame.lookingAt() || !task.id().toString().equals(frame.group(1)))
                throw new IllegalArgumentException("Agent executable task differs from original context");
        }
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode current = json.valueToTree(facts);
        if (operation.equals("deployment.agent.isolation.preflight.v1")) {
            if (!intent.revision().equals(current.path("agentIsolation").path("acceptedScope").path("configurationSha256").asText()))
                throw new IllegalArgumentException("Isolation configuration revision changed");
        } else for (var plan : current.findValues("planSha256"))
            if (!intent.revision().equals(plan.asText())) throw new IllegalArgumentException("Agent program business revision changed");
        if (operation.equals(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram.ROLLBACK)) {
            var arguments = current.path("publicationRecoveryProgram").path("typedArguments");
            if (!intent.revision().equals(arguments.get(arguments.size() - 1).asText()))
                throw new IllegalArgumentException("Recovery plan revision changed");
        }
        if (operation.startsWith(DeploymentAgentToolchainProgramStore.PREFIX))
            DeploymentAgentToolchainInputEvidence.selectInstalled(current, layout,
                json.valueToTree(receipts.get()), task.id().toString()).orElseThrow();
        else DeploymentAgentToolchainInputEvidence.select(current, layout);
        return facts;
    }
    /** Exact standalone isolation factory, kept inside the owning mode. */
    public static void requireIsolationAllowed(CommandRequest request, DeploymentRemoteLayout layout) {
        try (var fixed = gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.decode(request, layout)) {
            DeploymentCommandStaticGate.requireFixedAllowed(fixed.command().script());
            byte[] input = fixed.command().stdin();
            try { DeploymentCommandStaticGate.requireFixedAllowed(new String(input, StandardCharsets.UTF_8)); }
            finally { Arrays.fill(input, (byte) 0); }
        }
    }
    /** Complete facts; task-bound inspect remains the admission boundary. */
    public static Map<String, Object> facts(ActionIntent intent, CommandRequest request) throws IOException {
        return facts(intent, request, DeploymentRemoteLayout.original());
    }
    public static Map<String, Object> facts(ActionIntent intent, CommandRequest request, DeploymentRemoteLayout layout) throws IOException {
        String operation = intent.semantics().orElseThrow().operation();
        if (!OPERATIONS.contains(operation)) return DeploymentCommandEvidence.facts(intent, request, layout);
        boolean installedHelper = !Set.of("deployment.agent.publication-recovery-diagnostic.v1", "deployment.agent.publication-recovery-preflight.v2",
            "deployment.agent.publication-contract-diagnostic.v1", "deployment.agent.publication-contract-diagnostic.followup.v1",
            "deployment.agent.publication-correction.v1", "deployment.agent.isolation.preflight.v1").contains(operation)
            && request.script().contains(layout.helperPath());
        var facts = new LinkedHashMap<String, Object>(DeploymentCommandEvidence.baseFacts(intent, request, layout, installedHelper));
        String invocation = installedHelper ? gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract.verifiedInvocation(request.script(), layout) : request.script();
        if (operation.equals(gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.OPERATION)) {
            if (intent.serverIds().size() != 1) throw new IllegalArgumentException("Isolation server identity missing");
            var probe = gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe
                .reviewFacts(request, layout);
            var required = gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe
                .reviewedResourceTargets(request, layout, new gold.debug.windowstolinux.appapi.server.model.ServerId(intent.serverIds().getFirst()));
            if (!intent.semantics().orElseThrow().targets().equals(required))
                throw new IllegalArgumentException("Isolation resource identity changed");
            facts.put("agentIsolation", probe);
        }
        if (operation.equals(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationCorrectionProgram.OPERATION)) {
            if (intent.serverIds().size() != 1)
                throw new IllegalArgumentException("Publication correction server identity missing");
            var program = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationCorrectionProgram.reviewFacts(request, layout);
            var required = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationCorrectionProgram
                .reviewedResourceTargets(request, layout, new gold.debug.windowstolinux.appapi.server.model.ServerId(intent.serverIds().getFirst()));
            if (!intent.semantics().orElseThrow().targets().equals(required))
                throw new IllegalArgumentException("Publication correction resource identity changed");
            facts.put("publicationCorrection", program);
        }
        if (operation.equals(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticFollowupProgram.OPERATION)) {
            if (intent.serverIds().size() != 1)
                throw new IllegalArgumentException("Contract diagnostic followup server identity missing");
            var program = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticFollowupProgram.reviewFacts(request, layout);
            var required = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticFollowupProgram
                .reviewedResourceTargets(request, layout, new gold.debug.windowstolinux.appapi.server.model.ServerId(intent.serverIds().getFirst()));
            if (!intent.semantics().orElseThrow().targets().equals(required))
                throw new IllegalArgumentException("Contract diagnostic followup resource identity changed");
            facts.put("publicationContractDiagnosticFollowup", program);
        }
        if (operation.equals(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram.OPERATION)) {
            if (intent.serverIds().size() != 1)
                throw new IllegalArgumentException("Contract diagnostic server identity missing");
            var program = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram.reviewFacts(request, layout);
            var required = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram
                .reviewedResourceTargets(request, layout, new gold.debug.windowstolinux.appapi.server.model.ServerId(intent.serverIds().getFirst()));
            if (!intent.semantics().orElseThrow().targets().equals(required))
                throw new IllegalArgumentException("Contract diagnostic resource identity changed");
            facts.put("publicationContractDiagnostic", program);
        }
        if (operation.equals(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.OPERATION)) {
            if (intent.serverIds().size() != 1)
                throw new IllegalArgumentException("Continuation server identity missing");
            var program = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.reviewFacts(request, layout);
            var required = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram
                .reviewedResourceTargets(request, layout, new gold.debug.windowstolinux.appapi.server.model.ServerId(intent.serverIds().getFirst()));
            if (!intent.semantics().orElseThrow().targets().equals(required))
                throw new IllegalArgumentException("Continuation resource identity changed");
            facts.put("publicationRecoveryContinuation", program);
        }
        if (operation.equals(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryPreflightProgram.OPERATION)) {
            if (intent.serverIds().size() != 1) throw new IllegalArgumentException("Preflight server identity missing");
            var program = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryPreflightProgram.reviewFacts(request, layout);
            var required = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryPreflightProgram
                .reviewedResourceTargets(request, layout, new gold.debug.windowstolinux.appapi.server.model.ServerId(intent.serverIds().getFirst()));
            if (!intent.semantics().orElseThrow().targets().equals(required))
                throw new IllegalArgumentException("Preflight resource identity changed");
            facts.put("publicationRecoveryPreflight", program);
        }
        if (operation.equals(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnosticProgram.OPERATION)) {
            if (intent.serverIds().size() != 1)
                throw new IllegalArgumentException("Diagnostic server identity missing");
            var program = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnosticProgram.reviewFacts(request, layout);
            var required = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnosticProgram
                .reviewedResourceTargets(request, layout, new gold.debug.windowstolinux.appapi.server.model.ServerId(intent.serverIds().getFirst()));
            if (!intent.semantics().orElseThrow().targets().equals(required))
                throw new IllegalArgumentException("Diagnostic resource identity changed");
            facts.put("publicationRecoveryDiagnostic", program);
        }
        if (operation.startsWith(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram.PREFIX)) {
            var program = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram
                .reviewFacts(operation, request, layout);
            if (intent.serverIds().size() != 1) throw new IllegalArgumentException("Recovery server identity missing");
            var required = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram
                .reviewedResourceTargets(operation, request, layout, new gold.debug.windowstolinux.appapi.server.model.ServerId(intent.serverIds().getFirst()));
            if (!intent.semantics().orElseThrow().targets().equals(required))
                throw new IllegalArgumentException("Recovery resource identity changed");
            facts.put("publicationRecoveryProgram", program);
        }

        if (operation.equals("deployment.helper.install.files.custom.v1")) {
            facts.put("expectedHelperPath", layout.helperPath());
            facts.put("expectedHelperSha256", DeploymentManagedHelperBundle.helperSha256(layout));
        }
        boolean agentRun = operation.equals("deployment.agent.helper.v1") && invocation.startsWith("'" + layout.helperPath() + "' 'agent-run' ");
        boolean patch = operation.equals("deployment.agent.helper.v1") && invocation.startsWith("'" + layout.helperPath() + "' 'agent-patch' ");
        if (agentRun) {
            facts.put("installedBuildEntryPath", layout.buildEntry());
            facts.put("installedBuildEntrySha256", DeploymentManagedHelperBundle.buildEntrySha256(layout));
        }
        byte[] input = request.stdin();
        try {
            if (EMPTY_INPUT.contains(operation) && input.length != 0)
                throw new IllegalArgumentException("Installed Agent protocol requires empty stdin");
            boolean scriptInput = !Set.of("deployment.agent.toolchain.controller.readiness.v1",
                "deployment.agent.toolchain.controller.prepare.v1", "deployment.agent.toolchain.controller.bind.v1", "deployment.agent.helper.v1").contains(operation);
            if (scriptInput || agentRun || patch) {
                String executable = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();
                if (!patch && !executable.isBlank()) {
                    if (agentRun) DeploymentCommandStaticGate.requireAllowed(executable);
                    else DeploymentCommandStaticGate.requireFixedAllowed(executable);
                }
                if (agentRun)
                    facts.put("agentExecutableInputBinding", gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentAgentCommandInputBinding.facts(invocation, input, executable, layout));
                facts.put("inputKind", patch ? "SOURCE_PATCH" : "EXECUTABLE_SCRIPT");
                facts.put("completeExecutableInput", operation.equals(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram.INSTALL)
                    ? new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram(layout).installationEvidence()
                    : operation.equals("deployment.helper.install.files.custom.v1") ? DeploymentAgentHelperBootstrapEvidence.describe(executable, layout) : executable);
            } else facts.put("inputKind", input.length == 0 ? "EMPTY" : "OPAQUE_BOUND_DATA");
        } finally { Arrays.fill(input, (byte) 0); }
        return java.util.Collections.unmodifiableMap(facts);
    }
}
