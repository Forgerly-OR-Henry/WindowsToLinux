package gold.debug.windowstolinux.api.task;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public class ApiException extends Exception {
    private final Problem problem;
    public ApiException(Problem problem) { super(problem.message()); this.problem = problem; }
    public Problem problem() { return problem; }
}
