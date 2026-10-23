package gold.debug.windowstolinux.app.db.init;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.app.windows.security.CurrentUserProtection;

/** Explicit offline, one-time maintenance. It never calls the normal platform startup/recovery path. */
public final class AiModelMaintenance {
    private AiModelMaintenance() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 3 || !Set.of("apply", "restore").contains(arguments[0])) {
            System.err.println("Usage: AiModelMaintenance apply|restore ABSOLUTE_DATA_ROOT ABSOLUTE_BACKUP_DIRECTORY");
            System.exit(2);
        }
        try {
            var nativeProtection = new CurrentUserProtection();
            var protection = new DatabaseProtection() {
                public byte[] protect(byte[] clear) {
                    return nativeProtection.protect(clear);
                }

                public byte[] unprotect(byte[] encrypted) {
                    return nativeProtection.unprotect(encrypted);
                }
            };
            var root = Path.of(arguments[1]);
            var backup = Path.of(arguments[2]);
            if (arguments[0].equals("apply"))
                apply(root, backup, protection, point -> {
                });
            else
                restore(root, backup, protection);
            System.out.println("COMPLETED " + arguments[0] + "; paired backup and receipt retained at " + backup);
        } catch (Exception failure) {
            // Driver diagnostics may contain SQL values. Keep stdout/stderr free of row contents and key material.
            System.err.println("FAILED " + failure.getClass().getSimpleName()
                + "; no automatic startup or retry; retain the paired backup and use explicit restore if needed.");
            System.exit(1);
        }
    }

    static void apply(Path root, Path backup, DatabaseProtection protection, Hook hook) throws Exception {
        OfflineSchemaMaintenance.apply(root, backup, protection, new AiModelData(), hook::at);
    }

    static void restore(Path root, Path backup, DatabaseProtection protection) throws Exception {
        OfflineSchemaMaintenance.restore(root, backup, protection, new AiModelData());
    }

    interface Hook { void at(String point) throws Exception; }
}
