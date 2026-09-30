package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.RejectionReason;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.node.NodeLanguageInspector;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.node.NodeServiceDeploymentInspector;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.mod.deployment.source.git.DeploymentGitReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /** Rejects a target-local health address as a user-declared business URL. / 拒绝把目标机本地健康地址作为用户声明业务URL。
     */
    @Test
    void rejectsLoopbackAsAUserBusinessUrl() {
        var publicUrl = java.net.URI.create("https://example.test/application");
        assertEquals(publicUrl, new DeploymentAccessUrl(publicUrl).url());
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentAccessUrl(java.net.URI.create("http://127.0.0.1:8080/")));
    }

    /** Retains source-backed process health when Node declares no network endpoint. / Node源码未声明网络端点时保留有源码依据的进程健康检查。
     * @param root actual bounded Node source directory / 真实有界Node源码目录
     * @throws Exception if source inspection fails / 源码检查失败时 */
    @Test
    void sourceBackedNodeWithoutAPortDefaultsToProcessHealthAndNoNetworkEndpoint(@TempDir Path root) throws Exception {
        String metadata = "{\"name\":\"node-process\",\"engines\":{\"node\":\"22\"},\"scripts\":{\"build\":\"node build.js\",\"start\":\"node main.js\"}}";
        Files.writeString(root.resolve("package.json"), metadata);
        Files.writeString(root.resolve("package-lock.json"), "{\"name\":\"node-process\",\"lockfileVersion\":3}");
        var source = new SourceInspectionFacts(2, List.of(Path.of("package.json"), Path.of("package-lock.json")),
                metadata);
        var language = new NodeLanguageInspector().inspect(root, source);
        var rejected = new ArrayList<RejectionReason>();
        var assessment = new NodeServiceDeploymentInspector().inspect(root, source, language, rejected);
        assertNotNull(assessment);
        assertTrue(rejected.isEmpty());
        assertTrue(assessment.runtimeSuggestion().suggestedHealthPort().isEmpty());
        var values = resolver.defaults(DeploymentProjectKind.NODE_SERVICE, assessment.runtimeSuggestion(), root);
        assertFalse(values.containsKey("port"));
        assertEquals("PROCESS", values.get("healthMode"));
        values.put("exposure", "INTERNAL");
        var runtime = assertInstanceOf(DeploymentRuntimeDefinition.NodeService.class,
                resolver.runtime(DeploymentProjectKind.NODE_SERVICE, values));
        assertEquals(22, runtime.nodeMajorVersion());
        assertEquals(new DeploymentHealthCheck.Process(10, 5), runtime.healthCheck());
        assertTrue(runtime.workload().endpoints().isEmpty());
        assertTrue(resolver.access(values, "192.0.2.1").isEmpty());
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

    /** Retains explicit typed health choices and leaves absent TCP input for human completion. / 保留显式类型化健康选择，并将缺失TCP输入留给人工补全。 */
    @Test
    void explicitHttpAndTcpInputsKeepTheirReviewedEndpointsWithoutGuessingMissingPorts() {
        var endpoint = java.net.URI.create("http://127.0.0.1:18080/health");
        var http = assertInstanceOf(DeploymentHealthCheck.Http.class, resolver.health(Map.of("healthMode", "HTTP",
                "healthEndpoint", endpoint.toString(), "port", "18080", "expectedStatus", "204", "timeout", "20")));
        assertEquals(endpoint, http.endpoint());
        assertEquals(204, http.expectedStatus());
        assertEquals(20, http.timeoutSeconds());
        assertThrows(IllegalArgumentException.class, () -> resolver
                .health(Map.of("healthMode", "HTTP", "healthEndpoint", "file:///tmp/health", "port", "18080")));
        assertEquals(new DeploymentHealthCheck.Tcp(18081, 20, 1),
                resolver.health(Map.of("healthMode", "TCP", "port", "18081", "timeout", "20", "stability", "1")));
        var incomplete = Map.of("healthMode", "TCP", "version", "22", "exposure", "INTERNAL");
        assertTrue(resolver.missing("api", DeploymentProjectKind.NODE_SERVICE, incomplete).stream()
                .anyMatch(field -> field.id().equals("api/port") && field.value().isEmpty()));
        assertFalse(incomplete.containsKey("port"));
        assertThrows(IllegalArgumentException.class, () -> resolver.health(incomplete));
    }

    /** Converts the explicit tag index while rejecting every unsupported reference choice. / 转换显式标签索引，并拒绝每项不受支持的引用选择。 */
    @Test
    void typedGitTagIndexRetainsItsReferenceAndRejectsUnsupportedIndexes() {
        var tag = assertInstanceOf(DeploymentGitReference.Tag.class,
                DeploymentStaticInputParser.gitReference(1, "release-one"));
        assertEquals("release-one", tag.value());
        for (int index : List.of(-1, 3, 9))
            assertThrows(IllegalArgumentException.class, () -> DeploymentStaticInputParser.gitReference(index, "main"));
    }
}
