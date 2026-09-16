package gold.debug.windowstolinux.shared.linux.distro;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan;

/** Fixed system preparation operations on the authenticated target. / 已认证目标上的固定系统准备操作。 */
public interface SelinuxEnvironmentPreparer {
    java.util.Optional<SelinuxPreparationPlan> inspect() throws LinuxOperationException;
    void prepareReboot(SelinuxPreparationPlan approved) throws LinuxOperationException;
    void enableEnforcement(SelinuxPreparationPlan approved) throws LinuxOperationException;
    void commitEnforcement(SelinuxPreparationPlan approved) throws LinuxOperationException;
}
