package gold.debug.windowstolinux.appapi.secret;

import java.util.UUID;
import gold.debug.windowstolinux.appapi.server.ReferenceResolution;
import gold.debug.windowstolinux.appapi.server.ReferenceState;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskContext;

public interface SecretService {
    SecretRef create(String purpose, byte[] value, TaskContext task) throws ApiException;
    SecretReference reserve(SecretRef secret, String businessKey, TaskContext task) throws ApiException;
    void resolve(UUID referenceId, ReferenceResolution result, TaskContext task) throws ApiException;
    <T> T use(SecretRef secret, String purpose, TaskContext task, SecretConsumer<T> consumer)
        throws ApiException;
}
