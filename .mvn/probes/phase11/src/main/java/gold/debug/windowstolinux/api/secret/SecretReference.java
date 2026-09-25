package gold.debug.windowstolinux.api.secret;

import java.util.UUID;
import gold.debug.windowstolinux.api.server.ReferenceResolution;
import gold.debug.windowstolinux.api.server.ReferenceState;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.TaskContext;

public record SecretReference(UUID id, SecretRef secret, String businessKey, ReferenceState state) {}
