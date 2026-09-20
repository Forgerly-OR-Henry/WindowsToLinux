package gold.debug.windowstolinux.web.service;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleRoleClient;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatResult;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.*;
import gold.debug.windowstolinux.shared.linux.protocol.*;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.transfer.*;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.capability.*;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.server.*;
import gold.debug.windowstolinux.shared.model.server.security.*;
import gold.debug.windowstolinux.web.db.WebPersistence;
import gold.debug.windowstolinux.web.db.WebDatabaseTestContext;
import gold.debug.windowstolinux.web.db.persistence.repository.*;
import gold.debug.windowstolinux.web.file.quota.UploadQuota;
import gold.debug.windowstolinux.web.file.workspace.WebWorkspace;
import gold.debug.windowstolinux.web.secret.masterkey.WebMasterKey;
import gold.debug.windowstolinux.web.secret.crypto.WebSecretCipher;
import gold.debug.windowstolinux.web.secret.credential.WebCredentialStore;
import gold.debug.windowstolinux.web.service.ai.WebAiService;
import gold.debug.windowstolinux.web.service.config.WebApplicationSecrets;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.deployment.WebDeploymentService;
import gold.debug.windowstolinux.web.service.execution.lifecycle.WebApplicationInventory;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import gold.debug.windowstolinux.web.service.source.WebSourceService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WebBusinessTest {
    @TempDir Path root;
    private final WebRequestContext context=new WebRequestContext("internal","internal");
    private WebMasterKey key;private WebDatabaseTestContext database;private WebResourceRepository resources;
    private WebServerService servers;private WebSourceService sources;private WebApplicationInventory inventory;
    private WebApplicationSecrets secrets;private WebAiService ai;private WebDeploymentService deployment;
    private String serverId;private boolean failBuild,declineEnvironment,invalidAi,failRestore,failRecovery;private final List<String> calls=new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String,RuntimeState> runtimeStates=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,String> uploaded=new java.util.concurrent.ConcurrentHashMap<>();
    private String decisionSecret;
    @BeforeEach void setup() throws Exception {
        database=new WebDatabaseTestContext(root.resolve("db"));resources=database.resources();
        key=new WebMasterKey(new byte[32]);
        var credentials=new WebCredentialStore(database.secrets(),new WebSecretCipher(key));
        servers=new WebServerService(resources,credentials,(endpoint,credential,verifier)->{
            calls.add("connect");String fingerprint="SHA256:abcdefghijklmnop";
            assertNotEquals(HostKeyDecision.REJECT,verifier.verify(endpoint,fingerprint));
            assertTrue(verifier.authenticated(endpoint,new HostKeyObservation(fingerprint,"legacy")));return remote(endpoint.serverId());
        });
        sources=new WebSourceService(resources,new WebWorkspace(root.resolve("files"),new UploadQuota(128L<<20,1L<<30,4L<<30,10000,240),64L<<20),java.time.Duration.ofHours(24));
        inventory=new WebApplicationInventory(resources,database.tasks(),servers,java.time.Duration.ofMinutes(5));secrets=new WebApplicationSecrets(credentials);
        ai=new WebAiService(resources,credentials,new OpenAiCompatibleRoleClient((endpoint,secret,body)->{
            calls.add(endpoint.getHost());
            return new RoleChatResult(200,invalidAi?"{}":WebJson.write(Map.of("choices",List.of(Map.of("message",Map.of("content","{\"decision\":\"CLEAR\",\"summary\":\"verified\",\"findings\":[]}"))))));
        },Clock.systemUTC()));
        deployment=new WebDeploymentService(servers,sources,inventory,secrets,ai);
        serverId=servers.save(context,null,WebJson.read("{\"name\":\"Test\",\"host\":\"192.0.2.10\",\"port\":22,\"username\":\"root\",\"password\":\"synthetic\"}")).path("id").asText();
    }
    @AfterEach void cleanup(){key.close();database.close();}

    @Test void sourceDeployAndLifecyclePersistExactPortableIdentity() throws Exception {
        String source=source(Map.of("index.html","<h1>Hello</h1>"));
        var result=deploy(source,Map.of("port","18080"));assertEquals("SUCCEEDED",result.path("status").asText());
        String id=result.path("applicationId").asText();var graph=inventory.graph(inventory.require(context,id));
        assertTrue(graph.components().getFirst().releaseIdentity().matches("[a-f0-9]{64}"));assertEquals(1,Collections.frequency(calls,"prepareEnvironment"));
        assertTrue(inventory.list(context).get(0).path("accessUrl").asText().contains("18080"));
        var stopped=inventory.lifecycle(context,id,"STOP").work().execute(interaction());assertEquals("STOPPED",stopped.path("state").asText(),stopped.toString());
        try(var connection=database.source().getConnection();var q=connection.createStatement();var rows=q.executeQuery("SELECT count(*) FROM configuration_revisions")) { assertTrue(rows.next());assertEquals(1,rows.getInt(1)); }
        assertEquals(graph,inventory.graph(database.resources().find(WebPersistence.INTERNAL_SCOPE,gold.debug.windowstolinux.web.db.entity.ResourceType.APPLICATION,id).orElseThrow()));
        try(var paths=Files.walk(root.resolve("files"))) {assertFalse(paths.anyMatch(path->path.getFileName().toString().startsWith("source-snapshot-")));}
    }
    @Test void decliningEnvironmentOrMissingSecretCannotStartRemoteMutation() throws Exception {
        String source=source(Map.of("index.html","<h1>Hello</h1>"));declineEnvironment=true;
        assertThrows(java.util.concurrent.CancellationException.class,()->deploy(source,Map.of("port","18080")));
        assertFalse(calls.contains("prepareEnvironment"));declineEnvironment=false;
        assertThrows(Exception.class,()->deploy(source,Map.of("port","18080","secrets","missing.env.api-token:1")));
        assertFalse(calls.contains("prepareEnvironment"));assertFalse(calls.contains("publishDeployment"));
    }

    @Test void onDemandToolPublishesAsInstalledAndCannotBeStartedThroughTheBackend() throws Exception {
        String source=source(Map.of("pyproject.toml", "[project]\nname='console'\nversion='1.0'\nrequires-python='>=3.12,<3.13'\ndependencies=[]\n",
                "main.py", "print('usage')\n", "windowstolinux-application.properties",
                "version=1\nprojectType=PYTHON_SERVICE\nmode=ON_DEMAND\nruntime.primary=3.12\nruntime.secondary=main\ncommand.entrypoint=main.py\nverification.entrypoint=main.py\nverification.arg.0=--help\nexpectedOutput=usage\n"));
        var result=deploy(source,Map.of("primary","3.12"));
        assertEquals("SUCCEEDED",result.path("status").asText());
        var card=inventory.list(context).get(0);
        assertEquals("APP",card.path("category").asText());
        assertEquals("INSTALLED",card.path("observation").path("state").asText());
        assertFalse(card.path("canLifecycle").asBoolean());
        assertTrue(card.path("usage").get(0).path("command").asText().contains(" app-run "));
        for(String action:List.of("START","STOP","RESTART","ENABLE_AUTOSTART","DISABLE_AUTOSTART"))
            assertThrows(IllegalArgumentException.class,()->inventory.lifecycle(context,result.path("applicationId").asText(),action));
        assertFalse(calls.contains("executeLifecycle"));
        assertTrue(inventory.graph(inventory.require(context,result.path("applicationId").asText())).components().getFirst().configuration().configuration().entries().isEmpty());
    }
    @Test void invalidGraphAndSecretLikeConfigurationAreRejectedBeforeSsh() throws Exception {
        String source=source(Map.of("a/index.html","a","b/index.html","b"));
        assertThrows(Exception.class,()->deploy(source,Map.of("a/port","18080","b/port","18081","a/dependencies","b","b/dependencies","a")));
        assertFalse(calls.contains("connect"));
        assertThrows(Exception.class,()->deploy(source,Map.of("configuration","API_TOKEN=synthetic")));assertFalse(calls.contains("connect"));
    }
    @Test void failedBuildDoesNotPublishOrInventAReleaseIdentity() throws Exception {
        failBuild=true;var result=deploy(source(Map.of("index.html","hello")),Map.of("port","18080"));
        assertNotEquals("SUCCEEDED",result.path("status").asText());assertFalse(calls.contains("publishDeployment"));
        assertEquals("",inventory.graph(inventory.require(context,result.path("applicationId").asText())).components().getFirst().releaseIdentity());
    }
    @Test void multiComponentPublicationRetainsEveryExactReleaseAndTopology() throws Exception {
        var result=deploy(source(Map.of("a/index.html","a","b/index.html","b")),Map.of("a/port","18080","b/port","18081","a/dependencies","","b/dependencies","a"));
        assertEquals("SUCCEEDED",result.path("status").asText(),result.toString());
        var graph=inventory.graph(inventory.require(context,result.path("applicationId").asText()));assertEquals(2,graph.components().size());
        assertTrue(graph.components().stream().allMatch(component->component.releaseIdentity().matches("[a-f0-9]{64}")));
        assertEquals(List.of("a"),graph.plan().dependencies().get("b"));
    }

    @Test void encryptedPortableBackupRestoresThroughTheSharedCandidateFlow() throws Exception {
        var secret=secrets.save(context,WebJson.read("{\"environment\":\"API_TOKEN\",\"value\":\"synthetic-application-token\"}"));
        var result=deploy(source(Map.of("index.html","hello")),Map.of("port","18080","secrets",secret.path("identifier").asText()+":1"));
        assertEquals("SUCCEEDED",result.path("status").asText());String application=result.path("applicationId").asText();
        var backups=backups();char[] password="synthetic-backup-pass".toCharArray();
        var saved=backups.create(context,application,password,interaction());assertEquals(RuntimeState.RUNNING,runtimeStates.get(serverId));
        Path archive=backups.download(context,saved.path("id").asText());
        try(var material=gold.debug.windowstolinux.web.service.backup.WebRestoreMaterial.read(archive,Files.createDirectory(root.resolve("inspection")),password.clone(),4L<<30,64L<<20)) {
            assertEquals("6",material.validation().manifest().schemaVersion());assertEquals(1,material.secrets().size());
            assertEquals("synthetic-application-token",new String(material.secrets().getFirst().copyCharacters()));
        }
        int before=calls.size();assertThrows(Exception.class,()->backups.restore(context,saved.path("id").asText(),serverId,"wrong-password".toCharArray(),interaction(),false));assertEquals(before,calls.size());
        var restored=backups.restore(context,saved.path("id").asText(),serverId,password,interaction(),false);assertEquals("SUCCEEDED",restored.path("status").asText(),restored.toString());
        assertTrue(calls.indexOf("stageRestoreFiles")<calls.indexOf("commitRestoreActivation"));
        assertFalse(new String(Files.readAllBytes(archive),java.nio.charset.StandardCharsets.ISO_8859_1).contains("synthetic-application-token"));
    }

    private gold.debug.windowstolinux.web.service.backup.WebBackupService backups() throws Exception {
        return new gold.debug.windowstolinux.web.service.backup.WebBackupService(resources,new WebWorkspace(root.resolve("backups"),new UploadQuota(4L<<30,8L<<30,16L<<30,10000,240),64L<<20),servers,inventory,secrets);
    }

    @Test void restoreFailurePreservesExistingAndUnverifiedRecoveryRequiresReview() throws Exception {
        var result=deploy(source(Map.of("index.html","hello")),Map.of("port","18080"));
        var backups=backups();var saved=backups.create(context,result.path("applicationId").asText(),"test-pass".toCharArray(),interaction());
        failRestore=true;
        var failed=backups.restore(context,saved.path("id").asText(),serverId,"test-pass".toCharArray(),interaction(),false);
        assertEquals("FAILED_EXISTING_PRESERVED",failed.path("status").asText());assertTrue(calls.contains("recoverRestoreActivation"));
        failRecovery=true;
        var manual=backups.restore(context,saved.path("id").asText(),serverId,"test-pass".toCharArray(),interaction(),false);
        assertEquals("MANUAL_RECOVERY_REQUIRED",manual.path("status").asText());assertTrue(calls.contains("REVALIDATION_REQUIRED"));
        assertThrows(IllegalStateException.class,()->inventory.lifecycle(context,result.path("applicationId").asText(),"START").work().execute(interaction()));
        assertThrows(IllegalStateException.class,()->backups.create(context,result.path("applicationId").asText(),"test-pass".toCharArray(),interaction()));
    }

    @Test void offlineMigrationStopsSourceAndLeavesTrafficSwitchManual() throws Exception {
        var result=deploy(source(Map.of("index.html","hello")),Map.of("port","18080"));assertEquals("SUCCEEDED",result.path("status").asText());
        String target=servers.save(context,null,WebJson.read("{\"name\":\"Target\",\"host\":\"192.0.2.11\",\"port\":22,\"username\":\"root\",\"password\":\"synthetic\"}")).path("id").asText();
        decisionSecret=secrets.save(context,WebJson.read("{\"value\":\"synthetic-backup-pass\"}")).path("secretId").asText();
        var migrated=backups().prepare(context,"MIGRATE",WebJson.object().put("applicationId",result.path("applicationId").asText()).put("targetServerId",target)).work().execute(interaction());
        assertEquals("READY_FOR_MANUAL_TRAFFIC_SWITCH",migrated.path("status").asText(),migrated.toString());
        assertFalse(migrated.path("externalTrafficChanged").asBoolean());assertTrue(migrated.path("sourceRetained").asBoolean());
        assertEquals(RuntimeState.STOPPED,runtimeStates.get(serverId));assertEquals(RuntimeState.RUNNING,runtimeStates.get(target));assertEquals(2,inventory.list(context).size());
    }
    @Test void aiSaveIsValidatedAndEndpointChangeCannotReuseTheOldKey() throws Exception {
        var input=WebJson.read("{\"name\":\"Test AI\",\"endpoint\":\"https://provider.invalid/v1/chat/completions\",\"model\":\"test-model\",\"apiKey\":\"synthetic-key\"}");
        var operation=ai.save(context,null,input);assertFalse(operation.request().toString().contains("synthetic-key"));
        var saved=operation.work().execute(interaction());assertEquals("Test AI",saved.path("name").asText(),saved.toString());assertFalse(saved.toString().contains("synthetic-key"));
        var changed=((tools.jackson.databind.node.ObjectNode)input).deepCopy().put("endpoint","https://other.invalid/v1/chat/completions").put("apiKey","").put("version",saved.path("version").asLong());
        assertThrows(IllegalArgumentException.class,()->ai.save(context,saved.path("id").asText(),changed));
        invalidAi=true;var failed=ai.save(context,null,input).work().execute(interaction());assertEquals("AI_TEST_FAILED",failed.path("status").asText());assertEquals(1,ai.list(context).size());
    }
    private JsonNode deploy(String id,Map<String,String> inputs) throws Exception {
        var values=new HashMap<>(inputs);values.putIfAbsent("primary","public");
        return deployment.prepare(context,WebJson.object().put("sourceId",id).put("serverId",serverId).set("overrides",WebJson.tree(values))).work().execute(interaction());
    }
    private String source(Map<String,String> files) throws Exception {
        String id=sources.begin(context,"sample").path("id").asText();
        for(var entry:files.entrySet()) {
            sources.upload(context,id,entry.getKey(),new ByteArrayInputStream(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if(entry.getKey().endsWith("index.html"))sources.upload(context,id,entry.getKey().replace("index.html","public/index.html"),new ByteArrayInputStream(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        sources.finish(context,id);return id;
    }
    private TaskInteraction interaction(){return new TaskInteraction(){
        @Override public void progress(String code,JsonNode details){calls.add(code);}
        @Override public void checkCancelled() throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException();}
        @Override public void completion(OperationCompletionState state){calls.add(state.name());}
        @Override public JsonNode decide(String kind,JsonNode prompt){
            if(kind.equals("SECRET"))return WebJson.object().put("secretId",decisionSecret);
            calls.add(kind);if(kind.equals("INPUTS")){var values=WebJson.object();for(var field:prompt.path("fields")){String id=field.path("id").asText();values.put(id,field.path("choices").isEmpty()?field.path("value").asText():field.path("choices").get(0).asText());}return WebJson.object().set("values",values);}
            return WebJson.object().put("accepted",!(declineEnvironment&&prompt.path("code").asText().equals("ENVIRONMENT_PREPARATION")));
        }
    };}
    private DeploymentRemoteSession remote(String connectedServer){return (DeploymentRemoteSession)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DeploymentRemoteSession.class},(proxy,method,args)->{
        calls.add(method.getName());return switch(method.getName()){
            case "collectCapabilities" -> new ServerCapabilityFacts("Ubuntu 24.04","x86_64",true,true,true,true,true,true,true,true,ManagedHelperProtocolVersion.CURRENT,20L<<30,"fixture");
            case "collectDeploymentCapabilities" -> new LinuxCapabilityFacts(LinuxDistroType.UBUNTU,"24.04","x86_64","apt","amd64",true,true,true,true,Set.of(21),Set.of(22),true,true,Set.of("3.12"),true,Map.of(),Map.of(),true,true,CpuMicroarchitectureLevel.X86_64_V3,Set.of("sse4_2"),new LinuxSecurityPosture(LinuxSecurityModuleType.APPARMOR,LinuxSecurityState.ENABLED,LinuxFirewallKind.UFW,LinuxFirewallState.ACTIVE),"fixture");
            case "prepareEnvironment" -> new EnvironmentSetupResult(((DeploymentRemoteSession)proxy).collectCapabilities(),"fixture");
            case "selinuxPreparation" -> Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.shared.linux.distro.SelinuxEnvironmentPreparer.class},(p,m,a)->{if(m.getName().equals("inspect"))return Optional.empty();throw new AssertionError(m.getName());});
            case "uploadSource" -> {var archive=(SourceArchiveDescriptor)args[0];var workspace=(RemoteWorkspace)args[1];uploaded.put(workspace.candidateRoot(),archive.contentSha256());yield new SourceUploadResult(workspace.candidateRoot()+"/mutable/source.tar.gz",archive.byteCount(),archive.contentSha256(),"fixture");}
            case "buildDeployment" -> new DeploymentBuildResult(!failBuild,uploaded.get(((RemoteWorkspace)args[2]).candidateRoot()),"fixture");
            case "stageDeploymentInputs" -> new RemoteDeploymentInputs(((RemoteRuntimeConfiguration)args[1]).sha256(),((List<?>)args[2]).stream().map(value->((RemoteSecretPayload)value).digest()).toList());
            case "snapshotDeployment" -> ReleaseSnapshot.firstDeployment("fixture");
            case "publishDeployment","retainRecentSuccessfulReleases","cleanupCandidate","rollbackDeployment" -> new RemoteStepResult(true,false,"fixture");
            case "checkDeploymentHealth","checkHealth" -> new HealthCheckResult(true,"fixture");
            case "observeDeployment","executeDeploymentLifecycle","observe","executeLifecycle" -> {
                if(args.length>1 && args[1] instanceof gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification runtime
                        && runtime.workload().mode()==gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode.ON_DEMAND)
                    yield new LifecycleObservation((ManagedApplication)args[0],RuntimeState.INSTALLED,AutostartState.DISABLED,true,Instant.now(),"fixture installed");
                if(method.getName().startsWith("execute"))for(var arg:args)if(arg instanceof LifecycleAction action)runtimeStates.put(connectedServer,action==LifecycleAction.STOP?RuntimeState.STOPPED:RuntimeState.RUNNING);
                yield new LifecycleObservation((ManagedApplication)args[0],runtimeStates.getOrDefault(connectedServer,RuntimeState.RUNNING),AutostartState.DISABLED,true,Instant.now(),"fixture");
            }
            case "databaseOperations" -> Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort.class},(p,m,a)->{throw new AssertionError("No database expected");});
            case "backupArtifacts" -> backupPort();
            case "restoreActivation" -> restorePort(connectedServer);
            case "stageRestoreFiles" -> {var request=(gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreStagingRequest)args[0];yield new gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreStagingEvidence(request.candidateId(),"/var/lib/windowstolinux/work/"+request.candidateId()+"/mutable/restore",request.archiveSha256().substring(0,32),request.expectedBytes(),true,true,true,List.of("fixture"));}
            case "discardRestoreFiles" -> new RemoteStepResult(true,false,"fixture");
            case "close" -> null;
            default -> {if(method.isDefault())yield java.lang.reflect.InvocationHandler.invokeDefault(proxy,method,args);throw new AssertionError("Unexpected remote method "+method.getName());}
        };
    });}

    private Object backupPort() throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();
        try(var tar=new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(bytes)) {
            var entry=new org.apache.commons.compress.archivers.tar.TarArchiveEntry("public/index.html");byte[] data="fixture publication".getBytes(java.nio.charset.StandardCharsets.UTF_8);entry.setSize(data.length);tar.putArchiveEntry(entry);tar.write(data);tar.closeArchiveEntry();
        }
        byte[] data=bytes.toByteArray();String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactPort.class},(p,m,a)->{
            calls.add(m.getName());return switch(m.getName()){
                case "beginMaintenance", "endMaintenance" -> { assertTrue(((String)a[1]).matches("(?:deployment-[a-f0-9]{64}|(?:backup|restore)-[a-f0-9]{32})")); yield null; }
                case "createBackupArtifact" -> {var request=(gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactRequest)a[0];yield new gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifact(request.operationId(),"artifact-"+"a".repeat(32),request.kind(),data.length,sha);}
                case "copyBackupArtifact" -> {((java.io.OutputStream)a[1]).write(data);yield null;}
                case "discardBackupOperation" -> new RemoteStepResult(true,false,"fixture");
                default -> throw new AssertionError(m.getName());
            };
        });
    }
    private Object restorePort(String connectedServer) {
        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort.class},(p,m,a)->{
            calls.add(m.getName());return switch(m.getName()){
                case "inspectRestoreActivation" -> new gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort.PreflightEvidence(true,false,20L<<30,Set.of(),List.of(m.getName()));
                case "commitRestoreActivation" -> {runtimeStates.put(connectedServer,RuntimeState.RUNNING);yield new gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort.CommitEvidence(true,true,true,true,"restored",List.of(m.getName()));}
                case "recoverRestoreActivation" -> new gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort.RecoveryEvidence(!failRecovery,!failRecovery,List.of(m.getName()));
                case "verifyRestoreComponents" -> new gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort.StepEvidence(!failRestore,List.of(m.getName()));
                default -> new gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort.StepEvidence(true,List.of(m.getName()));
            };
        });
    }
}
