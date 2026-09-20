package gold.debug.windowstolinux.shared.model.ecosystem.db;

import gold.debug.windowstolinux.shared.model.ecosystem.db.sql.DatabaseVersionRequirement;
import java.util.*;

/** Non-secret database declarations extracted from a component or completed by its user. / 从组件提取或由用户补齐的非秘密数据库声明。 */
public record DatabaseRequirement(String id, DatabaseEngineType engine, String version, String database, String username,
                                  String environmentPrefix, String passwordEnvironment, List<String> initializationFiles,
                                  boolean springDatasource, String evidence, Optional<SqliteFileRequirement> sqlite) {
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
    public DatabaseRequirement(String id, DatabaseEngineType engine, String version, String database, String username,
            String environmentPrefix, String passwordEnvironment, List<String> initializationFiles, boolean springDatasource, String evidence) {
        this(id,engine,version,database,username,environmentPrefix,passwordEnvironment,initializationFiles,springDatasource,evidence,
                engine == DatabaseEngineType.SQLITE ? Optional.of(SqliteFileRequirement.fromPath("", "", "")) : Optional.empty());
    }
    private static String name(String value) {
        if (value == null || !value.isEmpty() && !value.matches("[a-z][a-z0-9_]{0,62}")) throw new IllegalArgumentException("invalid database or user name");
        return value;
    }
    private static String environment(String value) {
        if (value == null || !value.isEmpty() && !value.matches("[A-Z][A-Z0-9_]{0,39}")) throw new IllegalArgumentException("invalid database environment key");
        return value;
    }
}
