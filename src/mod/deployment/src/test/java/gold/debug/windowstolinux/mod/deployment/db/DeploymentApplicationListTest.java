package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.net.URI;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.swing.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentPublish;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;

/** Real CLASS/H2 inventory preserves origin, business dates and local presentation. / 真实CLASS/H2清单保留来源、业务时间及本地展示。 */
class DeploymentApplicationListTest extends DeploymentApplicationsFixture {
    private static final Instant DEPLOYED = OBSERVED.plusSeconds(10);

    private static final String BUSINESS_URL = "https://example.debug.gold/declared";

    @Test
    void unifiedInventoryCombinesFiltersSortsRealDatesAndKeepsPresentationSeparateFromRuntime() throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            prepareInventory(runtime, ssh.server().getPort());
            var service = new DeploymentApplicationsService(context(runtime));
            var entries = await(service.inventory()).value().orElseThrow();
            assertEquals(List.of("external:", "managed:c", "managed:a", "managed:b"),
                entries.stream().map(value -> value.external().isPresent() ? "external:" : value.key()).toList());
            var external = entries.getFirst();
            assertTrue(external.deployedAt().isEmpty());
            assertTrue(external.adoptedAt().isPresent());
            assertEquals(adoptedAt(runtime, external.external().orElseThrow().id()),
                external.adoptedAt().orElseThrow());
            assertEquals(DEPLOYED, entries.get(2).deployedAt().orElseThrow());
            assertEquals(DEPLOYED, entries.get(3).deployedAt().orElseThrow());
            assertTrue(entries.subList(1, 4).stream().allMatch(value -> value.adoptedAt().isEmpty()));
            assertEquals(2,
                entries.stream().filter(
                        value -> value.matches(Optional.of("WEBSITE"), Optional.of(new ServerId("server"))))
                    .count());
            assertEquals(0,
                entries.stream()
                    .filter(value -> value.matches(Optional.of("WEBSITE"), Optional.of(new ServerId("other"))))
                    .count());
            assertEquals(1, entries.stream()
                .filter(value -> value.matches(Optional.of("APP"), Optional.of(new ServerId("other")))).count());
            assertEquals(4,
                entries.stream().filter(value -> value.matches(Optional.empty(), Optional.empty())).count());
            assertEquals(3, await(service.list()).value().orElseThrow().size());
            assertEquals(1, await(service.externalApplications()).value().orElseThrow().size());
            var selected = entries.get(2);
            assertEquals(Optional.of(BUSINESS_URL), selected.accessUrl());
            var originalBusiness = businessBytes(runtime);
            int reads = peer.reads.get(), effects = peer.effects.get();
            assertEquals(TaskResult.FAILED,
                await(service.edit(selected.managed().orElseThrow(), "Invalid", "APP", Optional.of(BUSINESS_URL)))
                    .outcome());
            String editedUrl = "https://example.debug.gold/display?view=1";
            assertEquals(TaskResult.SUCCEEDED,
                await(service.edit(selected.managed().orElseThrow(), "本地名称", "WEBSITE", Optional.of(editedUrl)))
                    .outcome());
            var edited = await(service.inventory()).value().orElseThrow().stream()
                .filter(value -> value.key().equals(selected.key())).findFirst().orElseThrow();
            assertEquals("本地名称", edited.name());
            assertEquals("WEBSITE", edited.category());
            assertEquals(Optional.of(editedUrl), edited.accessUrl());
            assertEquals(selected.deployedAt(), edited.deployedAt());
            assertEquals(originalBusiness, businessBytes(runtime));
            assertEquals(TaskResult.SUCCEEDED, await(
                service.edit(edited.managed().orElseThrow(), "Explicitly cleared", "WEBSITE", Optional.empty()))
                .outcome());
            var cleared = await(service.inventory()).value().orElseThrow().stream()
                .filter(value -> value.key().equals(selected.key())).findFirst().orElseThrow();
            assertTrue(cleared.accessUrl().isEmpty(), "A cleared override must not resurrect the declared URL");
            assertEquals(TaskResult.SUCCEEDED, await(service.editExternal(external.external().orElseThrow(),
                "External website", "WEBSITE", Optional.of(editedUrl))).outcome());
            var savedExternal = await(service.inventory()).value().orElseThrow().stream()
                .filter(value -> value.key().equals(external.key())).findFirst().orElseThrow();
            assertEquals(external.adoptedAt(), savedExternal.adoptedAt());
            assertTrue(savedExternal.deployedAt().isEmpty());
            assertEquals(Optional.of(editedUrl), savedExternal.accessUrl());
            assertEquals("WEBSITE", savedExternal.category());
            assertEquals(originalBusiness, businessBytes(runtime));
            assertEquals(reads, peer.reads.get());
            assertEquals(effects, peer.effects.get());
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void actualLazyClassPageFiltersLocallyKeepsSelectionAndShowsBusinessUrlAcrossLanguageAndLockChanges()
        throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            var mounted = new AtomicReference<JComponent>();
            var pages = new ModPageRegistry(new ModPageRegistry.PageHost() {
                public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
                    mounted.set(component);
                }

                public void focus(ModPageRegistry.PageKey key) {
                }

                public void remove(ModPageRegistry.PageKey key) {
                    mounted.set(null);
                }

                public void environment(UiEnvironment environment) {
                }
            }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            var report = runtime.startDevelopmentMods(workspace(), pages);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertEquals(Set.of(OWNER), report.running());
            prepareInventory(runtime, ssh.server().getPort());
            var key = new ModPageRegistry.PageKey(OWNER, "workspace");
            assertTrue(pages.contributions().stream().anyMatch(value -> value.key().equals(key)));
            assertNull(mounted.get());
            pages.navigateView(key, "applications");
            var component = mounted.get();
            assertNotNull(component);
            var actualControls = new AtomicReference<List<Component>>();
            SwingUtilities.invokeAndWait(() -> actualControls
                .set(List.of(named(component, "applications.inventory"), named(component, "applications.category"),
                    named(component, "applications.serverFilter"), named(component, "applications.details"))));
            var selector = (JList<?>) actualControls.get().get(0);
            var categories = (JComboBox<?>) actualControls.get().get(1);
            var servers = (JComboBox<?>) actualControls.get().get(2);
            var details = (JTextArea) actualControls.get().get(3);
            waitEdt(() -> selector.getModel().getSize() == 4 && selector.isEnabled());
            int reads = peer.reads.get(), effects = peer.effects.get();
            SwingUtilities.invokeAndWait(() -> {
                categories.setSelectedItem("WEBSITE");
                servers.setSelectedItem(new ServerId("server"));
            });
            waitEdt(() -> selector.getModel().getSize() == 2);
            SwingUtilities.invokeAndWait(() -> selector.setSelectedIndex(0));
            var original = new AtomicReference<Object>();
            SwingUtilities.invokeAndWait(() -> {
                original.set(selector.getSelectedValue());
                assertEquals("managed:a", entryKey(original.get()));
                assertTrue(details.getText().contains(BUSINESS_URL));
                assertFalse(details.getText().contains("http://127.0.0.1:8080"));
                assertTrue(details.getText().contains(DEPLOYED.toString()));
            });
            pages.environment(new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
            SwingUtilities.invokeAndWait(() -> {
                assertSame(original.get(), selector.getSelectedValue());
                assertTrue(details.getText().contains("Business access URL: " + BUSINESS_URL));
            });
            var refresh = new AtomicReference<JButton>();
            SwingUtilities.invokeAndWait(() -> {
                refresh.set(button(component, "Refresh"));
                refresh.get().doClick();
            });
            waitEdt(() -> selector.isEnabled() && selector.getSelectedValue() != null
                && entryKey(selector.getSelectedValue()).equals("managed:a"));
            SwingUtilities.invokeAndWait(() -> servers.setSelectedItem(new ServerId("other")));
            waitEdt(() -> selector.getModel().getSize() == 0 && selector.getSelectedValue() == null);
            SwingUtilities.invokeAndWait(() -> servers.setSelectedItem(new ServerId("server")));
            waitEdt(() -> selector.getModel().getSize() == 2);
            SwingUtilities.invokeAndWait(() -> assertNull(selector.getSelectedValue(),
                "A hidden original selection is cleared explicitly, never replaced by another entry"));
            pages.environment(new UiEnvironment(Locale.ENGLISH, "LIGHT", true));
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(selector.isEnabled());
                assertFalse(categories.isEnabled());
                assertFalse(servers.isEnabled());
                assertFalse(refresh.get().isEnabled());
            });
            pages.environment(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(selector.isEnabled());
                assertEquals("WEBSITE", categories.getSelectedItem());
                assertEquals(new ServerId("server"), servers.getSelectedItem());
            });
            assertSame(component, mounted.get());
            assertEquals(reads, peer.reads.get());
            assertEquals(effects, peer.effects.get());
            pages.closeView(key);
            assertNull(mounted.get());
            pages.navigateView(key, "applications");
            var reopened = mounted.get();
            assertNotNull(reopened);
            assertNotSame(component, reopened);
            var reopenedInventory = new AtomicReference<JList<?>>();
            SwingUtilities.invokeAndWait(() -> reopenedInventory.set((JList<?>) named(reopened, "applications.inventory")));
            waitEdt(() -> reopenedInventory.get().isEnabled() && reopenedInventory.get().getModel().getSize() == 4);
            SwingUtilities.invokeAndWait(() -> {
                reopenedInventory.get().setSelectedIndex(2);
                assertEquals("managed:a", entryKey(reopenedInventory.get().getSelectedValue()));
                assertTrue(((JTextArea) named(reopened, "applications.details")).getText().contains(BUSINESS_URL));
            });
            assertEquals(reads, peer.reads.get());
            assertEquals(effects, peer.effects.get());
            pages.closeView(key);
            assertNull(mounted.get());
            waitEdt(() -> {
                try {
                    return scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL") == 0;
                } catch (Exception failed) {
                    throw new AssertionError(failed);
                }
            });
            assertNoActiveResources(runtime);
        }
    }

    private void prepareInventory(PlatformRuntime runtime, int port) throws Exception {
        server(runtime, port, "tester");
        runtime.servers().saveServerProfile(
            ServerProfile
                .fromStored(new StoredServerProfile("other", "127.0.0.2", port, "tester", "fixture-secret")),
            "fixture-secret".toCharArray());
        publishAt(runtime, "b", "server", true, DEPLOYED);
        publishAt(runtime, "a", "server", true, DEPLOYED);
        publishAt(runtime, "c", "other", false, DEPLOYED.plusSeconds(1));
        var service = new DeploymentApplicationsService(context(runtime));
        var scan = await(service.scan(new ServerId("server"))).value().orElseThrow();
        assertEquals(TaskResult.SUCCEEDED,
            await(service.adopt(new ServerId("server"), scan.applications().getFirst())).outcome());
    }

    /** Uses original formal repository transitions with monotonic explicit evidence times, never fabricated rows. / 使用原正式仓库迁移及单调明确依据时间，不伪造行。 */
    private void publishAt(PlatformRuntime runtime, String application, String server, boolean website, Instant time)
        throws Exception {
        var context = context(runtime);
        var captured = new AtomicReference<Throwable>();
        var handle = context.tasks().submit(new TaskRequest("inventory-publication-seed",
            "Original formal inventory publication", List.of(server), true), task -> {
            try {
                var original = graph(application);
                var components = new ArrayList<DeploymentApplicationGraph.Part>();
                for (var component : original.components()) {
                    var workload = component.runtime().workload();
                    boolean web = website && component.componentId().equals(original.healthComponentId());
                    if (web)
                        workload = new DeploymentWorkload(workload.mode(), workload.reviewed(),
                            workload.command(), workload.workingDirectory(),
                            List.of(new DeploymentEndpoint("business-http",
                                DeploymentEndpoint.ProtocolType.HTTP, "0.0.0.0", 8080, 8080,
                                DeploymentEndpoint.ExposureType.EXTERNAL, BUSINESS_URL)),
                            workload.verification(), workload.expectedOutput(), workload.client(),
                            workload.inputs(), workload.companions(), workload.buildDirectory(),
                            workload.workers());
                    DeploymentHealthCheck health = web
                        ? new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:8080/health"), 200,
                        10)
                        : component.runtime().healthCheck();
                    var definition = web
                        ? new DeploymentRuntimeDefinition.NodeService(22, health).withWorkload(workload)
                        : component.reviewedRuntime();
                    var unit = DeploymentUnitIdentity.forManaged(application + "-" + component.componentId(),
                        new ServerId(server), "a".repeat(64));
                    var runtimeValue = new DeploymentUnitRuntimeConfig(health,
                        web
                            ? Optional.of(new DeploymentAccessUrl(URI.create(BUSINESS_URL)))
                            : Optional.empty(),
                        definition.identityPolicy(), workload);
                    components.add(new DeploymentApplicationGraph.Part(component.componentId(), unit,
                        runtimeValue, component.dependencies(), definition, component.dataPaths(),
                        component.resources()));
                }
                var graph = new DeploymentApplicationGraph(application, original.healthComponentId(),
                    original.healthCheck(), components);
                var publication = new DeploymentPublish(graph, publications(graph, 1, List.of()));
                var references = DeploymentOriginalPublishCommit.references(new ServerId(server),
                    publication, Map.of());
                var identity = new DeploymentExecutionIdentity(UUID.randomUUID(), task.id(), Optional.empty(),
                    application, new ServerId(server),
                    DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64));
                var repository = new DeploymentExecutionStore(context.db());
                repository.begin(task, identity, "deployment-publication", references, Optional.empty(), time);
                for (var reference : references) {
                    assertEquals(DeploymentRefIntent.DeploymentRefKind.SERVER, reference.kind());
                    var reserved = context.servers().reserve(new ServerId(reference.resourceId()),
                        reference.businessKey(), task);
                    repository.recordReference(task, identity.executionId(), reference, reserved.id(), time);
                }
                repository.startEffects(task, identity.executionId(), time);
                repository.recordPublicationTemplate(task, identity.executionId(),
                    new DeploymentPublishProposal(graph, publication.units()), time);
                repository.commitApplication(task, identity.executionId(), graph, publication.units(), time);
                var commit = new DeploymentOriginalPublishCommit(context);
                assertEquals(TaskResult.SUCCEEDED,
                    commit.commitVerified(task, identity.executionId(), publication).outcome());
                assertEquals(TaskResult.SUCCEEDED,
                    commit.finishKnown(task, repository.find(task, identity.executionId()).orElseThrow())
                        .outcome());
                var record = repository.find(task, identity.executionId()).orElseThrow();
                assertEquals("FINISHED", record.phase());
                assertTrue(record.businessCommitted());
                assertTrue(record.cleanupComplete());
                return success();
            } catch (Exception | AssertionError failed) {
                captured.set(failed);
                throw failed;
            }
        });
        assertEquals(TaskResult.SUCCEEDED, await(handle).outcome(), () -> String.valueOf(captured.get()));
    }

    private Instant adoptedAt(PlatformRuntime runtime, String id) throws Exception {
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.prepareStatement(
                 "SELECT adopted_at FROM external_application WHERE external_application_id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                var value = Instant.ofEpochMilli(rows.getLong(1));
                assertFalse(rows.next());
                return value;
            }
        }
    }

    private List<String> businessBytes(PlatformRuntime runtime) throws Exception {
        var facts = new ArrayList<String>();
        try (var access = scope(runtime, true); var connection = access.openConnection()) {
            for (String sql : List.of(
                "SELECT deployment_unit_id,runtime_payload,current_release_sha256,current_release_published_at,runtime_state,autostart_state FROM deployment_unit ORDER BY deployment_unit_id",
                "SELECT * FROM managed_application ORDER BY application_id",
                "SELECT * FROM deployment_release ORDER BY application_id,release_id",
                "SELECT * FROM deployment_unit_configuration_snapshot ORDER BY deployment_unit_id,configuration_revision",
                "SELECT * FROM deployment_unit_configuration_entry ORDER BY deployment_unit_id,configuration_revision,configuration_key",
                "SELECT * FROM deployment_unit_release_binding ORDER BY deployment_unit_id,release_sha256",
                "SELECT external_application_id,server_profile_id,runtime_type,runtime_identity,identity_fingerprint,bound_host,bound_ssh_port,bound_username,runtime_state,can_start,can_stop,adopted_at,observed_at FROM external_application ORDER BY external_application_id")) {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                    while (rows.next())
                        for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                            Object value = rows.getMetaData().getColumnType(i) == Types.BLOB
                                ? rows.getBytes(i)
                                : rows.getObject(i);
                            facts.add(sql + ":" + rows.getMetaData().getColumnName(i) + "="
                                + (value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value));
                        }
                }
            }
        }
        return List.copyOf(facts);
    }

    private static Component named(Container container, String name) {
        for (var component : container.getComponents()) {
            if (name.equals(component.getName()))
                return component;
            if (component instanceof Container child) {
                var found = namedOrNull(child, name);
                if (found != null)
                    return found;
            }
        }
        throw new AssertionError("Actual page component is missing: " + name);
    }

    private static Component namedOrNull(Container container, String name) {
        for (var component : container.getComponents()) {
            if (name.equals(component.getName()))
                return component;
            if (component instanceof Container child) {
                var found = namedOrNull(child, name);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static JButton button(Container container, String text) {
        for (var component : container.getComponents()) {
            if (component instanceof JButton value && text.equals(value.getText()))
                return value;
            if (component instanceof Container child) {
                try {
                    return button(child, text);
                } catch (AssertionError absent) {
                }
            }
        }
        throw new AssertionError("Actual localized action is missing: " + text);
    }

    /** Reads only the public original UI payload identity across the real private CLASS loader. / 跨真实私有CLASS加载器仅读取公开原UI载荷身份。 */
    private static String entryKey(Object entry) {
        try {
            return (String) entry.getClass().getMethod("key").invoke(entry);
        } catch (ReflectiveOperationException failed) {
            throw new AssertionError(failed);
        }
    }

    private static void waitEdt(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        var done = new AtomicReference<Boolean>();
        do {
            SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean()));
            if (done.get())
                return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Original inventory page did not reach the expected state");
    }
}
