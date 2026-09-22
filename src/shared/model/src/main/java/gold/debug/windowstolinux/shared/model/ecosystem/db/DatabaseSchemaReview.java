package gold.debug.windowstolinux.shared.model.ecosystem.db;

import java.util.*;

/**
 * A source-bound result of verified initialization or separately approved existing-schema work. / 与源码绑定的验证结果，来自已验证初始化或单独获批的现有模式操作。
 *
 * @param inspectionSha256 inspection sha 256 / 检查SHA256
 * @param databaseIds database ids / 数据库标识集合
 * @param initializedSqlPaths initialized sql paths / 已初始化SQL路径集合
 * @param newOwnedDatabaseInitialized new owned database initialized / 新已持有数据库已初始化
 * @param existingSchemaChangeApproved existing schema change approved / 既有结构变更已批准
 * @param frameworkMigrationsDisabled framework migrations disabled / 框架迁移集合已禁用
 * @param firstDeploymentInitializationPlanned first deployment initialization planned / 首次部署初始化已计划
 */
public record DatabaseSchemaReview(String inspectionSha256, Set<String> databaseIds, Set<String> initializedSqlPaths,
        boolean newOwnedDatabaseInitialized, boolean existingSchemaChangeApproved, boolean frameworkMigrationsDisabled,
        boolean firstDeploymentInitializationPlanned) {
    /**
     * Initializes database schema review through its shared constructor contract.
     * <p>通过共享构造契约初始化数据库结构审阅。
     *
     * @param inspectionSha256 inspection sha 256 / 检查SHA256
     * @param databaseIds database ids / 数据库标识集合
     * @param initializedSqlPaths initialized sql paths / 已初始化SQL路径集合
     * @param newOwnedDatabaseInitialized new owned database initialized / 新已持有数据库已初始化
     * @param existingSchemaChangeApproved existing schema change approved / 既有结构变更已批准
     * @param frameworkMigrationsDisabled framework migrations disabled / 框架迁移集合已禁用
     */
    public DatabaseSchemaReview(String inspectionSha256, Set<String> databaseIds, Set<String> initializedSqlPaths,
            boolean newOwnedDatabaseInitialized, boolean existingSchemaChangeApproved,
            boolean frameworkMigrationsDisabled) {
        this(inspectionSha256, databaseIds, initializedSqlPaths, newOwnedDatabaseInitialized,
                existingSchemaChangeApproved, frameworkMigrationsDisabled, false);
    }

    /**
     * Validates and binds the inputs required by database schema review.
     * <p>校验并绑定数据库结构审阅所需输入。
     *
     * @param inspectionSha256 inspection sha 256 / 检查SHA256
     * @param databaseIds database ids / 数据库标识集合
     * @param initializedSqlPaths initialized sql paths / 已初始化SQL路径集合
     * @param newOwnedDatabaseInitialized new owned database initialized / 新已持有数据库已初始化
     * @param existingSchemaChangeApproved existing schema change approved / 既有结构变更已批准
     * @param frameworkMigrationsDisabled framework migrations disabled / 框架迁移集合已禁用
     * @param firstDeploymentInitializationPlanned first deployment initialization planned / 首次部署初始化已计划
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public DatabaseSchemaReview {
        if (inspectionSha256 == null || !inspectionSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("schema review must bind exact inspected source");
        databaseIds = Set.copyOf(databaseIds);
        initializedSqlPaths = Set.copyOf(initializedSqlPaths);
        if (databaseIds.isEmpty() || databaseIds.size() > 16 || initializedSqlPaths.size() > 64
                || !newOwnedDatabaseInitialized && !existingSchemaChangeApproved
                        && !firstDeploymentInitializationPlanned)
            throw new IllegalArgumentException("schema review requires verified DB work");
    }
}
