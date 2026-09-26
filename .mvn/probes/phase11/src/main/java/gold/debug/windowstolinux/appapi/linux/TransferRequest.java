package gold.debug.windowstolinux.appapi.linux;

import java.time.Duration;
import java.util.Optional;
import java.nio.file.Path;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.TaskContext;

public record TransferRequest(Path localPath, String remoteAbsolutePath, long maxBytes) {}
