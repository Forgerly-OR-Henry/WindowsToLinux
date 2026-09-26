package gold.debug.windowstolinux.appapi.task;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ManagedProcess extends AutoCloseable {
    UUID id();
    CompletionStage<OperationResult<ProcessMessage>> request(ProcessMessage message,
        Duration timeout) throws ApiException;
    void requestCancel() throws ApiException;
    CompletionStage<Integer> exitCode();
    @Override void close() throws ApiException;
}
