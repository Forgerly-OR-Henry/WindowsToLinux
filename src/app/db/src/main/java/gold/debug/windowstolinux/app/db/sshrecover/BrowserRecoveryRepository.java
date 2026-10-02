package gold.debug.windowstolinux.app.db.sshrecover;

import gold.debug.windowstolinux.app.db.connect.RepositoryTransactionExecutor;

import java.sql.SQLException;
import java.time.Instant;

import gold.debug.windowstolinux.app.db.connect.RepositoryConnections;

/**
 * Stores only bounded state codes and command hashes, never terminal contents. / 仅保存状态码及命令摘要，不保存终端内容。
 */
public final class BrowserRecoveryRepository {
    /**
     * Factory for scoped database connections.
     * <p>限定作用域数据库连接的工厂。
     */
    private final RepositoryConnections connections;

    /** Binds owned platform connections and repository transactions. / 绑定所属平台连接和仓库事务。
     * @param connections platform transaction scope / 平台事务作用域
     */
    public BrowserRecoveryRepository(RepositoryConnections connections) {
        this.connections = java.util.Objects.requireNonNull(connections);
    }

    /** Saves current platform rescue metadata only while its original task and server remain active. / 仅在原平台救援任务及服务器仍活动时保存元数据。
     * @param id original platform task / 原平台任务
     * @param server original held server / 原持有服务器
     * @param state observed controller state / 已观察控制器状态
     * @param digest last command hash without command text / 最后命令摘要，不含命令正文
     * @param event observed metadata event / 已观察元数据事件
     * @throws SQLException if original ownership or journal persistence fails / 原归属或记录持久化失败时
     * @throws IllegalArgumentException if state, event or digest violates the metadata contract / 状态、事件或摘要违反元数据契约时 */
    public void savePlatform(java.util.UUID id, String server, String state, String digest, String event)
        throws SQLException {
        connections.transaction(() -> {
            try (var c = connections.open()) {
                RepositoryConnections.lockPlatformState(c);
                gold.debug.windowstolinux.app.db.sshrecover.BrowserRecoveryArchival.requireActive(c, id, server);
                save(id.toString(), server, gold.debug.windowstolinux.app.db.sshrecover.BrowserRecoveryArchival.TASK_TYPE,
                    state, digest, event);
            }
        });
    }

    /**
     * Interrupts unfinished sessions at application startup. / 应用启动时中断未完成会话。
     *
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void interruptUnfinished() throws SQLException {
        try (var c = connections.open();
             var s = c.prepareStatement(
                 "UPDATE browser_recovery_task SET task_state='INTERRUPTED',last_event_code='app-restarted',updated_at=? WHERE task_state NOT IN ('RECOVERED','CANCELLED','FAILED','INTERRUPTED')")) {
            s.setLong(1, Instant.now().toEpochMilli());
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
                long time = Instant.now().toEpochMilli();
                int changed;
                try (var update = c.prepareStatement(
                    "UPDATE browser_recovery_task SET task_state=?,last_action_digest=?,last_event_code=?,updated_at=? WHERE task_id=? AND server_profile_id=? AND operation_id=?")) {
                    update.setString(1, state);
                    update.setString(2, digest);
                    update.setString(3, event);
                    update.setLong(4, time);
                    update.setString(5, id);
                    update.setString(6, server);
                    update.setString(7, operation);
                    changed = update.executeUpdate();
                }
                if (changed == 0)
                    try (var insert = c.prepareStatement(
                        "INSERT INTO browser_recovery_task(task_id,server_profile_id,operation_id,task_state,last_action_digest,last_event_code,updated_at) VALUES(?,?,?,?,?,?,?)")) {
                        insert.setString(1, id);
                        insert.setString(2, server);
                        insert.setString(3, operation);
                        insert.setString(4, state);
                        insert.setString(5, digest);
                        insert.setString(6, event);
                        insert.setLong(7, time);
                        insert.executeUpdate();
                    }
                try (var s = c.prepareStatement(
                    "INSERT INTO browser_recovery_event(task_id,task_state,action_digest,event_code,created_at) VALUES(?,?,?,?,?)")) {
                    s.setString(1, id);
                    s.setString(2, state);
                    s.setString(3, digest);
                    s.setString(4, event);
                    s.setLong(5, time);
                    s.executeUpdate();
                }
            });
        }
    }
}
