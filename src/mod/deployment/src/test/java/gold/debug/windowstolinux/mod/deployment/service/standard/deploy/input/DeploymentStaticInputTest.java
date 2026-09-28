package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.source.git.DeploymentGitReference;
import org.junit.jupiter.api.Test;

/** Preserves the six legacy deterministic input and correction contracts on Mod values. / 用 Mod 值保留六项旧确定性输入及修正契约。 */
class DeploymentStaticInputTest {
    /** Actual source-backed resolver. / 真实源码依据解析器。 */
    private final DeploymentStaticRuntimeResolver resolver = new DeploymentStaticRuntimeResolver();

    /** Keeps all supplied invalid advanced fields visible for review. / 保留全部已提供的无效高级字段供审阅。 */
    @Test
    void invalidAdvancedFieldsRemainAvailableForCorrection() {
        var values = Map.of("port", "8080", "timeout", "bad", "stability", "bad", "expectedStatus", "bad",
                "healthEndpoint", "bad", "jvmArguments", "bad", "arguments", "bad", "volumes", "bad", "accessUrl",
                "bad");
        assertEquals(
                java.util.stream.Stream
                        .concat(values.keySet().stream(), java.util.stream.Stream.of("applicationDeclaration"))
                        .map(key -> "app/" + key).collect(Collectors.toSet()),
                resolver.corrections("app", values).stream().map(DeploymentReviewField::id)
                        .collect(Collectors.toSet()));
    }

    /** Retains static-site HTTP and explicit public access constraints. / 保留静态站点 HTTP 与显式公网地址约束。 */
    @Test
    void incompatibleHealthChoicesAreValidationFailuresAndCannotSilentlyLoseAnAccessUrl() {
        assertThrows(IllegalArgumentException.class, () -> resolver.runtime(DeploymentProjectKind.STATIC_SITE,
                Map.of("port", "8080", "healthMode", "TCP", "primary", "public")));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.health(Map.of("port", "8080", "healthMode", "invalid")));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.access(Map.of("healthMode", "TCP", "accessUrl", "https://example.test"), "192.0.2.1"));
    }

    /** Keeps missing reviewed database details in the correction form. / 在修正表单保留缺失的数据库审阅详情。 */
    @Test
    void selectingAManualDatabaseWithoutDetailsCanBeCompletedInTheCorrectionForm() {
        var fields = resolver.corrections("app", Map.of("databaseMode", "POSTGRESQL"));
        assertTrue(
                fields.stream().anyMatch(field -> field.id().equals("app/databaseDetails") && field.value().isEmpty()));
    }

    /** Uses typed runtime and source references without dropping duplicate-port rejection. / 使用类型化运行与来源引用并保留重复端口拒绝。 */
    @Test
    void parsesBoundedRuntimeAndSourceReferencesWithoutDroppingDuplicates() {
        assertEquals(Map.of(18080, 8080), DeploymentStaticInputParser.ports("18080:8080"));
        assertEquals(1, DeploymentStaticInputParser.volumes("windowstolinux-data:/var/lib/demo:rw").size());
        assertEquals(java.util.List.of("--flag", "value"), DeploymentStaticInputParser.arguments("--flag value"));
        assertEquals("main",
                ((DeploymentGitReference.Branch) DeploymentStaticInputParser.gitReference(0, "main")).value());
        var php = (DeploymentRuntimeDefinition.PhpService) DeploymentStaticInputParser.service(
                DeploymentProjectKind.PHP_SERVICE, "8.3", "public", "public/index.php",
                new DeploymentHealthCheck.Tcp(8080, 10, 5));
        assertEquals(8080, php.servicePort());
        assertThrows(IllegalArgumentException.class, () -> DeploymentStaticInputParser.ports("18080:8080;18080:8081"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentStaticInputParser.gitReference(3, "main"));
    }

    /** Preserves reviewed-empty versus server database with an opaque password revision. / 保留已审阅为空与含不透明密码修订的服务器数据库区别。 */
    @Test
    void distinguishesExplicitNoDatabaseFromOneReviewedServerDatabase() {
        assertEquals(java.util.List.of(),
                DeploymentStaticInputParser
                        .databaseBindings(DeploymentDatabaseReviewModeType.NONE, "ignored retained expert input")
                        .orElseThrow());
        var bindings = DeploymentStaticInputParser.databaseBindings(DeploymentDatabaseReviewModeType.POSTGRESQL,
                "primary|db.example.test|5432|shop|shop|database-password:3|required").orElseThrow();
        assertEquals("primary", bindings.getFirst().databaseId());
        var connection = (DeploymentDatabaseConnection.Server) bindings.getFirst().connection();
        assertEquals("db.example.test", connection.host());
        assertEquals(5432, connection.port());
        assertEquals("database-password", connection.passwordReference().identifier());
        assertEquals(3, connection.passwordReference().revision());
        assertTrue(connection.tlsRequired());
    }

    /** Rejects absent review and malformed database bindings. / 拒绝未审阅及畸形数据库绑定。 */
    @Test
    void rejectsUnreviewedOrMalformedDatabaseScopes() {
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentStaticInputParser.databaseBindings(DeploymentDatabaseReviewModeType.UNREVIEWED, ""));
        assertThrows(IllegalArgumentException.class, () -> DeploymentStaticInputParser
                .databaseBindings(DeploymentDatabaseReviewModeType.MYSQL, "missing-fields"));
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentStaticInputParser.databaseBindings(DeploymentDatabaseReviewModeType.MARIADB,
                        "primary|db.example.test|3306|shop|shop|database-password:1|unknown"));
    }
}
