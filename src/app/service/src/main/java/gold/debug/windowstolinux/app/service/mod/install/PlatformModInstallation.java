package gold.debug.windowstolinux.app.service.mod.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.log.LogRecordCodec;
import gold.debug.windowstolinux.app.db.log.LogStore;
import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.mod.install.ModPackageReader;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity;
import gold.debug.windowstolinux.app.windows.file.WindowsFilePublish;
import gold.debug.windowstolinux.appapi.task.log.LogCategory;
import gold.debug.windowstolinux.appapi.task.log.LogLevel;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Installs reviewed local originals through durable phases without enabling or executing author code. / 经持久阶段安装已审阅本地原包，不启用或执行作者代码。 */
public final class PlatformModInstallation implements AutoCloseable {
    /** Original package inventory under this service monitor. / 此服务同步锁下的原包清单。 */
    private final InstallationList inventory = new InstallationList();

    /** Canonical installation paths and physical ownership checks. / 规范安装路径与物理归属核验。 */
    private final InstallationPaths paths = new InstallationPaths();

    /** Original installation records on the same locked transaction. / 在同一已锁事务上的原安装记录。 */
    private final InstallationRecords records = new InstallationRecords();

    /** Original boot identity. / 原启动身份。 */
    private final UUID boot;

    /** Explicit host-selected installation home. / 宿主明确选择的安装根。 */
    private final Path home;

    /** Shared platform transaction owner. / 共用平台事务所有者。 */
    private final StoreConnections connections;

    /** Current authorization and accepted-operation scopes. / 当前授权及已接纳操作作用域。 */
    private final DesktopSecurityService security;

    /** Durable standard operation archive. / 持久标准操作归档。 */
    private final LogStore logs;

    /** Fixed platform archive registration. / 固定平台归档登记。 */
    private final LogStore.Target archive;

    /** Installed-version cleanup bound to the same root and security owner. / 绑定同一根及安全所有者的安装版本清理。 */
    private final PlatformModDeletion deletion;

    /** Same-release repair bound to this installed root and gate. / 绑定此安装根和操作门的同版本修复。 */
    private final PlatformModReinstallation reinstallation;

    /** Strict bounded request codec. / 严格有界请求编解码。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Retention preference shared by all original-package sources. / 所有原包来源共用的留存偏好。 */
    private static final String KEEP = "mod_keep_original";

    /** Whether this owner has closed after all accepted calls returned. / 所有已接纳调用返回后，此所有者是否关闭。 */
    private boolean closed;

    /** Binds an actual application installation root; CLASS composition must not call this service. / 绑定实际应用安装根；CLASS 装配不得调用此服务。
     * @param database actual platform database / 实际平台数据库
     * @param security original security service / 原安全服务
     * @param applicationHome explicit application root / 明示应用根
     * @param diagnostics actual runtime logging owner / 实际运行时日志所有者
     * @param api actual compiled public API version / 实际编译公开 API 版本
     * @throws IOException if the root is absent or redirected / 根缺失或重定向时
     * @throws SQLException if archive registration fails / 归档登记失败时 */
    public PlatformModInstallation(PlatformDatabase database, DesktopSecurityService security, Path applicationHome,
                                   gold.debug.windowstolinux.app.service.log.PlatformLogService diagnostics, SemanticVersion api)
        throws IOException, SQLException {
        InstallationPaths.directory(applicationHome);
        home = applicationHome;
        boot = database.bootId();
        this.security = Objects.requireNonNull(security);
        connections = new StoreConnections(database::openConnection);
        logs = new LogStore(database);
        archive = logs.register("app", Optional.empty(),
            new LogCategory("mod_operation", "mod_operation", Optional.empty()));
        deletion = new PlatformModDeletion(database, security, home, diagnostics);
        reinstallation = new PlatformModReinstallation(database, security, home, api);
    }

    /** Returns actual installed-version deletion for host composition. / 返回供宿主装配的实际安装版本删除。
     * @return original deletion owner / 原删除所有者 */
    public PlatformModDeletion deletion() {
        return deletion;
    }

    /** Returns the original installed repair service for host composition. / 返回供宿主装配的原安装修复服务。
     * @return original repair service / 原修复服务 */
    public PlatformModReinstallation reinstallation() {
        return reinstallation;
    }

    /** Opaque single-use review of original bytes and the captured retention choice. / 原始字节及所捕获留存选择的不透明单次审阅。 */
    public final class Review {
        /** Original service authority. / 原服务权限。 */
        private final PlatformModInstallation owner = PlatformModInstallation.this;

        /** Stable operation identity allocated before admission. / 准入前分配的稳定操作身份。 */
        private final UUID id = UUID.randomUUID();

        /** Inspected immutable original file facts. / 已检验的不可变原文件事实。 */
        private final ModPackageReader.Inspection inspection;

        /** Original native archive identity. / 原归档原生身份。 */
        private final String identity;

        /** Captured preference, unaffected by later changes. / 捕获的偏好，不受后续变化影响。 */
        private final boolean keep;

        /** Whether admission was already attempted. / 是否已尝试准入。 */
        private boolean used;

        /** Captures only facts established by this service. / 仅捕获此服务确认的事实。
         * @param inspection verified bytes / 已验证字节
         * @param identity native identity / 原生身份
         * @param keep captured retention / 捕获的留存 */
        private Review(ModPackageReader.Inspection inspection, String identity, boolean keep) {
            this.inspection = inspection;
            this.identity = identity;
            this.keep = keep;
        }

        /** Returns the original query identity even if admission later becomes uncertain. / 即使后续准入不确定也返回原查询身份。
         * @return stable identity / 稳定身份 */
        public UUID id() {
            return id;
        }

        /** Exposes original package facts for host review. / 向宿主审阅暴露原包事实。
         * @return immutable facts / 不可变事实 */
        public ModPackageInfo packageInfo() {
            return PlatformModInstallation.packageInfo(inspection);
        }

        /** Returns the retention choice captured for this request. / 返回此请求捕获的留存选择。
         * @return whether to retain the original / 是否留存原包 */
        public boolean keepOriginal() {
            return keep;
        }
    }

    /** Installation identity and separately reported original cleanup. / 安装身份及单独报告的原包清理。
     * @param operationId stable original operation / 稳定原操作
     * @param owner exact Mod ID / 精确 Mod ID
     * @param version exact release / 精确发布
     * @param directory published target / 发布目标
     * @param originalCleanup RETAINED, DELETED, ABSENT or RETAINED_UNCONFIRMED / 留存、已删、不存在或留存未确认 */
    public record Receipt(UUID operationId, String owner, String version, Path directory, String originalCleanup) {
    }

    /** One discovered original, including malformed or already installed packages. / 一个已发现原包，包括损坏或已安装的包。
     * @param file actual direct child / 实际直接子文件
     * @param packageInfo verified readable package facts / 已验证可读取包事实
     * @param state AVAILABLE, INSTALLED, CONFLICT, BUSY or INVALID / 可安装、已安装、冲突、占用或无效
     * @param problem bounded reading failure / 有界读取失败
     * @param deletable whether current facts permit requesting a deletion review / 当前事实是否允许请求删除审阅 */
    public record OriginalView(Path file, Optional<ModPackageInfo> packageInfo,
                               String state, Optional<Problem> problem, boolean deletable) {
    }

    /** Converts private verification into observations. / 将私有验证转为观察。
     * @param inspection original verified package / 原已验证包
     * @return immutable display facts / 不可变展示事实 */
    static ModPackageInfo packageInfo(ModPackageReader.Inspection inspection) {
        var manifest = inspection.manifest();
        return new ModPackageInfo(inspection.archive(), manifest.id(), manifest.name(), manifest.version().toString(),
            inspection.archiveSha256(), inspection.contentSha256());
    }

    /** Retained installation displayed even when its original file has disappeared. / 即使原文件已消失也展示的保留安装。
     * @param id original operation / 原操作
     * @param owner exact owner / 精确所有者
     * @param version original target release / 原目标发布
     * @param type original maintenance type / 原维护类型
     * @param state current operation state / 当前操作状态
     * @param phase original checkpoint / 原检查点
     * @param original validated source path when readable / 可读时已校验来源路径
     * @param failure bounded original failure explanation / 有界原失败说明 */
    public record PendingView(UUID id, String owner, String version, String type, String state, String phase,
                              Optional<Path> original, Optional<String> failure) {
    }

    /** One explicit scan with a current durable management snapshot. / 一次明确扫描及当前持久管理快照。
     * @param originals all discovered originals / 全部发现原包
     * @param pending original retained installations / 原保留安装
     * @param keepOriginal current shared preference / 当前共用偏好 */
    public record SourceList(List<OriginalView> originals, List<PendingView> pending, boolean keepOriginal) {
        /** Copies observations without turning them into operation authority. / 复制观察，不将其转为操作权限。
         * @param originals observed files / 观察文件
         * @param pending retained operations / 保留操作
         * @param keepOriginal current preference / 当前偏好 */
        public SourceList {
            originals = List.copyOf(originals);
            pending = List.copyOf(pending);
        }
    }

    /** Returns the fixed source folder without creating it. / 返回固定来源文件夹，不创建它。
     * @return explicit original-package folder / 明示原包文件夹 */
    public Path originalsDirectory() {
        return home.resolve("mod-zip");
    }

    /** Scans direct originals without watching, extracting, enabling or hiding malformed packages. / 扫描直接原包，不监听、解压、启用或隐藏损坏包。
     * @return current file and operation observations / 当前文件及操作观察
     * @throws IOException if the root cannot be inspected / 无法检查根目录时
     * @throws SQLException if durable status cannot be read / 无法读取持久状态时 */
    public synchronized SourceList scan() throws IOException, SQLException {
        requireOpen();
        try (var ignored = security.enterOperation()) {
            return inventory.read();
        }
    }

    /** Opaque deletion review bound to one exact ordinary original file. / 绑定一个精确普通原文件的不透明删除审阅。 */
    public final class DeleteReview {
        /** Original service authority. / 原服务权限。 */
        private final PlatformModInstallation owner = PlatformModInstallation.this;

        /** Exact reviewed file. / 精确审阅文件。 */
        private final Path file;

        /** Physical identity, size and modification time. / 物理身份、大小及修改时间。 */
        private final FileStamp stamp;

        /** Whether the deletion was already attempted. / 是否已尝试删除。 */
        private boolean used;

        /** Captures service-verified evidence only. / 仅捕获服务已核验依据。
         * @param file exact original / 精确原文件
         * @param stamp original physical facts / 原物理事实 */
        private DeleteReview(Path file, FileStamp stamp) {
            this.file = file;
            this.stamp = stamp;
        }

        /** Returns the exact file for confirmation. / 返回供确认的精确文件。
         * @return original file / 原文件 */
        public Path file() {
            return file;
        }
    }

    /** Reviews deletion independently of manifest validity or installed state. / 独立于清单有效性及安装状态审阅删除。
     * @param file explicitly selected original / 明确选定原文件
     * @return bound single-use review / 已绑定单次审阅
     * @throws IOException if absent, redirected or irregular / 缺失、重定向或不是普通文件时
     * @throws SQLException if original use cannot be ruled out / 无法排除原包占用时 */
    public synchronized DeleteReview reviewDelete(Path file) throws IOException, SQLException {
        requireOpen();
        try (var ignored = security.enterOperation()) {
            paths.originalPath(file);
            var stamp = InstallationList.stamp(file);
            return locked(c -> {
                if (InstallationList.inUse(records.maintenance(c), file, stamp) || deletion.originalBusy(c, file, stamp.identity()))
                    throw new SQLException("Original package is retained by maintenance");
                return new DeleteReview(file, stamp);
            });
        }
    }

    /** Deletes only the confirmed original, preserving installed code, settings and data. / 仅删除已确认原包，保留已安装代码、设置及数据。
     * @param review original single-use deletion review / 原单次删除审阅
     * @throws IOException if physical evidence changed before deletion / 删除前物理依据变化时
     * @throws SQLException if use, deletion or transaction completion is unconfirmed; refresh rather than replay / 占用、删除或事务完成未确认时；刷新而非重放 */
    public synchronized void deleteOriginal(DeleteReview review) throws IOException, SQLException {
        requireOpen();
        if (review == null || review.owner != this || review.used)
            throw new IllegalArgumentException("Original deletion requires an unused review");
        try (var ignored = security.enterOperation()) {
            paths.originalPath(review.file);
            if (!review.stamp.equals(InstallationList.stamp(review.file)))
                throw new IOException("Original changed after deletion review");
            review.used = true;
            locked(c -> {
                if (InstallationList.inUse(records.maintenance(c), review.file, review.stamp)
                    || deletion.originalBusy(c, review.file, review.stamp.identity()))
                    throw new SQLException("Original package is in use");
                try {
                    paths.originalPath(review.file);
                    if (!review.stamp.equals(InstallationList.stamp(review.file)))
                        throw new IOException("Original changed before deletion");
                    Files.delete(review.file);
                } catch (IOException failure) {
                    throw new SQLException("Original deletion could not be confirmed", failure);
                }
                return null;
            });
        }
    }

    /** Physical review facts for an original file. / 原文件的物理审阅事实。
     * @param identity native file identity / 原生文件身份
     * @param size original byte length / 原字节长度
     * @param modified exact original modification time / 精确原修改时间 */
    private record FileStamp(String identity, long size, String modified) {
    }

    /** Internal scan facts before durable status is combined. / 合并持久状态之前的内部扫描事实。
     * @param file direct source / 直接来源
     * @param inspection validated bytes or null / 已验证字节或空
     * @param stamp physical facts or null / 物理事实或空
     * @param problem original reading problem or null / 原读取问题或空 */
    private record Observed(Path file, ModPackageReader.Inspection inspection, FileStamp stamp, Problem problem) {
    }

    /** Retained file use plus its safe view. / 保留文件占用及其安全视图。
     * @param view original operation / 原操作
     * @param identity original native source identity or null / 原生来源身份或空 */
    private record Maintenance(PendingView view, String identity) {
    }

    /** Reads the default-true retention preference without creating installation directories. / 读取默认真的留存偏好，不创建安装目录。
     * @return current preference / 当前偏好
     * @throws SQLException if the saved preference cannot be read / 无法读取已存偏好时 */
    public synchronized boolean keepOriginal() throws SQLException {
        requireOpen();
        try (var ignored = security.enterOperation()) {
            return locked(PlatformModInstallation::keepOriginal);
        }
    }

    /** Saves the shared preference independently of already reviewed requests. / 独立于已审阅请求保存共用偏好。
     * @param keep new preference / 新偏好
     * @throws SQLException if saving is unconfirmed / 保存未确认时 */
    public synchronized void keepOriginal(boolean keep) throws SQLException {
        requireOpen();
        try (var ignored = security.enterOperation()) {
            locked(c -> {
                InstallationRecords.execute(c,
                    "MERGE INTO desktop_preference(preference_key,preference_value) KEY(preference_key) VALUES(?,?)",
                    KEEP, Boolean.toString(keep));
                return null;
            });
        }
    }

    /** Inspects a direct mod-zip child without any installation side effects. / 检验 mod-zip 的直接子文件，不产生安装副作用。
     * @param original explicit original file / 明示原文件
     * @return single-use review / 单次审阅
     * @throws IOException if paths or bytes are invalid / 路径或字节无效时
     * @throws SQLException if catalog admission cannot be established / 无法确认目录准入时 */
    public synchronized Review review(Path original) throws IOException, SQLException {
        requireOpen();
        try (var ignored = security.enterOperation()) {
            paths.originalPath(original);
            String identity = WindowsFileIdentity.read(original);
            var inspection = ModPackageReader.read(original);
            if (!identity.equals(WindowsFileIdentity.read(original)))
                throw new IOException("Original identity changed during review");
            paths.target(inspection);
            return locked(c -> {
                records.available(c, inspection);
                return new Review(inspection, identity, keepOriginal(c));
            });
        }
    }

    /** Accepts the exact review once and advances only its original operation. / 仅接纳精确审阅一次，并仅推进原操作。
     * @param review original single-use review / 原单次审阅
     * @return installed receipt or retained uncertainty with the same operation ID / 安装凭据或保留同一操作 ID 的不确定结果
     * @throws IOException if the original changed before acceptance / 接纳前原包变化时
     * @throws SQLException if admission is refused or unconfirmed before filesystem work / 文件工作前准入被拒或未确认时 */
    public synchronized OperationResult<Receipt> install(Review review) throws IOException, SQLException {
        requireOpen();
        if (review == null || review.owner != this || review.used)
            throw new IllegalArgumentException("Installation requires an unused original review");
        try (var ignored = security.enterOperation()) {
            paths.originalPath(review.inspection.archive());
            if (!review.identity.equals(WindowsFileIdentity.read(review.inspection.archive())))
                throw new IOException("Original identity changed after review");
            var current = ModPackageReader.read(review.inspection.archive());
            if (!current.archiveSha256().equals(review.inspection.archiveSha256())
                || !current.contentSha256().equals(review.inspection.contentSha256()))
                throw new IOException("Original bytes changed after review");
            review.used = true;
            var request = new Request(1, home.toString(), boot.toString(), current.archive().toString(),
                review.identity, current.manifest().json(), current.archiveSha256(), current.contentSha256(),
                current.files(), review.keep);
            String json = JSON.writeValueAsString(request);
            if (json.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024)
                throw new IOException("Installation evidence exceeds limit");
            var pending = new Pending(review.id, current, request, json, "VALIDATED", new Checkpoint(null, null, null),
                UUID.randomUUID(), System.currentTimeMillis(), null);
            try {
                locked(c -> {
                    records.available(c, current);
                    InstallationRecords.execute(c,
                        "INSERT INTO mod_operation(operation_id,mod_id,operation_type,target_version,state,phase,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,?,'INSTALL',?,'RUNNING','VALIDATED',?,?,?, ?,?)",
                        pending.id(), current.manifest().id(), current.manifest().version().toString(), json,
                        InstallationRecords.encode(pending.checkpoint()), pending.archive().toString(), pending.created(),
                        pending.created());
                    return null;
                });
            } catch (SQLException unconfirmed) {
                try {
                    records.retain(pending, "Initial installation admission could not be confirmed");
                } catch (SQLException unavailable) {
                    unconfirmed.addSuppressed(unavailable);
                }
                throw unconfirmed;
            }
            return advance(pending);
        }
    }

    /** Explicitly reconciles an original interrupted installation, never creating a replacement operation. / 明确核对原中断安装，绝不创建替代操作。
     * @param operation original stable identity / 原稳定身份
     * @return original completion or retained uncertainty / 原完成结果或保留不确定性
     * @throws SQLException if no original evidence exists or another call owns the operation / 不存在原依据或其他调用持有操作时 */
    public synchronized OperationResult<Receipt> reconcile(UUID operation) throws SQLException {
        requireOpen();
        try (var ignored = security.enterOperation()) {
            var completed = records.completed(operation);
            if (completed != null)
                return success(completed);
            Pending pending = locked(c -> {
                var original = records.load(c, operation);
                if (original.cancelToken() != null)
                    throw new SQLException("Cancellation must continue through its original cleanup action");
                if (InstallationRecords.execute(c,
                    "UPDATE mod_operation SET state='RUNNING',failure_code=NULL,failure_detail=NULL,updated_at=? WHERE operation_id=? AND state='UNKNOWN' AND outcome IS NULL",
                    System.currentTimeMillis(), operation) != 1)
                    throw new SQLException("Installation is not available for explicit reconciliation");
                return original;
            });
            return advance(pending);
        }
    }

    /** Exact one-use cancellation review bound to this service and original checkpoint. / 绑定此服务及原检查点的精确单次撤销审阅。 */
    public final class CancelReview {
        /** Owning service instance. / 所属服务实例。 */
        private final PlatformModInstallation owner = PlatformModInstallation.this;

        /** Original working state. / 原工作状态。 */
        private final Pending pending;

        /** Original observed deletion scope. / 原观察删除范围。 */
        private final Cancellation cancellation;

        /** Whether this review has been consumed. / 此审阅是否已消费。 */
        private boolean used;

        /** Captures immutable original evidence. / 捕获不可变原依据。
         * @param pending original operation / 原操作
         * @param cancellation reviewed file set / 审阅文件集合 */
        private CancelReview(Pending pending, Cancellation cancellation) {
            this.pending = pending;
            this.cancellation = cancellation;
        }

        /** Returns the original operation identity. / 返回原操作身份。
         * @return original identity / 原身份 */
        public UUID id() {
            return pending.id();
        }

        /** Returns the original Mod identity. / 返回原 Mod 身份。
         * @return Mod identity / Mod 身份 */
        public String modId() {
            return pending.inspection().manifest().id();
        }

        /** Returns the original target version. / 返回原目标版本。
         * @return target version / 目标版本 */
        public String version() {
            return pending.inspection().manifest().version().toString();
        }

        /** Returns every exact reviewed path, including directories. / 返回每个精确审阅路径，包括目录。
         * @return immutable reviewed paths / 不可变审阅路径 */
        public List<Path> paths() {
            return cancellation.entries().stream().map(entry -> Path.of(entry.path())).toList();
        }

        /** Returns total currently observed file bytes. / 返回当前观察文件字节总量。
         * @return observed bytes / 观察字节 */
        public long bytes() {
            return cancellation.entries().stream().mapToLong(ModInstallationCleanup.Entry::size).sum();
        }
    }

    /** Retained proof of original installation cancellation, not a new installation result. / 原安装撤销的保留凭据，不是新安装结果。
     * @param operationId original operation / 原操作
     * @param owner original Mod / 原 Mod
     * @param version cancelled target version / 已撤销目标版本 */
    public record CancellationReceipt(UUID operationId, String owner, String version) {
    }

    /** Reviews only unregistered interrupted installations and their exact remaining files. / 仅审阅尚未登记的中断安装及精确剩余文件。
     * @param operation original operation identity / 原操作身份
     * @return single-use explicit cleanup review / 单次明确清理审阅
     * @throws IOException if files or ownership cannot be reviewed / 无法审阅文件或归属时
     * @throws SQLException if the original cannot be cancelled / 原操作不可撤销时 */
    public synchronized CancelReview reviewCancellation(UUID operation) throws IOException, SQLException {
        requireOpen();
        try (var ignored = security.enterOperation()) {
            var pending = locked(c -> {
                var original = records.load(c, operation);
                records.requireCancellation(c, original);
                if (original.cancelToken() != null)
                    throw new SQLException("Cancellation already has an accepted scope");
                InstallationRecords.requireUnknown(c, operation);
                return original;
            });
            var entries = cancellationFiles(pending);
            return new CancelReview(pending, new Cancellation(pending.phase(), pending.checkpoint(), entries));
        }
    }

    /** Persists the confirmed scope before deleting any exact reviewed file. / 删除任何精确审阅文件前持久保存确认范围。
     * @param review original one-use confirmation / 原单次确认
     * @return cancelled receipt or retained uncertainty / 撤销凭据或保留不确定性
     * @throws IOException if files changed before acceptance / 接纳前文件变化时
     * @throws SQLException if admission cannot be confirmed / 无法确认准入时 */
    public synchronized OperationResult<CancellationReceipt> cancel(CancelReview review)
        throws IOException, SQLException {
        requireOpen();
        if (review == null || review.owner != this || review.used)
            throw new IllegalArgumentException("Cancellation requires an unused original review");
        try (var ignored = security.enterOperation()) {
            if (!review.cancellation.entries().equals(cancellationFiles(review.pending)))
                throw new IOException("Cancellation scope changed after review");
            review.used = true;
            Pending pending;
            try {
                pending = locked(c -> {
                    records.requirePending(c, review.pending);
                    records.requireCancellation(c, review.pending);
                    InstallationRecords.requireUnknown(c, review.id());
                    String recovery;
                    try {
                        recovery = JSON.writeValueAsString(review.cancellation);
                    } catch (IOException invalid) {
                        throw new SQLException("Cannot retain cancellation scope", invalid);
                    }
                    if (recovery.getBytes(StandardCharsets.UTF_8).length > 8 * 1024 * 1024)
                        throw new SQLException("Cancellation scope exceeds limit");
                    if (InstallationRecords.execute(c,
                        "UPDATE mod_operation SET state='RUNNING',phase='CANCELLING',recovery_json=?,failure_code=NULL,failure_detail=NULL,updated_at=? WHERE operation_id=? AND state='UNKNOWN' AND outcome IS NULL",
                        recovery, System.currentTimeMillis(), review.id()) != 1)
                        throw new SQLException("Cancellation admission changed");
                    var original = review.pending;
                    return new Pending(original.id(), original.inspection(), original.request(), original.json(),
                        "CANCELLING", original.checkpoint(), original.archive(), original.created(),
                        review.cancellation);
                });
            } catch (SQLException failure) {
                try {
                    records.retain(review.pending, "Cancellation admission could not be confirmed");
                } catch (SQLException unavailable) {
                    failure.addSuppressed(unavailable);
                }
                throw failure;
            }
            return cancelFiles(pending);
        }
    }

    /** Continues an already accepted cancellation without approving a wider file set. / 继续已接纳撤销，不批准更大文件集合。
     * @param operation original operation identity / 原操作身份
     * @return original cancelled receipt or uncertainty / 原撤销凭据或不确定性
     * @throws SQLException if no accepted original cancellation exists / 不存在已接纳原撤销时 */
    public synchronized OperationResult<CancellationReceipt> reconcileCancellation(UUID operation) throws SQLException {
        requireOpen();
        try (var ignored = security.enterOperation()) {
            var completed = records.cancelled(operation);
            if (completed != null)
                return new OperationResult<>(TaskResult.CANCELLED, Optional.of(completed), Optional.empty());
            var pending = locked(c -> {
                var original = records.load(c, operation);
                records.requireCancellation(c, original);
                if (original.cancelToken() == null)
                    throw new SQLException("No original cancellation scope exists");
                InstallationRecords.requireUnknown(c, operation);
                if (InstallationRecords.execute(c,
                    "UPDATE mod_operation SET state='RUNNING',failure_code=NULL,failure_detail=NULL,updated_at=? WHERE operation_id=? AND state='UNKNOWN' AND outcome IS NULL",
                    System.currentTimeMillis(), operation) != 1)
                    throw new SQLException("Cancellation reconciliation admission changed");
                return original;
            });
            return cancelFiles(pending);
        }
    }

    /** Captures bounded files from this operation's original locations. / 从此操作原位置捕获有界文件。
     * @param pending original checkpoint / 原检查点
     * @return current exact observations / 当前精确观察
     * @throws IOException if unavailable or outside the package scope / 不可用或超出包范围时 */
    private List<ModInstallationCleanup.Entry> cancellationFiles(Pending pending) throws IOException {
        return ModInstallationCleanup.inspect(home.resolve(".mod-staging").resolve(pending.id().toString()),
            pending.checkpoint().root(), paths.target(pending.inspection()), pending.checkpoint().program(),
            pending.inspection().files());
    }

    /** Deletes the persisted original subset, then archives cancellation in the same original identity. / 删除已持久原子集，再按同一原身份归档撤销。
     * @param pending original accepted cancellation / 原已接纳撤销
     * @return actual cancellation or retained uncertainty / 实际撤销或保留不确定性 */
    private OperationResult<CancellationReceipt> cancelFiles(Pending pending) {
        try {
            ModInstallationCleanup.remove(pending.cancelToken().entries(),
                home.resolve(".mod-staging").resolve(pending.id().toString()), pending.checkpoint().root(),
                paths.target(pending.inspection()), pending.checkpoint().program(), pending.inspection().files());
            var receipt = new CancellationReceipt(pending.id(), pending.inspection().manifest().id(),
                pending.inspection().manifest().version().toString());
            locked(c -> {
                records.requirePending(c, pending);
                records.requireCancellation(c, pending);
                String details = JSON.createObjectNode().put("originalPhase", pending.cancelToken().phase())
                    .put("reviewedEntries", pending.cancelToken().entries().size())
                    .put("reviewedBytes", pending.cancelToken().entries().stream()
                        .mapToLong(ModInstallationCleanup.Entry::size).sum())
                    .put("originalArchive", "UNTOUCHED").toString();
                logs.store(c, archive,
                    List.of(new LogRecordCodec.Record(pending.archive(), pending.created(), "app", "mod_operation",
                        LogLevel.INFO, "MOD_INSTALL_CANCELLED",
                        "Reviewed installation cancelled; original archive untouched", details, receipt.owner(),
                        receipt.version(), null, pending.id(), TaskResult.CANCELLED, false)),
                    100);
                InstallationRecords.execute(c, "DELETE FROM mod_operation WHERE operation_id=?", pending.id());
                return null;
            });
            return new OperationResult<>(TaskResult.CANCELLED, Optional.of(receipt), Optional.empty());
        } catch (Exception failure) {
            try {
                records.retain(pending,
                    "CANCELLING: " + (failure instanceof IOException
                        ? bounded(failure.getMessage())
                        : "Cancellation persistence could not be confirmed"));
            } catch (SQLException unavailable) {
                failure.addSuppressed(unavailable);
            }
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "MOD_INSTALL_CANCEL_UNCONFIRMED",
                    "Installation cancellation requires reconciliation using its original file scope",
                    Map.of("operationId", pending.id().toString()), Optional.empty())));
        }
    }

    /** Advances durable phases with full filesystem reinspection before each irreversible boundary. / 在每个不可逆边界前完整复查文件系统，推进持久阶段。
     * @param original accepted original operation / 已接纳原操作
     * @return actual completion or original uncertainty / 实际完成或原不确定性 */
    private OperationResult<Receipt> advance(Pending original) {
        Pending pending = original;
        try {
            Path stage = home.resolve(".mod-staging").resolve(pending.id().toString());
            Path program = stage.resolve("program"), target = paths.target(pending.inspection());
            if (pending.phase().equals("VALIDATED")) {
                pending = paths.stageOriginal(pending, stage, program);
            }
            if (pending.phase().equals("STAGED")) {
                identity(stage, pending.checkpoint().root());
                InstallationPaths.onlyProgram(stage);
                InstallationPaths.container(home.resolve("mod"));
                InstallationPaths.container(target.getParent());
                if (exists(program)) {
                    identity(program, pending.checkpoint().program());
                    ModPackageReader.verifyDirectory(pending.inspection(), program);
                    identity(program, pending.checkpoint().program());
                    WindowsFilePublish.publishDirectory(program, target);
                }
                identity(target, pending.checkpoint().program());
                ModPackageReader.verifyDirectory(pending.inspection(), target);
                pending = records.checkpoint(pending, "FILES_PUBLISHED", pending.checkpoint());
            }
            if (pending.phase().equals("FILES_PUBLISHED")) {
                identity(target, pending.checkpoint().program());
                ModPackageReader.verifyDirectory(pending.inspection(), target);
                identity(target, pending.checkpoint().program());
                pending = records.register(pending, target);
            }
            if (pending.phase().equals("REGISTERED")) {
                identity(target, pending.checkpoint().program());
                ModPackageReader.verifyDirectory(pending.inspection(), target);
                records.verifyRegistration(pending);
                String cleanup = cleanupOriginal(pending);
                pending = records.checkpoint(pending, "CLEANUP",
                    new Checkpoint(pending.checkpoint().root(), pending.checkpoint().program(), cleanup));
            }
            if (!pending.phase().equals("CLEANUP"))
                throw new IOException("Unknown installation phase");
            identity(target, pending.checkpoint().program());
            ModPackageReader.verifyDirectory(pending.inspection(), target);
            records.verifyRegistration(pending);
            if (exists(stage)) {
                identity(stage, pending.checkpoint().root());
                Files.delete(stage); // Empty only; never recursively remove unknown files. / 仅删除空目录，绝不递归移除未知文件。
            }
            var receipt = records.receipt(pending);
            records.finish(pending, receipt);
            return success(receipt);
        } catch (Exception failure) {
            try {
                records.retain(original,
                    pending.phase() + ": "
                        + (failure instanceof IOException
                        ? bounded(failure.getMessage())
                        : "Installation persistence or step completion could not be confirmed"));
            } catch (SQLException unavailable) {
                failure.addSuppressed(unavailable);
            }
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "MOD_INSTALL_UNCONFIRMED",
                    "Installation requires reconciliation using its original operation",
                    Map.of("operationId", original.id().toString()), Optional.empty())));
        }
    }

    /** Bounds plain-text file diagnostics without exposing SQL statements or secret parameters. / 限制纯文本文件诊断，不暴露 SQL 语句或秘密参数。
     * @param message non-secret filesystem explanation / 不含秘密的文件系统说明
     * @return bounded printable explanation / 有界可打印说明 */
    private static String bounded(String message) {
        String text = Objects.toString(message, "Original is unavailable").replaceAll("[\\p{Cntrl}]", " ");
        return text.length() <= 512 ? text : text.substring(0, 512);
    }

    /** Removes only the exact retained original after registration; failure is a separate warning. / 仅在登记后移除精确保留原包；失败是单独警告。
     * @param pending original installed request / 原已安装请求
     * @return separate cleanup observation / 单独清理观察 */
    private String cleanupOriginal(Pending pending) {
        try {
            Path original = pending.inspection().archive();
            if (!exists(original))
                return "ABSENT";
            if (pending.request().keep())
                return "RETAINED";
            paths.originalPath(original);
            identity(original, pending.request().identity());
            var current = ModPackageReader.read(original);
            if (!current.archiveSha256().equals(pending.inspection().archiveSha256()))
                return "RETAINED_UNCONFIRMED";
            identity(original, pending.request().identity());
            Files.delete(original);
            return "DELETED";
        } catch (IOException failure) {
            return "RETAINED_UNCONFIRMED";
        }
    }

    /** Serializes short platform transactions, never holding the row lock while hashing files. / 串行化短平台事务，摘要文件时绝不持有行锁。
     * @param <T> result type / 结果类型
     * @param work host-only SQL work / 仅宿主 SQL 工作
     * @return committed result / 已提交结果
     * @throws SQLException if the boot, work or commit is unavailable / 启动、工作或提交不可用时 */
    private <T> T locked(SqlWork<T> work) throws SQLException {
        var result = new AtomicReference<T>();
        connections.transaction(() -> {
            try (var c = connections.open()) {
                StoreConnections.lockPlatformState(c);
                try (var p = c.prepareStatement("SELECT boot_id,shutdown_state FROM app_state WHERE singleton_id=1");
                     var r = p.executeQuery()) {
                    if (!r.next() || !boot.equals(r.getObject(1, UUID.class))
                        || !Set.of("STARTING", "RUNNING").contains(r.getString(2)))
                        throw new SQLException("Original application boot is unavailable");
                }
                result.set(work.run(c));
            }
        });
        return result.get();
    }

    /** Reads a strict persisted boolean inside the original transaction. / 在原事务内读取严格持久布尔值。
     * @param c locked transaction / 已锁事务
     * @return default-true preference / 默认真的偏好
     * @throws SQLException if malformed or unreadable / 格式错误或不可读时 */
    static boolean keepOriginal(Connection c) throws SQLException {
        try (var p = c.prepareStatement("SELECT preference_value FROM desktop_preference WHERE preference_key=?")) {
            p.setString(1, KEEP);
            try (var r = p.executeQuery()) {
                if (!r.next())
                    return true;
                String value = r.getString(1);
                if (!Set.of("true", "false").contains(value))
                    throw new SQLException("Invalid original retention preference");
                return Boolean.parseBoolean(value);
            }
        }
    }

    /** Rejects redirection, short aliases and case aliases before physical work. / 物理工作前拒绝重定向、短名别名及大小写别名。
     * @param path explicit path / 明示路径
     * @param directory whether the leaf is a directory / 叶是否目录
     * @throws IOException if any path component is invalid / 任何路径段无效时 */
    static void ordinary(Path path, boolean directory) throws IOException {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize()))
            throw new IOException("Explicit canonical path required");
        for (Path part = path; part != null; part = part.getParent()) {
            var attrs = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isOther() || attrs.isSymbolicLink() || !part.toRealPath().toString().equals(part.toString())
                || (part.equals(path) && !directory ? !attrs.isRegularFile() : !attrs.isDirectory()))
                throw new IOException("Installation path is redirected or irregular");
        }
    }

    /** Distinguishes absence from inaccessible filesystem state. / 区分不存在与不可访问的文件系统状态。
     * @param path observed path / 观察路径
     * @return whether an entry exists / 是否存在条目
     * @throws IOException if state cannot be read / 无法读取状态时 */
    static boolean exists(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    /** Confirms physical identity without accepting a same-content replacement. / 确认物理身份，不接受相同内容替代物。
     * @param path observed file or directory / 观察文件或目录
     * @param expected retained native identity / 保留原生身份
     * @throws IOException if redirected or replaced / 重定向或被替代时 */
    static void identity(Path path, String expected) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        ordinary(path, attrs.isDirectory());
        if (expected == null || !expected.equals(WindowsFileIdentity.read(path)))
            throw new IOException("Installation physical identity differs");
    }

    /** Wraps a confirmed installation, preserving cleanup warnings inside its receipt. / 包装已确认安装，将清理警告保留在凭据内。
     * @param receipt original receipt / 原凭据
     * @return success without a false installation failure / 不伪报安装失败的成功结果 */
    private static OperationResult<Receipt> success(Receipt receipt) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(receipt), Optional.empty());
    }

    /** Rejects EDT access or use after accepted calls have drained. / 拒绝 EDT 访问或已接纳调用排空后使用。 */
    private void requireOpen() {
        if (closed || SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Installation requires an open background service");
    }

    /** Closes only after synchronized accepted work returned. / 仅在同步已接纳工作返回后关闭。 */
    @Override
    public synchronized void close() {
        reinstallation.close();
        deletion.close();
        closed = true;
    }

    /** Internal transaction callback without author code. / 不含作者代码的内部事务回调。
     * @param <T> result type / 结果类型 */
    @FunctionalInterface
    private interface SqlWork<T> {
        /** Runs bounded original SQL. / 运行有界原 SQL。
         * @param c locked transaction / 已锁事务
         * @return result / 结果
         * @throws SQLException if persistence fails / 持久化失败时 */
        T run(Connection c) throws SQLException;
    }

    /** Original immutable admission evidence. / 原不可变准入依据。
     * @param format evidence version / 依据版本
     * @param home installation root / 安装根
     * @param boot accepting boot / 接纳启动
     * @param original archive path / 归档路径
     * @param identity native archive identity / 原生归档身份
     * @param manifest original JSON / 原 JSON
     * @param archiveHash original archive digest / 原归档摘要
     * @param contentHash original content digest / 原内容摘要
     * @param files original file evidence / 原文件依据
     * @param keep captured retention / 捕获留存 */
    private record Request(int format, String home, String boot, String original, String identity, String manifest,
                           String archiveHash, String contentHash, List<ModPackageReader.FileEntry> files,
                           boolean keep) {
    }

    /** Proven physical identities and cleanup observation. / 已证明物理身份及清理观察。
     * @param root original staging root identity / 原暂存根身份
     * @param program original program directory identity / 原程序目录身份
     * @param cleanup separate original cleanup / 单独原包清理 */
    private record Checkpoint(String root, String program, String cleanup) {
    }

    /** Persisted original cleanup authority, separate from normal installation checkpoints. / 持久原清理授权，与正常安装检查点分开。
     * @param phase original pre-registration phase / 原登记前阶段
     * @param checkpoint original physical checkpoint / 原物理检查点
     * @param entries exact reviewed files and directories / 精确审阅文件及目录 */
    private record Cancellation(String phase, Checkpoint checkpoint, List<ModInstallationCleanup.Entry> entries) {
        /** Copies the exact accepted scope. / 复制精确接纳范围。
         * @param phase original phase / 原阶段
         * @param checkpoint original physical evidence / 原物理依据
         * @param entries original reviewed entries / 原审阅条目 */
        private Cancellation {
            entries = List.copyOf(entries);
        }
    }

    /** Retained working operation. / 保留工作操作。
     * @param id operation identity / 操作身份
     * @param inspection original content facts / 原内容事实
     * @param request original request / 原请求
     * @param json exact durable request / 精确持久请求
     * @param phase proven durable phase / 已证明持久阶段
     * @param checkpoint physical evidence / 物理依据
     * @param archive fixed archive identity / 固定归档身份
     * @param created original timestamp / 原时间戳
     * @param cancelToken accepted cancellation scope or absent / 已接纳撤销范围或缺省 */
    private record Pending(UUID id, ModPackageReader.Inspection inspection, Request request, String json, String phase,
                           Checkpoint checkpoint, UUID archive, long created, Cancellation cancelToken) {
    }

    /** Original installation records on the same locked transaction. / 在同一已锁事务上的原安装记录。 */
    private final class InstallationRecords {
        /** Binds the original owning service. / 绑定原服务所有者。 */
        private InstallationRecords() {
        }

        /** Reads retained package use and preserves rows whose original evidence is unreadable. / 读取保留包占用，并保留原依据不可读的行。
         * @param c locked transaction / 已锁事务
         * @return original maintenance observations / 原维护观察
         * @throws SQLException if operation storage cannot be read / 无法读取操作存储时 */
        private List<Maintenance> maintenance(Connection c) throws SQLException {
            var results = new ArrayList<Maintenance>();
            try (var p = c.prepareStatement(
                "SELECT operation_id,mod_id,target_version,state,phase,request_json,operation_type,failure_detail FROM mod_operation WHERE operation_type IN ('INSTALL','REINSTALL') AND state<>'ENDED' ORDER BY created_at,operation_id");
                 var r = p.executeQuery()) {
                while (r.next()) {
                    Path path = null;
                    String identity = null;
                    try {
                        String json = r.getString(6);
                        if (json.length() > 8 * 1024 * 1024)
                            throw new IOException("Oversized package use evidence");
                        var node = JSON.readTree(json);
                        if (!home.toString().equals(node.path("home").textValue()))
                            throw new IOException("Unrecognized package use evidence");
                        boolean repair = r.getString(7).equals("REINSTALL");
                        var retained = repair ? reinstallation.original(node, r.getString(2), r.getString(3)) : null;
                        var source = Path.of(repair ? retained.path() : node.required("original").textValue());
                        String nativeId = repair ? retained.identity() : node.required("identity").textValue();
                        if (!source.isAbsolute() || !source.equals(source.normalize())
                            || !repair && !originalsDirectory().equals(source.getParent()) || nativeId == null
                            || !nativeId.matches("[0-9a-f]{48}"))
                            throw new IOException("Invalid package use evidence");
                        path = source;
                        identity = nativeId;
                    } catch (IOException | RuntimeException invalid) {
                        // Unknown ownership must block deletion, not disappear from the view. / 未知归属必须阻止删除，不能从视图消失。
                    }
                    results.add(new Maintenance(new PendingView(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                        r.getString(7), r.getString(4), r.getString(5), Optional.ofNullable(path),
                        Optional.ofNullable(r.getString(8)).map(PlatformModInstallation::bounded)), identity));
                }
            }
            return results;
        }

        /** Rejects registered versions and phases whose installation has already completed. / 拒绝已登记版本及安装已完成的阶段。
         * @param c original locked transaction / 原已锁事务
         * @param pending original operation / 原操作
         * @throws SQLException if cancellation would remove an installed version / 撤销会移除已安装版本时 */
        private void requireCancellation(Connection c, Pending pending) throws SQLException {
            String phase = pending.cancelToken() == null ? pending.phase() : pending.cancelToken().phase();
            if (!Set.of("VALIDATED", "STAGED", "FILES_PUBLISHED").contains(phase))
                throw new SQLException("Registered installations cannot be cancelled");
            try (var query = c.prepareStatement("SELECT 1 FROM mod_version WHERE mod_id=? AND version=?")) {
                query.setString(1, pending.inspection().manifest().id());
                query.setString(2, pending.inspection().manifest().version().toString());
                try (var result = query.executeQuery()) {
                    if (result.next())
                        throw new SQLException("Installed versions require explicit version deletion");
                }
            }
        }

        /** Requires an original unknown operation before taking exclusive cleanup ownership. / 取得独占清理归属前要求原操作未知。
         * @param c original transaction / 原事务
         * @param operation original identity / 原身份
         * @throws SQLException if another execution owns the operation / 另一执行持有操作时 */
        private static void requireUnknown(Connection c, UUID operation) throws SQLException {
            try (var query = c.prepareStatement("SELECT state FROM mod_operation WHERE operation_id=?")) {
                query.setObject(1, operation);
                try (var result = query.executeQuery()) {
                    if (!result.next() || !result.getString(1).equals("UNKNOWN"))
                        throw new SQLException("Original operation is not available for cancellation");
                }
            }
        }

        /** Reads the original cancellation receipt without replaying physical cleanup. / 读取原撤销凭据，不重放物理清理。
         * @param operation original identity / 原身份
         * @return retained receipt or absent / 保留凭据或缺省
         * @throws SQLException if retained results cannot be read / 无法读取保留结果时 */
        private CancellationReceipt cancelled(UUID operation) throws SQLException {
            return locked(c -> {
                try (var query = c.prepareStatement(
                    "SELECT subject_mod_id,mod_version FROM runtime_log WHERE owner_id='app' AND category='mod_operation' AND operation_id=? AND event_code='MOD_INSTALL_CANCELLED' AND outcome='CANCELLED'")) {
                    query.setObject(1, operation);
                    try (var result = query.executeQuery()) {
                        return result.next()
                            ? new CancellationReceipt(operation, result.getString(1), result.getString(2))
                            : null;
                    }
                }
            });
        }

        /** Retains only this exact original request after an unconfirmed write or physical step. / 写入或物理步骤未确认后，仅保留此精确原请求。
         * @param original original accepted identity and request / 原接纳身份及请求
         * @param detail bounded safe host explanation / 有界安全宿主说明
         * @throws SQLException if the uncertainty marker itself cannot be saved / 不确定标记本身无法保存时 */
        private void retain(Pending original, String detail) throws SQLException {
            locked(c -> {
                InstallationRecords.execute(c,
                    "UPDATE mod_operation SET state='UNKNOWN',failure_code='MOD_INSTALL_UNCONFIRMED',failure_detail=?,updated_at=? WHERE operation_id=? AND request_json=? AND state='RUNNING' AND outcome IS NULL",
                    bounded(detail), System.currentTimeMillis(), original.id(), original.json());
                return null;
            });
        }

        /** Registers files and the first default selection in one platform transaction. / 在一个平台事务中登记文件及首次默认选择。
         * @param pending published original / 原已发布操作
         * @param target verified final directory / 已核验最终目录
         * @return original with confirmed registered phase / 已确认登记阶段的原操作
         * @throws SQLException if registration or its phase commit is unconfirmed / 登记或其阶段提交未确认时 */
        private Pending register(Pending pending, Path target) throws SQLException {
            return locked(c -> {
                records.requirePending(c, pending);
                var manifest = pending.inspection().manifest();
                String owner = manifest.id(), version = manifest.version().toString();
                InstallationRecords.execute(c,
                    "INSERT INTO mod_registry(mod_id,updated_at) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM mod_registry WHERE mod_id=?)",
                    owner, System.currentTimeMillis(), owner);
                InstallationRecords.execute(c,
                    "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,archive_sha256,schema_version,manifest_json,origin_kind,installed_at,state) VALUES(?,?,?,?,?,?,?,'LOCAL',?,'READY')",
                    owner, version, target.toString(), pending.inspection().contentSha256(),
                    pending.inspection().archiveSha256(), manifest.schemaVersion().orElse(null), manifest.json(),
                    pending.created());
                var versions = new ArrayList<String>();
                try (var p = c.prepareStatement("SELECT version FROM mod_version WHERE mod_id=? AND state='READY'")) {
                    p.setString(1, owner);
                    try (var r = p.executeQuery()) {
                        while (r.next())
                            versions.add(r.getString(1));
                    }
                }
                String first = versions.stream()
                    .max(Comparator.comparing(SemanticVersion::parse).thenComparing(Comparator.naturalOrder()))
                    .orElseThrow();
                InstallationRecords.execute(c,
                    "UPDATE mod_registry SET selected_version=?,selection_initialized=TRUE,updated_at=? WHERE mod_id=? AND selection_initialized=FALSE AND selected_version IS NULL AND enabled=FALSE AND revision=0",
                    first, System.currentTimeMillis(), owner);
                return checkpoint(c, pending, "REGISTERED", pending.checkpoint());
            });
        }

        /** Confirms the original registered release before deleting its source or archiving success. / 删除来源或归档成功前确认原登记发布。
         * @param pending registered operation / 已登记操作
         * @throws SQLException if registration no longer matches the original request / 登记不再匹配原请求时 */
        private void verifyRegistration(Pending pending) throws SQLException {
            locked(c -> {
                records.requirePending(c, pending);
                try (var p = c.prepareStatement(
                    "SELECT install_path,content_sha256,archive_sha256,manifest_json,origin_kind,state FROM mod_version WHERE mod_id=? AND version=?")) {
                    p.setString(1, pending.inspection().manifest().id());
                    p.setString(2, pending.inspection().manifest().version().toString());
                    try (var r = p.executeQuery()) {
                        if (!r.next()
                            || !r.getString(1)
                            .equals(home.resolve("mod").resolve(pending.inspection().manifest().id())
                                .resolve(pending.inspection().manifest().version().toString()).toString())
                            || !r.getString(2).equals(pending.inspection().contentSha256())
                            || !r.getString(3).equals(pending.inspection().archiveSha256())
                            || !r.getString(4).equals(pending.inspection().manifest().json())
                            || !r.getString(5).equals("LOCAL") || !r.getString(6).equals("READY"))
                            throw new SQLException("Original installation registration differs");
                    }
                }
                return null;
            });
        }

        /** Archives success and removes the working row atomically after cleanup. / 清理后原子归档成功并移除工作行。
         * @param pending original completed working row / 原已完成工作行
         * @param receipt separate cleanup receipt / 单独清理凭据
         * @throws SQLException if completion cannot be confirmed / 无法确认完成时 */
        private void finish(Pending pending, Receipt receipt) throws SQLException {
            locked(c -> {
                records.requirePending(c, pending);
                var details = JSON.createObjectNode().put("directory", receipt.directory().toString())
                    .put("originalCleanup", receipt.originalCleanup()).toString();
                logs.store(c, archive,
                    List.of(new LogRecordCodec.Record(pending.archive(), pending.created(), "app", "mod_operation",
                        LogLevel.INFO, "MOD_INSTALLED", "Reviewed original package installed", details,
                        receipt.owner(), receipt.version(), null, pending.id(), TaskResult.SUCCEEDED, false)),
                    100);
                InstallationRecords.execute(c, "DELETE FROM mod_operation WHERE operation_id=?", pending.id());
                return null;
            });
        }

        /** Reads a retained terminal receipt when the caller did not observe the final commit. / 调用方未观察最终提交时读取保留终态凭据。
         * @param operation original identity / 原身份
         * @return original receipt or null when absent / 原凭据，不存在时为空
         * @throws SQLException if receipt storage is unavailable or invalid / 凭据存储不可用或无效时 */
        private Receipt completed(UUID operation) throws SQLException {
            return locked(c -> {
                try (var p = c.prepareStatement(
                    "SELECT subject_mod_id,mod_version,details FROM runtime_log WHERE owner_id='app' AND category='mod_operation' AND operation_id=? AND event_code='MOD_INSTALLED' AND outcome='SUCCEEDED'")) {
                    p.setObject(1, operation);
                    try (var r = p.executeQuery()) {
                        if (!r.next())
                            return null;
                        try {
                            var details = JSON.readTree(r.getString(3));
                            Path path = Path.of(details.required("directory").textValue());
                            if (!path.equals(home.resolve("mod").resolve(r.getString(1)).resolve(r.getString(2))))
                                throw new IOException("Receipt is outside original home");
                            return new Receipt(operation, r.getString(1), r.getString(2), path,
                                details.required("originalCleanup").textValue());
                        } catch (IOException | RuntimeException invalid) {
                            throw new SQLException("Invalid installation receipt", invalid);
                        }
                    }
                }
            });
        }

        /** Refuses duplicate releases and concurrent owner maintenance without changing existing intent. / 拒绝重复发布及并发所有者维护，不改变已有意图。
         * @param c locked transaction / 已锁事务
         * @param inspection reviewed package / 已审阅包
         * @throws SQLException if admission conflicts / 准入冲突时 */
        private void available(Connection c, ModPackageReader.Inspection inspection) throws SQLException {
            try (var p = c.prepareStatement(
                "SELECT 1 FROM mod_version WHERE mod_id=? AND version=? UNION ALL SELECT 1 FROM mod_operation WHERE mod_id=?")) {
                p.setString(1, inspection.manifest().id());
                p.setString(2, inspection.manifest().version().toString());
                p.setString(3, inspection.manifest().id());
                try (var r = p.executeQuery()) {
                    if (r.next())
                        throw new SQLException("Release already exists or owner has retained maintenance");
                }
            }
            try {
                Path target = paths.target(inspection);
                InstallationPaths.directory(home);
                if (exists(home.resolve("mod"))) {
                    InstallationPaths.directory(home.resolve("mod"));
                    InstallationPaths.noAlias(target.getParent());
                    if (exists(target.getParent())) {
                        InstallationPaths.directory(target.getParent());
                        InstallationPaths.noAlias(target);
                    }
                }
                if (exists(target))
                    throw new IOException("Target directory already exists");
            } catch (IOException failure) {
                throw new SQLException("Installation target is unavailable", failure);
            }
        }

        /** Loads and validates only the original installation evidence. / 仅加载并校验原安装依据。
         * @param c locked transaction / 已锁事务
         * @param operation original identity / 原身份
         * @return validated original request / 已校验原请求
         * @throws SQLException if absent, malformed or bound elsewhere / 缺失、格式错误或绑定其他位置时 */
        private Pending load(Connection c, UUID operation) throws SQLException {
            try (var p = c.prepareStatement(
                "SELECT * FROM mod_operation WHERE operation_id=? AND operation_type='INSTALL' AND outcome IS NULL")) {
                p.setObject(1, operation);
                try (var r = p.executeQuery()) {
                    if (!r.next())
                        throw new SQLException("Original installation evidence is absent");
                    try {
                        String json = r.getString("request_json"), recovery = r.getString("recovery_json");
                        String phase = r.getString("phase");
                        boolean cancelling = phase.equals("CANCELLING");
                        if (json.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024
                            || recovery.getBytes(StandardCharsets.UTF_8).length > (cancelling ? 8 * 1024 * 1024 : 4096))
                            throw new IOException("Retained installation evidence exceeds limits");
                        var request = JSON.readValue(json, Request.class);
                        var cancellation = cancelling ? JSON.readValue(recovery, Cancellation.class) : null;
                        var checkpoint = cancelling
                            ? cancellation.checkpoint()
                            : JSON.readValue(recovery, Checkpoint.class);
                        var manifest = ManifestReader.read(request.manifest().getBytes(StandardCharsets.UTF_8));
                        if (request.format() != 1 || !request.home().equals(home.toString())
                            || !UUID.fromString(request.boot()).toString().equals(request.boot())
                            || !request.identity().matches("[0-9a-f]{48}")
                            || !request.archiveHash().matches("[0-9a-f]{64}")
                            || !request.contentHash().matches("[0-9a-f]{64}")
                            || request.files().size() > ModPackageReader.MAX_ENTRIES
                            || !manifest.id().equals(r.getString("mod_id"))
                            || !manifest.version().toString().equals(r.getString("target_version")))
                            throw new IOException("Retained installation identity differs");
                        Path path = Path.of(request.original());
                        if (!path.isAbsolute() || !path.equals(path.normalize())
                            || !path.getParent().equals(home.resolve("mod-zip")))
                            throw new IOException("Retained original is outside its root");
                        var inspection = new ModPackageReader.Inspection(path, manifest, request.archiveHash(),
                            request.contentHash(), request.files(), List.of(), Map.of());
                        paths.target(inspection);
                        String originalPhase = cancelling ? cancellation.phase() : phase;
                        if (!Set.of("VALIDATED", "STAGED", "FILES_PUBLISHED", "REGISTERED", "CLEANUP")
                            .contains(originalPhase)
                            || cancelling && !Set.of("VALIDATED", "STAGED", "FILES_PUBLISHED").contains(originalPhase)
                            || checkpoint.root() != null && !checkpoint.root().matches("[0-9a-f]{48}")
                            || checkpoint.program() != null && !checkpoint.program().matches("[0-9a-f]{48}")
                            || !originalPhase.equals("VALIDATED")
                            && (checkpoint.root() == null || checkpoint.program() == null)
                            || phase.equals("CLEANUP")
                            && !Set.of("RETAINED", "DELETED", "ABSENT", "RETAINED_UNCONFIRMED")
                            .contains(checkpoint.cleanup()))
                            throw new IOException("Invalid retained installation phase");
                        if (cancelling)
                            ModInstallationCleanup.validate(cancellation.entries(),
                                home.resolve(".mod-staging").resolve(operation.toString()), checkpoint.root(),
                                paths.target(inspection), checkpoint.program(), inspection.files());
                        return new Pending(operation, inspection, request, json, phase, checkpoint,
                            UUID.fromString(r.getString("archive_log_id")), r.getLong("created_at"), cancellation);
                    } catch (IOException | RuntimeException invalid) {
                        throw new SQLException("Retained installation cannot be validated", invalid);
                    }
                }
            }
        }

        /** Confirms original durable identity before changing its phase. / 改变阶段前确认原持久身份。
         * @param c locked transaction / 已锁事务
         * @param pending original expected state / 原期望状态
         * @throws SQLException if another state was observed / 观察到其他状态时 */
        private void requirePending(Connection c, Pending pending) throws SQLException {
            var actual = records.load(c, pending.id());
            if (!actual.json().equals(pending.json()) || !actual.phase().equals(pending.phase())
                || !actual.checkpoint().equals(pending.checkpoint()) || !actual.archive().equals(pending.archive()))
                throw new SQLException("Original installation checkpoint changed");
            if (!Objects.equals(actual.cancelToken(), pending.cancelToken()))
                throw new SQLException("Original cancellation scope changed");
        }

        /** Commits a checkpoint without repeating filesystem effects on uncertainty. / 提交检查点，不在不确定时重复文件系统效果。
         * @param pending prior original state / 先前原状态
         * @param phase new proven phase / 新证明阶段
         * @param checkpoint observed identities / 观察身份
         * @return committed state / 已提交状态
         * @throws SQLException if checkpoint persistence is unconfirmed / 检查点持久化未确认时 */
        private Pending checkpoint(Pending pending, String phase, Checkpoint checkpoint) throws SQLException {
            return locked(c -> records.checkpoint(c, pending, phase, checkpoint));
        }

        /** Writes a proven phase inside the owning transaction. / 在所属事务内写入已证明阶段。
         * @param c locked transaction / 已锁事务
         * @param pending prior original state / 先前原状态
         * @param phase proven next phase / 已证明下一阶段
         * @param checkpoint original physical evidence / 原物理依据
         * @return prospective committed state / 待提交状态
         * @throws SQLException if update is refused / 更新被拒时 */
        private Pending checkpoint(Connection c, Pending pending, String phase, Checkpoint checkpoint) throws SQLException {
            records.requirePending(c, pending);
            if (InstallationRecords.execute(c,
                "UPDATE mod_operation SET phase=?,recovery_json=?,updated_at=? WHERE operation_id=? AND state='RUNNING'",
                phase, encode(checkpoint), System.currentTimeMillis(), pending.id()) != 1)
                throw new SQLException("Installation no longer owns its original phase");
            return new Pending(pending.id(), pending.inspection(), pending.request(), pending.json(), phase, checkpoint,
                pending.archive(), pending.created(), pending.cancelToken());
        }

        /** Encodes a small original physical checkpoint. / 编码小型原物理检查点。
         * @param value checkpoint / 检查点
         * @return strict JSON / 严格 JSON
         * @throws SQLException if encoding fails / 编码失败时 */
        private static String encode(Checkpoint value) throws SQLException {
            try {
                return JSON.writeValueAsString(value);
            } catch (IOException failure) {
                throw new SQLException("Cannot encode checkpoint", failure);
            }
        }

        /** Binds all SQL values without composing author-controlled SQL. / 绑定全部 SQL 值，不拼接作者控制的 SQL。
         * @param c owning transaction / 所属事务
         * @param sql fixed platform statement / 固定平台语句
         * @param values original bound values / 原绑定值
         * @return affected row count / 受影响行数
         * @throws SQLException if execution fails / 执行失败时 */
        private static int execute(Connection c, String sql, Object... values) throws SQLException {
            try (var p = c.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++)
                    p.setObject(i + 1, values[i]);
                return p.executeUpdate();
            }
        }

        /** Produces a receipt from original identities only. / 仅从原身份生成凭据。
         * @param pending original operation / 原操作
         * @return separate cleanup receipt / 单独清理凭据
         * @throws IOException if original target is invalid / 原目标无效时 */
        private Receipt receipt(Pending pending) throws IOException {
            return new Receipt(pending.id(), pending.inspection().manifest().id(),
                pending.inspection().manifest().version().toString(), paths.target(pending.inspection()),
                pending.checkpoint().cleanup());
        }
    }

    /** Canonical installation paths and physical ownership checks. / 规范安装路径与物理归属核验。 */
    private final class InstallationPaths {
        /** Binds the original owning service. / 绑定原服务所有者。 */
        private InstallationPaths() {
        }

        /** Requires a direct ordinary original-package path. / 要求直接普通原包路径。
         * @param original explicit archive / 明示归档
         * @throws IOException if outside, redirected or irregular / 越界、重定向或不是普通文件时 */
        private void originalPath(Path original) throws IOException {
            InstallationPaths.directory(home.resolve("mod-zip"));
            if (!original.isAbsolute() || !original.equals(original.normalize())
                || !original.getParent().equals(home.resolve("mod-zip"))
                || !original.getFileName().toString().endsWith(".wtlmod"))
                throw new IOException("Original must be a direct mod-zip child");
            ordinary(original, false);
        }

        /** Resolves only exact Windows-safe owner and release components. / 仅解析精确 Windows 安全所有者及发布路径段。
         * @param inspection original facts / 原事实
         * @return fixed final directory / 固定最终目录
         * @throws IOException if spelling cannot be preserved / 无法保留拼写时 */
        private Path target(ModPackageReader.Inspection inspection) throws IOException {
            for (String part : List.of(inspection.manifest().id(), inspection.manifest().version().toString()))
                if (part.isEmpty() || part.length() > 255 || part.endsWith(".") || part.endsWith(" ")
                    || part.matches(".*[<>:\"|?*\\\\/\\x00-\\x1f].*")
                    || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?"))
                    throw new IOException("Mod identity cannot be preserved as a Windows directory");
            return home.resolve("mod").resolve(inspection.manifest().id())
                .resolve(inspection.manifest().version().toString());
        }

        /** Creates only a single ordinary container, never a redirected parent tree. / 仅创建单个普通容器，绝不创建重定向父目录树。
         * @param path fixed container / 固定容器
         * @throws IOException if unavailable / 不可用时 */
        private static void container(Path path) throws IOException {
            InstallationPaths.directory(path.getParent());
            InstallationPaths.noAlias(path);
            try {
                Files.createDirectory(path);
            } catch (FileAlreadyExistsException exists) {
                /* Verify below. / 下方核验。 */
            }
            InstallationPaths.directory(path);
        }

        /** Refuses normalization or case collisions without renaming an author's identity. / 拒绝规范化或大小写冲突，不重命名作者身份。
         * @param path requested child path / 请求子路径
         * @throws IOException if an existing sibling has an ambiguous spelling / 现有同级项拼写有歧义时 */
        private static void noAlias(Path path) throws IOException {
            String name = path.getFileName().toString();
            String key = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).toUpperCase(Locale.ROOT);
            try (var siblings = Files.newDirectoryStream(path.getParent())) {
                for (Path sibling : siblings) {
                    String existing = sibling.getFileName().toString();
                    if (!existing.equals(name) && java.text.Normalizer.normalize(existing, java.text.Normalizer.Form.NFC)
                        .toUpperCase(Locale.ROOT).equals(key))
                        throw new IOException("Installation directory spelling conflicts with an existing entry");
                }
            }
        }

        /** Requires an ordinary explicit directory and ordinary ancestors. / 要求普通明示目录及普通祖先。
         * @param path directory / 目录
         * @throws IOException if redirected or unavailable / 重定向或不可用时 */
        private static void directory(Path path) throws IOException {
            ordinary(path, true);
        }

        /** Rejects any unowned entry in an operation's staging root. / 拒绝操作暂存根中的任何无归属条目。
         * @param stage original operation directory / 原操作目录
         * @throws IOException if unexpected content exists / 存在意外内容时 */
        private static void onlyProgram(Path stage) throws IOException {
            try (var entries = Files.newDirectoryStream(stage)) {
                for (Path entry : entries)
                    if (!entry.getFileName().toString().equals("program"))
                        throw new IOException("Unexpected operation staging content");
            }
        }

        /** Stages the exact original package and persists its original physical checkpoints. / 暂存精确原包并持久保存其原物理检查点。
         * @param pending original accepted operation / 原已接纳操作
         * @param stage fixed operation root / 固定操作根
         * @param program fixed staged program / 固定暂存程序
         * @return the same original operation advanced to STAGED / 推进至STAGED的同一原操作
         * @throws IOException if original bytes or native identity differ / 原字节或原生身份不符时
         * @throws SQLException if checkpoint persistence is unconfirmed / 检查点持久保存未确认时 */
        private Pending stageOriginal(Pending pending, Path stage, Path program) throws IOException, SQLException {
            InstallationPaths.container(home.resolve(".mod-staging"));
            if (pending.checkpoint().root() == null) {
                Files.createDirectory(stage);
                pending = records.checkpoint(pending, "VALIDATED",
                    new Checkpoint(WindowsFileIdentity.read(stage), null, null));
            }
            identity(stage, pending.checkpoint().root());
            InstallationPaths.onlyProgram(stage);
            if (!exists(program)) {
                paths.originalPath(pending.inspection().archive());
                identity(pending.inspection().archive(), pending.request().identity());
                ModPackageReader.extract(pending.inspection(), program);
            }
            ModPackageReader.verifyDirectory(pending.inspection(), program);
            identity(stage, pending.checkpoint().root());
            pending = records.checkpoint(pending, "STAGED",
                new Checkpoint(pending.checkpoint().root(), WindowsFileIdentity.read(program), null));
            return pending;
        }
    }

    /** Combines actual original files with the same locked maintenance inventory. / 将实际原文件与同一已锁维护清单合并。 */
    private final class InstallationList {
        /** Binds the original installation owner. / 绑定原安装所有者。 */
        private InstallationList() {
        }

        /** Reads files and saved status under the original outer monitor. / 在原外部同步锁下读取文件与已存状态。
         * @return actual package inventory / 实际包清单
         * @throws IOException if physical originals differ / 物理原包不符时
         * @throws SQLException if saved status is unavailable / 已存状态不可用时 */
        private SourceList read() throws IOException, SQLException {
            InstallationPaths.directory(home);
            var observed = observeOriginals();
            return locked(c -> {
                var pending = records.maintenance(c);
                var rows = new ArrayList<OriginalView>();
                for (var value : observed) {
                    boolean busy = InstallationList.inUse(pending, value.file(), value.stamp()) || deletion.originalBusy(c, value.file(),
                        value.stamp() == null ? null : value.stamp().identity());
                    String state = value.inspection() == null ? "INVALID" : "AVAILABLE";
                    if (value.inspection() != null) {
                        var manifest = value.inspection().manifest();
                        try (var p = c.prepareStatement(
                            "SELECT content_sha256 FROM mod_version WHERE mod_id=? AND version=?")) {
                            p.setString(1, manifest.id());
                            p.setString(2, manifest.version().toString());
                            try (var r = p.executeQuery()) {
                                if (r.next())
                                    state = r.getString(1).equals(value.inspection().contentSha256())
                                        ? "INSTALLED"
                                        : "CONFLICT";
                            }
                        }
                        try (var p = c.prepareStatement("SELECT 1 FROM mod_operation WHERE mod_id=?")) {
                            p.setString(1, manifest.id());
                            try (var r = p.executeQuery()) {
                                if (r.next())
                                    state = "BUSY";
                            }
                        }
                    }
                    if (busy)
                        state = "BUSY";
                    rows.add(new OriginalView(value.file(),
                        Optional.ofNullable(value.inspection()).map(PlatformModInstallation::packageInfo), state,
                        Optional.ofNullable(value.problem()), value.stamp() != null && !busy));
                }
                return new SourceList(rows, pending.stream().map(Maintenance::view).toList(), keepOriginal(c));
            });
        }

        /** Checks exact path or native identity, conservatively retaining unreadable maintenance. / 检查精确路径或原生身份，保守保留不可读维护。
         * @param operations retained operations / 保留操作

         * @param file selected original / 已选原包

         * @param stamp actual physical evidence, if readable / 可读时的实际物理依据

         * @return whether source removal is blocked / 是否阻止来源移除 */

        private static boolean inUse(List<Maintenance> operations, Path file, FileStamp stamp) {

            return operations.stream()

                .anyMatch(o -> o.view().original().isEmpty() || o.identity() == null

                    || o.view().original().orElseThrow().equals(file)

                    || stamp != null && o.identity().equals(stamp.identity()));

        }

        /** Reads physical deletion evidence without requiring valid package bytes. / 读取物理删除依据，不要求包字节有效。
         * @param file ordinary original / 普通原文件

         * @return current file stamp / 当前文件事实

         * @throws IOException if unreadable or changed during inspection / 不可读或检查中变化时 */

        private static FileStamp stamp(Path file) throws IOException {

            ordinary(file, false);

            var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            String identity = WindowsFileIdentity.read(file);

            var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()

                || !before.lastModifiedTime().equals(after.lastModifiedTime()))

                throw new IOException("Original changed during inspection");

            return new FileStamp(identity, after.size(), after.lastModifiedTime().toString());

        }

        /** Observes exact direct package bytes before acquiring the database row lock. / 取得数据库行锁前观察精确直接包字节。
         * @return sorted physical package observations / 已排序物理包观察
         * @throws IOException if the physical inventory is unavailable / 物理清单不可用时 */
        private List<Observed> observeOriginals() throws IOException {
            var observed = new ArrayList<Observed>();
            if (exists(originalsDirectory())) {
                InstallationPaths.directory(originalsDirectory());
                try (var entries = Files.newDirectoryStream(originalsDirectory(), "*.wtlmod")) {
                    for (Path path : entries) {
                        if (!path.getFileName().toString().endsWith(".wtlmod"))
                            continue;
                        ModPackageReader.Inspection inspection = null;
                        FileStamp stamp = null;
                        Problem problem = null;
                        try {
                            stamp = InstallationList.stamp(path);
                            inspection = ModPackageReader.read(path);
                            if (!stamp.equals(InstallationList.stamp(path)))
                                throw new IOException("Original changed while scanning");
                        } catch (IOException failure) {
                            problem = new Problem(ProblemCode.UNAVAILABLE, "MOD_PACKAGE_INVALID",
                                bounded(failure.getMessage()), Map.of(), Optional.empty());
                            inspection = null;
                        }
                        observed.add(new Observed(path, inspection, stamp, problem));
                    }
                }
            }
            observed.sort(Comparator.comparing(o -> o.file().getFileName().toString()));
            return observed;
        }
    }
}
