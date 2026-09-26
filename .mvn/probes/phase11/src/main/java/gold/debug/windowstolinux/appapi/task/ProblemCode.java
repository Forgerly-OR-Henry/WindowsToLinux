package gold.debug.windowstolinux.appapi.task;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public enum ProblemCode { INVALID_INPUT, UNAVAILABLE, INCOMPATIBLE, ACCESS_DENIED,
    BUSY, NOT_FOUND, IO_FAILURE, TIMEOUT, CANCELLED, UNKNOWN, CONTRACT_VIOLATION }
