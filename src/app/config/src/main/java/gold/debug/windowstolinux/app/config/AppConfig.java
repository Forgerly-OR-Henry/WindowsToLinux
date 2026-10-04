package gold.debug.windowstolinux.app.config;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/** Immutable configuration captured for one application boot. / 一次应用启动的不可变配置。
 * @param dataDirectory configured database directory / 数据目录
 * @param ui initial presentation choices / 界面初态
 * @param defaults fallback user preferences / 用户偏好默认值
 * @param ssh connection and recovery timings / SSH与救援时序
 * @param ai model request budgets / 模型调用预算
 * @param emergencyRecords retained records per emergency category / 每类应急日志保留数量
 * @param modLoad whether Mod composition is allowed / 是否装配Mod
 * @param storeUrl optional store service root / 可选商店服务根 */
public record AppConfig(String dataDirectory, Ui ui, Defaults defaults, Ssh ssh, Ai ai,
                        int emergencyRecords, boolean modLoad, Optional<URI> storeUrl) {
    /** Requires immutable, non-null members. / 要求不可变且非空的成员。
     * @param dataDirectory configured data root / 配置数据根
     * @param ui initial presentation / 初始展示
     * @param defaults absent-preference defaults / 缺失偏好默认值
     * @param ssh SSH timing configuration / SSH 时序配置
     * @param ai model request budgets / 模型请求预算
     * @param emergencyRecords retention per category / 每类保留数量
     * @param modLoad Mod composition switch / Mod 装配开关
     * @param storeUrl optional store root / 可选商店根地址
     */
    public AppConfig {
        Objects.requireNonNull(dataDirectory);
        Objects.requireNonNull(ui);
        Objects.requireNonNull(defaults);
        Objects.requireNonNull(ssh);
        Objects.requireNonNull(ai);
        Objects.requireNonNull(storeUrl);
    }

    /** Returns documented baseline values for explicit internal composition. / 返回明确内部装配使用的文档默认值。
     * @return baseline configuration / 默认配置 */
    public static AppConfig defaultsConfig() {
        return new AppConfig("data", new Ui(false, false), new Defaults("system", "SYSTEM", 30, 300, true),
            new Ssh(30, 2, 10, 15), new Ai(10, new Budget(60, 2048), new Budget(30, 512), new Budget(20, 256)),
            100, true, Optional.empty());
    }

    /** Initial visibility only, independent of permissions. / 仅初始展示，与权限独立。
     * @param navigationCollapsed initial sidebar collapse / 初始导航折叠
     * @param advancedExpanded initial CLASS advanced expansion / 初始CLASS高级展开 */
    public record Ui(boolean navigationCollapsed, boolean advancedExpanded) {
    }

    /** Defaults used only when a user preference is absent. / 仅在用户偏好缺失时使用。
     * @param locale system, zh-CN or en-US / 系统、中文或英文
     * @param theme SYSTEM, LIGHT or DARK / 系统、亮色或暗色
     * @param connectSeconds connection seconds / 连接秒数
     * @param idleSeconds no-progress seconds / 无进展秒数
     * @param deleteExplanation initial optional AI explanation / 初始AI删除说明 */
    public record Defaults(String locale, String theme, int connectSeconds, int idleSeconds,
                           boolean deleteExplanation) {
    }

    /** Positive SSH timing values. / 正SSH时序。
     * @param heartbeatSeconds heartbeat interval / 心跳间隔
     * @param observationSeconds recovery observation interval / 救援观察间隔
     * @param probeIntervalSeconds SSH verification interval / SSH验证间隔
     * @param probeTimeoutSeconds SSH verification timeout / SSH验证超时 */
    public record Ssh(int heartbeatSeconds, int observationSeconds, int probeIntervalSeconds, int probeTimeoutSeconds) {
    }

    /** Positive model budget. / 正模型预算。
     * @param timeoutSeconds request timeout / 请求超时
     * @param maxTokens output token limit / 输出Token上限 */
    public record Budget(int timeoutSeconds, int maxTokens) {
    }

    /** Independent transport and operation budgets. / 独立传输与用途预算。
     * @param connectSeconds HTTP connect cap / HTTP连接上限
     * @param recovery SSH recovery budget / SSH救援预算
     * @param deletion deletion explanation budget / 删除说明预算
     * @param file file judgement budget / 文件判断预算 */
    public record Ai(int connectSeconds, Budget recovery, Budget deletion, Budget file) {
    }
}
