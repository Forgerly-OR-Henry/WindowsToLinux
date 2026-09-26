package gold.debug.windowstolinux.appapi.source;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskContext;

public record SnapshotRequest(List<String> relativePaths, long maxTotalBytes) {}
