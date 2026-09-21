package gold.debug.windowstolinux.shared.ai.collaboration;

/**
 * Authoritative deterministic decision supplied before optional model review. / 在可选模型复核前提供的权威确定性决策。
 */
public enum DeterministicDecision {
    /**
     * Deterministic checks allow the reviewed workflow to continue. / 确定性检查允许经审阅流程继续。
     */
    ALLOW,
    /**
     * Deterministic checks require a safe stop. / 确定性检查要求安全停止。
     */
    STOP
}
