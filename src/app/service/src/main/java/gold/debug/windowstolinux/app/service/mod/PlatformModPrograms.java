package gold.debug.windowstolinux.app.service.mod;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.mod.install.ModPackageReader;
import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.mod.config.ModManifest;
import gold.debug.windowstolinux.appapi.task.Problem;

/** Resolves exact registered code for the common lifecycle without mixing source modes. / 为共用生命周期解析精确登记代码，不混用来源模式。 */
public final class PlatformModPrograms {
    /** Original database owner. / 原数据库所有者。 */
    private final PlatformDatabase database;

    /** Explicit workspace or installed application home. / 明示工作区或安装程序根。 */
    private final Path root;

    /** Whether this graph uses current CLASS outputs. / 此图是否使用当前 CLASS 输出。 */
    private final boolean development;

    /** Current admitted development builds, never inferred from old database paths. / 当前准入开发构建，绝不从旧数据库路径推断。 */
    private final Map<String, PlatformModCatalog.Build> builds = new HashMap<>();

    /** Binds one source mode to the original runtime. / 将一个来源模式绑定到原运行时。
     * @param database original platform storage / 原平台存储
     * @param root explicit program root / 明示程序根
     * @param development whether CLASS discovery is required / 是否需要 CLASS 发现 */
    public PlatformModPrograms(PlatformDatabase database, Path root, boolean development) {
        this.database = Objects.requireNonNull(database);
        this.root = Objects.requireNonNull(root);
        this.development = development;
    }

    /** Exact verified program shared by startup and schema maintenance. / 启动及数据维护共用的精确已验证程序。
     * @param manifest original strict metadata / 原严格元数据
     * @param sources ordinary code paths in loading order / 按加载顺序的普通代码路径
     * @param contentDigest registered complete content hash / 登记完整内容摘要 */
    public record Program(ModManifest manifest, List<Path> sources, String contentDigest) {
        /** Freezes verified source order. / 冻结已验证来源顺序。
         * @param manifest original metadata / 原元数据
         * @param sources ordered sources / 有序来源
         * @param contentDigest original digest / 原摘要 */
        public Program {
            sources = List.copyOf(sources);
        }
    }

    /** Refreshes only CLASS registrations; installed files are never automatically adopted. / 仅刷新 CLASS 登记，绝不自动接管安装文件。
     * @return independent discovery failures / 独立发现失败
     * @throws IOException if discovery is unavailable / 发现不可用时 */
    public List<Problem> refresh() throws IOException {
        requireWorker();
        if (!development)
            return List.of();
        var result = new PlatformModCatalog(database).refresh(root);
        builds.clear();
        result.builds().forEach(b -> builds.put(b.candidate().manifest().id(), b));
        return result.rejections().stream().map(r -> r.problem()).toList();
    }

    /** Reads only the exact ready registration and validates actual program bytes. / 仅读取精确就绪登记并核验实际程序字节。
     * @param owner requested owner / 请求所有者
     * @param version exact requested release / 精确请求发布
     * @return admitted current program / 已准入当前程序
     * @throws IOException if source or registration differs / 来源或登记不同步时
     * @throws SQLException if registry cannot be read / 无法读取登记时 */
    public Program read(String owner, String version) throws IOException, SQLException {
        requireWorker();
        if (development) {
            var build = builds.get(owner);
            if (build == null || !build.candidate().manifest().version().toString().equals(version))
                throw new IOException("Selected CLASS build is unavailable");
            PlatformModCatalog.verify(root, build);
            return new Program(build.candidate().manifest(), build.candidate().descriptor().sources(),
                build.contentDigest());
        }
        try (var c = database.openConnection();
             var p = c.prepareStatement(
                 "SELECT install_path,manifest_json,content_sha256,schema_version,origin_kind,state FROM mod_version WHERE mod_id=? AND version=?")) {
            p.setString(1, owner);
            p.setString(2, version);
            try (var r = p.executeQuery()) {
                if (!r.next() || !"READY".equals(r.getString(6))
                    || !Set.of("LOCAL", "BUNDLED", "STORE").contains(r.getString(5)))
                    throw new IOException("Selected installed release is unavailable");
                var manifest = ManifestReader.read(r.getString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!manifest.id().equals(owner) || !manifest.version().toString().equals(version)
                    || !manifest.schemaVersion().equals(Optional.ofNullable(r.getString(4))))
                    throw new IOException("Registered release identity is inconsistent");
                Path program = root.resolve("mod").resolve(owner).resolve(version);
                if (!program.toString().equals(r.getString(1)))
                    throw new IOException("Registered program path differs from the explicit application home");
                String digest = r.getString(3);
                return new Program(manifest, ModPackageReader.installedSources(program, manifest, digest), digest);
            }
        }
    }

    /** Rechecks original metadata and bytes immediately before allocating a loader. / 分配加载器前立即复核原元数据及字节。
     * @param original original verified program / 原已验证程序
     * @throws IOException if the original program changed / 原程序变化时
     * @throws SQLException if registration cannot be read / 无法读取登记时 */
    public void verify(Program original) throws IOException, SQLException {
        var current = read(original.manifest().id(), original.manifest().version().toString());
        if (!original.manifest().json().equals(current.manifest().json())
            || !original.sources().equals(current.sources())
            || !original.contentDigest().equals(current.contentDigest()))
            throw new IOException("Original program changed before loading");
    }

    /** Keeps code traversal and database access off the event thread. / 使代码遍历和数据库访问离开事件线程。 */
    private static void requireWorker() {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Program verification requires a worker thread");
    }
}
