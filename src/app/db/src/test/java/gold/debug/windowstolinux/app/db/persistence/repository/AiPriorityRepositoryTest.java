package gold.debug.windowstolinux.app.db.persistence.repository;
import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.*;
import gold.debug.windowstolinux.shared.model.ai.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AiPriorityRepositoryTest {
    @TempDir Path directory;
    private StoredAiProviderProfile profile(String id){return new StoredAiProviderProfile(id,"https://example.test/v1/chat/completions","model","ai/"+id,"MASTER_PASSWORD");}
    @Test void displayAndPurposeOrderRemainIndependentAcrossRestart() throws Exception {
        try(var db=DesktopPersistence.open(directory)){
            var repo=db.aiProfiles();for(var id:List.of("a","b","c"))repo.saveVerified(profile(id),id,Instant.now());
            repo.purposes().save(AiPurposeType.DEPLOYMENT,List.of(new AiPurposeAssignment("a",false),new AiPurposeAssignment("c",true)));
            repo.purposes().save(AiPurposeType.APPROVAL,List.of(new AiPurposeAssignment("c",true),new AiPurposeAssignment("a",true)));
            repo.reorder(List.of("c","a","b"));
            assertThrows(SQLException.class,()->repo.reorder(List.of("b","b","c")));
            assertThrows(SQLException.class,()->repo.reorder(List.of("a","c")));
            repo.saveVerified(profile("a"),"Renamed",Instant.now());
        }
        try(var db=DesktopPersistence.open(directory)){
            var repo=db.aiProfiles();
            assertEquals(List.of("c","a","b"),repo.listConfigured().stream().map(v->v.profile().id()).toList());
            assertEquals(List.of("a","c"),repo.purposes().list(AiPurposeType.DEPLOYMENT).stream().map(AiPurposeAssignment::profileId).toList());
            assertEquals(List.of("c"),repo.configuredFor(AiPurposeType.DEPLOYMENT).stream().map(v->v.profile().id()).toList());
            assertEquals(List.of("c","a"),repo.configuredFor(AiPurposeType.APPROVAL).stream().map(v->v.profile().id()).toList());
            repo.saveNamed(profile("d"));assertEquals("d",repo.listConfigured().getLast().profile().id());
            assertEquals(2,repo.purposes().list(AiPurposeType.DEPLOYMENT).size());
        }
    }
    @Test void capabilityChangesInvalidateOldEvidenceAndFailedMembershipSaveIsAtomic() throws Exception {
        try(var db=DesktopPersistence.open(directory)){
            var repo=db.aiProfiles();repo.saveVerified(profile("a"),"A",Instant.now());
            repo.recordVerification(profile("a"),AiCapabilityType.VISION,Instant.now());
            var members=List.of(new AiPurposeAssignment("a",true));
            for(var type:AiPurposeType.values())repo.purposes().save(type,members);
            assertThrows(IllegalArgumentException.class,()->repo.purposes().save(AiPurposeType.APPROVAL,List.of(members.getFirst(),members.getFirst())));
            assertThrows(SQLException.class,()->repo.purposes().save(AiPurposeType.APPROVAL,List.of(new AiPurposeAssignment("missing",true))));
            assertEquals(members,repo.purposes().list(AiPurposeType.APPROVAL));
            var changed=new StoredAiProviderProfile("a",profile("a").endpoint(),"changed","new/exact/key","MASTER_PASSWORD");
            repo.saveVerified(changed,"A",Instant.now(),AiCapabilityType.TEXT);
            assertTrue(repo.configuredFor(AiPurposeType.VISION).isEmpty());
            assertEquals(1,repo.configuredFor(AiPurposeType.APPROVAL).size());
            assertThrows(SQLException.class,()->repo.recordVerification(profile("a"),AiCapabilityType.VISION,Instant.now()));
            assertTrue(repo.listConfigured().getFirst().visionVerifiedAt().isEmpty());
            repo.purposes().save(AiPurposeType.APPROVAL,List.of());
            assertEquals(1,repo.listConfigured().size());assertEquals(1,repo.purposes().list(AiPurposeType.DEPLOYMENT).size());
        }
    }
    @Test void migratesLegacyRoleOrderWithoutInventingApprovalOrVerification() throws Exception {
        try(var db=DesktopPersistence.open(directory)){
            var repo=db.aiProfiles();for(var id:List.of("unbound","review","analysis"))repo.saveNamed(profile(id));
            repo.saveDefault(new StoredAiProfile("https://legacy.test/v1/chat/completions","legacy","old/exact/key","MASTER_PASSWORD"));
            repo.saveRoleAssignment(new StoredAiRoleAssignment("PROJECT_ANALYSIS","analysis"));
            repo.saveRoleAssignment(new StoredAiRoleAssignment("DEPLOYMENT_RISK_REVIEW","review"));
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("windowstolinux.db"));var s=c.createStatement()){
            s.execute("DROP TABLE deployment_agent_event");s.execute("DROP TABLE deployment_agent_task");
            s.execute("DROP TABLE ai_model_purpose");s.execute("DROP TABLE ai_model_verification");s.execute("DROP TABLE ai_model_inventory");s.execute("PRAGMA user_version=14");
        }
        try(var db=DesktopPersistence.open(directory)){
            var values=db.aiProfiles().listConfigured();
            assertEquals(List.of("analysis","review","default","unbound"),values.stream().map(v->v.profile().id()).toList());
            assertEquals(List.of(true,true,false,false),db.aiProfiles().purposes().list(AiPurposeType.DEPLOYMENT).stream().map(AiPurposeAssignment::enabled).toList());
            assertTrue(values.stream().allMatch(v->v.textVerifiedAt().isEmpty()));
            assertTrue(db.aiProfiles().purposes().list(AiPurposeType.APPROVAL).isEmpty());
            assertEquals("old/exact/key",values.get(2).profile().credentialKey());
        }
    }
}
