package gold.debug.windowstolinux.web.service;

import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;

import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import gold.debug.windowstolinux.web.db.persistence.repository.WebResourceRepository;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import gold.debug.windowstolinux.web.service.source.WebSourceService;
import gold.debug.windowstolinux.web.service.execution.lifecycle.WebApplicationInventory;
import gold.debug.windowstolinux.web.service.deployment.WebDeploymentService;
import gold.debug.windowstolinux.web.service.ai.WebAiService;
import gold.debug.windowstolinux.web.service.config.WebApplicationSecrets;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;

/**
 * Provides the Web use-case boundary so controllers do not access storage, credentials or SSH directly.
 * <p>提供 Web 用例边界，使控制器无需直接访问存储、凭据或 SSH。
 */
public final class WebApplicationService {
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
     * Bound web resource repository collaborator for persistence boundary for the owned records.
     * <p>处理所属记录的持久化边界的Web资源仓库协作对象。
     */
    private final WebResourceRepository repository;
    /**
     * Applications.
     * <p>应用集合。
     */
    private final WebApplicationInventory applications;
    /**
     * Bound web deployment service collaborator for deployment.
     * <p>处理部署的Web部署服务协作对象。
     */
    private final WebDeploymentService deployment;
    /**
     * Bound web ai service collaborator for the supplied web ai service.
     * <p>处理所提供的WebAI服务的WebAI服务协作对象。
     */
    private final WebAiService ai;
    /**
     * Credential references or scoped secret-access service.
     * <p>凭据引用或限定作用域的秘密访问服务。
     */
    private final WebApplicationSecrets secrets;
    /**
     * Bound gold debug windowstolinux web service backup web backup service collaborator for backups.
     * <p>处理备份集合的golddebugwindowstolinuxWeb服务备份Web备份服务协作对象。
     */
    private final gold.debug.windowstolinux.web.service.backup.WebBackupService backups;
    /**
     * Binds the supplied dependencies and state for web application service.
     * <p>为Web应用服务绑定传入的依赖及状态。
     *
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param sources sources / 源码集合
     * @param repository persistence boundary for the owned records / 所属记录的持久化边界
     * @param applications applications / 应用集合
     * @param deployment deployment / 部署
     * @param ai the supplied web ai service / 所提供的WebAI服务
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param backups backups / 备份集合
     */
    public WebApplicationService(WebServerService servers, WebSourceService sources, WebResourceRepository repository,
            WebApplicationInventory applications,WebDeploymentService deployment,WebAiService ai,WebApplicationSecrets secrets,gold.debug.windowstolinux.web.service.backup.WebBackupService backups) {
        this.servers = servers; this.sources = sources; this.repository = repository;
        this.applications=applications;this.deployment=deployment;this.ai=ai;this.secrets=secrets;
        this.backups=backups;
    }
    /**
     * Lists backups.
     * <p>列出备份集合。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode listBackups(WebRequestContext context) throws Exception { return backups.list(context); }
    /**
     * Uploads the local backup page state.
     * <p>上传本地备份页面状态。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode uploadBackup(WebRequestContext context,String name,InputStream input) throws Exception { return backups.upload(context,name,input); }
    /**
     * Downloads the local backup page state.
     * <p>下载本地备份页面状态。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved path / 构造或解析得到的路径
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public java.nio.file.Path downloadBackup(WebRequestContext context,String id) throws Exception { return backups.download(context,id); }
    /**
     * Lists applications.
     * <p>列出应用集合。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode listApplications(WebRequestContext context) throws Exception { return applications.list(context); }
    /**
     * Updates the application's presentation fields through the inventory service.
     * <p>通过清单服务更新应用展示字段。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode editApplication(WebRequestContext context,String id,JsonNode input) throws Exception { return applications.presentation(context,id,input); }
    /**
     * Persists secret.
     * <p>持久化秘密。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode saveSecret(WebRequestContext context,JsonNode input) throws Exception { return secrets.save(context,input); }
    /**
     * Lists AI analysis.
     * <p>列出AI 分析。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode listAi(WebRequestContext context) throws Exception { return ai.list(context); }
    /**
     * Persists AI analysis.
     * <p>持久化AI 分析。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved prepared web operation / 构造或解析得到的已准备Web操作
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public PreparedWebOperation saveAi(WebRequestContext context,String id,JsonNode input) throws Exception { return ai.save(context,id,input); }
    /**
     * Enables AI analysis.
     * <p>启用AI 分析。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode enableAi(WebRequestContext context,String id,JsonNode input) throws Exception { return ai.enabled(context,id,input); }
    /**
     * Reorders AI analysis.
     * <p>重新排序AI 分析。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode reorderAi(WebRequestContext context,JsonNode input) throws Exception { return ai.reorder(context,input); }
    /**
     * Deletes AI analysis.
     * <p>删除AI 分析。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public void deleteAi(WebRequestContext context,String id,long version) throws Exception { ai.delete(context,id,version); }
    /**
     * Lists server-profile and authenticated-session service.
     * <p>列出服务器资料及已认证会话服务。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode listServers(WebRequestContext context) throws Exception { return servers.list(context); }
    /**
     * Persists server identity or selected server configuration.
     * <p>持久化服务器身份或所选服务器配置。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param body body / 正文
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode saveServer(WebRequestContext context, String id, JsonNode body) throws Exception { return servers.save(context, id, body); }
    /**
     * Deletes server identity or selected server configuration.
     * <p>删除服务器身份或所选服务器配置。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public void deleteServer(WebRequestContext context, String id, long version) throws Exception { servers.delete(context, id, version); }
    /**
     * Lists sources.
     * <p>列出源码集合。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode listSources(WebRequestContext context) throws Exception { return sources.list(context); }
    /**
     * Begins source identity or content read by the operation.
     * <p>开始操作读取的源身份或内容。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode beginSource(WebRequestContext context, JsonNode input) throws Exception {
        WebRequestValidator.fields(input, "name"); return sources.begin(context, WebRequestValidator.text(input, "name", 100));
    }
    /**
     * Uploads source identity or content read by the operation.
     * <p>上传操作读取的源身份或内容。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public void uploadSource(WebRequestContext context, String id, String path, InputStream input) throws Exception { sources.upload(context, id, path, input); }
    /**
     * Uploads source or backup archive descriptor or filesystem path.
     * <p>上传源码或备份归档描述或文件系统路径。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param format format / 格式
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public void uploadArchive(WebRequestContext context, String id, String format, InputStream input) throws Exception { sources.archive(context, id, format, input); }
    /**
     * Finishes source identity or content read by the operation.
     * <p>完成操作读取的源身份或内容。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode finishSource(WebRequestContext context, String id) throws Exception { return sources.finish(context, id); }
    /**
     * Projects the current workspace member's persisted preferences into JSON.
     * <p>将当前工作区成员的持久化偏好设置投影为 JSON。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode preferences(WebRequestContext context) throws Exception { return WebJsonCodec.tree(repository.preferences(scope(context))); }
    /**
     * Validates the allowed non-secret preference fields and persists them within the current member scope.
     * <p>校验允许的非秘密偏好字段，并在当前成员作用域内持久化。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param body body / 正文
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public JsonNode savePreferences(WebRequestContext context, JsonNode body) throws Exception {
        WebRequestValidator.fields(body, "theme", "language", "navigationCollapsed");
        var values = new LinkedHashMap<String, String>();
        body.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) throw new IllegalArgumentException("Invalid preference");
            String value = entry.getValue().asText();
            boolean valid = switch (entry.getKey()) {
                case "theme" -> java.util.Set.of("light", "dark", "system").contains(value);
                case "language" -> java.util.Set.of("zh-CN", "en").contains(value);
                case "navigationCollapsed" -> java.util.Set.of("true", "false").contains(value);
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Invalid preference"); values.put(entry.getKey(), value);
        });
        repository.savePreferences(scope(context), values); return preferences(context);
    }
    /**
     * Dispatches an allowed task kind to its scoped preparation service before the task is persisted or scheduled.
     * <p>在任务持久化或调度前，将允许的任务类型分派到限定作用域准备服务。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved prepared web operation / 构造或解析得到的已准备Web操作
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public PreparedWebOperation prepare(WebRequestContext context, String kind, JsonNode input) throws Exception {
        var operation = switch (kind) {
            case "SERVER_PROBE" -> { WebRequestValidator.fields(input, "serverId"); yield servers.probe(context, WebRequestValidator.text(input, "serverId", 63)); }
            case "GIT_SNAPSHOT" -> sources.git(context, input);
            case "ANALYZE" -> { WebRequestValidator.fields(input, "sourceId"); yield sources.analyze(context, WebRequestValidator.text(input, "sourceId", 63)); }
            case "DEPLOY" -> deployment.prepare(context,input);
            case "LIFECYCLE" -> { WebRequestValidator.fields(input,"applicationId","action");yield applications.lifecycle(context,WebRequestValidator.text(input,"applicationId",63),WebRequestValidator.text(input,"action",40)); }
            case "APPLICATION_SCAN" -> { WebRequestValidator.fields(input,"serverId");yield applications.scan(context,WebRequestValidator.text(input,"serverId",63)); }
            case "APPLICATION_ADOPT" -> { WebRequestValidator.fields(input,"scanTaskId","candidateKey");yield applications.adopt(context,WebRequestValidator.text(input,"scanTaskId",63),WebRequestValidator.text(input,"candidateKey",512)); }
            case "BACKUP_CREATE", "RESTORE", "RESTORE_PREFLIGHT", "MIGRATE" -> backups.prepare(context,kind,input);
            default -> throw new NoSuchElementException("Operation not found");
        };
        return pinTargets(context,operation);
    }
    /**
     * Builds prepared web operation from the supplied pin targets inputs.
     * <p>根据所提供固定目标集合输入构建已准备Web操作。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param operation operation / 操作
     * @return prepared web operation from the supplied pin targets inputs / 根据所提供固定目标集合输入构建已准备Web操作
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private PreparedWebOperation pinTargets(WebRequestContext context,PreparedWebOperation operation) throws Exception {
        var endpoints=new java.util.LinkedHashMap<String,gold.debug.windowstolinux.shared.linux.connection.SshEndpoint>();
        for(String id:operation.serverIds()) endpoints.put(id,WebServerService.endpoint(servers.require(context,id)));
        var currentLocks=endpoints.keySet().stream().map(id->endpoints.get(id).host().toLowerCase(java.util.Locale.ROOT)+":"+endpoints.get(id).port()).toList();
        if(operation.mutating() && !new java.util.HashSet<>(currentLocks).equals(new java.util.HashSet<>(operation.lockKeys())))throw new IllegalStateException("Server endpoint changed while preparing the task");
        return new PreparedWebOperation(operation.kind(),operation.request(),operation.serverIds(),operation.lockKeys(),operation.mutating(),operation.sourceId(),operation.applicationId(),operation.backupId(),interaction->{
            for(var entry:endpoints.entrySet())if(!entry.getValue().equals(WebServerService.endpoint(servers.require(context,entry.getKey()))))throw new IllegalStateException("Server endpoint changed before task execution");
            return operation.work().execute(interaction);
        });
    }
    /**
     * Resolves the ownership scope supplied by the trusted caller.
     * <p>解析可信调用方提供的归属作用域。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return the ownership scope supplied by the trusted caller / 可信调用方提供的归属作用域
     */
    private static ResourceScope scope(WebRequestContext context) { return new ResourceScope(context.workspaceId(), context.userId()); }
}
