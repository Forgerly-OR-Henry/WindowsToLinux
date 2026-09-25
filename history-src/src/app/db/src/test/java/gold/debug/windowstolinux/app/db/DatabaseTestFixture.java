package gold.debug.windowstolinux.app.db;

import gold.debug.windowstolinux.app.db.entity.*;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

/** Creates database-only fixtures without invoking credential cryptography. */
public final class DatabaseTestFixture {
    public static void credential(DesktopPersistence db, String id) throws Exception {
        if (db.security().profile().isEmpty())
            db.security().initialize(new StoredSecurityProfile(false, "fixture-verifier", new byte[48], "AES256_GCM_V1",
                    new byte[12], "ARGON2ID_V1", new byte[16], "fixture-parameters", 1));
        if (db.security().find(id).isEmpty())
            try (var row = new StoredCredential(id, "PLAIN_V1", new byte[]{42}, null)) {
                db.security().save(row, false);
            }
    }

    public static void server(DesktopPersistence db, ServerIdentity identity) throws Exception {
        String key = "ssh/" + identity.id() + "/password";
        credential(db, key);
        if (db.servers().findServerProfile(identity.id()).isEmpty())
            db.servers().saveServerProfile(
                    new StoredServerProfile(identity.id(), identity.host(), identity.sshPort(), "tester", key));
        db.servers().saveServer(identity);
    }

    public static void server(DesktopPersistence db) throws Exception {
        server(db, new ServerIdentity("server", "example.test", 22, "SHA256:exampleFingerprint"));
    }
}
