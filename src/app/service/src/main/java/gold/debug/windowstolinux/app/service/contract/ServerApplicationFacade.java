package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Predicate;

/** Narrow application operations required by server management. / 服务器管理所需的窄应用操作。 */
public interface ServerApplicationFacade {
    java.util.List<ServerProfile> listServerProfiles() throws SQLException;
    void saveServerProfile(ServerProfile profile, CredentialStorageMode mode,
                           char[] masterPassword, char[] password) throws SQLException, SecretStoreException;

    Optional<ServerProfile> findServerProfile(String serverId) throws SQLException;

    ServerCapabilityFacts verifyServer(ServerProfile profile, CredentialStorageMode mode,
                                    char[] masterPassword, Predicate<String> confirmation)
            throws SecretStoreException, SQLException, LinuxOperationException;

    EnvironmentSetupResult prepareEnvironmentWithStoredPassword(
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation, boolean installationConfirmed)
            throws SecretStoreException, SQLException, LinuxOperationException;

    EnvironmentSetupResult prepareEnvironmentWithStoredPassword(
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation, boolean installationConfirmed,
            Predicate<gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan> systemConfirmation)
            throws SecretStoreException, SQLException, LinuxOperationException;
}
