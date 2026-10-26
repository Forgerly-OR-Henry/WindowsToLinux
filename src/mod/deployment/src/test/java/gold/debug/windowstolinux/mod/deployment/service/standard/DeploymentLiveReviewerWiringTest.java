package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.api.ApplicationAutostartState;
import gold.debug.windowstolinux.mod.deployment.api.ApplicationRunState;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationId;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationUsage;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentStandardRuntimeResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the live fixture's review wiring without opening a runtime or remote connection. / 不打开运行时或远端连接地检查真机fixture审阅接线。 */
class DeploymentLiveReviewerWiringTest {
    private static final String HOST = "192.0.2.10";
    private static final ServerId SERVER = new ServerId("review-fixture");

    @Test
    void selectedFrontendReviewUpdatesPortHealthAndAccessTogether() throws Exception {
        var answer = reviewFrontend(Map.of("port", "46100", "version", "24"));
        assertEquals("46100", answer.values().get("port"));
        assertEquals("http://" + HOST + ":46100/", answer.values().get("accessUrl"));
        var health = assertInstanceOf(DeploymentHealthCheck.Http.class,
            new DeploymentStandardRuntimeResolver().health(answer.values()));
        assertEquals(URI.create("http://127.0.0.1:46100/"), health.endpoint());
        assertEquals("24", answer.values().get("version"));
        assertEquals("preserved-default", answer.values().get("primary"));
        assertTrue(answer.files().isEmpty());
        assertEquals(1800, answer.limits().timeoutSeconds());
    }

    @Test
    void selectedFrontendReviewKeepsExplicitProxyAccessUrl() throws Exception {
        String proxy = "https://proxy.example.test/task-board/";
        var answer = reviewFrontend(Map.of("port", "46100", "accessUrl", proxy));
        assertEquals(proxy, answer.values().get("accessUrl"));
        assertEquals("46100", answer.values().get("port"));
        assertEquals(URI.create("http://127.0.0.1:46100/"), assertInstanceOf(DeploymentHealthCheck.Http.class,
            new DeploymentStandardRuntimeResolver().health(answer.values())).endpoint());
    }

    @Test
    void directWebsiteDeliveryRejectsStaleUrlWrongHostAndBackendOnlyMatch() throws Exception {
        var reviewer = new DeploymentStaticLiveAcceptanceTest().reviewer("task-board", "fixture-app",
            Map.of("frontend", Map.of(), "backend", Map.of()));
        String primary = select(reviewer).healthComponent().orElseThrow();
        var good = component(primary, "http://" + HOST + ":46100/");
        DeploymentStaticRegressionLiveAcceptanceTest.assertDirectWebsiteUsage("task-board", HOST, primary, usage(good));
        assertThrows(AssertionError.class, () -> DeploymentStaticRegressionLiveAcceptanceTest.assertDirectWebsiteUsage(
            "task-board", HOST, primary, usage(component(primary, "http://" + HOST + ":8080/"))));
        assertThrows(AssertionError.class, () -> DeploymentStaticRegressionLiveAcceptanceTest.assertDirectWebsiteUsage(
            "task-board", HOST, primary, usage(component(primary, "http://192.0.2.11:46100/"))));
        assertThrows(AssertionError.class, () -> DeploymentStaticRegressionLiveAcceptanceTest.assertDirectWebsiteUsage(
            "task-board", HOST, primary, usage(component(primary, "http://" + HOST + ":8080/"),
                component("actual-backend", "http://" + HOST + ":46100/"))));
        DeploymentStaticRegressionLiveAcceptanceTest.assertDirectWebsiteUsage("file-transfer", HOST, "actual-gateway",
            usage(component("actual-gateway", "http://" + HOST + ":46110/")));
    }

    private static DeploymentStandardInputs.Answers reviewFrontend(Map<String, String> frontendInputs) throws Exception {
        var reviewer = new DeploymentStaticLiveAcceptanceTest().reviewer("task-board", "fixture-app",
            Map.of("frontend", frontendInputs, "backend", Map.of()));
        select(reviewer);
        var defaults = new LinkedHashMap<String, String>();
        defaults.put("port", "8080");
        defaults.put("accessUrl", "http://" + HOST + ":8080/");
        defaults.put("healthMode", "HTTP");
        defaults.put("healthEndpoint", "http://127.0.0.1:8080/");
        defaults.put("exposure", "EXTERNAL");
        defaults.put("primary", "preserved-default");
        var prompt = new DeploymentStandardInputs.Prompt("actual-frontend", "fixture-app-frontend",
            DeploymentProjectKind.STATIC_SITE, defaults, List.of());
        var reviewed = reviewer.review(SERVER, "0".repeat(64), List.of(prompt), null);
        assertEquals(TaskResult.SUCCEEDED, reviewed.outcome());
        assertEquals("8080", prompt.defaults().get("port"));
        return reviewed.value().orElseThrow().get("actual-frontend");
    }

    private static DeploymentStandardInputs.Selection select(DeploymentStandardInputs.Reviewer reviewer) throws Exception {
        var selected = reviewer.select(SERVER, "0".repeat(64), List.of(
            new DiscoveredProjectPart("actual-frontend", Path.of("frontend"), List.of(DeploymentProjectKind.STATIC_SITE)),
            new DiscoveredProjectPart("actual-backend", Path.of("backend"), List.of(DeploymentProjectKind.JAVA_SOURCE))), null);
        assertEquals(TaskResult.SUCCEEDED, selected.outcome());
        var selection = selected.value().orElseThrow();
        assertEquals(Optional.of("actual-frontend"), selection.healthComponent());
        assertEquals(Set.of("actual-backend"), selection.choices().get("actual-frontend").dependencies());
        return selection;
    }

    private static DeploymentApplicationUsage.Component component(String id, String url) {
        return new DeploymentApplicationUsage.Component(id, "fixture-" + id, DeploymentApplicationUsage.Mode.DAEMON,
            ApplicationRunState.RUNNING, ApplicationAutostartState.DISABLED, Optional.empty(),
            List.of(new DeploymentApplicationUsage.Endpoint("http", "http", url, Optional.of(URI.create(url)))), List.of());
    }

    private static DeploymentApplicationUsage usage(DeploymentApplicationUsage.Component... components) {
        return new DeploymentApplicationUsage(new DeploymentApplicationId("fixture-app", SERVER), "0".repeat(64), "1".repeat(64),
            ApplicationRunState.RUNNING, ApplicationAutostartState.DISABLED, List.of(components), Set.of());
    }
}
