package gold.debug.windowstolinux.shared.standard.deploy.contract;

import java.util.*;

import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview;

/**
 * Verified non-secret database inputs to the existing deployment transaction. / 现有部署事务使用的已验证非秘密数据库输入。
 *
 * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
 * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
 * @param bindings bindings / 绑定集合
 * @param schemaReview schema review / 结构审阅
 */
public record AutomaticDatabasePreparation(List<ConfigurationEntry> configuration, List<SecretReference> secrets,
        List<ManagedDatabaseBinding> bindings, Optional<DatabaseSchemaReview> schemaReview) {
    /**
     * Validates and binds the inputs required by automatic database preparation.
     * <p>校验并绑定自动数据库准备所需输入。
     *
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param bindings bindings / 绑定集合
     * @param schemaReview schema review / 结构审阅
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public AutomaticDatabasePreparation {
        configuration = List.copyOf(configuration);
        secrets = List.copyOf(secrets);
        bindings = List.copyOf(bindings);
        schemaReview = Objects.requireNonNull(schemaReview);
    }

    /**
     * Builds automatic database preparation from the supplied empty inputs.
     * <p>根据所提供空输入构建自动数据库准备。
     *
     * @return automatic database preparation from the supplied empty inputs / 根据所提供空输入构建自动数据库准备
     */
    public static AutomaticDatabasePreparation empty() {
        return new AutomaticDatabasePreparation(List.of(), List.of(), List.of(), Optional.empty());
    }
}
