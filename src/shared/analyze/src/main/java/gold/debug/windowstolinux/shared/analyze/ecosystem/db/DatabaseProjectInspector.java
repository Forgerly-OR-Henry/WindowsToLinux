package gold.debug.windowstolinux.shared.analyze.ecosystem.db;

import gold.debug.windowstolinux.shared.model.ecosystem.db.*;
import java.nio.file.*;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;

/** Reads explicit DB declarations and conventional Spring endpoints without retaining credentials. */
public final class DatabaseProjectInspector {
    private static final Pattern JDBC = Pattern.compile("jdbc:(postgresql|mysql|mariadb)://([^/\\s]+)/(\\$\\{[^}]+}|[A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE);
    public record Assessment(List<DatabaseRequirement> databases, List<String> sqlCandidates, boolean schemaReviewRequired, boolean unknownDatabase,
                             boolean endpointConfirmationRequired) {
        public Assessment { databases = List.copyOf(databases); sqlCandidates = List.copyOf(sqlCandidates); }
        public Assessment(List<DatabaseRequirement> databases, List<String> sqlCandidates, boolean schemaReviewRequired, boolean unknownDatabase) {
            this(databases,sqlCandidates,schemaReviewRequired,unknownDatabase,false);
        }
    }
    public Assessment inspect(Path root) throws IOException {
        Path declaration = root.resolve("windowstolinux-db.properties");
        List<DatabaseRequirement> databases = new ArrayList<>(); List<String> sql = new ArrayList<>();
        StringBuilder scanned = new StringBuilder();
        boolean endpointConfirmation = false;
        try (var files = Files.walk(root, 12)) {
            var paths = files.limit(100_001).toList();
            if (paths.size() > 100_000) throw new IOException("database source inspection exceeds limits");
            for (Path file : paths.stream().sorted().toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("database inspection refuses symbolic links");
                if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) continue;
                Path relative = root.relativize(file);
                if (java.util.stream.StreamSupport.stream(relative.spliterator(),false).anyMatch(part ->
                        Set.of("node_modules","target","build","dist",".git",".venv","vendor","test","tests","docs","examples").contains(part.toString()))) continue;
                String name = file.getFileName().toString();
                if (name.endsWith(".sql") && sql.size() < 64) sql.add(relative.toString().replace('\\','/'));
                if (name.matches(".*\\.(?:properties|ya?ml|json|toml|xml|py|js|ts|sql)")) {
                    if (Files.size(file) > 2 * 1024 * 1024 || scanned.length()+Files.size(file) > 16 * 1024 * 1024)
                        throw new IOException("database declaration inspection exceeds text limits");
                    scanned.append(Files.readString(file)).append('\n');
                }
            }
        }
        String text = scanned.toString();
        boolean schema = new gold.debug.windowstolinux.shared.analyze.contract.policy.SourceMutationPolicy().requiresReview(
                new gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts(0,sql.stream().map(Path::of).toList(),text));
        if (Files.isRegularFile(declaration)) {
            if (Files.size(declaration) > 65536) throw new IOException("database declaration exceeds limits");
            Properties properties = new Properties(); properties.load(new StringReader(Files.readString(declaration)));
            for (String id : properties.getProperty("db", "").split(",")) {
                id = id.trim(); if (id.isEmpty()) continue;
                String prefix = "db."+id+".";
                DatabaseEngineType engine = DatabaseEngineType.valueOf(properties.getProperty(prefix+"engine", "").toUpperCase(Locale.ROOT));
                databases.add(new DatabaseRequirement(id,engine,properties.getProperty(prefix+"version", defaultVersion(engine)),
                        properties.getProperty(prefix+"database", ""),properties.getProperty(prefix+"username", ""),
                        properties.getProperty(prefix+"environmentPrefix", ""),properties.getProperty(prefix+"passwordEnvironment", ""),
                        Arrays.stream(properties.getProperty(prefix+"initialize", "").split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList(),
                        Boolean.parseBoolean(properties.getProperty(prefix+"springDatasource", "false")),"windowstolinux-db.properties#"+prefix));
            }
            if (databases.isEmpty() || databases.size() > 16 || databases.stream().map(DatabaseRequirement::id).distinct().count() != databases.size())
                throw new IOException("database declarations must be nonempty, unique and bounded");
        } else {
            var jdbc = JDBC.matcher(text); Set<String> observed = new LinkedHashSet<>();
            while (jdbc.find()) {
                String family = jdbc.group(1).toUpperCase(Locale.ROOT), endpoint = jdbc.group(2), database = jdbc.group(3);
                if (!observed.add(family+":"+endpoint+"/"+database)) continue;
                // External or templated hosts need an explicit DB binding; native installation is never redirected to them.
                if (!endpoint.matches("(?:localhost|127\\.0\\.0\\.1)(?::[0-9]+)?")) endpointConfirmation = true;
                DatabaseEngineType engine = DatabaseEngineType.valueOf(family);
                String name = database.matches("[a-z][a-z0-9_]{0,62}") ? database : "";
                boolean spring = text.contains("spring.datasource") || text.contains("spring-boot");
                List<String> initialization = spring && Pattern.compile("(?:spring\\.sql\\.init\\.mode|sql:\\s+init:\\s+mode)\\s*[=:]\\s*always").matcher(text).find()
                        ? sql.stream().filter(path -> Set.of("schema.sql","data.sql").contains(Path.of(path).getFileName().toString()))
                                .sorted(java.util.Comparator.comparingInt(path -> path.endsWith("data.sql") ? 1 : 0)).toList() : List.of();
                if (initialization.stream().map(path -> Path.of(path).getFileName()).distinct().count() != initialization.size()) initialization = List.of();
                var username = Pattern.compile("spring\\.datasource\\.username\\s*[=:]\\s*([a-z][a-z0-9_]{0,62})\\s*(?:\\n|$)").matcher(text);
                databases.add(new DatabaseRequirement("sql"+(databases.size()+1),engine,"",name,username.find() ? username.group(1) : "",spring ? "SPRING_DATASOURCE" : "",
                        spring ? "SPRING_DATASOURCE_PASSWORD" : "",initialization,spring,"JDBC endpoint declaration"));
            }
            if (Pattern.compile("(?:spring\\.(?:data\\.)?redis\\.|redis://|rediss://)").matcher(text).find()) {
                // URI credentials are never kept; a URI or unrecognized host requires the user's explicit target decision.
                if (text.contains("redis://") || text.contains("rediss://")) endpointConfirmation = true;
                var host = Pattern.compile("spring\\.(?:data\\.)?redis\\.host\\s*[=:]\\s*([^\\s]+)").matcher(text);
                if (host.find() && !Set.of("localhost","127.0.0.1").contains(host.group(1))) endpointConfirmation = true;
                String prefix = text.contains("spring.data.redis.") ? "SPRING_DATA_REDIS" : text.contains("spring.redis.") ? "SPRING_REDIS" : "";
                databases.add(new DatabaseRequirement("redis",DatabaseEngineType.REDIS,defaultVersion(DatabaseEngineType.REDIS),"","",prefix,
                        prefix.isEmpty() ? "" : prefix+"_PASSWORD",List.of(),false,"Redis endpoint declaration"));
            }
        }
        return new Assessment(databases,sql,schema,databases.isEmpty() && schema,endpointConfirmation);
    }
    private static String defaultVersion(DatabaseEngineType engine) { return engine == DatabaseEngineType.REDIS ? ">=6.2" : ""; }
}
