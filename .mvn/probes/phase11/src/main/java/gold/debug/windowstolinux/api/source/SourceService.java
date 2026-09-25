package gold.debug.windowstolinux.api.source;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.TaskContext;

public interface SourceService {
    SourceSelection selectDirectory(String title, TaskContext task) throws ApiException;
    SourceFile read(SourceSelection selection, SourceReadRequest request, TaskContext task)
        throws ApiException;
    SourceSnapshot snapshot(SourceSelection selection, SnapshotRequest request, TaskContext task)
        throws ApiException;
}
