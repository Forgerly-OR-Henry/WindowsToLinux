package gold.debug.windowstolinux.app.service.contract.definition;

import java.util.Optional;

import gold.debug.windowstolinux.shared.model.recovery.RecoveryAction;

/**
 * Transient UI state; command contents must not be logged or persisted. / 临时界面状态，命令内容不得记录日志或持久化。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
 * @param messageCode stable safe message code describing the current lifecycle transition / 描述当前生命周期转换的稳定安全消息码
 * @param action explicit action selected for the current target / 为当前目标显式选择的动作
 * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
 * @param resultUnknown whether a submitted terminal action lacks a verified completion result / 已提交终端动作是否缺少已验证完成结果
 * @param decisions number of recovery decisions consumed in the current active budget / 当前活跃预算内已消耗的救援决策次数
 * @param modelAttempts provider identifiers mapped to safe attempt classifications; raw model responses are excluded / 提供者标识到安全尝试分类的映射；不包含模型原始响应
 * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
 */
public record RecoverySnapshot(String id, State state, String messageCode, Optional<RecoveryAction> action,
        String confirmation, boolean resultUnknown, int decisions, java.util.Map<String, String> modelAttempts,
        Optional<gold.debug.windowstolinux.shared.model.failure.FailureDescriptor> failure) {
    /**
     * Copies non-secret attempt classifications; never stores response text. / 复制无秘密的调用分类，不保存响应原文。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @param messageCode stable safe message code describing the current lifecycle transition / 描述当前生命周期转换的稳定安全消息码
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @param resultUnknown whether a submitted terminal action lacks a verified completion result / 已提交终端动作是否缺少已验证完成结果
     * @param decisions number of recovery decisions consumed in the current active budget / 当前活跃预算内已消耗的救援决策次数
     * @param modelAttempts provider identifiers mapped to safe attempt classifications; raw model responses are excluded / 提供者标识到安全尝试分类的映射；不包含模型原始响应
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RecoverySnapshot {
        modelAttempts = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(modelAttempts));
        failure = java.util.Objects.requireNonNull(failure, "failure");
    }
    /**
     * Explicit rescue lifecycle. / 明确的救援生命周期。
     */
    public enum State {
        /**
         * The recovery worker is acquiring its server lock and checking the initial SSH state.
         * <p>救援工作线程正在获取服务器锁并检查初始 SSH 状态。
         */
        STARTING,
        /**
         * The browser is ready but the user must bind the intended terminal before observation.
         * <p>浏览器已就绪，但用户须先绑定目标终端才能观测。
         */
        WAITING_TERMINAL,
        /**
         * The bound terminal is being observed to prepare classified recovery advice.
         * <p>正在观测已绑定终端，以准备分类救援建议。
         */
        OBSERVING,
        /**
         * An exact revocable action is waiting for explicit user approval.
         * <p>精确且可撤销的动作正在等待用户显式批准。
         */
        AWAITING_CONFIRMATION,
        /**
         * The approved terminal action is being submitted or observed for its result.
         * <p>正在提交已批准终端动作或观测其结果。
         */
        EXECUTING,
        /**
         * Automatic recovery is paused and prior action authorization has been revoked.
         * <p>自动救援已暂停，且此前动作授权已撤销。
         */
        PAUSED,
        /**
         * Automatic work stopped after interruption or a non-retryable failure; user handoff is required.
         * <p>自动工作因中断或不可重试失败停止，需要交接给用户。
         */
        INTERRUPTED,
        /**
         * A fresh authenticated SSH check verified that normal access has recovered.
         * <p>新的已认证 SSH 检查已验证正常访问恢复。
         */
        RECOVERED,
        /**
         * Cancellation ended the session and no further actions are accepted.
         * <p>取消已结束会话，不再接受后续动作。
         */
        CANCELLED,
        /**
         * The session ended with a classified failure requiring user attention.
         * <p>会话因需要用户处理的分类失败而结束。
         */
        FAILED
    }

    /**
     * True only after the worker has stopped accepting actions. / 仅在工作线程停止接收操作后返回真。
     *
     * @return true for RECOVERED, CANCELLED or FAILED; false for all other lifecycle states / RECOVERED、CANCELLED 或 FAILED 时为 true；其他生命周期状态为 false
     */
    public boolean ended() {
        return state == State.RECOVERED || state == State.CANCELLED || state == State.FAILED;
    }

    /**
     * Returns the diagnostic text representation of this object.
     * <p>返回当前对象的诊断文本表示。
     *
     * @return the diagnostic text representation of this object / 当前对象的诊断文本表示
     */
    @Override
    public String toString() {
        return "RecoverySnapshot[" + id + "," + state + "]";
    }
}
