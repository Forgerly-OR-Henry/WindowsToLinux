package gold.debug.windowstolinux.app.service.setting;

import java.sql.SQLException;
import java.util.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.PlatformStorage;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;

/** Stores independent platform transfer preferences without retaining task grants or checkpoints. / 保存独立平台传输偏好，不保留任务授权或检查点。 */
public final class TransferSettingsService implements AutoCloseable {
    /** Configured thresholds used only for absent transfer preferences. / 仅用于缺失传输偏好的配置阈值。 */
    private final gold.debug.windowstolinux.app.config.AppConfig.Defaults defaults;

    /** Persisted connection threshold key. / 持久连接阈值键。 */
    private static final String CONNECT = "transfer_connect_seconds";

    /** Persisted effective-byte stall threshold key. / 持久有效字节停滞阈值键。 */
    private static final String IDLE = "transfer_idle_seconds";

    /** Current platform repositories. / 当前平台仓库。 */
    private final PlatformStorage persistence;

    /** Current-user and original-operation authorization. / 当前用户及原操作授权。 */
    private final DesktopSecurityService security;

    /** Prevents new settings work after shutdown. / 关闭后阻止新设置工作。 */
    private boolean closed;

    /** Binds the shared preference owner without reading storage on construction. / 绑定共用偏好所有者，构造时不读取存储。
     * @param persistence actual platform storage / 实际平台存储
     * @param security original operation authorization / 原操作授权 */
    public TransferSettingsService(PlatformStorage persistence, DesktopSecurityService security) {
        this(persistence, security, gold.debug.windowstolinux.app.config.AppConfig.defaultsConfig().defaults());
    }

    /** Captures the startup configuration. / 捕获启动配置。
     * @param persistence supplied startup dependency / 启动依赖
     * @param security supplied startup dependency / 启动依赖
     * @param defaults supplied startup dependency / 启动依赖
     */
    public TransferSettingsService(PlatformStorage persistence, DesktopSecurityService security, gold.debug.windowstolinux.app.config.AppConfig.Defaults defaults) {
        this.defaults = Objects.requireNonNull(defaults);
        this.persistence = Objects.requireNonNull(persistence);
        this.security = Objects.requireNonNull(security);
    }

    /** Reads one pair, using defaults only for absent keys. / 读取一对设置，仅为缺失键使用默认值。
     * @return validated current thresholds / 已校验当前阈值
     * @throws SQLException if storage or a stored value is invalid / 存储或已存值无效时 */
    public synchronized TransferConfig read() throws SQLException {
        requireOpen();
        try (var operation = security.enterOperation()) {
            try {
                return new TransferConfig(seconds(CONNECT, defaults.connectSeconds()),
                    seconds(IDLE, defaults.idleSeconds()));
            } catch (IllegalArgumentException invalid) {
                throw new SQLException("Stored transfer thresholds are invalid", invalid);
            }
        }
    }

    /** Commits both thresholds together and returns only after successful persistence. / 一并提交两个阈值，仅在持久化成功后返回。
     * @param settings validated requested thresholds / 已校验请求阈值
     * @return confirmed saved values / 确认已保存值
     * @throws SQLException if the atomic preference write fails / 原子偏好写入失败时 */
    public synchronized TransferConfig save(TransferConfig settings) throws SQLException {
        requireOpen();
        Objects.requireNonNull(settings);
        try (var operation = security.enterOperation()) {
            persistence.preferences().saveAll(Map.of(CONNECT, Integer.toString(settings.connectSeconds()), IDLE,
                Integer.toString(settings.idleSeconds())));
            return settings;
        }
    }

    /** Parses one persisted whole-second preference without silently replacing corrupt text. / 解析一项持久整数秒偏好，不静默替换损坏文本。
     * @param key exact owned key / 精确所属键
     * @param fallback default for an absent key / 缺失键默认值
     * @return stored or default seconds / 已存或默认秒数
     * @throws SQLException if the read fails / 读取失败时 */
    private int seconds(String key, int fallback) throws SQLException {
        var stored = persistence.preferences().find(key);
        if (stored.isEmpty())
            return fallback;
        if (!stored.orElseThrow().matches("[1-9][0-9]{0,4}"))
            throw new IllegalArgumentException("Invalid stored whole-second threshold");
        return Integer.parseInt(stored.orElseThrow());
    }

    /** Rejects database operations on the EDT or after closure. / 拒绝在 EDT 或关闭后执行数据库操作。 */
    private void requireOpen() {
        if (SwingUtilities.isEventDispatchThread() || closed)
            throw new IllegalStateException("Transfer settings require an open background service");
    }

    /** Seals access after any accepted preference operation returns. / 已接纳偏好操作返回后封闭访问。 */
    @Override
    public synchronized void close() {
        closed = true;
    }
}
