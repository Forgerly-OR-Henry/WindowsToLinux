package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.execution.lifecycle.LifecycleOutcome;
import gold.debug.windowstolinux.app.service.execution.lifecycle.ManagedApplicationSnapshot;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;

import java.sql.SQLException;
import java.util.List;

/** Narrow application operations required by the managed-applications page. / 受管应用页面所需的窄应用操作。 */
public interface ManagedApplicationFacade {
    List<ManagedApplicationSnapshot> listManagedApplicationSummaries() throws SQLException;

    LifecycleOutcome executePersistedLifecycleWithStoredPassword(
            String applicationId, LifecycleAction action, char[] masterPassword)
            throws SecretStoreException, SQLException;
}
