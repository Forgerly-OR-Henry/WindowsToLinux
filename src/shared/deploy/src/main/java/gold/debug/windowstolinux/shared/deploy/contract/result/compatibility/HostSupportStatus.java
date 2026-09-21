package gold.debug.windowstolinux.shared.deploy.contract.result.compatibility;

/**
 * The conservative outcome of matching live host evidence against the typed deployment matrix.
 *
 *  <p>将实时主机证据与部署矩阵匹配后的保守结果。
 */
public enum HostSupportStatus {
    /**
     * The host may proceed to real-environment acceptance; this is not a support claim. / 主机可进入真实环境验收；这不是支持声明。
     */
    READY_FOR_RUNTIME_VALIDATION,
    /**
     * The required cumulative CPU level is absent or needs separate review. / 缺少所需累积 CPU 级别或需要单独审阅。
     */
    REQUIRES_CPU_REVIEW,
    /**
     * Mandatory-access-control evidence requires review before mutation. / 强制访问控制证据需要在变更前审阅。
     */
    REQUIRES_SECURITY_REVIEW,
    /**
     * A discontinued CentOS family requires an explicit risk acknowledgement. / 停止维护的 CentOS 系列需要明确风险确认。
     */
    LEGACY_RISK_CONFIRMATION_REQUIRED,
    /**
     * The evidence does not meet the typed deployment matrix. / 证据不满足部署矩阵。
     */
    UNSUPPORTED
}
