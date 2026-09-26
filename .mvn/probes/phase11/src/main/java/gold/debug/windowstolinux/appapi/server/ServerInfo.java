package gold.debug.windowstolinux.appapi.server;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.PageSlice;
import gold.debug.windowstolinux.appapi.task.TaskContext;

public record ServerInfo(ServerRef ref, String name, String host, int port, String username,
    Optional<String> trustedFingerprint, Optional<Instant> observedAt) {}
