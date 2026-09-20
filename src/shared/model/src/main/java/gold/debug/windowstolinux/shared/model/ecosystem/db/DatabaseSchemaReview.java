package gold.debug.windowstolinux.shared.model.ecosystem.db;

import java.util.*;

/** A source-bound result of verified initialization or separately approved existing-schema work. / 与源码绑定的验证结果，来自已验证初始化或单独获批的现有模式操作。 */
public record DatabaseSchemaReview(String inspectionSha256, Set<String> databaseIds, Set<String> initializedSqlPaths,
                                   boolean newOwnedDatabaseInitialized, boolean existingSchemaChangeApproved,
                                   boolean frameworkMigrationsDisabled, boolean firstDeploymentInitializationPlanned) {
    public DatabaseSchemaReview(String inspectionSha256, Set<String> databaseIds, Set<String> initializedSqlPaths,
            boolean newOwnedDatabaseInitialized, boolean existingSchemaChangeApproved, boolean frameworkMigrationsDisabled) {
        this(inspectionSha256,databaseIds,initializedSqlPaths,newOwnedDatabaseInitialized,existingSchemaChangeApproved,frameworkMigrationsDisabled,false);
    }
    public DatabaseSchemaReview {
        if (inspectionSha256 == null || !inspectionSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("schema review must bind exact inspected source");
        databaseIds = Set.copyOf(databaseIds); initializedSqlPaths = Set.copyOf(initializedSqlPaths);
        if (databaseIds.isEmpty() || databaseIds.size() > 16 || initializedSqlPaths.size() > 64
                || !newOwnedDatabaseInitialized && !existingSchemaChangeApproved && !firstDeploymentInitializationPlanned) throw new IllegalArgumentException("schema review requires verified DB work");
    }
}
