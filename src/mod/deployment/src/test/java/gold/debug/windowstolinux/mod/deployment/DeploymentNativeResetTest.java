package gold.debug.windowstolinux.mod.deployment;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;

class DeploymentNativeResetTest extends DeploymentRuntimeFixture {
    @Test void oneTimeLifecycleResetPreservesSourceAppOtherOwnersAndNewRestartData() throws Exception {
        enable(); UUID first;
        Path other=root.resolve("data/mod/other.owner/cache.txt");
        Files.createDirectories(other.getParent()); Files.writeString(other,"preserve other Mod");
        String manifest=Files.readString(workspace().resolve("src/mod/deployment/mod.json"));
        try(var runtime=open()) {
            start(runtime,true);
            first=runtime.modContexts().get(OWNER).context().db().identity().generation();
            try(var access=scope(runtime,false);var c=access.openConnection();var s=c.createStatement()) {
                s.execute("INSERT INTO preference(preference_key,preference_value) VALUES('old-local-state','clear once')");
            }
            runtime.modIntents().apply(runtime.modIntents().review(OWNER,new PlatformModIntents.Choice(false,Optional.of(VERSION)),Optional.empty()),false);
        }
        try(var runtime=open()) {
            start(runtime,false);
            var preferences=new gold.debug.windowstolinux.app.db.setting.DesktopPreferenceStore(new gold.debug.windowstolinux.app.db.connect.StoreConnections(runtime.persistence().database()::openConnection));
            preferences.save("native_reset_fixture","preserve APP config");
            try(var installer=new PlatformModInstallation(runtime.persistence().database(),runtime.security(),root,runtime.logs(),gold.debug.windowstolinux.app.mod.version.SemanticVersion.parse("1.0.0"))) {
                var review=installer.deletion().review(OWNER);
                assertEquals(List.of("1"),review.removedSchemas().stream().map(SchemaIdentity::schemaVersion).toList());
                assertTrue(review.programs().isEmpty());
                assertTrue(review.ownedFiles().stream().allMatch(path->path.toAbsolutePath().startsWith(root)));
                assertEquals(TaskResult.SUCCEEDED,installer.deletion().delete(review,false).outcome());
            }
            assertEquals(0,scalar(runtime,"SELECT COUNT(*) FROM mod_schema WHERE mod_id='"+OWNER+"'"));
            assertEquals(0,scalar(runtime,"SELECT COUNT(*) FROM mod_registry WHERE mod_id='"+OWNER+"'"));
            assertEquals("preserve APP config",preferences.find("native_reset_fixture").orElseThrow());
        }
        assertEquals(manifest,Files.readString(workspace().resolve("src/mod/deployment/mod.json")));
        assertEquals("preserve other Mod",Files.readString(other));
        enable(); UUID replacement;
        try(var runtime=open()) {
            start(runtime,true);
            var identity=runtime.modContexts().get(OWNER).context().db().identity(); replacement=identity.generation();
            assertEquals("1",identity.schemaVersion()); assertNotEquals(first,replacement);
            try(var access=scope(runtime,false);var c=access.openConnection();var s=c.createStatement()) {
                var query=s.executeQuery("SELECT COUNT(*) FROM managed_application"); assertTrue(query.next()); assertEquals(0,query.getInt(1)); query.close();
                try(var rows=s.executeQuery("SELECT COUNT(*) FROM preference WHERE preference_key='old-local-state'")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
                s.execute("INSERT INTO managed_application(application_id,server_profile_id,updated_at) VALUES('retained','fixture',1)");
            }
        }
        try(var runtime=open()) {
            start(runtime,true); assertEquals(replacement,runtime.modContexts().get(OWNER).context().db().identity().generation());
            try(var access=scope(runtime,true);var c=access.openConnection();var s=c.createStatement();var query=s.executeQuery("SELECT COUNT(*) FROM managed_application WHERE application_id='retained'")) {
                assertTrue(query.next()); assertEquals(1,query.getInt(1));
            }
        }
    }
}
