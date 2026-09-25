package gold.debug.windowstolinux.api.ai;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.OperationResult;
import gold.debug.windowstolinux.api.task.TaskContext;

public interface AiService {
    OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException;
}
