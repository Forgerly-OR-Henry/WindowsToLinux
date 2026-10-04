package gold.debug.windowstolinux.app.service.security;

import gold.debug.windowstolinux.app.service.security.DesktopSecurityState;

/** The UI security boundary exposes state and password actions, never keys. / UI 安全边界只暴露状态和密码操作，不暴露密钥。 */
public interface SecurityAccess {
    /** Reports configuration, lock and accepted-task state without secrets. / 返回不含秘密的配置、锁定及已接纳任务状态。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    DesktopSecurityState securityState();

    /** Reads native privacy confirmation without waiting for window operations. / 不等待窗口操作地读取原生隐私确认。
     * @return pending transition and bounded failure / 待完成转换及有界失败 */
    DesktopPrivacyState privacyState();

    /** Retries native privacy while keeping platform access locked. / 保持平台访问锁定并重试原生隐私。 */
    void retryPrivacy();

    /** Atomically captures authorization before scheduling a background task. / 在调度后台任务前原子取得授权。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    DesktopTaskAuthorization beginTask();

    /** Creates the first security profile and unlocks only if no newer lock intervened. / 创建首次安全配置，仅在没有后续锁定时解锁。
     * @param password master-password buffer cleared after use / 使用后清除的主密码缓冲区
     * @param confirmation repeated password or confirmation callback / 重复密码或确认回调
     * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    void setupAndUnlock(char[] password, char[] confirmation) throws gold.debug.windowstolinux.appapi.task.result.ApiException;

    /** Verifies the password and opens a session without exposing the data key. / 验证密码并建立会话，不暴露数据密钥。
     * @param password master-password buffer cleared after use / 使用后清除的主密码缓冲区
     * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    void unlock(char[] password) throws gold.debug.windowstolinux.appapi.task.result.ApiException;

    /** Drops interactive access while preserving accepted task capabilities. / 释放交互会话，保留已接纳任务的凭据能力。
     */
    void lock();

    /** Verifies the current password and rewraps the unchanged data key. / 验证当前密码并重新包装原有数据密钥。
     * @param current current master-password buffer / 当前主密码缓冲区
     * @param replacement new master-password buffer / 新主密码缓冲区
     * @param confirmation repeated password or confirmation callback / 重复密码或确认回调
     * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    void changeMasterPassword(char[] current, char[] replacement, char[] confirmation) throws gold.debug.windowstolinux.appapi.task.result.ApiException;

    /** Exclusively converts all credentials and the global setting in one transaction. / 独占地在同一事务转换全部凭据及全局设置。
     * @param current current master-password buffer / 当前主密码缓冲区
     * @param enabled requested enabled flag / 请求启用标记
     * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    void setCredentialEncryption(char[] current, boolean enabled) throws gold.debug.windowstolinux.appapi.task.result.ApiException;

    /** Waits interruptibly for an unlocked session outside security mutation. / 以可中断方式等待不处于安全变更的解锁会话。
     * @throws InterruptedException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    void awaitUnlocked() throws InterruptedException;
}
