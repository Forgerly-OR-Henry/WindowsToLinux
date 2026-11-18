package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.*;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentProjectToolchainRequirements;
import gold.debug.windowstolinux.mod.deployment.service.mirror.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Actual public typed builders and native HTTP bodies; no historical wire or provider-usage reconstruction. */
@Timeout(120)
class DeploymentR101RepositoryBuildClosureTest {
    @TempDir Path temporary;

    @Test
    void nugetLockedBuildSuppliesWholeMirrorRuntimeAtTheOriginalFirstCap() throws Exception {
        try(var fixture=new MirroredInstallerTestFixture(DownloadPoint.values());
            var assisted=new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
            var build=AssetLendingBuildReviewFixture.build(fixture, sample("success-asset-lending"),Files.createDirectory(temporary.resolve("nuget")));
            var nativeReview=new DeploymentR101BuildSourceClosureTest.NativeReview(fixture,assisted.context,new int[]{599270},0,
                manifest->List.of(verdict()))) {
            var result=DeploymentR101BuildSourceClosureTest.approve(nativeReview.context,assisted,fixture,build.intent(),build.request(),build.facts());
            assertEquals(TaskResult.SUCCEEDED,result.outcome(),result.toString());
            assertEquals(1,assisted.hosts.get());
            assertEquals(1,nativeReview.received.size());
            verify(nativeReview.received.getFirst(),build,fixture);
            String body=new String(build.request().stdin(),StandardCharsets.UTF_8);
            assertTrue(body.contains("--locked-mode"));
            System.out.printf("R101_NUGET_NATIVE bytes=%d cap=599270 grant=1 fullRuntime=true originalWireUnavailable=true%n",nativeReview.received.getFirst().length);
        }
    }

    @Test
    void csvAnalyzerBuildKeepsWholeCurrentSourcesAtThePhysicalCap() throws Exception { csv("analyzer",1048576,false); }

    @Test
    void csvWebBuildKeepsWholeCurrentSourcesAtThePhysicalCap() throws Exception { csv("web",1048576,false); }

    @Test
    void csvAnalyzerOriginalSmallBudgetRefusesLocallyWithoutAnIncompleteFrame() throws Exception { csv("analyzer",126855,true); }

    @Test
    void csvWebOriginalSmallBudgetRefusesLocallyWithoutAnIncompleteFrame() throws Exception { csv("web",126855,true); }

    private void csv(String component,int cap,boolean refusal) throws Exception {
        try(var fixture=new MirroredInstallerTestFixture(DownloadPoint.values());
            var assisted=new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
            var build=csvBuild(fixture,component);
            var nativeReview=new DeploymentR101BuildSourceClosureTest.NativeReview(fixture,assisted.context,new int[]{cap},0,
                manifest->List.of(verdict()))) {
            if(refusal) {
                var problem=assertThrows(ApiException.class,()->DeploymentR101BuildSourceClosureTest.approve(nativeReview.context,assisted,fixture,build.intent(),build.request(),build.facts()));
                assertEquals("AI_REQUEST_BUDGET",problem.problem().eventCode());
                assertTrue(nativeReview.received.isEmpty());
                assertFalse(nativeReview.refused.isEmpty());
                assertEquals(0,assisted.hosts.get());
            } else {
                var result=DeploymentR101BuildSourceClosureTest.approve(nativeReview.context,assisted,fixture,build.intent(),build.request(),build.facts());
                assertEquals(TaskResult.SUCCEEDED,result.outcome(),result.toString());
                assertEquals(1,assisted.hosts.get());
                verify(nativeReview.received.getFirst(),build,fixture);
            }
            System.out.printf("R101_CSV_NATIVE component=%s typed=%s cap=%d received=%s refused=%s grants=%d originalFinalBuildComponentUnknown=true originalWireUnavailable=true%n",
                component,component.equals("analyzer")?"PIP_LOCKED":"COMPOSER_LOCKED",cap,
                nativeReview.received.stream().map(bytes->bytes.length).toList(),nativeReview.refused,assisted.hosts.get());
        }
    }

    private static void verify(byte[] wire,AssetLendingBuildReviewFixture.Captured build,MirroredInstallerTestFixture fixture) throws Exception {
        var current=(Map<?,?>)build.facts().get("currentCommandEvidence");
        var sources=(DeploymentSourceArchiveReview.Evidence)current.get("currentBuildArchiveSource");
        DeploymentR101BuildSourceClosureTest.verify(wire,build.request(),fixture,sources);
    }

    private AssetLendingBuildReviewFixture.Captured csvBuild(MirroredInstallerTestFixture fixture,String component) throws Exception {
        Path root=sample("success-csv-inspector");
        List<String> names;
        try(var paths=Files.walk(root.resolve(component))) {
            names=paths.filter(Files::isRegularFile).map(root::relativize).map(path->path.toString().replace('\\','/')).sorted().toList();
        }
        var material=new DeploymentSourceMaterial("CSV Inspector "+component,new Snapshot(UUID.randomUUID(),"a".repeat(64),root),names,List.of());
        Path directory=Files.createDirectory(temporary.resolve(component));
        var archive=archive(material,component,directory,fixture);
        var sources=DeploymentSourceArchiveReview.capture(material,archive,fixture.task);
        assertEquals("COMPLETE",sources.coverage());
        String logicalName="wtl-r101-csv-"+component;
        var kind=component.equals("analyzer")?DeploymentProjectKind.PYTHON_SERVICE:DeploymentProjectKind.PHP_SERVICE;
        var assessment=new DeploymentStandardAnalyzer().analyzeComponent(material,Path.of(component),logicalName,kind,fixture.task).value().orElseThrow();
        String application=assessment.assessment().facts().orElseThrow().applicationId();
        var limits=new DeploymentBuildLimits(1800,1024,2048,4L*1024*1024,4L*1024*1024*1024,false);
        var values=component.equals("analyzer")?Map.of("timeout","10","databaseMode","NONE","primary","3.12","secondary","server")
            :Map.of("timeout","10","databaseMode","NONE","version","8.3","primary","public","secondary","router.php","application.endpoint.web.url","https://csv-fixture.example.invalid/");
        var consent=new DeploymentReviewConsent(application,fixture.server.id(),material.snapshot().sha256(),archive.sha256(),Instant.EPOCH);
        var answers=new DeploymentStandardReviewAnswers(values,List.of(),1,limits,consent,false,true);
        var runtimeResolver=new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentStandardRuntimeResolver();
        var checkedValues=new LinkedHashMap<>(runtimeResolver.defaults(kind,assessment.assessment().runtimeSuggestion().orElse(null),root.resolve(component)));
        checkedValues.putAll(values);
        assertTrue(runtimeResolver.missing(application,kind,checkedValues).isEmpty(),checkedValues.toString());
        runtimeResolver.runtime(kind,checkedValues);
        var planner=new DeploymentStandardPlanner();
        var builder=new DeploymentStandardReviewBuilder(fixture.context.servers(),planner,fixture.layout);
        var planned=builder.plan(fixture.server,material,archive,assessment,answers,fixture.task);
        assertTrue(planned.value().isPresent(),planned.toString());
        var plan=planned.value().orElseThrow();
        var rendered=planner.render(plan,fixture.task,fixture.layout).value().orElseThrow();
        var reviewed=plan.request();
        assertEquals(component.equals("analyzer")?"PIP_LOCKED":"COMPOSER_LOCKED",reviewed.facts().buildTool().name());
        var ecosystem=component.equals("analyzer")?DeploymentToolchainType.PYTHON:DeploymentToolchainType.PHP;
        var requirement=DeploymentProjectToolchainRequirements.from(reviewed.facts(),reviewed.runtime()).stream().filter(value->value.ecosystem()==ecosystem).findFirst().orElseThrow();
        String version=component.equals("analyzer")?"3.12.16":"8.3.36";
        String key=component.equals("analyzer")?"1c911f339cb693069b67272b9c505925b17eb614c565fb1b4b3d27a2baa4a90f":"260a6a7373f480abde1cd83160b586eaa7fb40c6231c036b1148e0d6da129958";
        String url=component.equals("analyzer")?"https://www.python.org/ftp/python/3.12.16/Python-3.12.16.tgz":"https://www.php.net/distributions/php-8.3.36.tar.gz";
        String digest=component.equals("analyzer")?"710c3e75dac8b2f258d9cd0f76e5c4c2e9175fac9e249c98901552f7cf33b7dd":"c93f3441f87f6ab156bef4432db215d7e61ca2c0183bb3a2aa28d9886ab59f53";
        var selection=new DeploymentResolvedToolchains.Selection(requirement,DeploymentToolchainVersion.parse(ecosystem,version).orElseThrow(),
            fixture.layout.toolchains()+"/versions/"+ecosystem.name().toLowerCase(Locale.ROOT)+"-"+key,DeploymentResolvedToolchains.OriginType.MANAGED,url,digest);
        var tools=new DeploymentResolvedToolchains(DeploymentToolchainCatalog.defaults().revision(),List.of(selection));
        var runner=Class.forName("gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.DeploymentStandardBuildRunner");
        var scriptMethod=runner.getDeclaredMethod("script",String.class,gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest.class,
            DeploymentBuildEnvironment.class,DeploymentResolvedToolchains.class);
        scriptMethod.setAccessible(true);
        String body=(String)scriptMethod.invoke(null,rendered.buildScript(),reviewed,new DeploymentBuildEnvironment(application,Map.of()),tools);
        var engineMethod=runner.getDeclaredMethod("buildEngine",gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.class,
            gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.class);
        engineMethod.setAccessible(true);
        String engine=(String)engineMethod.invoke(null,reviewed.facts().buildTool(),reviewed.runtime());
        var groupsMethod=Class.forName("gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.BuildMatchPolicy").getDeclaredMethod("groups");
        groupsMethod.setAccessible(true);
        @SuppressWarnings("unchecked") var groups=(List<List<String>>)groupsMethod.invoke(null);
        var budget=new DeploymentBuildBudget(limits.timeoutSeconds());
        var workspace=new DeploymentRemoteWorkspace(application,archive.sha256(),fixture.layout);
        String binding=Base64.getEncoder().encodeToString(DeploymentToolchainBindingCodec.encode(tools).getBytes(StandardCharsets.UTF_8));
        var captured=AssetLendingBuildReviewFixture.capture(fixture,rendered.reviewedPlanSha256(),budget,
            channel->channel.build(workspace,limits,budget.remainingSeconds()-15,engine,body,binding,groups));
        try {
            var facts=new LinkedHashMap<>(new DeploymentFixedCommandProtocol(fixture.context).inspect(captured.intent(),captured.request(),fixture.layout,fixture.task));
            facts.put("currentCommandEvidence",Map.of("currentBuildArchive",association(fixture,material,archive,captured,application),"currentBuildArchiveSource",sources));
            return new AssetLendingBuildReviewFixture.Captured(captured.request(),captured.intent(),Map.copyOf(facts));
        } catch(Exception|AssertionError failure) { captured.close();throw failure; }
    }

    private static DeploymentSourceArchive archive(DeploymentSourceMaterial source,String component,Path directory,MirroredInstallerTestFixture fixture) throws Exception {
        var writer=Class.forName("gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceTar")
            .getDeclaredMethod("write",DeploymentSourceMaterial.class,String.class,OutputStream.class,gold.debug.windowstolinux.appapi.task.run.TaskContext.class);
        writer.setAccessible(true);
        Path target=directory.resolve("source.tar.gz");long total;
        try(var output=Files.newOutputStream(target)) { total=(long)writer.invoke(null,source,component,output,fixture.task); }
        byte[] bytes=Files.readAllBytes(target);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        return new DeploymentSourceArchive(new Snapshot(UUID.randomUUID(),"b".repeat(64),directory.toRealPath()),hash,bytes.length,total,source.snapshot().id(),source.snapshot().sha256(),component);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> association(MirroredInstallerTestFixture fixture,DeploymentSourceMaterial source,DeploymentSourceArchive archive,
            AssetLendingBuildReviewFixture.Captured captured,String application) throws Exception {
        var observation=Map.<String,Object>of("originSnapshotId",archive.originSnapshotId().toString(),"sourceManifestSha256",archive.sourceManifestSha256(),
            "archiveSha256",archive.sha256(),"archiveSnapshotId",archive.snapshot().id().toString(),"localArchivePath",archive.path().toString(),"archiveBytes",archive.byteCount(),
            "relativeRoot",archive.relativeRoot(),"fileCount",source.includedFiles().size(),"fileNames",source.includedFiles().stream().map(name->name.substring(archive.relativeRoot().length()+1)).toList(),"fileNamesTruncated",false);
        var reviewed=new LinkedHashMap<>(observation);reviewed.put("application",application);reviewed.put("server",fixture.server.id());reviewed.put("remoteLayoutSha256",fixture.layout.digest());
        var method=DeploymentAssistedScope.class.getDeclaredMethod("buildArchiveFacts",List.class,Map.class,ActionIntent.class,CommandRequest.class,DeploymentRemoteLayout.class);
        method.setAccessible(true);
        return (Map<String,Object>)method.invoke(null,List.of(observation),Map.of(application,reviewed),captured.intent(),captured.request(),fixture.layout);
    }

    private static Path sample(String name) {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.isDirectory(root.resolve("test/multi-language/"+name)))root=root.getParent();
        assertNotNull(root);return root.resolve("test/multi-language/"+name);
    }
    private record Snapshot(UUID id,String sha256,Path directory) implements FileSnapshot { public void close() { } }
    private static AiToolCall verdict() { return new AiToolCall(UUID.randomUUID().toString(),"submit_result","{\"decision\":\"APPROVE\",\"reason\":\"Independent public typed-source fixture verdict\",\"requiresUserConfirmation\":false}"); }
}
