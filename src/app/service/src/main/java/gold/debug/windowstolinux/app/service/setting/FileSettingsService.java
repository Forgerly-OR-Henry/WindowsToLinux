package gold.debug.windowstolinux.app.service.setting;

import java.sql.SQLException;
import java.util.Objects;
import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.PlatformStorage;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;

/** Persists optional deletion explanations in existing desktop preferences. / 在既有桌面偏好中保存可选删除说明。 */
public final class FileSettingsService {
    /** Configured explanation preference used only when storage is empty. / 仅在存储缺失时使用的配置说明偏好。 */
    private final boolean defaultEnabled;

    /** Saved application preference. / 应用持久偏好。 */
    public static final String KEY = "file_delete_ai_explanation";

    /** Actual storage owner. / 实际存储所有者。 */
    private final PlatformStorage persistence;

    /** Current user privacy boundary. / 当前用户隐私边界。 */
    private final DesktopSecurityService security;

    /** Binds the existing preference store. / 绑定既有偏好存储。
     * @param persistence actual storage / 实际存储
     * @param security user boundary / 用户边界 */
    public FileSettingsService(PlatformStorage persistence, DesktopSecurityService security) {
        this(persistence, security, true);
    }

    /** Captures the startup configuration. / 捕获启动配置。
     * @param persistence supplied startup dependency / 启动依赖
     * @param security supplied startup dependency / 启动依赖
     * @param defaultEnabled supplied startup dependency / 启动依赖
     */
    public FileSettingsService(PlatformStorage persistence, DesktopSecurityService security, boolean defaultEnabled) {
        this.defaultEnabled = defaultEnabled;
        this.persistence = Objects.requireNonNull(persistence);
        this.security = Objects.requireNonNull(security);
    }

    /** Reads the default-on setting; corrupt data never becomes normal absence. / 读取默认开启设置，损坏值不视作正常缺失。
     * @return confirmed saved state / 已确认保存状态
     * @throws SQLException if data or authorization is unavailable / 数据或授权不可用时 */
    public boolean enabled() throws SQLException {
        worker();
        try (var operation = security.enterOperation()) {
            String value = persistence.preferences().find(KEY).orElse(Boolean.toString(defaultEnabled));
            if (!value.equals("true") && !value.equals("false"))
                throw new SQLException("Stored deletion explanation setting is invalid");
            return Boolean.parseBoolean(value);
        }
    }

    /** Saves before presenting success. / 先保存再呈现成功。
     * @param enabled user selection / 用户选择
     * @return confirmed state / 已确认状态
     * @throws SQLException if storage or authorization fails / 存储或授权失败时 */
    public boolean save(boolean enabled) throws SQLException {
        worker();
        try (var operation = security.enterOperation()) {
            persistence.preferences().save(KEY, Boolean.toString(enabled));
            return enabled;
        }
    }

    /** Keeps database work off the EDT. / 将数据库工作留在 EDT 之外。 */
    private static void worker() {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("File settings require a worker");
    }
}
