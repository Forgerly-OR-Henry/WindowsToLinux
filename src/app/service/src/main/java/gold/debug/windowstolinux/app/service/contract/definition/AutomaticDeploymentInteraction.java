package gold.debug.windowstolinux.app.service.contract.definition;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** UI-independent questions; implementations marshal dialogs onto their UI thread. */
public interface AutomaticDeploymentInteraction {
    /** Requires explicit confirmation of backup, downtime and replacement for the exact displayed instance. */
    default boolean confirmDatabaseReplacement(java.util.Map<String, ?> details) {
        return confirm("db.replaceConfirmed", details);
    }
    /** Requests non-secret input; empty means cancel the current operation. */
    Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields);
    /** Requests explicit approval for a concrete high-risk operation. */
    boolean confirm(String messageKey, Map<String, ?> details);
    /** Requests a short-lived secret, which the caller must clear. */
    char[] requestSecret(String messageKey);
}
