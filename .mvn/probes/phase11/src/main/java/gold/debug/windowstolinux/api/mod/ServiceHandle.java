package gold.debug.windowstolinux.api.mod;

import java.util.Optional;
import gold.debug.windowstolinux.api.ai.AiService;
import gold.debug.windowstolinux.api.db.ModDatabase;
import gold.debug.windowstolinux.api.linux.LinuxService;
import gold.debug.windowstolinux.api.secret.SecretService;
import gold.debug.windowstolinux.api.server.ServerService;
import gold.debug.windowstolinux.api.source.SourceService;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.InteractionService;
import gold.debug.windowstolinux.api.task.LogService;
import gold.debug.windowstolinux.api.task.ProcessService;
import gold.debug.windowstolinux.api.task.TaskContext;
import gold.debug.windowstolinux.api.task.TaskService;
import gold.debug.windowstolinux.api.ui.UiService;

public interface ServiceHandle<T> extends AutoCloseable {
    <R> R call(TaskContext task, ServiceCall<T, R> call) throws ApiException;
    @Override void close() throws ApiException;
}
