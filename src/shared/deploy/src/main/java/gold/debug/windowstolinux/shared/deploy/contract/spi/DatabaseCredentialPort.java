package gold.debug.windowstolinux.shared.deploy.contract.spi;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import java.util.Optional;

/** Platform-owned immutable database credentials; returned character arrays belong to the caller. */
public interface DatabaseCredentialPort {
    Optional<SecretReference> latest(String identifier) throws Exception;
    char[] load(SecretReference reference) throws Exception;
    void save(SecretReference reference, char[] value) throws Exception;
}
