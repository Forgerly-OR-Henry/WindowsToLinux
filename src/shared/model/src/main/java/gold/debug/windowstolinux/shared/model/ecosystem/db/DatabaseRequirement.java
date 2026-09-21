package gold.debug.windowstolinux.shared.model.ecosystem.db;

import gold.debug.windowstolinux.shared.model.ecosystem.db.sql.DatabaseVersionRequirement;
import java.util.*;

/**
 * Non-secret database declarations extracted from a component or completed by its user. / 从组件提取或由用户补齐的非秘密数据库声明。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param engine engine / 引擎
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
 * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
 * @param environmentPrefix environment prefix / 环境前缀
 * @param passwordEnvironment password environment / 密码环境
 * @param initializationFiles initialization files / 初始化文件集合
 * @param springDatasource spring datasource / Spring数据源
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 * @param sqlite sqlite / SQLite 数据库
 */
public record DatabaseRequirement(String id, DatabaseEngineType engine, String version, String database, String username,
                                  String environmentPrefix, String passwordEnvironment, List<String> initializationFiles,
                                  boolean springDatasource, String evidence, Optional<SqliteFileRequirement> sqlite) {
    /**
     * Validates and binds the inputs required by database requirement.
     * <p>校验并绑定数据库要求所需输入。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param engine engine / 引擎
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param environmentPrefix environment prefix / 环境前缀
     * @param passwordEnvironment password environment / 密码环境
     * @param initializationFiles initialization files / 初始化文件集合
     * @param springDatasource spring datasource / Spring数据源
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @param sqlite sqlite / SQLite 数据库
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DatabaseRequirement {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,31}")) throw new IllegalArgumentException("invalid database requirement identifier");
        Objects.requireNonNull(engine); new DatabaseVersionRequirement(version);
        sqlite = Objects.requireNonNull(sqlite);
        if ((engine == DatabaseEngineType.SQLITE) != sqlite.isPresent()) throw new IllegalArgumentException("SQLite requires a file declaration");
        database = name(database); username = name(username);
        environmentPrefix = environment(environmentPrefix); passwordEnvironment = environment(passwordEnvironment);
        if (!passwordEnvironment.isEmpty() && !passwordEnvironment.endsWith("_PASSWORD") && !passwordEnvironment.endsWith("_PASSWORD_FILE"))
            throw new IllegalArgumentException("DB password input must name a password environment variable");
        initializationFiles = List.copyOf(initializationFiles); Objects.requireNonNull(evidence);
        if (engine == DatabaseEngineType.REDIS && (springDatasource || !initializationFiles.isEmpty()))
            throw new IllegalArgumentException("Redis cannot declare SQL initialization or a JDBC datasource");
        if (initializationFiles.size() > 32 || evidence.length() > 512) throw new IllegalArgumentException("database declaration exceeds limits");
        for (String path : initializationFiles) {
            if (!path.matches("[a-zA-Z0-9_./-]{1,255}") || path.startsWith("/") || Arrays.asList(path.split("/")).contains("..") || !path.endsWith(".sql"))
                throw new IllegalArgumentException("initialization must reference a bounded relative SQL file");
        }
    }
    /**
     * Initializes database requirement through its shared constructor contract.
     * <p>通过共享构造契约初始化数据库要求。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param engine engine / 引擎
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param environmentPrefix environment prefix / 环境前缀
     * @param passwordEnvironment password environment / 密码环境
     * @param initializationFiles initialization files / 初始化文件集合
     * @param springDatasource spring datasource / Spring数据源
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    public DatabaseRequirement(String id, DatabaseEngineType engine, String version, String database, String username,
            String environmentPrefix, String passwordEnvironment, List<String> initializationFiles, boolean springDatasource, String evidence) {
        this(id,engine,version,database,username,environmentPrefix,passwordEnvironment,initializationFiles,springDatasource,evidence,
                engine == DatabaseEngineType.SQLITE ? Optional.of(SqliteFileRequirement.fromPath("", "", "")) : Optional.empty());
    }
    /**
     * Checks name syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查名称语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return name text / 名称文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String name(String value) {
        if (value == null || !value.isEmpty() && !value.matches("[a-z][a-z0-9_]{0,62}")) throw new IllegalArgumentException("invalid database or user name");
        return value;
    }
    /**
     * Checks environment syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查环境语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return environment text / 环境文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String environment(String value) {
        if (value == null || !value.isEmpty() && !value.matches("[A-Z][A-Z0-9_]{0,39}")) throw new IllegalArgumentException("invalid database environment key");
        return value;
    }
}
