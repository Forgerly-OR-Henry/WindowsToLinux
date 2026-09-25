package gold.debug.windowstolinux.api.linux;

import java.time.Duration;
import java.util.Optional;
import java.nio.file.Path;
import gold.debug.windowstolinux.api.server.ServerRef;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.ApprovalGrant;
import gold.debug.windowstolinux.api.task.OperationResult;
import gold.debug.windowstolinux.api.task.TaskContext;

public interface RemoteSession extends AutoCloseable {
    RemoteFacts facts();
    OperationResult<CommandResult> execute(CommandRequest request, ApprovalGrant grant)
        throws ApiException;
    OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant)
        throws ApiException;
    OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant)
        throws ApiException;
    @Override void close() throws ApiException;
}
