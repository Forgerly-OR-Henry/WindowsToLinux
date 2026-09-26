package gold.debug.windowstolinux.appapi.mod;

import java.util.Optional;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.secret.SecretService;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.source.SourceService;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.InteractionService;
import gold.debug.windowstolinux.appapi.task.LogService;
import gold.debug.windowstolinux.appapi.task.ProcessService;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.ui.UiService;

public interface ServiceHandle<T> extends AutoCloseable {
    <R> R call(TaskContext task, ServiceCall<T, R> call) throws ApiException;
    @Override void close() throws ApiException;
}
