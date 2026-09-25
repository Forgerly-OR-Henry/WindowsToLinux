package gold.debug.windowstolinux.api.secret;

import java.util.UUID;
import gold.debug.windowstolinux.api.server.ReferenceResolution;
import gold.debug.windowstolinux.api.server.ReferenceState;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.TaskContext;

public interface SecretService {
    SecretRef create(String purpose, byte[] value, TaskContext task) throws ApiException;
    SecretReference reserve(SecretRef secret, String businessKey, TaskContext task) throws ApiException;
    void resolve(UUID referenceId, ReferenceResolution result, TaskContext task) throws ApiException;
    <T> T use(SecretRef secret, String purpose, TaskContext task, SecretConsumer<T> consumer)
        throws ApiException;
}
