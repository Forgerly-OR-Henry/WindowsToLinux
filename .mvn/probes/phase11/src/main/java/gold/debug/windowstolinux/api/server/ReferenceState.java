package gold.debug.windowstolinux.api.server;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.PageSlice;
import gold.debug.windowstolinux.api.task.TaskContext;

public enum ReferenceState { RESERVED, ACTIVE, UNKNOWN }
