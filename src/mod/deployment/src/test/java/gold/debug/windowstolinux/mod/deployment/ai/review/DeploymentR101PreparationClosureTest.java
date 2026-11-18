package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentToolchainDiagnostics;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;
import gold.debug.windowstolinux.mod.deployment.service.mirror.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

/** R100 public source/coordinate reconstruction; it makes no historical-wire or provider-usage claim. */
@Timeout(120)
class DeploymentR101PreparationClosureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String H = "/toolchainReviewEvidence/installedHelperSource";
    private static final String R = "/mirrorTransport/runtimeSource";
    private static final String S = "/toolchainReviewEvidence/mirrorBootstrapShell";
    private static final String P = "/toolchainReviewEvidence/mirrorPolicySource";
    private static final int[] CAPS = {884563,848007,810921,772327,730464,689253,644664,599743,553514,503507,450655,398147};
    private static final int[] REQUEST_LINES = {692,707,726,741,756,774,789,807,826,847,862,879};
    private static final int[][] UNITS = {{0,13775},{93871,102340},{153227,377411},{699205,706166}};
    private record Page(int line, boolean runtime, int offset, int limit) {
        String pointer() { return runtime ? R : H; }
        int currentOffset() { return runtime || offset < 101208 ? offset : offset + 1132; }
    }
    private static Page h(int line, int offset, int limit) { return new Page(line, false, offset, limit); }
    private static Page r(int line, int offset, int limit) { return new Page(line, true, offset, limit); }
    private static final List<List<Page>> READS = List.of(
        List.of(h(703,234780,5000)),
        List.of(h(718,237575,1702),h(719,226007,1896),h(720,374268,1291),h(721,172134,3000),h(722,206500,4500)),
        List.of(h(737,374268,8000)), List.of(h(752,237575,3500)),
        List.of(h(767,172134,8000),h(768,208500,8000),h(769,212000,6000),h(770,180262,6000)),
        List.of(h(785,206000,8000)), List.of(h(800,208500,8000),h(801,190000,8000),h(802,225000,5000),h(803,160000,4000)),
        List.of(h(818,169000,8000),h(819,180000,8000),h(820,198000,8000),h(821,206000,8000),h(822,192000,8000)),
        List.of(h(837,206000,8000),h(838,374268,1600),h(839,226007,2200),r(840,20251,8000),r(841,3984,3179),h(842,172134,3000),h(843,195585,3000)),
        List.of(h(858,216045,2000)),List.of(h(873,206500,8000)),List.of());

    private static MirroredInstallerTestFixture fixture() throws Exception {
        return new MirroredInstallerTestFixture(DownloadPoint.APT_UBUNTU, DownloadPoint.GO_METADATA, DownloadPoint.GO_DISTRIBUTION);
    }

    @ParameterizedTest
    @CsvSource({"JAVA,21,false", "JAVA,21,true", "NODE,24,false", "NODE,24,true",
        "GO,1.26,false", "GO,1.26,true", "DOTNET,10,false", "DOTNET,10,true", "KOTLIN,2.3,true",
        "RUST,1.95,false", "RUST,1.95,true", "PYTHON,3.12,false", "PYTHON,3.12,true",
        "PHP,8.3,false", "PHP,8.3,true", "RUBY,3.4,false", "RUBY,3.4,true"})
    void exactSupportedStandardPreparationFirstFrameHasWholeRequiredClosure(String ecosystem, String branch, boolean diagnosed) throws Exception {
        int cap = ecosystem.equals("GO") ? CAPS[0] : 1048576;
        try (var fixture = new MirroredInstallerTestFixture(DownloadPoint.values()); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var request = request(fixture, ecosystem, branch, diagnosed);
             var nativeReview = new DeploymentR101BuildSourceClosureTest.NativeReview(fixture, assisted.context, new int[]{cap}, 88008,
                 manifest -> List.of(verdict("APPROVE")))) {
            var intent = intent(fixture, request);
            var approved = DeploymentFixedCommandApproval.approveWithEvidence(nativeReview.context, assisted.host, intent, request,
                fixture.task, fixture.model.configurationRevision, "AUTOMATIC", false, (action, command, task) -> Map.of());
            assertEquals(TaskResult.SUCCEEDED, approved.outcome(), approved.toString());
            assertEquals(1, assisted.hosts.get());
            assertEquals(1, nativeReview.received.size());
            verify(nativeReview.received.getFirst(), fixture, request, intent);
            System.out.printf("R101_STANDARD_PREPARATION_FIRST_NATIVE ecosystem=%s diagnosed=%s bytes=%d cap=%d grant=%d originalWireUnavailable=true%n",
                ecosystem, diagnosed, nativeReview.received.getFirst().length, cap, assisted.hosts.get());
        }
    }

    @Test
    void completeGoEvidenceStillAllowsAnIndependentDenyWithoutAHostGrant() throws Exception {
        try (var fixture = fixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var request = request(fixture, true);
             var nativeReview = new DeploymentR101BuildSourceClosureTest.NativeReview(fixture, assisted.context, new int[]{CAPS[0]}, 88008,
                 manifest -> List.of(verdict("DENY")))) {
            var denied = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approveWithEvidence(nativeReview.context,
                assisted.host, intent(fixture, request), request, fixture.task, fixture.model.configurationRevision, "AUTOMATIC", false,
                (action, command, task) -> Map.of()));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", denied.problem().eventCode());
            assertEquals(0, assisted.hosts.get());
            verify(nativeReview.received.getFirst(), fixture, request, intent(fixture, request));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ecosystem", "catalogBranch", "requestedExact", "preparationTimeoutSeconds", "javaHome"})
    void alteredTypedFactsCannotReuseTheOriginalClosure(String field) throws Exception {
        try (var fixture = fixture(); var request = request(fixture, true)) {
            var intent = intent(fixture, request);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, request, fixture.layout, fixture.task);
            var changed = (ObjectNode) JSON.valueToTree(facts);
            var typed = (ObjectNode) changed.path("originalToolchainPreparation");
            assertTrue(typed.has(field));
            if (field.equals("preparationTimeoutSeconds")) typed.put(field, 121);
            else typed.put(field, field.equals("ecosystem") ? "NODE" : "changed-public-fixture-value");
            assertThrows(IOException.class, () -> DeploymentCommandSourceEvidence.project(JSON.writeValueAsString(changed), intent, request, fixture.layout));
        }
    }

    @ParameterizedTest
    @CsvSource({"JAVA,21", "NODE,24", "GO,1.26", "DOTNET,10", "KOTLIN,2.3", "RUST,1.95", "PYTHON,3.12", "PHP,8.3", "RUBY,3.4"})
    void everyAdapterBindsOriginalTypedRequestAndCanonicalTargets(String ecosystem,String branch) throws Exception {
        try(var fixture=new MirroredInstallerTestFixture(DownloadPoint.values());var request=request(fixture,ecosystem,branch,true)) {
            var intent=intent(fixture,request);
            var original=(ObjectNode)JSON.valueToTree(new DeploymentFixedCommandProtocol(fixture.context).inspect(intent,request,fixture.layout,fixture.task));
            for(String field:List.of("serverTargets","resourceTargets","businessRevision","installedHelperSha256","toolchainRequestTransport","toolchainDiagnostic")) {
                var changed=original.deepCopy();
                assertTrue(changed.has(field));
                changed.put(field,"altered-public-binding");
                assertThrows(IOException.class,()->DeploymentCommandSourceEvidence.project(JSON.writeValueAsString(changed),intent,request,fixture.layout),ecosystem+":"+field);
            }
        }
    }

    @Test
    void standardClosureDoesNotAddCatalogAdaptersOrRemoveTheKotlinPrerequisite() throws Exception {
        try(var fixture=new MirroredInstallerTestFixture(DownloadPoint.values())) {
            String helper=DeploymentManagedHelperBundle.renderScript(fixture.layout);
            assertTrue(helper.contains("if len(args) > 1 and args[0] == 'prepare' and args[1] == 'KOTLIN':\n        fail('request', 'Kotlin preparation requires its typed Java prerequisite')"));
            var missing=new DeploymentToolchainDiagnostics.Request(fixture.operation,fixture.task.id().toString(),fixture.server.id(),"a".repeat(64),"a".repeat(64),fixture.layout,
                List.of("prepare","KOTLIN","2.3","-","120","-"),null);
            assertThrows(IllegalArgumentException.class,()->DeploymentToolchainDiagnostics.command("prepare-diagnosed",missing));
            for(String ecosystem:List.of("C","CPP")) {
                try(var request=request(fixture,ecosystem,"12",false)) {
                    var action=intent(fixture,request);
                    assertThrows(Exception.class,()-> {
                        var facts=new DeploymentFixedCommandProtocol(fixture.context).inspect(action,request,fixture.layout,fixture.task);
                        DeploymentCommandSourceEvidence.project(executableFacts(facts),action,request,fixture.layout).orElseThrow();
                    });
                }
            }
        }
    }

    @Test
    void allThirtyOneOriginalSourceRangesRemainExactAtMappedFreshHelperCoordinates() throws Exception {
        try (var fixture = fixture(); var request = request(fixture, true);
             var nativeReview = new DeploymentR101BuildSourceClosureTest.NativeReview(fixture, fixture.context, new int[]{CAPS[0]}, 88008,
                 manifest -> List.of(verdict("DENY")))) {
            var intent = intent(fixture, request);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, request, fixture.layout, fixture.task);
            new DeploymentAiJson(nativeReview.context).askCommand(DeploymentFixedCommandApproval.commandReviewInstructions(true), facts,
                fixture.model.configurationRevision, fixture.task, DeploymentReviewDecision::malformed, intent, request);
            var evidence = DeploymentCommandSourceEvidence.project(executableFacts(facts), intent, request, fixture.layout).orElseThrow();
            var initial = verify(nativeReview.received.getFirst(), fixture, request, intent);
            String snapshot = initial.header().path("snapshotSha256").asText();
            String helper = DeploymentManagedHelperBundle.renderScript(fixture.layout), runtime = DeploymentMirrorRuntime.runtimeSource();
            int count = 0;
            for (var batch : READS) for (var page : batch) {
                var reader = evidence.reader(8);
                var messages = messages(nativeReview.encodedRequests.getFirst(), evidence);
                reader.offerInitialSource(messages);
                var result = reader.read(response(List.of(read(page, snapshot)))).getFirst();
                assertFalse(result.error());
                String[] pieces = result.text().split("\n",3);
                var metadata = JSON.readTree(pieces[1]);
                assertEquals(snapshot, metadata.path("snapshotSha256").asText());
                assertEquals(page.pointer(), metadata.path("pointer").asText());
                var value = metadata.path("value");
                assertEquals(page.currentOffset(), value.path("offset").asInt());
                String source = page.runtime ? runtime : helper;
                int next = Math.min(source.length(), page.currentOffset() + page.limit);
                assertEquals(next, value.path("next").asInt());
                var copy = copy(initial);
                int consumed = 0;
                if (value.has("newRanges")) {
                    for (var range : value.path("newRanges")) {
                        int offset = range.path("offset").asInt(), end = range.path("next").asInt();
                        String text = pieces[2].substring(consumed, consumed + end - offset); consumed += end - offset;
                        copy.parts().computeIfAbsent(page.pointer(), unused -> new TreeMap<>()).put(offset,text);
                    }
                } else {
                    copy.parts().computeIfAbsent(page.pointer(), unused -> new TreeMap<>()).put(page.currentOffset(),pieces[2]);
                    consumed = pieces[2].length();
                }
                assertEquals(pieces[2].length(), consumed);
                String restored = copy.range(page.pointer(),page.currentOffset(),source.substring(page.currentOffset(),next));
                if (page.line == 737) {
                    assertThrows(AssertionError.class, () -> initial.range(H,page.currentOffset(),source.substring(page.currentOffset(),next)));
                    assertEquals(5989, consumed, "The original cross-unit tail is returned, not truncated");
                }
                System.out.printf("R101_GO_ORIGINAL_SOURCE_PAGE line=%d originalOffset=%d currentOffset=%d next=%d rawNewCharacters=%d sha256=%s%n",
                    page.line,page.offset,page.currentOffset(),next,consumed,DeploymentSourceFrameDecoder.sha(restored));
                count++;
            }
            assertEquals(31,count);
        }
    }

    @Test
    void originalDecreasingCapsApplyToWholeCurrentFramesAndRefuseBeforeHttp() throws Exception {
        try (var fixture = fixture(); var request = request(fixture,true);
             var nativeReview = new DeploymentR101BuildSourceClosureTest.NativeReview(fixture,fixture.context,new int[]{CAPS[0]},88008,
                 manifest -> List.of(verdict("DENY")))) {
            var intent = intent(fixture,request);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(intent,request,fixture.layout,fixture.task);
            new DeploymentAiJson(nativeReview.context).askCommand(DeploymentFixedCommandApproval.commandReviewInstructions(true),facts,
                fixture.model.configurationRevision,fixture.task,DeploymentReviewDecision::malformed,intent,request);
            var template = nativeReview.encodedRequests.getFirst();
            var evidence = DeploymentCommandSourceEvidence.project(executableFacts(facts),intent,request,fixture.layout).orElseThrow();
            var reader = evidence.reader(256);
            var messages = messages(template,evidence); reader.offerInitialSource(messages);
            var requested = new ArrayList<Page>();
            int blocked = -1;
            for (int turn=0;turn<CAPS.length;turn++) {
                var current = bounded(template,messages,CAPS[turn]);
                int before = nativeReview.received.size();
                byte[] wire;
                try { wire=ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_RESPONSES).encode("gpt-6-luna",current,65536,ModelReasoningLevel.HIGH,Map.of()); }
                catch(ModelRequestBudgetException refused) {
                    var problem=new Problem(ProblemCode.INVALID_INPUT,"AI_REQUEST_BUDGET","Exact original pressure cap",
                        Map.of("maximumRequestBytes",Integer.toString(CAPS[turn]),"encodedRequestBytes",Integer.toString(refused.encodedBytes())),Optional.empty());
                    assertFalse(reader.repackBeforeSend(messages,problem));
                    assertEquals(before,nativeReview.received.size());
                    System.out.printf("R101_GO_SOURCE_PRESSURE_LOCAL_REFUSAL originalRequestLine=%d cap=%d completeBytes=%d zeroHttp=true zeroGrant=true sourceOnlyPressureNotModelReplay=true%n",
                        REQUEST_LINES[turn],CAPS[turn],refused.encodedBytes());
                    blocked=turn; break;
                }
                nativeReview.receiveEncodedBody(wire,CAPS[turn]);
                var frame=verify(wire,fixture,request,intent);
                for(var page:requested) {
                    String source=page.runtime?DeploymentMirrorRuntime.runtimeSource():DeploymentManagedHelperBundle.renderScript(fixture.layout);
                    frame.range(page.pointer(),page.currentOffset(),source.substring(page.currentOffset(),Math.min(source.length(),page.currentOffset()+page.limit)));
                }
                System.out.printf("R101_GO_SOURCE_PRESSURE originalRequestLine=%d cap=%d completeBytes=%d sourceOnlyPressureNotModelReplay=true%n",REQUEST_LINES[turn],CAPS[turn],wire.length);
                if(READS.get(turn).isEmpty()) break;
                String snapshot=frame.header().path("snapshotSha256").asText();
                var calls=new ArrayList<AiToolCall>();for(var page:READS.get(turn)) calls.add(read(page,snapshot));
                var reply=response(calls);
                try { reader.continueReview(messages,reply,reader.read(reply)); }
                catch(DeploymentCommandReviewModel.Failure noProgress) {
                    assertTrue(noProgress.evidenceReadMadeNoProgress());
                    reader.continueReadBeforeNoProgress(messages,reply);
                }
                requested.addAll(READS.get(turn));
            }
            assertTrue(blocked>=0,"The late original cap cannot fit this complete current representation");
        }
    }

    private static DeploymentSourceFrameDecoder.Frame verify(byte[] wire,MirroredInstallerTestFixture fixture,CommandRequest request,ActionIntent intent) throws Exception {
        var frame=DeploymentSourceFrameDecoder.decode(wire,"toolchainReviewEvidence");
        String helper=DeploymentManagedHelperBundle.renderScript(fixture.layout),runtime=DeploymentMirrorRuntime.runtimeSource();
        assertEquals("f483045edb858bd4148e582c421d6d32bdbc7aa1bc09f96e2240c6fcc60bd90b",DeploymentSourceFrameDecoder.sha(helper));
        assertEquals("0502566d564fa66353ea55527ddd75da61a7ce07e5395d9ebe813982fff9edca",DeploymentSourceFrameDecoder.sha(runtime));
        assertEquals("PINNED_FOUR_COMPLETE_UNITS",frame.facts().at("/toolchainReviewEvidence/helperClosureScope").asText());
        for(var unit:UNITS) frame.range(H,unit[0],helper.substring(unit[0],unit[1]));
        String policy=DeploymentMirrorTasks.preparation(request.script()).orElseThrow().policy();
        int start=request.script().indexOf("\nwtl_source_providers() {\n")+1;assertTrue(start>0);
        int length=frame.facts().at("/toolchainReviewEvidence/mirrorBootstrapShell/characters").asInt();
        String shell=request.script().substring(start,start+length);
        var checked=Map.of(R,frame.whole(R,runtime),S,frame.whole(S,shell),P,frame.whole(P,policy));
        var exact=frame.facts().path("exactAction");
        if(exact.isObject()) {
            var restored=new StringBuilder();
            for(var part:exact.path("segments")) {
                String text=checked.get(part.path("sourcePointer").asText());
                restored.append(switch(part.path("kind").asText()) {
                    case "LITERAL" -> part.path("text").asText();
                    case "SOURCE_TEXT" -> Objects.requireNonNull(text);
                    case "SHELL_SINGLE_QUOTE" -> "'"+Objects.requireNonNull(text).replace("'","'\"'\"'")+"'";
                    default -> throw new AssertionError("Unexpected exact command segment");
                });
            }
            assertEquals(intent.exactAction(),restored.toString());
        }
        var original=JSON.valueToTree(new DeploymentFixedCommandProtocol(fixture.context).inspect(intent,request,fixture.layout,fixture.task));
        String member=original.has("originalToolchainPreparation")?"originalToolchainPreparation":"toolchainPreparation";
        assertEquals(original.path(member),frame.facts().path(member));
        if(original.has("toolchainDiagnostic")) assertEquals(original.path("toolchainDiagnostic"),frame.facts().path("toolchainDiagnostic"));
        return frame;
    }

    private static List<AiMessage> messages(AiRequest template,DeploymentCommandSourceEvidence evidence) {
        var messages=new ArrayList<AiMessage>();messages.add(template.messages().getFirst());messages.addAll(evidence.messages());
        int fixed=messages.stream().mapToInt(message->message.text().getBytes(StandardCharsets.UTF_8).length).sum();
        if(fixed<88008) messages.add(messages.size()-1,new AiMessage(AiRole.USER,"x".repeat(88008-fixed),List.of()));
        return messages;
    }
    private static String executableFacts(Map<String, ?> facts) throws Exception {
        var method = DeploymentAiJson.class.getDeclaredMethod("facts",Map.class,int.class,boolean.class);
        method.setAccessible(true);
        return (String) method.invoke(null,facts,1048576,true);
    }
    private static AiRequest bounded(AiRequest template,List<AiMessage> messages,int cap) {
        return new AiRequest(template.purpose(),messages,template.maxOutputTokens(),template.timeout(),template.jsonObjectResponse(),template.tools(),cap,
            template.requiredToolName(),template.generationRecoveryAttempts(),Optional.of("gpt-6-luna"),Optional.of(AiReasoningMode.HIGH));
    }
    private static DeploymentSourceFrameDecoder.Frame copy(DeploymentSourceFrameDecoder.Frame frame) {
        var parts=new LinkedHashMap<String,NavigableMap<Integer,String>>();frame.parts().forEach((pointer,ranges)->parts.put(pointer,new TreeMap<>(ranges)));
        return new DeploymentSourceFrameDecoder.Frame(frame.facts(),frame.header(),parts);
    }
    private static AiToolCall verdict(String value) { return new AiToolCall(UUID.randomUUID().toString(),"submit_result","{\"decision\":\""+value+"\",\"reason\":\"Public source fixture verdict\",\"requiresUserConfirmation\":false}"); }
    private static AiToolCall read(Page page,String snapshot) throws Exception { return new AiToolCall("original-"+page.line,"evidence",JSON.writeValueAsString(Map.of("snapshotSha256",snapshot,"pointer",page.pointer(),"offset",page.currentOffset(),"limit",page.limit))); }
    private static AiResponse response(List<AiToolCall> calls) {
        var usage=new AiUsage(Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty());
        var attempt=new AiGenerationAttempt(UUID.randomUUID(),1,"public-source-pressure","OPENAI_RESPONSES",TaskResult.SUCCEEDED,"AI_GENERATION_COMPLETE","NONE",0L,usage,1,65536);
        return new AiResponse("","public-source-pressure","tool_calls",usage,calls,Optional.of(new AiContinuation(UUID.randomUUID(),64)),Optional.empty(),Optional.empty(),List.of(attempt));
    }
    private static CommandRequest request(MirroredInstallerTestFixture fixture,boolean diagnosed) {
        return request(fixture,"GO","1.26",diagnosed);
    }
    private static CommandRequest request(MirroredInstallerTestFixture fixture,String ecosystem,String branch,boolean diagnosed) {
        String invocation;byte[] input;
        String javaHome = ecosystem.equals("KOTLIN") ? fixture.layout.toolchains()+"/versions/java-"+"a".repeat(64) : "-";
        var arguments=List.of("prepare",ecosystem,branch,"-","120",javaHome);
        if(diagnosed) {
            var requirement=ecosystem.equals("KOTLIN") ? new gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentKotlinJavaRequirement(
                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement.ConstraintType.SERIES,"21","21.0.13+9") : null;
            var data=new DeploymentToolchainDiagnostics.Request(fixture.operation,fixture.task.id().toString(),fixture.server.id(),"a".repeat(64),"a".repeat(64),fixture.layout,
                arguments,requirement);
            invocation=DeploymentToolchainDiagnostics.command("prepare-diagnosed",data);input=data.bytes();
        } else {
            invocation="'"+fixture.layout.helperPath()+"' prepare-toolchains "+arguments.stream().map(value -> "'"+value+"'").collect(java.util.stream.Collectors.joining(" "));
            input=new byte[0];
        }
        String guarded=DeploymentManagedHelperBundle.verifiedCommand(invocation,fixture.layout);
        return new CommandRequest(DeploymentMirrorTasks.preparationCommand(guarded,fixture.task,135),input,Duration.ofSeconds(135),65536);
    }
    private static ActionIntent intent(MirroredInstallerTestFixture fixture,CommandRequest request) {
        return RemoteActions.command(fixture.server,"a".repeat(64),request).withSemantics(new ActionSemantics("deployment.toolchain.prepare.v1",
            Set.of(new ActionTarget(fixture.server.id(),"deployment-build",fixture.layout.toolchains()))));
    }
}
