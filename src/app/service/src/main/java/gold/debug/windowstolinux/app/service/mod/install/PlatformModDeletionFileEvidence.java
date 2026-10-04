package gold.debug.windowstolinux.app.service.mod.install;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.Objects;

import gold.debug.windowstolinux.app.db.mod.ModDeletionLedger;

/** Verifies original deletion targets without deleting files or owning database resources. / 核验原删除目标，不删除文件，不持有数据库资源。 */
public final class PlatformModDeletionFileEvidence implements ModDeletionLedger.FileEvidence {
    /** Exact original application root. / 精确原程序根。 */
    private final Path home;

    /** Original installed program root. / 原安装程序根。 */
    private final Path programs;

    /** Original owner-private data root. / 原所有者私有数据根。 */
    private final Path privateData;

    /** Original emergency-file root. / 原应急文件根。 */
    private final Path emergency;

    /** Binds the verifier to the original installation and database locations. / 将核验器绑定到原安装和数据库位置。
     * @param home trusted normalized application root / 可信规范化程序根
     * @param dataRoot trusted normalized database data root / 可信规范化数据库数据根
     * @throws SQLException if either root is not absolute and normalized / 任一根不是绝对规范化路径时 */
    public PlatformModDeletionFileEvidence(Path home, Path dataRoot) throws SQLException {
        this.home = Objects.requireNonNull(home);
        Objects.requireNonNull(dataRoot);
        require(home.isAbsolute() && home.normalize().equals(home)
                && dataRoot.isAbsolute() && dataRoot.normalize().equals(dataRoot),
            "Deletion roots must be absolute and normalized");
        programs = home.resolve("mod");
        privateData = dataRoot.resolve("mod");
        emergency = dataRoot.resolve("log").resolve("emergency");
    }

    /** Verifies the same original home before the ledger registers its operation category. / 账本登记操作类别前核验同一原程序根。
     * @param home exact original home / 精确原程序根
     * @throws SQLException if the root differs or its ancestors cannot be verified / 根不同或其祖先无法核验时 */
    @Override
    public void verifyHome(Path home) throws SQLException {
        require(this.home.equals(home), "Deletion verifier belongs to a different application home");
        ordinaryAncestors(home);
    }

    /** Proves an original program, owner-private root or emergency file is absent. / 证明原程序、所有者私有根或应急文件不存在。
     * @param path original bounded target derived by the ledger / 账本推导的原有界目标
     * @throws SQLException if outside scope, present, redirected or unobservable / 范围外、存在、重定向或无法观察时 */
    @Override
    public void verifyAbsent(Path path) throws SQLException {
        require(path != null && path.isAbsolute() && path.normalize().equals(path),
            "Deletion evidence requires an absolute normalized target");
        int depth = path.getNameCount() - programs.getNameCount();
        boolean program = path.startsWith(programs) && (depth == 1 || depth == 2);
        boolean owned = privateData.equals(path.getParent());
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        boolean diagnostic = emergency.equals(path.getParent())
            && (name.endsWith(".log") || name.endsWith(".pending"));
        require(program || owned || diagnostic, "Deletion evidence target is outside its original scope");
        ordinaryAncestors(path.getParent());
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            throw new SQLException("Original deletion target still exists");
        } catch (NoSuchFileException absent) {
            // Absence proves only this exact checkpoint. / 不存在仅证明当前精确检查点。
        } catch (IOException failure) {
            throw new SQLException("Deletion target absence cannot be verified", failure);
        }
    }

    /** Checks every existing ancestor without resolving links into another tree. / 检查每个已存在祖先，不将链接解析到其他目录树。
     * @param path original absolute ancestor path / 原绝对祖先路径
     * @throws SQLException if an ancestor is redirected or unobservable / 祖先重定向或无法观察时 */
    private static void ordinaryAncestors(Path path) throws SQLException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            try {
                var attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                require(attrs.isDirectory() && !attrs.isSymbolicLink() && !attrs.isOther(),
                    "Installed ancestor is not an ordinary directory");
            } catch (NoSuchFileException absent) {
                return;
            } catch (IOException failure) {
                throw new SQLException("Installed ancestor cannot be verified", failure);
            }
        }
    }

    /** Rejects unverified physical facts without treating them as absence. / 拒绝未核验物理事实，不将其视为不存在。
     * @param accepted verified condition / 已核验条件
     * @param message fixed non-secret reason / 固定非秘密原因
     * @throws SQLException if the condition is not verified / 条件未核验时 */
    private static void require(boolean accepted, String message) throws SQLException {
        if (!accepted)
            throw new SQLException(message);
    }
}
