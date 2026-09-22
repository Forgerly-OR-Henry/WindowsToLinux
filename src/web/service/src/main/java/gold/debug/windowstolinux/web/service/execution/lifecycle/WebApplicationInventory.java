package gold.debug.windowstolinux.web.service.execution.lifecycle;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.shared.deploy.execution.lifecycle.*;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.repository.*;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;
import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Provides scoped application cards and typed lifecycle operations with freshly rechecked external identities.
 * <p>提供限定作用域应用卡片及类型化生命周期操作，并重新复核外部身份。
 */
public final class WebApplicationInventory {
    /**
     * Bound web resource repository collaborator for persistence boundary for the owned records.
     * <p>处理所属记录的持久化边界的Web资源仓库协作对象。
     */
    private final WebResourceRepository repository;

    /**
     * Bound web task repository collaborator for tasks.
     * <p>处理任务集合的Web任务仓库协作对象。
     */
    private final WebTaskRepository tasks;

    /**
     * Bound web server service collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的Web服务器服务协作对象。
     */
    private final WebServerService servers;

    /**
     * Validity duration of a discovery snapshot before mandatory reinspection.
     * <p>发现快照在必须重新检查前的有效时长。
     */
    private final Duration scanValidity;
    /**
     * Binds the supplied dependencies and state for web application inventory.
     * <p>为Web应用清单绑定传入的依赖及状态。
     *
     * @param repository persistence boundary for the owned records / 所属记录的持久化边界
     * @param tasks tasks / 任务集合
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param scanValidity validity duration of a discovery snapshot before mandatory reinspection / 发现快照在必须重新检查前的有效时长
     */
    public WebApplicationInventory(WebResourceRepository repository, WebTaskRepository tasks, WebServerService servers,
            Duration scanValidity) {
        this.repository = repository;
        this.tasks = tasks;
        this.servers = servers;
        this.scanValidity = scanValidity;
    }

    /**
     * Lists json node.
     * <p>列出JSON节点。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode list(WebRequestContext context) throws Exception {
        return WebJsonCodec.tree(repository.list(scope(context), ResourceType.APPLICATION).stream()
                .map(WebApplicationInventory::view).toList());
    }

    /**
     * Validates and returns stored resource.
     * <p>校验并返回已存储资源。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved stored resource / 构造或解析得到的已存储资源
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public StoredResource require(WebRequestContext context, String id) throws Exception {
        return repository.find(scope(context), ResourceType.APPLICATION, id)
                .orElseThrow(() -> new NoSuchElementException("Application not found"));
    }

    /**
     * Finds the scoped managed application matching the server and remote application identity.
     * <p>查找作用域内匹配服务器及远端应用身份的受管应用。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param applicationId managed application identifier / 受管应用标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public Optional<StoredResource> managed(WebRequestContext context, String serverId, String applicationId)
            throws Exception {
        return repository.list(scope(context), ResourceType.APPLICATION).stream()
                .filter(row -> row.attributes().get("server_id").equals(serverId)
                        && row.attributes().get("kind").equals("MANAGED")
                        && row.attributes().get("remote_identity").equals(applicationId))
                .findFirst();
    }

    /**
     * Requires managed ownership before decoding the stored application graph.
     * <p>要求受管归属成立后解码持久化应用图。
     *
     * @param row row / 数据行
     * @return constructed or resolved managed web graph / 构造或解析得到的受管Web图
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public ManagedWebGraph graph(StoredResource row) {
        if (!row.attributes().get("kind").equals("MANAGED"))
            throw new IllegalArgumentException("External applications have no managed backup graph");
        return WebJsonCodec.convert(WebJsonCodec.read(row.document()).path("graph"), ManagedWebGraph.class);
    }

    /**
     * Stages stored resource.
     * <p>暂存已存储资源。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param graph graph / 图
     * @return constructed or resolved stored resource / 构造或解析得到的已存储资源
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public StoredResource stage(WebRequestContext context, String serverId, String name, ManagedWebGraph graph)
            throws Exception {
        var old = managed(context, serverId, graph.applicationId());
        ObjectNode document = old.isPresent()
                ? (ObjectNode) WebJsonCodec.read(old.orElseThrow().document())
                : WebJsonCodec.object();
        document.set("pendingGraph", WebJsonCodec.tree(graph));
        document.put("deploymentState", "RUNNING");
        if (!document.has("graph"))
            document.set("graph", WebJsonCodec.tree(graph));
        return repository.save(scope(context), ResourceType.APPLICATION,
                old.map(StoredResource::id).orElseGet(() -> UUID.randomUUID().toString()),
                old.map(StoredResource::name).orElse(name),
                Map.of("server_id", serverId, "kind", "MANAGED", "remote_identity", graph.applicationId()),
                WebJsonCodec.write(document), old.map(StoredResource::version).orElse(0L));
    }

    /**
     * Finishes json node.
     * <p>完成JSON节点。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param published published / 已发布
     * @param observation observation / 观测
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode finish(WebRequestContext context, String id, String status, ManagedWebGraph published,
            JsonNode observation) throws Exception {
        var row = require(context, id);
        var document = (ObjectNode) WebJsonCodec.read(row.document());
        document.put("deploymentState", status);
        if (published != null) {
            document.set("graph", WebJsonCodec.tree(published));
            document.remove("pendingGraph");
            document.put("deployedAt", Instant.now().toString());
            if (!document.has("accessUrl")) {
                var owner = published.components().stream()
                        .filter(component -> component.id().equals(published.healthOwner())).findFirst().orElseThrow();
                owner.configuration().runtimeConfiguration().userAccessUrl()
                        .ifPresent(url -> document.put("accessUrl", url.url().toString()));
            }
        }
        if (observation != null)
            document.set("observation", observation);
        return view(repository.save(scope(context), ResourceType.APPLICATION, id, row.name(), row.attributes(),
                WebJsonCodec.write(document), row.version()));
    }

    /**
     * Checks presentation syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查展示语法及边界。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public JsonNode presentation(WebRequestContext context, String id, JsonNode input) throws Exception {
        WebRequestValidator.fields(input, "name", "category", "accessUrl", "version");
        var row = require(context, id);
        if (row.version() != input.path("version").asLong())
            throw new IllegalStateException("Application changed");
        String name = WebRequestValidator.text(input, "name", 200), category = input.path("category").asText("");
        if (!Set.of("", "WEBSITE", "APP", "UNKNOWN").contains(category))
            throw new IllegalArgumentException("Invalid application category");
        if (row.attributes().get("kind").equals("MANAGED") && !category.equals(view(row).path("category").asText()))
            throw new IllegalArgumentException("Managed category comes from reviewed service declarations");
        String access = input.path("accessUrl").asText("");
        if (!access.isBlank())
            new UserAccessUrl(URI.create(access));
        var document = (ObjectNode) WebJsonCodec.read(row.document());
        document.put("category", category).put("accessUrl", access);
        return view(repository.save(scope(context), ResourceType.APPLICATION, id, name, row.attributes(),
                WebJsonCodec.write(document), row.version()));
    }

    /**
     * Prepares a scoped application lifecycle task with exact target locking and fresh identity checks.
     * <p>准备具有精确目标锁定及新鲜身份检查的限定作用域应用生命周期任务。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param requestedAction requested action / 已请求动作
     * @return constructed or resolved prepared web operation / 构造或解析得到的已准备Web操作
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public PreparedWebOperation lifecycle(WebRequestContext context, String id, String requestedAction)
            throws Exception {
        var row = require(context, id);
        String serverId = (String) row.attributes().get("server_id");
        LifecycleAction action = LifecycleAction.valueOf(requestedAction);
        boolean external = row.attributes().get("kind").equals("EXTERNAL");
        if (external && !Set.of(LifecycleAction.REFRESH_STATUS, LifecycleAction.START, LifecycleAction.STOP,
                LifecycleAction.RESTART).contains(action))
            throw new IllegalArgumentException("External lifecycle action is unsupported");
        if (!external && action != LifecycleAction.REFRESH_STATUS && !view(row).path("canLifecycle").asBoolean())
            throw new IllegalArgumentException("On-demand tools use a command; obsolete contracts require reanalysis");
        return new PreparedWebOperation("LIFECYCLE",
                WebJsonCodec.object().put("applicationId", id).put("action", action.name()), List.of(serverId),
                List.of(WebServerService.lockKey(servers.require(context, serverId))),
                action != LifecycleAction.REFRESH_STATUS, null, id, null, interaction -> {
                    var current = require(context, id);
                    var document = (ObjectNode) WebJsonCodec.read(current.document());
                    if (!external && action != LifecycleAction.REFRESH_STATUS
                            && Set.of("RUNNING", "REVALIDATION_REQUIRED", "MANUAL_RECOVERY_REQUIRED")
                                    .contains(document.path("deploymentState").asText()))
                        throw new IllegalStateException("Application publication requires revalidation");
                    JsonNode observation;
                    if (external) {
                        var target = WebJsonCodec.convert(document.path("target"), ExternalApplicationTarget.class);
                        var result = servers.withSession(context, serverId, interaction,
                                session -> session.externalApplications().execute(target, action));
                        if (result.managed() || !result.target().equals(target))
                            throw new IllegalStateException("External application identity changed");
                        if (action == LifecycleAction.STOP && result.state() != RuntimeState.STOPPED
                                || (action == LifecycleAction.START || action == LifecycleAction.RESTART)
                                        && result.state() != RuntimeState.RUNNING)
                            interaction.completion(OperationCompletionState.FAILED);
                        observation = WebJsonCodec.object().put("state", result.state().name()).put("observedAt",
                                Instant.now().toString());
                    } else {
                        var graph = graph(current);
                        if (!servers.identity(context, serverId)
                                .equals(graph.components().getFirst().application().server()))
                            throw new IllegalStateException("Server identity changed");
                        var components = graph.components().stream()
                                .map(component -> new ManagedComponentLifecycle(component.id(), component.application(),
                                        component.runtime().healthCheck()))
                                .toList();
                        var result = servers.withCredential(context, serverId, interaction,
                                (endpoint, credential, verifier) -> new MultiComponentLifecycleService().execute(
                                        graph.plan(), components,
                                        graph.components().stream()
                                                .filter(component -> action == LifecycleAction.REFRESH_STATUS
                                                        || component.runtime().workload().supportsLifecycle())
                                                .map(ManagedWebComponent::id)
                                                .collect(java.util.stream.Collectors.toSet()),
                                        action, servers.gateway(), endpoint, credential, verifier));
                        observation = WebJsonCodec.tree(result);
                        if (!result.accepted())
                            interaction.completion(OperationCompletionState.FAILED);
                    }
                    // Only the narrow status projection is exposed; raw remote evidence is never returned. / 仅公开有限状态投影；绝不返回原始远端证据。
                    var safe = statusProjection(observation);
                    var latest = require(context, id);
                    document = (ObjectNode) WebJsonCodec.read(latest.document());
                    document.set("observation", safe);
                    repository.save(scope(context), ResourceType.APPLICATION, id, latest.name(), latest.attributes(),
                            WebJsonCodec.write(document), latest.version());
                    return safe;
                });
    }

    /**
     * Scans prepared web operation.
     * <p>扫描已准备Web操作。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param serverId persisted server identifier / 持久化服务器标识
     * @return constructed or resolved prepared web operation / 构造或解析得到的已准备Web操作
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public PreparedWebOperation scan(WebRequestContext context, String serverId) throws Exception {
        servers.require(context, serverId);
        return new PreparedWebOperation("APPLICATION_SCAN", WebJsonCodec.object().put("serverId", serverId),
                List.of(serverId), List.of(), false, null, null, null, interaction -> {
                    var scan = servers.withSession(context, serverId, interaction,
                            session -> session.externalApplications().scan());
                    return WebJsonCodec.object().put("serverId", serverId).put("observedAt", Instant.now().toString())
                            .set("scan", WebJsonCodec.tree(scan));
                });
    }

    /**
     * Prepares adoption of a discovered application after validating its scan evidence and explicit approval requirements.
     * <p>验证扫描证据及显式批准要求后，准备接管已发现应用。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param taskId task id / 任务标识
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return constructed or resolved prepared web operation / 构造或解析得到的已准备Web操作
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public PreparedWebOperation adopt(WebRequestContext context, String taskId, String key) throws Exception {
        var scanTask = tasks.find(scope(context), taskId)
                .orElseThrow(() -> new NoSuchElementException("Scan not found"));
        if (!scanTask.kind().equals("APPLICATION_SCAN") || !scanTask.state().equals("SUCCEEDED")
                || Duration.between(Instant.parse(scanTask.updatedAt()), Instant.now()).compareTo(scanValidity) > 0)
            throw new IllegalStateException("Rescan the server first");
        var result = WebJsonCodec.read(scanTask.resultJson());
        String serverId = result.path("serverId").asText();
        var scan = WebJsonCodec.convert(result.path("scan"), ExternalApplicationScan.class);
        var candidate = scan.applications().stream().filter(item -> item.target().key().equals(key) && !item.managed())
                .findFirst().orElseThrow();
        return new PreparedWebOperation("APPLICATION_ADOPT",
                WebJsonCodec.object().put("scanTaskId", taskId).put("candidateKey", key), List.of(serverId),
                List.of(WebServerService.lockKey(servers.require(context, serverId))), true, null, null, null,
                interaction -> {
                    var checked = servers.withSession(context, serverId, interaction, session -> session
                            .externalApplications().execute(candidate.target(), LifecycleAction.REFRESH_STATUS));
                    if (checked.managed() || !checked.target().equals(candidate.target()))
                        throw new IllegalStateException("Application identity changed");
                    var document = WebJsonCodec.object().put("canStart", checked.canStart()).put("canStop",
                            checked.canStop());
                    document.set("target", WebJsonCodec.tree(checked.target()));
                    document.set("observation", WebJsonCodec.object().put("state", checked.state().name())
                            .put("observedAt", Instant.now().toString()));
                    var created = repository.save(scope(context), ResourceType.APPLICATION,
                            UUID.randomUUID().toString(), checked.name(), Map.of("server_id", serverId, "kind",
                                    "EXTERNAL", "remote_identity", checked.target().key()),
                            WebJsonCodec.write(document), 0);
                    return view(created);
                });
    }

    /**
     * Projects runtime observations into the bounded application status response with an observation timestamp.
     * <p>将运行观测投影为带观测时间戳的有界应用状态响应。
     *
     * @param raw raw / 原始
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     */
    private static JsonNode statusProjection(JsonNode raw) {
        if (raw.has("state"))
            return WebJsonCodec.object().put("state", raw.path("state").asText()).put("observedAt",
                    raw.path("observedAt").asText());
        var result = WebJsonCodec.object().put("observedAt", Instant.now().toString()).put("state",
                raw.path("runtimeState").asText("UNKNOWN"));
        var items = new ArrayList<JsonNode>();
        for (var component : raw.path("componentResults")) {
            var observation = component.path("observation");
            items.add(WebJsonCodec.object().put("id", component.path("componentId").asText())
                    .put("state", observation.path("runtimeState").asText("UNKNOWN"))
                    .put("autostart", observation.path("autostartState").asText("UNKNOWN"))
                    .put("observedAt", observation.path("observedAt").asText()));
        }
        if (!items.isEmpty() && items.stream().allMatch(item -> item.path("state").asText().equals("RUNNING")))
            result.put("state", "RUNNING");
        else if (!items.isEmpty() && items.stream().allMatch(item -> item.path("state").asText().equals("INSTALLED")))
            result.put("state", "INSTALLED");
        else if (!items.isEmpty() && items.stream().allMatch(item -> item.path("state").asText().equals("STOPPED")))
            result.put("state", "STOPPED");
        result.set("components", WebJsonCodec.tree(items));
        return result;
    }

    /**
     * Projects stored application metadata and bounded runtime status into its public card response.
     * <p>将持久化应用元数据及有界运行状态投影为公开卡片响应。
     *
     * @param row row / 数据行
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     */
    private static JsonNode view(StoredResource row) {
        var data = WebJsonCodec.read(row.document());
        var view = WebJsonCodec.object().put("id", row.id()).put("name", row.name())
                .put("serverId", row.attributes().get("server_id").toString())
                .put("kind", row.attributes().get("kind").toString()).put("version", row.version())
                .put("category", data.path("category").asText("SERVICE"))
                .put("accessUrl", data.path("accessUrl").asText(""))
                .put("deployedAt", data.path("deployedAt").asText(row.createdAt()))
                .put("deploymentState", data.path("deploymentState").asText("EXTERNAL"));
        view.set("observation", data.path("observation"));
        view.put("canLifecycle", !row.attributes().get("kind").equals("MANAGED"));
        view.put("reanalysisRequired", false);
        if (row.attributes().get("kind").equals("MANAGED")) {
            try {
                var graph = WebJsonCodec.convert(data.path("graph"), ManagedWebGraph.class);
                var usage = graph.components().stream()
                        .map(component -> gold.debug.windowstolinux.shared.model.managed.ApplicationUsage
                                .from(component.application(), component.runtime().workload()))
                        .toList();
                boolean reviewed = usage.stream().allMatch(item -> item.reviewed());
                view.put("category", !reviewed
                        ? "UNKNOWN"
                        : usage.stream().anyMatch(item -> item.category().equals("WEBSITE")) ? "WEBSITE" : "APP");
                view.put("canLifecycle", reviewed && usage.stream().anyMatch(item -> item.lifecycle()));
                view.put("reanalysisRequired", !reviewed);
                view.set("usage", WebJsonCodec.tree(usage));
                if (!view.path("category").asText().equals("WEBSITE"))
                    view.put("accessUrl", "");
            } catch (IllegalArgumentException | IllegalStateException obsolete) {
                view.put("reanalysisRequired", true).put("category", "UNKNOWN");
            }
        }
        if (data.has("graph"))
            view.set("types", WebJsonCodec.tree(graphTypes(data.path("graph"))));
        return view;
    }

    /**
     * Lists the stored runtime input type for each component in graph order.
     * <p>按图中顺序列出各组件持久化运行输入类型。
     *
     * @param graph graph / 图
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    private static List<String> graphTypes(JsonNode graph) {
        var result = new LinkedHashSet<String>();
        for (var component : graph.path("components"))
            result.add(component.path("inputs").path("type").asText());
        return List.copyOf(result);
    }

    /**
     * Resolves the ownership scope supplied by the trusted caller.
     * <p>解析可信调用方提供的归属作用域。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return the ownership scope supplied by the trusted caller / 可信调用方提供的归属作用域
     */
    private static ResourceScope scope(WebRequestContext context) {
        return new ResourceScope(context.workspaceId(), context.userId());
    }
}
