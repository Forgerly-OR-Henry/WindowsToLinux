package gold.debug.windowstolinux.web.task.model;

import java.time.Duration;

public record WebTaskPolicy(int workers, int maxActive, int maxEvents, Duration decisionTimeout, Duration shutdownTimeout, Duration forcedShutdownTimeout) {
    public WebTaskPolicy {
        if (workers < 1 || workers > 64 || maxActive < workers || maxEvents < 1 || decisionTimeout == null
                || decisionTimeout.isNegative() || decisionTimeout.isZero() || shutdownTimeout == null
                || shutdownTimeout.isNegative() || shutdownTimeout.isZero() || forcedShutdownTimeout == null
                || forcedShutdownTimeout.isNegative() || forcedShutdownTimeout.isZero()) throw new IllegalArgumentException("Invalid w2l.tasks limits");
    }
}
