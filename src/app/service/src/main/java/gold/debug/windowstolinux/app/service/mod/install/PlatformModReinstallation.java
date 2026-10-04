package gold.debug.windowstolinux.app.service.mod.install;

import static gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

import javax.swing.SwingUtilities;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.mod.ModReinstallationLedger;
import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.mod.install.ModPackageReader;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity;
import gold.debug.windowstolinux.app.windows.file.WindowsFilePublish;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Repairs the original installed release while preserving data, choices and a recoverable old program. / 修复原安装发布，保留数据、选择及可恢复旧程序。 */
public final class PlatformModReinstallation implements AutoCloseable {
    /** Original repair file identities, locations and authorized subsets. / 原修复文件身份、位置与授权子集。 */
    private final RepairFileScope files = new RepairFileScope();

    /** Original trusted installation root. / 原可信安装根。 */
    private final Path home;

    /** Original catalog and receipt database. / 原目录及凭据数据库。 */
    private final PlatformDatabase database;

    /** Shared unlocked-operation authority. / 共用解锁操作权限。 */
    private final DesktopSecurityService security;

    /** Actual compiled public API identity. / 实际编译公开 API 身份。 */
    private final SemanticVersion api;

    /** Original durable repair authority. / 原持久修复权限。 */
    private final ModReinstallationLedger ledger;

    /** Prevents admission after accepted work drains. / 已接纳工作排空后阻止准入。 */
    private boolean closed;

    /** Rejects ambiguous or truncated physical evidence. / 拒绝歧义或截断物理依据。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Binds repair to the installed host and its shared operation gate. / 将修复绑定安装宿主及其共用操作门。
     * @param database original database / 原数据库
     * @param security original security owner / 原安全所有者
     * @param home installed home / 安装根
     * @param api actual public API version / 实际公开 API 版本
     * @throws SQLException if the original root or ledger is unavailable / 原根或账本不可用时 */
    PlatformModReinstallation(PlatformDatabase database, DesktopSecurityService security, Path home,
                              SemanticVersion api) throws SQLException {
        this.database = database;
        this.security = security;
        this.home = home;
        this.api = Objects.requireNonNull(api);
        ledger = new ModReinstallationLedger(database, home);
    }

    /** One-use approval of exact new bytes and the actual old program scope. / 精确新字节及实际旧程序范围的一次性批准。 */
    public final class Review {
        /** Issuing physical service. / 签发物理服务。 */
        private final PlatformModReinstallation issuer = PlatformModReinstallation.this;

        /** Original catalog authority. / 原目录权限。 */
        private final ModReinstallationLedger.Review catalog;

        /** Original verified archive. / 原已验证归档。 */
        private final ModPackageReader.Inspection inspection;

        /** Exact immutable file scope. / 精确不可变文件范围。 */
        private final Evidence evidence;

        /** Prevents uncertain approval replay. / 防止未知批准重放。 */
        private boolean used;

        /** Retains reviewed facts. / 保留已审阅事实。
         * @param catalog original catalog / 原目录
         * @param inspection verified archive / 已验证归档
         * @param evidence captured files / 捕获文件 */
        private Review(ModReinstallationLedger.Review catalog, ModPackageReader.Inspection inspection,
                       Evidence evidence) {
            this.catalog = catalog;
            this.inspection = inspection;
            this.evidence = evidence;
        }

        /** Returns the stable original repair identity. / 返回稳定原修复身份。
         * @return operation identity / 操作身份 */
        public UUID id() {
            return catalog.id();
        }

        /** Returns verified package facts for confirmation. / 返回供确认的已验证包事实。
         * @return original package / 原包 */
        public ModPackageInfo packageInfo() {
            return PlatformModInstallation.packageInfo(inspection);
        }

        /** Lists the old files that will be saved before publication. / 列出发布前将保存的旧文件。
         * @return actual old scope / 实际旧范围 */
        public List<Path> oldFiles() {
            return evidence.old().stream().map(e -> Path.of(e.path())).toList();
        }

        /** Returns the captured retention choice; external inputs are always retained. / 返回捕获留存选择；外部输入始终保留。
         * @return whether original cleanup was excluded / 是否排除原包清理 */
        public boolean keepOriginal() {
            return evidence.keep();
        }
    }

    /** Confirmed program result with separately reported managed-original cleanup. / 已确认程序结果及单独报告的受管原包清理。
     * @param operationId original repair / 原修复
     * @param owner original Mod / 原 Mod
     * @param version original release / 原发布
     * @param restored whether repair failed and the old program was restored / 修复是否失败并恢复旧程序
     * @param originalCleanup exact source cleanup result / 精确来源清理结果 */
    public record Receipt(UUID operationId, String owner, String version, boolean restored, String originalCleanup) {
    }

    /** Reviews a selected local archive without copying, executing or changing program files. / 审阅选定本地归档，不复制、执行或改变程序文件。
     * @param owner installed Mod / 安装 Mod
     * @param version installed release / 安装发布
     * @param original explicitly selected ordinary archive / 明确选定普通归档
     * @return original single-use confirmation / 原一次性确认
     * @throws IOException if package, compatibility or physical scope is invalid / 包、兼容或物理范围无效时
     * @throws SQLException if stop or catalog admission is unconfirmed / 停止或目录准入未确认时 */
    public synchronized Review review(String owner, String version, Path original) throws IOException, SQLException {
        requireOpen();
        try (var permission = security.enterOperation()) {
            ordinary(original, false);
            var source = ModInstallationCleanup.observe(original, ModPackageReader.MAX_BYTES);
            var inspection = ModPackageReader.read(original);
            if (!owner.equals(inspection.manifest().id())
                || !version.equals(inspection.manifest().version().toString()))
                throw new IOException("Repair package identity differs from selected release");
            compatible(inspection);
            var catalog = ledger.review(owner, version, inspection.contentSha256(), inspection.archiveSha256());
            if (!catalog.manifest().equals(inspection.manifest().json()))
                throw new IOException("Repair manifest differs from the original release");
            Path stage = files.stage(catalog.id());
            if (exists(stage))
                throw new IOException("Original repair staging already exists");
            if (original.startsWith(home.resolve("mod")) || original.startsWith(home.resolve(".mod-staging")))
                throw new IOException("Repair archive must be outside installed and staged programs");
            boolean keep;
            try (var c = database.openConnection()) {
                keep = keepOriginal(c);
            }
            keep |= !original.getParent().equals(home.resolve("mod-zip"));
            var evidence = new Evidence(1, RepairFileScope.nativeDirectory(home), RepairFileScope.nativeDirectory(home.resolve("mod")),
                RepairFileScope.nativeDirectory(catalog.program().getParent()), source, RepairFileScope.nativeDirectory(original.getParent()), keep,
                inspection.files(), ModInstallationCleanup.installed(catalog.program()));
            files.parents(evidence, catalog.program());
            RepairFileScope.unchanged(source);
            return new Review(catalog, inspection, evidence);
        }
    }

    /** Persists the reviewed replacement before the first physical mutation. / 首次物理变更前持久保存已审阅替换。
     * @param review original unused approval / 原未使用批准
     * @return verified result or retained uncertainty / 已验证结果或保留未知
     * @throws IOException if approved bytes changed before admission / 准入前批准字节变化时
     * @throws SQLException if acceptance is unconfirmed / 接纳未确认时 */
    public synchronized OperationResult<Receipt> reinstall(Review review) throws IOException, SQLException {
        requireOpen();
        if (review == null || review.issuer != this || review.used)
            throw new IllegalArgumentException("Original unused repair review required");
        try (var permission = security.enterOperation()) {
            files.parents(review.evidence, review.catalog.program());
            identity(Path.of(review.evidence.original().path()).getParent(), review.evidence.originalParent());
            RepairFileScope.unchanged(review.evidence.original());
            if (!review.evidence.old().equals(ModInstallationCleanup.installed(review.catalog.program())))
                throw new IOException("Old program changed after repair confirmation");
            review.used = true;
            ledger.begin(review.catalog, encode(review.evidence),
                encode(new Checkpoint(1, null, null, null, null, null, null)));
            return advance(review.id());
        }
    }

    /** Continues only the original unknown repair or reads its retained terminal result. / 仅继续原未知修复或读取其保留终态。
     * @param operation original repair / 原修复
     * @return actual original result / 实际原结果
     * @throws SQLException if original catalog or claim is unavailable / 原目录或取得记录不可用时
     * @throws IOException if retained scope is malformed / 保留范围畸形时 */
    public synchronized OperationResult<Receipt> reconcile(UUID operation) throws SQLException, IOException {
        requireOpen();
        try (var permission = security.enterOperation()) {
            var complete = receipt(operation);
            if (complete != null)
                return result(complete);
            read(operation);
            ledger.resume(operation);
            return advance(operation);
        }
    }

    /** User-reviewed cleanup needed to restore the unchanged old program. / 恢复未变旧程序所需的用户已审阅清理。 */
    public final class RestoreReview {
        /** Original issuing service. / 原签发服务。 */
        private final PlatformModReinstallation issuer = PlatformModReinstallation.this;

        /** Original immutable execution and observation. / 原不可变执行及观察。 */
        private final Pending pending;

        /** Exact confirmed new-program location. / 精确确认新程序位置。 */
        private final String location;

        /** Actual new bytes explicitly approved for removal. / 明确批准移除的实际新字节。 */
        private final List<ModInstallationCleanup.Entry> discarded;

        /** Rejects repeated restoration approval. / 拒绝重复恢复批准。 */
        private boolean used;

        /** Saves actual restoration observations. / 保存实际恢复观察。
         * @param pending original repair / 原修复
         * @param location STAGE, TARGET or NONE / 暂存、目标或无
         * @param discarded confirmed actual new files / 确认实际新文件 */
        private RestoreReview(Pending pending, String location, List<ModInstallationCleanup.Entry> discarded) {
            this.pending = pending;
            this.location = location;
            this.discarded = List.copyOf(discarded);
        }

        /** Lists actual new files that restoration removes. / 列出恢复会移除的实际新文件。
         * @return exact reviewed paths / 精确已审阅路径 */
        public List<Path> discardedFiles() {
            return discarded.stream().map(e -> Path.of(e.path())).toList();
        }

        /** Returns the original repair identity. / 返回原修复身份。
         * @return stable operation / 稳定操作 */
        public UUID id() {
            return pending.execution().id();
        }

        /** Returns the owner of the original repair, independent of the current selection. / 返回原修复所有者，独立于当前选择。
         * @return exact original owner / 精确原所有者 */
        public String owner() {
            return pending.execution().owner();
        }

        /** Returns the release restored by this original operation. / 返回此原操作恢复的发布。
         * @return exact original release / 精确原发布 */
        public String version() {
            return pending.execution().version();
        }
    }

    /** Reviews restoration only while complete original old files remain identifiable. / 仅在完整原旧文件仍可识别时审阅恢复。
     * @param operation original unknown repair / 原未知修复
     * @return explicit restoration scope / 明确恢复范围
     * @throws SQLException if the repair is no longer restorable / 修复已不可恢复时
     * @throws IOException if old or new file ownership is ambiguous / 旧或新文件归属含糊时 */
    public synchronized RestoreReview reviewRestore(UUID operation) throws SQLException, IOException {
        requireOpen();
        try (var permission = security.enterOperation()) {
            var p = read(operation);
            if (!p.execution().state().equals("UNKNOWN")
                || !Set.of("VALIDATED", "STAGED", "OLD_SAVED", "FILES_PUBLISHED").contains(p.execution().phase()))
                throw new SQLException("Repair is not awaiting a pre-registration restoration decision");
            files.checkedParents(p);
            files.oldLocation(p);
            String location = files.newLocation(p);
            var discarded = location.equals("NONE")
                ? List.<ModInstallationCleanup.Entry>of()
                : ModInstallationCleanup.installed(files.newPath(p, location));
            files.allowedNewFiles(p, discarded, files.newPath(p, location));
            restorationCheckpoint(p, location, discarded);
            return new RestoreReview(p, location, discarded);
        }
    }

    /** Commits explicit restoration intent before removing any reviewed new files. / 移除任何已审阅新文件前提交明确恢复意图。
     * @param review original restoration approval / 原恢复批准
     * @return failed repair with restored old files, or retained uncertainty / 已恢复旧文件的修复失败或保留未知
     * @throws SQLException if original catalog or claim changed / 原目录或取得记录变化时
     * @throws IOException if the reviewed physical scope changed / 已审阅物理范围变化时 */
    public synchronized OperationResult<Receipt> restore(RestoreReview review) throws SQLException, IOException {
        requireOpen();
        if (review == null || review.issuer != this || review.used)
            throw new IllegalArgumentException("Original unused restoration review required");
        try (var permission = security.enterOperation()) {
            var p = read(review.id());
            if (!p.execution().equals(review.pending.execution()))
                throw new SQLException("Repair changed after restoration review");
            files.checkedParents(p);
            files.oldLocation(p);
            if (!review.location.equals(files.newLocation(p)) || !review.discarded.equals(review.location.equals("NONE")
                ? List.of()
                : ModInstallationCleanup.installed(files.newPath(p, review.location))))
                throw new IOException("New files changed after restoration review");
            String checkpoint = restorationCheckpoint(p, review.location, review.discarded);
            review.used = true;
            ledger.resume(review.id());
            ledger.beginRestore(review.id(), p.execution().phase(), checkpoint);
            return advance(review.id());
        }
    }

    /** Validates restoration size before claiming the operation or presenting confirmation. / 取得操作或展示确认前验证恢复容量。
     * @param p original operation / 原操作
     * @param location reviewed new-program location / 已审阅新程序位置
     * @param discarded exact reviewed cleanup / 精确已审阅清理
     * @return bounded original restoration checkpoint / 有界原恢复检查点
     * @throws IOException if restoration exceeds the durable checkpoint bound / 恢复超出持久检查点界限时 */
    private static String restorationCheckpoint(Pending p, String location,
                                                List<ModInstallationCleanup.Entry> discarded) throws IOException {
        var cp = p.checkpoint();
        String encoded = encode(new Checkpoint(1, cp.parent(), cp.root(), cp.program(), location, discarded, null));
        if (encoded.length() > 1024 * 1024)
            throw new IOException("Restoration evidence exceeds checkpoint bounds");
        return encoded;
    }

    /** Applies original physical phases and retains every ambiguous outcome. / 应用原物理阶段并保留每个含糊结果。
     * @param operation original identity / 原身份
     * @return verified completion or original uncertainty / 已验证完成或原未知 */
    private OperationResult<Receipt> advance(UUID operation) {
        try {
            var p = read(operation);
            if (Set.of("RESTORING", "RESTORED").contains(p.execution().phase()))
                return restoreFiles(p);
            while (true) {
                files.checkedParents(p);
                Path stage = files.stage(operation), program = stage.resolve("program"), old = stage.resolve("old"),
                    target = p.execution().program();
                switch (p.execution().phase()) {
                    case "VALIDATED" -> {
                        files.stageCandidate(p, stage, program, old, target);
                    }
                    case "STAGED" -> {
                        RepairFileScope.verified(p, program, p.checkpoint().program());
                        if (p.evidence().old().isEmpty()) {
                            RepairFileScope.absent(target);
                            RepairFileScope.absent(old);
                        } else if (exists(target)) {
                            files.equalOld(p, target);
                            RepairFileScope.absent(old);
                            WindowsFilePublish.publishDirectory(target, old);
                        }
                        files.equalOld(p, old);
                        RepairFileScope.absent(target);
                        ledger.advance(operation, "STAGED", "OLD_SAVED", encode(p.checkpoint()));
                    }
                    case "OLD_SAVED" -> {
                        files.equalOld(p, old);
                        if (exists(program)) {
                            RepairFileScope.verified(p, program, p.checkpoint().program());
                            RepairFileScope.absent(target);
                            WindowsFilePublish.publishDirectory(program, target);
                        }
                        RepairFileScope.verified(p, target, p.checkpoint().program());
                        RepairFileScope.absent(program);
                        ledger.advance(operation, "OLD_SAVED", "FILES_PUBLISHED", encode(p.checkpoint()));
                    }
                    case "FILES_PUBLISHED" -> {
                        RepairFileScope.verified(p, target, p.checkpoint().program());
                        RepairFileScope.absent(program);
                        files.equalOld(p, old);
                        ledger.registered(operation, encode(p.checkpoint()));
                    }
                    case "REGISTERED" -> {
                        RepairFileScope.verified(p, target, p.checkpoint().program());
                        RepairFileScope.absent(program);
                        files.removeOld(p, old);
                        var cp = p.checkpoint();
                        ledger.advance(operation, "REGISTERED", "CLEANUP", encode(new Checkpoint(1, cp.parent(),
                            cp.root(), cp.program(), null, null, cleanupOriginal(p))));
                    }
                    case "CLEANUP" -> {
                        RepairFileScope.verified(p, target, p.checkpoint().program());
                        RepairFileScope.absent(program);
                        RepairFileScope.absent(old);
                        files.removeStage(p);
                        ledger.finish(operation, p.checkpoint().cleanup());
                        return result(new Receipt(operation, p.execution().owner(), p.execution().version(), false,
                            p.checkpoint().cleanup()));
                    }
                    default -> throw new IOException("Unrecognized repair phase");
                }
                p = read(operation);
            }
        } catch (Exception failure) {
            try {
                ledger.unconfirmed(operation);
            } catch (SQLException retaining) {
                failure.addSuppressed(retaining);
            }
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "MOD_REINSTALL_UNCONFIRMED",
                    "Repair requires its original file scope and explicit reconciliation",
                    Map.of("operationId", operation.toString()), Optional.empty())));
        }
    }

    /** Restores only the complete original program and removes only explicitly reviewed new bytes. / 仅恢复完整原程序并仅移除明确审阅的新字节。
     * @param p original restoration / 原恢复
     * @return failed repair with restored old files / 已恢复旧文件的修复失败
     * @throws IOException if physical ownership or completion is ambiguous / 物理归属或完成含糊时
     * @throws SQLException if persistence is unconfirmed / 持久化未确认时 */
    private OperationResult<Receipt> restoreFiles(Pending p) throws IOException, SQLException {
        files.checkedParents(p);
        var e = p.execution();
        var cp = p.checkpoint();
        Path stage = files.stage(e.id()), old = stage.resolve("old"), program = stage.resolve("program"),
            target = e.program();
        if (e.phase().equals("RESTORING")) {
            String oldAt = files.oldLocation(p);
            if (!cp.discardAt().equals("NONE") && !(cp.discardAt().equals("TARGET") && oldAt.equals("TARGET"))) {
                Path discarded = files.newPath(p, cp.discardAt());
                files.remove(p, cp.discarded(), discarded);
            }
            RepairFileScope.absent(program);
            if (oldAt.equals("OLD")) {
                files.equalOld(p, old);
                RepairFileScope.absent(target);
                WindowsFilePublish.publishDirectory(old, target);
            }
            files.equalOld(p, target);
            RepairFileScope.absent(old);
            ledger.restored(e.id(), encode(cp));
            p = read(e.id());
        }
        files.equalOld(p, target);
        RepairFileScope.absent(program);
        RepairFileScope.absent(old);
        files.removeStage(p);
        ledger.finish(e.id(), "UNTOUCHED");
        return result(new Receipt(e.id(), e.owner(), e.version(), true, "UNTOUCHED"));
    }

    /** Reads strict original evidence without adopting current files as authority. / 读取严格原依据，不将当前文件接纳为权限。
     * @param operation original repair / 原修复
     * @return validated original facts / 已验证原事实
     * @throws SQLException if catalog differs / 目录不同时
     * @throws IOException if physical evidence is malformed / 物理依据畸形时 */
    private Pending read(UUID operation) throws SQLException, IOException {
        var e = ledger.execution(operation);
        try {
            var evidence = JSON.readValue(e.files(), Evidence.class);
            var cp = JSON.readValue(e.checkpoint(), Checkpoint.class);
            if (evidence.format() != 1 || cp.format() != 1)
                throw new IOException("Unknown repair evidence format");
            for (String value : List.of(evidence.home(), evidence.mod(), evidence.owner(), evidence.originalParent()))
                validIdentity(value, false);
            validIdentity(cp.parent(), true);
            validIdentity(cp.root(), true);
            validIdentity(cp.program(), true);
            if (cp.root() != null && cp.parent() == null || cp.program() != null && cp.root() == null
                || !e.phase().equals("VALIDATED") && !Set.of("RESTORING", "RESTORED").contains(e.phase())
                && cp.program() == null)
                throw new IOException("Repair directory ownership is incomplete");
            ModInstallationCleanup.validateEntry(evidence.original());
            Path original = Path.of(evidence.original().path());
            if (evidence.original().directory() || !original.isAbsolute() || !original.equals(original.normalize())
                || !original.toString().endsWith(".wtlmod") || original.startsWith(home.resolve("mod"))
                || original.startsWith(home.resolve(".mod-staging"))
                || !e.archiveHash().equals(evidence.original().sha256()))
                throw new IOException("Invalid repair source scope");
            if (!evidence.keep() && !original.getParent().equals(home.resolve("mod-zip")))
                throw new IOException("External repair source cannot be removed");
            ModInstallationCleanup.validateInstalled(evidence.old(), e.program());
            var inspection = new ModPackageReader.Inspection(original,
                ManifestReader.read(e.manifest().getBytes(StandardCharsets.UTF_8)), e.archiveHash(),
                e.contentHash(), evidence.files(), List.of(), Map.of());
            compatible(inspection);
            var p = new Pending(e, evidence, cp, inspection);
            boolean restoring = Set.of("RESTORING", "RESTORED").contains(e.phase());
            if (restoring) {
                if (!Set.of("STAGE", "TARGET", "NONE").contains(cp.discardAt()) || cp.discarded() == null
                    || evidence.old().isEmpty() || cp.discardAt().equals("NONE") && !cp.discarded().isEmpty())
                    throw new IOException("Invalid original restoration scope");
                files.allowedNewFiles(p, cp.discarded(), files.newPath(p, cp.discardAt()));
                if (!cp.discarded().isEmpty()
                    && !Objects.equals(RepairFileScope.rootIdentity(cp.discarded(), files.newPath(p, cp.discardAt())), cp.program()))
                    throw new IOException("Restoration does not own the new program");
            } else if (cp.discardAt() != null || cp.discarded() != null)
                throw new IOException("Unexpected restoration evidence");
            if (e.phase().equals("CLEANUP")) {
                if (!Set.of("RETAINED", "ABSENT", "DELETED", "RETAINED_UNCONFIRMED").contains(cp.cleanup()))
                    throw new IOException("Invalid source cleanup evidence");
            } else if (cp.cleanup() != null)
                throw new IOException("Unexpected source cleanup evidence");
            return p;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid retained repair evidence", invalid);
        }
    }

    /** Rechecks host API and Java compatibility without invoking author code or data preparation. / 不调用作者代码或数据准备地复核宿主 API 和 Java 兼容。
     * @param inspection original verified package / 原已验证包
     * @throws IOException if incompatible / 不兼容时 */
    private void compatible(ModPackageReader.Inspection inspection) throws IOException {
        if (!inspection.manifest().platformApiRange().contains(api)
            || inspection.manifest().minJavaVersion() > Runtime.version().feature())
            throw new IOException("Repair package is incompatible with the actual host");
    }

    /** Applies captured retention only to the exact managed original after successful program registration. / 程序登记成功后仅对精确受管原包应用捕获留存选择。
     * @param p original repair / 原修复
     * @return independent original cleanup observation / 独立原包清理观察 */
    private String cleanupOriginal(Pending p) {
        try {
            Path original = Path.of(p.evidence().original().path());
            if (!exists(original))
                return "ABSENT";
            if (p.evidence().keep())
                return "RETAINED";
            if (!original.getParent().equals(home.resolve("mod-zip")))
                throw new IOException("Unmanaged original source");
            identity(original.getParent(), p.evidence().originalParent());
            RepairFileScope.unchanged(p.evidence().original());
            Files.delete(original);
            return "DELETED";
        } catch (IOException failure) {
            return "RETAINED_UNCONFIRMED";
        }
    }

    /** Reads original archive ownership for the shared inventory without granting physical execution. / 为共用清单读取原归档归属，不授予物理执行权限。
     * @param request retained ledger request / 保留账本请求
     * @param owner original row owner / 原行所有者
     * @param version original row release / 原行发布
     * @return validated original archive coordinates / 已验证原归档坐标
     * @throws IOException if original ownership cannot be read / 无法读取原归属时 */
    ModInstallationCleanup.Entry original(JsonNode request, String owner, String version) throws IOException {
        try {
            if (request.path("format").asInt() != 1 || !home.toString().equals(request.path("home").textValue())
                || !owner.equals(request.path("owner").textValue())
                || !version.equals(request.path("release").path("version").textValue())
                || !home.resolve("mod").resolve(owner).resolve(version).toString()
                .equals(request.path("release").path("path").textValue()))
                throw new IOException("Original repair inventory identity differs");
            String raw = request.path("files").textValue();
            if (raw == null || raw.length() > 4 * 1024 * 1024)
                throw new IOException("Invalid repair inventory evidence");
            var evidence = JSON.readValue(raw, Evidence.class);
            var entry = evidence.original();
            ModInstallationCleanup.validateEntry(entry);
            validIdentity(evidence.originalParent(), false);
            Path path = Path.of(entry.path());
            if (evidence.format() != 1 || entry.directory() || !path.isAbsolute() || !path.equals(path.normalize())
                || !path.toString().endsWith(".wtlmod")
                || !entry.sha256().equals(request.path("archiveHash").textValue()))
                throw new IOException("Invalid repair original scope");
            return entry;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid original repair inventory", invalid);
        }
    }

    /** Reads only retained platform repair receipts without replay. / 仅读取保留平台修复凭据，不重放。
     * @param operation original identity / 原身份
     * @return original receipt or absent / 原凭据或缺省
     * @throws SQLException if reading fails / 读取失败时 */
    private Receipt receipt(UUID operation) throws SQLException {
        try (var c = database.openConnection();
             var q = c.prepareStatement(
                 "SELECT subject_mod_id,mod_version,event_code,outcome,details FROM runtime_log WHERE owner_id='app' AND category='mod_operation' AND operation_id=? AND event_code IN ('MOD_REINSTALLED','MOD_REINSTALL_RESTORED')")) {
            q.setObject(1, operation);
            try (var r = q.executeQuery()) {
                if (!r.next())
                    return null;
                boolean restored = r.getString(3).equals("MOD_REINSTALL_RESTORED");
                if (!(restored ? "FAILED" : "SUCCEEDED").equals(r.getString(4)))
                    throw new SQLException("Repair receipt outcome differs");
                String cleanup;
                try {
                    cleanup = JSON.readTree(r.getString(5)).required("originalCleanup").textValue();
                } catch (IOException | RuntimeException invalid) {
                    throw new SQLException("Invalid repair receipt", invalid);
                }
                if (cleanup == null || !Set.of("RETAINED", "ABSENT", "DELETED", "RETAINED_UNCONFIRMED", "UNTOUCHED")
                    .contains(cleanup))
                    throw new SQLException("Invalid original cleanup result");
                return new Receipt(operation, r.getString(1), r.getString(2), restored, cleanup);
            }
        }
    }

    /** Wraps actual repair completion without treating restored old files as repair success. / 包装实际修复完成，不将恢复旧文件视为修复成功。
     * @param receipt actual terminal result / 实际终态结果
     * @return exact outcome / 精确结果 */
    private static OperationResult<Receipt> result(Receipt receipt) {
        return new OperationResult<>(receipt.restored() ? TaskResult.FAILED : TaskResult.SUCCEEDED, Optional.of(receipt),
            receipt.restored()
                ? Optional.of(new Problem(ProblemCode.IO_FAILURE, "MOD_REINSTALL_RESTORED",
                "Repair failed; original program restored", Map.of(), Optional.empty()))
                : Optional.empty());
    }

    /** Checks retained native identity syntax. / 检查保留原生身份语法。
     * @param value original identity / 原身份
     * @param nullable whether not yet created / 是否尚未创建
     * @throws IOException if malformed / 畸形时 */
    private static void validIdentity(String value, boolean nullable) throws IOException {
        if (value == null ? !nullable : !value.matches("[0-9a-f]{48}"))
            throw new IOException("Invalid repair native identity");
    }

    /** Encodes bounded host physical evidence. / 编码有界宿主物理依据。
     * @param value original record / 原记录
     * @return strict JSON / 严格 JSON
     * @throws IOException if encoding or bounds fail / 编码或界限失败时 */
    private static String encode(Object value) throws IOException {
        String encoded = JSON.writeValueAsString(value);
        if (encoded.length() > 4 * 1024 * 1024)
            throw new IOException("Repair physical evidence exceeds bounds");
        return encoded;
    }

    /** Rejects EDT and closed-service calls. / 拒绝 EDT 和已关闭服务调用。 */
    private void requireOpen() {
        if (closed || SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Repair requires an open background service");
    }

    /** Waits for accepted repair calls before closing admission. / 关闭准入前等待已接纳修复调用。 */
    @Override
    public synchronized void close() {
        closed = true;
    }

    /** Immutable reviewed source and old-program scope. / 不可变已审阅来源及旧程序范围。
     * @param format evidence format / 依据格式
     * @param home original home identity / 原程序根身份
     * @param mod original mod container identity / 原 mod 容器身份
     * @param owner original owner container identity / 原所有者容器身份
     * @param original selected archive observation / 选定归档观察
     * @param originalParent original source-directory identity / 原来源目录身份
     * @param keep captured original-retention choice / 捕获原包留存选择
     * @param files original new package contents / 原新包内容
     * @param old actual original old program / 实际原旧程序 */
    private record Evidence(int format, String home, String mod, String owner, ModInstallationCleanup.Entry original,
                            String originalParent, boolean keep, List<ModPackageReader.FileEntry> files,
                            List<ModInstallationCleanup.Entry> old) {
    }

    /** Durable physical ownership and explicit restoration scope. / 持久物理归属及明确恢复范围。
     * @param format checkpoint format / 检查点格式
     * @param parent staging container identity / 暂存容器身份
     * @param root operation directory identity / 操作目录身份
     * @param program new program identity / 新程序身份
     * @param discardAt explicitly reviewed restoration location / 明确审阅恢复位置
     * @param discarded explicitly reviewed actual new files / 明确审阅实际新文件
     * @param cleanup separately observed source result after registration / 登记后单独观察的来源结果 */
    private record Checkpoint(int format, String parent, String root, String program, String discardAt,
                              List<ModInstallationCleanup.Entry> discarded, String cleanup) {
    }

    /** Validated original execution and physical evidence. / 已验证原执行及物理依据。
     * @param execution authoritative catalog stage / 权威目录阶段
     * @param evidence immutable scope / 不可变范围
     * @param checkpoint original physical ownership / 原物理归属
     * @param inspection original package declaration / 原包声明 */
    private record Pending(ModReinstallationLedger.Execution execution, Evidence evidence, Checkpoint checkpoint,
                           ModPackageReader.Inspection inspection) {
    }

    /** Original repair file identities, locations and authorized subsets. / 原修复文件身份、位置与授权子集。 */
    private final class RepairFileScope {
        /** Binds the original owning service. / 绑定原服务所有者。 */
        private RepairFileScope() {
        }

        /** Confirms immutable shared ancestors before any file action. / 任何文件动作前确认不可变共用祖先。
         * @param evidence original physical scope / 原物理范围
         * @param target original program / 原程序
         * @throws IOException if changed or redirected / 变化或重定向时 */
        private void parents(Evidence evidence, Path target) throws IOException {
            identity(home, evidence.home());
            identity(home.resolve("mod"), evidence.mod());
            identity(target.getParent(), evidence.owner());
        }

        /** Rechecks original shared and staging ancestry without creating missing directories. / 复核原共用及暂存祖先，不创建缺失目录。
         * @param p original operation / 原操作
         * @throws IOException if ownership differs / 归属不同时 */
        private void checkedParents(Pending p) throws IOException {
            files.parents(p.evidence(), p.execution().program());
            var cp = p.checkpoint();
            Path stage = files.stage(p.execution().id());
            if (cp.parent() != null)
                identity(stage.getParent(), cp.parent());
            if (exists(stage)) {
                if (cp.root() == null)
                    throw new IOException("Unclaimed repair staging directory");
                identity(stage, cp.root());
                RepairFileScope.onlyChildren(stage);
            } else if (cp.root() != null && !Set.of("CLEANUP", "RESTORED").contains(p.execution().phase()))
                throw new IOException("Claimed repair staging directory disappeared");
        }

        /** Finds complete old files at exactly one original location. / 在恰好一个原位置找到完整旧文件。
         * @param p original operation / 原操作
         * @return OLD or TARGET / 旧副本或目标
         * @throws IOException if absent, changed or duplicated / 缺失、变化或重复时 */
        private String oldLocation(Pending p) throws IOException {
            if (p.evidence().old().isEmpty())
                throw new IOException("No complete original program is available for restoration");
            Path old = files.stage(p.execution().id()).resolve("old"), target = p.execution().program();
            boolean saved = exists(old);
            if (saved) {
                files.equalOld(p, old);
                return "OLD";
            }
            files.equalOld(p, target);
            return "TARGET";
        }

        /** Locates only the claimed new directory and rejects ambiguous replacements. / 仅定位已取得新目录并拒绝含糊替代物。
         * @param p original operation / 原操作
         * @return STAGE, TARGET or NONE / 暂存、目标或无
         * @throws IOException if ownership is ambiguous / 归属含糊时 */
        private String newLocation(Pending p) throws IOException {
            Path program = files.stage(p.execution().id()).resolve("program"), target = p.execution().program();
            boolean staged = exists(program),
                atTarget = exists(target) && Objects.equals(WindowsFileIdentity.read(target), p.checkpoint().program());
            if (staged && atTarget)
                throw new IOException("New program has conflicting locations");
            if (staged) {
                identity(program, p.checkpoint().program());
                return "STAGE";
            }
            if (atTarget) {
                identity(target, p.checkpoint().program());
                return "TARGET";
            }
            if (p.checkpoint().program() != null)
                throw new IOException("Claimed new program disappeared");
            return "NONE";
        }

        /** Derives new-file locations only from fixed original coordinates. / 仅从固定原坐标推导新文件位置。
         * @param p original operation / 原操作
         * @param location confirmed location kind / 已确认位置种类
         * @return exact original path / 精确原路径 */
        private Path newPath(Pending p, String location) {
            return location.equals("TARGET") ? p.execution().program() : files.stage(p.execution().id()).resolve("program");
        }

        /** Rejects unrelated new files while allowing explicit review of partial or damaged expected files. / 拒绝无关新文件，允许明确审阅部分或损坏的预期文件。
         * @param p original package evidence / 原包依据
         * @param entries actual reviewed files / 实际已审阅文件
         * @param root original new-program root / 原新程序根
         * @throws IOException if an entry is outside the package / 条目超出包范围时 */
        private void allowedNewFiles(Pending p, List<ModInstallationCleanup.Entry> entries, Path root) throws IOException {
            ModInstallationCleanup.validateInstalled(entries, root);
            var allowed = new HashSet<String>();
            allowed.add("");
            for (var file : p.evidence().files()) {
                allowed.add(file.path());
                if (file.path().contains("/"))
                    allowed.add(file.path().substring(0, file.path().indexOf('/')));
            }
            for (var entry : entries)
                if (!allowed.contains(root.relativize(Path.of(entry.path())).toString().replace('\\', '/')))
                    throw new IOException("Unrelated new file prevents restoration");
        }

        /** Verifies exact old native identities and bytes after a directory rename. / 目录重命名后验证精确旧原生身份及字节。
         * @param p original evidence / 原依据
         * @param location current old-program location / 当前旧程序位置
         * @throws IOException if any original file changed / 任意原文件变化时 */
        private void equalOld(Pending p, Path location) throws IOException {
            if (!RepairFileScope.moved(p.evidence().old(), p.execution().program(), location)
                .equals(ModInstallationCleanup.installed(location)))
                throw new IOException("Complete original old program cannot be confirmed");
        }

        /** Rewrites only the parent coordinate while preserving every file identity and digest. / 仅改写父坐标，保留每个文件身份及摘要。
         * @param entries original scope / 原范围
         * @param from original root / 原根
         * @param to fixed renamed root / 固定重命名根
         * @return same immutable files at the renamed location / 重命名位置的同一不可变文件 */
        private static List<ModInstallationCleanup.Entry> moved(List<ModInstallationCleanup.Entry> entries, Path from,
                                                                Path to) {
            return ModInstallationCleanup.ordered(entries.stream()
                .map(e -> new ModInstallationCleanup.Entry(to.resolve(from.relativize(Path.of(e.path()))).toString(),
                    e.identity(), e.directory(), e.size(), e.sha256()))
                .toList());
        }

        /** Removes only the unchanged remaining saved-old subset after successful new registration. / 新登记成功后仅移除未变剩余旧副本子集。
         * @param p original evidence / 原依据
         * @param old fixed saved directory / 固定副本目录
         * @throws IOException if cleanup ownership is ambiguous / 清理归属含糊时 */
        private void removeOld(Pending p, Path old) throws IOException {
            files.remove(p, RepairFileScope.moved(p.evidence().old(), p.execution().program(), old), old);
        }

        /** Applies shared subset cleanup under original staging and installation ancestors. / 在原暂存及安装祖先下应用共用子集清理。
         * @param p original repair / 原修复
         * @param accepted exact authorized files / 精确授权文件
         * @param root exact removable root / 精确可移除根
         * @throws IOException if files or parents differ / 文件或父目录不同时 */
        private void remove(Pending p, List<ModInstallationCleanup.Entry> accepted, Path root) throws IOException {
            files.checkedParents(p);
            ModInstallationCleanup.validateInstalled(accepted, root);
            var parentEntries = new ArrayList<ModInstallationCleanup.Entry>();
            var parents = new LinkedHashMap<Path, String>();
            parents.put(home, p.evidence().home());
            parents.put(home.resolve("mod"), p.evidence().mod());
            parents.put(p.execution().program().getParent(), p.evidence().owner());
            parents.put(files.stage(p.execution().id()).getParent(), p.checkpoint().parent());
            parents.put(files.stage(p.execution().id()), p.checkpoint().root());
            for (var parent : parents.entrySet())
                if (parent.getValue() != null)
                    parentEntries.add(
                        new ModInstallationCleanup.Entry(parent.getKey().toString(), parent.getValue(), true, 0, null));
            ModInstallationCleanup.removeEntries(accepted, ModInstallationCleanup.installed(root), parentEntries);
            RepairFileScope.absent(root);
        }

        /** Removes the original empty operation directory only. / 仅移除原空操作目录。
         * @param p original repair / 原修复
         * @throws IOException if unowned or nonempty / 无归属或非空时 */
        private void removeStage(Pending p) throws IOException {
            files.checkedParents(p);
            Path stage = files.stage(p.execution().id());
            if (exists(stage))
                Files.delete(stage);
        }

        /** Verifies all new package bytes under the original native directory identity. / 在原原生目录身份下验证全部新包字节。
         * @param p original package / 原包
         * @param directory exact current location / 精确当前位置
         * @param expected original native identity / 原原生身份
         * @throws IOException if code, bytes or identity differs / 代码、字节或身份不同时 */
        private static void verified(Pending p, Path directory, String expected) throws IOException {
            identity(directory, expected);
            ModPackageReader.verifyDirectory(p.inspection(), directory);
            identity(directory, expected);
        }

        /** Derives the operation-owned staging root. / 推导操作所属暂存根。
         * @param id original operation / 原操作
         * @return fixed original path / 固定原路径 */
        private Path stage(UUID id) {
            return home.resolve(".mod-staging").resolve(id.toString());
        }

        /** Reads an ordinary canonical directory identity. / 读取普通规范目录身份。
         * @param path explicit directory / 明示目录
         * @return native identity / 原生身份
         * @throws IOException if invalid / 无效时 */
        private static String nativeDirectory(Path path) throws IOException {
            ordinary(path, true);
            return WindowsFileIdentity.read(path);
        }

        /** Rechecks original archive identity and bytes. / 复核原归档身份及字节。
         * @param entry original archive / 原归档
         * @throws IOException if changed / 变化时 */
        private static void unchanged(ModInstallationCleanup.Entry entry) throws IOException {
            if (!entry.equals(ModInstallationCleanup.observe(Path.of(entry.path()), entry.size())))
                throw new IOException("Reviewed archive changed");
        }

        /** Proves absence without conflating unreadability. / 证明不存在，不混同不可读取。
         * @param path exact scope / 精确范围
         * @throws IOException if present or unconfirmed / 存在或未确认时 */
        private static void absent(Path path) throws IOException {
            if (exists(path))
                throw new IOException("Original repair location remains occupied");
        }

        /** Refuses unexpected operation-root children. / 拒绝意外操作根子项。
         * @param stage original operation directory / 原操作目录
         * @throws IOException if extras exist / 存在额外项时 */
        private static void onlyChildren(Path stage) throws IOException {
            try (var entries = Files.newDirectoryStream(stage)) {
                for (var entry : entries)
                    if (!Set.of("program", "old").contains(entry.getFileName().toString()))
                        throw new IOException("Unexpected repair staging entry");
            }
        }

        /** Finds a captured root identity. / 查找捕获根身份。
         * @param entries original entries / 原条目
         * @param root exact root / 精确根
         * @return captured identity or absent / 捕获身份或缺省 */
        private static String rootIdentity(List<ModInstallationCleanup.Entry> entries, Path root) {
            return entries.stream().filter(e -> e.path().equals(root.toString()) && e.directory())
                .map(ModInstallationCleanup.Entry::identity).findFirst().orElse(null);
        }

        /** Creates and validates only the original staged candidate. / 仅创建并核验原暂存候选。
         * @param p original persisted repair / 原持久修复
         * @param stage original operation root / 原操作根
         * @param program fixed staged program / 固定暂存程序
         * @param old fixed saved old directory / 固定旧副本目录
         * @param target original installed target / 原安装目标
         * @throws IOException if identities or original bytes differ / 身份或原字节不符时
         * @throws SQLException if the original checkpoint cannot be saved / 原检查点不能保存时 */
        private void stageCandidate(Pending p, Path stage, Path program, Path old, Path target) throws IOException, SQLException {
            files.equalOld(p, target);
            RepairFileScope.absent(old);
            var cp = p.checkpoint();
            if (cp.parent() == null) {
                if (!exists(stage.getParent()))
                    Files.createDirectory(stage.getParent());
                cp = new Checkpoint(1, RepairFileScope.nativeDirectory(stage.getParent()), null, null, null, null, null);
                ledger.checkpoint(p.execution().id(), "VALIDATED", encode(cp));
            }
            identity(stage.getParent(), cp.parent());
            if (cp.root() == null) {
                Files.createDirectory(stage);
                cp = new Checkpoint(1, cp.parent(), RepairFileScope.nativeDirectory(stage), null, null, null, null);
                ledger.checkpoint(p.execution().id(), "VALIDATED", encode(cp));
            }
            identity(stage, cp.root());
            RepairFileScope.onlyChildren(stage);
            if (cp.program() == null) {
                Files.createDirectory(program);
                cp = new Checkpoint(1, cp.parent(), cp.root(), RepairFileScope.nativeDirectory(program), null, null, null);
                ledger.checkpoint(p.execution().id(), "VALIDATED", encode(cp));
            }
            identity(program, cp.program());
            try (var children = Files.newDirectoryStream(program)) {
                if (!children.iterator().hasNext()) {
                    RepairFileScope.unchanged(p.evidence().original());
                    ModPackageReader.extractIntoEmpty(p.inspection(), program);
                }
            }
            RepairFileScope.verified(p, program, cp.program());
            ledger.advance(p.execution().id(), "VALIDATED", "STAGED", encode(cp));
        }
    }
}
