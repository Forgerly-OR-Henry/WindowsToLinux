package gold.debug.windowstolinux.appapi.secret;

import java.util.UUID;
import gold.debug.windowstolinux.appapi.server.ReferenceResolution;
import gold.debug.windowstolinux.appapi.server.ReferenceState;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskContext;

public record SecretRef(String id, String purpose) {}
