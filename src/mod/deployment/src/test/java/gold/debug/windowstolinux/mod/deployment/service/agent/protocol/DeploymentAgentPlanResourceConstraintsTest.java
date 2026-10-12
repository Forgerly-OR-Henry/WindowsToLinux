package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishArguments;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentPlanResourceConstraintsTest {
    private static final DeploymentRemoteLayout LAYOUT = DeploymentRemoteLayout.original();

    @ParameterizedTest
    @ValueSource(strings = {"file-id", "configuration-id", "file-path", "configuration-path", "sqlite-path", "default-path"})
    void mergedHelperBindingDuplicatesAreRejectedWithoutDroppingEitherDeclaration(String scenario) throws Exception {
        String path = scenario.endsWith("path") ? "data/state.db" : "data";
        String databasePath = "data/state.db";
        if (scenario.equals("default-path")) {
            path = DeploymentStorageLocation.defaults().resolve("demo-api", DeploymentStorageLocation.StorageResourceType.DATABASE, "state", LAYOUT) + "/state.db";
            databasePath = "";
        }
        var type = scenario.startsWith("configuration") ? DeploymentStorageLocation.StorageResourceType.CONFIGURATION : DeploymentStorageLocation.StorageResourceType.FILE;
        String id = scenario.endsWith("id") ? "state" : "files";
        var file = new DeploymentFileBinding(id, new DeploymentStoragePath(path,
            type == DeploymentStorageLocation.StorageResourceType.CONFIGURATION ? DeploymentStoragePath.AccessMode.READ_ONLY : DeploymentStoragePath.AccessMode.READ_WRITE,
            "schema", true), DeploymentStorageLocation.defaults(), type,
            type == DeploymentStorageLocation.StorageResourceType.CONFIGURATION ? "settings.json" : "",
            type == DeploymentStorageLocation.StorageResourceType.CONFIGURATION ? "a".repeat(64) : "");
        var databases = new java.util.ArrayList<>(List.of(sqlite("state", databasePath)));
        var files = scenario.equals("sqlite-path") ? List.<DeploymentFileBinding>of() : List.of(file);
        if (scenario.equals("sqlite-path")) databases.add(sqlite("other", databasePath));
        var resources = new DeploymentResourceBindings(files, Optional.of(databases));
        var part = part(resources);
        var failure = assertThrows(IllegalArgumentException.class,
            () -> DeploymentAgentPlanResourceConstraints.require(part, Optional.of("demo-api"), LAYOUT));
        assertTrue(failure.getMessage().startsWith("AGENT_PLAN_MANAGED_DATA_CONSTRAINT: component api"));
        assertTrue(failure.getMessage().contains(scenario.endsWith("id") ? "binding ID state" : "logical access path"));
        assertSame(resources, part.resources(), "Validation preserves the original resource declarations");
        assertEquals(files.size() + databases.size(), Integer.parseInt(DeploymentPublishArguments.content("demo-api", resources).get(2)));
    }

    @Test
    void nestedDirectoryAndSqliteChildRemainDistinctBindings() throws Exception {
        var files = List.of(new DeploymentFileBinding("uploads", new DeploymentStoragePath("data", DeploymentStoragePath.AccessMode.READ_WRITE, "files", true), DeploymentStorageLocation.defaults()));
        var resources = new DeploymentResourceBindings(files, Optional.of(List.of(sqlite("state", "data/state.db"))));
        assertDoesNotThrow(() -> DeploymentAgentPlanResourceConstraints.require(part(resources), Optional.of("demo-api"), LAYOUT));
        var encoded = DeploymentPublishArguments.content("demo-api", resources);
        assertEquals("2", encoded.get(2));
        assertEquals("uploads", encoded.get(3));
        assertEquals("state", encoded.get(13));
        assertEquals("data/state.db", encoded.get(14));
    }

    @Test
    void serverDatabaseAndOtherComponentDoNotLoseTheirIndependentReferences() throws Exception {
        var file = new DeploymentFileBinding("state", new DeploymentStoragePath("data", DeploymentStoragePath.AccessMode.READ_WRITE, "files", true), DeploymentStorageLocation.defaults());
        var server = new DeploymentDatabaseBinding("state", new DeploymentDatabaseConnection.Server(DeploymentDatabaseEngineType.POSTGRESQL,
            "db.example", 5432, "app", "app", new DeploymentSecretRef("db-secret", 1), true));
        assertDoesNotThrow(() -> DeploymentAgentPlanResourceConstraints.require(part(new DeploymentResourceBindings(List.of(file), Optional.of(List.of(server)))), Optional.empty(), LAYOUT));
        var shared = new DeploymentResourceBindings(List.of(), Optional.of(List.of(sqlite("state", "data/state.db"))));
        var original = part(shared);
        var second = new DeploymentAgentPlan.Part("worker", ".", List.of(), List.of("o1"), original.runtime(), shared, List.of(), Optional.of(List.of("state")), List.of());
        assertDoesNotThrow(() -> DeploymentAgentPlanResourceConstraints.require(original, Optional.of("demo-api"), LAYOUT));
        assertDoesNotThrow(() -> DeploymentAgentPlanResourceConstraints.require(second, Optional.of("demo-worker"), LAYOUT));
        assertSame(shared, second.resources());
    }

    @Test
    void unknownDatabaseScopeBeforePreparationRemainsUnknownAndDoesNotRejectInitialPlan() throws Exception {
        var part = part(new DeploymentResourceBindings(List.of(), Optional.empty()));
        assertTrue(part.databaseIds().isEmpty());
        assertDoesNotThrow(() -> DeploymentAgentPlanResourceConstraints.require(part, Optional.empty(), LAYOUT));
        assertTrue(part.resources().databaseBindings().isEmpty());
    }

    @Test
    void unboundDefaultSqlitePathCannotBeGuessedFromGlobalLayoutOrComponentName() throws Exception {
        var part = part(new DeploymentResourceBindings(List.of(), Optional.of(List.of(sqlite("state", "")))));
        var failure = assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPlanResourceConstraints.require(part, Optional.empty(), LAYOUT));
        assertTrue(failure.getMessage().contains("existing candidate identity"));
    }

    private static DeploymentDatabaseBinding sqlite(String id, String path) {
        return new DeploymentDatabaseBinding(id, new DeploymentDatabaseConnection.Sqlite("state.db", DeploymentStorageLocation.defaults(), path));
    }

    private static DeploymentAgentPlan.Part part(DeploymentResourceBindings resources) throws Exception {
        var initial = DeploymentAgentPlanProtocol.parse(new ObjectMapper().readTree("""
            {"applicationId":"demo","components":[{"id":"api","sourceRoot":".","dependencies":[],"evidence":["o1"],
            "backend":"PROCESS","mode":"DAEMON","entrypoint":"run.sh","arguments":[],"workingDirectory":"",
            "workspace":{"mode":"AUTO","minimumBuildBytes":0,"preferredBuildBytes":134217728,"explicitBytes":0},
            "health":{"type":"PROCESS","timeout":30,"stability":3},"ports":[],"resources":[],"configuration":[],
            "databases":[],"secretReferences":[]}],"applicationHealth":{"component":"api","health":{"type":"PROCESS","timeout":30,"stability":3}}}
            """)).components().getFirst();
        return new DeploymentAgentPlan.Part(initial.id(), initial.sourceRoot(), initial.dependencies(), initial.evidence(), initial.runtime(), resources,
            initial.configuration(), resources.databaseBindings().map(bindings -> bindings.stream().map(DeploymentDatabaseBinding::databaseId).toList()), List.of());
    }
}
