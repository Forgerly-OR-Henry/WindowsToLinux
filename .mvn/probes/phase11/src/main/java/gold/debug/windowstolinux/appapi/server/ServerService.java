package gold.debug.windowstolinux.appapi.server;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.PageSlice;
import gold.debug.windowstolinux.appapi.task.TaskContext;

public interface ServerService {
    PageSlice<ServerInfo> list(Optional<String> cursor, int limit, TaskContext task) throws ApiException;
    ServerInfo get(ServerRef server, TaskContext task) throws ApiException;
    ServerReference reserve(ServerRef server, String businessKey, TaskContext task) throws ApiException;
    void resolve(UUID referenceId, ReferenceResolution result, TaskContext task) throws ApiException;
}
