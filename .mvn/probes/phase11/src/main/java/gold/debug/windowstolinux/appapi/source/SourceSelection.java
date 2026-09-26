package gold.debug.windowstolinux.appapi.source;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskContext;

public record SourceSelection(UUID id, String displayName) {}
