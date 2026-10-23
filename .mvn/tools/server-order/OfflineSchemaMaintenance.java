package gold.debug.windowstolinux.app.db.init;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.app.windows.security.CurrentUserProtection;

/** Explicit offline, one-time maintenance. It never calls the normal platform startup/recovery path. */
final class OfflineSchemaMaintenance {
    private OfflineSchemaMaintenance() {
    }

    static void apply(Path requestedRoot, Path requestedBackup, DatabaseProtection protection, OfflineTableChange schema, Hook hook) throws Exception {
        var root = ServerOrderFiles.root(requestedRoot);
        ServerOrderFiles.regular(root.resolve(ServerOrderFiles.DATABASE));
        ServerOrderFiles.regular(root.resolve(ServerOrderFiles.ACCESS));
        try (var ownership = ServerOrderFiles.own(root)) {
            var backup = ServerOrderFiles.newBackup(root, requestedBackup);
            var receipt = backup(root, backup, protection, schema);
            try {
                hook.at("after-backup");
                var work = Files.createDirectory(backup.resolve("work"));
                ServerOrderFiles.copy(backup.resolve(ServerOrderFiles.DATABASE), work.resolve(ServerOrderFiles.DATABASE));
                ServerOrderFiles.copy(backup.resolve(ServerOrderFiles.ACCESS), work.resolve(ServerOrderFiles.ACCESS));
                rebuild(work, protection, schema, hook);
                receipt.setProperty("afterHash", ServerOrderFiles.hash(work.resolve(ServerOrderFiles.DATABASE)));
                receipt.setProperty("state", "VALIDATED");
                ServerOrderFiles.receipt(backup, receipt);
                hook.at("before-publish");
                ServerOrderFiles.publish(root, work.resolve(ServerOrderFiles.DATABASE),
                    receipt.getProperty("beforeHash"), receipt.getProperty("accessHash"), receipt.getProperty("afterHash"));
                hook.at("after-publish");
                if (!ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE)).equals(receipt.getProperty("afterHash")))
                    throw new IOException("The published image differs from the validated copy");
                receipt.setProperty("state", "PUBLISHED");
                ServerOrderFiles.receipt(backup, receipt);
                cleanupWork(backup);
            } catch (Exception failure) {
                try {
                    restoreOwned(root, backup, receipt, protection);
                    receipt.setProperty("state", "FAILED");
                    ServerOrderFiles.receipt(backup, receipt);
                    cleanupWork(backup);
                } catch (Exception restoration) {
                    failure.addSuppressed(restoration);
                }
                throw failure;
            }
        }
    }

    private static Properties backup(Path root, Path backup, DatabaseProtection protection, OfflineTableChange schema)
        throws Exception {
        var receipt = new Properties();
        try (var database = ServerOrderFiles.exclusive(root.resolve(ServerOrderFiles.DATABASE), false);
             var access = ServerOrderFiles.exclusive(root.resolve(ServerOrderFiles.ACCESS), false)) {
            ServerOrderFiles.copy(database, backup.resolve(ServerOrderFiles.DATABASE));
            ServerOrderFiles.copy(access, backup.resolve(ServerOrderFiles.ACCESS));
            receipt.setProperty("beforeHash", ServerOrderFiles.hash(database));
            receipt.setProperty("accessHash", ServerOrderFiles.hash(access));
        }
        if (!receipt.getProperty("beforeHash").equals(ServerOrderFiles.hash(backup.resolve(ServerOrderFiles.DATABASE)))
            || !receipt.getProperty("accessHash").equals(ServerOrderFiles.hash(backup.resolve(ServerOrderFiles.ACCESS))))
            throw new IOException("Paired backup verification failed");
        try (var material = DatabaseBootstrap.load(backup, protection)) {
            if (material.fresh())
                throw new IOException("Maintenance never initializes a database");
            receipt.setProperty("generation", material.generation().toString());
        }
        receipt.setProperty("format", "1");
        receipt.setProperty("root", root.toString());
        receipt.setProperty("oldDdl", schema.oldSha());
        receipt.setProperty("newDdl", schema.newSha());
        receipt.setProperty("afterHash", "");
        receipt.setProperty("state", "BACKED_UP");
        ServerOrderFiles.receipt(backup, receipt);
        return receipt;
    }

    private static void rebuild(Path work, DatabaseProtection protection, OfflineTableChange schema, Hook hook) throws Exception {
        try (var material = DatabaseBootstrap.load(work, protection);
             var connection = ServerOrderData.open(work, material)) {
            if (material.fresh())
                throw new IOException("Maintenance never initializes a database");
            schema.rebuild(connection, material.generation(), hook);
        }
    }

    static void restore(Path requestedRoot, Path requestedBackup, DatabaseProtection protection, OfflineTableChange schema) throws Exception {
        var root = ServerOrderFiles.root(requestedRoot);
        var backup = ServerOrderFiles.root(requestedBackup);
        try (var ownership = ServerOrderFiles.own(root)) {
            var receipt = ServerOrderFiles.readReceipt(root, backup);
            if (!receipt.getProperty("oldDdl").equals(schema.oldSha())
                || !receipt.getProperty("newDdl").equals(schema.newSha()))
                throw new IOException("Receipt does not match the pinned maintenance operation");
            restoreOwned(root, backup, receipt, protection);
            receipt.setProperty("state", "RESTORED");
            ServerOrderFiles.receipt(backup, receipt);
            cleanupWork(backup);
        }
    }

    private static void restoreOwned(Path root, Path backup, Properties receipt, DatabaseProtection protection) throws Exception {
        // Re-verify the saved pair, even on the ordinary exception path.
        ServerOrderFiles.readReceipt(root, backup);
        if (!ServerOrderFiles.hash(root.resolve(ServerOrderFiles.ACCESS)).equals(receipt.getProperty("accessHash")))
            throw new IOException("Bootstrap changed; refuse to overwrite an unrecognized database pair");
        try (var material = DatabaseBootstrap.load(root, protection)) {
            if (material.fresh() || !material.generation().toString().equals(receipt.getProperty("generation")))
                throw new IOException("The original protected generation changed");
        }
        var current = ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE));
        if (current.equals(receipt.getProperty("beforeHash")))
            return;
        if (receipt.getProperty("afterHash").isEmpty() || !current.equals(receipt.getProperty("afterHash")))
            throw new IOException("Original image is neither the saved original nor the exact validated result; refuse rollback");
        var temporary = root.resolve(".server-order-restore-" + UUID.randomUUID() + ".mv.db");
        try {
            ServerOrderFiles.copy(backup.resolve(ServerOrderFiles.DATABASE), temporary);
            if (!ServerOrderFiles.hash(temporary).equals(receipt.getProperty("beforeHash")))
                throw new IOException("Restore copy failed integrity verification");
            ServerOrderFiles.publish(root, temporary, current, receipt.getProperty("accessHash"), receipt.getProperty("beforeHash"));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void cleanupWork(Path backup) throws IOException {
        var work = backup.resolve("work");
        if (!Files.exists(work, LinkOption.NOFOLLOW_LINKS))
            return;
        if (!work.equals(ServerOrderFiles.root(work)))
            throw new IOException("Unsafe working directory");
        for (var name : List.of(ServerOrderFiles.DATABASE, ServerOrderFiles.ACCESS)) {
            var file = work.resolve(name);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                ServerOrderFiles.regular(file);
                Files.delete(file);
            }
        }
        Files.delete(work);
    }

    interface Hook {
        void at(String point) throws Exception;
    }
}
