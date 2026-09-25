package gold.debug.windowstolinux.api.linux;

import java.time.Duration;
import java.util.Optional;
import java.nio.file.Path;
import gold.debug.windowstolinux.api.server.ServerRef;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.ApprovalGrant;
import gold.debug.windowstolinux.api.task.OperationResult;
import gold.debug.windowstolinux.api.task.TaskContext;

public record CommandRequest(String script, byte[] stdin, Duration timeout, long outputLimitBytes) {}
