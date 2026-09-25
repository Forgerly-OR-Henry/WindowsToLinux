package gold.debug.windowstolinux.api.task;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface TaskService {
    <T> TaskHandle<T> submit(TaskRequest request, TaskWork<T> work) throws ApiException;
    PageSlice<TaskSnapshot> list(Optional<String> cursor, int limit) throws ApiException;
    RecoveryRegistration registerRecovery(String taskType, TaskRecovery recovery) throws ApiException;
}
