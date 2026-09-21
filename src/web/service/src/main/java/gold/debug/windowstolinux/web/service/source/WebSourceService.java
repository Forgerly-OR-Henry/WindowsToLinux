package gold.debug.windowstolinux.web.service.source;

import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;

import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.repository.WebResourceRepository;
import gold.debug.windowstolinux.web.file.workspace.*;
import gold.debug.windowstolinux.web.file.upload.SourceArchiveUpload;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.shared.source.archive.SafeSourceArchivePreparer;
import gold.debug.windowstolinux.shared.source.archive.SourceArchive;
import gold.debug.windowstolinux.shared.source.snapshot.SourceDirectorySnapshot;
import gold.debug.windowstolinux.shared.git.*;
import gold.debug.windowstolinux.shared.git.snapshot.GitSnapshotPreparer;
import gold.debug.windowstolinux.shared.analyze.component.ProjectComponentDiscovery;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Prepares browser-owned uploads and pinned Git snapshots using static reads and canonical archives.
 * <p>通过静态读取及规范归档准备浏览器持有的上传及固定 Git 快照。
 */
public final class WebSourceService {
    /**
     * Bound web resource repository collaborator for persistence boundary for the owned records.
     * <p>处理所属记录的持久化边界的Web资源仓库协作对象。
     */
    private final WebResourceRepository repository;
    /**
     * Platform-owned work area with enforced path boundaries.
     * <p>具有路径边界约束的平台工作区。
     */
    private final WebWorkspace workspace;
    /**
     * Temporary retention.
     * <p>临时保留。
     */
    private final java.time.Duration temporaryRetention;
    /**
     * Binds the supplied dependencies and state for web source service.
     * <p>为Web源码服务绑定传入的依赖及状态。
     *
     * @param repository persistence boundary for the owned records / 所属记录的持久化边界
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param temporaryRetention temporary retention / 临时保留
     */
    public WebSourceService(WebResourceRepository repository, WebWorkspace workspace, java.time.Duration temporaryRetention) {
        this.repository = repository; this.workspace = workspace; this.temporaryRetention = temporaryRetention;
    }

    /**
     * Recovers temporary.
     * <p>恢复临时。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public void recoverTemporary(WebRequestContext context) throws Exception {
        for(var row:repository.list(scope(context),ResourceType.SOURCE)) {
            var address=address(context,row.id());
            if("READY".equals(row.attributes().get("state"))) {
                if(workspace.exists(address))for(String child:List.of("snapshots","git","verify.tar.gz","upload.archive"))workspace.discardChild(address,child);
            } else if(java.time.Instant.parse(row.updatedAt()).isBefore(java.time.Instant.now().minus(temporaryRetention))) {
                if(workspace.exists(address))workspace.discard(address);
                var fields=new LinkedHashMap<>(row.attributes());fields.put("state","FAILED");
                repository.save(scope(context),ResourceType.SOURCE,row.id(),row.name(),fields,row.document(),row.version());
            }
        }
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
        return WebJsonCodec.tree(repository.list(scope(context), ResourceType.SOURCE).stream().map(WebSourceService::view).toList());
    }
    /**
     * Begins json node.
     * <p>开始JSON节点。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public JsonNode begin(WebRequestContext context, String name) throws Exception {
        String id = UUID.randomUUID().toString();
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) throw new IllegalArgumentException("Use a plain project name");
        var fields = new LinkedHashMap<String, Object>();
        fields.put("state", "UPLOADING"); fields.put("kind", "UPLOAD"); fields.put("digest", null); fields.put("byte_count", 0);
        var stored = repository.save(scope(context), ResourceType.SOURCE, id, name, fields, "{}", 0);
        workspace.create(address(context, id)); return view(stored);
    }
    /**
     * Uploads web source.
     * <p>上传Web源码。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public synchronized void upload(WebRequestContext context, String id, String path, InputStream input) throws Exception {
        requireUploading(context, id); workspace.upload(address(context, id), path, input);
    }
    /**
     * Extracts an archive into the uploading source resource and persists FAILED state when extraction fails.
     * <p>将归档提取到正在上传的源码资源，并在提取失败时持久化 FAILED 状态。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param format format / 格式
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public synchronized void archive(WebRequestContext context, String id, String format, InputStream input) throws Exception {
        var stored = requireUploading(context, id);
        try { new SourceArchiveUpload(workspace).extract(address(context, id), format, input); }
        catch (Exception failure) {
            var fields = new LinkedHashMap<>(stored.attributes()); fields.put("state", "FAILED");
            repository.save(scope(context), ResourceType.SOURCE, id, stored.name(), fields, stored.document(), stored.version());
            workspace.discard(address(context, id)); throw failure;
        }
    }
    /**
     * Finishes json node.
     * <p>完成JSON节点。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public synchronized JsonNode finish(WebRequestContext context, String id) throws Exception {
        var stored = requireUploading(context, id); var address = address(context, id);
        SourceArchive archive = new SafeSourceArchivePreparer().archive(workspace.source(address), workspace.directory(address).resolve("source.tar.gz"));
        workspace.complete(address);
        var fields = new LinkedHashMap<>(stored.attributes()); fields.put("state", "READY"); fields.put("digest", archive.contentSha256()); fields.put("byte_count", archive.uncompressedByteCount());
        return view(repository.save(scope(context), ResourceType.SOURCE, id, stored.name(), fields, "{}", stored.version()));
    }

    /**
     * Prepares a scoped Git source task with a validated remote reference and controlled upload state.
     * <p>使用已校验远端引用及受控上传状态准备限定作用域 Git 源码任务。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved prepared web operation / 构造或解析得到的已准备Web操作
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public PreparedWebOperation git(WebRequestContext context, JsonNode input) throws Exception {
        WebRequestValidator.fields(input, "url", "referenceKind", "reference", "name");
        GitRemote remote = GitRemote.parse(WebRequestValidator.text(input, "url", 2048));
        if (!remote.location().getScheme().equals("https")) throw new IllegalArgumentException("Web Git sources require credential-free HTTPS");
        String host = remote.host().orElseThrow();
        GitReference reference = switch (input.path("referenceKind").asText("default")) {
            case "default" -> new GitReference.DefaultBranch();
            case "branch" -> new GitReference.Branch(WebRequestValidator.text(input, "reference", 255));
            case "tag" -> new GitReference.Tag(WebRequestValidator.text(input, "reference", 255));
            case "commit" -> new GitReference.Commit(WebRequestValidator.text(input, "reference", 40));
            default -> throw new IllegalArgumentException("Invalid Git reference kind");
        };
        String name = input.path("name").asText(remote.location().getPath().replaceFirst(".*/", "").replaceFirst("\\.git$", ""));
        var created = begin(context, name); String id = created.path("id").asText();
        var request = new GitSourceRequest(remote, reference, Set.of(host), workspace.quota().projectBytes(), false);
        return new PreparedWebOperation("GIT_SNAPSHOT", input, List.of(), List.of(), false, id, null, null, interaction -> {
            var address = address(context, id);
            try {
                interaction.progress("SOURCE_FETCHING", WebJsonCodec.object().put("sourceId", id));
                Path gitRoot = Files.createDirectory(workspace.directory(address).resolve("git"));
                var snapshot = prepareGit(request,gitRoot,address);
                try (var archive = Files.newInputStream(snapshot.archive().archivePath())) {
                    new SourceArchiveUpload(workspace).extract(address, "tar.gz", archive);
                }
                workspace.discardChild(address,"git");
                interaction.checkCancelled();
                finish(context, id);
                var stored = require(context, id);
                var fields = new LinkedHashMap<>(stored.attributes()); fields.put("kind", "GIT");
                return view(repository.save(scope(context), ResourceType.SOURCE, id, stored.name(), fields,
                        WebJsonCodec.write(Map.of("remote", remote.location().toString(), "commit", snapshot.commit())), stored.version()));
            } catch (Exception failure) {
                var stored = require(context, id); var fields = new LinkedHashMap<>(stored.attributes()); fields.put("state", "FAILED");
                repository.save(scope(context), ResourceType.SOURCE, id, stored.name(), fields, stored.document(), stored.version());
                workspace.discardChild(address,"git"); workspace.discard(address);
                throw failure;
            }
        });
    }

    /**
     * Creates and verifies the pinned Git snapshot in owned storage and records its ready source identity.
     * <p>在自有存储创建并验证固定 Git 快照，并记录其就绪源码身份。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @param address address / 地址
     * @return and verifies the pinned Git snapshot in owned storage and records its ready source identity / 在自有存储创建并验证固定 Git 快照，并记录其就绪源码身份
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private GitSnapshot prepareGit(GitSourceRequest request,Path directory,WorkspaceAddress address) throws Exception {
        Thread owner=Thread.currentThread();
        var exceeded=new java.util.concurrent.atomic.AtomicReference<Exception>();
        var monitor=Thread.ofVirtual().name("web-git-quota").start(() -> {
            try {
                while(!Thread.currentThread().isInterrupted()) { workspace.checkCapacity(address,0); Thread.sleep(250); }
            } catch(InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch(Exception failure) { exceeded.set(failure);owner.interrupt(); }
        });
        try { return new GitSnapshotPreparer().prepare(request,directory); }
        finally {
            monitor.interrupt(); boolean interrupted=Thread.interrupted();
            monitor.join();
            if(exceeded.get()!=null) throw exceeded.get();
            if(interrupted)owner.interrupt();
        }
    }

    /**
     * Prepares a read-only analysis task for the current ready source revision.
     * <p>为当前就绪源码修订准备只读分析任务。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved prepared web operation / 构造或解析得到的已准备Web操作
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public PreparedWebOperation analyze(WebRequestContext context, String id) throws Exception {
        requireReady(context, id);
        return new PreparedWebOperation("ANALYZE", WebJsonCodec.object().put("sourceId", id), List.of(), List.of(), false, id, null, null,
                interaction -> {
                    interaction.progress("SOURCE_ANALYZING", WebJsonCodec.object());
                    try (var snapshot = snapshot(context, id)) {
                        var components = new ArrayList<JsonNode>();
                        for (var component : new ProjectComponentDiscovery().discover(snapshot.directory())) {
                            var value = WebJsonCodec.object().put("id", component.id()).put("path", component.relativeRoot().toString().replace('\\', '/'));
                            value.set("types", WebJsonCodec.tree(component.types()));
                            if (component.types().size() == 1) {
                                var assessment = new DeploymentAnalysisCoordinator().analyzeForDatabaseReview(snapshot.directory().resolve(component.relativeRoot()), component.types().getFirst());
                                value.put("admission", assessment.admission().name());
                                value.set("rejections", WebJsonCodec.tree(assessment.rejections().stream().map(rejection -> rejection.code()).toList()));
                                assessment.facts().ifPresent(facts -> value.put("applicationId", facts.applicationId()).put("buildTool", facts.buildTool().name()));
                                assessment.runtimeSuggestion().ifPresent(suggestion -> value.set("suggestion", WebJsonCodec.tree(suggestion)));
                            }
                            components.add(value); interaction.checkCancelled();
                        }
                        return WebJsonCodec.object().put("sourceId", id).set("components", WebJsonCodec.tree(components));
                    }
                });
    }

    /**
     * Rebuilds a snapshot from ready owned source and checks its frozen identity so local tampering is detected.
     * <p>从就绪自有源码重建快照并检查其冻结身份，以检测本地篡改。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved source directory snapshot / 构造或解析得到的源码目录快照
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public synchronized SourceDirectorySnapshot snapshot(WebRequestContext context, String id) throws Exception {
        var stored = requireReady(context, id); var address = address(context, id);
        Path directory = workspace.directory(address);
        // Recompute from the frozen directory so local tampering cannot silently change a reviewed source. / 从冻结目录重新计算，避免本地篡改静默改变已审阅源码。
        var archive = new SafeSourceArchivePreparer().archive(workspace.source(address), directory.resolve("verify.tar.gz"));
        try {
            if (!archive.contentSha256().equals(stored.attributes().get("digest"))) throw new IllegalStateException("Source digest changed; upload again");
            return SourceDirectorySnapshot.create(workspace.source(address), directory.resolve("snapshots"), stored.name());
        } finally { Files.deleteIfExists(directory.resolve("verify.tar.gz")); }
    }
    /**
     * Creates an operation-scoped temporary directory beneath a ready source resource; its caller owns cleanup.
     * <p>在就绪源码资源下创建操作专用临时目录；调用方负责清理。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return an operation-scoped temporary directory beneath a ready source resource; its caller owns cleanup / 在就绪源码资源下创建操作专用临时目录；调用方负责清理
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public Path operationDirectory(WebRequestContext context, String id) throws Exception {
        requireReady(context, id);
        return Files.createTempDirectory(workspace.directory(address(context, id)), "operation-");
    }
    /**
     * Validates and returns ready and rejects inputs outside the declared constraints.
     * <p>校验并返回就绪并拒绝超出已声明约束的输入。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved stored resource / 构造或解析得到的已存储资源
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public StoredResource requireReady(WebRequestContext context, String id) throws Exception {
        var stored = require(context, id);
        if (!"READY".equals(stored.attributes().get("state"))) throw new IllegalStateException("Source upload is not complete");
        return stored;
    }
    /**
     * Validates and returns uploading and rejects inputs outside the declared constraints.
     * <p>校验并返回上传中并拒绝超出已声明约束的输入。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved stored resource / 构造或解析得到的已存储资源
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private StoredResource requireUploading(WebRequestContext context, String id) throws Exception {
        var stored = require(context, id);
        if (!"UPLOADING".equals(stored.attributes().get("state"))) throw new IllegalStateException("Source is not accepting uploads"); return stored;
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
    private StoredResource require(WebRequestContext context, String id) throws Exception {
        return repository.find(scope(context), ResourceType.SOURCE, id).orElseThrow(() -> new NoSuchElementException("Source not found"));
    }
    /**
     * Projects stored non-secret resource metadata into its Web response fields.
     * <p>将持久化的非秘密资源元数据投影为 Web 响应字段。
     *
     * @param row row / 数据行
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     */
    private static JsonNode view(StoredResource row) {
        var value = WebJsonCodec.object().put("id", row.id()).put("name", row.name()).put("state", (String) row.attributes().get("state"))
                .put("kind", (String) row.attributes().get("kind")).put("digest", (String) row.attributes().get("digest"))
                .put("byteCount", ((Number) row.attributes().get("byte_count")).longValue()).put("createdAt", row.createdAt());
        value.set("provenance", WebJsonCodec.read(row.document())); return value;
    }
    /**
     * Resolves the ownership scope supplied by the trusted caller.
     * <p>解析可信调用方提供的归属作用域。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return the ownership scope supplied by the trusted caller / 可信调用方提供的归属作用域
     */
    private static ResourceScope scope(WebRequestContext context) { return new ResourceScope(context.workspaceId(), context.userId()); }
    /**
     * Builds workspace address from the supplied address inputs.
     * <p>根据所提供地址输入构建工作区地址。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return workspace address from the supplied address inputs / 根据所提供地址输入构建工作区地址
     */
    private static WorkspaceAddress address(WebRequestContext context, String id) { return new WorkspaceAddress(context.workspaceId(), id); }
}
