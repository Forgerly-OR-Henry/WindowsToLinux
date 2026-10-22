package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.linux.build.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.report.DeploymentProjectReport;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.registry.DeploymentBuildWriterRegistry;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.*;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.SafeBuildScriptEnvelope;

/** Optional shared recipes on the original Agent candidate. / 原 Agent 候选上的可选公共配方。 */
final class DeploymentAgentBuildTools {
    static final int BUILD_MEMORY_MIB = 2048;

    /** Point-in-time admission floor, not a DAEMON memory cap or CPU reservation. */
    static long isolationMemoryFloor() {
        String helper;
        try (var input = DeploymentAgentBuildTools.class.getResourceAsStream(
            "/gold/debug/windowstolinux/mod/deployment/linux/helper/workspace/27-agent-workspace.sh")) {
            if (input == null) throw new IllegalStateException("Agent build resource contract missing");
            helper = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (java.io.IOException failure) { throw new IllegalStateException("Agent build resource contract unavailable", failure); }
        String fixed = "    run_restricted_build \"$app\" \"$candidate\" \"$seconds\" 256 " + BUILD_MEMORY_MIB
            + " \"$output\" \"$engine\" \"$script_digest\" \"$tools\" || status=$?";
        String controller = gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms
            .controller(gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
        if (!helper.lines().anyMatch(fixed::equals) || BUILD_MEMORY_MIB != 2048
            || !controller.contains("'MemoryMax=2147483648'")
            || !controller.contains("AGENT_PROBE_VOLUME_BYTES = 64 * 1024 * 1024\n")
            || !controller.contains("AGENT_HOST_RESERVE_BYTES = 1024 * 1024 * 1024\n"))
            throw new SecurityException("Agent build/PROBE resource contract changed; isolation budget must be re-evaluated");
        return Math.multiplyExact((long) BUILD_MEMORY_MIB + 256, 1024L * 1024);
    }

    /** Source-bound deterministic analysis / 来源绑定确定性分析。
     * @param snapshot Immutable snapshot digest / 不可变快照摘要
     * @param root Component source root / 组件源码根
     * @param report Actual project assessment / 实际项目评估
     */
    private record Analysis(String snapshot, String root, DeploymentProjectReport report) {
    }

    /** Source and runtime-bound verified toolchains / 来源及运行绑定核实工具链。
     * @param snapshot Immutable snapshot digest / 不可变快照摘要
     * @param root Component source root / 组件源码根
     * @param runtime Reviewed runtime contract / 已审阅运行合同
     * @param tools verified actual tool binding / 已核实实际工具绑定
     */
    private record Tools(String snapshot, String root, DeploymentRuntimeDefinition runtime,
                         DeploymentResolvedToolchains tools, Map<String, Object> recipe,
                         Map<String, Object> outputSemantics) {
    }

    private record BuildOutcome(Tools binding, DeploymentAgentProjectChannel.RunReceipt run) {
    }

    /** Original platform capabilities / 原平台能力。
     */
    private final DeploymentRemoteLayout layout;

    private final boolean reuseExistingOnly;

    private final ModContext context;

    /** Original admitted task / 原已准入任务。
     */
    private final TaskContext task;

    /** Current task-owned immutable sources / 当前任务所属不可变来源。
     */
    private final DeploymentAgentSources sources;

    /** Original candidate channel / 原候选通道。
     */
    private final DeploymentAgentProjectChannel projects;

    /** Reviewed command channel / 已审阅命令通道。
     */
    private DeploymentBuildCommandChannel commands;

    private final ServerId server;

    /** Source-bound shared analysis results / 来源绑定公共分析结果。
     */
    private final Map<String, Analysis> analyses = new HashMap<>();

    /** Current verified tool bindings / 当前已核实工具绑定。
     */
    private final Map<String, Tools> bindings = new HashMap<>();

    /** Actual executed tool bindings / 实际执行工具绑定。
     */
    private final Map<String, Tools> executed = new HashMap<>();

    private final Map<String, BuildOutcome> builds = new HashMap<>();

    /** Binds original task capabilities / 绑定原任务能力。
     * @param context Original platform capabilities / 原平台能力
     * @param task Original admitted task / 原已准入任务
     * @param session Original authenticated command channel / 原已认证命令通道
     * @param server Exact original server / 精确原服务器
     * @param revision Exact current revision / 精确当前修订
     * @param guard Current identity guard / 当前身份守卫
     * @param sources Current task-owned immutable sources / 当前任务所属不可变来源
     * @param projects Original candidate channel / 原候选通道
     */
    DeploymentAgentBuildTools(ModContext context, TaskContext task, RemoteSession session, ServerId server,
                              String revision, DeploymentSendGuard guard, DeploymentAgentSources sources, DeploymentAgentProjectChannel projects) {
        this(context, task, session, server, revision, guard, sources, projects, DeploymentRemoteLayout.original());
    }

    DeploymentAgentBuildTools(ModContext context, TaskContext task, RemoteSession session, ServerId server,
                              String revision, DeploymentSendGuard guard, DeploymentAgentSources sources, DeploymentAgentProjectChannel projects, DeploymentRemoteLayout layout) {
        this(context, task, session, server, revision, guard, sources, projects, layout, false);
    }

    DeploymentAgentBuildTools(ModContext context, TaskContext task, RemoteSession session, ServerId server,
                              String revision, DeploymentSendGuard guard, DeploymentAgentSources sources,
                              DeploymentAgentProjectChannel projects, DeploymentRemoteLayout layout, boolean reuseExistingOnly) {
        this.reuseExistingOnly = reuseExistingOnly;
        this.layout = Objects.requireNonNull(layout);
        this.context = context;
        this.task = task;
        this.server = Objects.requireNonNull(server);
        this.sources = sources;
        this.projects = projects;
        commands = new DeploymentBuildCommandChannel(session, reuseExistingOnly
            ? new ExistingPreparationReview(context, context.interaction(), task, server, layout) : context.interaction(), server, task, revision,
            gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoalBudget.current()
                .map(gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoalBudget::build)
                .orElseGet(() -> new DeploymentBuildBudget(7200)), guard, layout);
    }

    /** Classifies only the original denied existing-only review, before any host grant or dispatch. */
    private record ExistingPreparationReview(ModContext context, InteractionService original, TaskContext task,
                                             ServerId server, DeploymentRemoteLayout layout) implements DeploymentCommandReview {
        public OperationResult<ApprovalGrant> approveCommand(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
            try {
                return DeploymentCommandReviewRouter.approve(original, intent, request, supplied);
            } catch (ApiException denied) {
                if (!(original instanceof DeploymentCommandReview) || supplied != task
                    || denied.problem().code() != ProblemCode.ACCESS_DENIED || !denied.problem().eventCode().equals("DEPLOYMENT_COMMAND_REVIEW_DENIED")
                    || !task.id().toString().equals(denied.problem().context().get("task"))
                    || !denied.problem().context().getOrDefault("reviewBinding", "").matches("[0-9a-f]{64}")
                    || denied.getSuppressed().length != 0
                    || !intent.serverIds().equals(List.of(server.id()))
                    || intent.semantics().filter(value -> value.operation().equals("deployment.agent.toolchain.prepare.v1")).isEmpty())
                    throw denied;
                var actual = RemoteActions.command(server, intent.revision(), request);
                byte[] input = request.stdin();
                try {
                    if (!intent.type().equals(actual.type()) || !intent.target().equals(actual.target())
                        || !intent.exactAction().equals(actual.exactAction()) || !intent.effectSummary().equals(actual.effectSummary())
                        || !Arrays.equals(input, gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms
                            .existingController(layout).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        throw denied;
                } finally {
                    Arrays.fill(input, (byte) 0);
                }
                // The caller now continues, so it will not reach Workflow's complete-denial diagnostic path.
                gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentFailure(context, task, denied);
                throw DeploymentAgentAlternativeRequiredException.fromReview(task, denied);
            }
        }

        public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext supplied) throws ApiException {
            return original.approve(intent, supplied);
        }

        public OperationResult<ApprovalGrant> approveRequired(ActionIntent intent, TaskContext supplied) throws ApiException {
            return original.approveRequired(intent, supplied);
        }

        public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext supplied) throws ApiException {
            return original.requestText(title, prompt, secret, supplied);
        }
    }

    /** Keeps selected tools and the consumed budget when adopting an explicitly accepted model revision. / 采用明确接受的模型修订时保留已选工具及已消费预算。
     * @param revision accepted model identity / 已接受模型身份 */
    void replaceConfiguration(String revision) {
        commands = commands.rebound(revision);
    }

    /** Executes the specifically selected capability, without choosing a workflow. / 只执行选定能力，不选择工作流。
     * @param request Explicitly selected typed action / 明确选定类型化动作
     * @param part current component plan / 当前组件计划
     * @param candidate Original protected candidate / 原受保护候选
     * @param revision Exact current revision / 精确当前修订
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    String run(DeploymentAgentProposal.Capability request, DeploymentAgentPlan.Part part,
               DeploymentAgentDeliveryPort.Prepared candidate, String revision) throws Exception {
        String snapshot = sources.material(part.id()).snapshot().sha256();
        if (request.name().equals("analyze")) {
            var type = DeploymentProjectKind.valueOf(DeploymentJsonFields.text(request.arguments(), "type", 64));
            var result = new DeploymentStandardAnalyzer().analyzeComponent(sources.material(part.id()), Path.of(part.sourceRoot()), part.id(), type, task);
            if (result.outcome() != TaskResult.SUCCEEDED)
                throw new DeploymentAiJson.CapabilityOutcomeException(result.outcome(), result.problem());
            var report = result.value().orElseThrow().assessment();
            analyses.put(part.id(), new Analysis(snapshot, part.sourceRoot(), report));
            bindings.remove(part.id());
            return "ANALYSIS snapshot=" + snapshot + " root=" + part.sourceRoot() + " admission=" + report.admission()
                + " facts=" + report.facts().map(v -> v.projectType() + " " + v.buildTool() + " evidence=" + v.evidence()
                + " missing=" + v.missingInformation() + " conflicts=" + v.conflicts()).orElse("unavailable")
                + " runtimeInputs=" + report.runtimeSuggestion().map(v -> v.values().toString()).orElse("unknown")
                + " rejections=" + report.rejections() + "; source facts only, no recipe or permission inferred";
        }
        var analysis = requireAnalysis(part, snapshot);
        var facts = analysis.report().facts().orElseThrow();
        if (request.name().equals("prepare_tools")) {
            var runtime = DeploymentAgentRecipeRuntime.resolve(facts, part, request.arguments().get("recipe"));
            DeploymentBuildWriterRegistry.defaults().require(facts.projectType(), facts.buildTool());
            var cargo = cargoLifecycle(facts, runtime, part);
            var requirements = DeploymentProjectToolchainRequirements.from(facts, runtime);
            var preparation = new gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainCommands(commands, server, context, task, layout, reuseExistingOnly);
            if (requirements.stream().anyMatch(requirement -> requirement.ecosystem() == DeploymentToolchainType.C
                || requirement.ecosystem() == DeploymentToolchainType.CPP)) {
                var dependencies = preparation.prepareNativeBuild();
                if (dependencies.outcome() != TaskResult.SUCCEEDED)
                    throw new DeploymentAiJson.CapabilityOutcomeException(dependencies.outcome(), dependencies.problem());
            }
            var result = new DeploymentToolchainSetup(preparation, DeploymentToolchainCatalog.defaults()).prepare(requirements, 0, 7200);
            if (result.outcome() != TaskResult.SUCCEEDED)
                throw new DeploymentAiJson.CapabilityOutcomeException(result.outcome(), result.problem());
            var selected = result.value().orElseThrow();
            var recipe = request.arguments().get("recipe");
            bindings.put(part.id(), new Tools(snapshot, part.sourceRoot(), runtime, selected, Map.of(
                "version", recipe.get("version").textValue(), "primary", recipe.get("primary").textValue(),
                "secondary", recipe.get("secondary").textValue(), "jvmTarget", recipe.get("jvmTarget").textValue(),
                "servicePort", recipe.get("servicePort").intValue()), cargo.map(DeploymentAgentCargoArtifactLifecycle::semantics).orElse(Map.of())));
            return "TOOLS_VERIFIED " + DeploymentToolchainBindingCodec.identity(selected) + " " + selected.selections()
                + cargo.map(value -> " recipeOutputSemantics=" + value.semantics()).orElse("");
        }
        var binding = requireBinding(part, snapshot);
        var cargo = cargoLifecycle(facts, binding.runtime(), part);
        int seconds = DeploymentJsonFields.number(request.arguments(), "seconds", 60, 7200);
        var limits = new DeploymentBuildLimits(seconds, 256, BUILD_MEMORY_MIB, 1048576,
            Objects.requireNonNull(candidate.quota(), "Original candidate workspace quota is missing").bytes(), false, candidate.quota().policy().reserveBytes());
        String root = "export WTL_AGENT_SOURCE=\"$WTL_SOURCE\""
            + (part.sourceRoot().equals(".") ? "" : "/" + SafeBuildScriptEnvelope.shellQuote(part.sourceRoot())) + "\n";
        String script = root + cargo.map(value -> value.before(candidate.workspace(), part.sourceRoot())).orElse("")
            + ToolchainBuildEnvironment.render(binding.tools())
            + DeploymentBuildWriterRegistry.defaults().require(facts.projectType(), facts.buildTool()).render(facts, binding.runtime(), candidate.workspace(), limits)
            + gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.CompanionBuildScript.render(part.runtime().workload(), facts)
            + cargo.map(value -> value.after(candidate.workspace())).orElse("");
        var result = projects.command(candidate, revision, script, Duration.ofSeconds(seconds), encoded(binding.tools()));
        if (!result.known()) throw new IllegalStateException("Unknown recipe result; never replay");
        executed.put(part.id(), binding);
        builds.put(part.id(), new BuildOutcome(binding, projects.latestRun(candidate).orElseThrow()));
        return "BUILD_RESULT exit=" + result.exitStatus() + " " + result.evidenceOutput() + "; independently inspect and seal output"
            + cargo.map(value -> " recipeOutputSemantics=" + value.semantics()).orElse("");
    }

    /** Checks only current real analysis, recipe and renderer before remote preparation. / 远端准备前仅检查当前真实分析、配方和渲染器。 */
    void validateCapability(DeploymentAgentProposal.Capability request, DeploymentAgentPlan.Part part) throws Exception {
        String snapshot = sources.material(part.id()).snapshot().sha256();
        var facts = requireAnalysis(part, snapshot).report().facts().orElseThrow();
        if (request.name().equals("prepare_tools")) {
            var runtime = DeploymentAgentRecipeRuntime.resolve(facts, part, request.arguments().get("recipe"));
            DeploymentBuildWriterRegistry.defaults().require(facts.projectType(), facts.buildTool());
            cargoLifecycle(facts, runtime, part);
        } else if (request.name().equals("build")) {
            var binding = requireBinding(part, snapshot);
            cargoLifecycle(facts, binding.runtime(), part);
            DeploymentJsonFields.number(request.arguments(), "seconds", 60, 7200);
        } else throw new IllegalArgumentException("Not a shared build capability");
    }

    private Optional<DeploymentAgentCargoArtifactLifecycle> cargoLifecycle(DeploymentProjectFacts facts, DeploymentRuntimeDefinition runtime,
                                                                           DeploymentAgentPlan.Part part) throws java.io.IOException {
        var cargo = DeploymentAgentCargoArtifactLifecycle.forRecipe(facts, runtime);
        if (cargo.isPresent()) {
            cargo.orElseThrow().validateReferences(part);
            cargo.orElseThrow().validateSource(sources.material(part.id()).snapshot().directory(), part.sourceRoot());
        }
        return cargo;
    }

    private Analysis requireAnalysis(DeploymentAgentPlan.Part part, String snapshot) {
        var analysis = analyses.get(part.id());
        if (analysis == null || !analysis.snapshot().equals(snapshot) || !analysis.root().equals(part.sourceRoot())
            || analysis.report().facts().isEmpty() || !analysis.report().rejections().isEmpty())
            throw new IllegalArgumentException("CAPABILITY_NOT_APPLICABLE: current admissible analysis required; run analyze with an exact supported project kind, evaluate its actual facts and rejections; check supplies host facts only");
        return analysis;
    }

    private Tools requireBinding(DeploymentAgentPlan.Part part, String snapshot) {
        var binding = bindings.get(part.id());
        if (binding == null || !binding.snapshot().equals(snapshot) || !binding.root().equals(part.sourceRoot())
            || !binding.runtime().workload().equals(part.runtime().workload()))
            throw new IllegalArgumentException("CAPABILITY_NOT_APPLICABLE: current selected recipe and tools required");
        return binding;
    }

    /** Returns the current verified tool binding or absence. / 返回当前已核实工具绑定或缺省。
     * @param component Selected component identity / 选定组件身份
     * @return checked current result / 已校验当前结果
     */
    DeploymentResolvedToolchains tools(String component) {
        var found = bindings.get(component);
        return found != null && found.snapshot().equals(sources.material(component).snapshot().sha256()) ? found.tools() : new DeploymentResolvedToolchains(DeploymentToolchainCatalog.defaults().revision(), List.of());
    }

    /** Records the exact verified binding used by a known command. / 记录已知命令实际使用的精确核实绑定。
     * @param component component / 组件
     * @param selected executed tools / 执行工具 */
    void used(String component, DeploymentResolvedToolchains selected) {
        var found = bindings.get(component);
        executed.put(component, new Tools(sources.material(component).snapshot().sha256(),
            found == null ? "." : found.root(), found == null ? null : found.runtime(), selected,
            found == null ? Map.of() : found.recipe(), found == null ? Map.of() : found.outputSemantics()));
    }

    /** Reads existing local bindings and known build receipts; it never selects tools or inspects remote files. / 仅读取本地既有绑定和已知构建回执，不选择工具或探测远端文件。 */
    Map<String, Object> state(DeploymentAgentPlan.Part part) {
        String snapshot = sources.material(part.id()).snapshot().sha256();
        var analysis = analyses.get(part.id());
        boolean admissible = analysis != null && analysis.report().facts().isPresent() && analysis.report().rejections().isEmpty();
        var analysisFacts = analysis == null ? Map.<String, Object>of("present", false)
            : Map.<String, Object>ofEntries(Map.entry("present", true), Map.entry("sourceSnapshot", analysis.snapshot()),
            Map.entry("sourceRoot", analysis.root()), Map.entry("sourceCurrent", analysis.snapshot().equals(snapshot)),
            Map.entry("rootCurrent", analysis.root().equals(part.sourceRoot())), Map.entry("admissible", admissible),
            Map.entry("admission", analysis.report().admission().name()),
            Map.entry("projectType", analysis.report().facts().map(facts -> facts.projectType().name()).orElse("")),
            Map.entry("buildTool", analysis.report().facts().map(facts -> facts.buildTool().name()).orElse("")));
        var selected = bindings.get(part.id());
        boolean applicable = admissible && analysis.snapshot().equals(snapshot) && analysis.root().equals(part.sourceRoot())
            && bindingCurrent(selected, part, snapshot);
        var selectedFacts = bindingFacts(selected, part, snapshot);
        if (selected != null) {
            var full = new LinkedHashMap<String, Object>(selectedFacts);
            full.put("parameters", selected.recipe());
            selectedFacts = Collections.unmodifiableMap(full);
        }
        var build = builds.get(part.id());
        var buildFacts = build == null ? Map.<String, Object>of("present", false)
            : new LinkedHashMap<>(bindingFacts(build.binding(), part, snapshot));
        if (build != null) {
            buildFacts.put("exitStatus", build.run().exitStatus());
            buildFacts.put("succeeded", build.run().exitStatus() == 0);
            buildFacts.put("candidate", build.run().workspace().candidateId());
            buildFacts.put("sourceRevision", build.run().sourceRevision());
            buildFacts.put("outputGeneration", build.run().outputGeneration());
            buildFacts.put("outputCurrent", projects.outputCurrent(build.run()));
            buildFacts.put("successfulRunCurrent", bindingCurrent(build.binding(), part, snapshot)
                && projects.outputCurrent(build.run()) && build.run().exitStatus() == 0);
            buildFacts.put("meaning", "Historical recipe result; bindingCurrent alone does not establish current output or seal permission");
        }
        var state = new LinkedHashMap<String, Object>(Map.of("analysis", analysisFacts, "recipeApplicable", applicable, "selectedRecipe", selectedFacts,
            "selectedToolchains", toolFacts(selected), "executedToolchains", toolFacts(executed.get(part.id())),
            "executedBinding", bindingFacts(executed.get(part.id()), part, snapshot), "latestKnownBuild", Map.copyOf(buildFacts)));
        if (applicable && !selected.outputSemantics().isEmpty())
            state.put("recipeOutputSemantics", selected.outputSemantics());
        return Collections.unmodifiableMap(state);
    }

    private static boolean bindingCurrent(Tools value, DeploymentAgentPlan.Part part, String snapshot) {
        return value != null && value.snapshot().equals(snapshot) && value.root().equals(part.sourceRoot())
            && value.runtime() != null && value.runtime().workload().equals(part.runtime().workload());
    }

    private static Map<String, Object> bindingFacts(Tools value, DeploymentAgentPlan.Part part, String snapshot) {
        if (value == null) return Map.of("present", false);
        var facts = new LinkedHashMap<String, Object>(Map.of("present", true, "sourceSnapshot", value.snapshot(), "sourceRoot", value.root(),
            "sourceCurrent", value.snapshot().equals(snapshot), "rootCurrent", value.root().equals(part.sourceRoot()),
            "workloadCurrent", value.runtime() != null && value.runtime().workload().equals(part.runtime().workload()),
            "bindingCurrent", bindingCurrent(value, part, snapshot)));
        return Collections.unmodifiableMap(facts);
    }

    private static Map<String, Object> toolFacts(Tools value) {
        if (value == null) return Map.of("present", false);
        var tools = value.tools();
        return Map.of("present", true, "catalogRevision", tools.catalogRevision(),
            "bindingSha256", DeploymentToolchainBindingCodec.identity(tools), "selectionCount", tools.selections().size(),
            "ecosystems", tools.selections().stream().map(tool -> tool.version().ecosystem().name()).distinct().sorted().toList());
    }

    /** Returns the actual current-source execution binding. / 返回实际当前来源执行绑定。
     * @param component component / 组件
     * @return executed tools / 实际执行工具 */
    DeploymentResolvedToolchains executed(String component) {
        var found = executed.get(component);
        return found != null && found.snapshot().equals(sources.material(component).snapshot().sha256()) ? found.tools()
            : new DeploymentResolvedToolchains(DeploymentToolchainCatalog.defaults().revision(), List.of());
    }

    /** Encodes the exact product-selected binding for the execution guard. / 编码产品选定精确绑定以供执行守卫。
     * @param tools verified actual tool binding / 已核实实际工具绑定
     * @return checked current result / 已校验当前结果
     */
    static String encoded(DeploymentResolvedToolchains tools) {
        return tools.selections().isEmpty() ? "-" : Base64.getEncoder().encodeToString(DeploymentToolchainBindingCodec.encode(tools).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
