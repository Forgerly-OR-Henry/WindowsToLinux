package gold.debug.windowstolinux.api.source;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.TaskContext;

public record SourceSelection(UUID id, String displayName) {}
