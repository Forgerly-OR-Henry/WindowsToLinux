package gold.debug.windowstolinux.web.db.runtime;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Resolves storage relative to the database module's actual classes or external JAR location.
 * <p>相对于数据库模块实际类文件或外部 JAR 位置解析存储。
 *
 * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
 * @param mode selected operating or storage mode / 所选运行或存储模式
 */
public record WebStorageLocation(Path root, Mode mode) {
    /**
     * Selects whether Web storage paths are resolved for class-directory or packaged execution.
     * <p>选择按类目录或打包执行方式解析 Web 存储路径。
     */
    public enum Mode {
    /**
     * CLASS classification within mode.
     * <p>模式中的类分类。
     */
     CLASS,
    /**
     * JAR classification within mode.
     * <p>模式中的JAR分类。
     */
     JAR }
    /**
     * DATABASE NAME.
     * <p>数据库名称。
     */
    public static final String DATABASE_NAME = "windowstolinuxweb.db";

    /**
     * Resolves and validates the configured Web data locations against the actual module or external JAR installation.
     * <p>根据实际模块或外部 JAR 安装位置解析并校验所配置 Web 数据位置。
     *
     * @param configuredRoot configured root / 已配置根目录
     * @return and validates the configured Web data locations against the actual module or external JAR installation / 根据实际模块或外部 JAR 安装位置解析并校验所配置 Web 数据位置
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static WebStorageLocation resolve(String configuredRoot) throws IOException {
        try {
            return resolve(configuredRoot, WebStorageLocation.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException | NullPointerException failure) {
            throw new IOException("Cannot identify the Web DB module code source", failure);
        }
    }

    /**
     * Resolves and validates the configured Web data locations against the actual module or external JAR installation.
     * <p>根据实际模块或外部 JAR 安装位置解析并校验所配置 Web 数据位置。
     *
     * @param configuredRoot configured root / 已配置根目录
     * @param codeSource code source / 代码源码
     * @return and validates the configured Web data locations against the actual module or external JAR installation / 根据实际模块或外部 JAR 安装位置解析并校验所配置 Web 数据位置
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    static WebStorageLocation resolve(String configuredRoot, URI codeSource) throws IOException {
        if (!"file".equals(codeSource.getScheme())) throw new IOException("Web DB must load from classes or an external JAR");
        Path source = Path.of(codeSource).toAbsolutePath().normalize();
        Mode mode;
        Path base;
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && source.toString().endsWith(".jar")) {
            mode = Mode.JAR;
            base = source.getParent();
        } else if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            mode = Mode.CLASS;
            base = moduleRoot(source);
        } else throw new IOException("Unrecognized Web DB code source: " + source);
        if (configuredRoot == null || configuredRoot.isBlank()) throw new IOException("w2l.storage.root is required");
        Path configured = Path.of(configuredRoot);
        if (configured.isAbsolute()) return new WebStorageLocation(configured.normalize(), mode);
        if (!configuredRoot.equals("db.data") && !configuredRoot.startsWith("db.data/"))
            throw new IOException("w2l.storage.root must be db.data, db.data/subdirectory, or an absolute path");
        Path data = base.resolve("data");
        Path root = configuredRoot.equals("db.data") ? data : data.resolve(configuredRoot.substring(8));
        root = root.normalize();
        if (!root.startsWith(data) || configuredRoot.indexOf('\\') >= 0)
            throw new IOException("The logical data location must stay beneath db.data");
        return new WebStorageLocation(root, mode);
    }

    /**
     * Finds the enclosing Web database Maven module by its fixed artifact identifier.
     * <p>通过固定制品标识查找外层 Web 数据库 Maven 模块。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return the enclosing Web database Maven module by its fixed artifact identifier / 通过固定制品标识查找外层 Web 数据库 Maven 模块
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static Path moduleRoot(Path source) throws IOException {
        for (Path candidate = source; candidate != null; candidate = candidate.getParent()) {
            Path pom = candidate.resolve("pom.xml");
            if (Files.isRegularFile(pom) && Files.readString(pom).contains("<artifactId>windowstolinux-web-db</artifactId>"))
                return candidate;
        }
        throw new IOException("Cannot identify the Web DB module root from its classes: " + source);
    }

    /**
     * Returns reviewed database identity or database operation boundary.
     * <p>返回已审阅数据库身份或数据库操作边界。
     *
     * @return reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     */
    public Path database() { return root.resolve(DATABASE_NAME); }
    /**
     * Returns controlled filesystem access or reviewed file inventory.
     * <p>返回受控文件系统访问或已审阅文件清单。
     *
     * @return controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     */
    public Path files() { return root.resolve("files"); }
    /**
     * Returns backups.
     * <p>返回备份集合。
     *
     * @return backups / 备份集合
     */
    public Path backups() { return root.resolve("backups"); }
    /**
     * Returns the SQLite JDBC connection URL.
     * <p>返回SQLite JDBC 连接 URL。
     *
     * @return the SQLite JDBC connection URL / SQLite JDBC 连接 URL
     */
    public String jdbcUrl() { return "jdbc:sqlite:" + database(); }
}
