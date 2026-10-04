package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseEngineType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSqliteRequirement;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentStorageLocation.StorageLocationType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentStorageLocation.StorageResourceType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseRequirement;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectChecker;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentReviewField;

/** Completes source-derived DB declarations without connecting to a server or granting schema approval. / 补全源码派生的数据库声明，不连接服务器也不授予结构批准。 */
public final class DeploymentDatabaseSourceInputs {
    /** Prevents construction of the source input helper. / 禁止构造源码输入辅助类。 */
    private DeploymentDatabaseSourceInputs() {
    }

    /** Returns the next bounded non-secret questions from the current source facts. / 根据当前源码事实返回下一组有界非秘密问题。
     * @param assessment original source assessment / 原源码评估
     * @param applicationId reviewed component identity / 经审阅组件身份
     * @param supplied previously supplied non-secret answers / 已提供的非秘密答案
     * @return fields to display before any database effect / 任何数据库效果前应显示的字段 */
    public static List<DeploymentReviewField> questions(DatabaseProjectChecker.Report assessment,
                                                        String applicationId, Map<String, String> supplied) {
        return questions(assessment, applicationId, supplied, !supplied.isEmpty());
    }

    /** Reopens editable source values after an invalid declaration even when no first answer existed. / 即使初次无答案，也在声明无效后重新开放源码值供更正。
     * @param assessment source facts / 源码事实
     * @param applicationId managed identity / 受管身份
     * @param supplied previous answers / 先前答案
     * @param correction whether every declaration field must be editable / 是否允许更正全部声明字段
     * @return bounded non-secret questions / 有界非秘密问题 */
    public static List<DeploymentReviewField> questions(DatabaseProjectChecker.Report assessment,
                                                        String applicationId, Map<String, String> supplied, boolean correction) {
        var fields = new ArrayList<DeploymentReviewField>();
        if (assessment.unknownDatabase() && !supplied.containsKey("database.main.engine")) {
            fields.add(field("main", "engine", "",
                Arrays.stream(DeploymentDatabaseEngineType.values()).map(Enum::name).toList()));
            return List.copyOf(fields);
        }
        if (assessment.unknownDatabase())
            fields.add(field("main", "engine", supplied.get("database.main.engine"),
                Arrays.stream(DeploymentDatabaseEngineType.values()).map(Enum::name).toList()));
        for (var requirement : basis(assessment, supplied)) {
            String id = requirement.id();
            if (requirement.engine() == DeploymentDatabaseEngineType.SQLITE) {
                var sqlite = requirement.sqlite().orElseThrow();
                if (correction || sqlite.location().type() == StorageLocationType.UNRESOLVED)
                    fields.add(field(id, "path", value(supplied, id, "path",
                            sqlite.location().type() == StorageLocationType.DEFAULT ? "DEFAULT" : sqlite.accessPath()),
                        List.of()));
                if (correction || sqlite.pathEnvironment().isEmpty() && !requirement.springDatasource()
                    && sqlite.accessPath().isEmpty())
                    fields.add(field(id, "pathEnvironment",
                        value(supplied, id, "pathEnvironment", sqlite.pathEnvironment()), List.of()));
            } else {
                if (correction || requirement.database().isEmpty())
                    fields.add(field(id, "database", value(supplied, id, "database",
                            requirement.database().isEmpty() ? name(applicationId, id) : requirement.database()),
                        List.of()));
                if (correction || requirement.username().isEmpty())
                    fields.add(field(id, "username", value(supplied, id, "username",
                            requirement.username().isEmpty() ? name(applicationId, id) : requirement.username()),
                        List.of()));
                if (correction || requirement.environmentPrefix().isEmpty())
                    fields.add(field(id, "environmentPrefix",
                        value(supplied, id, "environmentPrefix", requirement.environmentPrefix()), List.of()));
                if (correction || requirement.passwordEnvironment().isEmpty())
                    fields.add(field(id, "passwordEnvironment",
                        value(supplied, id, "passwordEnvironment", requirement.passwordEnvironment()), List.of()));
            }
            if (assessment.schemaReviewRequired() && requirement.engine() != DeploymentDatabaseEngineType.REDIS
                && (correction || requirement.initializationFiles().isEmpty()))
                fields.add(field(id, "initialize",
                    value(supplied, id, "initialize", String.join(",", requirement.initializationFiles())),
                    assessment.sqlCandidates()));
        }
        return List.copyOf(fields);
    }

    /** Completes and validates reviewed declarations against the same frozen component root. / 对同一冻结组件根补齐并校验经审阅声明。
     * @param root frozen component root / 冻结组件根
     * @param applicationId reviewed component identity / 经审阅组件身份
     * @param assessment original source assessment / 原源码评估
     * @param supplied exact non-secret answers / 精确非秘密答案
     * @return resolved assessment without schema-effect approval / 不含结构效果批准的已解析评估
     * @throws IOException if a reviewed source file cannot be checked / 无法核验经审阅源码文件时 */
    public static DatabaseProjectChecker.Report complete(Path root, String applicationId,
                                                         DatabaseProjectChecker.Report assessment, Map<String, String> supplied) throws IOException {
        var complete = new ArrayList<DatabaseRequirement>();
        for (var requirement : basis(assessment, supplied)) {
            String id = requirement.id();
            var sqlite = sqlite(requirement, supplied);
            String initialization = value(supplied, id, "initialize", "");
            var paths = initialization.isBlank()
                ? requirement.initializationFiles()
                : Arrays.stream(initialization.split(",", -1)).map(String::trim).toList();
            var resolved = new DatabaseRequirement(id, requirement.engine(), requirement.version(),
                value(supplied, id, "database", requirement.database()),
                value(supplied, id, "username", requirement.username()),
                value(supplied, id, "environmentPrefix", requirement.environmentPrefix()),
                value(supplied, id, "passwordEnvironment", requirement.passwordEnvironment()), paths,
                requirement.springDatasource(), requirement.evidence(), sqlite);
            validate(root, applicationId, resolved);
            complete.add(resolved);
        }
        if (complete.size() > 16
            || complete.stream().map(DatabaseRequirement::id).distinct().count() != complete.size())
            throw new IllegalArgumentException("Database declarations must be bounded and unique");
        var server = complete.stream().filter(value -> value.engine() != DeploymentDatabaseEngineType.SQLITE).toList();
        if (server.stream().map(DatabaseRequirement::environmentPrefix).distinct().count() != server.size()
            || server.stream().map(DatabaseRequirement::passwordEnvironment).distinct().count() != server.size())
            throw new IllegalArgumentException("Database environment variables must be unique");
        if (assessment.schemaReviewRequired()
            && complete.stream().allMatch(value -> value.initializationFiles().isEmpty()))
            throw new IllegalArgumentException("Schema changes require explicit reviewed initialization files");
        return new DatabaseProjectChecker.Report(complete, assessment.sqlCandidates(),
            assessment.schemaReviewRequired(), false, assessment.endpointConfirmationRequired());
    }

    /** Constructs only a user-selected missing database family. / 仅构造由用户选择的缺失数据库族。
     * @param assessment original observations / 原观测
     * @param supplied exact answers / 精确答案
     * @return declared and selected requirements / 声明及已选择要求 */
    private static List<DatabaseRequirement> basis(DatabaseProjectChecker.Report assessment,
                                                   Map<String, String> supplied) {
        var values = new ArrayList<>(assessment.databases());
        if (assessment.unknownDatabase()) {
            String name = supplied.get("database.main.engine");
            if (name == null)
                throw new IllegalArgumentException("Database family requires explicit review");
            var engine = DeploymentDatabaseEngineType.valueOf(name);
            values.add(
                new DatabaseRequirement("main", engine, engine == DeploymentDatabaseEngineType.REDIS ? ">=6.2" : "",
                    "", "", "", "", List.of(), false, "User-selected database family"));
        }
        return List.copyOf(values);
    }

    /** Applies an explicit SQLite path correction without replacing an explicit host location. / 应用明确的 SQLite 路径修正而不替换已明确的宿主位置。
     * @param requirement source requirement / 源码要求
     * @param supplied reviewed answers / 经审阅答案
     * @return original or corrected SQLite requirement / 原始或已修正 SQLite 要求 */
    private static Optional<DeploymentSqliteRequirement> sqlite(DatabaseRequirement requirement,
                                                                Map<String, String> supplied) {
        if (requirement.sqlite().isEmpty())
            return Optional.empty();
        var original = requirement.sqlite().orElseThrow();
        String path = value(supplied, requirement.id(), "path", original.accessPath());
        String environment = value(supplied, requirement.id(), "pathEnvironment", original.pathEnvironment());
        if (path.equals(original.accessPath()) && environment.equals(original.pathEnvironment()))
            return Optional.of(original);
        var corrected = DeploymentSqliteRequirement.fromPath(path, environment, original.seedFile());
        return Optional.of(original.hostLocationExplicit()
            ? new DeploymentSqliteRequirement(original.location(), corrected.fileName(), corrected.accessPath(),
            corrected.pathEnvironment(), corrected.seedFile(), true)
            : corrected);
    }

    /** Checks exact reviewed file and connection inputs before remote mutation. / 远端修改前核验精确已审阅文件与连接输入。
     * @param root frozen source root / 冻结源码根
     * @param applicationId managed identity / 受管身份
     * @param requirement completed requirement / 已补全要求
     * @throws IOException if a source file cannot be verified / 无法核验源码文件时 */
    private static void validate(Path root, String applicationId, DatabaseRequirement requirement) throws IOException {
        if (requirement.engine() == DeploymentDatabaseEngineType.SQLITE) {
            var sqlite = requirement.sqlite().orElseThrow();
            sqlite.location().resolve(applicationId, StorageResourceType.DATABASE, requirement.id());
            if (sqlite.accessPath().isEmpty() && sqlite.pathEnvironment().isEmpty() && !requirement.springDatasource())
                throw new IllegalArgumentException("SQLite access path or environment must be reviewed");
            if (!sqlite.seedFile().isEmpty())
                checkedSourceFile(root, sqlite.seedFile());
        } else if (requirement.database().isBlank() || requirement.username().isBlank()
            || requirement.environmentPrefix().isBlank() || requirement.passwordEnvironment().isBlank())
            throw new IllegalArgumentException("Server database connection inputs must be complete");
        var sql = new StringBuilder();
        for (String path : requirement.initializationFiles()) {
            Path file = checkedSourceFile(root, path);
            if (Files.size(file) > 2 * 1024 * 1024)
                throw new IllegalArgumentException("Database SQL file exceeds the supported bound");
            sql.append(Files.readString(file)).append('\n');
            if (sql.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2 * 1024 * 1024)
                throw new IllegalArgumentException("Combined database SQL exceeds the supported bound");
        }
    }

    /** Confirms that a source file is a regular child of the frozen tree without imposing an SQL limit on SQLite seeds. / 确认源码文件是冻结树下的常规文件，不对 SQLite 种子套用 SQL 限额。
     * @param root frozen source root / 冻结源码根
     * @param relative reviewed relative path / 经审阅相对路径
     * @return original verified source path / 原已核验源码路径
     * @throws IOException if the source file cannot be inspected / 无法检查源码文件时 */
    private static Path checkedSourceFile(Path root, String relative) throws IOException {
        Path base = root.toRealPath();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            || !file.toRealPath().startsWith(base))
            throw new IllegalArgumentException("Database file must belong to the frozen source");
        return file;
    }

    /** Returns one bounded reviewed answer or its source default. / 返回一项有界审阅答案或源码默认值。
     * @param supplied reviewed answers / 经审阅答案
     * @param id database identifier / 数据库标识
     * @param key field identifier / 字段标识
     * @param fallback source value / 源码值
     * @return reviewed value / 经审阅值 */
    private static String value(Map<String, String> supplied, String id, String key, String fallback) {
        String value = supplied.getOrDefault("database." + id + "." + key, fallback);
        if (value.length() > 4096 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid database answer");
        return value.trim();
    }

    /** Describes one non-secret database field for the original review. / 描述原审阅中的一项非秘密数据库字段。
     * @param id database identifier / 数据库标识
     * @param key field identifier / 字段标识
     * @param value source suggestion / 源码建议
     * @param choices allowed answers / 可选答案
     * @return bounded field / 有界字段 */
    private static DeploymentReviewField field(String id, String key, String value, List<String> choices) {
        return new DeploymentReviewField("database." + id + "." + key, "database." + key, "database.help." + key, value,
            choices);
    }

    /** Derives the old bounded managed SQL name without guessing a workspace display name. / 派生旧有界受管 SQL 名称，不猜测工作区显示名。
     * @param applicationId reviewed application / 经审阅应用
     * @param id database identifier / 数据库标识
     * @return suggested SQL name / 建议 SQL 名称 */
    private static String name(String applicationId, String id) {
        String stem = (applicationId + "_" + id).replace('-', '_');
        if (stem.length() > 55)
            stem = stem.substring(0, 55);
        return stem;
    }
}
