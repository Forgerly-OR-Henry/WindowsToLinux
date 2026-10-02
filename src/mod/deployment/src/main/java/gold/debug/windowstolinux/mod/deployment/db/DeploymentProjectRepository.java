package gold.debug.windowstolinux.mod.deployment.db;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.net.URI;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.ModDatabase;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectSource;

/** Saves complete project source revisions through the original task and managed H2 generation. / 经原任务及受管H2代次保存完整项目来源修订。 */
public final class DeploymentProjectRepository {
    /** Original task-bound database capability. / 原任务绑定数据库能力。 */
    private final ModDatabase database;

    /** Binds the exact business database. / 绑定精确业务数据库。
     * @param database original capability / 原能力 */
    public DeploymentProjectRepository(ModDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Creates one complete record; an exact retry leaves the original row unchanged. / 创建一条完整记录；精确重试不改原行。
     * @param task original active task / 原活动任务
     * @param project observed project / 已观察项目
     * @throws SQLException if a conflicting identity or SQL failure occurs / 身份冲突或SQL失败时
     * @throws ApiException if task access or transaction confirmation is uncertain / 任务访问或事务确认未知时 */
    public void create(TaskContext task, DeploymentProjectRecord project) throws SQLException, ApiException {
        Objects.requireNonNull(project);
        var connection = connection(task);
        DeploymentStorageTransaction.execute(connection, () -> {
            var existing = read(connection, project.id(), true);
            if (existing.isPresent()) {
                if (!existing.get().equals(project))
                    throw new SQLException("Project identity belongs to different facts");
                return;
            }
            try (var statement = connection.prepareStatement("""
                INSERT INTO deployment_project(project_id,display_name,source_kind,source_location,git_ref,
                git_commit,source_sha256,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)
                """)) {
                bind(statement, project);
                if (statement.executeUpdate() != 1)
                    throw new SQLException("Project creation was not confirmed");
            }
        });
    }

    /** Changes an existing project only when every expected old fact still matches. / 仅在全部预期旧事实仍匹配时更新项目。
     * @param task original active task / 原活动任务
     * @param expected complete reviewed old row / 完整已审阅旧记录
     * @param next complete newly observed row / 完整新观察记录
     * @throws SQLException if ownership, time, comparison or SQL fails / 身份、时间、比较或SQL失败时
     * @throws ApiException if task access or durability is uncertain / 任务访问或持久性未知时 */
    public void update(TaskContext task, DeploymentProjectRecord expected, DeploymentProjectRecord next)
        throws SQLException, ApiException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(next);
        if (!expected.id().equals(next.id()) || !expected.createdAt().equals(next.createdAt())
            || !next.updatedAt().isAfter(expected.updatedAt()))
            throw new SQLException("Project update requires original identity and a later observation");
        var connection = connection(task);
        DeploymentStorageTransaction.execute(connection, () -> {
            var current = read(connection, expected.id(), true)
                .orElseThrow(() -> new SQLException("Original project is absent"));
            if (current.equals(next))
                return;
            if (!current.equals(expected))
                throw new SQLException("Project changed since the reviewed revision");
            try (var statement = connection.prepareStatement("""
                UPDATE deployment_project SET display_name=?,source_kind=?,source_location=?,git_ref=?,
                git_commit=?,source_sha256=?,updated_at=? WHERE project_id=?
                """)) {
                bindUpdate(statement, next);
                if (statement.executeUpdate() != 1)
                    throw new SQLException("Project update was not confirmed");
            }
        });
    }

    /** Reads one complete source revision from the task's generation. / 从任务代次读取一条完整来源修订。
     * @param task active task / 活动任务
     * @param id stable project identity / 稳定项目身份
     * @return stored project or absence / 已存项目或不存在
     * @throws SQLException if SQL or retained facts are invalid / SQL或已留存事实无效时
     * @throws ApiException if task access fails / 任务访问失败时 */
    public Optional<DeploymentProjectRecord> find(TaskContext task, UUID id) throws SQLException, ApiException {
        try (var connection = connection(task)) {
            return read(connection, Objects.requireNonNull(id), false);
        }
    }

    /** Lists a bounded stable prefix of saved projects. / 列出有界且排序稳定的已保存项目前缀。
     * @param task active task / 活动任务
     * @param maximum maximum one to one thousand / 一至一千的最大数量
     * @return immutable ordered projects / 不可变有序项目
     * @throws SQLException if input, SQL or retained facts are invalid / 输入、SQL或已存事实无效时
     * @throws ApiException if task access fails / 任务访问失败时 */
    public List<DeploymentProjectRecord> list(TaskContext task, int maximum) throws SQLException, ApiException {
        if (maximum < 1 || maximum > 1000)
            throw new SQLException("Project list limit is invalid");
        try (var connection = connection(task);
             var statement = connection
                 .prepareStatement("SELECT * FROM deployment_project ORDER BY created_at,project_id LIMIT ?")) {
            statement.setInt(1, maximum);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<DeploymentProjectRecord>();
                while (rows.next())
                    result.add(row(rows));
                return List.copyOf(result);
            }
        }
    }

    /** Reads and optionally locks a complete project within an existing transaction. / 在已有事务中读取并可锁定完整项目。
     * @param connection original connection / 原连接
     * @param id stable project identity / 稳定项目身份
     * @param lock whether an original transaction needs a row lock / 原事务是否需要行锁
     * @return current row or absence / 当前记录或不存在
     * @throws SQLException if SQL or row is invalid / SQL或记录无效时 */
    static Optional<DeploymentProjectRecord> read(Connection connection, UUID id, boolean lock) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT * FROM deployment_project WHERE project_id=?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(row(rows)) : Optional.empty();
            }
        }
    }

    /** Validates saved complete records without changing a row. / 不改记录地核验已保存完整项目。
     * @param connection SELECT-only schema reader / 仅SELECT结构读取器
     * @throws SQLException if a record is invalid / 记录无效时 */
    static void validate(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM deployment_project")) {
            while (rows.next())
                row(rows);
        }
    }

    /** Decodes actual complete project facts. / 解码实际完整项目事实。
     * @param rows current row / 当前记录
     * @return checked project / 已校验项目
     * @throws SQLException if retained facts are invalid / 已存事实无效时 */
    private static DeploymentProjectRecord row(ResultSet rows) throws SQLException {
        try {
            var source = new DeploymentProjectSource(
                DeploymentProjectSource.KindType.valueOf(rows.getString("source_kind")),
                URI.create(rows.getString("source_location")), Optional.ofNullable(rows.getString("git_ref")),
                Optional.ofNullable(rows.getString("git_commit")), rows.getString("source_sha256"));
            return new DeploymentProjectRecord(rows.getObject("project_id", UUID.class), rows.getString("display_name"),
                source, Instant.ofEpochMilli(rows.getLong("created_at")),
                Instant.ofEpochMilli(rows.getLong("updated_at")));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Stored project source facts are invalid", invalid);
        }
    }

    /** Binds all immutable creation columns. / 绑定全部创建列。
     * @param statement original insert / 原插入语句
     * @param project complete record / 完整记录
     * @throws SQLException if JDBC rejects a value / JDBC拒绝值时 */
    private static void bind(PreparedStatement statement, DeploymentProjectRecord project) throws SQLException {
        statement.setObject(1, project.id());
        statement.setString(2, project.displayName());
        statement.setString(3, project.source().kind().name());
        statement.setString(4, project.source().location().toString());
        statement.setString(5, project.source().gitRef().orElse(null));
        statement.setString(6, project.source().gitCommit().orElse(null));
        statement.setString(7, project.source().sha256());
        statement.setLong(8, project.createdAt().toEpochMilli());
        statement.setLong(9, project.updatedAt().toEpochMilli());
    }

    /** Binds only the changed project columns. / 仅绑定可变项目列。
     * @param statement original update / 原更新语句
     * @param project new complete record / 新完整记录
     * @throws SQLException if JDBC rejects a value / JDBC拒绝值时 */
    private static void bindUpdate(PreparedStatement statement, DeploymentProjectRecord project) throws SQLException {
        statement.setString(1, project.displayName());
        statement.setString(2, project.source().kind().name());
        statement.setString(3, project.source().location().toString());
        statement.setString(4, project.source().gitRef().orElse(null));
        statement.setString(5, project.source().gitCommit().orElse(null));
        statement.setString(6, project.source().sha256());
        statement.setLong(7, project.updatedAt().toEpochMilli());
        statement.setObject(8, project.id());
    }

    /** Opens only the exact owned business generation. / 仅打开精确所属业务代次。
     * @param task original task / 原任务
     * @return managed connection / 受管连接
     * @throws SQLException if JDBC fails / JDBC失败时
     * @throws ApiException if task authorization fails / 任务授权失败时 */
    private Connection connection(TaskContext task) throws SQLException, ApiException {
        DeploymentSchema.requireIdentity(database.identity());
        return database.openConnection(task);
    }
}
