package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;

/** Adds complete executable input while keeping protected data payloads opaque. / 增补完整可执行输入，受保护数据载荷继续保持不透明。 */
final class DeploymentCommandEvidence {
    /** Fixed operations whose stdin is executable Bash rather than protected data. / stdin是可执行Bash而非受保护数据的固定动作。 */
    private static final Set<String> SCRIPT_INPUT = Set.of("deployment.helper.install.v1",
        "deployment.helper.install.tracked.v1", "deployment.helper.install.files.v1", "deployment.helper.install.files.custom.v1", "deployment.agent.helper.readiness.v1",
        "deployment.helper.install.status.v1", "deployment.build.run.v1", "deployment.model.command.v1", "deployment.agent.preparation.absence.v1", "deployment.agent.preparation.partial-observation.v1",
        "deployment.agent.toolchain.dependencies.v1", "deployment.agent.toolchain.resolve.v1", "deployment.agent.toolchain.prepare.v1", "deployment.agent.toolchain.bind.v1",
        "deployment.agent.toolchain.controller.install.v1", "deployment.agent.publication-recovery.readiness.v1",
        "deployment.agent.publication-recovery.install.v1", "deployment.agent.publication-recovery.guarded-first-rollback.v1",
        "deployment.agent.publication-recovery-diagnostic.v1", "deployment.agent.publication-recovery-preflight.v2", "deployment.agent.publication-continuation.v1", "deployment.agent.publication-contract-diagnostic.v1", "deployment.agent.publication-contract-diagnostic.followup.v1", "deployment.agent.publication-correction.v1", "deployment.agent.isolation.preflight.v1");

    /** Prevents construction. / 禁止构造。 */
    private DeploymentCommandEvidence() {
    }

    /** Classifies only original executable formats; arbitrary payloads are never decoded. / 仅分类原可执行格式，绝不解码任意载荷。
     * @param intent exact fixed command intent / 精确固定命令意图
     * @param request complete immutable request / 完整不可变请求
     * @return bounded complete command facts with opaque protected input binding / 有界完整命令事实及不透明受保护输入绑定
     * @throws java.io.IOException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    static Map<String, Object> facts(ActionIntent intent, CommandRequest request) throws java.io.IOException {
        return facts(intent, request, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    static Map<String, Object> facts(ActionIntent intent, CommandRequest request,
                                     gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) throws java.io.IOException {
        var facts = new LinkedHashMap<String, Object>();
        String operation = intent.semantics().orElseThrow().operation();
        boolean installedHelper = !operation.equals("deployment.helper.install.v1") && !operation.equals("deployment.helper.install.tracked.v1")
            && !operation.equals("deployment.helper.install.files.v1")
            && !operation.equals("deployment.agent.publication-recovery-diagnostic.v1")
            && !operation.equals("deployment.agent.publication-recovery-preflight.v2")
            && !operation.equals("deployment.agent.publication-contract-diagnostic.v1")
            && !operation.equals("deployment.agent.publication-contract-diagnostic.followup.v1")
            && !operation.equals("deployment.agent.publication-correction.v1")
            && !operation.equals("deployment.agent.isolation.preflight.v1")
            && request.script().contains(layout.helperPath());
        String invocation = installedHelper ? gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract.verifiedInvocation(request.script(), layout) : request.script();
        if (installedHelper) {
            facts.put("installedHelperPath", layout.helperPath());
            facts.put("installedHelperSha256", DeploymentManagedHelperBundle.helperSha256(layout));
            facts.put("platformContractRevision", gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract.REVISION);
        }
        facts.put("operation", operation);
        if (operation.equals("deployment.model.command.v1")) {
            facts.put("commandOrigin", "AI_GENERATED_BODY_WITH_PRODUCT_RECEIPT_WRAPPER");
            facts.put("executionScope", "Selected SSH host identity; not a project-build sandbox. Deployment and authorized host prerequisites only. modelCommand supplies the exact model body and its untrusted rationale, separated from the byte-validated product envelope. The product envelope records only its own task/operation/body-bound receipt. The model body must not alter receipt or approval files. Review both bodies and all host effects; this description does not grant permission.");
        }
        facts.put("serverTargets", intent.serverIds());
        facts.put("resourceTargets", intent.semantics().orElseThrow().targets().stream().sorted(java.util.Comparator
            .comparing(gold.debug.windowstolinux.appapi.task.action.ActionTarget::serverId)
            .thenComparing(gold.debug.windowstolinux.appapi.task.action.ActionTarget::kind)
            .thenComparing(gold.debug.windowstolinux.appapi.task.action.ActionTarget::id)).toList());
        facts.put("businessRevision", intent.revision());
        facts.put("businessRevisionMeaning", "Author-owned identity of the reviewed business state or workflow plan, captured independently of per-resource ownership manifests, releases, executable bodies and toolchain bindings. These identities describe different objects and are not required to be equal. Compare each digest to the same object's bound expected value and enforced code checks; this description does not verify a resource or waive any target/integrity check.");
        facts.put("exactAction", intent.exactAction());
        platformProbe(operation, request).ifPresent(probe -> facts.put("platformProbe", probe));
        if (operation.equals("deployment.candidate.capacity.v1")) {
            byte[] input = request.stdin();
            try {
                if (input.length == 0 && request.script().equals(gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel.capacityScript(layout))) {
                    String target = gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel.capacityTarget(layout);
                    if (intent.serverIds().size() != 1 || !intent.semantics().orElseThrow().targets().equals(Set.of(
                        new gold.debug.windowstolinux.appapi.task.action.ActionTarget(intent.serverIds().getFirst(), "deployment-workspace", target))))
                        throw new IllegalArgumentException("Capacity observation resource differs");
                    facts.put("capacityProbe", Map.of("comparison", "EXACT_PRODUCT_CAPACITY_SCRIPT_EMPTY_STDIN_AND_DECLARED_TARGET",
                        "target", target, "scriptBytes", request.script().getBytes(StandardCharsets.UTF_8).length,
                        "meaning", "Fixed OS test/stat filesystem-capacity observation, distinct from host/container platformProbe. Complete original script remains supplied for review. Original layout tests /var/lib existence/non-symlink and reads statfs; custom layouts check root ownership/non-symlink ancestry until an absent component, then statfs on the existing ancestor. No helper, project executable, installation, filesystem write or application ownership lookup is invoked. Literal request/target comparison only, not observed live capacity, OS binary trust, safety verdict or permission."));
                }
            } finally {
                Arrays.fill(input, (byte) 0);
            }
        }
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
        if (operation.equals("deployment.toolchain.bind.v1")) {
            facts.put("decodedToolchainBinding", binding(invocation, layout));
            facts.put("toolchainSubcommand", "bind");
        }
        if (operation.equals("deployment.toolchain.prepare.v1"))
            preparation(invocation, layout).ifPresent(value -> facts.put("toolchainPreparation", value));
        if (operation.equals("deployment.build.run.v1"))
            buildBinding(invocation, layout).ifPresent(binding -> facts.put("decodedToolchainBinding", binding));
        if (operation.equals("deployment.helper.install.v1") || operation.equals("deployment.helper.install.tracked.v1")
            || operation.equals("deployment.helper.install.files.v1") || operation.equals("deployment.helper.install.files.custom.v1")) {
            facts.put("expectedHelperPath", layout.helperPath());
            facts.put("expectedHelperSha256", DeploymentManagedHelperBundle.helperSha256(layout));
        }
        boolean agentRun = operation.equals("deployment.agent.helper.v1")
            && invocation.startsWith("'" + layout.helperPath() + "' 'agent-run' ");
        boolean patch = operation.equals("deployment.agent.helper.v1")
            && invocation.startsWith("'" + layout.helperPath() + "' 'agent-patch' ");
        boolean installedBuildEntry = operation.equals("deployment.build.run.v1") || agentRun;
        if (installedBuildEntry) {
            facts.put("installedBuildEntryPath", layout.buildEntry());
            facts.put("installedBuildEntrySha256", DeploymentManagedHelperBundle.buildEntrySha256(layout));
        }
        byte[] input = request.stdin();
        try {
            if (Set.of("deployment.publication.publish.v1", "deployment.publication.health.v1").contains(operation))
                workload(invocation, input, intent, layout).ifPresent(value -> facts.put("workloadInput", value));
            if (operation.equals("deployment.publication.snapshot.v1"))
                publicationSnapshot(invocation, input, intent, layout).ifPresent(value -> facts.put("publicationSnapshot", value));
            if (operation.equals("deployment.publication.config.v1"))
                stagedConfiguration(invocation, input, intent, layout).ifPresent(value -> facts.put("stagedConfiguration", value));
            if (SCRIPT_INPUT.contains(operation) || agentRun || patch) {
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
                    : operation.equals("deployment.helper.install.files.custom.v1")
                    ? gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentHelperBootstrapEvidence.describe(executable, layout)
                    : operation.equals("deployment.helper.install.files.v1")
                    ? gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.describeFiles(executable, layout)
                    : operation.equals("deployment.helper.install.v1")
                    || operation.equals("deployment.helper.install.tracked.v1")
                    ? gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.describe(executable, layout) : executable);
                if (operation.equals("deployment.model.command.v1")) {
                    if (!request.script().equals(gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.LAUNCHER))
                        throw new IllegalArgumentException("Model command launcher changed");
                    var modelCommand = gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.executableFacts(executable, layout);
                    if (!intent.semantics().orElseThrow().targets().contains(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(
                        intent.serverIds().getFirst(), "task", modelCommand.get("task").toString())))
                        throw new IllegalArgumentException("Model command task target changed");
                    facts.put("modelCommand", modelCommand);
                }
            } else {
                facts.put("inputKind", input.length == 0 ? "EMPTY" : "OPAQUE_BOUND_DATA");
            }
        } finally {
            Arrays.fill(input, (byte) 0);
        }
        return java.util.Collections.unmodifiableMap(facts);
    }

    /** Adds only status facts cross-checked against the original task journal. / 仅增加与原任务账本交叉核对的状态事实。 */
    static Map<String, Object> facts(ActionIntent intent, CommandRequest request,
                                     gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout,
                                     gold.debug.windowstolinux.appapi.mod.ModContext context,
                                     gold.debug.windowstolinux.appapi.task.run.TaskContext task)
        throws java.io.IOException, gold.debug.windowstolinux.appapi.task.result.ApiException {
        var result = new LinkedHashMap<String, Object>(facts(intent, request, layout));
        if (result.get("publicationSnapshot") instanceof Map<?, ?> snapshot) {
            if (!task.id().toString().equals(snapshot.get("task")))
                throw new IllegalArgumentException("Snapshot task differs from original review context");
            result.put("snapshotTaskMatchesOriginalContext", true);
        }
        if (intent.semantics().orElseThrow().operation().equals("deployment.model.command.status.v1"))
            result.put("modelCommandReceiptStatus", gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner
                .receiptStatusEvidence(context, task, intent, request, layout));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static java.util.Optional<Map<String, Object>> workload(String script, byte[] input, ActionIntent intent,
                                                                    gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout)
        throws CharacterCodingException {
        if (input.length != 0) return java.util.Optional.empty();
        String helper = java.util.regex.Pattern.quote("'" + layout.helperPath() + "' ");
        var publish = java.util.regex.Pattern.compile(helper
            + "'(publish-deployment|publish-container)' '([a-z0-9][a-z0-9-]{0,62})' '([a-z0-9][a-z0-9-]{0,62}-[a-f0-9]{16})' '([a-f0-9]{64})' '([a-f0-9]{64})' 'identity-v2' '(SYSTEMD_STATIC|CONTAINER_NON_ROOT)' '([A-Za-z0-9+/=]{1,87384})'(?= )").matcher(script);
        var health = java.util.regex.Pattern.compile(helper
            + "'application-health' '([a-z0-9][a-z0-9-]{0,62})' '([a-f0-9]{64})' '([A-Za-z0-9+/=]{1,87384})'\\z").matcher(script);
        boolean publishing = intent.semantics().orElseThrow().operation().equals("deployment.publication.publish.v1");
        if (publishing ? !publish.lookingAt() : !health.matches()) return java.util.Optional.empty();
        var publicationArguments = publishing ? DeploymentWorkloadReviewEvidence.publicationArguments(script) : java.util.Optional.<Map<String, Object>>empty();
        if (publishing && publicationArguments.isEmpty()) return java.util.Optional.empty();
        String app = publishing ? publish.group(2) : health.group(1);
        var result = new LinkedHashMap<String, Object>(DeploymentWorkloadReviewEvidence.decode(publishing ? publish.group(7) : health.group(3), !publishing));
        var targets = intent.semantics().orElseThrow().targets();
        if (intent.serverIds().size() != 1 || !targets.contains(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(
            intent.serverIds().getFirst(), "deployment-release", layout.applicationRoot(app))))
            throw new IllegalArgumentException("Workload release resource differs");
        result.put("application", app);
        result.put("ownershipManifestSha256", publishing ? publish.group(5) : health.group(2));
        if (publishing) {
            String candidate = publish.group(3);
            if (!candidate.startsWith(app + "-") || !targets.contains(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(
                intent.serverIds().getFirst(), "deployment-candidate", layout.candidateRoot(candidate))))
                throw new IllegalArgumentException("Publication candidate resource differs");
            result.put("candidate", candidate);
            result.put("releaseSha256", publish.group(4));
            result.put("identityPolicy", publish.group(6));
            result.put("derivedReleaseRoot", layout.applicationRoot(app));
            result.put("derivedCandidateRoot", layout.candidateRoot(candidate));
            result.put("publicationFrame", publicationArguments.orElseThrow());
            result.put("frameValidation", "COMPLETE_CANONICAL_LITERAL_ARGUMENTS_PAYLOAD_AND_DECLARED_PATHS_ONLY");
        } else result.put("frameValidation", "EXACT_APPLICATION_HEALTH_FRAME");
        return java.util.Optional.of(Collections.unmodifiableMap(result));
    }

    /** Describes the exact public snapshot frame and its derived paths, not live state or permission. */
    private static java.util.Optional<Map<String, Object>> publicationSnapshot(String script, byte[] input, ActionIntent intent,
                                                                               gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        var match = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("'" + layout.helperPath() + "' ")
            + "'(snapshot-deployment|snapshot-container)' '([a-z0-9][a-z0-9-]{0,62})' '([a-f0-9]{64})' '([a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})'\\z").matcher(script);
        if (input.length != 0 || !match.matches()) return java.util.Optional.empty();
        String release = layout.applicationRoot(match.group(2));
        String snapshot = layout.snapshotRoot(match.group(2), java.util.UUID.fromString(match.group(4)));
        if (intent.serverIds().size() != 1 || !intent.semantics().orElseThrow().targets().containsAll(Set.of(
            new gold.debug.windowstolinux.appapi.task.action.ActionTarget(intent.serverIds().getFirst(), "deployment-release", release),
            new gold.debug.windowstolinux.appapi.task.action.ActionTarget(intent.serverIds().getFirst(), "deployment-snapshot", snapshot))))
            throw new IllegalArgumentException("Snapshot derived resource bindings differ");
        return java.util.Optional.of(Map.of("verb", match.group(1), "application", match.group(2),
            "ownershipManifestSha256", match.group(3), "task", match.group(4), "derivedReleaseRoot", release,
            "possibleSnapshotRoot", snapshot, "literalValidation", "EXACT_FRAME_EMPTY_STDIN_AND_DECLARED_DERIVED_PATHS_ONLY",
            "meaning", "Public snapshot-deployment/container accept application, ownership manifest digest and optional task; this exact frame includes the original task. Release path is derived, not a missing argument. The manifest digest identifies ownership, not snapshot content or business/source/model revision. The pinned helper validates managed roots and matching current ownership or absence: absent previous deployment returns PREVIOUS=0 without a snapshot; otherwise captures protected prior runtime state. These are code semantics, not proof of current presence, safety or permission."));
    }

    /** Binds the actual configuration frame without disclosing its data or asserting live ownership. */
    private static java.util.Optional<Map<String, Object>> stagedConfiguration(String script, byte[] input, ActionIntent intent,
                                                                               gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        var match = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("'" + layout.helperPath() + "' 'stage-config' ")
            + "'([a-z0-9][a-z0-9-]{0,62})' '([a-f0-9]{64})' '(systemd|container)' '([a-f0-9]{64})' '(0|[1-9][0-9]{0,4})'\\z").matcher(script);
        if (!match.matches()) return java.util.Optional.empty();
        int bytes = Integer.parseInt(match.group(5));
        String digest;
        try {
            digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        String destination = layout.configurationRoot(match.group(1)) + "/" + match.group(2) + "/" + match.group(3) + ".env";
        if (bytes > 65536 || input.length != bytes || !digest.equals(match.group(4))
            || intent.serverIds().size() != 1 || !intent.semantics().orElseThrow().targets().contains(
            new gold.debug.windowstolinux.appapi.task.action.ActionTarget(intent.serverIds().getFirst(), "deployment-configuration", destination)))
            throw new IllegalArgumentException("Configuration frame, payload or resource binding differs");
        return java.util.Optional.of(Map.of("application", match.group(1), "configurationIdentity", match.group(2), "format", match.group(3),
            "payloadSha256", digest, "payloadBytes", bytes, "destination", destination,
            "literalValidation", "EXACT_FRAME_PAYLOAD_BYTES_SHA256_AND_DECLARED_DESTINATION_ONLY",
            "meaning", "Configuration identity names the reviewed configuration set, not its rendered payload or deployment/source/model revision. Empty normal configuration is valid; secret staging forbids empty data. This protocol binds application/configuration, not a task argument; active workflow evidence separately identifies the task. Data stays opaque; no live ownership, content-safety verdict or command authority is implied."));
    }

    /** Labels exact literal arguments without asserting resolver results, installation trust or authority. */
    private static java.util.Optional<Map<String, Object>> preparation(String script, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        var match = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("'" + layout.helperPath() + "' prepare-toolchains ")
            + "'prepare' '([A-Z]+)' '([0-9]{1,3}(?:\\.[0-9]{1,3})?)' '([^'\\r\\n]+)' '([1-9][0-9]{1,3})' '(-|/[^'\\r\\n]*)'\\z").matcher(script);
        if (!match.matches()) return java.util.Optional.empty();
        gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType ecosystem;
        try {
            ecosystem = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.valueOf(match.group(1));
        } catch (IllegalArgumentException invalid) {
            return java.util.Optional.empty();
        }
        String branch = match.group(2), exact = match.group(3);
        int seconds = Integer.parseInt(match.group(4));
        if (seconds < 30 || seconds > 7200 || match.group(5).length() > 2048
            || !(exact.equals("-") || gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion.parse(ecosystem, exact)
            .filter(version -> version.text().equals(exact) && !version.preview() && version.branch().equals(branch)).isPresent()))
            return java.util.Optional.empty();
        return java.util.Optional.of(Map.of("subcommand", "prepare", "ecosystem", ecosystem.name(), "catalogBranch", branch,
            "requestedExact", exact, "preparationTimeoutSeconds", seconds, "javaHome", match.group(5),
            "literalArgumentOrder", java.util.List.of("prepare", "ecosystem", "catalogBranch", "requestedExact", "preparationTimeoutSeconds", "javaHome"),
            "meaning", "Exact literal argument decoding only; branch and timeout are separate arguments. Official version/source/digest are resolved inside the pinned helper. Not live installation evidence, a safety verdict or a command grant."));
    }

    /** Decodes the exact toolchain binding carried by a fixed command. / 解码固定命令携带的精确工具链绑定。
     * @param script complete original reviewed executable script / 完整原已审阅可执行脚本
     * @return decoded original toolchain binding evidence / 已解码原工具链绑定依据
     * @throws CharacterCodingException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    private static Map<String, Object> binding(String script, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) throws CharacterCodingException {
        String prefix = "'" + layout.helperPath() + "' prepare-toolchains 'bind' '";
        if (!script.startsWith(prefix) || !script.endsWith("'"))
            throw new IllegalArgumentException("Toolchain binding command must use the exact literal protocol");
        String encoded = script.substring(prefix.length(), script.length() - 1);
        return decodeBinding(encoded, layout);
    }

    /** Extracts the reviewed build binding from its exact command frame. / 从精确命令帧提取已审阅构建绑定。
     * @param script complete original reviewed executable script / 完整原已审阅可执行脚本
     * @return decoded original build binding evidence / 已解码原构建绑定依据
     * @throws CharacterCodingException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    private static java.util.Optional<Map<String, Object>> buildBinding(String script, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) throws CharacterCodingException {
        var pattern = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("'" + layout.helperPath() + "' build-run ")
            + "'[A-Za-z0-9._-]+' '[A-Za-z0-9._-]+' [0-9]+ [0-9]+ [0-9]+ [0-9]+ '(?:ordinary|docker|podman)' '[0-9a-f]{64}' '([A-Za-z0-9+/=]+|-)'$");
        var match = pattern.matcher(script);
        if (!match.find()) throw new IllegalArgumentException("Build command must use the exact bound input protocol");
        return match.group(1).equals("-") ? java.util.Optional.empty() : java.util.Optional.of(decodeBinding(match.group(1), layout));
    }

    /** Preserves canonical encoded binding and parsed evidence together. / 同时保留规范编码绑定及已解析依据。
     * @param encoded original encoded bound to this operation / 当前操作绑定的原encoded值
     * @return canonical binding evidence / 规范绑定依据
     * @throws CharacterCodingException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    private static Map<String, Object> decodeBinding(String encoded, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) throws CharacterCodingException {
        if (!encoded.matches("[A-Za-z0-9+/=]{8,87384}"))
            throw new IllegalArgumentException("Invalid toolchain binding encoding");
        byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
        try {
            if (bytes.length > 65536) throw new IllegalArgumentException("Toolchain binding exceeds budget");
            if (!java.util.Base64.getEncoder().encodeToString(bytes).equals(encoded))
                throw new IllegalArgumentException("Toolchain binding Base64 is not canonical");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var binding = DeploymentToolchainBindingCodec.decode(text);
            if (!DeploymentToolchainBindingCodec.encode(binding).equals(text))
                throw new IllegalArgumentException("Toolchain binding is not canonical");
            return Map.of("encoding", "BASE64", "encodedCharacters", encoded.length(), "decodedBytes", bytes.length,
                "catalogRevision", binding.catalogRevision(), "bindingSha256", DeploymentToolchainBindingCodec.identity(binding),
                "bindingDigestMeaning", "MANAGED sha256 is the downloaded archive SHA-256 returned only after checking the official expected digest. For SHA256 sources it equals the official checksum (Rust resolves archive URL plus .sha256); SHA512 sources retain a separate official digest in the protected completion marker. The directory name is a distinct installation cache identity from ecosystem/version/resolver digest and fixed recipe, not the archive or a tree checksum. SYSTEM sha256 identifies the pinned system-file set. bind checks protected markers and exact versions and rehashes SYSTEM files before sealing; missing/drifted installations fail. These are input/code semantics, not observed installation, safety or authority.",
                "literalValidation", Map.of("canonicalBase64", true, "strictUtf8", true, "canonicalTypedBinding", true,
                    "scope", "LITERAL_ENCODING_ONLY_NOT_LIVE_INSTALLATION_OR_AUTHORITY"),
                "managedToolchainsRoot", layout.toolchains(),
                "toolchains", binding.selections().stream().map(selection -> Map.of(
                    "ecosystem", selection.requirement().ecosystem().name(), "version", selection.version().text(),
                    "directory", selection.directory(), "origin", selection.origin().name(),
                    "lexicalRelationToManagedRoot", selection.directory().equals(layout.toolchains()) ? "SAME"
                        : selection.directory().startsWith(layout.toolchains() + "/") ? "DESCENDANT" : "OUTSIDE",
                    "source", selection.source(), "sha256", selection.sha256())).toList());
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Describes only exact current product probe bytes, never observed executable trust or permission. */
    private static java.util.Optional<Map<String, Object>> platformProbe(String operation, CommandRequest request) {
        String variant = null;
        if (operation.equals("deployment.candidate.host-probe.v1")
            && request.script().equals(gold.debug.windowstolinux.mod.deployment.linux.probe.DeploymentPlatformCapabilityProbe.render()))
            variant = "HOST";
        if (operation.equals("deployment.candidate.container-probe.v1"))
            for (var engine : gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.ContainerEngineType.values())
                if (request.script().equals(gold.debug.windowstolinux.mod.deployment.linux.probe.DeploymentPlatformCapabilityProbe.renderForContainer(engine)))
                    variant = engine.name();
        if (variant == null) return java.util.Optional.empty();
        byte[] input = request.stdin();
        try {
            if (input.length != 0) return java.util.Optional.empty();
        } finally {
            Arrays.fill(input, (byte) 0);
        }
        byte[] script = request.script().getBytes(StandardCharsets.UTF_8);
        try {
            return java.util.Optional.of(Map.of("variant", variant, "comparison", "EXACT_PRODUCT_REQUEST_SCRIPT_AND_EMPTY_STDIN",
                "scriptSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(script)),
                "scriptBytes", script.length, "stdinBytes", 0,
                "environment", Map.of("launcher", "/usr/bin/env -i", "workingDirectory", "/", "shellArguments", java.util.List.of("--noprofile", "--norc", "-s"), "homeAndXdgPaths", "/dev/null"),
                "optionalCommandAdmission", "probe_command checks absolute executable, root ownership and non-writable original/resolved parent paths before invocation",
                "python", Map.of("discoveryNamePattern", "^python[0-9]+[.][0-9]+$", "venvArguments", java.util.List.of("-I", "-S", "-B", "-m", "venv", "--help"), "pipArguments", java.util.List.of("-I", "-S", "-B", "-")),
                "composerArguments", java.util.List.of("--no-plugins", "--no-scripts", "--no-cache", "--no-interaction", "--version"),
                "meaning", "Exact request-byte comparison and literal guard/argument descriptions only; not live program trust, no-effects proof, ownership or authority. Review every reachable effect; container variants can initialize storage."));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
