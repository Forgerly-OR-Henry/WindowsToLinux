package gold.debug.windowstolinux.shared.model.project;

/** Persistent execution policy without ephemeral UID values. / 不包含临时 UID 的持久执行策略。 */
public enum RuntimeIdentityMode {
    /** Historical record that did not declare an isolation policy. / 未声明隔离策略的历史记录。 */
    LEGACY_UNSPECIFIED,
    /** Native service with a fixed, non-login per-application account. / 使用应用独占固定不可登录账号的原生服务。 */
    SYSTEMD_STATIC,
    /** Container image declaring an explicit non-root user. / 声明明确非 root 用户的容器镜像。 */
    CONTAINER_NON_ROOT
}
