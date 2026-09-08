package gold.debug.windowstolinux.shared.model.ecosystem.db;

import java.util.*;

/** A source-bound result of verified initialization or separately approved existing-schema work. */
public record DatabaseSchemaReview(String inspectionSha256, Set<String> databaseIds, Set<String> initializedSqlPaths,
                                   boolean newOwnedDatabaseInitialized, boolean existingSchemaChangeApproved,
                                   boolean frameworkMigrationsDisabled) {
    public DatabaseSchemaReview {
        if (inspectionSha256 == null || !inspectionSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("schema review must bind exact inspected source");
        databaseIds = Set.copyOf(databaseIds); initializedSqlPaths = Set.copyOf(initializedSqlPaths);
        if (databaseIds.isEmpty() || databaseIds.size() > 16 || initializedSqlPaths.size() > 64
                || !newOwnedDatabaseInitialized && !existingSchemaChangeApproved) throw new IllegalArgumentException("schema review requires verified DB work");
    }
}
