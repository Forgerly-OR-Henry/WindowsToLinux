package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.api.ApplicationAutostartState;
import gold.debug.windowstolinux.mod.deployment.api.ApplicationRunState;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationUsage;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentLifecycleAction;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationsService;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reuses complete STATIC acceptance and returns product-owned usage. / 复用完整静态验收并返回产品用法供人工验收。 */
@EnabledIfSystemProperty(named = "managed.runtime.staticLive", matches = "true")
class DeploymentStaticRegressionLiveAcceptanceTest extends DeploymentStaticLiveAcceptanceTest {
    @ParameterizedTest
    @MethodSource("websiteProjects")
    void deploysWebsitesForManualAcceptance(String project) throws Exception {
        Path delivery = deliveryFile(project);
        deploysWebsitesThroughStaticEntry(project);
        deliver(project, false, delivery, "STATIC");
    }

    @ParameterizedTest
    @MethodSource("cliProjects")
    void deploysCliForManualAcceptance(String project) throws Exception {
        Path delivery = deliveryFile(project);
        deploysWebsitesThroughStaticEntry(project);
        deliver(project, true, delivery, "STATIC");
    }

    final Path deliveryFile(String project) throws Exception {
        Path directory = Path.of(System.getProperty("managed.live.deliveryDirectory")).toRealPath();
        assertTrue(directory.startsWith(workspace().resolve(".ai-workspace/evidence").toRealPath()));
        assertTrue(project.matches("[a-z]+(?:-[a-z]+)*"));
        Path file = directory.resolve(project + ".json");
        assertFalse(Files.exists(file), "A previous delivery must not be overwritten");
        return file;
    }

    final void deliver(String project, boolean cli, Path delivery, String mode) throws Exception {
        assertTrue(mode.equals("STATIC") || mode.equals("ASSISTED"));
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var applications = new DeploymentApplicationsService(context);
            var published = await(applications.list());
            assertEquals(1, published.size(), "The isolated state must contain the original publication only");
            var selected = await(applications.inspect(published.getFirst().application()));
            assertTrue(selected.application().id().startsWith("wtl-" + mode.toLowerCase(java.util.Locale.ROOT) + "-" + project + "-"));
            if (cli) {
                assertEquals(ApplicationRunState.INSTALLED, selected.state());
                assertEquals(ApplicationAutostartState.NOT_APPLICABLE, selected.autostart());
            } else {
                assertEquals(ApplicationRunState.STOPPED, selected.state());
                assertEquals(ApplicationAutostartState.DISABLED, selected.autostart());
                await(applications.lifecycle(selected, DeploymentLifecycleAction.START));
                selected = await(applications.inspect(selected.application()));
                assertEquals(ApplicationRunState.RUNNING, selected.state());
                assertEquals(ApplicationAutostartState.DISABLED, selected.autostart());
            }
            var usage = await(applications.usage(selected));
            assertEquals(selected.application(), usage.application());
            assertEquals(selected.revisionSha256(), usage.revisionSha256());
            assertEquals(selected.ownershipSha256(), usage.ownershipSha256());
            assertEquals(selected.state(), usage.state());
            assertTrue(usage.components().stream().allMatch(component -> component.mode()
                == (cli ? DeploymentApplicationUsage.Mode.ON_DEMAND : DeploymentApplicationUsage.Mode.DAEMON)));
            if (cli) {
                assertTrue(usage.components().stream().allMatch(component -> component.command().isPresent()));
            } else {
                assertTrue(usage.components().stream().flatMap(component -> component.endpoints().stream())
                    .anyMatch(endpoint -> endpoint.browser().isPresent()), "Use product-qualified public website endpoints");
            }
            var report = new LinkedHashMap<String, Object>();
            report.put("project", project);
            report.put("mode", mode);
            report.put("stateDirectory", root.toString());
            report.put("application", usage.application().id());
            report.put("server", usage.application().server().id());
            report.put("revisionSha256", usage.revisionSha256());
            report.put("ownershipSha256", usage.ownershipSha256());
            report.put("state", usage.state().name());
            report.put("autostart", usage.autostart().name());
            report.put("observedAt", selected.observedAt().toString());
            report.put("components", usage.components().stream().map(component -> Map.of(
                "id", component.id(), "unitId", component.unitId(), "mode", component.mode().name(),
                "state", component.state().name(), "command", component.command().orElse(""),
                "workers", component.workers(), "endpoints", component.endpoints().stream().map(endpoint -> Map.of(
                    "protocol", endpoint.protocol(), "value", endpoint.value(),
                    "browser", endpoint.browser().map(Object::toString).orElse(""))).toList())).toList());
            Files.writeString(delivery, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
            System.out.println(mode + "_MANUAL_DELIVERY " + project + " " + delivery + " state=" + usage.state());
        }
    }

    private static <T> T await(TaskHandle<T> handle) throws Exception {
        var result = handle.completion().toCompletableFuture().get(5, TimeUnit.MINUTES);
        assertEquals(TaskResult.SUCCEEDED, result.outcome(), result::toString);
        return result.value().orElseThrow();
    }
}
