package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.StoredExternalApplication;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationPresentation;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import java.net.URI;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Persists external registrations and presentation without granting managed ownership. / 保存外部登记与显示信息，不授予受管归属。
 */
public final class ExternalApplicationRepository {
    /**
     * Factory for scoped database connections.
     * <p>限定作用域数据库连接的工厂。
     */
    private final DesktopConnectionFactory connections;
    /**
     * Binds the repository to desktop persistence. / 绑定桌面持久化。
     *
     * @param connections factory for scoped database connections / 限定作用域数据库连接的工厂
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ExternalApplicationRepository(DesktopConnectionFactory connections) { this.connections = Objects.requireNonNull(connections); }

    /**
     * Returns registrations in deterministic order. / 按确定顺序返回登记。
     *
     * @return registrations in deterministic order / 按确定顺序返回登记
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public List<StoredExternalApplication> list() throws SQLException {
        try (Connection connection = connections.open(); var statement = connection.prepareStatement("SELECT * FROM external_application ORDER BY id"); var rows = statement.executeQuery()) {
            List<StoredExternalApplication> result = new ArrayList<>(); while (rows.next()) result.add(read(rows)); return List.copyOf(result);
        }
    }
    /**
     * Finds one exact registration. / 查找精确登记。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<StoredExternalApplication> find(String id) throws SQLException {
        try (Connection connection = connections.open(); var statement = connection.prepareStatement("SELECT * FROM external_application WHERE id=?")) {
            statement.setString(1, id); try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(read(rows)) : Optional.empty(); }
        }
    }
    /**
     * Imports once, rejecting a replaced runtime behind an existing lookup key. / 只导入一次，拒绝既有查询键后的运行时替换。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return adopt text / 接管文本
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public String adopt(StoredExternalApplication application) throws SQLException {
        return adopt(application, false);
    }
    /**
     * A newly selected rescan may explicitly replace a changed local registration. / 新扫描后的明确选择可替换已变化的本地登记。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param replaceChanged replace changed / 替换已变化
     * @return adopt text / 接管文本
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public String adopt(StoredExternalApplication application, boolean replaceChanged) throws SQLException {
        try (Connection connection = connections.open()) {
            String[] savedId = {application.id()};
            RepositoryTransactionExecutor.execute(connection, () -> {
                try (var existing = connection.prepareStatement("SELECT * FROM external_application WHERE server_id=? AND kind=? AND runtime_identity=?")) {
                    existing.setString(1, application.serverId()); existing.setString(2, application.application().target().kind().name());
                    existing.setString(3, application.application().target().identity());
                    try (var rows = existing.executeQuery()) {
                        if (rows.next()) {
                            var prior = read(rows);
                            boolean changed = !prior.application().target().equals(application.application().target()) || !prior.host().equals(application.host())
                                    || prior.sshPort() != application.sshPort() || !prior.username().equals(application.username());
                            if (!changed) { savedId[0] = prior.id(); return; }
                            if (!replaceChanged) throw new SQLException("registered application identity changed; rescan and explicitly replace registration");
                            try (var remove = connection.prepareStatement("DELETE FROM external_application WHERE id=?");
                                 var presentation = connection.prepareStatement("DELETE FROM application_presentation WHERE application_key=?")) {
                                remove.setString(1, prior.id()); remove.executeUpdate();
                                presentation.setString(1, prior.id()); presentation.executeUpdate();
                            }
                        }
                    }
                }
                try (var statement = connection.prepareStatement("INSERT INTO external_application VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    var app = application.application(); var target = app.target();
                    statement.setString(1, application.id()); statement.setString(2, application.serverId()); statement.setString(3, application.host());
                    statement.setInt(4, application.sshPort()); statement.setString(5, application.username()); statement.setString(6, target.kind().name());
                    statement.setString(7, target.identity()); statement.setString(8, target.fingerprint()); statement.setString(9, app.name());
                    statement.setString(10, app.state().name()); statement.setBoolean(11, app.canStart()); statement.setBoolean(12, app.canStop());
                    statement.setString(13, application.adoptedAt().toString()); statement.setString(14, application.observedAt().toString()); statement.executeUpdate();
                }
            });
            return savedId[0];
        }
    }
    /**
     * Updates only the observation for the same adopted target. / 仅更新同一已接管目标的观测。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param app app / 应用
     * @param time time / 时间
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void observe(String id, DiscoveredApplication app, Instant time) throws SQLException {
        try (Connection connection = connections.open(); var statement = connection.prepareStatement(
                "UPDATE external_application SET runtime_state=?, can_start=?, can_stop=?, observed_at=? WHERE id=? AND kind=? AND runtime_identity=? AND fingerprint=?")) {
            statement.setString(1, app.state().name()); statement.setBoolean(2, app.canStart()); statement.setBoolean(3, app.canStop()); statement.setString(4, time.toString());
            statement.setString(5, id); statement.setString(6, app.target().kind().name()); statement.setString(7, app.target().identity()); statement.setString(8, app.target().fingerprint());
            if (statement.executeUpdate() != 1) throw new SQLException("external registration changed during observation");
        }
    }
    /**
     * Reads user presentation overrides for both inventory kinds. / 读取两种应用清单的用户显示覆盖。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<StoredApplicationPresentation> presentation(String key) throws SQLException {
        try (Connection connection = connections.open(); var statement = connection.prepareStatement("SELECT * FROM application_presentation WHERE application_key=?")) {
            statement.setString(1, key); try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new StoredApplicationPresentation(key, rows.getString("display_name"), rows.getString("category"),
                        Optional.ofNullable(rows.getString("access_url")).map(URI::create).map(UserAccessUrl::new))) : Optional.empty();
            }
        }
    }
    /**
     * Stores local presentation without modifying the server configuration. / 保存本地显示设置，不修改服务器配置。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void savePresentation(StoredApplicationPresentation value) throws SQLException {
        try (Connection connection = connections.open(); var statement = connection.prepareStatement("""
                INSERT INTO application_presentation VALUES (?,?,?,?) ON CONFLICT(application_key) DO UPDATE SET
                display_name=excluded.display_name, category=excluded.category, access_url=excluded.access_url
                """)) {
            statement.setString(1, value.key()); statement.setString(2, value.name()); statement.setString(3, value.category());
            statement.setString(4, value.accessUrl().map(url -> url.url().toString()).orElse(null)); statement.executeUpdate();
        }
    }
    /**
     * Reads stored external application.
     * <p>读取已存储外部应用。
     *
     * @param row row / 数据行
     * @return stored external application / 已存储外部应用
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static StoredExternalApplication read(ResultSet row) throws SQLException {
        var target = new ExternalApplicationTarget(ExternalApplicationKind.valueOf(row.getString("kind")), row.getString("runtime_identity"), row.getString("fingerprint"));
        var app = new DiscoveredApplication(target, row.getString("display_name"), RuntimeState.valueOf(row.getString("runtime_state")), row.getBoolean("can_start"), row.getBoolean("can_stop"), false);
        return new StoredExternalApplication(row.getString("id"), row.getString("server_id"), row.getString("host"), row.getInt("ssh_port"), row.getString("username"),
                app, Instant.parse(row.getString("adopted_at")), Instant.parse(row.getString("observed_at")));
    }
}
