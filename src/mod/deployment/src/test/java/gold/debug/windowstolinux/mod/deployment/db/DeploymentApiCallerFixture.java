package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.mod.dev.DevelopmentInfo;
import gold.debug.windowstolinux.app.mod.load.ModClassLoader;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.mod.lifecycle.StopContext;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;

/** Real CLASS caller and production provider, with distinct PF4J loaders and the exported contract. / 真实 CLASS 调用方及生产提供方，使用独立 PF4J 加载器与公开合同。 */
final class DeploymentApiCallerFixture {
    static final String CALLER = "example.deploymentcaller";

    static final String PROVIDER = DeploymentSchema.OWNER;

    static final String VERSION = "1.1.0-dev.1";

    static final String API_PACKAGE = "gold.debug.windowstolinux.mod.deployment.api";

    final PlatformRuntime runtime;

    final ModClassLoader providerLoader, callerLoader;

    final Class<?> callerClass;

    DeploymentApiCallerFixture(PlatformRuntime runtime) throws Exception {
        this.runtime = runtime;
        // Read-only host metadata: both sessions and loaders belong to the actual startup coordinator.
        providerLoader = activeLoader(runtime, PROVIDER);
        callerLoader = activeLoader(runtime, CALLER);
        callerClass = callerLoader.loadClass(CALLER + ".Mod");
        assertNotSame(providerLoader, callerLoader);
        assertSame(providerLoader.loadClass(API_PACKAGE + ".DeploymentApi"), callerLoader.loadClass(API_PACKAGE + ".DeploymentApi"));
        assertSame(providerLoader, providerLoader.loadClass(API_PACKAGE + ".DeploymentApi").getClassLoader());
        assertSame(callerLoader, callerClass.getClassLoader());
    }

    private static ModClassLoader activeLoader(PlatformRuntime runtime, String owner) throws Exception {
        var original = Objects.requireNonNull(runtime.modSessions().get(owner), "Actual startup session required: " + owner);
        assertEquals("RUNNING", original.state().name());
        var loader = original.getClass().getDeclaredField("loader");
        loader.setAccessible(true);
        return (ModClassLoader) loader.get(original);
    }

    /** Copies the actual provider build and generates both official CLASS descriptions before the preparation boot. / 在准备启动前复制实际提供方构建并生成两个官方 CLASS 描述。 */
    static Path prepareWorkspace(Path workspace, Path temporary) throws Exception {
        workspace = DevelopmentInfo.workspace(workspace);
        var descriptor = DevelopmentInfo.read(workspace,
            workspace.resolve("src/mod/deployment/target/wtlmod-development.json"));
        assertEquals(PROVIDER, descriptor.manifest().id());
        assertEquals(VERSION, descriptor.manifest().version().toString());
        Path callerWorkspace = temporary.toAbsolutePath().normalize();
        Files.createDirectories(callerWorkspace);
        callerWorkspace = callerWorkspace.toRealPath();
        for (String anchor : List.of("pom.xml", "src/app/pom.xml", "src/mod/pom.xml")) {
            copyVerified(workspace.resolve(anchor), callerWorkspace.resolve(anchor));
        }
        Path providerManifest = callerWorkspace.resolve(workspace.relativize(descriptor.manifestPath()));
        copyVerified(descriptor.manifestPath(), providerManifest);
        var inputs = new ArrayList<Path>();
        for (Path input : descriptor.buildInputs()) {
            Path copied = callerWorkspace.resolve(workspace.relativize(input));
            copyVerified(input, copied);
            inputs.add(copied);
        }
        Path providerClasses = copyOutput(workspace, callerWorkspace, descriptor.mainOutput());
        var authors = new ArrayList<Path>();
        for (Path output : descriptor.authorOutputs()) authors.add(copyOutput(workspace, callerWorkspace, output));
        var libraries = new ArrayList<Path>();
        for (int i = 0; i < descriptor.libraries().size(); i++) {
            Path library = descriptor.libraries().get(i);
            Path copied = callerWorkspace.resolve("private-libraries").resolve(Integer.toString(i)).resolve(library.getFileName());
            copyVerified(library, copied);
            libraries.add(copied);
        }
        assertEquals(descriptor, DevelopmentInfo.read(workspace,
            workspace.resolve("src/mod/deployment/target/wtlmod-development.json")), "Actual provider provenance changed while copying");
        Path providerDescription = DevelopmentInfo.generate(callerWorkspace, providerManifest, providerClasses, authors, inputs, libraries);
        var copiedProvider = DevelopmentInfo.read(callerWorkspace, providerDescription);
        assertEquals(descriptor.manifest().json(), copiedProvider.manifest().json());
        Path module = callerWorkspace.resolve("src/mod/caller");
        Files.createDirectories(module);
        Path classes = compileCaller(module, providerClasses);
        String manifest = """
            {"formatVersion":1,"id":"example.deploymentcaller","name":"Deployment API caller fixture",
             "version":"1.1.0-dev.1","platformApiRange":"[1.0.0-dev.1,1.0.0)","minJavaVersion":21,
             "mainJar":"main/caller.jar","entryClass":"example.deploymentcaller.Mod",
             "dependencies":[{"id":"%s","versionRange":"[1.1.0-dev.1,1.1.0)"}]}
            """.formatted(PROVIDER);
        Files.writeString(module.resolve("mod.json"), manifest);
        Files.writeString(module.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>example</groupId><artifactId>deployment-caller-fixture</artifactId><version>1.1.0-dev.1</version>
            <dependencies><dependency><groupId>gold.debug.windowstolinux</groupId><artifactId>windowstolinux-appapi</artifactId>
            <version>1.0.0-dev.1</version><scope>provided</scope></dependency>
            <dependency><groupId>gold.debug.windowstolinux</groupId><artifactId>windowstolinux-mod-deployment</artifactId>
            <version>1.1.0-dev.1</version><scope>provided</scope></dependency></dependencies></project>
            """);
        Path callerDescription = DevelopmentInfo.generate(callerWorkspace, module.resolve("mod.json"), classes, List.of(),
            List.of(callerWorkspace.resolve("pom.xml"), callerWorkspace.resolve("src/mod/pom.xml"), module.resolve("pom.xml")), List.of());
        assertEquals(CALLER, DevelopmentInfo.read(callerWorkspace, callerDescription).manifest().id());
        return callerWorkspace;
    }

    private static Path copyOutput(Path workspace, Path temporary, Path output) throws Exception {
        assertTrue(output.startsWith(workspace));
        assertEquals(output, output.toRealPath());
        Path copied = temporary.resolve(workspace.relativize(output));
        try (var paths = Files.walk(output)) {
            for (Path source : paths.sorted().toList()) {
                assertFalse(Files.isSymbolicLink(source), "Actual compiled source cannot redirect: " + source);
                Path target = copied.resolve(output.relativize(source));
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else copyVerified(source, target);
            }
        }
        return copied;
    }

    private static void copyVerified(Path original, Path copied) throws Exception {
        assertEquals(original, original.toRealPath());
        var before = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertTrue(before.isRegularFile());
        byte[] digest = fileDigest(original);
        Files.createDirectories(copied.getParent());
        if (!Files.exists(copied)) Files.copy(original, copied, StandardCopyOption.COPY_ATTRIBUTES);
        assertEquals(copied, copied.toRealPath());
        assertArrayEquals(digest, fileDigest(copied), "Copied source differs: " + original);
        assertArrayEquals(digest, fileDigest(original), "Original source changed during copy: " + original);
        var after = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertEquals(before.fileKey(), after.fileKey());
        assertEquals(before.size(), after.size());
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
    }

    private static byte[] fileDigest(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = stream.read(buffer)) != -1; ) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    Object invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        try {
            return callerClass.getMethod(name, types).invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw (Error) failure.getCause();
        }
    }

    TaskHandle<?> collect(String application, String revision, List<String> bindings, Runnable between) throws Exception {
        return (TaskHandle<?>) invoke("collect", new Class<?>[]{String.class, String.class, List.class, Runnable.class},
            application, revision, bindings, between);
    }

    TaskHandle<?> boundaries() throws Exception {
        return (TaskHandle<?>) invoke("boundaries", new Class<?>[]{});
    }

    TaskHandle<?> occupancy(Runnable retained) throws Exception {
        return (TaskHandle<?>) invoke("occupancy", new Class<?>[]{Runnable.class}, retained);
    }

    TaskHandle<?> restore(Map<String, Object> archive, Path files, Runnable prepared) throws Exception {
        return (TaskHandle<?>) invoke("restore", new Class<?>[]{Map.class, Path.class, Runnable.class}, archive, files, prepared);
    }

    TaskHandle<?> restoreRejected(Map<String, Object> archive, Path files, String code, String event, Runnable reviewed) throws Exception {
        return (TaskHandle<?>) invoke("restoreRejected", new Class<?>[]{Map.class, Path.class, String.class, String.class, Runnable.class},
            archive, files, code, event, reviewed);
    }

    TaskHandle<?> lostPrepare(Map<String, Object> archive, Path files, boolean prepared,
                              Runnable fault, Runnable malformed, Runnable lookup) throws Exception {
        return (TaskHandle<?>) invoke("lostPrepare", new Class<?>[]{Map.class, Path.class, boolean.class,
            Runnable.class, Runnable.class, Runnable.class}, archive, files, prepared, fault, malformed, lookup);
    }

    UUID handoff() throws Exception {
        return (UUID) callerClass.getField("handoff").get(null);
    }

    static Path compileCaller(Path temporary, Path providerClasses) throws Exception {
        Path source = temporary.resolve("src/main/java/example/deploymentcaller/Mod.java");
        Path classes = temporary.resolve("target/classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, SOURCE);
        String classpath = providerClasses + java.io.File.pathSeparator
            + Path.of(ModContext.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-proc:none",
            "-encoding", "UTF-8", "-classpath", classpath, "-d", classes.toString(), source.toString()));
        return classes;
    }

    static ModPageRegistry pages() {
        return new ModPageRegistry(new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, javax.swing.JComponent component) {
            }

            public void focus(ModPageRegistry.PageKey key) {
            }

            public void remove(ModPageRegistry.PageKey key) {
            }

            public void environment(UiEnvironment environment) {
            }
        }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
    }

    static final String SOURCE = """
        package example.deploymentcaller;
        import java.util.*;
        import java.nio.file.*;
        import java.time.Instant;
        import gold.debug.windowstolinux.appapi.mod.ModContext; import gold.debug.windowstolinux.appapi.mod.ModEntrypoint; import gold.debug.windowstolinux.appapi.mod.service.ServiceCall; import gold.debug.windowstolinux.appapi.mod.service.ServiceHandle; import gold.debug.windowstolinux.appapi.mod.lifecycle.StopContext;
        import gold.debug.windowstolinux.appapi.task.result.ApiException; import gold.debug.windowstolinux.appapi.task.run.CancelToken; import gold.debug.windowstolinux.appapi.task.result.OperationResult; import gold.debug.windowstolinux.appapi.task.result.Problem; import gold.debug.windowstolinux.appapi.task.result.ProblemCode; import gold.debug.windowstolinux.appapi.task.run.ResourceLease; import gold.debug.windowstolinux.appapi.task.run.TaskContext; import gold.debug.windowstolinux.appapi.task.run.TaskHandle; import gold.debug.windowstolinux.appapi.task.model.TaskRequest; import gold.debug.windowstolinux.appapi.task.result.TaskResult;
        import gold.debug.windowstolinux.appapi.server.model.ServerId;
        import gold.debug.windowstolinux.mod.deployment.api.*;
        public final class Mod implements ModEntrypoint {
            public static ModContext context;
            public static UUID handoff;
            public static TaskContext originalTask;
            public static Throwable assertion;
            public static String firstPrepareError;
            public static boolean firstPrepareReturned;
            private static DeploymentRestoreRequest reliableRequest;
            public static volatile String firstPrepareProviderEvidence;
            public static volatile String restoreCallEvidence="";
            private static int restoreEvidenceCount;
            private static boolean recoverPrepared;
            private static Runnable lookupAssertion;
            private static ServiceHandle<DeploymentApi> api;
            public void start(ModContext supplied) throws Exception {
                context=supplied;
                api=context.services().require("gold.debug.windowstolinux.mod.deployment",DeploymentApi.class);
                context.tasks().registerRecovery("api-collection",(before,task)-> {
                    var original=context.tasks().findCheckpoint(task,"handoff").orElseThrow();
                    String payload=original.snapshot().payload();
                    UUID saved=UUID.fromString(payload.substring(payload.indexOf(':')+2,payload.length()-2));
                    var restored=api.call(task,p->p.finishCollection(saved,task));
                    if(restored.outcome()!=TaskResult.SUCCEEDED || restored.value().orElseThrow().state()!=DeploymentHandoffState.FINISHED)
                        return uncertain();
                    original.close(); return new OperationResult<Void>(TaskResult.FAILED,Optional.empty(),Optional.of(new Problem(ProblemCode.IO_FAILURE,"CALLER_COLLECTION_ABORTED","Original incomplete collection was restored and released",Map.of(),Optional.empty())));
                });
                context.tasks().registerRecovery("api-boundary",(before,task)->ok());
                context.tasks().registerRecovery("api-occupancy",(before,task)-> {
                    var root=context.tasks().findCheckpoint(task,"occupancy").orElseThrow();
                    var identity=context.tasks().inspect(task);
                    check(identity.id().equals(before.id()) && identity.ownerId().equals("example.deploymentcaller"),"Original retained occupancy identity changed");
                    check(root.snapshot().payload().equals(String.format("{%creadOnly%c:true}",34,34)),"Original read-only call did not return");
                    root.close(); return new OperationResult<Void>(TaskResult.CANCELLED,Optional.empty(),Optional.empty());
                });
                context.tasks().registerRecovery("api-restore",(before,task)-> {
                    try {
                        var root=context.tasks().findCheckpoint(task,"restore").orElseThrow();
                        String payload=root.snapshot().payload();
                        UUID saved=UUID.fromString(payload.substring(payload.indexOf(':')+2,payload.length()-2));
                        var observed=restoreCall("restore-reconcile",task,p->p.reconcile(saved,task));
                        if(observed.outcome()!=TaskResult.SUCCEEDED)return uncertain();
                        var original=observed.value().orElseThrow();check(original.id().equals(saved),"Reconciliation changed original handoff");
                        if(original.state()==DeploymentHandoffState.ACTIVE) {
                            var completed=restoreCall("restore-finish-active",task,p->p.activate(saved,task));
                            if(completed.outcome()==TaskResult.UNKNOWN)return uncertain();
                            check(completed.outcome()==TaskResult.SUCCEEDED && completed.value().orElseThrow().handoff().id().equals(saved)
                                    && completed.value().orElseThrow().handoff().state()==DeploymentHandoffState.FINISHED,completed.toString());
                        } else if(original.state()!=DeploymentHandoffState.FINISHED)return uncertain();
                        check(!root.snapshot().released(),"Provider reconciliation released caller checkpoint");
                        root.close(); return ok();
                    } catch(Exception|AssertionError failure) { assertion=failure;throw failure; }
                });
                context.tasks().registerRecovery("api-lost-prepare",(before,task)-> {
                    try {
                        var root=context.tasks().findCheckpoint(task,"lost-restore").orElseThrow();
                        check(root.snapshot().payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<12000,"Root checkpoint contains archive content");
                        var identity=context.tasks().inspect(task);
                        check(identity.id().equals(originalTask.id()) && identity.ownerId().equals("example.deploymentcaller")
                                && identity.ownerVersion().equals("1.1.0-dev.1"),"Recovery task owner or release changed");
                        String wrong= reliableRequest.archiveSha256().equals("0".repeat(64)) ? "1".repeat(64) : "0".repeat(64);
                        var different=new DeploymentRestoreRequest(reliableRequest.archivedApplication(),reliableRequest.target(),reliableRequest.files(),wrong,reliableRequest.reviewedTargetSha256());
                        var rejected=restoreCall("different-request-rejected",task,p->{
                            try { return p.prepareRestore(different,task); }
                            catch(ApiException denied) {
                                check(denied.problem().eventCode().equals("DEPLOYMENT_HANDOFF_REQUEST_CHANGED"),denied.problem().toString());
                                return new OperationResult<DeploymentHandoff>(TaskResult.FAILED,Optional.empty(),Optional.of(denied.problem()));
                            }
                        });
                        check(rejected.outcome()==TaskResult.FAILED && rejected.value().isEmpty(),"Different request recovered an original handoff");
                        var found=restoreCall("original-request-lookup",task,p->p.prepareRestore(reliableRequest,task));
                        check(found.value().isPresent(),"Original request did not return its durable handoff");
                        var original=found.value().orElseThrow();
                        if(handoff!=null)check(handoff.equals(original.id()),"Original handoff ID changed");
                        handoff=original.id();
                        check(found.outcome()==(recoverPrepared?TaskResult.SUCCEEDED:TaskResult.UNKNOWN),found.toString());
                        check(original.state()==(recoverPrepared?DeploymentHandoffState.PREPARED:DeploymentHandoffState.UNKNOWN),original.toString());
                        lookupAssertion.run();
                        root.advance(root.snapshot(),String.format("{%chandoff%c:%c%s%c}",34,34,34,handoff,34));
                        var observed=restoreCall("lost-prepare-reconcile",task,p->p.reconcile(handoff,task));
                        if(recoverPrepared) {
                            if(observed.outcome()==TaskResult.UNKNOWN)return uncertain();
                            check(observed.outcome()==TaskResult.SUCCEEDED && observed.value().orElseThrow().id().equals(handoff),observed.toString());
                            var phase=observed.value().orElseThrow().state();
                            if(phase==DeploymentHandoffState.FINISHED) { root.close();return ok(); }
                            check(phase==DeploymentHandoffState.PREPARED || phase==DeploymentHandoffState.ACTIVE,observed.toString());
                            var activated=restoreCall("lost-prepare-activate",task,p->p.activate(handoff,task));
                            if(activated.outcome()==TaskResult.UNKNOWN)return uncertain();
                            check(activated.outcome()==TaskResult.SUCCEEDED && activated.value().orElseThrow().handoff().id().equals(handoff)
                                    && activated.value().orElseThrow().handoff().state()==DeploymentHandoffState.FINISHED,activated.toString());
                            root.close();return ok();
                        }
                        check(observed.outcome()==TaskResult.UNKNOWN,"Pending original upload unexpectedly resolved");
                        var discarded=restoreCall("lost-prepare-discard",task,p->p.discard(handoff,task));
                        if(discarded.outcome()==TaskResult.UNKNOWN)return uncertain();
                        check(discarded.outcome()==TaskResult.SUCCEEDED && discarded.value().orElseThrow().state()==DeploymentHandoffState.FINISHED,discarded.toString());
                        check(!root.snapshot().released(),"Provider discarded caller root checkpoint");
                        root.close();return new OperationResult<Void>(TaskResult.CANCELLED,Optional.empty(),Optional.empty());
                    } catch(Exception|AssertionError failure) { assertion=failure;throw failure; }
                });
            }
            public void stop(StopContext reason) throws Exception { api.close(); }
            private static OperationResult<Void> ok() { return new OperationResult<>(TaskResult.SUCCEEDED,Optional.empty(),Optional.empty()); }
            private static OperationResult<Void> uncertain() { return new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(new Problem(ProblemCode.UNKNOWN,"CALLER_RETAINED","Original handoff retained",Map.of(),Optional.empty()))); }
            private static void check(boolean value,String reason) { if(!value)throw new AssertionError(reason); }
            public static TaskHandle<?> occupancy(Runnable retained) throws Exception {
                return context.tasks().submit(new TaskRequest("api-occupancy","Actual retained CLASS caller",List.of(),false),task->{
                    try {
                        var root=context.tasks().beginCheckpoint(task,"occupancy","{}"); originalTask=task;
                        var values=api.call(task,p->p.listApplications(Optional.empty(),1,task));
                        check(!values.items().isEmpty(),"Production read-only API did not return");
                        root.advance(root.snapshot(),String.format("{%creadOnly%c:true}",34,34));
                        retained.run(); return uncertain();
                    } catch(Exception|AssertionError failure) { assertion=failure;throw failure; }
                });
            }
            public static TaskHandle<?> collect(String application,String revision,List<String> bindings,Runnable between) throws Exception {
                return context.tasks().submit(new TaskRequest("api-collection","Actual CLASS collection",List.of("server"),true),task->{
                    try {
                        var root=context.tasks().beginCheckpoint(task,"handoff","{}");
                        originalTask=task;
                        var identity=context.tasks().inspect(task);
                        check(identity.ownerId().equals("example.deploymentcaller") && identity.ownerVersion().equals("1.1.0-dev.1"),"Original root identity changed");
                        var reviewed=api.call(task,p->p.reviewCollection(new DeploymentCollectionRequest(new DeploymentApplicationId(application,new ServerId("server")),revision,bindings),task));
                        check(reviewed.outcome()==TaskResult.SUCCEEDED,reviewed.toString()); handoff=reviewed.value().orElseThrow().id();
                        root.advance(root.snapshot(),"{\\"handoff\\":\\""+handoff+"\\"}");
                        between.run();
                        var collected=api.call(task,p->p.collect(handoff,task));
                        if(collected.outcome()==TaskResult.UNKNOWN)return uncertain();
                        check(collected.outcome()==TaskResult.SUCCEEDED,collected.toString());
                        check(collected.value().orElseThrow().artifacts().stream().anyMatch(a->a.bindingId().equals("worker:uploads")),"Qualified worker binding absent");
                        check(collected.value().orElseThrow().artifacts().stream().noneMatch(a->a.bindingId().equals("web:uploads")),"Unselected same-name web binding leaked");
                        var finished=api.call(task,p->p.finishCollection(handoff,task));
                        check(finished.outcome()==TaskResult.SUCCEEDED && finished.value().orElseThrow().state()==DeploymentHandoffState.FINISHED,finished.toString());
                        root.close(); return ok();
                    } catch(Exception|AssertionError failure) { assertion=failure; throw failure; }
                });
            }
            public static TaskHandle<?> boundaries() throws Exception {
                return context.tasks().submit(new TaskRequest("api-boundary","Actual CLASS boundaries",List.of(),false),task->{
                    try {
                        DeploymentApi[] escaped=new DeploymentApi[1];
                        var values=api.call(task,p->{escaped[0]=p;return p.listApplications(Optional.empty(),1,task);});
                        check(!values.items().isEmpty(),"Production application page absent");
                        var archived=values.items().getFirst().runtime(); byte[] copy=archived.payload(); copy[0]^=1;
                        check(!java.util.Arrays.equals(copy,archived.payload()),"Runtime bytes aliased");
                        try { escaped[0].listApplications(Optional.empty(),1,task);throw new AssertionError("Escaped API accepted"); }
                        catch(ApiException denied) { check(denied.problem().eventCode().equals("SERVICE_SCOPE_ENDED"),denied.problem().toString()); }
                        TaskContext fake=new TaskContext(){public UUID id(){return task.id();} public CancelToken cancelToken(){return task.cancelToken();}public void progress(String s,Optional<Double> f){} public ResourceLease retain(String k,String i,String d){throw new AssertionError();}};
                        try { api.call(fake,p->p.listApplications(Optional.empty(),1,fake));throw new AssertionError("Fake task accepted"); }
                        catch(ApiException denied) { check(denied.problem().code()==ProblemCode.ACCESS_DENIED,denied.problem().toString()); }
                        byte[] original=new byte[16*1_048_576]; original[0]=7;
                        var maximum=api.call(task,p->{p.listApplications(Optional.empty(),1,task);return new VersionedRuntime(1,original);});
                        original[0]=9; check(maximum.payload()[0]==7 && maximum.payload().length==16*1_048_576,"Service snapshot byte boundary or cloning failed");
                        try { new VersionedRuntime(1,new byte[16*1_048_576+1]);throw new AssertionError("Oversize runtime accepted"); }
                        catch(IllegalArgumentException expected){}
                        return ok();
                    } catch(Exception|AssertionError failure) { assertion=failure; throw failure; }
                });
            }
            public static TaskHandle<?> restoreRejected(Map<String,Object> archive,Path files,String code,String event,Runnable reviewed) throws Exception {
                return context.tasks().submit(new TaskRequest("api-rejection","Actual target pre-effect refusal",List.of("target"),true),task->{
                    try {
                        originalTask=task;
                        var root=context.tasks().beginCheckpoint(task,"rejection","{}");
                        var identity=context.tasks().inspect(task);
                        check(identity.id().equals(task.id()) && identity.ownerId().equals("example.deploymentcaller") && identity.ownerVersion().equals("1.1.0-dev.1"),"Actual rejection owner changed");
                        var bindings=new ArrayList<DeploymentBinding>();
                        for(Object element:(List<?>)archive.get("bindings")) {
                            Map<?,?> value=(Map<?,?>)element;
                            bindings.add(new DeploymentBinding((String)value.get("id"),(String)value.get("component"),(String)value.get("kind"),(String)value.get("path"),(String)value.get("engine"),(Boolean)value.get("quiescence"),Optional.empty()));
                        }
                        var snapshot=new DeploymentApplicationSnapshot(new DeploymentApplicationId((String)archive.get("application"),new ServerId((String)archive.get("source"))),
                                (String)archive.get("display"),(String)archive.get("revision"),(String)archive.get("ownership"),ApplicationRunState.valueOf((String)archive.get("state")),
                                Instant.parse((String)archive.get("observed")),new VersionedRuntime((Integer)archive.get("format"),(byte[])archive.get("runtime")),bindings,(String)archive.get("health"));
                        var inputs=new ArrayList<RestoreFile>();
                        var ids=new ArrayList<>(List.of("release:worker","release:web")); bindings.forEach(b->ids.add(b.id()));
                        for(String id:ids) {
                            Path file=files.resolve(id.replace(':','-')+".pax");byte[] bytes=Files.readAllBytes(file);
                            inputs.add(new RestoreFile(id,file,bytes.length,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))));
                        }
                        String digest=(String)archive.get("archiveSha");
                        var review=api.call(task,p->p.reviewRestoreTarget(snapshot,new ServerId("target"),digest,task));
                        check(review.outcome()==TaskResult.SUCCEEDED,review.toString());reviewed.run();
                        var request=new DeploymentRestoreRequest(snapshot,new ServerId("target"),inputs,digest,review.value().orElseThrow());
                        var refused=api.call(task,p->{
                            try { return p.prepareRestore(request,task); }
                            catch(ApiException failure) { return new OperationResult<DeploymentHandoff>(TaskResult.FAILED,Optional.empty(),Optional.of(failure.problem())); }
                        });
                        check(refused.outcome()==TaskResult.FAILED && refused.value().isEmpty(),refused.toString());
                        var problem=refused.problem().orElseThrow();check(problem.code().name().equals(code) && problem.eventCode().equals(event),problem.toString());
                        check(!root.snapshot().released(),"Provider released rejection root checkpoint");root.close();return ok();
                    } catch(Exception|AssertionError failure) { assertion=failure;throw failure; }
                });
            }
            public static TaskHandle<?> restore(Map<String,Object> archive,Path files,Runnable prepared) throws Exception {
                return context.tasks().submit(new TaskRequest("api-restore","Actual CLASS restore",List.of("target"),true),task->{
                    try {
                        var checkpoint=context.tasks().beginCheckpoint(task,"restore","{}"); originalTask=task;
                        var bindings=new ArrayList<DeploymentBinding>();
                        for(Object element:(List<?>)archive.get("bindings")) {
                            Map<?,?> value=(Map<?,?>)element;
                            bindings.add(new DeploymentBinding((String)value.get("id"),(String)value.get("component"),(String)value.get("kind"),(String)value.get("path"),(String)value.get("engine"),(Boolean)value.get("quiescence"),Optional.empty()));
                        }
                        var snapshot=new DeploymentApplicationSnapshot(new DeploymentApplicationId((String)archive.get("application"),new ServerId((String)archive.get("source"))),
                                (String)archive.get("display"),(String)archive.get("revision"),(String)archive.get("ownership"),ApplicationRunState.valueOf((String)archive.get("state")),
                                Instant.parse((String)archive.get("observed")),new VersionedRuntime((Integer)archive.get("format"),(byte[])archive.get("runtime")),bindings,(String)archive.get("health"));
                        var inputs=new ArrayList<RestoreFile>();
                        var ids=new ArrayList<>(List.of("release:worker","release:web")); bindings.forEach(b->ids.add(b.id()));
                        for(String id:ids) {
                            Path file=files.resolve(id.replace(':','-')+".pax"); byte[] bytes=Files.readAllBytes(file);
                            inputs.add(new RestoreFile(id,file,bytes.length,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))));
                        }
                        String archiveDigest=(String)archive.get("archiveSha");
                        var reviewed=api.call(task,p->p.reviewRestoreTarget(snapshot,new ServerId("target"),archiveDigest,task));
                        check(reviewed.outcome()==TaskResult.SUCCEEDED,reviewed.toString());
                        var request=new DeploymentRestoreRequest(snapshot,new ServerId("target"),inputs,archiveDigest,reviewed.value().orElseThrow());
                        var candidate=restoreCall("first-prepare",task,p->prepareWithEvidence(p,request,task));
                        check(candidate.outcome()==TaskResult.SUCCEEDED && candidate.value().orElseThrow().state()==DeploymentHandoffState.PREPARED,candidate.toString());
                        handoff=candidate.value().orElseThrow().id(); checkpoint.advance(checkpoint.snapshot(),"{\\"handoff\\":\\""+handoff+"\\"}");
                        prepared.run();
                        check(!checkpoint.snapshot().released(),"Provider prepare released caller root checkpoint");
                        var activated=restoreCall("first-activate",task,p->p.activate(handoff,task));
                        if(activated.outcome()==TaskResult.UNKNOWN)return uncertain();
                        check(activated.outcome()==TaskResult.SUCCEEDED && activated.value().orElseThrow().handoff().state()==DeploymentHandoffState.FINISHED,activated.toString());
                        check(!checkpoint.snapshot().released(),"Provider activation released caller root checkpoint");
                        checkpoint.close(); return ok();
                    } catch(Exception|AssertionError failure) { assertion=failure;throw failure; }
                });
            }
            private static OperationResult<DeploymentHandoff> prepareWithEvidence(DeploymentApi api,DeploymentRestoreRequest request,TaskContext task) throws ApiException {
                try {
                    var observed=api.prepareRestore(request,task);
                    firstPrepareProviderEvidence=observed.outcome()+" phase="+observed.value().map(DeploymentHandoff::state)+" problem="+observed.problem();
                    return observed;
                } catch(ApiException failure) {
                    firstPrepareProviderEvidence="thrown="+failure.problem();
                    throw failure;
                } catch(RuntimeException failure) {
                    firstPrepareProviderEvidence="thrown type="+failure.getClass().getName();
                    throw failure;
                }
            }
            private static <T> OperationResult<T> restoreCall(String stage,TaskContext task,ServiceCall<DeploymentApi,OperationResult<T>> work) throws ApiException {
                restoreEvidence(stage+" attempted");
                try {
                    var returned=api.call(task,p->{
                        try {
                            var observed=work.invoke(p);
                            String phase=observed.value().map(value->{
                                if(value instanceof DeploymentHandoff handoff)return handoff.state()+" blockers="+handoff.blockers();
                                if(value instanceof DeploymentActivation activated)return activated.handoff().state()+" cleanup="+activated.cleanupComplete()+" blockers="+activated.handoff().blockers();
                                return value.getClass().getSimpleName();
                            }).orElse("empty");
                            restoreEvidence(stage+" provider="+observed.outcome()+" phase="+phase+" problem="+observed.problem().map(Mod::problemEvidence));
                            return observed;
                        } catch(ApiException failure) {
                            restoreEvidence(stage+" provider threw="+problemEvidence(failure.problem()));throw failure;
                        } catch(Exception|Error failure) {
                            restoreEvidence(stage+" provider threw type="+failure.getClass().getName());throw failure;
                        }
                    });
                    restoreEvidence(stage+" caller returned="+returned.outcome());return returned;
                } catch(ApiException failure) {
                    restoreEvidence(stage+" caller threw="+problemEvidence(failure.problem()));throw failure;
                }
            }
            private static String problemEvidence(Problem problem) {
                return problem.code()+"/"+problem.eventCode()+" diagnostic="+problem.diagnosticId();
            }
            private static void restoreEvidence(String observation) {
                if(restoreEvidenceCount++<32)restoreCallEvidence+=observation.substring(0,Math.min(observation.length(),2048))+"; ";
            }
            private static DeploymentApplicationSnapshot archived(Map<String,Object> archive,byte[] bytes) {
                var bindings=new ArrayList<DeploymentBinding>();
                for(Object element:(List<?>)archive.get("bindings")) {
                    Map<?,?> value=(Map<?,?>)element;
                    bindings.add(new DeploymentBinding((String)value.get("id"),(String)value.get("component"),(String)value.get("kind"),(String)value.get("path"),(String)value.get("engine"),(Boolean)value.get("quiescence"),Optional.empty()));
                }
                return new DeploymentApplicationSnapshot(new DeploymentApplicationId((String)archive.get("application"),new ServerId((String)archive.get("source"))),
                        (String)archive.get("display"),(String)archive.get("revision"),(String)archive.get("ownership"),ApplicationRunState.valueOf((String)archive.get("state")),
                        Instant.parse((String)archive.get("observed")),new VersionedRuntime((Integer)archive.get("format"),bytes),bindings,(String)archive.get("health"));
            }
            public static TaskHandle<?> lostPrepare(Map<String,Object> archive,Path files,boolean prepared,Runnable fault,Runnable malformed,Runnable lookup) throws Exception {
                return context.tasks().submit(new TaskRequest("api-lost-prepare","Actual CLASS prepare return lost",List.of("target"),true),task->{
                    try {
                        originalTask=task;recoverPrepared=prepared;lookupAssertion=lookup;
                        var root=context.tasks().beginCheckpoint(task,"lost-restore",String.format("{%crequestSha%c:%c%s%c}",34,34,34,archive.get("archiveSha"),34));
                        var invalid=archived(archive,(byte[])archive.get("malformedRuntime"));
                        var rejected=api.call(task,p->p.reviewRestoreTarget(invalid,new ServerId("target"),(String)archive.get("archiveSha"),task));
                        check(rejected.outcome()==TaskResult.FAILED && rejected.value().isEmpty(),"Fractional archive metadata reached target preparation");
                        malformed.run();
                        var snapshot=archived(archive,(byte[])archive.get("runtime"));
                        var inputs=new ArrayList<RestoreFile>();
                        var ids=new ArrayList<>(List.of("release:worker","release:web"));snapshot.bindings().forEach(b->ids.add(b.id()));
                        for(String id:ids) {
                            Path file=files.resolve(id.replace(':','-')+".pax");byte[] bytes=Files.readAllBytes(file);
                            inputs.add(new RestoreFile(id,file,bytes.length,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))));
                        }
                        var reviewed=api.call(task,p->p.reviewRestoreTarget(snapshot,new ServerId("target"),(String)archive.get("archiveSha"),task));
                        check(reviewed.outcome()==TaskResult.SUCCEEDED,reviewed.toString());
                        reliableRequest=new DeploymentRestoreRequest(snapshot,new ServerId("target"),inputs,(String)archive.get("archiveSha"),reviewed.value().orElseThrow());
                        fault.run();
                        try {
                            restoreCall("lost-first-prepare",task,p->{
                                return prepareWithEvidence(p,reliableRequest,task);
                            });firstPrepareReturned=true;
                            throw new AssertionError("First prepare unexpectedly returned a value");
                        } catch(ApiException expected) {
                            firstPrepareError=expected.problem().eventCode();
                            check(firstPrepareError.equals(prepared?"SERVICE_RELEASE_PERSISTENCE":"SERVICE_RELEASE_PENDING"),expected.problem().toString());
                        }
                        check(handoff==null && !root.snapshot().released(),"Lost prepare leaked an ID or released caller checkpoint");
                        return uncertain();
                    } catch(Exception|AssertionError failure) { assertion=failure;throw failure; }
                });
            }
        }
        """;
}
