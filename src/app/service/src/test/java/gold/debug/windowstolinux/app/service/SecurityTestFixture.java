package gold.debug.windowstolinux.app.service;

import java.util.*;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.secret.*;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import org.junit.jupiter.api.AfterEach;

/** Supplies scoped authorization for tests of non-security business behavior. */
public class SecurityTestFixture {
    private static final ThreadLocal<List<AutoCloseable>> RESOURCES = ThreadLocal.withInitial(ArrayList::new);

    private static final ThreadLocal<Map<DesktopPersistence, DesktopSecretStoreService>> STORES = ThreadLocal
            .withInitial(IdentityHashMap::new);
    private static char[] password() {
        return "test fixture password".toCharArray();
    }

    public static DesktopApplicationFacade unlocked(DesktopApplicationFacade facade) throws Exception {
        if (facade.securityState().configured())
            facade.unlock(password());
        else
            facade.setupAndUnlock(password(), password());
        RESOURCES.get().add(() -> {
            facade.closeSshRecovery();
            facade.closeSecurity();
        });
        return facade;
    }

    public static DesktopSecretStoreService secrets(DesktopPersistence db) {
        return STORES.get().computeIfAbsent(db, value -> {
            try {
                var security = new DesktopSecurityService(db.security());
                if (security.securityState().configured())
                    security.unlock(password());
                else
                    security.setupAndUnlock(password(), password());
                var operation = security.enterOperation();
                RESOURCES.get().add(() -> {
                    operation.close();
                    security.close();
                });
                return new DesktopSecretStoreService(security);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    public static SecretStore store(DesktopPersistence db) throws Exception {
        return secrets(db).open();
    }

    public static void credential(DesktopPersistence db, String id) throws Exception {
        try (var store = store(db)) {
            store.save(id, "fixture-secret".toCharArray());
        }
    }

    public static void server(DesktopPersistence db,
            gold.debug.windowstolinux.shared.model.server.ServerIdentity identity) throws Exception {
        String key = "ssh/" + identity.id() + "/password";
        credential(db, key);
        if (db.servers().findServerProfile(identity.id()).isEmpty())
            db.servers().saveServerProfile(new gold.debug.windowstolinux.app.db.entity.StoredServerProfile(
                    identity.id(), identity.host(), identity.sshPort(), "tester", key));
        db.servers().saveServer(identity);
    }

    @AfterEach
    public void releaseAuthorization() throws Exception {
        var resources = RESOURCES.get();
        Exception failure = null;
        for (int i = resources.size() - 1; i >= 0; i--)
            try {
                resources.get(i).close();
            } catch (Exception problem) {
                if (failure == null)
                    failure = problem;
                else
                    failure.addSuppressed(problem);
            }
        RESOURCES.remove();
        STORES.remove();
        if (failure != null)
            throw failure;
    }
}
