package gold.debug.windowstolinux.shared.linux.runtime;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.lifecycle.*;

/** Bounded discovery and identity-checked lifecycle of existing applications. / 既有应用的有界发现与身份复核生命周期。 */
public interface ExternalApplicationPort {
    ExternalApplicationScan scan() throws LinuxOperationException;
    DiscoveredApplication execute(ExternalApplicationTarget target, LifecycleAction action) throws LinuxOperationException;
}
