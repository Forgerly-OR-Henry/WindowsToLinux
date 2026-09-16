package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.ai.AiProviderProfile;
import gold.debug.windowstolinux.app.service.ai.AiRoleAssignment;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleContext;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiRoleInvocationResult;

import java.sql.SQLException;
import java.util.Optional;

/** Narrow application operations required by the AI page. / AI 页面所需的窄应用操作。 */
public interface AiApplicationFacade {
    java.util.List<gold.debug.windowstolinux.app.service.ai.AiProviderSummary> listAiConfigurations() throws SQLException;
    void saveAiConfiguration(AiProviderProfile profile, String name, char[] master, char[] apiKey) throws SQLException, SecretStoreException;
    void setAiProviderEnabled(String id, boolean enabled) throws SQLException;
    void reorderAiProviders(java.util.List<String> ids) throws SQLException;
    void saveAiProviderProfile(AiProviderProfile profile, char[] masterPassword, char[] apiKey)
            throws SQLException, SecretStoreException;

    void assignAiRole(AiRoleAssignment assignment) throws SQLException;

    Optional<AiRoleInvocationResult> invokeAiRole(AiRoleContext context, char[] masterPassword)
            throws SQLException, SecretStoreException;
}
