package gold.debug.windowstolinux.app.service.mod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.sql.*;
import java.util.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.app.mod.development.*;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.appapi.task.*;

/** Registers verified CLASS builds without enabling them or changing an existing selection. / 登记已核验 CLASS 构建，不启用它们或改变已有单选。 */
public final class PlatformModCatalog {
    /** Original physical engine boot. / 原物理引擎启动身份。 */
    private final UUID boot;

    /** Platform transactions serialized with all task admission. / 与全部任务准入串行的平台事务。 */
    private final RepositoryConnections connections;

    /** Binds development registration to one actual database boot. / 将开发登记绑定到一次实际数据库启动。
     * @param database owned platform database / 所属平台数据库 */
    public PlatformModCatalog(PlatformDatabase database) {
        boot = database.bootId();
        connections = new RepositoryConnections(database::openConnection);
    }

    /** Current verified build registered for later selection and loading. / 已登记且供后续选择及加载的当前核验构建。
     * @param candidate exact discovered source / 精确发现来源
     * @param contentDigest digest of actual compiled content and build evidence / 实际编译内容及构建证据摘要 */
    public record Build(DevelopmentModDiscovery.Candidate candidate, String contentDigest) {
    }

    /** Independent registration results, without implying startup success. / 独立登记结果，不暗示启动成功。
     * @param builds admitted current builds / 已准入当前构建
     * @param rejections refused sources and safe reasons / 被拒来源及安全原因 */
    public record Result(List<Build> builds, List<DevelopmentModDiscovery.Rejection> rejections) {
        /** Copies results before handing them to the lifecycle coordinator. / 向生命周期协调器交付前复制结果。
         * @param builds admitted builds / 已准入构建
         * @param rejections refused sources / 被拒来源 */
        public Result {
            builds = List.copyOf(builds);
            rejections = List.copyOf(rejections);
        }
    }

    /** Scans a validated workspace and registers independent builds atomically per source. / 扫描已验证工作区，逐来源原子登记独立构建。
     * @param workspace explicit canonical development root / 明示规范开发根
     * @return current registered builds and retained source failures / 当前登记构建及保留来源失败
     * @throws IOException if workspace discovery itself is unavailable / 工作区发现本身不可用时 */
    public Result refresh(Path workspace) throws IOException {
        requireWorker();
        var discovered = DevelopmentModDiscovery.scan(workspace);
        var builds = new ArrayList<Build>();
        var rejected = new ArrayList<>(discovered.rejections());
        for (var candidate : discovered.candidates()) {
            try {
                var build = new Build(candidate, fingerprint(candidate));
                verify(workspace, build);
                register(build);
                builds.add(build);
            } catch (IOException | SQLException failure) {
                rejected.add(new DevelopmentModDiscovery.Rejection(candidate.module(), problem(failure, candidate)));
            }
        }
        return new Result(builds, rejected);
    }

    /** Revalidates both build provenance and actual code immediately before a later load. / 在后续加载前重新核验构建来源及实际代码。
     * @param workspace original verified root / 原已验证根
     * @param build previously registered build / 之前登记的构建
     * @throws IOException if metadata, output or compiled content changed / 元数据、输出或编译内容变化时 */
    public static void verify(Path workspace, Build build) throws IOException {
        requireWorker();
        var before = build.candidate();
        var current = DevelopmentDescriptor.read(workspace, before.module().resolve("target/wtlmod-development.json"));
        if (!current.equals(before.descriptor()) || !current.manifest().json().equals(before.manifest().json())
                || !fingerprint(before).equals(build.contentDigest()))
            throw new IOException("The registered CLASS build changed before loading");
    }

    /** Publishes only complete registration facts in the original platform transaction. / 仅在原平台事务内发布完整登记事实。
     * @param build verified compiled content / 已核验编译内容
     * @throws SQLException if ownership, retained work or persistence prevents registration / 归属、保留工作或持久化阻止登记时 */
    private void register(Build build) throws SQLException {
        connections.transaction(() -> {
            try (var c = connections.open()) {
                RepositoryConnections.lockPlatformState(c);
                try (var s = c.createStatement();
                        var r = s.executeQuery("SELECT boot_id,shutdown_state FROM app_state WHERE singleton_id=1")) {
                    if (!r.next() || !boot.equals(r.getObject(1, UUID.class))
                            || !Set.of("STARTING", "RUNNING").contains(r.getString(2)))
                        throw rejected(ProblemCode.UNAVAILABLE, "MOD_CATALOG_BOOT_CHANGED",
                                "Development registration belongs to another or closed boot");
                }
                var candidate = build.candidate();
                var manifest = candidate.manifest();
                String owner = manifest.id(), version = manifest.version().toString();
                long now = System.currentTimeMillis();
                ensureRegistry(c, owner, now);
                reconcileBuild(c, build, owner, version, now);
                initializeSelection(c, owner, now);
            }
        });
    }

    /** Refuses to replace evidence used by actual activation, pending decisions or retained work. / 拒绝替换实际启用、待执行决定或保留工作使用的证据。
     * @param c original platform transaction / 原平台事务
     * @param owner exact Mod identity / 精确 Mod 身份
     * @throws SQLException if replacement is unsafe / 替换不安全时 */
    private static void requireReplaceable(Connection c, String owner) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM mod_registry WHERE mod_id=? AND (actual_version IS NOT NULL OR last_runtime_state NOT IN ('STOPPED','FAILED')))+(SELECT COUNT(*) FROM mod_operation WHERE mod_id=? AND state<>'ENDED')+(SELECT COUNT(*) FROM platform_task WHERE owner_id=? AND state<>'ENDED')+(SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND (owner_id=? OR resource_kind='MOD' AND resource_id=?))")) {
            for (int i = 1; i <= 5; i++)
                p.setString(i, owner);
            try (var r = p.executeQuery()) {
                r.next();
                if (r.getLong(1) != 0)
                    throw rejected(ProblemCode.BUSY, "MOD_CATALOG_BUILD_IN_USE",
                            "Original development evidence is still in use");
            }
        }
    }

    /** Hashes actual output bytes, not merely the input marker copied by compilation. / 对实际输出字节求摘要，而非只检查编译复制的输入标记。
     * @param candidate validated source and ordered outputs / 已校验来源及有序输出
     * @return canonical content SHA-256 / 规范内容 SHA-256
     * @throws IOException if an output is redirected, unreadable or changes during hashing / 输出重定向、不可读或求摘要期间变化时 */
    private static String fingerprint(DevelopmentModDiscovery.Candidate candidate) throws IOException {
        var files = new TreeMap<String, Path>((a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)));
        add(files, candidate.descriptor().manifestPath());
        add(files, candidate.module().resolve("target/wtlmod-development.json"));
        for (Path source : candidate.descriptor().sources()) {
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    /** Rejects redirected descendants before visiting their contents. / 访问内容前拒绝重定向后代。
                     * @param directory current output directory / 当前输出目录
                     * @param attrs observed attributes / 已观察属性
                     * @return continue traversal / 继续遍历
                     * @throws IOException if directory is redirected / 目录重定向时 */
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                            throws IOException {
                        if (!directory.toRealPath().equals(directory))
                            throw new IOException("Compiled directory is redirected");
                        return FileVisitResult.CONTINUE;
                    }

                    /** Registers regular output bytes, including processed resources. / 登记普通输出字节，包括处理后资源。
                     * @param file compiled file / 编译文件
                     * @param attrs observed attributes / 已观察属性
                     * @return continue traversal / 继续遍历
                     * @throws IOException if file is not an owned regular file / 文件不是归属内普通文件时 */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        add(files, file);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                add(files, source);
            }
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("WTL-CLASS-CONTENT-1\n".getBytes(StandardCharsets.UTF_8));
            for (var entry : files.entrySet()) {
                var before = Files.readAttributes(entry.getValue(), BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                var content = MessageDigest.getInstance("SHA-256");
                long length = 0;
                try (var input = Files.newInputStream(entry.getValue())) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = input.read(buffer)) != -1;) {
                        content.update(buffer, 0, n);
                        length += n;
                    }
                }
                var after = Files.readAttributes(entry.getValue(), BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (length != before.size() || before.size() != after.size()
                        || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || !Objects.equals(before.fileKey(), after.fileKey()))
                    throw new IOException("Compiled file changed during inspection");
                byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(name.length).array());
                digest.update(name);
                digest.update(ByteBuffer.allocate(8).putLong(length).array());
                digest.update(content.digest());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 is unavailable", unavailable);
        }
    }

    /** Adds one canonical regular output without following links. / 添加一个规范普通输出，不跟随链接。
     * @param files ordered content entries / 有序内容条目
     * @param file exact output / 精确输出
     * @throws IOException if redirected or irregular / 重定向或不是普通文件时 */
    private static void add(Map<String, Path> files, Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file))
            throw new IOException("Compiled content must be an original regular file");
        files.put(file.toString().replace('\\', '/'), file);
    }

    /** Carries a known rejection through the original rollback boundary. / 通过原回滚边界携带已知拒绝。
     * @param code safe failure category / 安全失败类别
     * @param event stable event / 稳定事件
     * @param message constant public explanation / 常量公开说明
     * @return internal transaction signal / 内部事务信号 */
    private static SQLException rejected(ProblemCode code, String event, String message) {
        return new SQLException(message,
                new ApiException(new Problem(code, event, message, Map.of(), Optional.empty())));
    }

    /** Preserves known refusals but never interprets uncertain storage as a proven rollback. / 保留已知拒绝，绝不把不确定存储当成已证明回滚。
     * @param failure original internal failure / 原内部失败
     * @param candidate affected source / 受影响来源
     * @return source-specific safe evidence / 来源专属安全证据 */
    private static Problem problem(Exception failure, DevelopmentModDiscovery.Candidate candidate) {
        if (failure instanceof SQLException && failure.getSuppressed().length == 0
                && failure.getCause() instanceof ApiException api)
            return api.problem();
        return new Problem(failure instanceof SQLException ? ProblemCode.UNKNOWN : ProblemCode.UNAVAILABLE,
                "MOD_DEVELOPMENT_REGISTRATION_UNCONFIRMED",
                "Development registration could not be confirmed; reload the saved state before another attempt",
                Map.of("ownerId", candidate.manifest().id(), "source", candidate.module().toString()),
                Optional.of(UUID.randomUUID()));
    }

    /** Keeps traversal, hashing and registration away from Swing input. / 使遍历、摘要及登记远离 Swing 输入线程。 */
    private static void requireWorker() {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Development catalog requires a worker thread");
    }

    /** Creates only the missing original owner row. / 仅创建缺失的原所有者行。
     * @param c original locked transaction / 原已锁事务
     * @param owner original Mod identity / 原Mod身份
     * @param now original registration timestamp / 原登记时间戳
     * @throws SQLException if original registration cannot be verified / 原登记不能核实时 */
    private static void ensureRegistry(Connection c, String owner, long now) throws SQLException {
        boolean fresh;
        try (var p = c.prepareStatement("SELECT COUNT(*) FROM mod_registry WHERE mod_id=?")) {
            p.setString(1, owner);
            try (var r = p.executeQuery()) {
                r.next();
                fresh = r.getLong(1) == 0;
            }
        }
        if (fresh) {
            try (var p = c.prepareStatement("INSERT INTO mod_registry(mod_id,updated_at) VALUES(?,?)")) {
                p.setString(1, owner);
                p.setLong(2, now);
                p.executeUpdate();
            }
        }
    }

    /** Reconciles only this exact compiled release inside the original transaction. / 在原事务中仅核对此精确编译发布。
     * @param c original locked transaction / 原已锁事务
     * @param build exact compiled evidence / 精确编译依据
     * @param owner original Mod identity / 原Mod身份
     * @param version original release / 原发布
     * @param now original registration timestamp / 原登记时间戳
     * @throws SQLException if original registration cannot be verified / 原登记不能核实时 */
    private static void reconcileBuild(Connection c, Build build, String owner, String version, long now) throws SQLException {
        var candidate = build.candidate();
        var manifest = candidate.manifest();
        boolean exists = false, identical = false;
        try (var p = c.prepareStatement(
                "SELECT install_path,content_sha256,schema_version,manifest_json,origin_kind,state FROM mod_version WHERE mod_id=? AND version=?")) {
            p.setString(1, owner);
            p.setString(2, version);
            try (var r = p.executeQuery()) {
                if (r.next()) {
                    exists = true;
                    if (!"CLASS".equals(r.getString(5)) || !"READY".equals(r.getString(6)))
                        throw rejected(ProblemCode.CONTRACT_VIOLATION, "MOD_CATALOG_SOURCE_CONFLICT",
                                "Existing release belongs to another source or maintenance");
                    identical = candidate.module().toString().equals(r.getString(1))
                            && build.contentDigest().equals(r.getString(2))
                            && manifest.schemaVersion().equals(Optional.ofNullable(r.getString(3)))
                            && manifest.json().equals(r.getString(4));
                }
            }
        }
        if (!identical) {
            requireReplaceable(c, owner);
            if (exists) {
                try (var p = c.prepareStatement(
                        "UPDATE mod_version SET content_sha256=?,schema_version=?,manifest_json=?,installed_at=?,install_path=? WHERE mod_id=? AND version=?")) {
                    p.setString(1, build.contentDigest());
                    p.setString(2, manifest.schemaVersion().orElse(null));
                    p.setString(3, manifest.json());
                    p.setLong(4, now);
                    p.setString(5, candidate.module().toString());
                    p.setString(6, owner);
                    p.setString(7, version);
                    if (p.executeUpdate() != 1)
                        throw new SQLException("Original development registration changed");
                }
            } else {
                try (var p = c.prepareStatement(
                        "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,schema_version,manifest_json,origin_kind,state,installed_at) VALUES(?,?,?,?,?,?,'CLASS','READY',?)")) {
                    p.setString(1, owner);
                    p.setString(2, version);
                    p.setString(3, candidate.module().toString());
                    p.setString(4, build.contentDigest());
                    p.setString(5, manifest.schemaVersion().orElse(null));
                    p.setString(6, manifest.json());
                    p.setLong(7, now);
                    p.executeUpdate();
                }
            }
        }
    }

    /** Initializes only a previously untouched selection from actual ready releases. / 仅从实际就绪发布初始化未触碰的选择。
     * @param c original locked transaction / 原已锁事务
     * @param owner original Mod identity / 原Mod身份
     * @param now original registration timestamp / 原登记时间戳
     * @throws SQLException if original registration cannot be verified / 原登记不能核实时 */
    private static void initializeSelection(Connection c, String owner, long now) throws SQLException {
        var versions = new ArrayList<SemanticVersion>();
        try (var p = c.prepareStatement("SELECT version FROM mod_version WHERE mod_id=? AND state='READY'")) {
            p.setString(1, owner);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    try {
                        versions.add(SemanticVersion.parse(r.getString(1)));
                    } catch (IllegalArgumentException invalid) {
                        throw rejected(ProblemCode.INVALID_INPUT, "MOD_CATALOG_VERSION_INVALID",
                                "Stored release identity is invalid");
                    }
                }
            }
        }
        String initial = Collections
                .max(versions,
                        Comparator.<SemanticVersion>naturalOrder().thenComparing(SemanticVersion::toString))
                .toString();
        try (var p = c.prepareStatement(
                "UPDATE mod_registry SET selected_version=?,selection_initialized=TRUE,updated_at=? WHERE mod_id=? AND selection_initialized=FALSE AND selected_version IS NULL AND enabled=FALSE AND revision=0")) {
            p.setString(1, initial);
            p.setLong(2, now);
            p.setString(3, owner);
            p.executeUpdate();
        }
    }
}
