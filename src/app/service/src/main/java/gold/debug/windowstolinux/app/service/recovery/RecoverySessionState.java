package gold.debug.windowstolinux.app.service.recovery;

import java.util.*;

import gold.debug.windowstolinux.app.service.contract.definition.RecoverySnapshot;
import gold.debug.windowstolinux.shared.model.recovery.RecoveryAction;

/**
 * Worker-owned state; snapshots contain no raw terminal evidence. / 工作线程持有状态，快照不含原始终端证据。
 */
final class RecoverySessionState {
    /**
     * Stable identifier within the owning registry.
     * <p>所属登记表内的稳定标识。
     */
    final String id = UUID.randomUUID().toString();

    /**
     * Stage associated with the result or failure.
     * <p>结果或失败所属阶段。
     */
    RecoverySnapshot.State phase = RecoverySnapshot.State.STARTING;

    /**
     * Stable machine-readable classification code.
     * <p>稳定的机器可读分类码。
     */
    String code = "starting";

    /**
     * Explicit action selected for the current target.
     * <p>为当前目标显式选择的动作。
     */
    RecoveryAction action;

    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    gold.debug.windowstolinux.shared.model.failure.FailureDescriptor failure;

    /**
     * Token.
     * <p>令牌。
     */
    String token = "";

    /**
     * Marker.
     * <p>标记。
     */
    String marker = "";

    /**
     * Content identity used for independent verification.
     * <p>独立验证所用的内容身份。
     */
    String digest = "";

    /**
     * Generation.
     * <p>代次。
     */
    long generation;

    /**
     * Active nanos.
     * <p>活跃纳秒。
     */
    long activeNanos;

    /**
     * Next probe.
     * <p>下一探测。
     */
    long nextProbe;

    /**
     * Next observation.
     * <p>下一观测。
     */
    long nextObservation;

    /**
     * Number of recovery decisions consumed in the current active budget.
     * <p>当前活跃预算内已消耗的救援决策次数。
     */
    int decisions;

    /**
     * Unknown.
     * <p>未知。
     */
    boolean unknown;

    /**
     * Bound.
     * <p>边界。
     */
    boolean bound;

    /**
     * Attempts.
     * <p>尝试集合。
     */
    final Map<String, String> attempts = new LinkedHashMap<>();
    /**
     * Builds recovery snapshot from the supplied snapshot inputs.
     * <p>根据所提供快照输入构建恢复快照。
     *
     * @return recovery snapshot from the supplied snapshot inputs / 根据所提供快照输入构建恢复快照
     */
    RecoverySnapshot snapshot() {
        return new RecoverySnapshot(id, phase, code, Optional.ofNullable(action), token, unknown, decisions, attempts,
                Optional.ofNullable(failure));
    }

    /**
     * Revokes recovery session state.
     * <p>撤销恢复会话状态。
     */
    void revoke() {
        action = null;
        token = "";
    }

    /**
     * Tests the active predicate against the supplied evidence.
     * <p>根据所提供证据检查活跃条件。
     *
     * @return true when active predicate against the supplied evidence, false otherwise / 根据所提供证据检查活跃条件时为 true，否则为 false
     */
    boolean active() {
        return phase == RecoverySnapshot.State.OBSERVING || phase == RecoverySnapshot.State.EXECUTING;
    }
}
