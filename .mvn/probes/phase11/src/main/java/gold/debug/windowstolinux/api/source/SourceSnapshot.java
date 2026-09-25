package gold.debug.windowstolinux.api.source;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.TaskContext;

public interface SourceSnapshot extends AutoCloseable {
    UUID id();
    String sha256();
    Path directory();
    @Override void close() throws ApiException;
}
