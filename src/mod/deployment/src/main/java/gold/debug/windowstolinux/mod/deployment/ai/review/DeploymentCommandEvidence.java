package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
        if (operation.equals("deployment.toolchain.bind.v1"))
            facts.put("decodedToolchainBinding", binding(invocation, layout));
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
        if (intent.semantics().orElseThrow().operation().equals("deployment.model.command.status.v1"))
            result.put("modelCommandReceiptStatus", gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner
                .receiptStatusEvidence(context, task, intent, request, layout));
        return java.util.Collections.unmodifiableMap(result);
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
}
