package gold.debug.windowstolinux.mod.deployment.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.stream.Stream;

import javax.swing.*;
import javax.swing.text.JTextComponent;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeploymentRuntimePreviewTest extends DeploymentRuntimeFixture {
    @ParameterizedTest
    @EnumSource(value = DeploymentProjectKind.class, names = {"GO_SERVICE", "NODE_SERVICE"})
    void actualClassOwnerPublishesPureRuntimePreviewWithoutOpeningBusinessOrAdmittingTasks(DeploymentProjectKind kind)
        throws Exception {
        enable();
        var views = new LinkedHashMap<ModPageRegistry.PageKey, JComponent>();
        var normal = new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent view) {
                throw new AssertionError("Preview must never open a deployment business page");
            }

            public void focus(ModPageRegistry.PageKey key) {
            }

            public void remove(ModPageRegistry.PageKey key) {
            }

            public void environment(UiEnvironment environment) {
            }
        };
        var previews = new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent view) {
                assertTrue(SwingUtilities.isEventDispatchThread());
                assertNull(views.put(key, view));
            }

            public void focus(ModPageRegistry.PageKey key) {
                assertTrue(SwingUtilities.isEventDispatchThread());
                assertTrue(views.containsKey(key));
            }

            public void remove(ModPageRegistry.PageKey key) {
                assertTrue(SwingUtilities.isEventDispatchThread());
                views.remove(key);
            }

            public void environment(UiEnvironment environment) {
                assertTrue(SwingUtilities.isEventDispatchThread());
            }
        };
        var initial = new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false);
        var pages = new ModPageRegistry(normal, initial);
        pages.installPreviews(previews);
        try (var runtime = open()) {
            var report = runtime.startDevelopmentMods(workspace(), pages);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertEquals(Set.of(OWNER), report.running());
            assertEquals(Set.of("workspace"), pages.contributions().stream()
                .map(value -> value.key().pageId()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(Set.of("runtime-preview-go_service", "runtime-preview-node_service"), pages.previews().stream()
                .map(value -> value.key().pageId()).collect(java.util.stream.Collectors.toSet()));
            assertTrue(views.isEmpty());
            var before = effectCounts(runtime);
            var key = new ModPageRegistry.PageKey(OWNER, "runtime-preview-" + kind.name().toLowerCase(Locale.ROOT));
            var context = runtime.modContexts().get(OWNER).context();
            assertThrows(ApiException.class, () -> context.ui().open(key.pageId()));
            assertThrows(ApiException.class,
                () -> pages.openPreview(new ModPageRegistry.PageKey(OWNER, "workspace")));
            pages.openPreview(key);
            pages.openPreview(key);
            var original = onEdt(() -> views.get(key));
            assertNotNull(original);
            onEdt(() -> {
                assertEquals(Set.of(key), views.keySet());
                assertRuntimeFields(original, kind, initial.locale());
                return null;
            });
            var changed = new UiEnvironment(Locale.ENGLISH, "DARK", false);
            pages.environment(changed);
            onEdt(() -> {
                assertSame(original, views.get(key), "Appearance retains the original author-owned root");
                assertRuntimeFields(original, kind, changed.locale());
                return null;
            });
            assertEquals(before, effectCounts(runtime));
            assertEquals(0, runtime.security().securityState().activeTasks());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            pages.environment(new UiEnvironment(Locale.ENGLISH, "DARK", true));
            assertTrue(onEdt(views::isEmpty));
            assertEquals("PAGE_LOCKED",
                assertThrows(ApiException.class, () -> pages.openPreview(key)).problem().eventCode());
            pages.environment(initial);
            pages.openPreview(key);
            assertNotSame(original, onEdt(() -> views.get(key)), "Released preview reopens as a fresh author instance");
            assertEquals(before, effectCounts(runtime));
            runtime.close();
            assertTrue(onEdt(views::isEmpty));
            assertTrue(pages.previews().isEmpty());
            assertTrue(pages.contributions().isEmpty());
        }
    }

    private Map<String, Long> effectCounts(PlatformRuntime runtime) throws Exception {
        var values = new LinkedHashMap<String, Long>();
        for (var table : List.of("platform_task", "platform_task_resource", "mod_operation", "server_profile",
            "credential", "ai_model", "mod_server_reference", "mod_credential_reference"))
            values.put("app." + table, scalar(runtime, "SELECT COUNT(*) FROM " + table));
        try (var access = scope(runtime, true); var connection = access.openConnection()) {
            var tables = new ArrayList<String>();
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema=CURRENT_SCHEMA AND table_type='BASE TABLE' ORDER BY table_name")) {
                while (rows.next())
                    tables.add(rows.getString(1));
            }
            assertEquals(Set.of("deployment_unit", "managed_application", "managed_application_component",
                "managed_application_dependency", "external_application", "deployment_unit_configuration_snapshot",
                "deployment_unit_configuration_entry", "deployment_unit_release_binding", "secret_revision",
                "deployment_unit_release_secret_reference", "deployment_agent_task", "deployment_agent_event",
                "deployment_project", "deployment_execution", "deployment_release", "deployment_handoff",
                "preference", "deployment_log", "deployment_management_operation"), Set.copyOf(tables));
            for (var table : tables) {
                assertTrue(table.matches("[A-Za-z0-9_]+"));
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
                    assertTrue(rows.next());
                    values.put("author." + table, rows.getLong(1));
                }
            }
        }
        return values;
    }

    private static void assertRuntimeFields(JComponent root, DeploymentProjectKind kind, Locale locale) {
        var values = descendants(root).filter(JTextComponent.class::isInstance).map(JTextComponent.class::cast)
            .map(JTextComponent::getText).toList();
        for (String expected : List.of(kind == DeploymentProjectKind.NODE_SERVICE ? "22" : "1.25", "8080", "TCP",
            "https://example.invalid/preview", "APP_MODE=preview", "1800", "1024", "4096", "4194304", "4294967296"))
            assertTrue(values.contains(expected), "Missing actual ordinary runtime control value: " + expected);
        assertEquals(2, values.stream().filter("8080"::equals).count());
        assertTrue(descendants(root).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                .anyMatch(field -> "APP_MODE=preview".equals(field.getText()) && field.isEditable()),
            "The ordinary configuration must remain a real editable multiline control");
        var catalog = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", locale);
        assertTrue(descendants(root).filter(JLabel.class::isInstance).map(JLabel.class::cast).anyMatch(
            label -> (catalog.getString("application") + ": preview-application").equals(label.getText())));
        assertTrue(descendants(root).filter(JButton.class::isInstance).map(JButton.class::cast)
            .anyMatch(button -> catalog.getString("addFile").equals(button.getText())));
        assertTrue(descendants(root).filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast)
            .noneMatch(JCheckBox::isSelected));
    }

    private static Stream<Component> descendants(Component root) {
        return Stream.concat(Stream.of(root),
            root instanceof Container container
                ? Arrays.stream(container.getComponents()).flatMap(DeploymentRuntimePreviewTest::descendants)
                : Stream.empty());
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var result = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(result);
        return result.get();
    }
}
