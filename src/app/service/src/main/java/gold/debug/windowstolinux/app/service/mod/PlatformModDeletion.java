package gold.debug.windowstolinux.app.service.mod;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import javax.swing.SwingUtilities;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.log.LogRepository;
import gold.debug.windowstolinux.app.db.mod.ModDeletionLedger;
import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.mod.installation.ModPackageReader;
import gold.debug.windowstolinux.app.service.log.PlatformLogService;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.windows.filesystem.WindowsFileIdentity;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.*;

/** Deletes reviewed installed releases or complete owners through original durable and physical scopes. / 经原持久及物理范围删除已审阅安装版本或完整所有者。 */
public final class PlatformModDeletion implements AutoCloseable {
    /** Original trusted application home. / 原可信程序根。 */
    private final Path home;

    /** Original physical database. / 原物理数据库。 */
    private final PlatformDatabase database;

    /** Original unlocked-operation gate. / 原解锁操作门。 */
    private final DesktopSecurityService security;

    /** Actual runtime diagnostic owner used to exclude replay during deletion. / 用于删除期间排除回填的实际运行时诊断所有者。 */
    private final PlatformLogService diagnostics;

    /** Exact durable deletion authority. / 精确持久删除权限。 */
    private final ModDeletionLedger ledger;

    /** Prevents new calls after accepted work has drained. / 已接纳工作排空后阻止新调用。 */
    private boolean closed;

    /** Strict bounded physical evidence parser. / 严格有界物理证据解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Binds deletion to the same installed root and security owner as installation. / 将删除绑定到与安装相同的安装根及安全所有者。
     * @param database original database / 原数据库
     * @param security original authorization gate / 原授权门
     * @param home installed application root / 安装应用根
     * @param diagnostics actual runtime logging owner / 实际运行时日志所有者
     * @throws SQLException if the ledger cannot be opened / 无法打开账本时 */
    PlatformModDeletion(PlatformDatabase database, DesktopSecurityService security, Path home,
            PlatformLogService diagnostics) throws SQLException {
        this.database = database;
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.security = security;
        this.home = home;
        ledger = new ModDeletionLedger(database, home);
    }

    /** One-use review including exact data impact and optional proven originals. / 包含精确数据影响及可选已证明原包的一次性审阅。 */
    public final class Review {
        /** Original service identity. / 原服务身份。 */
        private final PlatformModDeletion issuer = PlatformModDeletion.this;

        /** Original catalog review. / 原目录审阅。 */
        private final ModDeletionLedger.Review catalog;

        /** Captured physical scope. / 捕获物理范围。 */
        private final Evidence evidence;

        /** Exact selected installed identity. / 精确选定安装身份。 */
        private final String owner, version;

        /** Prevents repeat acceptance after uncertain completion. / 防止完成未知后重复接纳。 */
        private boolean used;

        /** Retains only inspected facts. / 仅保留已检查事实。
         * @param catalog original catalog / 原目录
         * @param evidence actual files / 实际文件
         * @param owner exact Mod / 精确 Mod
         * @param version exact release, null for the whole owner / 精确版本，整所有者为空 */
        private Review(ModDeletionLedger.Review catalog, Evidence evidence, String owner, String version) {
            this.catalog = catalog;
            this.evidence = evidence;
            this.owner = owner;
            this.version = version;
        }

        /** Returns the original operation for uncertain-result lookup. / 返回用于未知结果查询的原操作。
         * @return stable identity / 稳定身份 */
        public UUID id() {
            return catalog.operation();
        }

        /** Returns the exact owner. / 返回精确所有者。
         * @return original Mod ID / 原 Mod ID */
        public String owner() {
            return owner;
        }

        /** Returns the exact release or the whole-owner marker. / 返回精确版本或整所有者标记。
         * @return original release, null for the whole owner / 原版本，整所有者为空 */
        public String version() {
            return version;
        }

        /** Returns whether this review removes the complete registered owner. / 返回此审阅是否移除完整登记所有者。
         * @return whole-owner scope / 整所有者范围 */
        public boolean whole() {
            return version == null;
        }

        /** Lists every selected registered release. / 列出全部选定登记发布。
         * @return exact selected releases / 精确选定发布 */
        public List<String> versions() {
            return catalog.versions();
        }

        /** Lists auxiliary and emergency paths explicitly covered by whole-owner confirmation. / 列出整所有者确认明确覆盖的辅助及应急路径。
         * @return actual owned file and directory paths / 实际自有文件及目录路径 */
        public List<Path> ownedFiles() {
            if (evidence.owned() == null)
                return List.of();
            return java.util.stream.Stream
                    .concat(evidence.owned().auxiliary().stream(), evidence.owned().emergency().stream())
                    .map(entry -> Path.of(entry.path())).toList();
        }

        /** Returns actual auxiliary and emergency byte counts, excluding optional original packages. / 返回实际辅助及应急字节数，不含可选原包。
         * @return observed owned bytes / 观察自有字节 */
        public long ownedBytes() {
            if (evidence.owned() == null)
                return 0;
            return java.util.stream.Stream
                    .concat(evidence.owned().auxiliary().stream(), evidence.owned().emergency().stream())
                    .mapToLong(ModInstallationCleanup.Entry::size).sum();
        }

        /** Lists all generations that this exact deletion removes. / 列出此精确删除移除的全部代次。
         * @return exact original data identities / 精确原数据身份 */
        public List<SchemaIdentity> removedSchemas() {
            return catalog.removedSchemas();
        }

        /** Lists owned diagnostic categories removed by a whole-owner confirmation. / 列出整所有者确认移除的自有诊断类别。
         * @return original category names, without log contents / 原类别名称，不含日志正文 */
        public List<String> removedLogCategories() {
            return catalog.logTargets().stream().map(LogRepository.Target::category).toList();
        }

        /** Lists owner-local pinned pages removed by the same confirmation. / 列出同一确认移除的所有者内部固定页面。
         * @return original page identities / 原页面身份 */
        public List<String> removedNavigationPages() {
            return catalog.removedNavigationPages();
        }

        /** Lists owned credentials removed when no shared consumer protects them. / 列出无共享使用方保护时移除的自有凭据。
         * @return identities without any secret payload / 不含任何秘密载荷的身份 */
        public List<String> removedSecrets() {
            return catalog.removedSecrets();
        }

        /** Lists owned credentials retained for shared consumers. / 列出为共享使用方保留的自有凭据。
         * @return retained identities / 保留身份 */
        public List<String> retainedSecrets() {
            return catalog.retainedSecrets();
        }

        /** Lists actual program files and directories that confirmation authorizes. / 列出确认所授权的实际程序文件及目录。
         * @return immutable reviewed paths / 不可变已审阅路径 */
        public List<Path> programs() {
            return evidence.program().stream().map(e -> Path.of(e.path())).toList();
        }

        /** Lists originals matching the exact owner and, for a single release, its version. / 列出匹配精确所有者的原包，单版本删除还须匹配版本。
         * @return immutable optional original paths / 不可变可选原包路径 */
        public List<Path> originals() {
            return evidence.originals().stream().map(e -> Path.of(e.path())).toList();
        }

        /** Returns reviewed program bytes. / 返回已审阅程序字节数。
         * @return total actual program bytes / 实际程序总字节 */
        public long bytes() {
            return evidence.program().stream().mapToLong(ModInstallationCleanup.Entry::size).sum();
        }

        /** Returns data that will actually be deleted. / 返回实际将删除的数据。
         * @return original last-reference generation / 原最后引用代次 */
        public Optional<SchemaIdentity> removedSchema() {
            return catalog.removedSchema();
        }

        /** Lists retained releases protecting the shared data. / 列出保护共享数据的保留版本。
         * @return exact release identities / 精确版本身份 */
        public List<String> retainedSchemaVersions() {
            return catalog.retainedSchemaVersions();
        }
    }

    /** Verified completion independent of current catalog rows. / 独立于当前目录行的已核验完成。
     * @param operationId original operation / 原操作
     * @param owner original Mod / 原 Mod
     * @param version deleted release, null for the complete owner / 已删版本，完整所有者为空 */
    public record Receipt(UUID operationId, String owner, String version) {
    }

    /** Reviews the complete disabled owner, including all installed releases and private data paths. / 审阅完整已禁用所有者，包含全部安装发布及私有数据路径。
     * @param owner exact registered Mod identity / 精确登记 Mod 身份
     * @return exact one-use whole-owner review / 精确一次性整所有者审阅
     * @throws IOException if physical ownership or range is unconfirmed / 物理归属或范围未确认时
     * @throws SQLException if original deletion admission is unavailable / 原删除准入不可用时 */
    public synchronized Review review(String owner) throws IOException, SQLException {
        return review(owner, null);
    }

    /** Captures actual installed contents and valid matching originals without mutation or code execution. / 捕获实际安装内容及有效匹配原包，不修改或执行代码。
     * @param owner exact installed owner / 精确安装所有者
     * @param version exact installed release, null for whole-owner deletion / 精确安装版本，整所有者删除为空
     * @return one-use scope review / 一次性范围审阅
     * @throws IOException if file ownership or range is uncertain / 文件归属或范围不确定时
     * @throws SQLException if deletion admission is unavailable / 删除准入不可用时 */
    public synchronized Review review(String owner, String version) throws IOException, SQLException {
        requireOpen();
        try (var permission = security.enterOperation(); var logs = diagnostics.sealDeletion(owner)) {
            var catalog = ledger.review(owner, version);
            var program = version == null
                    ? ModOwnedCleanup.programs(catalog.program(), catalog.versions())
                    : ModInstallationCleanup.installed(catalog.program());
            var owned = version == null ? ModOwnedCleanup.inspect(database.root(), owner, catalog.logTargets()) : null;
            List<ModInstallationCleanup.Entry> originals;
            String originalsId;
            try {
                originals = originals(owner, version);
                originalsId = originals.isEmpty() ? null : nativeId(home.resolve("mod-zip"));
            } catch (IOException unavailable) {
                // An unproven optional archive scope cannot authorize cleanup or prevent program deletion. / 未证明的可选原包范围不能授权清理，也不阻止程序删除。
                originals = List.of();
                originalsId = null;
            }
            var evidence = new Evidence(2, nativeId(home), nativeId(home.resolve("mod")),
                    nativeId(version == null ? catalog.program() : catalog.program().getParent()), originalsId, program,
                    originals, owned);
            validate(evidence, catalog.program(), owner, version, catalog.versions(), catalog.logTargets());
            parents(evidence, catalog.program());
            return new Review(catalog, evidence, owner, version);
        }
    }

    /** Rechecks and persists confirmed files before removing the first byte. / 移除首个字节前复核并持久保存确认文件。
     * @param review original single-use review / 原一次性审阅
     * @param deleteOriginals explicit opt-in, false by default in the UI / 明确选择连带原包，界面默认 false
     * @return confirmed completion or retained uncertainty / 已确认完成或保留不确定性
     * @throws IOException if reviewed files changed before admission / 准入前审阅文件变化时
     * @throws SQLException if admission cannot be confirmed / 无法确认准入时 */
    public synchronized OperationResult<Receipt> delete(Review review, boolean deleteOriginals)
            throws IOException, SQLException {
        requireOpen();
        if (review == null || review.issuer != this || review.used)
            throw new IllegalArgumentException("Deletion requires an unused original review");
        try (var permission = security.enterOperation(); var logs = diagnostics.sealDeletion(review.owner())) {
            var original = review.evidence;
            var accepted = deleteOriginals
                    ? original
                    : new Evidence(original.format(), original.homeId(), original.modId(), original.ownerId(), null,
                            original.program(), List.of(), original.owned());
            parents(accepted, review.catalog.program());
            var currentPrograms = review.whole()
                    ? ModOwnedCleanup.programs(review.catalog.program(), review.versions())
                    : ModInstallationCleanup.installed(review.catalog.program());
            if (!accepted.program().equals(currentPrograms))
                throw new IOException("Installed files changed after review");
            if (review.whole() && !accepted.owned()
                    .equals(ModOwnedCleanup.inspect(database.root(), review.owner(), review.catalog.logTargets())))
                throw new IOException("Owned files changed after review");
            for (var file : accepted.originals())
                unchanged(file);
            String json = JSON.writeValueAsString(accepted);
            review.used = true;
            try {
                ledger.begin(review.catalog, json);
            } catch (SQLException failure) {
                try {
                    ledger.unconfirmed(review.id());
                } catch (SQLException retaining) {
                    failure.addSuppressed(retaining);
                }
                throw failure;
            }
            return advance(review.id());
        }
    }

    /** Explicitly continues only the original accepted file subset and durable checkpoint. / 明确仅继续原接纳文件子集及持久检查点。
     * @param operation original identity / 原身份
     * @return original verified completion or uncertainty / 原已核验完成或不确定性
     * @throws SQLException if original execution or receipt is unavailable / 原执行或凭据不可用时
     * @throws IOException if physical evidence is malformed / 物理证据畸形时 */
    public synchronized OperationResult<Receipt> reconcile(UUID operation) throws SQLException, IOException {
        requireOpen();
        try (var permission = security.enterOperation()) {
            var complete = receipt(operation);
            if (complete != null)
                return success(complete);
            var original = ledger.execution(operation);
            try (var logs = diagnostics.sealDeletion(original.owner())) {
                decode(original.files(), original.program(), original.owner(), original.version(), original.versions(),
                        original.targets());
                ledger.resume(operation);
                return advance(operation);
            }
        }
    }

    /** Advances original phases while preserving all failures for explicit reconciliation. / 推进原阶段，将全部失败保留供明确核对。
     * @param operation original operation / 原操作
     * @return actual completion or safe unknown outcome / 实际完成或安全未知结果 */
    private OperationResult<Receipt> advance(UUID operation) {
        try {
            var execution = ledger.execution(operation);
            var evidence = decode(execution.files(), execution.program(), execution.owner(), execution.version(),
                    execution.versions(), execution.targets());
            parents(evidence, execution.program());
            switch (execution.phase()) {
                case "QUIESCENT" :
                    if (execution.version() == null) {
                        ModOwnedCleanup.validatePrograms(evidence.program(), execution.program(), execution.versions());
                        var parentEntries = new ArrayList<ModInstallationCleanup.Entry>();
                        parentEntries.add(
                                new ModInstallationCleanup.Entry(home.toString(), evidence.homeId(), true, 0, null));
                        if (evidence.modId() != null)
                            parentEntries.add(new ModInstallationCleanup.Entry(home.resolve("mod").toString(),
                                    evidence.modId(), true, 0, null));
                        ModInstallationCleanup.removeEntries(evidence.program(),
                                ModOwnedCleanup.programs(execution.program(), execution.versions()), parentEntries);
                    } else
                        ModInstallationCleanup.removeInstalled(evidence.program(), execution.program());
                    ledger.programRemoved(operation);
                case "PROGRAM_REMOVED" :
                    ledger.removeData(operation);
                case "DATA_REMOVED" :
                    if (evidence.owned() != null)
                        ModOwnedCleanup.remove(evidence.owned(), database.root(), execution.owner(),
                                execution.targets());
                    ledger.removeReferences(operation);
                case "REFERENCES_REMOVED" :
                    ledger.removeRegistry(operation);
                case "REGISTRY_REMOVED" :
                    parents(evidence, execution.program());
                    for (var entry : evidence.originals()) {
                        Path path = Path.of(entry.path());
                        if (!PlatformModInstallation.exists(path))
                            continue;
                        parents(evidence, execution.program());
                        unchanged(entry);
                        Files.delete(path);
                    }
                    ledger.cleanup(operation);
                case "CLEANUP" :
                    for (var entry : evidence.originals())
                        if (PlatformModInstallation.exists(Path.of(entry.path())))
                            throw new IOException("Accepted original cleanup remains unconfirmed");
                    if (!ledger.finish(operation) && receipt(operation) == null)
                        throw new SQLException("Original deletion completion cannot be confirmed");
                    return success(new Receipt(operation, execution.owner(), execution.version()));
                default :
                    throw new SQLException("Unknown deletion phase");
            }
        } catch (Exception failure) {
            try {
                ledger.unconfirmed(operation);
            } catch (SQLException retaining) {
                failure.addSuppressed(retaining);
            }
            return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNKNOWN, "MOD_DELETE_UNCONFIRMED",
                            "Mod cleanup requires its original operation and file scope",
                            Map.of("operationId", operation.toString()), Optional.empty())));
        }
    }

    /** Reads only a retained fixed archive; never recreates expired receipts or operations. / 仅读取保留固定归档，从不重建过期凭据或操作。
     * @param operation original identity / 原身份
     * @return original receipt, or null / 原凭据或 null
     * @throws SQLException if reading fails / 读取失败时 */
    private Receipt receipt(UUID operation) throws SQLException {
        try (var c = database.openConnection();
                var p = c.prepareStatement(
                        "SELECT subject_mod_id,mod_version FROM runtime_log WHERE owner_id='app' AND category='mod_operation' AND event_code IN ('MOD_VERSION_DELETED','MOD_DELETED') AND outcome='SUCCEEDED' AND operation_id=?")) {
            p.setObject(1, operation);
            try (var r = p.executeQuery()) {
                return r.next() ? new Receipt(operation, r.getString(1), r.getString(2)) : null;
            }
        }
    }

    /** Finds readable exact-ID/version originals without guessing ownership from their names. / 查找可读且精确 ID/版本匹配的原包，不从名称猜归属。
     * @param owner installed identity / 安装身份
     * @param version installed release, null for all owner originals / 安装版本，所有者全部原包为空
     * @return immutable proven original observations / 不可变已证明原包观察
     * @throws IOException if the source directory or matched scope exceeds bounds / 来源目录或匹配范围超出界限时 */
    private List<ModInstallationCleanup.Entry> originals(String owner, String version) throws IOException {
        Path directory = home.resolve("mod-zip");
        if (!PlatformModInstallation.exists(directory))
            return List.of();
        PlatformModInstallation.ordinary(directory, true);
        var results = new ArrayList<ModInstallationCleanup.Entry>();
        try (var entries = Files.newDirectoryStream(directory, "*.wtlmod")) {
            int scanned = 0;
            for (Path path : entries) {
                if (++scanned > ModPackageReader.MAX_ENTRIES)
                    throw new IOException("Original inventory exceeds review bounds");
                try {
                    var entry = ModInstallationCleanup.observe(path, ModPackageReader.MAX_BYTES);
                    var source = ModPackageReader.read(path);
                    if (!source.manifest().id().equals(owner)
                            || version != null && !source.manifest().version().toString().equals(version))
                        continue;
                    unchanged(entry);
                    results.add(entry);
                } catch (IOException unreadable) {
                    // Unproven originals are retained for independent manual management. / 未证明原包保留供独立手动管理。
                }
                if (results.size() > 128)
                    throw new IOException("Matching originals exceed review bounds");
            }
        }
        return results.stream().sorted(Comparator.comparing(ModInstallationCleanup.Entry::path)).toList();
    }

    /** Rechecks a file's exact native identity and bytes. / 复核文件精确原生身份及字节。
     * @param expected original observation / 原观察
     * @throws IOException if unavailable or changed / 不可用或变化时 */
    private static void unchanged(ModInstallationCleanup.Entry expected) throws IOException {
        if (!expected.equals(ModInstallationCleanup.observe(Path.of(expected.path()), expected.size())))
            throw new IOException("Reviewed file changed");
    }

    /** Captures an ordinary directory identity, allowing genuinely absent optional parents. / 捕获普通目录身份，允许可选父目录确实不存在。
     * @param path explicit directory / 明示目录
     * @return native identity, or null / 原生身份或 null
     * @throws IOException if redirected or inaccessible / 重定向或不可访问时 */
    private static String nativeId(Path path) throws IOException {
        if (!PlatformModInstallation.exists(path))
            return null;
        PlatformModInstallation.ordinary(path, true);
        return WindowsFileIdentity.read(path);
    }

    /** Rechecks fixed ancestor identities without deleting any shared parent. / 复核固定祖先身份，不删除共享父目录。
     * @param evidence original range / 原范围
     * @param program original installed directory / 原安装目录
     * @throws IOException if redirected or replaced / 重定向或替代时 */
    private void parents(Evidence evidence, Path program) throws IOException {
        PlatformModInstallation.identity(home, evidence.homeId());
        if (evidence.owned() == null || !evidence.program().isEmpty())
            parent(home.resolve("mod"), evidence.modId());
        parent(evidence.owned() == null ? program.getParent() : program, evidence.ownerId());
        if (evidence.owned() != null)
            ModOwnedCleanup.parents(evidence.owned());
        if (!evidence.originals().isEmpty())
            parent(home.resolve("mod-zip"), evidence.originalsId());
    }

    /** Allows an already absent parent but never a replacement at the same spelling. / 允许父目录已不存在，但不允许同名替代物。
     * @param path original parent / 原父目录
     * @param identity original native identity / 原原生身份
     * @throws IOException if a present parent differs / 存在的父目录不同时 */
    private static void parent(Path path, String identity) throws IOException {
        if (PlatformModInstallation.exists(path))
            PlatformModInstallation.identity(path, identity);
    }

    /** Decodes only bounded original evidence and validates all paths before any physical action. / 仅解码有界原证据，在物理动作前校验全部路径。
     * @param json original evidence / 原证据
     * @param program registered directory / 登记目录
     * @param owner original owner / 原所有者
     * @param version original release, null for whole owner / 原发布，整所有者为空
     * @param versions original registered versions / 原登记版本
     * @param targets original registered diagnostic targets / 原登记诊断目标
     * @return validated physical range / 已验证物理范围
     * @throws IOException if malformed or out of scope / 畸形或越界时 */
    private Evidence decode(String json, Path program, String owner, String version, List<String> versions,
            List<LogRepository.Target> targets) throws IOException {
        try {
            if (json == null || json.length() > 4 * 1024 * 1024)
                throw new IOException("Oversized physical deletion evidence");
            var node = JSON.readTree(json);
            Evidence evidence;
            if (node != null && node.path("format").isInt() && node.path("format").intValue() == 1) {
                var original = JSON.treeToValue(node, OriginalEvidence.class);
                evidence = new Evidence(2, original.homeId(), original.modId(), original.ownerId(),
                        original.originalsId(), original.program(), original.originals(), null);
            } else
                evidence = JSON.treeToValue(node, Evidence.class);
            validate(evidence, program, owner, version, versions, targets);
            return evidence;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IOException("Invalid physical deletion evidence", invalid);
        }
    }

    /** Validates original coordinates, cardinality and native identity framing. / 校验原坐标、数量及原生身份格式。
     * @param evidence original scope / 原范围
     * @param program registered root / 登记根
     * @param owner original owner / 原所有者
     * @param version original release, null for whole owner / 原发布，整所有者为空
     * @param versions original registered versions / 原登记版本
     * @param targets original registered diagnostic targets / 原登记诊断目标
     * @throws IOException if original evidence is invalid / 原证据无效时 */
    private void validate(Evidence evidence, Path program, String owner, String version, List<String> versions,
            List<LogRepository.Target> targets) throws IOException {
        if (evidence == null || evidence.format() != 2 || evidence.homeId() == null || evidence.originals() == null
                || evidence.originals().size() > 128)
            throw new IOException("Invalid deletion scope");
        for (String identity : Arrays.asList(evidence.homeId(), evidence.modId(), evidence.ownerId(),
                evidence.originalsId()))
            if (identity != null && !identity.matches("[0-9a-f]{48}"))
                throw new IOException("Invalid deletion ancestor");
        if ((evidence.owned() != null) != (version == null))
            throw new IOException("Deletion scope type differs");
        if (version == null) {
            ModOwnedCleanup.validatePrograms(evidence.program(), program, versions);
            ModOwnedCleanup.validate(evidence.owned(), database.root(), owner, targets);
        } else
            ModInstallationCleanup.validateInstalled(evidence.program(), program);
        if (!evidence.program().isEmpty() && (evidence.modId() == null || evidence.ownerId() == null)
                || !evidence.originals().isEmpty() && evidence.originalsId() == null)
            throw new IOException("Missing deletion ancestor");
        var paths = new HashSet<String>();
        for (var entry : evidence.originals()) {
            ModInstallationCleanup.validateEntry(entry);
            Path path = Path.of(entry.path());
            if (entry.directory() || !path.isAbsolute() || !path.equals(path.normalize())
                    || !home.resolve("mod-zip").equals(path.getParent())
                    || !path.getFileName().toString().endsWith(".wtlmod") || !paths.add(entry.path()))
                throw new IOException("Invalid accepted original path");
        }
    }

    /** Protects originals retained by accepted deletions, including unreadable ownership evidence. / 保护已接纳删除保留的原包，包括不可读归属证据。
     * @param c serialized platform transaction / 串行平台事务
     * @param path original being considered / 被考虑原包
     * @param identity current native identity, if available / 当前原生身份，如可用
     * @return whether another cleanup owns it / 是否归其他清理持有
     * @throws SQLException if operations cannot be read / 无法读取操作时 */
    boolean originalBusy(Connection c, Path path, String identity) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT mod_id,source_version,request_json FROM mod_operation WHERE operation_type IN ('DELETE_VERSION','DELETE_MOD') AND state<>'ENDED'");
                var r = p.executeQuery()) {
            while (r.next()) {
                try {
                    String json = r.getString(3);
                    if (json.length() > 8 * 1024 * 1024)
                        return true;
                    var node = JSON.readTree(json);
                    if (!home.toString().equals(node.path("home").textValue()))
                        return true;
                    String owner = r.getString(1), version = r.getString(2);
                    if (!owner.equals(node.path("owner").textValue())
                            || !Objects.equals(version, node.path("version").textValue()))
                        return true;
                    var versions = new ArrayList<String>();
                    if (!node.path("versions").isArray() || node.path("versions").size() > 1024)
                        return true;
                    for (var release : node.path("versions")) {
                        if (!release.path("version").isTextual())
                            return true;
                        versions.add(release.path("version").textValue());
                    }
                    List<LogRepository.Target> targets = List.of();
                    if (version == null) {
                        if (!node.path("targets").isArray() || node.path("targets").size() > 4096)
                            return true;
                        targets = JSON.readerForListOf(LogRepository.Target.class).readValue(node.path("targets"));
                    }
                    Path program = home.resolve("mod").resolve(owner);
                    if (version != null)
                        program = program.resolve(version);
                    var evidence = decode(node.path("files").textValue(), program, owner, version, versions, targets);
                    for (var entry : evidence.originals())
                        if (entry.path().equals(path.toString()) || entry.identity().equals(identity))
                            return true;
                } catch (IOException | RuntimeException invalid) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Wraps only verified completion. / 仅包装已核验完成。
     * @param receipt original receipt / 原凭据
     * @return success / 成功 */
    private static OperationResult<Receipt> success(Receipt receipt) {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(receipt), Optional.empty());
    }

    /** Rejects EDT and closed-service calls. / 拒绝 EDT 及已关闭服务调用。 */
    private void requireOpen() {
        if (closed || SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Deletion requires an open background service");
    }

    /** Waits for accepted cleanup before closing admission. / 等待已接纳清理后关闭准入。 */
    @Override
    public synchronized void close() {
        closed = true;
    }

    /** Previously persisted version-only physical scope, interpreted without rewriting its request. / 先前持久单版本物理范围，不重写请求地解释。
     * @param format original evidence version / 原证据版本
     * @param homeId original home identity / 原程序根身份
     * @param modId original installation parent identity / 原安装父目录身份
     * @param ownerId original owner directory identity / 原所有者目录身份
     * @param originalsId original archive directory identity / 原原包目录身份
     * @param program original program evidence / 原程序证据
     * @param originals originally selected archives / 原选定归档 */
    private record OriginalEvidence(int format, String homeId, String modId, String ownerId, String originalsId,
            List<ModInstallationCleanup.Entry> program, List<ModInstallationCleanup.Entry> originals) {
    }

    /** Original immutable physical scope stored within the deletion request. / 存于删除请求中的原不可变物理范围。
     * @param format evidence version / 证据版本
     * @param homeId original home identity / 原程序根身份
     * @param modId original installation parent identity / 原安装父目录身份
     * @param ownerId original owner-directory identity / 原所有者目录身份
     * @param originalsId original archive-directory identity / 原原包目录身份
     * @param program original program observations / 原程序观察
     * @param originals explicitly accepted originals / 明确接纳原包
     * @param owned exact whole-owner private scope, absent for a version-only request / 精确整所有者私有范围，单版本请求为空 */
    private record Evidence(int format, String homeId, String modId, String ownerId, String originalsId,
            List<ModInstallationCleanup.Entry> program, List<ModInstallationCleanup.Entry> originals,
            ModOwnedCleanup.Scope owned) {
    }
}
