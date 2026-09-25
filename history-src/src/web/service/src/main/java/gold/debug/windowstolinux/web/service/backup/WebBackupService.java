package gold.debug.windowstolinux.web.service.backup;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.shared.backup.contract.validation.*;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.repository.WebResourceRepository;
import gold.debug.windowstolinux.web.file.workspace.*;
import gold.debug.windowstolinux.web.service.config.WebApplicationSecrets;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;
import gold.debug.windowstolinux.web.service.execution.lifecycle.WebApplicationInventory;
import gold.debug.windowstolinux.web.service.interaction.WebTaskInteractionService;
import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import tools.jackson.databind.JsonNode;

/**
 * Provides scoped backup upload/export and durable tasks using the desktop-compatible portable archive.
 * <p>使用兼容桌面的可移植归档提供限定作用域备份上传和导出及持久化任务。
 */
public final class WebBackupService {
    /**
     * Bound web resource repository collaborator for persistence boundary for the owned records.
     * <p>处理所属记录的持久化边界的Web资源仓库协作对象。
     */
    private final WebResourceRepository repository;

    /**
     * Controlled filesystem access or reviewed file inventory.
     * <p>受控文件系统访问或已审阅文件清单。
     */
    private final WebWorkspace files;

    /**
     * Bound web server service collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的Web服务器服务协作对象。
     */
    private final WebServerService servers;

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
     * Binds the supplied dependencies and state for web backup service.
     * <p>为Web备份服务绑定传入的依赖及状态。
     *
     * @param repository persistence boundary for the owned records / 所属记录的持久化边界
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param applications applications / 应用集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     */
    public WebBackupService(WebResourceRepository repository, WebWorkspace files, WebServerService servers,
            WebApplicationInventory applications, WebApplicationSecrets secrets) {
        this.repository = repository;
        this.files = files;
        this.servers = servers;
        this.applications = applications;
        this.secrets = secrets;
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
        return WebJsonCodec.tree(
                repository.list(scope(context), ResourceType.BACKUP).stream().map(WebBackupService::view).toList());
    }

    /**
     * Recovers temporary.
     * <p>恢复临时。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public void recoverTemporary(WebRequestContext context) throws Exception {
        for (var address : files.owned(context.workspaceId()))
            if (repository.find(scope(context), ResourceType.BACKUP, address.resourceId()).isEmpty())
                files.discard(address);
    }

    /**
     * Uploads json node.
     * <p>上传JSON节点。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public synchronized JsonNode upload(WebRequestContext context, String name, InputStream input) throws Exception {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))
            throw new IllegalArgumentException("Invalid backup name");
        String id = UUID.randomUUID().toString();
        var address = address(context, id);
        files.create(address);
        try {
            files.upload(address, "backup.zip", input);
            var validation = new BackupArchiveValidator(BackupArchivePolicy.defaults())
                    .validate(files.source(address).resolve("backup.zip"));
            files.complete(address);
            return view(save(context, id, name, null, validation));
        } catch (Exception failure) {
            files.discard(address);
            throw failure;
        }
    }

    /**
     * Downloads path.
     * <p>下载路径。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved path / 构造或解析得到的路径
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public Path download(WebRequestContext context, String id) throws Exception {
        var record = require(context, id);
        Path archive = files.source(address(context, id)).resolve("backup.zip");
        WebWorkspace.safeAncestors(archive);
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                || Files.size(archive) != ((Number) record.attributes().get("byte_count")).longValue())
            throw new IOException("Backup changed");
        var checked = new BackupArchiveValidator(BackupArchivePolicy.defaults()).validate(archive);
        if (!checked.archiveSha256().equals(record.attributes().get("digest")))
            throw new IOException("Backup changed");
        return archive;
    }

    /**
     * Validates a backup task request and resolves its scoped resources and target locks before scheduling.
     * <p>校验备份任务请求，并在调度前解析限定作用域资源及目标锁。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved prepared web operation / 构造或解析得到的已准备Web操作
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public PreparedWebOperation prepare(WebRequestContext context, String kind, JsonNode input) throws Exception {
        if (kind.equals("BACKUP_CREATE")) {
            WebRequestValidator.fields(input, "applicationId");
            String id = WebRequestValidator.text(input, "applicationId", 63);
            var row = applications.require(context, id);
            applications.graph(row);
            String serverId = row.attributes().get("server_id").toString();
            return new PreparedWebOperation(kind, input, List.of(serverId),
                    List.of(WebServerService.lockKey(servers.require(context, serverId))), true, null, id, null,
                    interaction -> {
                        WebTaskInteractionService.approve(interaction, "BACKUP_STOP_WINDOW",
                                WebJsonCodec.object().put("applicationId", id));
                        char[] password = secrets.decisionSecret(context, interaction, "backup.password");
                        try {
                            return create(context, id, password, interaction);
                        } finally {
                            Arrays.fill(password, '\0');
                        }
                    });
        }
        if (kind.equals("RESTORE") || kind.equals("RESTORE_PREFLIGHT")) {
            WebRequestValidator.fields(input, "backupId", "serverId");
            String id = WebRequestValidator.text(input, "backupId", 63),
                    serverId = WebRequestValidator.text(input, "serverId", 63);
            require(context, id);
            return new PreparedWebOperation(kind, input, List.of(serverId),
                    List.of(WebServerService.lockKey(servers.require(context, serverId))), true, null, null, id,
                    interaction -> {
                        if (kind.equals("RESTORE"))
                            WebTaskInteractionService.approve(interaction, "RESTORE_TARGET",
                                    WebJsonCodec.object().put("backupId", id).put("serverId", serverId));
                        char[] password = secrets.decisionSecret(context, interaction, "backup.password");
                        try {
                            return restore(context, id, serverId, password, interaction,
                                    kind.equals("RESTORE_PREFLIGHT"));
                        } finally {
                            Arrays.fill(password, '\0');
                        }
                    });
        }
        if (kind.equals("MIGRATE"))
            return new WebOfflineMigration(this, applications, servers, secrets).prepare(context, input);
        throw new NoSuchElementException();
    }

    /**
     * Collects the managed backup into quota-controlled storage and persists its validated archive metadata.
     * <p>将受管备份采集到配额受控存储，并持久化已验证归档元数据。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param applicationId managed application identifier / 受管应用标识
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public synchronized JsonNode create(WebRequestContext context, String applicationId, char[] password,
            TaskInteraction interaction) throws Exception {
        return create(context, applicationId, password, interaction, null);
    }

    /**
     * Collects the managed backup into quota-controlled storage and persists its validated archive metadata.
     * <p>将受管备份采集到配额受控存储，并持久化已验证归档元数据。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param applicationId managed application identifier / 受管应用标识
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param heldMaintenance held maintenance / 已持有维护
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public synchronized JsonNode create(WebRequestContext context, String applicationId, char[] password,
            TaskInteraction interaction, String heldMaintenance) throws Exception {
        var row = applications.require(context, applicationId);
        var graph = applications.graph(row);
        String serverId = row.attributes().get("server_id").toString();
        if (Set.of("RUNNING", "REVALIDATION_REQUIRED", "MANUAL_RECOVERY_REQUIRED")
                .contains(WebJsonCodec.read(row.document()).path("deploymentState").asText()))
            throw new IllegalStateException("Revalidate publication before backup");
        if (!servers.identity(context, serverId).equals(graph.components().getFirst().application().server()))
            throw new IllegalStateException("Server identity changed");
        var refs = graph.components().stream().flatMap(component -> component.secrets().stream()).distinct().toList();
        var resolved = secrets.resolve(context, refs);
        String id = UUID.randomUUID().toString();
        var address = address(context, id);
        String workId = UUID.randomUUID().toString();
        var workAddress = address(context, workId);
        boolean saved = false, workCreated = false, destinationCreated = false;
        try {
            Path work = files.create(workAddress);
            workCreated = true;
            files.create(address);
            destinationCreated = true;
            files.checkCapacity(workAddress, files.quota().projectBytes() - WebWorkspace.size(work));
            Path output = files.source(workAddress).resolve("backup.zip");
            Path material = Files.createDirectory(work.resolve("material"));
            var validation = servers.withSession(context, serverId, interaction,
                    session -> new WebBackupCollection(files.quota(), files.minimumFreeBytes()).create(graph, session,
                            material, output, password, resolved, interaction, heldMaintenance));
            try (var stream = Files.newInputStream(output)) {
                files.upload(address, "backup.zip", stream);
            }
            files.complete(address);
            var record = save(context, id, graph.applicationId(), applicationId, validation);
            saved = true;
            return view(record);
        } finally {
            resolved.forEach(ResolvedSecretRevision::close);
            try {
                if (workCreated)
                    files.discard(workAddress);
            } finally {
                if (!saved && destinationCreated)
                    files.discard(address);
            }
        }
    }

    /**
     * Restores json node.
     * <p>恢复JSON节点。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param backupId backup id / 备份标识
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param preflightOnly preflight only / 预检仅
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public synchronized JsonNode restore(WebRequestContext context, String backupId, String serverId, char[] password,
            TaskInteraction interaction, boolean preflightOnly) throws Exception {
        Path archive = download(context, backupId);
        var address = address(context, UUID.randomUUID().toString());
        Path directory = files.create(address);
        try {
            long bytes = new BackupArchiveValidator(BackupArchivePolicy.defaults()).validate(archive).manifest()
                    .members().stream().mapToLong(member -> member.size()).sum();
            files.checkCapacity(address, bytes);
            try (var material = WebRestoreMaterial.read(archive, directory, password.clone(), files.quota().fileBytes(),
                    files.minimumFreeBytes())) {
                return new WebBackupRestore(servers, applications, secrets).execute(context, serverId, material,
                        interaction, preflightOnly);
            }
        } finally {
            files.discard(address);
        }
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
        return repository.find(scope(context), ResourceType.BACKUP, id).orElseThrow(NoSuchElementException::new);
    }

    /**
     * Persists stored resource.
     * <p>持久化已存储资源。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param applicationId managed application identifier / 受管应用标识
     * @param validation validation / 校验
     * @return constructed or resolved stored resource / 构造或解析得到的已存储资源
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private StoredResource save(WebRequestContext context, String id, String name, String applicationId,
            BackupArchiveValidation validation) throws Exception {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("application_id", applicationId);
        fields.put("digest", validation.archiveSha256());
        fields.put("byte_count", Files.size(files.source(address(context, id)).resolve("backup.zip")));
        var manifest = validation.manifest();
        var document = WebJsonCodec.object().put("application", manifest.applicationId())
                .put("schema", manifest.schemaVersion()).put("canRestore", manifest.supportsAutomaticActivation())
                .put("components", manifest.inventory().components().size())
                .put("database", manifest.inventory().database().type().name())
                .put("provenance", validation.provenanceStatus().name());
        return repository.save(scope(context), ResourceType.BACKUP, id, name, fields, WebJsonCodec.write(document), 0);
    }

    /**
     * Projects stored non-secret resource metadata into its Web response fields.
     * <p>将持久化的非秘密资源元数据投影为 Web 响应字段。
     *
     * @param row row / 数据行
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     */
    private static JsonNode view(StoredResource row) {
        var result = WebJsonCodec.object().put("id", row.id()).put("name", row.name()).put("version", row.version())
                .put("createdAt", row.createdAt()).put("digest", row.attributes().get("digest").toString())
                .put("byteCount", ((Number) row.attributes().get("byte_count")).longValue());
        result.set("inspection", WebJsonCodec.read(row.document()));
        return result;
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

    /**
     * Builds workspace address from the supplied address inputs.
     * <p>根据所提供地址输入构建工作区地址。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return workspace address from the supplied address inputs / 根据所提供地址输入构建工作区地址
     */
    private static WorkspaceAddress address(WebRequestContext context, String id) {
        return new WorkspaceAddress(context.workspaceId(), id);
    }
}
