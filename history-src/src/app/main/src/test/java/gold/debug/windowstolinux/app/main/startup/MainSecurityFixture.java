package gold.debug.windowstolinux.app.main.startup;

import java.util.ArrayList;
import java.util.List;

import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Owns unlocked sessions only in isolated acceptance databases. / 仅为隔离验收数据库持有解锁会话。 */
final class MainSecurityFixture implements AfterEachCallback {
    private static final ThreadLocal<List<DesktopApplicationFacade>> SERVICES = ThreadLocal.withInitial(ArrayList::new);
    static DesktopApplicationFacade unlocked(DesktopApplicationFacade service) throws Exception {
        char[] password = "isolated-main-test-password".toCharArray();
        if (service.securityState().configured())
            service.unlock(password);
        else
            service.setupAndUnlock(password, password.clone());
        SERVICES.get().add(service);
        return service;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        try {
            for (var service : SERVICES.get()) {
                service.closeSshRecovery();
                service.closeSecurity();
            }
        } finally {
            SERVICES.remove();
        }
    }
}
