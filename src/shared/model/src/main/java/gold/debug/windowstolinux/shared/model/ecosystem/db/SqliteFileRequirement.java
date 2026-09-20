package gold.debug.windowstolinux.shared.model.ecosystem.db;

import gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation;
import java.util.Objects;

/** File location and application input for embedded SQLite. / 嵌入式 SQLite 的文件位置与应用接入声明。 */
public record SqliteFileRequirement(ManagedStorageLocation location, String fileName, String accessPath,
                                    String pathEnvironment, String seedFile, boolean hostLocationExplicit) {
    public SqliteFileRequirement(ManagedStorageLocation location,String fileName,String accessPath,String pathEnvironment,String seedFile) {
        this(location,fileName,accessPath,pathEnvironment,seedFile,false);
    }
    public SqliteFileRequirement {
        Objects.requireNonNull(location);
        if (!Objects.requireNonNull(fileName).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("SQLite requires a plain database file name");
        accessPath = Objects.requireNonNull(accessPath);
        if (!accessPath.isEmpty()) accessPath = ManagedStorageLocation.validatedPath(accessPath);
        if (!Objects.requireNonNull(pathEnvironment).isEmpty() && !pathEnvironment.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("invalid SQLite path environment variable");
        seedFile = relativeSourceFile(seedFile);
    }

    public static SqliteFileRequirement fromPath(String path, String environment, String seed) {
        if (path == null || path.equals("DEFAULT"))
            return new SqliteFileRequirement(ManagedStorageLocation.defaults(), "application.db", "", environment, seed);
        if (path.isBlank() || path.contains("$") || path.contains("{{"))
            return new SqliteFileRequirement(ManagedStorageLocation.unresolved(), "application.db", "", environment, seed);
        if (path.equals(":memory:") || path.startsWith("file:"))
            throw new IllegalArgumentException("in-memory databases and SQLite URI options are not persistent file declarations");
        path = ManagedStorageLocation.validatedPath(path);
        int separator = path.lastIndexOf('/');
        String file = path.substring(separator + 1);
        String directory = path.startsWith("/") ? path.substring(0, separator) : path;
        return new SqliteFileRequirement(ManagedStorageLocation.custom(directory), file, path, environment, seed);
    }

    public static String relativeSourceFile(String value) {
        if (Objects.requireNonNull(value).isEmpty()) return value;
        value = ManagedStorageLocation.validatedPath(value);
        if (value.startsWith("/")) throw new IllegalArgumentException("seed and initialization files must belong to the reviewed source");
        return value;
    }
}
