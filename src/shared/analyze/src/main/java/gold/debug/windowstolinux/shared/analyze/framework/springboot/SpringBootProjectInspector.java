package gold.debug.windowstolinux.shared.analyze.framework.springboot;

import gold.debug.windowstolinux.shared.analyze.build.maven.MavenProjectInspection;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Applies the deterministic managed-deployment Spring Boot safety policy.
 *
 * <p>应用确定性的受管部署 Spring Boot 安全策略。
 */
public final class SpringBootProjectInspector {
    private static final Pattern MIGRATION = Pattern.compile("\\b(flyway|liquibase)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTOMATIC_SCHEMA_MUTATION = Pattern.compile(
            "(?im)(?:spring\\.jpa\\.hibernate\\.ddl-auto|hibernate\\.hbm2ddl\\.auto|ddl-auto)\\s*[:=]\\s*['\\\"]?"
                    + "(?:create|create-drop|update)['\\\"]?"
                    + "|spring\\.jpa\\.generate-ddl\\s*[:=]\\s*(?:true|yes)"
                    + "|spring\\.(?:sql\\.init\\.mode|datasource\\.initialization-mode)\\s*[:=]\\s*(?:always|embedded)"
    );
    private static final Pattern EXTERNAL_CONFIG = Pattern.compile(
            "spring\\.config\\.(import|location|additional-location)|SPRING_CONFIG_(IMPORT|LOCATION|ADDITIONAL_LOCATION)"
                    + "|System\\.getenv\\s*\\(|spring\\.application\\.json", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern APPLICATION_SECRET = Pattern.compile(
            "(?i)(password|secret|api[_-]?key|access[_-]?key|token)\\s*[:=]"
                    + "|@Value\\s*\\(\\s*\\\"?\\$\\{[^}]*?(password|secret|key|token)[^}]*}"
    );

    /**
     * Performs the {@code inspect} operation.
     *
     * <p>执行 {@code inspect} 操作。
     *
     * @param maven the {@code maven} value / {@code maven} 值
     * @param source the {@code source} value / {@code source} 值
     * @param rejections the {@code rejections} value / {@code rejections} 值
     */
    public void inspect(
            MavenProjectInspection maven,
            SourceInspection source,
            List<RejectionReason> rejections
    ) {
        if (!maven.springBootPlugin()) {
            rejections.add(reason("SPRING_BOOT_PLUGIN_MISSING", "analysis.rejection.bootPluginMissing", "phase.two"));
        }
        if (maven.warPackaging()) {
            rejections.add(reason("UNSUPPORTED_WAR", "analysis.rejection.warUnsupported", "phase.two"));
        }
        String scannedText = source.scannedText();
        if (MIGRATION.matcher(maven.pomText() + "\n" + scannedText).find()) {
            rejections.add(reason("DATABASE_MIGRATION_DETECTED", "analysis.rejection.migrationDetected", "phase.two"));
        }
        if (source.hasSchemaScript() || AUTOMATIC_SCHEMA_MUTATION.matcher(scannedText).find()) {
            rejections.add(reason("AUTOMATIC_SCHEMA_MUTATION_DETECTED",
                    "analysis.rejection.schemaMutationDetected", "phase.two"));
        }
        if (EXTERNAL_CONFIG.matcher(scannedText).find()) {
            rejections.add(reason("EXTERNAL_CONFIGURATION_DETECTED", "analysis.rejection.externalConfig", "phase.two"));
        }
        if (APPLICATION_SECRET.matcher(scannedText).find()) {
            rejections.add(reason("APPLICATION_SECRET_DETECTED", "analysis.rejection.applicationSecret", "phase.two"));
        }
    }

    private static RejectionReason reason(String code, String messageKey, String nextPhase) {
        return new RejectionReason(code, LocalizedMessage.of(messageKey), nextPhase);
    }
}
