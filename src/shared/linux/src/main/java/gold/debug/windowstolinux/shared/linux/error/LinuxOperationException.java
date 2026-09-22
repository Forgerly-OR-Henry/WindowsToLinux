package gold.debug.windowstolinux.shared.linux.error;

import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

/**
 * A structured connection, protocol, transfer or controlled-operation failure. / 结构化连接、协议、传输或受控操作失败。
 */
public final class LinuxOperationException extends Exception implements FailureCarrier {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final FailureDescriptor failure;

    /**
     * Completed environment.
     * <p>已完成环境。
     */
    private final gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult completedEnvironment;

    /**
     * Environment not started.
     * <p>环境未已启动。
     */
    private final boolean environmentNotStarted;

    /**
     * Creates a Linux failure occurrence. / 创建一次 Linux 失败。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     */
    public LinuxOperationException(FailureDescriptor failure, Throwable cause) {
        this(failure, cause, null, false);
    }

    /**
     * Validates and binds the inputs required by linux operation exception.
     * <p>校验并绑定Linux操作异常所需输入。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @param completed completed / 已完成
     * @param notStarted not started / 未已启动
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private LinuxOperationException(FailureDescriptor failure, Throwable cause,
            gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult completed, boolean notStarted) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
        this.completedEnvironment = completed;
        this.environmentNotStarted = notStarted;
    }

    /**
     * Creates a typed Linux failure. / 创建类型化 Linux 失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return a typed Linux failure / 类型化 Linux 失败
     */
    public static LinuxOperationException create(LinuxOperationFailureType type, String diagnostic) {
        return create(type, Map.of(), diagnostic, null);
    }

    /**
     * Creates a typed Linux failure with its original cause. / 创建带原始原因的类型化 Linux 失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a typed Linux failure with its original cause / 带原始原因的类型化 Linux 失败
     */
    public static LinuxOperationException create(LinuxOperationFailureType type, String diagnostic, Throwable cause) {
        return create(type, Map.of(), diagnostic, cause);
    }

    /**
     * Creates a typed Linux failure with safe message arguments. / 创建带安全消息参数的类型化 Linux 失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return a typed Linux failure with safe message arguments / 带安全消息参数的类型化 Linux 失败
     */
    public static LinuxOperationException create(LinuxOperationFailureType type, Map<String, ?> arguments,
            String diagnostic) {
        return create(type, arguments, diagnostic, null);
    }

    /**
     * Creates a fully described Linux failure. / 创建完整描述的 Linux 失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a fully described Linux failure / 完整描述的 Linux 失败
     */
    public static LinuxOperationException create(LinuxOperationFailureType type, Map<String, ?> arguments,
            String diagnostic, Throwable cause) {
        return new LinuxOperationException(
                FailureDescriptor.create(type, OperationIdentity.create(), arguments, diagnostic), cause);
    }

    /**
     * Carries proven completed installation facts when only its fresh reconnect failed. / 仅重新连接失败时携带已完成安装的事实。
     *
     * @param completed completed / 已完成
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return constructed or resolved linux operation exception / 构造或解析得到的Linux操作异常
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static LinuxOperationException afterEnvironmentPreparation(
            gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult completed,
            LinuxOperationException failure) {
        return new LinuxOperationException(failure.failure(), failure, Objects.requireNonNull(completed), false);
    }

    /**
     * Records a connection failure before any ordinary installation was submitted. / 记录尚未提交普通安装前的连接失败。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return constructed or resolved linux operation exception / 构造或解析得到的Linux操作异常
     */
    public static LinuxOperationException beforeEnvironmentPreparation(LinuxOperationException failure) {
        return new LinuxOperationException(failure.failure(), failure, null, true);
    }

    /**
     * Identifies a proven unstarted ordinary installation. / 标识已证明尚未开始的普通安装。
     *
     * @return true when identifies a proven unstarted ordinary installation, false otherwise / 标识已证明尚未开始的普通安装时为 true，否则为 false
     */
    public boolean environmentNotStarted() {
        return environmentNotStarted;
    }

    /**
     * Never implies that an unfinished installation is safe to repeat. / 不表示未完成的安装可以安全重放。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    public java.util.Optional<gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult> completedEnvironment() {
        return java.util.Optional.ofNullable(completedEnvironment);
    }

    /**
     * Returns structured failure occurrence retained for safe reporting.
     * <p>返回保留用于安全报告的结构化失败实例。
     *
     * @return structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    @Override
    public FailureDescriptor failure() {
        return failure;
    }
}
