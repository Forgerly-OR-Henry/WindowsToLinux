package gold.debug.windowstolinux.app.service.deployment.automatic;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.definition.*;
import gold.debug.windowstolinux.app.service.contract.definition.MultiComponentReviewInput;
import gold.debug.windowstolinux.app.service.deployment.single.DeploymentHandoff;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.source.*;
import gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.git.GitSnapshot;
import gold.debug.windowstolinux.shared.model.agent.AgentAction;
import gold.debug.windowstolinux.shared.model.agent.AgentToolType;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;
import gold.debug.windowstolinux.shared.model.health.*;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.*;
import gold.debug.windowstolinux.shared.model.project.component.ComponentIsolationSpecification;
import gold.debug.windowstolinux.shared.source.snapshot.SourceDirectorySnapshot;
import gold.debug.windowstolinux.shared.standard.analyze.component.*;
import gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedDeploymentAdvisor;
import gold.debug.windowstolinux.shared.standard.deploy.contract.AutomaticDatabasePreparation;
import gold.debug.windowstolinux.shared.standard.deploy.input.AutomaticRuntimeResolver;
import gold.debug.windowstolinux.shared.standard.deploy.input.DeploymentRuntimeParser;

/**
 * Executes one click-time operation, pausing only for missing inputs and concrete risk decisions. / 执行一次点击触发的操作，仅因缺失输入或具体风险决策暂停。
 */
public final class AutomaticDeploymentUseCase {
    /**
     * Bound automatic deployment application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的自动部署应用门面协作对象。
     */
    private final AutomaticDeploymentApplicationFacade service;

    /**
     * Source identity or content read by the operation.
     * <p>操作读取的源身份或内容。
     */
    private final SourcePreparationUseCase source;

    /**
     * Shared operation locks indexed by target identity.
     * <p>按目标身份索引的共享操作锁。
     */
    private final ServerOperationLockRegistry locks;

    /**
     * Bound automatic runtime resolver collaborator for reviewed language, process and health specification.
     * <p>处理已审阅的语言、进程及健康规格的自动运行时解析器协作对象。
     */
    private final AutomaticRuntimeResolver runtime = new AutomaticRuntimeResolver();

    /** Optional fixed-checkpoint advisor for assisted mode. / AI 辅助 模式的可选固定节点建议器。 */
    private final AssistedDeploymentAdvisor assistance;

    /** Execution boundary for assisted deployment. / 辅助部署执行边界。 */
    private final AssistedDeploymentBoundary boundary;

    /** Actual nonsecret server observations for preflight advice. / 用于预检建议的实际非秘密服务器观察。 */
    private Map<String, String> serverFacts = Map.of();

    /** Last known transaction failure, used only after verified recovery. / 最近已知事务失败，仅在验证恢复后使用。 */
    private Map<String, String> failureEvidence = Map.of();

    /** Runtime fields corrected explicitly by the user. / 用户明确修正过的运行字段。 */
    private final Set<String> correctedByUser = new HashSet<>();

    /** Successfully prepared databases are not recreated during a retry. / 重试期间不重新创建已成功准备的数据库。 */
    private final Map<String, AutomaticDatabasePreparation> preparedDatabases = new LinkedHashMap<>();

    /** Completed normal environment preparation. / 已完成的常规环境准备。 */
    private boolean environmentPrepared;

    /**
     * Binds the existing reviewed operations and shared server lock. / 绑定既有审阅操作和共享服务器锁。
     *
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param locks shared operation locks indexed by target identity / 按目标身份索引的共享操作锁
     */
    public AutomaticDeploymentUseCase(AutomaticDeploymentApplicationFacade service, SourcePreparationUseCase source,
            ServerOperationLockRegistry locks) {
        this(service, source, locks, null, null);
    }

    /** Binds one task's mode-specific policy to the existing deterministic workflow. / 将一个任务的模式策略绑定至既有确定性流程。
     * @param service existing deployment ports / 既有部署端口
     * @param source source preparation / 源码准备
     * @param locks server operation exclusion / 服务器操作互斥
     * @param assistance fixed-checkpoint advisor / 固定节点建议器
     * @param boundary assisted execution boundary / 辅助执行边界
     */
    public AutomaticDeploymentUseCase(AutomaticDeploymentApplicationFacade service, SourcePreparationUseCase source,
            ServerOperationLockRegistry locks, AssistedDeploymentAdvisor assistance,
            AssistedDeploymentBoundary boundary) {
        this.service = service;
        this.source = source;
        this.locks = locks;
        this.assistance = assistance;
        this.boundary = boundary;
    }

    /**
     * Freezes the source, resolves its runtime, and executes a single reviewed transaction. / 冻结源码、解析运行时并执行一次已审阅事务。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param progress progress / 进度
     * @return constructed or resolved automatic deployment outcome / 构造或解析得到的自动部署结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public AutomaticDeploymentOutcome deploy(AutomaticDeploymentRequest request, char[] master,
            AutomaticDeploymentInteraction interaction, Predicate<String> fingerprint,
            Consumer<LocalizedMessage> progress) throws Exception {
        if (request.automationMode() == DeploymentAutomationMode.AGENT)
            throw new SecurityException("autonomous mode cannot use standard analysis");
        if (request.automationMode() == DeploymentAutomationMode.ASSISTED && assistance == null)
            throw new IllegalStateException("assisted deployment requires checkpoint advisor");
        var lock = locks.forServer(request.server().id());
        if (!lock.tryLock()) {
            Arrays.fill(master, '\0');
            throw new IllegalStateException("server already has an active operation");
        }
        try {
            progress.accept(LocalizedMessage.of("auto.progress.snapshot"));
            if (request.git().isPresent()) {
                GitSnapshot git = source.snapshotGit(request.git().orElseThrow());
                try (SourceDirectorySnapshot snapshot = source.snapshot(git)) {
                    return execute(request, snapshot.directory(), Optional.of(git), master, interaction, fingerprint,
                            progress);
                }
            }
            try (SourceDirectorySnapshot snapshot = source.snapshot(request.directory().orElseThrow())) {
                return execute(request, snapshot.directory(), Optional.empty(), master, interaction, fingerprint,
                        progress);
            }
        } finally {
            Arrays.fill(master, '\0');
            lock.unlock();
        }
    }

    /**
     * Executes automatic deployment outcome.
     * <p>执行自动部署结果。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param git Git source / Git 源码
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param progress progress / 进度
     * @return constructed or resolved automatic deployment outcome / 构造或解析得到的自动部署结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private AutomaticDeploymentOutcome execute(AutomaticDeploymentRequest request, Path root, Optional<GitSnapshot> git,
            char[] master, AutomaticDeploymentInteraction interaction, Predicate<String> fingerprint,
            Consumer<LocalizedMessage> progress) throws Exception {
        if (boundary != null)
            boundary.freeze(root);
        if (assistance != null)
            assistance.source(new gold.debug.windowstolinux.shared.source.browse.SourceBrowser(root));
        var resolved = operation(AgentToolType.ANALYZE_SOURCE, Map.of("effect",
                "Analyze private source snapshot; request unresolved nonsecret inputs; never modify original source"),
                () -> resolveInputs(request, root, master, interaction, progress));
        var sourceFacts = Map.of("components",
                String.join(",", resolved.discovered().stream().map(DiscoveredProjectComponent::id).toList()),
                "sourceRevisions",
                resolved.preparations().values().stream().map(p -> p.archive().orElseThrow().contentSha256()).sorted()
                        .toList().toString(),
                "validatedInputsDigest", AgentAction.digest(new TreeMap<>(resolved.inputs()).toString()), "healthOwner",
                resolved.healthOwner());
        if (boundary != null)
            sourceFacts.forEach(boundary::fact);
        var discovered = resolved.discovered();
        var preparations = resolved.preparations();
        var databaseAssessments = resolved.databases();
        var inputs = resolved.inputs();
        progress.accept(LocalizedMessage.of("auto.progress.server"));
        var capabilities = operation(AgentToolType.VERIFY_SERVER,
                Map.of("effect", "Read host identity and supported deployment capabilities"),
                () -> service.verifyServer(request.server(), request.server().credentialMode(), master.clone(),
                        fingerprint));
        if (capabilities != null)
            serverFacts = Map.of("observedOs", capabilities.operatingSystem(), "observedArchitecture",
                    capabilities.architecture(), "helperVersion",
                    Integer.toString(capabilities.managedHelperProtocolVersion()), "availableBytes",
                    Long.toString(capabilities.availableBytes()));
        if (boundary != null) {
            serverFacts.forEach(boundary::fact);
            boundary.register("server-capabilities", AgentToolType.VERIFY_SERVER,
                    Map.of("effect", "Read current server deployment capabilities"), () -> true, () -> {
                        var observed = service.verifyServer(request.server(), request.server().credentialMode(),
                                master.clone(), fingerprint);
                        return new gold.debug.windowstolinux.shared.model.agent.AgentObservation(true, true,
                                Map.of("operatingSystem", observed.operatingSystem(), "architecture",
                                        observed.architecture(), "availableBytes",
                                        Long.toString(observed.availableBytes())));
                    });
            boundary.register("environment-preparation", AgentToolType.PREPARE_ENVIRONMENT, Map.of("effect",
                    "Recheck and prepare existing supported dependencies and helper; no unrelated packages or arbitrary shell"),
                    () -> true, () -> {
                        var before = service.verifyServer(request.server(), request.server().credentialMode(),
                                master.clone(), fingerprint);
                        prepareEnvironmentOnce(request, master, fingerprint, interaction);
                        var after = service.verifyServer(request.server(), request.server().credentialMode(),
                                master.clone(), fingerprint);
                        boolean changed = environmentImproved(before, after);
                        return new gold.debug.windowstolinux.shared.model.agent.AgentObservation(true, true,
                                Map.of("environment", "prepared", "changed", Boolean.toString(changed)));
                    });
        }
        var server = service.findTrustedServer(request.server().id()).orElseThrow();
        var tried = new HashSet<String>();
        for (int attempt = 0;; attempt++) {
            String inputRevision = AgentAction.digest(new TreeMap<>(resolved.inputs()).toString());
            if (boundary != null)
                boundary.fact("validatedInputsDigest", inputRevision);
            var outcome = discovered.size() == 1
                    ? deploySingle(request, root, git, master, interaction, fingerprint, progress, resolved, server)
                    : deployMultiple(request, root, git, master, interaction, fingerprint, progress, resolved, server);
            if (outcome.status() == DeploymentStatus.SUCCEEDED
                    || outcome.status() == DeploymentStatus.MANUAL_RECOVERY_REQUIRED || assistance == null
                    || attempt >= 2 || !assistance.canRecover())
                return outcome;
            String failureBinding = inputRevision + ":" + new TreeMap<>(failureEvidence);
            if (!tried.add(failureBinding))
                return outcome;
            failureEvidence.forEach((key, value) -> assistance.fact("failure/" + key, value));
            assistance.check("FAILURE", failureEvidence);
            if (!assistance.canRecover())
                return outcome;
            boolean corrected = correctFailedInputs(request, root, resolved, interaction, progress);
            boolean recovered = !corrected && assistance.canRecover() && boundary != null
                    && boundary.recover(failureEvidence);
            if (!corrected && !recovered)
                return outcome;
            var observed = service.verifyServer(request.server(), request.server().credentialMode(), master.clone(),
                    fingerprint);
            if (observed != null)
                serverFacts = Map.of("observedOs", observed.operatingSystem(), "observedArchitecture",
                        observed.architecture(), "helperVersion",
                        Integer.toString(observed.managedHelperProtocolVersion()), "availableBytes",
                        Long.toString(observed.availableBytes()));
            progress.accept(LocalizedMessage.of("deployment.assisted.retry", "attempt", attempt + 1));
        }
    }

    /** Requires an installed capability; incidental disk or diagnostic changes do not authorize replay. / 要求已安装能力发生改善，偶发磁盘或诊断变化不授权重试。
     * @param before capabilities observed before preparation / 准备前观察的能力
     * @param after capabilities observed after preparation / 准备后观察的能力
     * @return whether a supported dependency became available / 受支持依赖是否变为可用
     */
    static boolean environmentImproved(gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts before,
            gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts after) {
        if (before == null || after == null || !before.operatingSystem().equals(after.operatingSystem())
                || !before.architecture().equals(after.architecture()))
            return false;
        return !before.systemdAvailable() && after.systemdAvailable()
                || !before.java21Available() && after.java21Available()
                || !before.mavenAvailable() && after.mavenAvailable() || !before.tarAvailable() && after.tarAvailable()
                || !before.curlAvailable() && after.curlAvailable()
                || !before.socketInspectionAvailable() && after.socketInspectionAvailable()
                || !before.buildLimitToolsAvailable() && after.buildLimitToolsAvailable()
                || !before.nonInteractiveSudoAvailable() && after.nonInteractiveSudoAvailable()
                || before.managedHelperProtocolVersion() != after.managedHelperProtocolVersion() && after
                        .managedHelperProtocolVersion() == gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion.CURRENT;
    }

    /** Applies evidenced technical corrections and revalidates the existing component graph. / 应用有证据的技术修正并重新校验既有组件图。
     * @param request frozen explicit user inputs / 冻结显式用户输入
     * @param root frozen source root / 冻结源码根目录
     * @param resolved existing reviewed inputs / 既有审阅输入
     * @param interaction missing business input / 缺失业务输入
     * @param progress visible changes / 可见变更
     * @return whether a validated input actually changed / 已校验输入是否实际变化
     * @throws Exception when source or domain validation fails / 源码或领域校验失败时
     */
    private boolean correctFailedInputs(AutomaticDeploymentRequest request, Path root, ResolvedInputs resolved,
            AutomaticDeploymentInteraction interaction, Consumer<LocalizedMessage> progress) throws Exception {
        var fields = new ArrayList<DeploymentInputField>();
        for (var component : resolved.discovered()) {
            var values = resolved.inputs().get(component.id());
            values.forEach((key, value) -> {
                if (gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedParameterPolicy.allows(key))
                    assistance.fact(component.id() + "/current/" + key, value);
            });
            fields.addAll(runtime.corrections(component.id(), values).stream()
                    .filter(field -> gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedParameterPolicy
                            .allows(field.id()))
                    .filter(field -> !field.id().endsWith("/healthMode") && !field.id().endsWith("/expectedStatus"))
                    .toList());
        }
        var proposals = assistance.suggest(fields);
        boolean changed = false;
        for (var entry : proposals.entrySet()) {
            String[] parts = entry.getKey().split("/", 2);
            var values = resolved.inputs().get(parts[0]);
            if (values == null || Objects.equals(values.get(parts[1]), entry.getValue()))
                continue;
            if (request.overrides().containsKey(entry.getKey()) || request.overrides().containsKey(parts[1])
                    || assistance.humanSupplied(entry.getKey()) || correctedByUser.contains(entry.getKey())) {
                var field = fields.stream().filter(value -> value.id().equals(entry.getKey())).findFirst()
                        .orElseThrow();
                var answer = gold.debug.windowstolinux.shared.standard.deploy.input.DeploymentInputAnswers
                        .ask(List.of(field), interaction);
                correctedByUser.addAll(answer.keySet());
                if (!Objects.equals(values.get(parts[1]), answer.get(entry.getKey()))) {
                    values.put(parts[1], answer.get(entry.getKey()));
                    changed = true;
                }
            } else {
                values.put(parts[1], entry.getValue());
                changed = true;
            }
        }
        if (!changed)
            return false;
        for (var component : resolved.discovered()) {
            var prepared = source.prepareAutomatic(root.resolve(component.relativeRoot()),
                    DeploymentProjectType.valueOf(resolved.inputs().get(component.id()).get("type")));
            if (prepared.assessment().facts().isEmpty()
                    || prepared.assessment().facts().orElseThrow().missingInformation().stream()
                            .anyMatch(message -> !message.key().equals("analysis.db.reviewRequired"))
                    || !prepared.assessment().facts().orElseThrow().conflicts().isEmpty())
                throw new IllegalArgumentException("corrected source is not admitted");
            resolved.preparations().put(component.id(), prepared);
        }
        validateInputs(resolved.inputs(), resolved.preparations(), resolved.databases(), request.server().host(),
                interaction, progress);
        return true;
    }

    /**
     * Carries reviewed deployment inputs and the secret references resolved for them.
     * <p>携带已审阅部署输入及为其解析的秘密引用。
     *
     * @param discovered discovered / 已发现
     * @param preparations preparations / 准备集合
     * @param databases databases / 数据库集合
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param healthOwner health owner / 健康所有者
     */
    private record ResolvedInputs(List<DiscoveredProjectComponent> discovered,
            Map<String, ReviewedSourcePreparation> preparations,
            Map<String, gold.debug.windowstolinux.shared.standard.analyze.ecosystem.db.DatabaseProjectInspector.Assessment> databases,
            Map<String, Map<String, String>> inputs, String healthOwner) {
    }

    /**
     * Combines deterministic source facts, explicit overrides and requested user input into complete reviewed runtime inputs.
     * <p>将确定性源码事实、显式覆盖项及请求的用户输入组合为完整已审阅运行输入。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param progress progress / 进度
     * @return constructed or resolved resolved inputs / 构造或解析得到的已解析输入集合
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private ResolvedInputs resolveInputs(AutomaticDeploymentRequest request, Path root, char[] master,
            AutomaticDeploymentInteraction interaction, Consumer<LocalizedMessage> progress) throws Exception {
        progress.accept(LocalizedMessage.of("auto.progress.discovery"));
        List<DiscoveredProjectComponent> discovered = new ProjectComponentDiscovery().discover(root);
        if (assistance != null)
            for (var component : discovered)
                assistance.fact("component/" + component.id(),
                        component.relativeRoot().toString().replace('\\', '/') + " types=" + component.types());
        Map<String, String> selection = new LinkedHashMap<>();
        List<DeploymentInputField> questions = new ArrayList<>();
        for (var component : discovered) {
            List<String> types = component.types().stream().map(Enum::name).toList();
            String override = request.overrides().getOrDefault(component.id() + "/type",
                    discovered.size() == 1 ? request.overrides().getOrDefault("type", "") : "");
            if (!override.isBlank())
                selection.put(component.id() + "/type", override);
            else if (types.size() == 1)
                selection.put(component.id() + "/type", types.getFirst());
            else
                questions.add(AutomaticRuntimeResolver.field(component.id(), "type", "", types));
        }
        answerWithAi(questions, selection, interaction, master, request.automationMode());
        Map<String, ReviewedSourcePreparation> preparations = new LinkedHashMap<>();
        Map<String, gold.debug.windowstolinux.shared.standard.analyze.ecosystem.db.DatabaseProjectInspector.Assessment> databaseAssessments = new LinkedHashMap<>();
        Map<String, Map<String, String>> inputs = new LinkedHashMap<>();
        questions.clear();
        for (var component : discovered) {
            DeploymentProjectType type = DeploymentProjectType.valueOf(selection.get(component.id() + "/type"));
            ReviewedSourcePreparation prepared = source.prepareAutomatic(root.resolve(component.relativeRoot()), type);
            if (prepared.assessment().facts().isEmpty()
                    || prepared.assessment().facts().orElseThrow().missingInformation().stream()
                            .anyMatch(message -> !message.key().equals("analysis.db.reviewRequired"))
                    || !prepared.assessment().facts().orElseThrow().conflicts().isEmpty()) {
                if (assistance != null)
                    assistance.explainRejection(Map.of("component", component.id(), "rejection",
                            prepared.assessment().rejections().toString()));
                throw new IllegalArgumentException("source cannot be deployed: "
                        + prepared.assessment().rejections().stream().map(value -> value.code()).toList());
            }
            preparations.put(component.id(), prepared);
            databaseAssessments.put(component.id(),
                    new gold.debug.windowstolinux.shared.standard.analyze.ecosystem.db.DatabaseProjectInspector()
                            .inspect(root.resolve(component.relativeRoot())));
            Map<String, String> values = runtime.defaults(type, prepared.assessment().runtimeSuggestion().orElse(null),
                    root.resolve(component.relativeRoot()));
            request.overrides().forEach((key, value) -> {
                if (!value.isBlank() && !key.contains("/"))
                    values.put(key, value);
                if (key.startsWith(component.id() + "/") && !value.isBlank())
                    values.put(key.substring(component.id().length() + 1), value);
            });
            values.put("type", type.name());
            inputs.put(component.id(), values);
            if (assistance != null)
                values.forEach((key, value) -> {
                    if (gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedParameterPolicy.allows(key))
                        assistance.fact(component.id() + "/current/" + key, value);
                });
            questions.addAll(runtime.missing(component.id(), type, values));
            if (discovered.size() > 1 && !values.containsKey("dependencies"))
                questions.add(AutomaticRuntimeResolver.field(component.id(), "dependencies", "", List.of()));
        }
        Map<String, String> answers = new LinkedHashMap<>();
        answerWithAi(questions, answers, interaction, master, request.automationMode());
        answers.forEach((key, value) -> {
            String[] parts = key.split("/", 2);
            inputs.get(parts[0]).put(parts[1], value);
        });
        validateInputs(inputs, preparations, databaseAssessments, request.server().host(), interaction, progress);
        String healthOwner = discovered.size() == 1
                ? discovered.getFirst().id()
                : healthOwner(discovered.stream().map(DiscoveredProjectComponent::id).toList(), inputs, interaction);
        for (var component : discovered) {
            var assessment = databaseAssessments.get(component.id());
            var values = inputs.get(component.id());
            if (values.get("type").equals("DOCKERFILE_CONTAINER")) {
                assessment = gold.debug.windowstolinux.shared.standard.analyze.ecosystem.db.DatabaseProjectInspector
                        .containerStorage(assessment);
                databaseAssessments.put(component.id(), assessment);
            }
            if ((!assessment.databases().isEmpty() || assessment.unknownDatabase())
                    && !values.containsKey("databaseMode")) {
                if (assessment.endpointConfirmationRequired() && !interaction.confirm("db.nativeEndpoint", Map.of()))
                    throw new CancellationException();
                databaseAssessments.put(component.id(),
                        service.completeAutomaticDatabaseInputs(root.resolve(component.relativeRoot()),
                                preparations.get(component.id()).assessment().facts().orElseThrow().applicationId(),
                                assessment, master.clone(), interaction));
            }
        }
        return new ResolvedInputs(discovered, preparations, databaseAssessments, inputs, healthOwner);
    }

    /**
     * Validates reviewed non-secret deployment input fields and rejects inputs outside the declared constraints.
     * <p>校验已审阅的非秘密部署输入字段并拒绝超出已声明约束的输入。
     *
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param preparations preparations / 准备集合
     * @param databases databases / 数据库集合
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param progress progress / 进度
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private void validateInputs(Map<String, Map<String, String>> inputs,
            Map<String, ReviewedSourcePreparation> preparations,
            Map<String, gold.debug.windowstolinux.shared.standard.analyze.ecosystem.db.DatabaseProjectInspector.Assessment> databases,
            String host, AutomaticDeploymentInteraction interaction, Consumer<LocalizedMessage> progress) {
        for (var entry : inputs.entrySet()) {
            Map<String, String> values = entry.getValue();
            while (true) {
                if (Thread.currentThread().isInterrupted())
                    throw new CancellationException();
                try {
                    if (values.getOrDefault("databaseMode", "").equals("UNREVIEWED"))
                        values.remove("databaseMode");
                    var assessment = databases.get(entry.getKey());
                    String mode = values.get("databaseMode");
                    if (mode != null && (!assessment.databases().isEmpty() || assessment.unknownDatabase())
                            && (mode.equals("NONE") || assessment.databases().size() > 1 || assessment.databases()
                                    .stream().anyMatch(database -> !database.engine().name().equals(mode))))
                        throw new IllegalArgumentException(
                                "explicit database scope conflicts with source declarations");
                    runtime.runtime(DeploymentProjectType.valueOf(values.get("type")), values);
                    runtime.access(values, host);
                    configuration(preparations.get(entry.getKey()).assessment().facts().orElseThrow().applicationId(),
                            values);
                    secrets(values, AutomaticDatabasePreparation.empty());
                    bindings(values, AutomaticDatabasePreparation.empty());
                    limits(values);
                    break;
                } catch (IllegalArgumentException invalid) {
                    var previous = Map.copyOf(values);
                    progress.accept(LocalizedMessage.of("auto.progress.invalid", "component", entry.getKey()));
                    Map<String, String> corrected = new LinkedHashMap<>();
                    answer(runtime.corrections(entry.getKey(), values), corrected, interaction);
                    correctedByUser.addAll(corrected.keySet());
                    corrected.forEach((key, value) -> values.put(key.split("/", 2)[1], value));
                    for (String key : List.of("healthEndpoint", "accessUrl", "configuration", "secrets",
                            "databaseDetails", "jvmArguments", "arguments", "volumes")) {
                        if (values.getOrDefault(key, "").isBlank())
                            values.remove(key);
                    }
                    if (values.equals(previous))
                        throw invalid;
                }
            }
            var storageFacts = preparations.get(entry.getKey()).assessment().facts().orElseThrow();
            gold.debug.windowstolinux.shared.standard.deploy.input.ManagedStoragePreparation.prepare(
                    storageFacts.sourceRoot(), storageFacts.applicationId(),
                    configuration(storageFacts.applicationId(), values),
                    runtime.runtime(DeploymentProjectType.valueOf(values.get("type")), values), List.of());
            if (preparations.get(entry.getKey()).assessment().facts().orElseThrow().support()
                    .level() == DeploymentSupportLevel.EXPERIMENTAL_ADAPTER
                    && !Boolean.parseBoolean(values.getOrDefault("experimentalAdapterRisk", "false"))
                    && boundary == null
                    && !interaction.confirm("auto.risk.experimental", Map.of("component", entry.getKey())))
                throw new CancellationException();
            if (values.get("type").equals("DOCKERFILE_CONTAINER")
                    && values.getOrDefault("containerEngine", "PODMAN").equals("DOCKER") && boundary == null
                    && !interaction.confirm("auto.risk.docker", Map.of("component", entry.getKey())))
                throw new CancellationException();
        }
    }

    /**
     * Prepares and reviews one component, then delegates its deployment and state recording through the existing transaction flow.
     * <p>准备并审阅一个组件，随后通过既有事务流程委派部署及状态记录。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param git Git source / Git 源码
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param progress progress / 进度
     * @param resolved resolved / 已解析
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @return constructed or resolved automatic deployment outcome / 构造或解析得到的自动部署结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private AutomaticDeploymentOutcome deploySingle(AutomaticDeploymentRequest request, Path root,
            Optional<GitSnapshot> git, char[] master, AutomaticDeploymentInteraction interaction,
            Predicate<String> fingerprint, Consumer<LocalizedMessage> progress, ResolvedInputs resolved,
            gold.debug.windowstolinux.shared.model.server.ServerIdentity server) throws Exception {
        var discovered = resolved.discovered();
        var preparations = resolved.preparations();
        var databaseAssessments = resolved.databases();
        var inputs = resolved.inputs();
        String component = discovered.getFirst().id();
        var prepared = preparations.get(component);
        Map<String, String> values = inputs.get(component);
        String id = prepared.assessment().facts().orElseThrow().applicationId();
        var config = configuration(id, values);
        // Validate local inputs before preparing any database or environment. / 在准备任何数据库或环境前验证本地输入。
        runtime.access(values, server.host());
        limits(values);
        var db = prepareDatabases(root.resolve(discovered.getFirst().relativeRoot()), id,
                databaseAssessments.get(component), request, values, master, interaction, fingerprint, progress);
        if (db.schemaReview().isPresent())
            prepared = source.prepareWithDatabaseReview(root.resolve(discovered.getFirst().relativeRoot()),
                    DeploymentProjectType.valueOf(values.get("type")), db.schemaReview().orElseThrow());
        prepared = withGit(prepared, git);
        config = withDatabases(config, db);
        var reviewed = service.createReviewedDeploymentRequest(prepared, server, config, secrets(values, db),
                bindings(values, db), runtime.runtime(DeploymentProjectType.valueOf(values.get("type")), values),
                runtime.access(values, server.host()), limits(values), false, true, true);
        service.planDeployment(reviewed);
        if (boundary != null
                && service instanceof gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade managed)
            AssistedManagedToolCatalog.register(boundary, managed, request.server(), Set.of(id), master);
        service.saveDeploymentConfigurationSnapshot(reviewed.configuration());
        var description = AutomaticActionDescription.deployment(reviewed);
        var preflightEvidence = new LinkedHashMap<>(preparationEvidence(resolved));
        preflightEvidence.putAll(description);
        preflight(preflightEvidence);
        progress.accept(LocalizedMessage.of("auto.progress.environment"));
        if (db.bindings().isEmpty())
            prepareEnvironment(request, master, fingerprint, interaction);
        progress.accept(LocalizedMessage.of("auto.progress.deploy"));
        var outcome = operation(AgentToolType.DEPLOY_TRANSACTION, description, () -> service
                .deployAutomaticallyReviewed(reviewed, request.server(), master.clone(), fingerprint, progress));
        failureEvidence = DeploymentDiagnosticEvidence.from(outcome);
        return new AutomaticDeploymentOutcome(id, outcome.status(),
                outcome.handoff().map(value -> Map.of(component, value)).orElse(Map.of()));
    }

    /**
     * Prepares the discovered component graph, resolves its reviewed inputs and executes dependency-ordered whole-application deployment.
     * <p>准备已发现组件图、解析已审阅输入，并执行按依赖排序的整应用部署。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param git Git source / Git 源码
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param progress progress / 进度
     * @param resolved resolved / 已解析
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @return constructed or resolved automatic deployment outcome / 构造或解析得到的自动部署结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private AutomaticDeploymentOutcome deployMultiple(AutomaticDeploymentRequest request, Path root,
            Optional<GitSnapshot> git, char[] master, AutomaticDeploymentInteraction interaction,
            Predicate<String> fingerprint, Consumer<LocalizedMessage> progress, ResolvedInputs resolved,
            gold.debug.windowstolinux.shared.model.server.ServerIdentity server) throws Exception {
        var discovered = resolved.discovered();
        var preparations = resolved.preparations();
        var databaseAssessments = resolved.databases();
        var inputs = resolved.inputs();
        String applicationId = request.directory().map(path -> path.getFileName().toString()).orElseGet(() -> {
            String path = request.git().orElseThrow().remote().location().getPath();
            return path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.git$", "");
        }).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("application name must be a bounded identifier");
        List<ComponentAnalysisRequest> components = new ArrayList<>();
        for (var component : discovered) {
            var values = inputs.get(component.id());
            DeploymentRuntimeSpecification specification = runtime
                    .runtime(DeploymentProjectType.valueOf(values.get("type")), values);
            String path = component.relativeRoot().toString().replace('\\', '/');
            Set<String> dependencies = new LinkedHashSet<>();
            for (String dependency : values.getOrDefault("dependencies", "").split("[,;\\s]+"))
                if (!dependency.isBlank())
                    dependencies.add(dependency);
            components.add(new ComponentAnalysisRequest(component.id(), path.isBlank() ? "." : path,
                    specification.projectType(), Optional.of(specification),
                    runtime.artifacts(component.relativeRoot(),
                            preparations.get(component.id()).assessment().facts().orElseThrow(), values),
                    specification.workload().endpoints().stream().map(endpoint -> endpoint.hostPort())
                            .collect(java.util.stream.Collectors.toSet()),
                    List.of(), List.of(), List.of(), dependencies, true, ComponentIsolationSpecification.managed()));
        }
        var graph = new MixedProjectInspector().analyzeAutomatic(root, applicationId, components);
        if (graph.issues().stream().anyMatch(issue -> !issue.code().equals("COMPONENT_REQUIRES_INPUT")))
            throw new IllegalArgumentException("component graph requires correction: "
                    + graph.issues().stream().map(issue -> issue.code()).toList());
        String healthOwner = resolved.healthOwner();
        Map<String, AutomaticDatabasePreparation> databases = new LinkedHashMap<>();
        Map<String, gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview> databaseReviews = new LinkedHashMap<>();
        for (var component : graph.components()) {
            var db = prepareDatabases(component.sourceRoot(), component.facts().applicationId(),
                    databaseAssessments.get(component.componentId()), request, inputs.get(component.componentId()),
                    master, interaction, fingerprint, progress);
            databases.put(component.componentId(), db);
            db.schemaReview().ifPresent(review -> databaseReviews.put(component.componentId(), review));
        }
        var prepared = source.prepareMultiComponent(root, applicationId, components, databaseReviews);
        if (prepared.components().isEmpty())
            throw new IllegalArgumentException("component graph requires correction");
        if (git.isPresent()) {
            Map<String, PreparedComponentSource> pinned = new LinkedHashMap<>();
            prepared.components().forEach((id, value) -> pinned.put(id,
                    new PreparedComponentSource(id, value.facts(), value.archive(),
                            new SourceRevision(value.archive().contentSha256(), Optional.of(git.orElseThrow().commit()),
                                    Optional.of(git.orElseThrow().remote().location()), Map.of()),
                            value.excludedEntries())));
            prepared = new PreparedMultiComponentSource(prepared.assessment(), pinned);
        }
        List<MultiComponentReviewInput> reviews = new ArrayList<>();
        for (var component : prepared.assessment().components()) {
            var values = inputs.get(component.componentId());
            var db = databases.get(component.componentId());
            var config = withDatabases(configuration(component.facts().applicationId(), values), db);
            reviews.add(new MultiComponentReviewInput(component.componentId(), config, secrets(values, db),
                    bindings(values, db), runtime.access(values, server.host()), limits(values), true, true));
        }
        var reviewed = service.createReviewedMultiComponentApplication(prepared, server, reviews,
                new ApplicationHealthGate(healthOwner, runtime.health(inputs.get(healthOwner))));
        if (boundary != null
                && service instanceof gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade managed)
            AssistedManagedToolCatalog.register(boundary, managed, request.server(), reviewed.components().stream()
                    .map(c -> c.application().id()).collect(java.util.stream.Collectors.toSet()), master);
        for (var component : reviewed.components()) {
            service.saveDeploymentConfigurationSnapshot(component.request().configuration());
        }
        var description = AutomaticActionDescription.deployment(reviewed);
        var preflightEvidence = new LinkedHashMap<>(preparationEvidence(resolved));
        preflightEvidence.putAll(description);
        preflight(preflightEvidence);
        progress.accept(LocalizedMessage.of("auto.progress.environment"));
        prepareEnvironment(request, master, fingerprint, interaction);
        progress.accept(LocalizedMessage.of("auto.progress.deploy"));
        var result = operation(AgentToolType.DEPLOY_TRANSACTION, description, () -> service
                .deployAutomaticallyReviewed(reviewed, request.server(), master.clone(), fingerprint, progress));
        failureEvidence = DeploymentDiagnosticEvidence.from(result);
        Map<String, DeploymentHandoff> handoffs = new LinkedHashMap<>();
        if (result.status() == DeploymentStatus.SUCCEEDED)
            for (var component : reviewed.components()) {
                var access = component.request().userAccessUrl();
                handoffs.put(component.componentId(),
                        new DeploymentHandoff.ApplicationEntry(
                                gold.debug.windowstolinux.shared.model.managed.ApplicationUsage
                                        .from(component.application(), component.request().runtime().workload())));
            }
        return new AutomaticDeploymentOutcome(applicationId, result.status(), handoffs);
    }

    /**
     * Records the answer for automatic deployment.
     * <p>记录以下交互的回答：自动部署。
     *
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     */
    private static void answer(List<DeploymentInputField> fields, Map<String, String> values,
            AutomaticDeploymentInteraction interaction) {
        values.putAll(
                gold.debug.windowstolinux.shared.standard.deploy.input.DeploymentInputAnswers.ask(fields, interaction));
    }

    /**
     * Chooses the application health owner from reviewed HTTP components and explicit interaction when needed.
     * <p>根据已审阅 HTTP 组件选择整应用健康检查所属组件，必要时通过显式交互选择。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return health owner text / 健康所有者文本
     */
    private String healthOwner(List<String> components, Map<String, Map<String, String>> inputs,
            AutomaticDeploymentInteraction interaction) {
        List<String> owners = components.stream().filter(id -> inputs.get(id).get("healthMode").equals("HTTP"))
                .toList();
        if (owners.size() == 1)
            return owners.getFirst();
        var field = AutomaticRuntimeResolver.field("application", "healthOwner", "", components);
        return (assistance == null
                ? gold.debug.windowstolinux.shared.standard.deploy.input.DeploymentInputAnswers.ask(List.of(field),
                        interaction)
                : assistance.complete(List.of(field))).get(field.id());
    }

    /**
     * Prepares databases.
     * <p>准备数据库集合。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param assessment the typed static assessment / 类型化静态评估
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param progress progress / 进度
     * @return constructed or resolved automatic database preparation / 构造或解析得到的自动数据库准备
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private AutomaticDatabasePreparation prepareDatabases(Path root, String id,
            gold.debug.windowstolinux.shared.standard.analyze.ecosystem.db.DatabaseProjectInspector.Assessment assessment,
            AutomaticDeploymentRequest request, Map<String, String> values, char[] master,
            AutomaticDeploymentInteraction interaction, Predicate<String> fingerprint,
            Consumer<LocalizedMessage> progress) throws Exception {
        if (preparedDatabases.containsKey(id))
            return preparedDatabases.get(id);
        if (assessment.databases().isEmpty() && !assessment.unknownDatabase())
            return AutomaticDatabasePreparation.empty();
        if (!values.getOrDefault("databaseMode", "NONE").equals("NONE")) {
            if (assessment.schemaReviewRequired())
                throw new IllegalArgumentException(
                        "schema initialization requires an automatic DB declaration or a separately reviewed schema change");
            return AutomaticDatabasePreparation.empty();
        }
        if (values.get("type").equals("DOCKERFILE_CONTAINER")
                && assessment.databases().stream().anyMatch(database -> database
                        .engine() != gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseEngineType.SQLITE))
            throw new IllegalArgumentException(
                    "native database access from isolated containers requires an explicit reachable database binding; configure the DB binding in advanced options");
        progress.accept(LocalizedMessage.of("auto.progress.environment"));
        prepareEnvironment(request, master, fingerprint, interaction);
        var prepared = operation(AgentToolType.PREPARE_DATABASE, Map.of("application", id, "effect",
                "Prepare only the declared reviewed database bindings; preserve existing data unless separately authorized",
                "assessmentDigest", AgentAction.digest(assessment.toString())),
                () -> service.prepareAutomaticDatabases(root, id, request.server(), assessment, master.clone(),
                        interaction, fingerprint, progress));
        preparedDatabases.put(id, prepared);
        return prepared;
    }

    /**
     * Prepares environment.
     * <p>准备环境。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private void prepareEnvironment(AutomaticDeploymentRequest request, char[] master, Predicate<String> fingerprint,
            AutomaticDeploymentInteraction interaction) throws Exception {
        if (environmentPrepared)
            return;
        if (boundary == null && !interaction.confirm("environment.confirm",
                Map.of("serverId", request.server().id(), "host", request.server().host(), "port",
                        request.server().sshPort(), "username", request.server().username())))
            throw new CancellationException();
        operation(AgentToolType.PREPARE_ENVIRONMENT, Map.of("effect",
                "Install supported deployment tools and scoped privileged helper; separately review system-security preparation"),
                () -> {
                    prepareEnvironmentOnce(request, master, fingerprint, interaction);
                    return null;
                });
        environmentPrepared = true;
    }

    /**
     * Prepares environment once.
     * <p>准备环境Once。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private void prepareEnvironmentOnce(AutomaticDeploymentRequest request, char[] master,
            Predicate<String> fingerprint, AutomaticDeploymentInteraction interaction) throws Exception {
        service.prepareEnvironmentWithStoredPassword(request.server(), request.server().credentialMode(),
                master.clone(), fingerprint, true,
                plan -> interaction.confirm("environment.system.confirm", Map.of("serverId", request.server().id(),
                        "host", request.server().host(), "security", plan.securityState().name(), "reboot",
                        plan.state() == gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationState.UNPREPARED
                                || plan.state() == gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationState.REBOOT_PENDING)));
    }

    /**
     * Returns the contract with the supplied databases applied.
     * <p>返回应用所提供数据库集合后的契约。
     *
     * @param config config / 配置
     * @param db the prepared database resources and bindings / 已准备数据库资源及绑定
     * @return the contract with the supplied databases applied / 应用所提供数据库集合后的契约
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static ConfigurationSnapshot withDatabases(ConfigurationSnapshot config, AutomaticDatabasePreparation db) {
        var entries = new LinkedHashMap<String, gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry>();
        config.entries().forEach(entry -> entries.put(entry.key(), entry));
        db.configuration().forEach(entry -> {
            if (entries.containsKey(entry.key()) && !entries.get(entry.key()).equals(entry))
                throw new IllegalArgumentException(
                        "advanced configuration conflicts with verified DB input: " + entry.key());
            entries.put(entry.key(), entry);
        });
        return ConfigurationSnapshot.create(config.applicationId(), config.revision(), config.schemaVersion(),
                config.createdAt(), List.copyOf(entries.values()));
    }

    /**
     * Combines explicitly declared application secrets with the prepared database secret references.
     * <p>合并显式声明的应用秘密与已准备的数据库秘密引用。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param db the prepared database resources and bindings / 已准备数据库资源及绑定
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    private static List<gold.debug.windowstolinux.shared.config.secretref.SecretReference> secrets(
            Map<String, String> values, AutomaticDatabasePreparation db) {
        var result = new ArrayList<>(gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser
                .secrets(values.getOrDefault("secrets", "")));
        result.addAll(db.secrets());
        return List.copyOf(result);
    }

    /**
     * Uses prepared database bindings when available, otherwise parses the reviewed form bindings.
     * <p>存在已准备数据库绑定时使用该绑定，否则解析已审阅表单绑定。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param db the prepared database resources and bindings / 已准备数据库资源及绑定
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private static Optional<List<gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding>> bindings(
            Map<String, String> values, AutomaticDatabasePreparation db) {
        return db.bindings().isEmpty()
                ? DeploymentRuntimeParser.databaseBindings(
                        DatabaseReviewMode.valueOf(values.getOrDefault("databaseMode", "NONE")),
                        values.getOrDefault("databaseDetails", ""))
                : Optional.of(db.bindings());
    }

    /**
     * Records the answer for with ai.
     * <p>记录以下交互的回答：具有AI。
     *
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param mode task deployment mode / 任务部署模式
     */
    private void answerWithAi(List<DeploymentInputField> fields, Map<String, String> values,
            AutomaticDeploymentInteraction interaction, char[] master, DeploymentAutomationMode mode) {
        values.putAll(assistance == null
                ? gold.debug.windowstolinux.shared.standard.deploy.input.DeploymentInputAnswers.ask(fields, interaction)
                : assistance.complete(fields));
    }

    /**
     * Returns the contract with the supplied git applied.
     * <p>返回应用所提供Git后的契约。
     *
     * @param prepared prepared / 已准备
     * @param git Git source / Git 源码
     * @return the contract with the supplied git applied / 应用所提供Git后的契约
     */
    private static ReviewedSourcePreparation withGit(ReviewedSourcePreparation prepared, Optional<GitSnapshot> git) {
        if (git.isEmpty())
            return prepared;
        return new ReviewedSourcePreparation(prepared.assessment(), prepared.archive(),
                Optional.of(new SourceRevision(prepared.archive().orElseThrow().contentSha256(),
                        Optional.of(git.orElseThrow().commit()), Optional.of(git.orElseThrow().remote().location()),
                        Map.of())),
                prepared.excludedEntries());
    }

    /**
     * Builds a revisioned configuration snapshot from completed reviewed input fields.
     * <p>根据补全的已审阅输入字段构建带修订的配置快照。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return a revisioned configuration snapshot from completed reviewed input fields / 根据补全的已审阅输入字段构建带修订的配置快照
     */
    private static ConfigurationSnapshot configuration(String id, Map<String, String> values) {
        values = gold.debug.windowstolinux.shared.standard.deploy.input.ApplicationDeclaration.completed(values);
        var entries = new ArrayList<>(DeploymentConfigurationParser.parse(values.getOrDefault("configuration", "")));
        String portKey = values.get("type").equals("SPRING_BOOT") ? "SERVER_PORT" : "PORT";
        if (values.containsKey("port") && entries.stream().noneMatch(entry -> entry.key().equals(portKey)))
            entries.add(new gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry(portKey,
                    gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope.RUNTIME,
                    new gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue.Number(
                            Long.parseLong(values.get("port")))));
        return ConfigurationSnapshot.create(id, Instant.now().toEpochMilli(), "runtime-v1", Instant.now(), entries);
    }

    /**
     * Rejects root builds and returns the default non-root build limits.
     * <p>拒绝以 root 构建，并返回默认非 root 构建限制。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return constructed or resolved build limit configuration / 构造或解析得到的构建限制配置
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static BuildLimitConfiguration limits(Map<String, String> values) {
        if (Boolean.parseBoolean(values.getOrDefault("rootBuild", "false"))) {
            throw new IllegalArgumentException("Root builds are not supported");
        }
        return BuildLimitConfiguration.defaultNonRoot();
    }

    /** Routes one concrete operation through Agent approval when selected. / 选择 Agent 时经审批路由一个具体操作。
     * @param type registered operation / 已登记操作
     * @param parameters exact safe parameters / 精确安全参数
     * @param operation existing executor / 既有执行器
     * @param <T> typed result / 类型化结果
     * @return actual operation result / 实际操作结果
     * @throws Exception when validation or execution fails / 验证或执行失败时
     */
    private <T> T operation(AgentToolType type, Map<String, String> parameters,
            java.util.concurrent.Callable<T> operation) throws Exception {
        return boundary == null || type != AgentToolType.ANALYZE_SOURCE && type != AgentToolType.DEPLOY_TRANSACTION
                ? operation.call()
                : boundary.execute(type, parameters, operation);
    }

    /** Describes validated runtime and data scope before any environment or database mutation. / 在环境或数据库变更前描述已校验运行及数据范围。
     * @param resolved locally completed inputs / 本地补齐输入
     * @return bounded nonsecret review facts / 有界非秘密审阅事实
     */
    private Map<String, String> preparationEvidence(ResolvedInputs resolved) {
        var facts = new LinkedHashMap<String, String>();
        facts.put("healthOwner", resolved.healthOwner());
        for (var component : resolved.discovered()) {
            var values = resolved.inputs().get(component.id());
            var type = DeploymentProjectType.valueOf(values.get("type"));
            var specification = runtime.runtime(type, values);
            limits(values);
            String prefix = component.id() + "/";
            facts.put(prefix + "type", type.name());
            facts.put(prefix + "runtimeIdentity", specification.identityPolicy().name());
            facts.put(prefix + "health", gold.debug.windowstolinux.shared.ai.redaction.AgentEvidenceText
                    .redact(specification.healthCheck().toString()));
            facts.put(prefix + "dependencies", values.getOrDefault("dependencies", ""));
            facts.put(prefix + "dataBindings", gold.debug.windowstolinux.shared.ai.redaction.AgentEvidenceText
                    .redact(values.getOrDefault("fileBindings", "declared source bindings")));
            facts.put(prefix + "databaseMode", values.getOrDefault("databaseMode", "NONE"));
            facts.put(prefix + "configurationDigest", AgentAction.digest(new TreeMap<>(values).toString()));
            facts.put(prefix + "buildTool",
                    resolved.preparations().get(component.id()).assessment().facts().orElseThrow().buildTool().name());
            values.forEach((key, value) -> {
                if (gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedParameterPolicy.allows(key))
                    facts.put(prefix + key,
                            gold.debug.windowstolinux.shared.ai.redaction.AgentEvidenceText.redact(value));
            });
        }
        return Map.copyOf(facts);
    }

    /** Includes actual server observations in the fixed preflight checkpoint. / 在固定预检节点中包含实际服务器观察。
     * @param description exact nonsecret operation description / 精确非秘密操作描述
     */
    private void preflight(Map<String, String> description) {
        if (assistance != null) {
            var facts = new LinkedHashMap<>(description);
            facts.putAll(serverFacts);
            assistance.check("PREFLIGHT", facts);
        }
    }

}
