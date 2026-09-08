package gold.debug.windowstolinux.shared.model.ecosystem.db;

/** Database ecosystem families supported by native provisioning. */
public enum DatabaseEngineType {
    POSTGRESQL(5432, DatabaseCategoryType.SQL), MYSQL(3306, DatabaseCategoryType.SQL), MARIADB(3306, DatabaseCategoryType.SQL), REDIS(6379, DatabaseCategoryType.OTHER);
    public enum DatabaseCategoryType { SQL, DOCUMENT, OTHER }
    private final int port;
    private final DatabaseCategoryType category;
    DatabaseEngineType(int port, DatabaseCategoryType category) { this.port = port; this.category = category; }
    public int defaultPort() { return port; }
    public DatabaseCategoryType category() { return category; }
}
