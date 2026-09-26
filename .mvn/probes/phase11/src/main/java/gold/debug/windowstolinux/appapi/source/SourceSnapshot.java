package gold.debug.windowstolinux.appapi.source;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskContext;

public interface SourceSnapshot extends AutoCloseable {
    UUID id();
    String sha256();
    Path directory();
    @Override void close() throws ApiException;
}
