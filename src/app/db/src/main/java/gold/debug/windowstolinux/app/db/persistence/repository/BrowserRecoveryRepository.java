package gold.debug.windowstolinux.app.db.persistence.repository;

import java.sql.SQLException;
import java.time.Instant;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;

/**
 * Stores only bounded state codes and command hashes, never terminal contents. / 仅保存状态码及命令摘要，不保存终端内容。
 */
public final class BrowserRecoveryRepository {
    /**
     * Factory for scoped database connections.
     * <p>限定作用域数据库连接的工厂。
     */
    private final DesktopConnectionFactory connections;
    /**
     * Binds the desktop connection factory. / 绑定桌面连接工厂。
     *
     * @param connections factory for scoped database connections / 限定作用域数据库连接的工厂
     */
    public BrowserRecoveryRepository(DesktopConnectionFactory connections) {
        this.connections = connections;
    }

    /**
     * Interrupts unfinished sessions at application startup. / 应用启动时中断未完成会话。
     *
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void interruptUnfinished() throws SQLException {
        try (var c = connections.open();
                var s = c.prepareStatement(
                        "UPDATE browser_recovery SET state='INTERRUPTED',event_code='app-restarted' WHERE state NOT IN ('RECOVERED','CANCELLED','FAILED','INTERRUPTED')")) {
            s.executeUpdate();
        }
    }

    /**
     * Atomically saves current state and its bounded audit event. / 原子保存当前状态及有界审计事件。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param operation operation / 操作
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @param digest content identity used for independent verification / 独立验证所用的内容身份
     * @param event state or UI event being processed / 正在处理的状态或 UI 事件
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public void save(String id, String server, String operation, String state, String digest, String event)
            throws SQLException {
        if (!state.matches("[A-Z_]{1,40}") || !event.matches("[a-z-]{1,80}") || !digest.matches("(?:[a-f0-9]{64})?"))
            throw new IllegalArgumentException("invalid recovery journal");
        try (var c = connections.open()) {
            RepositoryTransactionExecutor.execute(c, () -> {
                String time = Instant.now().toString();
                try (var s = c.prepareStatement(
                        "INSERT INTO browser_recovery(id,server_id,operation_id,state,action_digest,event_code,updated_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET state=excluded.state,action_digest=excluded.action_digest,event_code=excluded.event_code,updated_at=excluded.updated_at")) {
                    s.setString(1, id);
                    s.setString(2, server);
                    s.setString(3, operation);
                    s.setString(4, state);
                    s.setString(5, digest);
                    s.setString(6, event);
                    s.setString(7, time);
                    s.executeUpdate();
                }
                try (var s = c.prepareStatement(
                        "INSERT INTO browser_recovery_event(recovery_id,state,action_digest,event_code,created_at) VALUES(?,?,?,?,?)")) {
                    s.setString(1, id);
                    s.setString(2, state);
                    s.setString(3, digest);
                    s.setString(4, event);
                    s.setString(5, time);
                    s.executeUpdate();
                }
            });
        }
    }
}
