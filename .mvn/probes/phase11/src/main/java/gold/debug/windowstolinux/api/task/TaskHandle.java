package gold.debug.windowstolinux.api.task;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface TaskHandle<T> {
    UUID id();
    TaskSnapshot snapshot();
    CompletionStage<OperationResult<T>> completion();
    void requestCancel() throws ApiException;
}
