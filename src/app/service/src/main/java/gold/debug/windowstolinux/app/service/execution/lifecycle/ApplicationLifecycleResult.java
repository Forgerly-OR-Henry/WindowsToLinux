package gold.debug.windowstolinux.app.service.execution.lifecycle;

import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import java.time.Instant;
import java.util.Optional;

/** Preserves full managed diagnostics while exposing the external runtime observation. / 保留完整受管诊断，同时提供外部运行时观测。 */
public record ApplicationLifecycleResult(RuntimeState state, Instant observedAt, Optional<LifecycleOutcome> managed) { }
