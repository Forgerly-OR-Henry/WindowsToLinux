package gold.debug.windowstolinux.app.service.execution.lifecycle;

import java.time.Instant;
import java.util.Optional;

import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;

/**
 * Preserves full managed diagnostics while exposing the external runtime observation. / 保留完整受管诊断，同时提供外部运行时观测。
 *
 * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
 * @param observedAt observed at / 已观测时刻
 * @param managed managed / 受管
 */
public record ApplicationLifecycleResult(RuntimeState state, Instant observedAt, Optional<LifecycleOutcome> managed) {
}
