package gold.debug.windowstolinux.shared.deploy.contract;

import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview;
import java.util.*;

/** Verified non-secret database inputs to the existing deployment transaction. / 现有部署事务使用的已验证非秘密数据库输入。 */
public record AutomaticDatabasePreparation(List<ConfigurationEntry> configuration, List<SecretReference> secrets,
                                           List<ManagedDatabaseBinding> bindings, Optional<DatabaseSchemaReview> schemaReview) {
    public AutomaticDatabasePreparation {
        configuration = List.copyOf(configuration); secrets = List.copyOf(secrets); bindings = List.copyOf(bindings);
        schemaReview = Objects.requireNonNull(schemaReview);
    }
    public static AutomaticDatabasePreparation empty() { return new AutomaticDatabasePreparation(List.of(),List.of(),List.of(),Optional.empty()); }
}
