package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Types;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationId;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual reader and H2 presentation edits preserve the original runtime, configuration and health. / 实际读取器与H2展示编辑保留原运行、配置及健康合同。 */
class DeploymentApplicationPresentationTest extends DeploymentApplicationsFixture {
    enum RejectedType {
        APP_URL, CATEGORY_CHANGE, STALE_REVISION, LOOPBACK_URL, CREDENTIAL_URL, FRAGMENT_URL, NON_HTTP_URL, BLANK_NAME, NEWLINE_NAME, OVERSIZE_NAME
    }

    @ParameterizedTest(name = "managed category WEBSITE={0}")
    @ValueSource(booleans = {false, true})
    void localManagedEditsPersistInListAndDetailWithoutChangingBusinessBytes(boolean website) throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            String revision = seedManaged(runtime, "demo", website, 1);
            var service = new DeploymentApplicationsService(context(runtime));
            var selected = await(service.list()).value().orElseThrow().getFirst();
            assertEquals(revision, selected.revisionSha256());
            assertEquals("demo", selected.displayName());
            var before = businessBytes(runtime);
            var url = website ? Optional.of("https://demo.debug.gold/path?view=1") : Optional.<String>empty();
            assertEquals(TaskResult.SUCCEEDED,
                await(service.edit(selected, "  本地名称  ", website ? "WEBSITE" : "APP", url)).outcome());
            assertEquals(List.of("本地名称", website ? "WEBSITE" : "APP", url.orElse("<NULL>")),
                storedPresentation(runtime, "demo-web"));
            assertEquals("本地名称", await(service.list()).value().orElseThrow().getFirst().displayName());
            var editor = await(service.presentation(selected)).value().orElseThrow();
            assertEquals(website ? "WEBSITE" : "APP", editor.category());
            assertEquals(url, editor.accessUrl());
            assertEquals(TaskResult.SUCCEEDED, await(service.editName(selected, "Renamed locally")).outcome());
            assertEquals(List.of("Renamed locally", website ? "WEBSITE" : "APP", url.orElse("<NULL>")),
                storedPresentation(runtime, "demo-web"));
            var inspected = new AtomicReference<DeploymentApplicationReader.ApplicationFacts>();
            assertEquals(TaskResult.SUCCEEDED, await(rootTask(runtime, task -> {
                var reader = new DeploymentApplicationReader(context(runtime).db());
                var data = reader.find(new DeploymentApplicationId("demo", new ServerId("server")), task);
                inspected.set(data);
                assertEquals("Renamed locally", data.snapshot().displayName());
                assertEquals(revision, data.snapshot().revisionSha256());
                assertEquals(data.snapshot(), reader.list(Optional.empty(), 10, task).items().getFirst());
                return success();
            })).outcome());
            assertNotNull(inspected.get());
            assertEquals(before, businessBytes(runtime));
            assertEquals(url, await(service.presentation(selected)).value().orElseThrow().accessUrl(),
                "Name-only edit preserves the stored access override");
            assertEquals(TaskResult.SUCCEEDED,
                await(service.edit(selected, "No access override", website ? "WEBSITE" : "APP", Optional.empty()))
                    .outcome());
            assertTrue(await(service.presentation(selected)).value().orElseThrow().accessUrl().isEmpty());
            assertEquals(before, businessBytes(runtime));
            assertEquals(0, peer.reads.get());
            assertEquals(0, peer.effects.get());
            assertNoActiveResources(runtime);
        }
    }

    @ParameterizedTest(name = "real managed editor rejection: {0}")
    @EnumSource(RejectedType.class)
    void invalidEditorInputsAndOldReviewRevisionLeaveEveryStoredBusinessFactUntouched(RejectedType rejected)
        throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            boolean website = rejected != RejectedType.APP_URL;
            seedManaged(runtime, "demo", website, 1);
            var service = new DeploymentApplicationsService(context(runtime));
            var selected = await(service.list()).value().orElseThrow().getFirst();
            if (rejected == RejectedType.STALE_REVISION)
                seedManaged(runtime, "demo", website, 2);
            var before = businessBytes(runtime);
            String category = rejected == RejectedType.CATEGORY_CHANGE ? "APP" : website ? "WEBSITE" : "APP";
            String name = switch (rejected) {
                case BLANK_NAME -> "   ";
                case NEWLINE_NAME -> "line\nbreak";
                case OVERSIZE_NAME -> "x".repeat(161);
                default -> "Must not be stored";
            };
            Optional<String> url = switch (rejected) {
                case APP_URL -> Optional.of("https://demo.debug.gold/");
                case LOOPBACK_URL -> Optional.of("http://127.0.0.1:8080/");
                case CREDENTIAL_URL -> Optional.of("https://user:password@demo.debug.gold/");
                case FRAGMENT_URL -> Optional.of("https://demo.debug.gold/#fragment");
                case NON_HTTP_URL -> Optional.of("file:///tmp/application");
                default -> Optional.empty();
            };
            var result = await(service.edit(selected, name, category, url));
            assertEquals(TaskResult.FAILED, result.outcome());
            assertTrue(result.value().isEmpty());
            String event = switch (rejected) {
                case APP_URL -> "DEPLOYMENT_PRESENTATION_URL_NOT_APPLICABLE";
                case CATEGORY_CHANGE -> "DEPLOYMENT_PRESENTATION_CATEGORY_CHANGED";
                case STALE_REVISION -> "DEPLOYMENT_PRESENTATION_REVISION_CHANGED";
                case BLANK_NAME, NEWLINE_NAME, OVERSIZE_NAME -> "DEPLOYMENT_PRESENTATION_NAME_INVALID";
                default -> "DEPLOYMENT_PRESENTATION_URL_INVALID";
            };
            assertEquals(event, result.problem().orElseThrow().eventCode());
            assertEquals(before, businessBytes(runtime));
            assertEquals("demo", await(service.list()).value().orElseThrow().getFirst().displayName());
            assertEquals(0, peer.reads.get());
            assertEquals(0, peer.effects.get());
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void externalEditorChangesOnlyLocalPresentationAndKeepsAdoptedIdentityAndRuntime() throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            var service = new DeploymentApplicationsService(context(runtime));
            var target = await(service.scan(new ServerId("server"))).value().orElseThrow().applications().getFirst();
            assertEquals(TaskResult.SUCCEEDED, await(service.adopt(new ServerId("server"), target)).outcome());
            var original = await(service.externalApplications()).value().orElseThrow().getFirst();
            int reads = peer.reads.get();
            assertEquals(TaskResult.FAILED, await(service.editExternal(original, "App with forbidden URL", "APP",
                Optional.of("https://demo.debug.gold/"))).outcome());
            assertEquals(TaskResult.FAILED,
                await(service.editExternal(original, "Blank URL", "WEBSITE", Optional.of(""))).outcome());
            assertEquals(TaskResult.FAILED, await(service.editExternal(original, "Invalid URL", "WEBSITE",
                Optional.of("https://user:password@demo.debug.gold/"))).outcome());
            assertEquals(TaskResult.SUCCEEDED, await(service.editExternal(original, "  External website  ", "WEBSITE",
                Optional.of("https://demo.debug.gold/"))).outcome());
            var updated = await(service.externalApplications()).value().orElseThrow().getFirst();
            assertEquals("External website", updated.displayName());
            assertEquals("WEBSITE", updated.category());
            assertEquals(Optional.of("https://demo.debug.gold/"), updated.accessUrl());
            assertEquals(original.id(), updated.id());
            assertEquals(original.server(), updated.server());
            assertEquals(original.target(), updated.target());
            assertEquals(TaskResult.SUCCEEDED,
                await(service.editExternal(updated, "External app", "APP", Optional.empty())).outcome());
            var app = await(service.externalApplications()).value().orElseThrow().getFirst();
            assertEquals("APP", app.category());
            assertTrue(app.accessUrl().isEmpty());
            assertEquals(original.target(), app.target());
            assertEquals(reads, peer.reads.get());
            assertEquals(0, peer.effects.get());
            assertNoActiveResources(runtime);
        }
    }

    /** Reads the actual saved editor fields for the exact health unit. / 读取精确健康单元实际保存的编辑字段。 */
    private List<String> storedPresentation(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime,
                                            String unit) throws Exception {
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var query = connection.prepareStatement(
                 "SELECT display_name,application_category,access_url FROM deployment_unit WHERE deployment_unit_id=?")) {
            query.setString(1, unit);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                var value = List.of(rows.getString(1), rows.getString(2),
                    Objects.toString(rows.getString(3), "<NULL>"));
                assertFalse(rows.next());
                return value;
            }
        }
    }

    /** Captures actual immutable runtime, configuration, release and health records. / 捕获实际不可变运行、配置、发布与健康记录。 */
    private List<List<String>> businessBytes(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime)
        throws Exception {
        var rows = new ArrayList<List<String>>();
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.createStatement()) {
            for (String query : List.of(
                "SELECT deployment_unit_id,runtime_payload,current_release_sha256,current_release_published_at FROM deployment_unit ORDER BY deployment_unit_id",
                "SELECT application_id,release_id,release_sha256,state,runtime_payload,health_payload FROM deployment_release ORDER BY application_id,release_id",
                "SELECT * FROM deployment_unit_configuration_snapshot ORDER BY deployment_unit_id,configuration_revision",
                "SELECT * FROM deployment_unit_configuration_entry ORDER BY deployment_unit_id,configuration_revision,configuration_key",
                "SELECT * FROM deployment_unit_release_binding ORDER BY deployment_unit_id,release_sha256")) {
                rows.add(List.of(query));
                try (var result = statement.executeQuery(query)) {
                    var metadata = result.getMetaData();
                    while (result.next()) {
                        var row = new ArrayList<String>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            if (metadata.getColumnType(i) == Types.BLOB)
                                row.add(Base64.getEncoder().encodeToString(result.getBytes(i)));
                            else
                                row.add(Objects.toString(result.getObject(i), "<NULL>"));
                        }
                        rows.add(List.copyOf(row));
                    }
                }
            }
        }
        return List.copyOf(rows);
    }
}
