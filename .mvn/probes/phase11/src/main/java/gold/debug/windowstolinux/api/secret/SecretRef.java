package gold.debug.windowstolinux.api.secret;

import java.util.UUID;
import gold.debug.windowstolinux.api.server.ReferenceResolution;
import gold.debug.windowstolinux.api.server.ReferenceState;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.TaskContext;

public record SecretRef(String id, String purpose) {}
