package gold.debug.windowstolinux.web.service.deployment;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.shared.backup.format.*;
import gold.debug.windowstolinux.shared.config.contract.definition.*;
import gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser;
import gold.debug.windowstolinux.shared.config.resource.*;
import gold.debug.windowstolinux.shared.config.revision.*;
import gold.debug.windowstolinux.shared.config.secretref.*;
import gold.debug.windowstolinux.shared.deploy.contract.*;
import gold.debug.windowstolinux.shared.deploy.execution.environment.EnvironmentSetupService;
import gold.debug.windowstolinux.shared.deploy.execution.transaction.*;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.managed.*;
import gold.debug.windowstolinux.shared.model.project.*;
import gold.debug.windowstolinux.shared.model.project.component.ComponentIsolationSpecification;
import gold.debug.windowstolinux.shared.source.archive.SafeSourceArchivePreparer;
import gold.debug.windowstolinux.shared.standard.analyze.component.*;
import gold.debug.windowstolinux.shared.standard.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.standard.deploy.contract.AutomaticDatabasePreparation;
import gold.debug.windowstolinux.shared.standard.deploy.contract.DeploymentApproval;
import gold.debug.windowstolinux.shared.standard.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.standard.deploy.execution.transaction.*;
import gold.debug.windowstolinux.shared.standard.deploy.input.*;
import gold.debug.windowstolinux.shared.standard.deploy.plan.*;
import gold.debug.windowstolinux.web.service.config.WebApplicationSecrets;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;
import gold.debug.windowstolinux.web.service.execution.lifecycle.WebApplicationInventory;
import gold.debug.windowstolinux.web.service.interaction.WebTaskInteractionService;
import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import gold.debug.windowstolinux.web.service.source.WebSourceService;
import tools.jackson.databind.JsonNode;

/**
 * Composes deterministic analysis and shared publication transactions for Web tasks.
 * <p>为 Web 任务组合确定性分析及共享发布事务。
 */
public final class WebDeploymentService {
    /**
     * Bound web server service collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的Web服务器服务协作对象。
     */
    private final WebServerService servers;

    /**
     * Bound web source service collaborator for sources.
     * <p>处理源码集合的Web源码服务协作对象。
     */
    private final WebSourceService sources;

    /**
     * Applications.
     * <p>应用集合。
     */
    private final WebApplicationInventory applications;

    /**
     * Credential references or scoped secret-access service.
     * <p>凭据引用或限定作用域的秘密访问服务。
     */
    private final WebApplicationSecrets secrets;

    /**
     * Bound gold debug windowstolinux web service ai web ai service collaborator for the supplied web ai service.
     * <p>处理所提供的WebAI服务的golddebugwindowstolinuxWeb服务AIWebAI服务协作对象。
     */
    private final gold.debug.windowstolinux.web.service.ai.WebAiService ai;

    /**
     * Bound automatic runtime resolver collaborator for reviewed language, process and health specification.
     * <p>处理已审阅的语言、进程及健康规格的自动运行时解析器协作对象。
     */
    private final AutomaticRuntimeResolver runtime = new AutomaticRuntimeResolver();
    /**
     * Binds the supplied dependencies and state for web deployment service.
     * <p>为Web部署服务绑定传入的依赖及状态。
     *
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param sources sources / 源码集合
     * @param applications applications / 应用集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param ai the supplied web ai service / 所提供的WebAI服务
     */
    public WebDeploymentService(WebServerService servers, WebSourceService sources,
            WebApplicationInventory applications, WebApplicationSecrets secrets,
            gold.debug.windowstolinux.web.service.ai.WebAiService ai) {
        this.servers = servers;
        this.sources = sources;
        this.applications = applications;
        this.secrets = secrets;
        this.ai = ai;
    }

    /**
     * Prepares prepared web operation.
     * <p>准备已准备Web操作。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved prepared web operation / 构造或解析得到的已准备Web操作
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public PreparedWebOperation prepare(WebRequestContext context, JsonNode input) throws Exception {
        WebRequestValidator.fields(input, "serverId", "sourceId", "overrides");
        String serverId = WebRequestValidator.text(input, "serverId", 63),
                sourceId = WebRequestValidator.text(input, "sourceId", 63);
        var server = servers.require(context, serverId);
        sources.requireReady(context, sourceId);
        var overrides = WebDeploymentInputs.overrides(input.path("overrides"));
        for (var entry : overrides.entrySet()) {
            if (entry.getKey().equals("configuration") || entry.getKey().endsWith("/configuration"))
                DeploymentConfigurationParser.parse(entry.getValue());
            if (entry.getKey().equals("secrets") || entry.getKey().endsWith("/secrets"))
                DeploymentConfigurationParser.secrets(entry.getValue());
        }
        return new PreparedWebOperation("DEPLOY", input, List.of(serverId), List.of(WebServerService.lockKey(server)),
                true, sourceId, null, null,
                interaction -> execute(context, serverId, sourceId, overrides, interaction));
    }

    /**
     * Analyzes and reviews the source graph, prepares approved resources, executes shared publication and persists the resulting application state while clearing resolved secrets.
     * <p>分析并审阅源码图、准备已批准资源、执行共享发布，并持久化所得应用状态，同时清空已解析秘密。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param sourceId source id / 源码标识
     * @param overrides overrides / 覆盖项集合
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private JsonNode execute(WebRequestContext context, String serverId, String sourceId, Map<String, String> overrides,
            TaskInteraction interaction) throws Exception {
        interaction.progress("SOURCE_ANALYZING", WebJsonCodec.object());
        var resolvedSecrets = new ArrayList<ResolvedSecretRevision>();
        try (var snapshot = sources.snapshot(context, sourceId)) {
            Path root = snapshot.directory();
            var analysis = analyze(context, sourceId, root, serverId, overrides, interaction);
            var discovered = analysis.components();
            var inputs = analysis.inputs();
            var facts = analysis.facts();
            String applicationId = analysis.applicationId();
            var plan = analysis.plan();
            var requests = analysis.requests();
            String healthOwner = healthOwner(discovered, inputs, interaction);
            approveRuntimeChoices(analysis, interaction);
            var databaseService = new WebDatabaseService(secrets, servers, ai);
            var databaseInputs = new LinkedHashMap<String, gold.debug.windowstolinux.shared.standard.analyze.ecosystem.db.DatabaseProjectInspector.Assessment>();
            for (var component : discovered)
                databaseInputs.put(component.id(),
                        databaseService.inputs(context, root.resolve(component.relativeRoot()),
                                facts.get(component.id()).applicationId(), inputs.get(component.id()), interaction));
            // Authenticate and pin the endpoint before deriving ownership identities. / 派生归属身份前先认证并固定端点。
            servers.withSession(context, serverId, interaction, session -> session.collectCapabilities());
            var identity = servers.identity(context, serverId);
            var old = applications.managed(context, serverId, applicationId);
            var known = old.map(applications::graph).map(ManagedWebGraph::components).orElse(List.of());
            if (!known.isEmpty() && !known.stream().map(ManagedWebComponent::id)
                    .collect(java.util.stream.Collectors.toSet()).equals(facts.keySet()))
                throw new IllegalStateException(
                        "Component topology changed; review the existing application before redeploying");
            validatePublicationInputs(context, analysis, identity);
            prepareEnvironment(context, serverId, applicationId, identity, interaction);
            var databases = prepareDatabases(context, serverId, root, analysis, databaseService, databaseInputs,
                    interaction);
            var publication = review(context, sourceId, root, discovered, facts, inputs, databases, identity, known,
                    plan, resolvedSecrets);
            var reviewed = publication.reviewed();
            var durable = publication.durable();
            for (var component : reviewed)
                for (var item : gold.debug.windowstolinux.shared.standard.deploy.input.ManagedStoragePreparation
                        .preview(component.request())) {
                    var preview = WebJsonCodec.object();
                    item.arguments().forEach(preview::put);
                    interaction.progress(
                            item.key().equals("storage.preflight") ? "STORAGE_PREFLIGHT" : "APPLICATION_PREFLIGHT",
                            preview);
                }
            var graph = new ManagedWebGraph(applicationId, healthOwner, durable);
            var record = applications.stage(context, serverId, applicationId, graph);
            var progress = (java.util.function.Consumer<gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent>) event -> WebTaskInteractionService
                    .progress(interaction, event.step().code(), WebJsonCodec.object()
                            .put("succeeded", event.succeeded()).put("messageKey", event.message().key()));
            try {
                interaction.progress("DEPLOYMENT_STARTED", WebJsonCodec.object().put("applicationId", record.id()));
                DeploymentStatus status;
                Map<String, String> releases;
                if (reviewed.size() == 1) {
                    var component = reviewed.getFirst();
                    var result = servers.withCredential(context, serverId, interaction,
                            (endpoint, credential, verifier) -> new ReviewedDeploymentService().deploy(
                                    component.request(), component.application(), servers.gateway(), endpoint,
                                    credential, verifier, component.resolvedSecrets(), progress));
                    status = result.status();
                    releases = result.publishedReleaseSha256().map(value -> Map.of(component.componentId(), value))
                            .orElse(Map.of());
                } else {
                    var result = servers.withCredential(context, serverId, interaction,
                            (endpoint, credential, verifier) -> new ReviewedMultiComponentDeploymentService().deploy(
                                    plan, reviewed,
                                    new ApplicationHealthGate(healthOwner, runtime.health(inputs.get(healthOwner))),
                                    servers.gateway(), endpoint, credential, verifier, progress));
                    status = result.status();
                    releases = result.componentReleaseIdentities();
                }
                boolean success = status == DeploymentStatus.SUCCEEDED;
                if (!success)
                    interaction.completion(status == DeploymentStatus.MANUAL_RECOVERY_REQUIRED
                            ? OperationCompletionState.REVALIDATION_REQUIRED
                            : OperationCompletionState.FAILED);
                var published = success
                        ? new ManagedWebGraph(applicationId, healthOwner,
                                durable.stream().map(value -> value.published(releases.get(value.id()))).toList())
                        : null;
                var finalObservation = success
                        ? WebJsonCodec.object().put("state", reviewed.stream().allMatch(component -> component.request()
                                .runtime().workload()
                                .mode() == gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode.ON_DEMAND)
                                        ? "INSTALLED"
                                        : "RUNNING")
                                .put("observedAt", Instant.now().toString())
                        : null;
                applications.finish(context, record.id(), status.name(), published, finalObservation);
                var result = WebJsonCodec.object().put("applicationId", record.id()).put("status", status.name());
                if (success)
                    runtime.access(inputs.get(healthOwner), identity.host())
                            .ifPresent(url -> result.put("accessUrl", url.url().toString()));
                return result;
            } catch (Exception failure) {
                interaction.completion(OperationCompletionState.REVALIDATION_REQUIRED);
                applications.finish(context, record.id(), "REVALIDATION_REQUIRED", null, null);
                throw failure;
            }
        } finally {
            resolvedSecrets.forEach(ResolvedSecretRevision::close);
        }
    }

    /**
     * Requests the explicit confirmations required by experimental adapters and reviewed runtime choices.
     * <p>请求实验性适配器及已审阅运行选择所需的显式确认。
     *
     * @param analysis analysis / 分析
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private static void approveRuntimeChoices(InitialAnalysis analysis, TaskInteraction interaction) throws Exception {
        var facts = analysis.facts();
        var inputs = analysis.inputs();
        for (var entry : facts.entrySet()) {
            var values = inputs.get(entry.getKey());
            if (entry.getValue().support().level() == DeploymentSupportLevel.EXPERIMENTAL_ADAPTER)
                WebTaskInteractionService.approve(interaction, "EXPERIMENTAL_ADAPTER",
                        WebJsonCodec.object().put("component", entry.getKey()).put("type", values.get("type")));
            if (values.get("type").equals("DOCKERFILE_CONTAINER")
                    && values.getOrDefault("containerEngine", "PODMAN").equals("DOCKER"))
                WebTaskInteractionService.approve(interaction, "DOCKER_DAEMON",
                        WebJsonCodec.object().put("component", entry.getKey()));
        }
    }

    /**
     * Prepares environment.
     * <p>准备环境。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param applicationId managed application identifier / 受管应用标识
     * @param identity identity / 身份
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private void prepareEnvironment(WebRequestContext context, String serverId, String applicationId,
            gold.debug.windowstolinux.shared.model.server.ServerIdentity identity, TaskInteraction interaction)
            throws Exception {
        WebTaskInteractionService.approve(interaction, "ENVIRONMENT_PREPARATION", WebJsonCodec.object()
                .put("serverId", serverId).put("host", identity.host()).put("applicationId", applicationId));
        interaction.progress("ENVIRONMENT_PREPARING", WebJsonCodec.object());
        servers.withCredential(context, serverId, interaction,
                (endpoint, credential, verifier) -> new EnvironmentSetupService().prepare(
                        new EnvironmentSetupApproval(serverId, true, Instant.now()), servers.gateway(), endpoint,
                        credential, verifier,
                        system -> WebTaskInteractionService.confirm(interaction, "SYSTEM_PREPARATION",
                                WebJsonCodec.object().put("security", system.securityState().name()).put("state",
                                        system.state().name()))));
    }

    /**
     * Validates every component's publication inputs against the authenticated target identity before remote preparation.
     * <p>在远端准备前，根据已认证目标身份校验每个组件的发布输入。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param analysis analysis / 分析
     * @param identity identity / 身份
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private void validatePublicationInputs(WebRequestContext context, InitialAnalysis analysis,
            gold.debug.windowstolinux.shared.model.server.ServerIdentity identity) throws Exception {
        var discovered = analysis.components();
        var inputs = analysis.inputs();
        var facts = analysis.facts();
        for (var component : discovered) {
            var values = inputs.get(component.id());
            var storageFacts = facts.get(component.id());
            gold.debug.windowstolinux.shared.standard.deploy.input.ManagedStoragePreparation.prepare(
                    storageFacts.sourceRoot(), storageFacts.applicationId(),
                    configuration(storageFacts.applicationId(), values),
                    runtime.runtime(storageFacts.projectType(), values), List.of());
            runtime.runtime(facts.get(component.id()).projectType(), values);
            runtime.access(values, identity.host());
            var references = DeploymentConfigurationParser.secrets(values.getOrDefault("secrets", ""));
            var checkedSecrets = secrets.resolve(context, references);
            checkedSecrets.forEach(ResolvedSecretRevision::close);
            DeploymentRuntimeParser.databaseBindings(
                    DatabaseReviewMode.valueOf(values.getOrDefault("databaseMode", "NONE")),
                    values.getOrDefault("databaseDetails", ""));
        }
    }

    /**
     * Prepares the reviewed per-component database requirements and returns exact bindings for publication.
     * <p>准备已审阅的各组件数据库要求，并返回精确绑定供发布使用。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param analysis analysis / 分析
     * @param databaseService database service / 数据库服务
     * @param databaseInputs database inputs / 数据库输入集合
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private Map<String, AutomaticDatabasePreparation> prepareDatabases(WebRequestContext context, String serverId,
            Path root, InitialAnalysis analysis, WebDatabaseService databaseService,
            Map<String, gold.debug.windowstolinux.shared.standard.analyze.ecosystem.db.DatabaseProjectInspector.Assessment> databaseInputs,
            TaskInteraction interaction) throws Exception {
        var discovered = analysis.components();
        var facts = analysis.facts();
        var inputs = analysis.inputs();
        var requests = analysis.requests();
        String applicationId = analysis.applicationId();
        var databases = new LinkedHashMap<String, AutomaticDatabasePreparation>();
        var databaseReviews = new LinkedHashMap<String, gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview>();
        for (var component : discovered) {
            var db = databaseService.prepare(context, serverId, root.resolve(component.relativeRoot()),
                    facts.get(component.id()).applicationId(), databaseInputs.get(component.id()),
                    inputs.get(component.id()), interaction);
            databases.put(component.id(), db);
            db.schemaReview().ifPresent(review -> databaseReviews.put(component.id(), review));
        }
        if (discovered.size() == 1) {
            var component = discovered.getFirst();
            var type = DeploymentProjectType.valueOf(inputs.get(component.id()).get("type"));
            var assessment = databaseReviews.containsKey(component.id())
                    ? new DeploymentAnalysisCoordinator().analyze(root.resolve(component.relativeRoot()), type,
                            databaseReviews.get(component.id()))
                    : new DeploymentAnalysisCoordinator().analyze(root.resolve(component.relativeRoot()), type);
            var fact = assessment.facts().orElseThrow();
            if (!fact.readyForPlanning())
                throw new IllegalArgumentException("Source database review is incomplete");
            facts.put(component.id(), fact);
        } else {
            var assessment = new MixedProjectInspector().analyze(root, applicationId, requests, databaseReviews);
            new MultiComponentDeploymentPlanner().plan(assessment);
            assessment.components().forEach(component -> facts.put(component.componentId(), component.facts()));
        }
        return databases;
    }
    /**
     * Carries the source revision and deterministic analysis used to prepare Web deployment.
     * <p>携带准备 Web 部署所用的源码修订及确定性分析。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param applicationId managed application identifier / 受管应用标识
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param requests requests / 请求集合
     */
    private record InitialAnalysis(List<DiscoveredProjectComponent> components, Map<String, Map<String, String>> inputs,
            LinkedHashMap<String, DeploymentProjectFacts> facts, String applicationId,
            MultiComponentDeploymentPlan plan, List<ComponentAnalysisRequest> requests) {
    }
    /**
     * Produces the deterministic source graph, completed runtime inputs and reviewed deployment plan for the scoped Web request.
     * <p>为限定作用域 Web 请求生成确定性源码图、已补全运行输入及已审阅部署计划。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param sourceId source id / 源码标识
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param overrides overrides / 覆盖项集合
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return the deterministic source graph, completed runtime inputs and reviewed deployment plan for the scoped Web request / 为限定作用域 Web 请求生成确定性源码图、已补全运行输入及已审阅部署计划
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private InitialAnalysis analyze(WebRequestContext context, String sourceId, Path root, String serverId,
            Map<String, String> overrides, TaskInteraction interaction) throws Exception {
        var discovered = new ProjectComponentDiscovery().discover(root);
        var inputs = new WebDeploymentInputs(ai, context).resolve(root, discovered, overrides,
                WebServerService.endpoint(servers.require(context, serverId)).host(), interaction);
        var facts = new LinkedHashMap<String, DeploymentProjectFacts>();
        String applicationId;
        MultiComponentDeploymentPlan plan;
        var requests = new ArrayList<ComponentAnalysisRequest>();
        if (discovered.size() == 1) {
            var component = discovered.getFirst();
            var values = inputs.get(component.id());
            var assessment = new DeploymentAnalysisCoordinator().analyzeForDatabaseReview(
                    root.resolve(component.relativeRoot()), DeploymentProjectType.valueOf(values.get("type")));
            var fact = assessment.facts().orElseThrow();
            applicationId = fact.applicationId();
            facts.put(component.id(), fact);
            plan = new MultiComponentDeploymentPlanner().restore(applicationId, Map.of(component.id(), applicationId),
                    Map.of(component.id(), List.of()));
        } else {
            applicationId = sources.requireReady(context, sourceId).name().toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9-]", "-");
            for (var component : discovered) {
                var values = inputs.get(component.id());
                var specification = runtime.runtime(DeploymentProjectType.valueOf(values.get("type")), values);
                var fact = new DeploymentAnalysisCoordinator()
                        .analyzeForDatabaseReview(root.resolve(component.relativeRoot()), specification.projectType())
                        .facts().orElseThrow();
                String path = component.relativeRoot().toString().replace('\\', '/');
                requests.add(new ComponentAnalysisRequest(component.id(), path.isBlank() ? "." : path,
                        specification.projectType(), Optional.of(specification),
                        runtime.artifacts(component.relativeRoot(), fact, values),
                        specification.workload().endpoints().stream().map(endpoint -> endpoint.hostPort())
                                .collect(java.util.stream.Collectors.toSet()),
                        List.of(), List.of(), List.of(), dependencies(values), true,
                        ComponentIsolationSpecification.managed()));
            }
            var assessment = new MixedProjectInspector().analyzeAutomatic(root, applicationId, requests);
            if (assessment.issues().stream().anyMatch(issue -> !issue.code().equals("COMPONENT_REQUIRES_INPUT")))
                throw new IllegalArgumentException("Invalid component graph");
            var namespaces = new LinkedHashMap<String, String>();
            var dependencies = new LinkedHashMap<String, List<String>>();
            assessment.components().forEach(component -> {
                namespaces.put(component.componentId(), component.facts().applicationId());
                dependencies.put(component.componentId(), component.dependencies().stream().sorted().toList());
            });
            plan = new MultiComponentDeploymentPlanner().restore(applicationId, namespaces, dependencies);
            assessment.components().forEach(component -> facts.put(component.componentId(), component.facts()));
        }
        return new InitialAnalysis(discovered, inputs, facts, applicationId, plan, requests);
    }
    /**
     * Collects publication results that can be persisted after the remote transaction.
     * <p>汇总远端事务后可持久化的发布结果。
     *
     * @param reviewed reviewed / 已审阅
     * @param durable durable / 持久化
     */
    private record Publication(List<ReviewedComponentDeployment> reviewed, List<ManagedWebComponent> durable) {
    }
    /**
     * Builds exact component publication requests and durable graph facts, resolving only reviewed secret revisions.
     * <p>构建精确组件发布请求及持久化图事实，并仅解析已审阅秘密修订。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param sourceId source id / 源码标识
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param discovered discovered / 已发现
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param databases databases / 数据库集合
     * @param identity identity / 身份
     * @param known known / 已知
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param resolvedSecrets resolved secrets / 已解析秘密集合
     * @return exact component publication requests and durable graph facts, resolving only reviewed secret revisions / 精确组件发布请求及持久化图事实，并仅解析已审阅秘密修订
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private Publication review(WebRequestContext context, String sourceId, Path root,
            List<DiscoveredProjectComponent> discovered, Map<String, DeploymentProjectFacts> facts,
            Map<String, Map<String, String>> inputs, Map<String, AutomaticDatabasePreparation> databases,
            gold.debug.windowstolinux.shared.model.server.ServerIdentity identity, List<ManagedWebComponent> known,
            MultiComponentDeploymentPlan plan, List<ResolvedSecretRevision> resolvedSecrets) throws Exception {
        var reviewed = new ArrayList<ReviewedComponentDeployment>();
        var durable = new ArrayList<ManagedWebComponent>();
        for (var component : discovered) {
            var fact = facts.get(component.id());
            var values = inputs.get(component.id());
            var archive = new SafeSourceArchivePreparer().archive(root.resolve(component.relativeRoot()),
                    root.getParent().resolve(component.id() + ".tar.gz"));
            var descriptor = new SourceArchiveDescriptor(archive.archivePath(), archive.contentSha256(),
                    archive.byteCount(), archive.uncompressedByteCount());
            var provenance = WebJsonCodec.read(sources.requireReady(context, sourceId).document());
            var revision = new SourceRevision(archive.contentSha256(),
                    Optional.ofNullable(provenance.has("commit") ? provenance.path("commit").asText() : null),
                    Optional.ofNullable(
                            provenance.has("remote") ? java.net.URI.create(provenance.path("remote").asText()) : null),
                    Map.of());
            var db = databases.get(component.id());
            var configuration = withDatabases(configuration(fact.applicationId(), values), db);
            var references = new ArrayList<>(DeploymentConfigurationParser.secrets(values.getOrDefault("secrets", "")));
            references.addAll(db.secrets());
            var boundSecrets = secrets.resolve(context, references);
            resolvedSecrets.addAll(boundSecrets);
            var bindings = db.bindings().isEmpty()
                    ? DeploymentRuntimeParser.databaseBindings(
                            DatabaseReviewMode.valueOf(values.getOrDefault("databaseMode", "NONE")),
                            values.getOrDefault("databaseDetails", ""))
                    : Optional.of(db.bindings());
            var storage = gold.debug.windowstolinux.shared.standard.deploy.input.ManagedStoragePreparation.prepare(
                    fact.sourceRoot(), fact.applicationId(), configuration, runtime.runtime(fact.projectType(), values),
                    List.of());
            configuration = storage.configuration();
            var request = new ReviewedDeploymentRequest(identity, fact, revision, descriptor, configuration, references,
                    bindings, storage.files(), runtime.runtime(fact.projectType(), values),
                    runtime.access(values, identity.host()), BuildLimitConfiguration.defaultNonRoot(),
                    new DeploymentApproval(fact.applicationId(), archive.contentSha256(), identity.id(), false,
                            Instant.now()),
                    true, true);
            var application = known.stream().filter(value -> value.id().equals(component.id())).findFirst()
                    .map(ManagedWebComponent::application)
                    .orElseGet(() -> ManagedApplication.forManaged(fact.applicationId(), identity, ownership()));
            if (!application.server().equals(identity))
                throw new IllegalStateException("Server identity changed");
            var resources = new ManagedComponentResourceBindings(storage.files(), bindings);
            var activation = new BackupConfigurationDocument(configuration, resources,
                    new ManagedApplicationRuntimeConfiguration(request.runtime().healthCheck(), request.userAccessUrl(),
                            request.runtime().identityPolicy(), request.runtime().workload()));
            durable.add(new ManagedWebComponent(component.id(), application, values,
                    Base64.getEncoder().encodeToString(new BackupConfigurationCodec().writeActivation(activation)),
                    plan.dependencies().get(component.id()), "", references,
                    Base64.getEncoder().encodeToString(
                            new gold.debug.windowstolinux.shared.config.persistence.serialization.DeploymentRuntimePersistenceCodec()
                                    .write(request.runtime()))));
            reviewed.add(
                    new ReviewedComponentDeployment(component.id(), request, application, boundSecrets, resources));
        }
        return new Publication(reviewed, durable);
    }

    /**
     * Returns the contract with the supplied databases applied.
     * <p>返回应用所提供数据库集合后的契约。
     *
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param db the prepared database resources and bindings / 已准备数据库资源及绑定
     * @return the contract with the supplied databases applied / 应用所提供数据库集合后的契约
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static ConfigurationSnapshot withDatabases(ConfigurationSnapshot configuration,
            AutomaticDatabasePreparation db) {
        var entries = new LinkedHashMap<String, ConfigurationEntry>();
        configuration.entries().forEach(entry -> entries.put(entry.key(), entry));
        db.configuration().forEach(entry -> {
            if (entries.containsKey(entry.key()) && !entries.get(entry.key()).equals(entry))
                throw new IllegalArgumentException("Configuration conflicts with verified database");
            entries.put(entry.key(), entry);
        });
        return ConfigurationSnapshot.create(configuration.applicationId(), configuration.revision(),
                configuration.schemaVersion(), configuration.createdAt(), List.copyOf(entries.values()));
    }

    /**
     * Splits comma-, semicolon- or whitespace-separated dependency identifiers and removes blanks and duplicates.
     * <p>拆分逗号、分号或空白分隔的依赖标识，并移除空项及重复项。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return constructed or resolved set / 构造或解析得到的集合
     */
    private static Set<String> dependencies(Map<String, String> values) {
        var result = new LinkedHashSet<String>();
        for (String value : values.getOrDefault("dependencies", "").split("[,;\\s]+"))
            if (!value.isBlank())
                result.add(value);
        return result;
    }

    /**
     * Uses the sole component or unique HTTP component as health owner, otherwise asks for an explicit selection.
     * <p>使用唯一组件或唯一 HTTP 组件作为健康检查所属组件，否则请求显式选择。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return health owner text / 健康所有者文本
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private static String healthOwner(List<DiscoveredProjectComponent> components,
            Map<String, Map<String, String>> inputs, TaskInteraction interaction) throws Exception {
        if (components.size() == 1)
            return components.getFirst().id();
        var owners = components.stream().filter(value -> inputs.get(value.id()).get("healthMode").equals("HTTP"))
                .toList();
        if (owners.size() == 1)
            return owners.getFirst().id();
        return WebTaskInteractionService
                .inputs(interaction,
                        List.of(AutomaticRuntimeResolver.field("application", "healthOwner", "",
                                components.stream().map(DiscoveredProjectComponent::id).toList())))
                .get("application/healthOwner");
    }

    /**
     * Builds a revisioned configuration snapshot from completed reviewed input fields.
     * <p>根据补全的已审阅输入字段构建带修订的配置快照。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return a revisioned configuration snapshot from completed reviewed input fields / 根据补全的已审阅输入字段构建带修订的配置快照
     */
    public static ConfigurationSnapshot configuration(String id, Map<String, String> values) {
        values = gold.debug.windowstolinux.shared.standard.deploy.input.ApplicationDeclaration.completed(values);
        var entries = new ArrayList<>(DeploymentConfigurationParser.parse(values.getOrDefault("configuration", "")));
        String key = values.get("type").equals("SPRING_BOOT") ? "SERVER_PORT" : "PORT";
        if (values.containsKey("port") && entries.stream().noneMatch(entry -> entry.key().equals(key)))
            entries.add(new ConfigurationEntry(key, ConfigurationScope.RUNTIME,
                    new ConfigurationValue.Number(Long.parseLong(values.get("port")))));
        return ConfigurationSnapshot.create(id, Instant.now().toEpochMilli(), "runtime-v1", Instant.now(), entries);
    }

    /**
     * Returns ownership.
     * <p>返回归属。
     *
     * @return ownership / 归属
     */
    private static String ownership() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
