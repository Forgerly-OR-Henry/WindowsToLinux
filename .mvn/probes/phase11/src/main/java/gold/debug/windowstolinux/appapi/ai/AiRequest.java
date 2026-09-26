package gold.debug.windowstolinux.appapi.ai;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.TaskContext;

public record AiRequest(AiPurpose purpose, List<AiMessage> messages, int maxOutputTokens,
    Duration timeout, boolean jsonObjectResponse) {}
