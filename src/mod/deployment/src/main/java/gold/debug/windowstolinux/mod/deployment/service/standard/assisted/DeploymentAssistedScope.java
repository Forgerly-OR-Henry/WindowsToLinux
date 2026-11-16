package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode;
import gold.debug.windowstolinux.appapi.mod.model.ModIdentity;
import gold.debug.windowstolinux.appapi.mod.service.ModServices;
import gold.debug.windowstolinux.appapi.secret.SecretService;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.log.LogService;
import gold.debug.windowstolinux.appapi.task.process.ProcessService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchiveReview;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement;
import gold.debug.windowstolinux.mod.deployment.service.deploy.support.runtime.DeploymentRuntimeCapabilityMatcher;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies;

/** Freezes one original assisted task and its language-call budget without replacing platform authority. / 冻结一项原辅助任务及语言调用预算，不替换平台权限。 */
public final class DeploymentAssistedScope implements ModContext, gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence {
    /** Actual owner capabilities. / 实际所属能力。 */
    private final ModContext original;

    /** Authentic original task. / 真实原任务。 */
    private final TaskContext task;

    /** Frozen complete model routing revision. / 冻结完整模型路由修订。 */
    private final String revision;

    /** Explicit independent-review policy. / 明确独立审核策略。 */
    private final String approvalMode;

    /** Original immutable rememberAuthorization repetition. / 原不可变授权重复规则。 */
    private final boolean rememberAuthorization;

    private volatile gold.debug.windowstolinux.appapi.task.action.WorkflowGrant workflow;

    private volatile Map<String, Object> sourceSelection;

    private final LinkedHashMap<String, Map<String, Object>> preparedToolchains = new LinkedHashMap<>();

    private record CapturedArchive(DeploymentSourceMaterial material, DeploymentSourceArchive archive,
                                   Map<String, Object> observation) { }

    private final LinkedHashMap<String, CapturedArchive> sourceArchives = new LinkedHashMap<>();

    /** Validated application-to-source associations, independent of source display names. / 经校验的应用与来源对应关系，独立于来源显示名称。 */
    private final LinkedHashMap<String, Map<String, Object>> reviewedApplicationSources = new LinkedHashMap<>();

    private final LinkedHashMap<String, Map<String, Object>> hostPrerequisites = new LinkedHashMap<>();

    private record AuthenticatedTarget(RemoteTarget expected, String fingerprint) { }

    private final AtomicReference<AuthenticatedTarget> authenticatedTarget = new AtomicReference<>();

    private record ToolchainPlan(String identity, String server,
                                 gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan plan,
                                 List<gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement> requirements,
                                 Map<String, Object> evidence) {
    }

    private volatile ToolchainPlan toolchainPlan;

    private record PreparedBinding(ToolchainPlan plan,
                                   gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains tools,
                                   String canonical) {
    }

    private PreparedBinding preparedBinding;

    private volatile Map<String, Object> prePreparationHost;

    public void bindPreparation(
        gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan plan,
        TaskContext supplied) throws ApiException {
        sameTask(supplied);
        var rendered = new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardPlanner()
            .render(plan, supplied, gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied));
        if (rendered.outcome() != gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED)
            throw new IllegalArgumentException("Runtime dependency plan cannot be rendered");
        toolchainPreparationPlanned(plan, rendered.value().orElseThrow().reviewedPlanSha256(), supplied);
    }

    /** Keeps standalone diagnostics and bounded display history; only a plan-bound observer supplies binding receipts. */
    public gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentPreparationObserver preparationObserver() {
        return new gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentPreparationObserver() {
            @Override
            public void diagnosticRequested(gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentToolchainDiagnostics.Request request,
                                            TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                if (!request.taskId().equals(supplied.id().toString()))
                    throw new IllegalArgumentException("Diagnostic task differs");
                try (var evidence = original.tasks().beginCheckpoint(supplied,
                    gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentToolchainDiagnostics.checkpointKey(request.operationId()),
                    new String(request.bytes(), java.nio.charset.StandardCharsets.UTF_8))) {
                    if (!evidence.snapshot().payload().equals(new String(request.bytes(), java.nio.charset.StandardCharsets.UTF_8)))
                        throw new IllegalArgumentException("Diagnostic request was not durably retained");
                }
            }

            @Override
            public void toolsPrepared(ServerId server,
                                      gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains tools,
                                      TaskContext supplied) throws ApiException {
                toolchainsPrepared(server, tools, supplied);
            }

            @Override
            public void hostObserved(String identity,
                                     gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts host,
                                     gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains tools,
                                     TaskContext supplied) throws ApiException {
                var current = toolchainPlan;
                if (current == null) throw new IllegalArgumentException("No original reviewed preparation plan");
                hostPrerequisitesObserved(current.plan(), identity, host, tools, supplied);
            }
        };
    }

    /** Captures the actual reviewed plan before its channel can prepare anything. */
    public synchronized gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentPreparationObserver preparationObserver(
        gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan plan,
        TaskContext supplied) throws ApiException {
        sameTask(supplied);
        var captured = currentPreparation(plan.request().server(), supplied);
        if (captured.plan() != plan) throw new IllegalArgumentException("Observer belongs to another reviewed plan");
        var permission = workflow;
        var observations = preparationObserver();
        return new gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentPreparationObserver() {
            private void requireCurrent(TaskContext actual) throws ApiException {
                sameTask(actual);
                var current = toolchainPlan;
                if (workflow != permission || current == null || current.plan() != captured.plan() || !current.identity().equals(captured.identity()))
                    throw new IllegalArgumentException("Original preparation observer plan changed");
                currentPreparation(new ServerId(captured.server()), actual);
            }

            @Override
            public void diagnosticRequested(gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentToolchainDiagnostics.Request request,
                                            TaskContext actual) throws ApiException {
                synchronized (DeploymentAssistedScope.this) {
                    requireCurrent(actual);
                    if (!request.planRevision().equals(captured.identity()) || !request.serverId().equals(captured.server())
                        || !request.layout().equals(captured.plan().request().layout()))
                        throw new IllegalArgumentException("Diagnostic differs from captured preparation plan");
                    observations.diagnosticRequested(request, actual);
                }
            }

            @Override
            public void toolsPrepared(ServerId server,
                                      gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains tools,
                                      TaskContext actual) throws ApiException {
                synchronized (DeploymentAssistedScope.this) {
                    requireCurrent(actual);
                    toolchainsPrepared(server, tools, actual);
                    preparedBinding = new PreparedBinding(toolchainPlan, tools,
                        gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.encode(tools));
                }
            }

            @Override
            public void hostObservedBeforePreparation(String identity, DeploymentLinuxCapabilityFacts host,
                                                      TaskContext actual) throws ApiException {
                synchronized (DeploymentAssistedScope.this) {
                    requireCurrent(actual);
                    if (!captured.identity().equals(identity))
                        throw new IllegalArgumentException("Original host probe plan changed");
                    prePreparationHost = Map.of("reviewedPlanSha256", identity, "server", captured.server(),
                        "task", actual.id().toString(), "application", captured.plan().request().facts().applicationId(),
                        "platform", Map.of("distro", host.distro().name(), "version", host.version(),
                            "architecture", host.architecture(), "packageManager", host.packageManager(),
                            "packageArchitecture", host.packageArchitecture()),
                        "observedTools", Map.of("python3Available", host.python3Available(),
                            "pythonVenvBranches", host.pythonVersions(), "ecosystemVersions", host.ecosystemToolVersions(),
                            "serviceVersions", host.serviceRuntimeVersions(), "systemdAvailable", host.systemdAvailable()),
                        "meaning", "Successful complete fixed host probe before this component's catalog preparation. These are prior observations, not a package inventory, exact reusable tool identity, current build prerequisites or authorization. The selected helper checks exact reuse and may enter the independently reviewed fixed preparation path.");
                }
            }

            @Override
            public void hostObserved(String identity,
                                     gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts host,
                                     gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains tools,
                                     TaskContext actual) throws ApiException {
                synchronized (DeploymentAssistedScope.this) {
                    requireCurrent(actual);
                    if (!captured.identity().equals(identity))
                        throw new IllegalArgumentException("Host observation plan changed");
                    observations.hostObserved(identity, host, tools, actual);
                }
            }
        };
    }

    public synchronized void toolchainPreparationPlanned(
        gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan plan,
        String expected, TaskContext supplied) throws ApiException {
        sameTask(supplied);
        verify();
        var previous = toolchainPlan;
        if (previous == null || previous.plan() != plan || !previous.identity().equals(expected)) {
            preparedBinding = null;
            prePreparationHost = null;
        }
        toolchainPlan = null;
        var request = plan.request();
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied);
        var rendered = new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardPlanner()
            .render(plan, supplied, layout);
        if (rendered.outcome() != gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED
            || !rendered.value().orElseThrow().reviewedPlanSha256().equals(expected))
            throw new IllegalArgumentException("Runtime dependency plan binding changed");
        var selected = sourceSelection;
        if (selected == null || !request.server().id().equals(selected.get("server"))
            || !request.material().snapshot().id().toString().equals(selected.get("snapshotId"))
            || !request.archive().sourceManifestSha256().equals(selected.get("sourceRevision"))
            || archiveObservations().stream().noneMatch(archive ->
            request.archive().sha256().equals(archive.get("archiveSha256"))
                && request.archive().relativeRoot().equals(archive.get("relativeRoot"))
                && request.archive().originSnapshotId().toString().equals(archive.get("originSnapshotId"))
                && request.archive().snapshot().id().toString().equals(archive.get("archiveSnapshotId"))
                && request.archive().path().toString().equals(archive.get("localArchivePath"))
                && Long.valueOf(request.archive().byteCount()).equals(archive.get("archiveBytes"))))
            throw new IllegalArgumentException("Runtime dependency plan differs from captured source");
        validateWorkflow(gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.reviewFacts(
            workflow, supplied, request.server()));
        var archiveAssociation = new LinkedHashMap<String, Object>(Map.<String, Object>of("application", request.facts().applicationId(),
            "server", request.server().id(), "task", supplied.id().toString(),
            "originSnapshotId", request.archive().originSnapshotId().toString(),
            "sourceManifestSha256", request.archive().sourceManifestSha256(),
            "archiveSha256", request.archive().sha256(), "relativeRoot", request.archive().relativeRoot(),
            "remoteLayoutSha256", request.layout().digest()));
        archiveAssociation.put("archiveSnapshotId", request.archive().snapshot().id().toString());
        archiveAssociation.put("localArchivePath", request.archive().path().toString());
        archiveAssociation.put("archiveBytes", request.archive().byteCount());
        var association = Map.copyOf(archiveAssociation);
        var prior = reviewedApplicationSources.get(request.facts().applicationId());
        if (prior != null && !prior.equals(association))
            throw new IllegalArgumentException("Reviewed application source association changed");
        if (prior == null && reviewedApplicationSources.size() == 32)
            throw new IllegalArgumentException("Reviewed application source bound exceeded");
        var requirements = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentProjectToolchainRequirements
            .from(request.facts(), request.runtime());
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("reviewedPlanSha256", expected);
        evidence.put("server", request.server().id());
        evidence.put("application", request.facts().applicationId());
        evidence.put("sourceManifestSha256", request.archive().sourceManifestSha256());
        evidence.put("archiveSha256", request.archive().sha256());
        evidence.put("relativeRoot", request.archive().relativeRoot());
        evidence.put("runtimeKind", request.runtime().getClass().getSimpleName());
        evidence.put("requirements", requirements.stream()
            .map(requirement -> requirementFacts(requirement, request.facts().toolchainRequirements().contains(requirement))).toList());
        evidence.put("meaning", "Intended tool requirements derived by the same fixed product planner used for this source-bound reviewed runtime. Source declarations are separate and need not include product runtime dependencies. This is a planned dependency, not an installation observation or approval. Review original workflow authority and the exact current effects independently.");
        if (request.runtime() instanceof gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.StaticSite)
            evidence.put("runtimeDependencyReason", "This reviewed StaticSite uses the product managed Python 3.12 http.server to serve built assets; Node, when selected, builds those assets. Python is a fixed runtime requirement and need not be declared by package.json. The pinned helper static launch branch uses the prepared Python executable; this does not prove it is installed or authorize an effect.");
        toolchainPlan = new ToolchainPlan(expected, request.server().id(), plan, requirements, Map.copyOf(evidence));
        reviewedApplicationSources.put(request.facts().applicationId(), association);
    }

    public synchronized void hostPrerequisitesObserved(
        gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan plan,
        String expected,
        gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts host,
        gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains tools,
        TaskContext supplied) throws ApiException {
        sameTask(supplied);
        verify();
        var request = plan.request();
        var current = toolchainPlan;
        var association = reviewedApplicationSources.get(request.facts().applicationId());
        var rendered = new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardPlanner()
            .render(plan, supplied, gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied));
        if (current == null || !current.identity().equals(expected) || !current.server().equals(request.server().id())
            || association == null || !association.get("archiveSha256").equals(request.archive().sha256())
            || rendered.outcome() != gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED
            || !rendered.value().orElseThrow().reviewedPlanSha256().equals(expected))
            throw new IllegalArgumentException("Host prerequisite observation differs from reviewed plan");
        validateWorkflow(gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.reviewFacts(
            workflow, supplied, request.server()));
        var result = DeploymentRuntimeCapabilityMatcher.evaluate(host, request.facts(), request.runtime(), tools);
        hostPrerequisites.remove(request.facts().applicationId());
        if (!result.missingHostPrerequisite()
            || gold.debug.windowstolinux.mod.deployment.service.deploy.support.DeploymentHostMatcher.evaluatePlatform(host).support()
            != gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentHostCapabilityStatus.READY_FOR_RUNTIME_VALIDATION)
            return;
        var observation = new LinkedHashMap<String, Object>(association);
        observation.put("reviewedPlanSha256", expected);
        observation.put("requiredCapability", result.detail().orElseThrow());
        var missingNativeTools = DeploymentRuntimeCapabilityMatcher.missingNativeBuildTools(host, request.facts(), request.runtime());
        observation.put("nativeBuildPrerequisitesMissing", !missingNativeTools.isEmpty());
        observation.put("missingNativeBuildTools", missingNativeTools.stream().map(Enum::name).sorted().toList());
        if (!missingNativeTools.isEmpty())
            observation.put("nativeBuildProfile", "apt".equals(host.packageManager())
                ? DeploymentToolchainDependencies.nativeAptProfile(missingNativeTools) : DeploymentToolchainDependencies.PROFILE);
        observation.put("observedAt", java.time.Instant.now().toString());
        observation.put("distro", host.distro().name());
        observation.put("version", host.version());
        observation.put("architecture", host.architecture());
        observation.put("packageManager", host.packageManager());
        observation.put("ecosystemToolVersions", host.ecosystemToolVersions());
        observation.put("toolchainBindingSha256", gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.identity(tools));
        hostPrerequisites.put(request.facts().applicationId(), Map.copyOf(observation));
    }

    /** Records the archiver's original-source association, never source trust or build permission. */
    public synchronized void archiveCaptured(DeploymentSourceMaterial material,
                                             DeploymentSourceArchive archive,
                                             List<gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement> requirements,
                                             TaskContext supplied) throws ApiException {
        sameTask(supplied);
        verify();
        var selected = sourceSelection;
        if (selected == null || !archive.originSnapshotId().toString().equals(selected.get("snapshotId"))
            || !archive.sourceManifestSha256().equals(selected.get("sourceRevision"))
            || !material.snapshot().id().equals(archive.originSnapshotId())
            || !material.snapshot().sha256().equals(archive.sourceManifestSha256()))
            throw new IllegalArgumentException("Archive differs from the original captured source");
        validateWorkflow(gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.reviewFacts(
            workflow, task, new ServerId(selected.get("server").toString())));
        String prefix = archive.relativeRoot().isEmpty() ? "" : archive.relativeRoot() + "/";
        var names = material.includedFiles().stream().filter(name -> name.startsWith(prefix))
            .map(name -> name.substring(prefix.length())).sorted().toList();
        var displayed = new java.util.ArrayList<String>();
        int chars = 0;
        for (String name : names) {
            if (displayed.size() == 64 || chars + name.length() > 8192) break;
            displayed.add(name);
            chars += name.length();
        }
        var observation = new LinkedHashMap<String, Object>(Map.of("originSnapshotId", archive.originSnapshotId().toString(),
            "sourceManifestSha256", archive.sourceManifestSha256(), "archiveSha256", archive.sha256(),
            "archiveSnapshotId", archive.snapshot().id().toString(), "localArchivePath", archive.path().toString(),
            "archiveBytes", archive.byteCount(), "relativeRoot", archive.relativeRoot(), "fileCount", names.size(),
            "fileNames", List.copyOf(displayed), "fileNamesTruncated", displayed.size() != names.size()));
        observation.put("sourceToolchainRequirements", List.copyOf(requirements).stream().map(requirement -> Map.of(
            "ecosystem", requirement.ecosystem().name(), "declaration", requirement.declaration(), "source", requirement.source(),
            "purpose", requirement.purpose().name(), "constraint", requirement.constraint().name(),
            "version", requirement.version().map(value -> value.text()).orElse(""))).toList());
        String key = archiveKey(archive.relativeRoot(), archive.sha256());
        var previous = sourceArchives.get(key);
        if (previous != null && (!previous.material().equals(material) || !previous.archive().equals(archive)
            || !previous.observation().equals(observation)))
            throw new IllegalStateException("Original archive association cannot be replaced");
        if (previous == null && sourceArchives.size() == 32)
            sourceArchives.remove(sourceArchives.keySet().iterator().next());
        if (previous == null)
            sourceArchives.put(key, new CapturedArchive(material, archive, Map.copyOf(observation)));
    }

    private static String archiveKey(String relativeRoot, String archiveSha256) {
        return relativeRoot + "\n" + archiveSha256;
    }

    private List<Map<String, Object>> archiveObservations() {
        return sourceArchives.values().stream().map(CapturedArchive::observation).toList();
    }

    public synchronized void toolchainsPrepared(ServerId server,
                                                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains tools,
                                                TaskContext supplied) throws ApiException {
        sameTask(supplied);
        verify();
        var planned = currentPreparation(server, supplied);
        requireSelections(planned, tools);
        if (tools.selections().isEmpty()) return;
        validateWorkflow(gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.reviewFacts(workflow, task, server));
        String binding = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.identity(tools);
        preparedToolchains.remove(binding);
        if (preparedToolchains.size() == 32) preparedToolchains.remove(preparedToolchains.keySet().iterator().next());
        preparedToolchains.put(binding, Map.of("bindingSha256", binding, "selectionCount", tools.selections().size(),
            "recordedAt", java.time.Instant.now().toString(), "server", server.id(), "task", task.id().toString()));
    }

    private ToolchainPlan currentPreparation(ServerId server, TaskContext supplied) throws ApiException {
        var planned = toolchainPlan;
        if (planned == null || !planned.server().equals(server.id()))
            throw new IllegalArgumentException("Current reviewed preparation plan required");
        toolchainPreparationPlanned(planned.plan(), planned.identity(), supplied);
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied);
        if (!planned.plan().request().layout().equals(layout))
            throw new IllegalArgumentException("Preparation layout differs from original task");
        return toolchainPlan;
    }

    private static Map<String, String> requirementFacts(ToolchainRequirement requirement, boolean declared) {
        return Map.of("ecosystem", requirement.ecosystem().name(),
            "version", requirement.version().map(value -> value.text()).orElse(""),
            "constraint", requirement.constraint().name(), "source", requirement.source(),
            "derivation", declared ? "SOURCE_DECLARATION" : "REVIEWED_RUNTIME");
    }

    private static void requireKotlinJavaPlan(ActionIntent intent, ToolchainPlan planned, Map<?, ?> javaRequirement) {
        if (javaRequirement == null || planned == null || !planned.identity().equals(intent.revision())
            || !intent.serverIds().equals(List.of(planned.server()))
            || planned.requirements().stream().noneMatch(requirement -> "JAVA".equals(requirement.ecosystem().name())
                && requirement.constraint().name().equals(javaRequirement.get("constraint"))
                && requirement.version().filter(version -> version.text().equals(javaRequirement.get("version"))).isPresent()))
            throw new IllegalArgumentException("Kotlin Java constraint differs from the current reviewed toolchain plan");
    }

    private static boolean plannedPreparationMatches(ToolchainPlan planned, Object ecosystem, Object requestedBranch, Object requestedExact) {
        return planned.requirements().stream().anyMatch(requirement -> requirement.ecosystem().name().equals(ecosystem)
            && gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog.defaults()
            .candidates(requirement).stream().anyMatch(branch -> {
                String exact = requirement.constraint() == ToolchainRequirement.ConstraintType.EXACT
                    && requirement.version().isPresent() && !requirement.version().orElseThrow().preview()
                    && requirement.version().orElseThrow().branch().equals(branch.version())
                    ? requirement.version().orElseThrow().text() : "-";
                return branch.version().equals(requestedBranch) && exact.equals(requestedExact);
            }));
    }

    private static List<ToolchainRequirement> requirementsForStage(ToolchainPlan planned, boolean systemCompiler) {
        var catalog = DeploymentToolchainCatalog.defaults();
        return planned.requirements().stream().filter(requirement -> {
            var branches = catalog.candidates(requirement);
            if (branches.isEmpty()) throw new IllegalArgumentException("Unsupported preparation requirement");
            return (branches.getFirst().installation() == DeploymentToolchainCatalog.InstallationType.SYSTEM_COMPILER)
                == systemCompiler;
        }).toList();
    }

    private static void requireSelections(ToolchainPlan planned,
                                          gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains tools) {
        var catalog = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog.defaults();
        if (!catalog.revision().equals(tools.catalogRevision()))
            throw new IllegalArgumentException("Prepared catalog changed");
        var expected = requirementsForStage(planned, false);
        if (!expected.equals(tools.selections().stream().map(value -> value.requirement()).toList()))
            throw new IllegalArgumentException("Prepared selections differ from current component requirements");
        var policy = new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainSelectionPolicy(catalog);
        for (var selection : tools.selections()) {
            var branch = catalog.candidates(selection.requirement()).stream()
                .filter(value -> value.version().equals(selection.version().branch())).findFirst().orElseThrow();
            policy.resolve(selection.requirement(), branch, List.of(selection.version()));
        }
    }

    private synchronized Map<String, ?> bindingPlanFacts(ActionIntent intent,
                                                         gold.debug.windowstolinux.appapi.linux.command.CommandRequest request, TaskContext supplied) throws ApiException {
        sameTask(supplied);
        if (!intent.type().equals("platform.ssh.execute") || intent.serverIds().size() != 1)
            throw new IllegalArgumentException("Original binding command required");
        var planned = currentPreparation(new ServerId(intent.serverIds().getFirst()), supplied);
        var layout = planned.plan().request().layout();
        if (!planned.identity().equals(intent.revision()) || !intent.semantics().orElseThrow().targets().equals(Set.of(
            new gold.debug.windowstolinux.appapi.task.action.ActionTarget(planned.server(), "deployment-build", layout.toolchains()))))
            throw new IllegalArgumentException("Binding differs from current preparation identity or target");
        String invocation = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract.verifiedInvocation(request.script(), layout);
        String prefix = "'" + layout.helperPath() + "' prepare-toolchains 'bind' '";
        if (!invocation.startsWith(prefix) || !invocation.endsWith("'") || request.stdin().length != 0)
            throw new IllegalArgumentException("Binding literal frame changed");
        String encoded = invocation.substring(prefix.length(), invocation.length() - 1);
        if (!encoded.matches("[A-Za-z0-9+/=]{8,87384}")) throw new IllegalArgumentException("Binding encoding changed");
        byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
        try {
            String canonical = java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            var tools = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.decode(canonical);
            if (bytes.length > 65536 || !java.util.Base64.getEncoder().encodeToString(bytes).equals(encoded)
                || !gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.encode(tools).equals(canonical))
                throw new IllegalArgumentException("Binding is not canonical");
            requireSelections(planned, tools);
            var observed = preparedBinding;
            if (observed == null || observed.plan().plan() != planned.plan() || !observed.plan().identity().equals(planned.identity())
                || !observed.canonical().equals(canonical) || !observed.tools().equals(tools))
                throw new IllegalArgumentException("Binding differs from actual current-plan preparation receipts");
            var declared = planned.plan().request().facts().toolchainRequirements();
            return Map.of("currentToolchainPlan", planned.evidence(), "currentToolchainBindingStage", Map.of(
                "application", planned.plan().request().facts().applicationId(), "reviewedPlanSha256", planned.identity(),
                "bindingSha256", DeploymentToolchainBindingCodec.identity(tools),
                "catalogPrepared", tools.selections().stream().map(selection -> Map.of(
                    "ecosystem", selection.requirement().ecosystem().name(), "version", selection.version().text(),
                    "directory", selection.directory(), "origin", selection.origin().name(), "sha256", selection.sha256())).toList(),
                "deferredHostCompilers", requirementsForStage(planned, true).stream()
                    .map(requirement -> requirementFacts(requirement, declared.contains(requirement))).toList(),
                "meaning", "Only catalog preparations represented by the exact current binding are sealed by this operation. SYSTEM_COMPILER requirements are evaluated by the fixed post-bind host probe and runtime matcher before candidate creation. The native prerequisite transaction, when applicable, requires separate independent review, exact host grant and a fresh successful probe. No full runtime or compiler readiness has been inferred from this binding."));
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Binding requires complete canonical UTF-8", invalid);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Records the actual post-picker frozen selection, not executable trust or a command grant. */
    public synchronized void sourceCaptured(ServerId server, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial material,
                                            TaskContext supplied) throws ApiException {
        sameTask(supplied);
        verify();
        var authorization = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.reviewFacts(workflow, task, server);
        validateWorkflow(authorization);
        var snapshot = material.snapshot();
        String hash = Objects.requireNonNull(snapshot.sha256());
        if (!hash.matches("[a-f0-9]{64}") || material.includedFiles().isEmpty())
            throw new IllegalArgumentException("Actual source snapshot is missing");
        Map<String, Object> selected = Map.of("server", server.id(), "snapshotId", Objects.requireNonNull(snapshot.id()).toString(),
            "sourceRevision", hash, "displayName", material.displayName(), "includedFiles", material.includedFiles().size(),
            "meaning", "Actual frozen source selected after workflow admission (folder picker may leave the initial draft URI empty). Snapshot identity only; not code trust, per-command permission or an installation safety verdict.");
        if (sourceSelection != null && !sourceSelection.equals(selected))
            throw new IllegalStateException("Original assisted source snapshot cannot be replaced");
        sourceSelection = selected;
    }

    @Override
    public synchronized void workflowOpened(gold.debug.windowstolinux.appapi.task.action.WorkflowGrant permission) {
        if (workflow != null) throw new IllegalStateException("Original assisted workflow is already bound");
        workflow = Objects.requireNonNull(permission);
    }

    @Override
    public Map<String, ?> facts(ActionIntent intent, gold.debug.windowstolinux.appapi.linux.command.CommandRequest request,
                                TaskContext supplied) throws ApiException {
        return facts(intent, request, supplied, true);
    }

    private Map<String, ?> facts(ActionIntent intent, gold.debug.windowstolinux.appapi.linux.command.CommandRequest request,
                                 TaskContext supplied, boolean includeBuildText) throws ApiException {
        String operation = intent.semantics().map(value -> value.operation()).orElse("");
        var observation = nativePublicationApplication(intent, request, supplied);
        if (observation.isPresent()) return publicationObservationFacts(intent, observation.orElseThrow(), supplied);
        var facts = new LinkedHashMap<String, Object>(effectFacts(intent, supplied));
        if (Set.of(DeploymentAssistedCandidateReview.QUERY, DeploymentAssistedCandidateReview.VERIFY).contains(operation)) {
            if (!facts.containsKey("workflowAuthorization"))
                throw new IllegalArgumentException("Candidate observation requires original workflow authority");
            var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied);
            var sources = Map.copyOf(reviewedApplicationSources);
            try {
                facts.put("candidateObservation", DeploymentAssistedCandidateReview.facts(intent, request, supplied, layout,
                    sources, Set.of()));
            } catch (DeploymentAssistedCandidateReview.AlternateCandidate required) {
                var record = new gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore(original.db());
                var attempts = new gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentPublishAttemptStore(original.db());
                try {
                    var execution = record.findForTask(supplied).orElseThrow();
                    var reviewed = new HashSet<UUID>();
                    reviewed.add(attempts.operation(execution));
                    attempts.intent(execution).ifPresent(value -> reviewed.add(value.nextOperation()));
                    facts.put("candidateObservation", DeploymentAssistedCandidateReview.facts(intent, request, supplied,
                        layout, sources, reviewed));
                } catch (java.sql.SQLException unavailable) {
                    throw new ApiException(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_CANDIDATE_REVIEW_EVIDENCE_UNAVAILABLE",
                        "Original candidate operation could not be read", Map.of(), Optional.empty()));
                }
            }
            var authenticated = authenticatedTarget.get();
            if (authenticated != null && intent.serverIds().equals(List.of(authenticated.expected().server().id())))
                facts.put("candidateSshAuthentication", Map.of("server", authenticated.expected().server().id(),
                    "task", supplied.id().toString(), "authenticatedHostFingerprint", authenticated.fingerprint(),
                    "sshAccount", authenticated.expected().username(),
                    "meaning", "The product connected using the original fixed task, endpoint and SSH account and observed this authenticated host key. The connection grants no privileges. The pinned helper verifies effective UID 0 at execution."));
            if (operation.equals(DeploymentAssistedCandidateReview.VERIFY))
                return DeploymentAssistedCandidateReview.verificationEvidence(facts);
        }
        if (intent.semantics().filter(value -> Set.of("deployment.helper.install.v1", "deployment.helper.install.files.v1",
                "deployment.helper.install.tracked.v1").contains(value.operation())).isPresent()) {
            if (!facts.containsKey("workflowAuthorization"))
                throw new IllegalArgumentException("Native helper package evidence requires its original workflow");
            try {
                facts.put("helperPackageImpact", gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence
                    .helperPackageImpact(intent, request, supplied.id(),
                        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied)));
            } catch (java.io.IOException invalid) {
                throw new IllegalArgumentException("Native helper package evidence cannot be decoded", invalid);
            }
        }
        if (intent.semantics().filter(value -> value.operation().equals("deployment.toolchain.bind.v1")).isPresent())
            facts.putAll(bindingPlanFacts(intent, request, supplied));
        if (intent.semantics().filter(value -> value.operation().equals(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.OPERATION)).isPresent())
            facts.put("currentNativeBuildPrerequisites", nativeDependencyFacts(intent, request, supplied));
        if (intent.semantics().filter(value -> value.operation().startsWith(
            gold.debug.windowstolinux.mod.deployment.linux.cleanup.DeploymentCandidateCompensationProgram.OPERATION_PREFIX)).isPresent())
            facts.put("originalCandidateCompensation",
                gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup.DeploymentCandidateCompensation
                    .reviewFacts(original, supplied, intent, request,
                        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied)));
        if (intent.semantics().filter(value -> value.operation().equals("deployment.helper.install.status.v1")).isPresent())
            facts.put("originalHelperInstallationStatus", DeploymentAssistedHelperStatusReview.facts(original.tasks(), supplied, intent, request,
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied)));
        if (intent.semantics().filter(value -> Set.of("deployment.publication.config.v1", "deployment.publication.secret.v1")
            .contains(value.operation())).isPresent())
            facts.put("configurationStagingImplementation", gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle
                .configurationStagingImplementationFacts(gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied)));
        if (intent.semantics().filter(value -> value.operation().equals("deployment.build.run.v1")).isPresent()) {
            var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied);
            String invocation = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract.verifiedInvocation(request.script(), layout);
            if (invocation.startsWith("exec /usr/bin/python3 -I -c ")) {
                try {
                    var association = buildArchiveFacts(archiveObservations(), Map.copyOf(reviewedApplicationSources),
                        intent, request, layout);
                    facts.put("currentBuildArchive", association);
                    if (mirroredBuildInput(request)) {
                        var archive = (Map<?, ?>) association.get("archive");
                        var captured = Objects.requireNonNull(sourceArchives.get(archiveKey(
                            archive.get("relativeRoot").toString(), archive.get("archiveSha256").toString())));
                        if (includeBuildText) {
                            facts.put("currentBuildArchiveSource", DeploymentSourceArchiveReview.capture(
                                captured.material(), captured.archive(), supplied));
                        } else {
                            DeploymentSourceArchiveReview.verifyBinding(captured.material(), captured.archive(), supplied);
                        }
                    }
                } catch (java.io.IOException invalid) {
                    throw new IllegalArgumentException("Build archive evidence cannot be decoded", invalid);
                }
            }
        }
        if (intent.semantics().filter(value -> value.operation().equals("deployment.toolchain.prepare.v1")).isPresent()) {
            var contract = DeploymentAssistedPreparationReview.facts(intent, request,
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied));
            facts.put("toolchainPreparationContract", contract);
            var planned = toolchainPlan;
            var arguments = (Map<?, ?>) contract.get("arguments");
            if ("KOTLIN".equals(arguments.get("ecosystem")))
                requireKotlinJavaPlan(intent, planned, (Map<?, ?>) arguments.get("javaRequirement"));
            if (planned != null && planned.identity().equals(intent.revision())
                && intent.serverIds().equals(List.of(planned.server()))) {
                if (!plannedPreparationMatches(planned, arguments.get("ecosystem"), arguments.get("catalogBranch"), arguments.get("requestedExact")))
                    throw new IllegalArgumentException("Preparation differs from reviewed runtime requirements");
                facts.put("currentToolchainPlan", planned.evidence());
                // The matching plan already binds the selected application's exact source archive.
                facts.remove("sourceArchiveObservations");
                facts.remove("toolchainPreparationObservations");
                facts.remove("hostPrerequisiteObservations");
                var currentSource = reviewedApplicationSources.get(planned.evidence().get("application"));
                if (currentSource != null)
                    facts.put("reviewedApplicationSources", Map.of("applications", List.of(currentSource)));
                var host = prePreparationHost;
                if (host != null && planned.identity().equals(host.get("reviewedPlanSha256")))
                    facts.put("currentPrePreparationHost", host);
            }
        }
        if (operation.equals("deployment.toolchain.prepare.tracked.v1")) {
            var tracked = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.decode(request.stdin());
            var parameters = tracked.parameters();
            if ("KOTLIN".equals(parameters.get("ecosystem"))) {
                var planned = toolchainPlan;
                requireKotlinJavaPlan(intent, planned, (Map<?, ?>) parameters.get("javaRequirement"));
                if (!plannedPreparationMatches(planned, parameters.get("ecosystem"), parameters.get("branch"), parameters.get("exact")))
                    throw new IllegalArgumentException("Tracked preparation differs from reviewed runtime requirements");
                facts.put("currentToolchainPlan", planned.evidence());
            }
        }
        if (intent.semantics().filter(value -> value.operation().equals("deployment.publication.publish.v1")).isPresent()) {
            try {
                var store = new gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore(original.db());
                var record = store.findForTask(supplied).orElseThrow();
                var proposal = store.publicationTemplate(record).orElseThrow();
                facts.put("originalPublication", publicationFacts(record.identity(), proposal.units(), intent, request, supplied));
            } catch (java.sql.SQLException unavailable) {
                throw new ApiException(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_PUBLICATION_REVIEW_EVIDENCE_UNAVAILABLE",
                    "Original publication template could not be read", Map.of(), Optional.empty()));
            }
        }
        return Map.copyOf(facts);
    }

    @Override
    public String reviewInstructions(ActionIntent intent, gold.debug.windowstolinux.appapi.linux.command.CommandRequest request,
                                     TaskContext supplied) throws ApiException {
        // Revalidate original authority before selecting any trusted current-action supplement.
        var facts = facts(intent, request, supplied, false);
        if (facts.containsKey("publicationObservationScope"))
            return """
                Match the exact guarded native observation's application, owner marker, release target and original workflow.
                publicationObservationScope carries the selected application's frozen association when captured and describes the scope of the upcoming read.
                Check the installed helper pin, literal limits and the native observation contract. An earlier publication or health receipt does not prove present state or supply the fresh host grant. Request a verdict only for this observation.
                """;
        String operation = intent.semantics().map(value -> value.operation()).orElse("");
        if (operation.equals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper.PREREQUISITE_OBSERVATION))
            return """
                Review the read-only package and runtime observation against the complete source, fixed limits and exact targets.
                This review precedes the observation. A future install requires its own independent review and exact host grant.
                """;
        if (operation.equals(DeploymentAssistedCandidateReview.VERIFY) && facts.containsKey("candidateObservation"))
            return DeploymentAssistedCandidateReview.verificationInstructions();
        String instructions = operation.equals("deployment.build.run.v1") && facts.containsKey("currentBuildArchive")
            ? standardBuildReviewInstructions() : gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources.text(
                DeploymentAssistedApproval.class, "fixed-effect-review.md");
        if (facts.get("helperPackageImpact") instanceof Map<?, ?> impact
            && "VERIFIED_EXISTING_APT_PREREQUISITES".equals(impact.get("packageMode")))
            instructions += "\n" + """
                Inspect the exact installer command, its protected live prerequisite recheck and its helper and receipt writes.
                Verify the absence of package changes against the complete current evidence. A probe result grants no write authority.
                """;
        if (Set.of("deployment.toolchain.prepare.v1", "deployment.toolchain.bind.v1",
            DeploymentToolchainDependencies.OPERATION).contains(operation))
            instructions = toolchainReviewInstructions(instructions, operation);
        if (operation.equals("deployment.toolchain.prepare.v1"))
            instructions += "\n" + DeploymentAssistedPreparationReview.instructions(intent, request,
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied));
        if (intent.semantics().filter(value -> value.operation().equals("deployment.build.run.v1")).isPresent()) {
            byte[] input = request.stdin();
            try {
                String executable = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(input)).toString();
                instructions += gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.java.MavenBuildWriter
                    .bootstrapReviewInstructions(executable).map(value -> "\n" + value).orElse("");
            } catch (java.nio.charset.CharacterCodingException invalid) {
                throw new IllegalArgumentException("Build instructions require complete UTF-8 input", invalid);
            } finally {
                java.util.Arrays.fill(input, (byte) 0);
            }
        }
        if (DeploymentToolchainDependencies.OPERATION.equals(operation)) {
            var prerequisites = (Map<?, ?>) facts.get("currentNativeBuildPrerequisites");
            var transaction = (Map<?, ?>) prerequisites.get("fixedTransaction");
            String profile = (String) transaction.get("profile");
            instructions += "\n" + (DeploymentToolchainDependencies.isScopedNativeAptProfile(profile)
                ? scopedAptReviewInstructions()
                : gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources.text(
                    DeploymentAssistedApproval.class, "native-dependencies-review.md"));
        }
        return instructions;
    }

    private static String scopedAptReviewInstructions() {
        return """
            Compare the observed missing native tools with the exact fixed APT profile, literal command and package targets.
            Follow the protected Ubuntu/Debian admission, authenticated sources, index and frozen archive handling from the
            actual worker and mirror code. The declared root packages are only the observed gaps. Review resolver-selected
            dependencies and potential upgrades, distribution maintainer scripts, triggers and validated OS hooks under
            the authenticated transaction target. Read any missing reached source and deny insufficient evidence or unsafe effects.
            A fresh exact host grant must follow the independent verdict. The completion receipt and a new full host probe
            must confirm the reviewed prerequisites before the candidate proceeds.
            """;
    }

    private static String toolchainReviewInstructions(String instructions, String operation) {
        return instructions.lines().filter(line -> line.startsWith("Review the exact registered fixed effect.")
            || line.startsWith("reviewedApplicationSources records")
            || line.startsWith("hostPrerequisiteObservations,")
            || line.startsWith("For " + operation + ",")
            || line.startsWith("For tracked and diagnosed toolchain JSON requests,")
            || line.startsWith("When the supplied evidence establishes the boundaries"))
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** Trusted instructions for an exact archive-bound standard build. */
    public static String standardBuildReviewInstructions() {
        return """
            Review the exact fixed build and original authority before any execution. Match the saved server, declared targets,
            reviewed application and selected component archive. The original source display name alone does not identify the
            application. Check the collector, complete executable stdin and project sources, engine, bound tools, candidate
            ownership, non-root isolation, paths, source integrity, resource and time limits, and selected mirror effects.
            The independently hash-bound helper and build-entry contract describes the fixed platform code. Verify the actual
            command, input and boundaries. Only ordinary-offline adds the fixed network restrictions to the build unit. Any
            selected Maven bootstrap labels only its exact range. Review all remaining executable input and nested hooks.
            Deny unsafe effects, missing executable evidence, ownership or source drift, root project execution, writable host
            executables, secret disclosure and unverified prerequisites. Exact digest checks and source frames grant no permission.
            An approval permits only a request for a fresh host grant for this exact effect. Earlier grants cannot authorize it.
            Submit the native verdict with a concrete reason. Missing material evidence or a missing boundary requires DENY.
            """;
    }

    private Optional<String> nativePublicationApplication(ActionIntent intent,
        gold.debug.windowstolinux.appapi.linux.command.CommandRequest request, TaskContext supplied) throws ApiException {
        if (intent.semantics().filter(value -> Set.of("deployment.publication.inspect.v1", "deployment.publication.observe.v1")
            .contains(value.operation())).isEmpty()) return Optional.empty();
        return gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandEvidence.nativePublicationApplication(
            intent, request, gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied));
    }

    private synchronized Map<String, ?> publicationObservationFacts(ActionIntent intent, String application, TaskContext supplied)
        throws ApiException {
        sameTask(supplied);
        verify();
        if (workflow == null) return Map.of();
        var authority = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.reviewFacts(workflow, task, intent);
        validateWorkflow(authority);
        var scope = new LinkedHashMap<String, Object>();
        scope.put("application", application);
        scope.put("server", intent.serverIds().getFirst());
        scope.put("operation", intent.semantics().orElseThrow().operation());
        var association = reviewedApplicationSources.get(application);
        if (association != null) scope.put("reviewedApplicationSource", association);
        var facts = new LinkedHashMap<String, Object>();
        facts.put("workflowAuthorization", authority);
        if (sourceSelection != null) facts.put("sourceSelection", sourceSelection);
        facts.put("publicationObservationScope", Map.copyOf(scope));
        return Map.copyOf(facts);
    }

    private synchronized Map<String, ?> nativeDependencyFacts(ActionIntent intent,
                                                              gold.debug.windowstolinux.appapi.linux.command.CommandRequest request, TaskContext supplied) throws ApiException {
        var planned = toolchainPlan;
        if (planned == null || !planned.identity().equals(intent.revision())
            || !intent.serverIds().equals(List.of(planned.server())))
            throw new IllegalArgumentException("Current native preparation plan required");
        var r = planned.plan().request();
        var observation = hostPrerequisites.get(r.facts().applicationId());
        if (observation == null || !Boolean.TRUE.equals(observation.get("nativeBuildPrerequisitesMissing"))
            || !planned.identity().equals(observation.get("reviewedPlanSha256")))
            throw new IllegalArgumentException("Current typed native prerequisite gap required");
        // Re-render against the actual source/archive handles and original workflow, rather than trusting stored labels.
        toolchainPreparationPlanned(planned.plan(), planned.identity(), supplied);
        var association = reviewedApplicationSources.get(r.facts().applicationId());
        if (association == null || association.entrySet().stream().anyMatch(entry -> !entry.getValue().equals(observation.get(entry.getKey()))))
            throw new IllegalArgumentException("Native gap source association changed");
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied);
        if (!r.layout().digest().equals(layout.digest()))
            throw new IllegalArgumentException("Native plan layout differs from original task");
        var binding = DeploymentToolchainDependencies.inspect(intent, request, layout);
        if (!binding.get("profile").equals(observation.get("nativeBuildProfile")))
            throw new IllegalArgumentException("Native package request differs from the observed missing tools");
        return Map.of("currentToolchainPlan", planned.evidence(), "observedGap", observation, "fixedTransaction", binding);
    }

    private static boolean mirroredBuildInput(gold.debug.windowstolinux.appapi.linux.command.CommandRequest request) {
        byte[] input = request.stdin();
        try {
            return gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks.buildInput(
                new String(input, java.nio.charset.StandardCharsets.UTF_8)).isPresent();
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    /** Compares the literal build archive guard and complete collector to one captured source association. */
    static Map<String, Object> buildArchiveFacts(List<Map<String, Object>> archives,
                                                 Map<String, Map<String, Object>> reviewedSources, ActionIntent intent,
                                                 gold.debug.windowstolinux.appapi.linux.command.CommandRequest request,
                                                 gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) throws java.io.IOException {
        byte[] input = request.stdin();
        try {
            String invocation = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract.verifiedInvocation(request.script(), layout);
            var collector = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildOutputProtocol.reviewFacts(invocation, input, layout);
            if (!intent.semantics().orElseThrow().operation().equals("deployment.build.run.v1") || intent.serverIds().size() != 1
                || !intent.semantics().orElseThrow().targets().contains(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(
                intent.serverIds().getFirst(), "deployment-build", collector.get("candidateRoot").toString())))
                throw new IllegalArgumentException("Build archive candidate differs");
            String body = java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(input)).toString();
            var guard = java.util.regex.Pattern.compile("(?m)^" + java.util.regex.Pattern.quote("test \"$(sha256sum \"$archive\" | awk '{print $1}')\" = '")
                + "([a-f0-9]{64})'$").matcher(body);
            if (!guard.find()) throw new IllegalArgumentException("Build archive hash guard missing");
            String expected = guard.group(1);
            if (guard.find()) throw new IllegalArgumentException("Build archive hash guard is ambiguous");
            String application = collector.get("application").toString();
            var association = reviewedSources.get(application);
            if (association == null || !application.equals(association.get("application"))
                || !intent.serverIds().getFirst().equals(association.get("server"))
                || !layout.digest().equals(association.get("remoteLayoutSha256"))
                || !expected.equals(association.get("archiveSha256")))
                throw new IllegalArgumentException("Build differs from its reviewed application archive binding");
            var archiveFields = List.of("archiveSha256", "relativeRoot", "originSnapshotId", "sourceManifestSha256",
                "archiveSnapshotId", "localArchivePath", "archiveBytes");
            if (archiveFields.stream().anyMatch(field -> !association.containsKey(field)))
                throw new IllegalArgumentException("Reviewed archive binding is incomplete");
            var matched = archives.stream().filter(value -> archiveFields.stream()
                .allMatch(field -> Objects.equals(association.get(field), value.get(field)))).toList();
            if (matched.size() != 1) throw new IllegalArgumentException("One exact captured build archive required");
            return Map.of("expectedArchiveSha256", expected, "sha256Characters", expected.length(), "archive", matched.getFirst(),
                "candidate", collector.get("candidate"), "candidateRoot", collector.get("candidateRoot"),
                "comparison", "REVIEWED_APPLICATION_ROOT_AND_ORIGINAL_ARCHIVE_MATCH_COLLECTOR_WITH_ALL_64_SHA256_CHARACTERS",
                "meaning", "Full literal archive SHA256 in this exact executable stdin equals the captured compressed archive SHA256, without display abbreviation. The complete collector round-trip verifies this input digest and declared candidate. This is a literal source association, not proof that the shell guard or archive extraction has executed, that later code cannot override variables, that project code/hooks are safe, or that execution is authorized. Independently review the complete body, exact arguments and source-bound SYSTEM build isolation contract.");
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    /** Cross-checks a literal publication with the original durable proposal, without claiming a completed remote effect. */
    static Map<String, Object> publicationFacts(gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity identity,
                                                List<gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitCommit> units,
                                                ActionIntent intent, gold.debug.windowstolinux.appapi.linux.command.CommandRequest request,
                                                TaskContext task) {
        if (!identity.taskId().equals(task.id()) || !intent.serverIds().equals(List.of(identity.server().id())) || units.size() > 32)
            throw new IllegalArgumentException("Original publication task or server differs");
        var matched = units.stream().filter(value -> value.unit().server().equals(identity.server())
            && intent.semantics().orElseThrow().targets().contains(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(
            identity.server().id(), "deployment-release", value.unit().releaseRoot()))).toList();
        if (matched.size() != 1) throw new IllegalArgumentException("One original publication unit required");
        var value = matched.getFirst();
        var unit = value.unit();
        String invocation = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract.verifiedInvocation(request.script(), unit.layout());
        var frame = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("'" + unit.layout().helperPath() + "' ")
            + "'(publish-deployment|publish-container)' " + java.util.regex.Pattern.quote("'" + unit.id() + "' ")
            + "'(" + java.util.regex.Pattern.quote(unit.id()) + "-[a-f0-9]{16})' "
            + java.util.regex.Pattern.quote("'" + value.release().sha256() + "' '" + unit.ownershipManifestSha256() + "' ")).matcher(invocation);
        if (!frame.lookingAt())
            throw new IllegalArgumentException("Publication differs from original release or ownership proposal");
        return Map.of("task", task.id().toString(), "execution", identity.executionId().toString(), "server", identity.server().id(),
            "workflowPlanSha256", identity.reviewedRevision(), "application", unit.id(), "literalCandidate", frame.group(2),
            "releaseSha256", value.release().sha256(), "ownershipManifestSha256", unit.ownershipManifestSha256(),
            "configurationSha256", value.configuration().sha256(),
            "meaning", "Read from this original task's durable pre-dispatch typed publication proposal, produced after candidate build/freeze and snapshot. Literal application/release/ownership and declared release target match that stored proposal. literalCandidate only labels the command argument; separate resource/source evidence binds that candidate. This is intended identity and prior pipeline ordering, not proof of current candidate bytes, live ownership, successful publication, safety or authority; the pinned helper must still validate sealed candidate and actual filesystem state.");
    }

    /** Supplies the same task-bound observations for fixed transfers and business effects. */
    public synchronized Map<String, ?> effectFacts(ActionIntent intent, TaskContext supplied) throws ApiException {
        sameTask(supplied);
        verify();
        if (workflow == null) return Map.of();
        var evidence = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.reviewFacts(workflow, task, intent);
        validateWorkflow(evidence);
        var source = sourceSelection;
        var facts = new LinkedHashMap<String, Object>();
        facts.put("workflowAuthorization", evidence);
        if (source != null) facts.put("sourceSelection", source);
        if (!hostPrerequisites.isEmpty())
            facts.put("hostPrerequisiteObservations", Map.of("applications", List.copyOf(hostPrerequisites.values()),
                "meaning", "Complete successful fixed host probes after catalog preparation in this original task, evaluated by the product against the exact reviewed runtime and resolved tools. These observations identify non-catalog host prerequisites and are separate from model-authored rationale. They are prior observations, not current live state, installation proof or permission. A supplement needs fresh independent review of its complete effects, source/version resolution and enforced postconditions; the original prepare must probe and validate again before any candidate upload."));
        if (!reviewedApplicationSources.isEmpty())
            facts.put("reviewedApplicationSources", Map.of("applications", List.copyOf(reviewedApplicationSources.values()),
                "meaning", "Application identifiers from this original task's deterministically reviewed requests, matched to the captured original snapshot and exact component archive before preparation. Source display names and frozen logicalRootName label the selected source; they are not deployment application or component identifiers and need not equal them. Match each command application and resource target to its own reviewed association, source hashes, relative root, server and layout. These intended associations prove no live ownership, completed build, safe source or permission."));
        if (intent.type().equals("platform.sftp.upload") && intent.semantics()
            .filter(value -> value.operation().equals("deployment.candidate.upload.v1")).isPresent())
            facts.put("currentSourceArchiveTransfer", archiveTransferFacts(archiveObservations(), intent,
                new ServerId(Objects.requireNonNull(source).get("server").toString()),
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(original.tasks(), supplied)));
        if (!sourceArchives.isEmpty())
            facts.put("sourceArchiveObservations", Map.of("archives", archiveObservations(),
                "meaning", "Archiver-produced frozen source/subtree associations captured before final confirmation under this original task/server. sourceToolchainRequirements preserve the deterministic selected component declarations/constraints/source files, independent of the initial draft URI/git field; they are not a resolved future installer result or installation proof. Source manifest and compressed archive hashes identify different objects; match the build envelope's expected archive SHA to its association and declared candidate. Listed companion files belong to that archived subtree; file lists and recent history (32 archives) are explicitly bounded, not a complete content/safety review. The deterministic build body is separately reviewed, not required to be a file or signature of the source manifest. It verifies actual compressed archive bytes before extraction; project hooks still require non-root candidate isolation, complete executable input and fresh independent host permission. These observations do not prove live remote state, safe content or authorization."));
        if (!preparedToolchains.isEmpty())
            facts.put("toolchainPreparationObservations", Map.of("bindings", List.copyOf(preparedToolchains.values()),
                "meaning", "Earlier complete successful preparation receipts under this original task/server, matched to every typed selection after standard catalog/requirement validation. bindingSha256 identifies that exact canonical set. Recent history is bounded to 32 sets; absence is not proof of missing installation. This is a prior observation, not current live state, a safety verdict or command permission; bind still validates live directories/markers/versions and SYSTEM hashes."));
        return Map.copyOf(facts);
    }

    /** Matches the entire canonical transfer to exactly one archiver observation, without granting permission. */
    static Map<String, Object> archiveTransferFacts(List<Map<String, Object>> archives, ActionIntent intent,
                                                    ServerId server, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        var semantics = intent.semantics().orElseThrow();
        if (!semantics.operation().equals("deployment.candidate.upload.v1") || semantics.targets().size() != 1
            || !intent.serverIds().equals(List.of(server.id())))
            throw new IllegalArgumentException("Archive transfer scope differs");
        var target = semantics.targets().iterator().next();
        String remote = target.id(), prefix = layout.workRoot() + "/", suffix = "/mutable/source.tar.gz";
        if (!target.serverId().equals(server.id()) || !target.kind().equals("deployment-candidate")
            || !remote.startsWith(prefix) || !remote.endsWith(suffix))
            throw new IllegalArgumentException("Archive transfer destination differs");
        String candidate = remote.substring(prefix.length(), remote.length() - suffix.length());
        if (!(layout.candidateRoot(candidate) + suffix).equals(remote))
            throw new IllegalArgumentException("Archive transfer candidate differs");
        var matches = new ArrayList<Map<String, Object>>();
        for (var archive : archives) {
            var request = new gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest(
                java.nio.file.Path.of(archive.get("localArchivePath").toString()), remote,
                ((Number) archive.get("archiveBytes")).longValue());
            String hash = archive.get("archiveSha256").toString();
            var expected = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.transfer(server, intent.revision(), request, hash, true);
            boolean matched = expected.withSemantics(semantics).equals(intent);
            for (var choice : gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice.values())
                matched |= gold.debug.windowstolinux.appapi.linux.action.RemoteActions.transfer(server, intent.revision(), request, hash, true, choice)
                    .withSemantics(semantics).equals(intent);
            if (matched) matches.add(archive);
        }
        if (matches.size() != 1) throw new IllegalArgumentException("One exact captured upload archive required");
        return Map.of("archive", matches.getFirst(), "server", server.id(), "literalCandidate", candidate,
            "remoteArchivePath", remote, "canonicalTransferMatched", true,
            "meaning", "Complete canonical transfer (direction, server, local path, archive SHA256, exact byte limit, remote path and optional explicit conflict choice) equals exactly one frozen archiver observation. Other observations are sibling component archives, not conflicting identities. Source manifest SHA and compressed archive SHA identify different objects. The local archive basename may differ from the fixed remote source.tar.gz name. This identifies intended transfer content only; the host must still verify actual local bytes, destination, conflicts and fresh independent permission. It does not prove live remote ownership, safe source or authorization.");
    }

    private void validateWorkflow(Map<String, Object> evidence) {
        if (!revision.equals(evidence.get("modelRevision")) || !approvalMode().equals(evidence.get("approvalPolicy"))
            || !Boolean.valueOf(rememberAuthorization).equals(evidence.get("rememberAuthorization")))
            throw new IllegalArgumentException("Workflow evidence differs from the frozen assisted route");
    }

    @Override
    public void verify(ActionIntent intent, gold.debug.windowstolinux.appapi.linux.command.CommandRequest request,
                       TaskContext supplied) throws ApiException {
        facts(intent, request, supplied, false);
    }

    /** Logical language capability calls, shared by analysis and recovery. / 分析和恢复共用的逻辑语言能力调用次数。 */
    private final java.util.concurrent.atomic.AtomicInteger languageCalls = new java.util.concurrent.atomic.AtomicInteger();

    /** Validates the task-local route and policy without replacing host authority. / 验证任务内部路由及策略，不替换宿主权限。
     * @param original actual owner capabilities / 实际所属能力
     * @param task original task / 原任务
     * @param revision frozen complete revision / 冻结完整修订
     * @param approvalMode selected exact policy / 所选精确策略 */
    public DeploymentAssistedScope(ModContext original, TaskContext task, String revision, String approvalMode) {
        this(original, task, revision, approvalMode, true);
    }

    /** Captures both original policies without replacing host authority. / 抓取两项原策略，不替换宿主权限。
     * @param original actual owner capabilities / 实际所属能力
     * @param task authentic original task / 真实原任务
     * @param revision frozen model route / 冻结模型路由
     * @param approvalMode selected approval strategy / 所选审批策略
     * @param rememberAuthorization selected rememberAuthorization repetition / 所选授权重复规则 */
    public DeploymentAssistedScope(ModContext original, TaskContext task, String revision, String approvalMode, boolean rememberAuthorization) {
        this.original = Objects.requireNonNull(original);
        this.task = Objects.requireNonNull(task);
        this.revision = Objects.requireNonNull(revision);
        gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentAuthorizationPolicy.requireAllowed(approvalMode, rememberAuthorization);
        this.approvalMode = approvalMode;
        this.rememberAuthorization = rememberAuthorization;
    }

    /** Returns the selected policy without changing it. / 返回所选策略，不改变其值。
     * @return original exact policy / 原精确策略 */
    public String approvalMode() {
        return gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.current(task, approvalMode);
    }

    /** Returns the captured original question repetition. / 返回已抓取原问题重复规则。
     * @return original rememberAuthorization strategy / 原授权策略 */
    public boolean rememberAuthorization() {
        return rememberAuthorization;
    }

    /** Returns the original complete frozen model revision without reading preferences. / 不读取偏好地返回原完整冻结模型修订。
     * @return original frozen revision / 原冻结修订 */
    public String modelRevision() {
        return revision;
    }

    /** Counts one logical language send; provider HTTP fallback is outside this counter. / 计算一次逻辑语言发送；提供方 HTTP 回退不属于该计数。
     * @param purpose actual model purpose / 实际模型用途
     * @throws ApiException if the original thirty-call budget is exhausted / 原三十次调用预算耗尽时 */
    private void consume(AiPurpose purpose) throws ApiException {
        if (purpose == AiPurpose.LANGUAGE && languageCalls.updateAndGet(value -> Math.min(321, value + 1)) > 320)
            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_ASSISTED_DECISION_BUDGET",
                "Original assisted language-call budget is exhausted", Map.of("task", task.id().toString()), Optional.of(task.id())));
    }

    /** Captures the usable language route once before any assisted work. / 辅助工作前一次捕获可用语言路由。
     * @param original actual capabilities / 实际能力
     * @param task original task / 原任务
     * @return task-bound frozen capabilities / 任务绑定冻结能力
     * @throws ApiException if configuration is unavailable / 配置不可用时 */
    public static DeploymentAssistedScope freeze(ModContext original, TaskContext task) throws ApiException {
        return freeze(original, task, "MANUAL_REVIEW");
    }

    /** Requires both selected model roles before freezing any approval policy. / 冻结任何审批策略前要求两个所选模型角色可用。
     * @param original actual owner capabilities / 实际所属能力
     * @param task original authentic task / 真实原任务
     * @param approvalMode selected policy / 所选策略
     * @return exact frozen route / 精确冻结路由
     * @throws ApiException if a required independent reviewer is absent or changed / 必需独立审核者缺失或变化时 */
    public static DeploymentAssistedScope freeze(ModContext original, TaskContext task, String approvalMode) throws ApiException {
        return freeze(original, task, approvalMode, true);
    }

    /** Requires both original roles before freezing both deployment policies. / 冻结部署两策略前要求两个原角色。
     * @param original actual original owner / 实际原所有者
     * @param task authentic original task / 真实原任务
     * @param approvalMode approval strategy / 审批策略
     * @param rememberAuthorization rememberAuthorization repetition / 授权重复规则
     * @return original bound capabilities / 原绑定能力
     * @throws ApiException if original model route is unavailable / 原模型路由不可用时 */
    public static DeploymentAssistedScope freeze(ModContext original, TaskContext task, String approvalMode, boolean rememberAuthorization) throws ApiException {
        String revision = original.ai().configurationRevision(AiPurpose.LANGUAGE, task);
        if (!revision.equals(original.ai().configurationRevision(AiPurpose.APPROVAL, task)))
            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_ASSISTED_CONFIGURATION_CHANGED",
                "Independent assisted review route differs", Map.of("task", task.id().toString()), Optional.of(task.id())));
        return new DeploymentAssistedScope(original, task, revision, approvalMode, rememberAuthorization);
    }

    /** Verifies the original binding at actual dispatch time. / 在真实发送时验证原绑定。
     * @throws ApiException when the binding changed / 绑定变化时 */
    private void verify() throws ApiException {
        if (!revision.equals(original.ai().configurationRevision(AiPurpose.LANGUAGE, task)))
            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_ASSISTED_CONFIGURATION_CHANGED",
                "Assisted configuration changed", Map.of(), Optional.empty()));
        if (!revision.equals(original.ai().configurationRevision(AiPurpose.APPROVAL, task)))
            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_ASSISTED_CONFIGURATION_CHANGED",
                "Independent assisted review configuration changed", Map.of("task", task.id().toString()), Optional.of(task.id())));
    }

    /** Preserves the exact task at every scoped capability call. / 每次限定能力调用均保留精确任务。
     * @param supplied supplied task / 提供任务 */
    private void sameTask(TaskContext supplied) {
        if (supplied != task)
            throw new IllegalArgumentException("Assisted capability belongs to another task");
    }

    /** Returns real SSH with a guard before each actual send. / 返回每次真实发送前带校验的真实 SSH。
     * @return guarded original Linux access / 带校验原 Linux 访问 */
    @Override
    public LinuxService linux() {
        return new LinuxService() {
            /** Connects only under the frozen original task. / 仅按冻结原任务连接。
             * @param server original target / 原目标
             * @param supplied original task / 原任务
             * @return actual guarded connection / 实际带校验连接
             * @throws ApiException on drift or failed connection / 漂移或连接失败时 */
            public RemoteSession connect(ServerId server, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                var connected = original.linux().connect(server, supplied);
                authenticatedTarget.set(null);
                return new DeploymentAssistedSession(connected,
                    DeploymentAssistedScope.this::verify, () -> { });
            }

            /** Enforces the original endpoint and wire before authenticated connection. / 认证连接前强制原端点及 wire。
             * @param expected original fixed target / 原固定目标
             * @param supplied original task / 原任务
             * @return actual guarded fixed-target connection / 实际受控固定目标连接
             * @throws ApiException if original route, endpoint or trust differs / 原路由、端点或信任不符时 */
            public RemoteSession connect(RemoteTarget expected, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                var connected = original.linux().connect(expected, supplied);
                try {
                    var fingerprint = connected.facts().fingerprint();
                    if (!expected.taskId().equals(supplied.id())
                        || expected.fingerprint().filter(value -> !value.equals(fingerprint)).isPresent())
                        throw new IllegalArgumentException("Authenticated SSH target differs");
                    var authenticated = fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}=?")
                        ? new AuthenticatedTarget(expected, fingerprint) : null;
                    authenticatedTarget.set(authenticated);
                    return new DeploymentAssistedSession(connected, DeploymentAssistedScope.this::verify,
                        () -> authenticatedTarget.compareAndSet(authenticated, null));
                } catch (RuntimeException invalid) {
                    try {
                        connected.close();
                    } catch (ApiException cleanup) {
                        invalid.addSuppressed(cleanup);
                    }
                    throw invalid;
                }
            }

            /** Reads only original pending transfers. / 仅读取原待结传输。
             * @param supplied original task / 原任务
             * @return actual pending transfers / 实际待结传输
             * @throws ApiException on unavailable evidence / 依据不可用时 */
            public List<TransferCheckpoint> pendingTransfers(TaskContext supplied) throws ApiException {
                sameTask(supplied);
                return original.linux().pendingTransfers(supplied);
            }
        };
    }

    /** Returns AI that cannot silently adopt a later configuration. / 返回不能默默采用后续配置的 AI。
     * @return frozen original AI capability / 冻结原 AI 能力 */
    @Override
    public AiService ai() {
        return new AiService() {
            /** Reads non-secret model metadata inside the original task admission. / 在原任务准入内读取非秘密模型元数据。
             * @param supplied authentic original task context / 真实原任务上下文
             * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
             * @throws ApiException if original admission, storage or configuration validation fails / 原准入、存储或配置校验失败时
             */
            public java.util.List<gold.debug.windowstolinux.appapi.ai.model.AiModelMetadata> models(TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                return original.ai().models(supplied);
            }

            /** Reads non-secret model metadata inside the original task admission. / 在原任务准入内读取非秘密模型元数据。
             * @param purpose declared invocation purpose / 声明的调用用途
             * @param supplied authentic original task context / 真实原任务上下文
             * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
             * @throws ApiException if original admission, storage or configuration validation fails / 原准入、存储或配置校验失败时
             */
            public java.util.List<gold.debug.windowstolinux.appapi.ai.model.AiModelMetadata> models(AiPurpose purpose, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                return original.ai().models(purpose, supplied);
            }

            /** Captures the inventory revision without requiring recommendation membership. / 捕获清单修订，不要求推荐成员资格。
             * @param modelId optional or exact platform model configuration identity / 可选或精确的平台模型配置标识
             * @param supplied authentic original task context / 真实原任务上下文
             * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
             * @throws ApiException if original admission, storage or configuration validation fails / 原准入、存储或配置校验失败时
             */
            public String modelConfigurationRevision(String modelId, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                return original.ai().modelConfigurationRevision(modelId, supplied);
            }

            /** Rejects audio outside the frozen text/vision assisted decision budget. / 拒绝冻结文字与视觉辅助决策预算之外的音频。
             * @param request audio request / 音频请求
             * @param supplied original task / 原任务
             * @return no audio dispatch / 不发送音频
             * @throws ApiException because this workflow did not admit audio / 此流程未准入音频 */
            public OperationResult<gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse> call(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest request, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                throw voiceDenied();
            }

            /** Rejects audio regardless of a caller-supplied revision. / 无论调用方提供何修订均拒绝音频。
             * @param request audio request / 音频请求
             * @param expected caller revision / 调用方修订
             * @param supplied original task / 原任务
             * @return no audio dispatch / 不发送音频
             * @throws ApiException because audio was not admitted / 音频未准入 */
            public OperationResult<gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse> call(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest request, String expected, TaskContext supplied) throws ApiException {
                return call(request, supplied);
            }

            /** Rejects realtime resources outside the assisted workflow scope. / 拒绝辅助流程作用域以外的实时资源。
             * @param options audio budgets / 音频预算
             * @param listener audio events / 音频事件
             * @param supplied original task / 原任务
             * @return no realtime session / 不创建实时会话
             * @throws ApiException because audio was not admitted / 音频未准入 */
            public gold.debug.windowstolinux.appapi.ai.voice.VoiceSession openVoice(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceOptions options, gold.debug.windowstolinux.appapi.ai.voice.VoiceListener listener,
                TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                throw voiceDenied();
            }

            /** Explains the exact missing admission without exposing business data. / 不暴露业务数据地说明精确未准入范围。
             * @return denied capability / 拒绝能力 */
            private ApiException voiceDenied() {
                return new ApiException(new Problem(ProblemCode.ACCESS_DENIED,
                    "DEPLOYMENT_ASSISTED_VOICE_SCOPE", "Assisted deployment admits only its frozen text and vision decisions", Map.of(), Optional.empty()));
            }

            /** Verifies the usable purpose and frozen whole route. / 验证可用用途和冻结整路由。
             * @param purpose required purpose / 必需用途
             * @param supplied original task / 原任务
             * @return frozen revision / 冻结修订
             * @throws ApiException on drift or missing purpose / 漂移或用途缺失时 */
            public String configurationRevision(AiPurpose purpose, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                if (!revision.equals(original.ai().configurationRevision(purpose, supplied)))
                    throw new IllegalStateException("Whole AI route differs by purpose");
                return revision;
            }

            /** Calls only the frozen route. / 仅调用冻结路由。
             * @param request bounded request / 有界请求
             * @param supplied original task / 原任务
             * @return actual provider feedback / 实际提供方反馈
             * @throws ApiException on drift or failed invocation / 漂移或调用失败时 */
            public OperationResult<AiResponse> call(AiRequest request, TaskContext supplied) throws ApiException {
                return call(request, revision, supplied);
            }

            /** Rejects any caller revision differing from the task. / 拒绝与任务不同的调用方修订。
             * @param request bounded request / 有界请求
             * @param expected caller binding / 调用方绑定
             * @param supplied original task / 原任务
             * @return actual provider feedback / 实际提供方反馈
             * @throws ApiException on drift or failed invocation / 漂移或调用失败时 */
            public OperationResult<AiResponse> call(AiRequest request, String expected, TaskContext supplied)
                throws ApiException {
                sameTask(supplied);
                verify();
                if (!revision.equals(expected))
                    throw new IllegalArgumentException("Assisted caller revision differs");
                consume(request.purpose());
                return original.ai().call(request, revision, supplied);
            }
        };
    }

    /** Returns bound owner identity. / 返回绑定所有者身份。
     * @return bound owner identity / 绑定所有者身份 */
    @Override
    public ModIdentity identity() {
        return original.identity();
    }

    /** Returns original launch mode. / 返回原启动模式。
     * @return original launch mode / 原启动模式 */
    @Override
    public ModLaunchMode launchMode() {
        return original.launchMode();
    }

    /** Returns owner page capability. / 返回所属页面能力。
     * @return owner page capability / 所属页面能力 */
    @Override
    public UiService ui() {
        return original.ui();
    }

    /** Returns original task capability. / 返回原任务能力。
     * @return original task capability / 原任务能力 */
    @Override
    public TaskService tasks() {
        return original.tasks();
    }

    /** Returns host review capability. / 返回宿主审核能力。
     * @return host review capability / 宿主审核能力 */
    @Override
    public InteractionService interaction() {
        return new gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview() {
            /** Reviews complete fixed command bytes before the exact host grant. / 精确宿主许可前审核完整固定命令字节。
             * @param intent original exact intent / 原精确意图
             * @param request complete actual request / 完整实际请求
             * @param supplied authentic original task / 真实原任务
             * @return actual single-use host grant or refusal / 实际单次宿主许可或拒绝
             * @throws ApiException if original review fails / 原审核失败时 */
            public OperationResult<ApprovalGrant> approveCommand(ActionIntent intent,
                                                                 gold.debug.windowstolinux.appapi.linux.command.CommandRequest request, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                var actual = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval.approveWithEvidence(
                    DeploymentAssistedScope.this, original.interaction(), intent, request, supplied, revision, approvalMode, rememberAuthorization,
                    DeploymentAssistedScope.this,
                    gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().requests().generationRecoveryAttempts());
                if (actual.outcome() == gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED) verify();
                return actual;
            }

            /** Applies the task's fixed policy before the original exact host review. / 原精确宿主审阅前应用任务固定策略。
             * @param intent exact fixed intent / 精确固定意图
             * @param supplied authentic original task / 真实原任务
             * @return original host grant or refusal / 原宿主授权或拒绝
             * @throws ApiException if routing or fixed review rejects / 路由或固定审阅拒绝时 */
            public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                var actual = DeploymentAssistedApproval.approve(DeploymentAssistedScope.this, original.interaction(), intent, supplied, revision, approvalMode, rememberAuthorization);
                if (actual.outcome() == gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED) verify();
                return actual;
            }

            /** Preserves original human input authority. / 保留原人工输入权限。
             * @param title original title / 原标题
             * @param prompt original question / 原问题
             * @param secret original secret classification / 原秘密分类
             * @param supplied original authentic task / 原真实任务
             * @return original classified answer / 原分类答案
             * @throws ApiException if the frozen task is no longer admitted / 冻结任务不再准入时 */
            public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                return original.interaction().requestText(title, prompt, secret, supplied);
            }
        };
    }

    /** Returns original server references. / 返回原服务器引用。
     * @return original server references / 原服务器引用 */
    @Override
    public ServerService servers() {
        return original.servers();
    }

    /** Returns original Schema access. / 返回原 Schema 访问。
     * @return original Schema access / 原 Schema 访问 */
    @Override
    public ModDatabase db() {
        return original.db();
    }

    /** Returns original opaque secret access. / 返回原不透明秘密访问。
     * @return original opaque secret access / 原不透明秘密访问 */
    @Override
    public SecretService secrets() {
        return original.secrets();
    }

    /** Returns original source ownership. / 返回原来源归属。
     * @return original source ownership / 原来源归属 */
    @Override
    public FileService file() {
        return original.file();
    }

    /** Returns original structured diagnostics. / 返回原结构化诊断。
     * @return original structured diagnostics / 原结构化诊断 */
    @Override
    public LogService logs() {
        return original.logs();
    }

    /** Returns original process ownership. / 返回原进程归属。
     * @return original process ownership / 原进程归属 */
    @Override
    public ProcessService processes() {
        return original.processes();
    }

    /** Returns declared service capability. / 返回已声明服务能力。
     * @return declared service capability / 已声明服务能力 */
    @Override
    public ModServices services() {
        return original.services();
    }
}
