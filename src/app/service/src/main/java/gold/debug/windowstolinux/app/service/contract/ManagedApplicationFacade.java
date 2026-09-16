package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.execution.lifecycle.LifecycleOutcome;
import gold.debug.windowstolinux.app.service.execution.lifecycle.ManagedApplicationSnapshot;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;

import java.sql.SQLException;
import java.util.List;

/** Narrow application operations required by the managed-applications page. / 受管应用页面所需的窄应用操作。 */
public interface ManagedApplicationFacade {
    List<gold.debug.windowstolinux.app.service.server.ServerProfile> listServerProfiles() throws SQLException;
    List<gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationSummary> listApplications() throws SQLException;
    void saveApplicationPresentation(String key, String name, String category, String accessUrl) throws SQLException;
    gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationScan scanApplications(String serverId, char[] master,
            java.util.function.Predicate<String> confirmation) throws Exception;
    String adoptApplication(gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationScan scan,
            gold.debug.windowstolinux.shared.model.lifecycle.DiscoveredApplication application, char[] master,
            java.util.function.Predicate<String> confirmation) throws Exception;
    gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationLifecycleResult executeApplicationLifecycle(String key,
            LifecycleAction action, char[] master, java.util.function.Predicate<String> confirmation) throws Exception;
    List<ManagedApplicationSnapshot> listManagedApplicationSummaries() throws SQLException;

    LifecycleOutcome executePersistedLifecycleWithStoredPassword(
            String applicationId, LifecycleAction action, char[] masterPassword)
            throws SecretStoreException, SQLException;
}
