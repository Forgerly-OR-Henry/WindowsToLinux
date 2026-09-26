package gold.debug.windowstolinux.appapi.task;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface TaskContext {
    UUID id();
    CancellationToken cancellation();
    void progress(String text, Optional<Double> fraction) throws ApiException;
    ResourceLease retain(String kind, String localId, String description) throws ApiException;
}
