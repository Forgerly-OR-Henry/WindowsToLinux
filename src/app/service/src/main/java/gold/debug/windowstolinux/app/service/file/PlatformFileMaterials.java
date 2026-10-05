package gold.debug.windowstolinux.app.service.file;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Callable;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.file.source.*;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.appapi.file.material.*;
import gold.debug.windowstolinux.appapi.file.source.FileSelection;
import gold.debug.windowstolinux.appapi.file.work.FileWorkspace;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;

/** Transfers exact original workspace custody and persists consumer holds before access. / 转移精确原工作目录接管并在访问前持久保存消费方持有。 */
public final class PlatformFileMaterials implements FileMaterialService, AutoCloseable {
    /** Original engine and physical root. / 原引擎及物理根。 */
    private final PlatformDatabase database;

    /** Authentic original task authority. / 真实原任务授权方。 */
    private final PlatformTaskRuntime tasks;

    /** Activation's exact original code identity. / 启用精确原代码身份。 */
    private final String owner, version;

    /** Activation's ordinary selections and workspace handles. / 启用普通选择及工作目录句柄。 */
    private final PlatformFileAccess files;

    /** Narrow original resource transactions. / 窄原资源事务。 */
    private final TaskLedger ledger;

    /** Retained exact material facts. / 保留精确素材事实。 */
    private final PlatformMaterialStore store;

    /** Actual synchronous operation lifetime. / 实际同步操作寿命。 */
    private final TaskCapabilityScope scope;

    /** Same engine actual capture custody. / 同引擎实际捕获接管。 */
    private final PlatformMaterialCoordinator coordinator;

    /** Actual bound consumer holds. / 实际绑定消费方持有。 */
    private final Map<UUID, Hold> holds = new HashMap<>();

    /** Binds the original file activation without performing I/O. / 绑定原文件启用，不执行读写。
     * @param database original engine / 原引擎
     * @param tasks genuine task runtime / 真实任务运行时
     * @param owner activation owner / 启用所有者
     * @param version exact loaded release / 精确已加载发布
     * @param files same activation's file owner / 同启用文件所有者 */
    PlatformFileMaterials(PlatformDatabase database, PlatformTaskRuntime tasks, String owner, String version, PlatformFileAccess files) {
        this.database = database;
        this.tasks = tasks;
        this.owner = owner;
        this.version = version;
        this.files = files;
        ledger = new TaskLedger(database);
        store = new PlatformMaterialStore(database);
        coordinator = PlatformMaterialCoordinator.forEngine(database);
        scope = new TaskCapabilityScope(database, tasks, owner, version, Optional.empty(), "material-access-");
    }

    /** Takes custody only after the producer has ended all original writers. / 仅在生产方结束全部原写入者后接管。
     * @param originalWorkspace original owned handle / 原所属句柄
     * @param publication pre-recorded immutable identities / 预记不可变身份
     * @param task authentic original task / 真实原任务
     * @return original published reference / 原发布引用
     * @throws ApiException if custody or physical sealing is uncertain / 接管或物理封存不确定时 */
    @Override
    public MaterialRef publish(FileWorkspace originalWorkspace, MaterialPublication publication, TaskContext task) throws ApiException {
        Objects.requireNonNull(publication);
        return call(task, false, () -> {
            if (tasks.reconciling(task))
                throw error(ProblemCode.ACCESS_DENIED, "MATERIAL_RECOVERY_PUBLISH", "Recovery cannot publish new material");
            var graph = graph(task);
            var producer = tasks.callbacks().originalRelease(task, owner, version);
            var consumer = graph.release(graph.owner());
            try {
                SourceMaterialManifest.validate(publication.relativePaths());
            } catch (IOException | IllegalArgumentException invalid) {
                throw error(ProblemCode.INVALID_INPUT, "MATERIAL_PUBLICATION", "Material publication paths exceed their canonical budget");
            }
            if (!store.quiescent(task.id(), owner))
                throw error(ProblemCode.BUSY, "MATERIAL_PRODUCER_ACTIVE", "Original producer process release is unconfirmed");
            var capture = coordinator.capture(task.id(), publication.materialId());
            synchronized (capture) {
                if (capture.active || capture.transfer != null || store.findRoot(task.id(), publication.materialId()).isPresent())
                    throw error(ProblemCode.ACCESS_DENIED, "MATERIAL_ALREADY_RECORDED", "Original material identity already has custody");
                capture.active = true;
            }
            try {
                var original = files.custody.transfer(originalWorkspace, task);
                synchronized (capture) {
                    capture.transfer = original;
                }
                var header = PlatformMaterialHeader.capturing(publication, task.id(), producer, consumer, original.id(), original.work().identity());
                var resource = new TaskResource("FILE", PlatformMaterialStore.ROOT + publication.materialId(), consumer.owner(), false);
                var rootLease = tasks.retain(task, resource, header.recovery(), true);
                var manifest = SourceMaterialManifest.capture(original.work(), publication.relativePaths(), tasks.operationCancellation(task));
                var published = header.published(manifest);
                ledger.materials().transferSourceWorkspace(task.id(), original.lease().id(), new TaskResource("FILE",
                    "source-workspace-v1:" + original.id(), owner, false), rootLease.id(), resource, header.recovery(), published.recovery());
                synchronized (capture) {
                    capture.finish();
                }
                return published.reference();
            } catch (Exception failure) {
                synchronized (capture) {
                    if (capture.transfer == null && failure instanceof ApiException denied)
                        throw denied;
                }
                tasks.reportUncertainEffect(task);
                throw error(ProblemCode.UNKNOWN, "MATERIAL_CAPTURE_UNCERTAIN", "Original material custody requires exact reconciliation");
            } finally {
                synchronized (capture) {
                    capture.active = false;
                }
            }
        });
    }

    /** Persists an exact hold only while the root remains published. / 仅在根仍为发布状态时持久保存精确持有。
     * @param original exact original reference / 精确原引用
     * @param task authentic original root / 真实原根
     * @return original consumer-local hold / 原消费方本地持有
     * @throws ApiException if exact ownership or persistence differs / 精确归属或持久保存不同时 */
    @Override
    public FileMaterialHold acquire(MaterialRef original, TaskContext task) throws ApiException {
        return call(task, false, () -> {
            var root = authenticate(original, task);
            if (tasks.reconciling(task) || !root.header().state().equals("PUBLISHED"))
                throw error(ProblemCode.ACCESS_DENIED, "MATERIAL_NOT_PUBLISHED", "Only normal original published material can be acquired");
            var work = physical(root.header());
            var manifest = manifest(root.header(), work, tasks.operationCancellation(task));
            UUID id = UUID.randomUUID();
            var fields = new TreeMap<String, String>();
            fields.put("materialId", original.id().toString());
            fields.put("holdId", id.toString());
            fields.put("taskId", task.id().toString());
            fields.put("consumerId", owner);
            fields.put("consumerVersion", version);
            fields.put("materialDigest", original.manifestSha256());
            fields.put("revision", original.revision());
            fields.put("bootId", database.bootId().toString());
            fields.put("state", "ACTIVE");
            var recovery = new ResourceRecovery("material-hold.v1", fields);
            var resource = new TaskResource("FILE", PlatformMaterialStore.HOLD + original.id() + "/" + id, owner, false);
            UUID lease;
            try {
                lease = ledger.materials().acquireMaterialHold(task.id(), root.lease(), root.resource(), root.header().recovery(), resource, recovery);
            } catch (SQLException uncertain) {
                tasks.reportUncertainEffect(task);
                throw error(ProblemCode.UNKNOWN, "MATERIAL_HOLD_CAPTURE", "Original material hold persistence is unconfirmed");
            }
            var result = new Hold(task, original, new PlatformMaterialStore.Hold(lease, resource, recovery), work, manifest, false);
            synchronized (this) {
                holds.put(id, result);
            }
            return result;
        });
    }

    /** Rebinds every exact old hold for cleanup only, without creating replacement resources. / 仅为清理重绑每个精确旧持有，不创建替代资源。
     * @param original exact original reference / 精确原引用
     * @param task authentic original root recovery / 真实原根恢复
     * @return close-only original holds / 仅清理原持有
     * @throws ApiException if any old evidence is uncertain / 任一旧证据不确定时 */
    @Override
    public List<FileMaterialHold> recoverHolds(MaterialRef original, TaskContext task) throws ApiException {
        return call(task, true, () -> {
            var root = authenticate(original, task);
            if (!tasks.reconciling(task) || root.released() || root.header().state().equals("CAPTURING"))
                throw error(ProblemCode.ACCESS_DENIED, "MATERIAL_HOLD_RECOVERY", "Original root recovery is required");
            var persistedHolds = store.holds(task.id(), original.id());
            if (persistedHolds.isEmpty())
                return List.of();
            var work = physical(root.header());
            var manifest = manifest(root.header(), work, () -> false);
            var result = new ArrayList<FileMaterialHold>();
            synchronized (this) {
                for (var persisted : persistedHolds) {
                    requireHold(persisted, original, task);
                    UUID id = PlatformMaterialHeader.uuid(persisted.recovery().fields().get("holdId"));
                    if (database.bootId().toString().equals(persisted.recovery().fields().get("bootId")) || holds.containsKey(id))
                        throw error(ProblemCode.UNKNOWN, "MATERIAL_HOLD_BOUND", "Original material hold already has actual ownership");
                    result.add(new Hold(task, original, persisted, work, manifest, true));
                }
                for (var value : result)
                    holds.put(value.id(), (Hold) value);
            }
            return List.copyOf(result);
        });
    }

    /** Returns original retained metadata even after confirmed physical release. / 即使已确认物理释放仍返回原保留元数据。
     * @param materialId original material / 原素材
     * @param operationId original operation / 原操作
     * @param task authentic original task / 真实原任务
     * @return exact original reference / 精确原引用
     * @throws ApiException if original facts are absent or uncertain / 原事实缺失或不确定时 */
    @Override
    public MaterialRef find(UUID materialId, UUID operationId, TaskContext task) throws ApiException {
        return call(task, true, () -> {
            var root = store.root(task.id(), materialId);
            root.header().requireGraph(graph(task));
            if (!root.header().field("operationId").equals(operationId.toString())
                || !(owner.equals(root.header().field("consumerId")) && version.equals(root.header().field("consumerVersion"))
                || owner.equals(root.header().field("producerId")) && version.equals(root.header().field("producerVersion"))))
                throw error(ProblemCode.ACCESS_DENIED, "MATERIAL_ORIGINAL_IDENTITY", "Original material belongs to another operation or release");
            if (root.header().state().equals("CAPTURING"))
                throw error(ProblemCode.UNKNOWN, "MATERIAL_CAPTURE_PENDING", "Original material publication is unconfirmed");
            if (owner.equals(root.header().field("producerId")))
                files.custody.confirmMaterialWorkspace(task, PlatformMaterialHeader.uuid(root.header().field("workspaceId")));
            return root.header().reference();
        });
    }

    /** Inspects only the authenticated original root or producer, without granting access. / 仅检查已认证原根或生产方，不授予访问。
     * @param materialId original material / 原素材
     * @param operationId original operation / 原操作
     * @param task authentic original task / 真实原任务
     * @return exact original read-only facts / 精确原只读事实
     * @throws ApiException if original evidence differs / 原证据不同时 */
    @Override
    public MaterialInspection inspect(UUID materialId, UUID operationId, TaskContext task) throws ApiException {
        return call(task, true, () -> {
            var root = original(materialId, operationId, task, false);
            if (root.isEmpty())
                return new MaterialInspection(MaterialState.ABSENT, Optional.empty());
            var facts = root.get();
            if (facts.released() || !facts.header().field("manifestSha256").isEmpty())
                synchronized (coordinator.capture(task.id(), materialId)) {
                    coordinator.capture(task.id(), materialId).finish();
                }
            return facts.header().inspection();
        });
    }

    /** Releases only proven unpublished original custody, retaining uncertain physical evidence. / 仅释放已证未发布原接管，保留不确定物理证据。
     * @param materialId original material / 原素材
     * @param operationId original operation / 原操作
     * @param task authentic original root cleanup / 真实原根清理
     * @return original terminal or inapplicable facts / 原终态或不适用事实
     * @throws ApiException if capture or cleanup remains uncertain / 捕获或清理仍不确定时 */
    @Override
    public MaterialInspection releaseUnpublished(UUID materialId, UUID operationId, TaskContext task) throws ApiException {
        return call(task, true, () -> {
            var capture = coordinator.capture(task.id(), materialId);
            synchronized (capture) {
                var found = original(materialId, operationId, task, true);
                if (found.isEmpty()) {
                    if (capture.active || capture.transfer != null)
                        throw error(ProblemCode.UNKNOWN, "MATERIAL_CAPTURE_NOT_RECORDED", "Actual original custody has no confirmed root record");
                    return new MaterialInspection(MaterialState.ABSENT, Optional.empty());
                }
                var root = found.get();
                var header = root.header();
                if (root.released()) {
                    capture.finish();
                    return header.inspection();
                }
                if (!header.field("manifestSha256").isEmpty())
                    return header.inspection();
                if (capture.active || !store.quiescent(task.id(), header.field("producerId")))
                    throw error(ProblemCode.BUSY, "MATERIAL_CAPTURE_ACTIVE", "Original producer capture or process is still active");
                UUID workspaceLease = store.workspaceLease(header);
                if (header.state().equals("CAPTURING")) {
                    physical(header);
                    var requested = header.advance("RELEASE_REQUESTED");
                    try {
                        ledger.materials().checkpointFileMaterial(task.id(), root.lease(), root.resource(), header.recovery(), requested.recovery());
                    } catch (SQLException uncertain) {
                        tasks.reportUncertainEffect(task);
                        throw error(ProblemCode.UNKNOWN, "MATERIAL_UNPUBLISHED_INTENT", "Original unpublished cleanup intent is unconfirmed");
                    }
                    header = requested;
                }
                if (!header.state().equals("RELEASE_REQUESTED") || !store.holds(task.id(), materialId).isEmpty())
                    throw error(ProblemCode.UNKNOWN, "MATERIAL_UNPUBLISHED_STATE", "Original unpublished cleanup evidence differs");
                try {
                    var work = work(header);
                    work.recover();
                    if (!work.absent() && !work.identity().equals(header.field("physicalIdentity")))
                        throw new IOException("Original unpublished physical identity differs");
                    work.close();
                    var released = header.advance("RELEASED");
                    ledger.materials().releaseUnpublishedMaterial(task.id(), workspaceLease, new TaskResource("FILE",
                            "source-workspace-v1:" + header.field("workspaceId"), header.field("physicalOwnerId"), false),
                        root.lease(), root.resource(), header.recovery(), released.recovery());
                    capture.finish();
                    return released.inspection();
                } catch (Exception uncertain) {
                    tasks.reportUncertainEffect(task);
                    throw error(ProblemCode.UNKNOWN, "MATERIAL_UNPUBLISHED_RELEASE", "Original unpublished physical or durable cleanup is unconfirmed");
                }
            }
        });
    }

    /** Authenticates exact original IDs before observing custody or proven absence. / 观察接管或已证缺失前认证精确原身份。
     * @param material original material / 原素材
     * @param operation original operation / 原操作
     * @param task authentic original task / 真实原任务
     * @param rootOnly whether only the root may act / 是否仅允许根操作
     * @return retained exact root or proven absence / 精确保留根或已证缺失
     * @throws Exception if original authority or evidence differs / 原授权或证据不同时 */
    private Optional<PlatformMaterialStore.Root> original(UUID material, UUID operation, TaskContext task, boolean rootOnly) throws Exception {
        Objects.requireNonNull(material);
        Objects.requireNonNull(operation);
        var graph = graph(task);
        var found = store.findRoot(task.id(), material);
        if (found.isEmpty()) {
            if (rootOnly && (!owner.equals(graph.owner()) || !version.equals(graph.release(graph.owner()).version())))
                throw error(ProblemCode.ACCESS_DENIED, "MATERIAL_ABSENT_ROOT", "Only original root can inspect absent custody");
            return found;
        }
        var header = found.get().header();
        header.requireGraph(graph);
        boolean root = owner.equals(header.field("consumerId")) && version.equals(header.field("consumerVersion"));
        boolean producer = owner.equals(header.field("producerId")) && version.equals(header.field("producerVersion"));
        if (!header.field("operationId").equals(operation.toString()) || !(root || !rootOnly && producer))
            throw error(ProblemCode.ACCESS_DENIED, "MATERIAL_ORIGINAL_IDENTITY", "Original material belongs to another operation or release");
        return found;
    }

    /** Requests release, then deletes only after every original hold is durably closed. / 请求释放，仅在每个原持有持久关闭后删除。
     * @param original exact original reference / 精确原引用
     * @param task authentic original root cleanup / 真实原根清理
     * @throws ApiException if occupancy or deletion remains uncertain / 占用或删除仍不确定时 */
    @Override
    public void release(MaterialRef original, TaskContext task) throws ApiException {
        call(task, true, () -> {
            var root = authenticate(original, task);
            if (root.released())
                return null;
            var header = root.header();
            if (header.state().equals("PUBLISHED")) {
                try {
                    manifest(header, physical(header), () -> false);
                } catch (Exception uncertain) {
                    tasks.reportUncertainEffect(task);
                    throw error(ProblemCode.UNKNOWN, "MATERIAL_RELEASE_PROOF", "Original published material cannot be physically verified");
                }
                var requested = header.advance("RELEASE_REQUESTED");
                try {
                    ledger.materials().checkpointFileMaterial(task.id(), root.lease(), root.resource(), header.recovery(), requested.recovery());
                } catch (SQLException uncertain) {
                    tasks.reportUncertainEffect(task);
                    throw error(ProblemCode.UNKNOWN, "MATERIAL_RELEASE_INTENT", "Original material release intent is unconfirmed");
                }
                header = requested;
            }
            if (!header.state().equals("RELEASE_REQUESTED"))
                throw error(ProblemCode.UNKNOWN, "MATERIAL_RELEASE_STATE", "Original material release intent is unavailable");
            if (!store.holds(task.id(), original.id()).isEmpty())
                throw error(ProblemCode.BUSY, "MATERIAL_HOLDS_ACTIVE", "Original read holds must be closed before root release");
            try {
                var work = work(header);
                work.recover();
                if (!work.absent()) {
                    if (!work.identity().equals(header.field("physicalIdentity")))
                        throw new IOException("Original material physical identity differs");
                    manifest(header, work, () -> false);
                }
                work.close();
                ledger.materials().releaseMaterialRoot(task.id(), root.lease(), root.resource(), header.recovery(), header.advance("RELEASED").recovery());
            } catch (Exception uncertain) {
                tasks.reportUncertainEffect(task);
                throw error(ProblemCode.UNKNOWN, "MATERIAL_ROOT_RELEASE", "Original material deletion or release is unconfirmed");
            }
            return null;
        });
    }

    /** Checks the exact root consumer and every original reference field. / 核验精确根消费方及每个原引用字段。
     * @param reference original immutable value / 原不可变值
     * @param task authentic original task / 真实原任务
     * @return exact retained original row / 精确保留原行
     * @throws Exception if original authentication or storage fails / 原认证或存储失败时 */
    private PlatformMaterialStore.Root authenticate(MaterialRef reference, TaskContext task) throws Exception {
        if (!reference.taskId().equals(task.id()))
            throw error(ProblemCode.ACCESS_DENIED, "MATERIAL_TASK", "Material belongs to another original task");
        var root = store.root(task.id(), reference.id());
        root.header().requireGraph(graph(task));
        if (!root.header().reference().equals(reference) || !owner.equals(root.header().field("consumerId"))
            || !version.equals(root.header().field("consumerVersion")))
            throw error(ProblemCode.ACCESS_DENIED, "MATERIAL_REFERENCE", "Material reference or root consumer differs");
        return root;
    }

    /** Reads only the authenticated task's retained graph. / 仅读取已认证任务的保留图。
     * @param task authentic task / 真实任务
     * @return exact original graph / 精确原图
     * @throws Exception if original graph is unavailable / 原图不可用时 */
    private TaskRecoveryGraph graph(TaskContext task) throws Exception {
        tasks.requireCleanup(task, owner, version);
        return ledger.queries().recoveryGraph(task.id()).orElseThrow(() -> new SQLException("Original material graph is absent"));
    }

    /** Derives only the original marked physical coordinates. / 仅推导原有标记物理坐标。
     * @param header trusted original header / 可信原头部
     * @return unopened original work / 未打开原工作目录 */
    private SourceWorkDirectory work(PlatformMaterialHeader header) {
        return new SourceWorkDirectory(database.root().resolve("work/source-workspace"), PlatformMaterialHeader.uuid(header.field("taskId")),
            PlatformMaterialHeader.uuid(header.field("workspaceId")), header.field("physicalOwnerId"));
    }

    /** Requires present exact physical identity for read access. / 为读取要求存在精确物理身份。
     * @param header trusted original header / 可信原头部
     * @return recovered original container / 已恢复原容器
     * @throws IOException if the original container is missing or substituted / 原容器缺失或替换时 */
    private SourceWorkDirectory physical(PlatformMaterialHeader header) throws IOException {
        var work = work(header);
        work.recover();
        if (!work.identity().equals(header.field("physicalIdentity")))
            throw new IOException("Original material physical identity differs");
        return work;
    }

    /** Reads and physically verifies the persisted manifest. / 读取并物理核验持久清单。
     * @param header original complete header / 原完整头部
     * @param work exact original container / 精确原容器
     * @param cancellation original read cancellation or confirmed cleanup / 原读取取消或已确认清理
     * @return verified immutable manifest / 已核验不可变清单
     * @throws IOException if original content differs / 原内容不同时 */
    private SourceMaterialManifest.Manifest manifest(PlatformMaterialHeader header, SourceWorkDirectory work, CancelToken cancellation) throws IOException {
        var manifest = SourceMaterialManifest.read(work, header.field("manifestSha256"), Long.parseLong(header.field("byteCount")),
            Integer.parseInt(header.field("fileCount")));
        SourceMaterialManifest.verify(work, manifest, cancellation);
        return manifest;
    }

    /** Requires exact original root fields on every persisted read hold. / 对每个持久读持有要求精确原根字段。
     * @param hold original retained row / 原保留行
     * @param reference exact original reference / 精确原引用
     * @param task authentic original task / 真实原任务 */
    private void requireHold(PlatformMaterialStore.Hold hold, MaterialRef reference, TaskContext task) {
        var f = hold.recovery().fields();
        if (!f.get("consumerId").equals(owner) || !f.get("consumerVersion").equals(version)
            || !f.get("taskId").equals(task.id().toString()) || !f.get("materialId").equals(reference.id().toString())
            || !f.get("materialDigest").equals(reference.manifestSha256()) || !f.get("revision").equals(reference.revision()))
            throw new IllegalArgumentException("Original read hold differs from its material root");
    }

    /** Tracks exact host work and exposes only bounded safe uncertainty. / 跟踪精确宿主工作并仅暴露有界安全不确定性。
     * @param task authentic original task / 真实原任务
     * @param cleanup whether only original cleanup executes / 是否仅执行原清理
     * @param action actual platform work / 实际平台工作
     * @param <T> original capability result / 原能力结果
     * @return actual result / 实际结果
     * @throws ApiException if authority or original facts cannot be verified / 授权或原事实无法核实时 */
    private <T> T call(TaskContext task, boolean cleanup, Callable<T> action) throws ApiException {
        try {
            return scope.call(task, cleanup, () -> {
                try {
                    return action.call();
                } catch (ApiException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw error(ProblemCode.UNKNOWN, "MATERIAL_EVIDENCE_UNCERTAIN", "Original material facts require reconciliation");
                }
            });
        } catch (ApiException failure) {
            throw failure;
        } catch (Exception failure) {
            throw error(ProblemCode.UNKNOWN, "MATERIAL_EVIDENCE_UNCERTAIN", "Original material facts require reconciliation");
        }
    }

    /** Seals this activation without guessing that surviving material holds have expired. / 封闭此启用，不猜测存续素材持有已过期。
     * @throws ApiException if actual holds remain / 实际持有仍存在时 */
    @Override
    public void close() throws ApiException {
        scope.close();
        synchronized (this) {
            if (!holds.isEmpty())
                throw error(ProblemCode.BUSY, "MATERIAL_HOLDS_OPEN", "Original material holds still have actual ownership");
        }
    }

    /** Creates a safe diagnosis without local path or file contents. / 创建不含本机路径或文件内容的安全诊断。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @param message safe explanation / 安全解释
     * @return public exception / 公开异常 */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /** Exact consumer-local hold and whitelisted original selection. / 精确消费方本地持有及白名单原选择。 */
    private final class Hold implements FileMaterialHold, PlatformMaterialGrant {
        /** Authentic original context. / 真实原上下文。 */
        private final TaskContext task;

        /** Exact immutable material reference. / 精确不可变素材引用。 */
        private final MaterialRef reference;

        /** Exact persisted read lease. / 精确持久读占用。 */
        private final PlatformMaterialStore.Hold persisted;

        /** Original physical container. / 原物理容器。 */
        private final SourceWorkDirectory work;

        /** Exact immutable content facts. / 精确不可变内容事实。 */
        private final SourceMaterialManifest.Manifest manifest;

        /** Whether only old-boot cleanup is authorized. / 是否仅授权旧启动清理。 */
        private final boolean cleanupOnly;

        /** Consumer's own original opaque token. / 消费方自己的原不透明令牌。 */
        private FileSelection selection;

        /** Actual confirmed durable close state. / 实际已确认持久关闭状态。 */
        private boolean closed;

        /** Binds original persisted facts without changing the original lease or boot. / 绑定原持久事实，不改变原占用或启动。
         * @param task authentic original task / 真实原任务
         * @param reference original reference / 原引用
         * @param persisted original hold row / 原持有行
         * @param work exact physical owner / 精确物理所有者
         * @param manifest original manifest / 原清单
         * @param cleanupOnly whether no new reads are allowed / 是否禁止新读取 */
        private Hold(TaskContext task, MaterialRef reference, PlatformMaterialStore.Hold persisted,
                     SourceWorkDirectory work, SourceMaterialManifest.Manifest manifest, boolean cleanupOnly) {
            this.task = task;
            this.reference = reference;
            this.persisted = persisted;
            this.work = work;
            this.manifest = manifest;
            this.cleanupOnly = cleanupOnly;
        }

        /** Returns the exact original hold UUID. / 返回精确原持有 UUID。
         * @return original hold identity / 原持有身份 */
        @Override
        public UUID id() {
            return PlatformMaterialHeader.uuid(persisted.recovery().fields().get("holdId"));
        }

        /** Returns original immutable metadata without granting access. / 返回原不可变元数据，不授予访问权。
         * @return original reference / 原引用 */
        @Override
        public MaterialRef reference() {
            return reference;
        }

        /** Returns this hold's exact original whitelist. / 返回此持有的精确原白名单。
         * @return original immutable entries / 原不可变项目 */
        @Override
        public SortedMap<String, SelectedDirectory.Content> entries() {
            return manifest.entries();
        }

        /** Rechecks authentic root, original lease and immutable payload before any read. / 每次读取前复核真实根、原占用及不可变载荷。
         * @throws ApiException if original access is unavailable / 原访问不可用时 */
        @Override
        public synchronized void require() throws ApiException {
            if (closed || cleanupOnly)
                throw error(ProblemCode.ACCESS_DENIED, "MATERIAL_HOLD_CLOSED", "Material hold does not authorize business reads");
            tasks.requireExecuting(task, owner, version);
            try {
                var root = authenticate(reference, task);
                if (!(root.header().state().equals("PUBLISHED") || root.header().state().equals("RELEASE_REQUESTED"))
                    || !store.holds(task.id(), reference.id()).contains(persisted))
                    throw new IOException("Original material hold is unavailable");
                SourceMaterialManifest.verify(work, manifest, tasks.operationCancellation(task));
            } catch (ApiException failure) {
                throw failure;
            } catch (Exception failure) {
                throw error(ProblemCode.UNKNOWN, "MATERIAL_HOLD_UNCERTAIN", "Original material read evidence cannot be verified");
            }
        }

        /** Issues only an original consumer selection without exposing payload location. / 仅发放原消费方选择，不暴露载荷位置。
         * @return exact original selection / 精确原选择
         * @throws ApiException if original hold is unavailable / 原持有不可用时 */
        @Override
        public synchronized FileSelection selection() throws ApiException {
            require();
            if (selection == null) {
                try {
                    selection = files.materialSelection(task, new SelectedDirectory(work.directory()), this);
                } catch (IOException failure) {
                    throw error(ProblemCode.UNKNOWN, "MATERIAL_SELECTION_UNCERTAIN", "Original material selection cannot be verified");
                }
            }
            return selection;
        }

        /** Revokes access and releases only this original persisted hold, retaining failures. / 撤销访问并仅释放此原持久持有，保留失败。
         * @throws ApiException if exact original release remains uncertain / 精确原释放仍不确定时 */
        @Override
        public synchronized void close() throws ApiException {
            if (closed)
                return;
            tasks.requireCleanup(task, owner, version);
            if (selection != null)
                files.revokeMaterial(selection);
            try {
                var f = new TreeMap<>(persisted.recovery().fields());
                f.put("state", "RELEASED");
                ledger.materials().releaseMaterialHold(task.id(), persisted.lease(), persisted.resource(), persisted.recovery(),
                    new ResourceRecovery("material-hold.v1", f));
                closed = true;
                synchronized (PlatformFileMaterials.this) {
                    holds.remove(id());
                }
            } catch (Exception failure) {
                tasks.reportUncertainEffect(task);
                throw error(ProblemCode.UNKNOWN, "MATERIAL_HOLD_RELEASE", "Original material hold release requires reconciliation");
            }
        }
    }
}
