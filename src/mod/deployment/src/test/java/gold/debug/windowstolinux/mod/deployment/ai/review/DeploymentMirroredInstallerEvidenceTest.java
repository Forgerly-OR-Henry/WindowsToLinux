package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentMirroredInstallerEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings={"PLAIN","FILES","TRACKED"})
    void everyInstallerRetainsExactStdinAndEveryScopeWithAndWithoutMirrors(String kind) throws Exception {
        verifyInstaller(kind);
    }

    @Test
    void customInstallerRetainsExactStdinAndEveryScopeWithAndWithoutMirrors() throws Exception {
        verifyInstaller("CUSTOM");
    }

    private void verifyInstaller(String kind) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture()) {
            for (boolean mirrored : List.of(false,true)) {
                String executable = fixture.installer(kind, mirrored);
                try (var request = fixture.request(executable)) {
                    var facts = JSON.valueToTree(fixture.facts(kind, request));
                    var evidence = facts.path("completeExecutableInput");
                    assertArrayEquals(executable.getBytes(StandardCharsets.UTF_8), DeploymentInstallerEvidence.reconstruct(evidence).getBytes(StandardCharsets.UTF_8));
                    DeploymentInstallerEvidence.verify(evidence, executable);
                    boolean custom = kind.equals("CUSTOM");
                    assertEquals(custom ? mirrored ? DeploymentInstallerEvidence.CUSTOM_MIRROR_FORMAT : DeploymentInstallerEvidence.CUSTOM_FORMAT
                        : mirrored ? DeploymentInstallerEvidence.MIRROR_FORMAT : "ORDERED_SHELL_LITERAL_V1", evidence.path("format").asText());
                    assertEquals((mirrored ? 3 : 2) + (custom ? 1 : 0), evidence.path("sources").size());
                    assertEquals((mirrored ? 7 : 5) + (custom ? 4 : 0), evidence.path("segments").size());
                    assertEquals(MirroredInstallerTestFixture.sha(executable), evidence.at("/digestScopes/requestDigest/value").asText());
                    if (kind.equals("TRACKED")) assertTrue(evidence.path("digestScopes").has("bodyDigest"));
                    if (mirrored) {
                        assertEquals(DeploymentMirrorRuntime.runtimeSource(), evidence.at("/sources/mirrorRuntime").textValue());
                        assertFalse(facts.path("mirrorTransport").has("runtimeSource"));
                        assertEquals(DeploymentInstallerEvidence.MIRROR_SOURCE_POINTER, facts.at("/mirrorTransport/runtimeSourcePointer").textValue());
                        DeploymentInstallerEvidence.verifySourceReferences(facts);
                        assertEquals(custom ? 10 : 7, DeploymentInstallerEvidence.readableBodies(evidence).size());
                        assertTrue(facts.path("exactAction").asText().contains("stdinSha256="+MirroredInstallerTestFixture.sha(executable)));
                    }
                    String current = DeploymentAiJson.facts(JSON.convertValue(facts, new TypeReference<Map<String,Object>>(){}), 1048576);
                    System.out.println("MIRRORED_INSTALLER_SIZE kind="+kind+" mirrored="+mirrored+" stdinBytes="+executable.getBytes(StandardCharsets.UTF_8).length+" factsCharacters="+current.length());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"RUNTIME_SOURCE","HELPER_SOURCE","POINTER","ORDER","DIGEST","MISSING","EXTRA","LITERAL"})
    void changedSourceReferenceOrderDigestOrWrapperStopsBeforeModelOrHost(String mutation) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var request = fixture.request(fixture.installer("TRACKED",true))) {
            ObjectNode current = JSON.valueToTree(fixture.facts("TRACKED",request));
            ObjectNode evidence = (ObjectNode)current.path("completeExecutableInput");
            switch(mutation) {
                case "RUNTIME_SOURCE" -> ((ObjectNode)evidence.path("sources")).put("mirrorRuntime",DeploymentMirrorRuntime.runtimeSource()+"# changed\n");
                case "HELPER_SOURCE" -> ((ObjectNode)evidence.path("sources")).put("managedHelper",evidence.at("/sources/managedHelper").asText()+"# changed\n");
                case "POINTER" -> ((ObjectNode)current.path("mirrorTransport")).put("runtimeSourcePointer","/completeExecutableInput/sources/buildEntry");
                case "ORDER" -> {var segments=(ArrayNode)evidence.path("segments");JsonNode first=segments.get(1).deepCopy();segments.set(1,segments.get(3).deepCopy());segments.set(3,first);}
                case "DIGEST" -> ((ObjectNode)evidence.at("/digestScopes/mirrorRuntimeDigest")).put("value","0".repeat(64));
                case "MISSING" -> ((ObjectNode)evidence.path("sources")).remove("mirrorRuntime");
                case "EXTRA" -> ((ObjectNode)evidence.path("sources")).put("unreviewed","printf changed\n");
                case "LITERAL" -> ((ObjectNode)evidence.path("segments").get(6)).put("text",evidence.at("/segments/6/text").asText()+"printf changed\n");
                default -> throw new AssertionError(mutation);
            }
            Map<String,Object> map=JSON.convertValue(current,new TypeReference<>(){});
            var ai=new DeploymentAiJson(fixture.context,fixture.conversation());
            var hosts=new java.util.concurrent.atomic.AtomicInteger();
            var host=(gold.debug.windowstolinux.appapi.task.action.InteractionService)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},(p,m,a)->{hosts.incrementAndGet();throw new AssertionError("Invalid evidence cannot obtain a host grant");});
            var protocol=new DeploymentCommandProtocol() {
                public boolean registered(gold.debug.windowstolinux.appapi.task.action.ActionIntent intent){return true;}
                public Map<String,Object> inspect(gold.debug.windowstolinux.appapi.task.action.ActionIntent intent,
                    gold.debug.windowstolinux.appapi.linux.command.CommandRequest actual,
                    gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout,
                    gold.debug.windowstolinux.appapi.task.run.TaskContext task){return map;}
            };
            var refused=assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class,()->DeploymentFixedCommandApproval.approve(
                fixture.context,host,fixture.intent("TRACKED",request),request,fixture.task,fixture.model.configurationRevision,"AUTOMATIC",false,
                DeploymentCommandReviewModel.submission(ai),DeploymentCurrentCommandEvidence.NONE,true,protocol));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID",refused.problem().eventCode());
            assertTrue(fixture.model.requests.isEmpty(),"Invalid represented bytes/ref must stop before the actual model");
            assertEquals(0,hosts.get());
        }
    }
}
