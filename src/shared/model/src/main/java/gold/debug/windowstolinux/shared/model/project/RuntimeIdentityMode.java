package gold.debug.windowstolinux.shared.model.project;

/** Persistent execution policy without ephemeral UID values. / 不包含临时 UID 的持久执行策略。 */
public enum RuntimeIdentityMode {
    /** Historical record that did not declare an isolation policy. / 未声明隔离策略的历史记录。 */
    LEGACY_UNSPECIFIED,
    /** Native service with systemd-managed dynamic identity and state. / 使用 systemd 动态身份及状态目录的原生服务。 */
    SYSTEMD_DYNAMIC,
    /** Container image declaring an explicit non-root user. / 声明明确非 root 用户的容器镜像。 */
    CONTAINER_NON_ROOT
}
