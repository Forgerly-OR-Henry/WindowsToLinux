package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentAgentToolchainInputEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ServerId SERVER = new ServerId("original-server");

    private static final String REVISION = "c".repeat(64);

    private static final String OPERATION = "a".repeat(32);

    @Test
    void existingOnlyPreparationRefusesMissingAndDriftingRecordsBeforeColdInstallation() throws Exception {
        // Execute only the two actual Python function definitions with controlled lowest-level peers, not Linux/vendor code.
        String python = System.getProperty("managed.test.python", "python");
        String fixture = """
            import ast,json,sys,re
            source=json.load(sys.stdin)['source']
            tree=ast.parse(source)
            chosen=[node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name in ('agent_prepare','agent_prepare_selected')]
            assert len(chosen)==2
            class Failure(Exception): category='unknown'
            namespace={'re':re,'AGENT_ECOSYSTEMS':{'GO'},'SHIPPED_CATALOG':{'GO':['1.24']},'PreparationFailure':Failure}
            identities=[node.value for node in tree.body if isinstance(node,ast.Assign) and len(node.targets)==1 and isinstance(node.targets[0],ast.Name) and node.targets[0].id=='EXPECTED_RESOLVER_SOURCE_SHA256']
            assert len(identities)==1
            namespace['EXPECTED_RESOLVER_SOURCE_SHA256']=ast.literal_eval(identities[0])
            exec(compile(ast.Module(body=chosen,type_ignores=[]),'<actual-existing-only-functions>','exec'),namespace)
            for mode in ('missing','existing','drift'):
                counts={'create':0,'probe':0,'install':0,'selection':0}
                record={'ecosystem':'GO','version':'1.24.0','directory':'/controlled/existing','origin':'MANAGED','source':'official','sha256':'a'*64}
                def reject(code,*rest): raise Failure(code)
                def selection(*args):
                    counts['selection']+=1
                    return (None if mode=='missing' or mode=='drift' and counts['selection']>1 else record),None
                def create(operation,seconds,volumeMode='INSTALL'):
                    counts['create']+=1
                    assert volumeMode=='PROBE'
                    return {'operation':operation,'volumeMode':volumeMode}
                def probe(manifest,records,java):
                    counts['probe']+=1
                    assert records==[record]
                def install(*args):
                    counts['install']+=1
                    raise AssertionError('cold installation forbidden')
                namespace.update(agent_reject=reject,agent_java_home=lambda value:True,agent_installation_selection=selection,
                    resolution_decode=lambda *args:dict(version='1.24.0',url='official',algorithm='sha256',digest='a'*64,layout={}),
                    agent_selection_snapshot=lambda value:{'record':value},agent_create=create,agent_probe_records=probe,
                    agent_cleanup_before_start=lambda value:False,agent_detach=lambda value:None,
                    agent_verify_selections=lambda value:None,agent_release_source=install)
                try:
                    namespace['agent_prepare'](['GO','1.24','-','30','-','a'*32,'encoded-fixture'])
                    assert mode=='existing'
                except Failure as error:
                    assert str(error)==('EXISTING_INSTALLATION_REQUIRED' if mode=='missing' else 'INSTALLATION_SELECTION_CHANGED')
                    assert mode!='existing'
                assert counts['install']==0
                assert counts['create']==(0 if mode=='missing' else 1)
                assert counts['probe']==(1 if mode=='existing' else 0)
            print('EXISTING_ONLY_GUARDS=PASS')
            """;
        var process = new ProcessBuilder(python, "-I", "-S", "-c", fixture).start();
        var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (var stream = process.getInputStream()) { return stream.readNBytes(4097); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        var error = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (var stream = process.getErrorStream()) { return stream.readNBytes(4097); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        try {
            try (var input = process.getOutputStream()) {
                input.write(JSON.writeValueAsBytes(java.util.Map.of("source", DeploymentAgentToolchainPrograms.existingController(
                    gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()))));
            }
            assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "Controlled Python fixture deadline");
            byte[] stdout = output.get(5, java.util.concurrent.TimeUnit.SECONDS), stderr = error.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, process.exitValue(), new String(stderr, StandardCharsets.UTF_8));
            assertEquals(0, stderr.length); assertTrue(stdout.length <= 4096);
            assertTrue(new String(stdout, StandardCharsets.UTF_8).replace("\r\n", "\n").endsWith("EXISTING_ONLY_GUARDS=PASS\n"));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Owned Python fixture did not exit");
            output.get(5, java.util.concurrent.TimeUnit.SECONDS); error.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void isolationExistingOnlyPreparationRejectsColdFallbackAndPreservesInstalledVersion() {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        String old = DeploymentAgentToolchainPrograms.controller(layout);
        String version = DeploymentAgentToolchainProgramStore.version(layout);
        String guarded = DeploymentAgentToolchainPrograms.existingController(layout);
        String insertion = "    if not selections:\n        agent_reject('EXISTING_INSTALLATION_REQUIRED')\n";
        assertEquals(old, guarded.replace(insertion, ""), "Default controller and installed version are unchanged");
        assertTrue(guarded.indexOf(insertion) < guarded.indexOf("    manifest = agent_create(operation, seconds, 'PROBE')"));
        var current = current("prepare");
        current.put("completeExecutableInput", guarded);
        rebind(current, DeploymentAgentToolchainPrograms.command(arguments("prepare")), Duration.ofSeconds(60));
        var facts = DeploymentAgentToolchainInputEvidence.select(current).orElseThrow().facts();
        assertEquals(true, facts.get("reuseExistingOnly"));
        assertFalse(DeploymentAgentToolchainInputEvidence.select(current("prepare")).orElseThrow().facts().containsKey("reuseExistingOnly"));
        var changed = current.deepCopy();
        changed.put("completeExecutableInput", guarded.replace("if not selections:", "if False:"));
        rebind(changed, DeploymentAgentToolchainPrograms.command(arguments("prepare")), Duration.ofSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(changed));
        var wrongPhase = current.deepCopy();
        wrongPhase.put("operation", "deployment.agent.toolchain.bind.v1");
        rebind(wrongPhase, DeploymentAgentToolchainPrograms.command(arguments("bind")), Duration.ofSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(wrongPhase));
        assertEquals(old, DeploymentAgentToolchainPrograms.controller(layout));
        assertEquals(version, DeploymentAgentToolchainProgramStore.version(layout));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void installedEntrypointsRequireOriginalActionObservationAndEveryExactInput(boolean customLayout) throws Exception {
        var originalLayout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var layout = customLayout ? new gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout(
            new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
                "/srv/source-evidence/lib", "/srv/source-evidence/apps", "/srv/source-evidence/state",
                "/srv/source-evidence/config", "/srv/source-evidence/data")) : originalLayout;
        if (customLayout) assertThrows(IllegalArgumentException.class, () ->
            new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
                "/srv/源码/lib", "/srv/源码/apps", "/srv/源码/state", "/srv/源码/config", "/srv/源码/data"));
        var release = (ObjectNode) JSON.readTree(java.util.Base64.getDecoder().decode(
            DeploymentAgentToolchainReleaseTest.encoded("RUST", "1.95", "1.95.0", OPERATION)));
        release.put("resolverSourceSha256", DeploymentAgentToolchainPrograms.sha256(DeploymentAgentToolchainPrograms.resolver(layout)));
        String encodedRelease = java.util.Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(release));
        String encodedBinding = java.util.Base64.getEncoder().encodeToString(new String(
            java.util.Base64.getDecoder().decode(DeploymentAgentToolchainCommandsTest.binding()), StandardCharsets.UTF_8)
            .replace(originalLayout.toolchains(), layout.toolchains()).getBytes(StandardCharsets.UTF_8));
        for (String phase : List.of("readiness", "install", "prepare", "bind")) {
            var memory = new DeploymentAgentToolchainProgramStateTest.Memory();
            var state = memory.state(SERVER, layout);
            com.fasterxml.jackson.databind.JsonNode installation = null;
            if (!phase.equals("readiness")) {
                var prior = state.begin("READINESS", "e".repeat(32), "original-readiness", null);
                installation = DeploymentAgentToolchainProgramStoreTest.observation(memory.id.toString(), "e".repeat(32), !phase.equals("install"), layout);
                state.finish(prior, DeploymentAgentToolchainProgramStateTest.success("AGENT_CONTROLLER_STORE=" + installation + "\n"), true, installation);
            }
            List<String> args = phase.equals("prepare") ? List.of("prepare", "RUST", "1.95", "1.95.0", "100", "-", OPERATION, encodedRelease)
                : phase.equals("bind") ? List.of("bind", encodedBinding, "100", OPERATION) : List.of();
            var current = DeploymentAgentToolchainProgramStoreTest.current(phase, memory.id.toString(), OPERATION, args, layout);
            state.begin(phase.toUpperCase(java.util.Locale.ROOT), OPERATION, current.path("exactAction").asText(), installation);
            var receipts = JSON.valueToTree(memory.receipts(SERVER, layout));
            var selected = DeploymentAgentToolchainInputEvidence.selectInstalled(current, layout, receipts, memory.id.toString()).orElseThrow().facts();
            assertEquals(phase, selected.get("phase"));
            assertFalse(selected.containsKey("approved"));
            if (phase.equals("prepare") || phase.equals("bind")) {
                var source = JSON.valueToTree(selected).path("controllerSource");
                byte[] expected = DeploymentAgentToolchainPrograms.controller(layout).getBytes(StandardCharsets.UTF_8);
                assertArrayEquals(expected, source.path("text").asText().getBytes(StandardCharsets.UTF_8));
                String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(expected));
                assertEquals(digest, source.path("sha256").asText());
                assertEquals(selected.get("sourceSha256"), source.path("sha256").asText());
                assertEquals(expected.length, source.path("utf8Bytes").asInt());
                assertEquals("/" + DeploymentAgentToolchainProgramStore.FIELD + "/controllerSource/text", source.path("evidencePointer").asText());
                assertEquals("/exactAction", selected.get("sourcePointer"));
                if (customLayout)
                    assertNotEquals(DeploymentAgentToolchainPrograms.controller(), source.path("text").asText());
            } else assertFalse(selected.containsKey("controllerSource"));
            for (String mutation : List.of("script", "stdin", "target", "version", "source", "task", "receipt", "kind")) {
                var changed = current.deepCopy();
                var changedReceipts = receipts.deepCopy();
                switch (mutation) {
                    case "script" -> changed.put("exactAction", changed.path("exactAction").asText() + " ");
                    case "stdin" ->
                        changed.put("exactAction", changed.path("exactAction").asText().replace("stdinBytes=", "stdinBytes=1"));
                    case "target" -> changed.withArray("resourceTargets").remove(0);
                    case "kind" -> changed.put("inputKind", "OPAQUE_BOUND_DATA");
                    case "version" ->
                        ((ObjectNode) changedReceipts.path(DeploymentAgentToolchainProgramState.FIELD).get(receipts.path(DeploymentAgentToolchainProgramState.FIELD).size() - 1).path("payload").path("index")).put("version", "0".repeat(64));
                    case "source" ->
                        ((ObjectNode) changedReceipts.path(DeploymentAgentToolchainProgramState.FIELD).get(receipts.path(DeploymentAgentToolchainProgramState.FIELD).size() - 1).path("payload").path("index")).put("sourceSha256", "0".repeat(64));
                    case "receipt" -> changedReceipts = JSON.createObjectNode();
                    default -> {
                    }
                }
                var frozenReceipts = changedReceipts;
                assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.selectInstalled(changed, layout, frozenReceipts, mutation.equals("task") ? java.util.UUID.randomUUID().toString() : memory.id.toString()), phase + ":" + mutation);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prepare", "bind", "dependencies", "native", "resolve"})
    void everyProgramRequiresCurrentRendererFrameAndCompleteTargets(String phase) {
        var current = current(phase);
        var facts = DeploymentAgentToolchainInputEvidence.select(current).orElseThrow().facts();
        assertEquals(true, facts.get("inputMatchesRenderer"));
        assertEquals(true, facts.get("launcherAndArgumentsMatch"));
        assertEquals(true, facts.get("targetsMatchTypedArguments"));
        assertEquals(facts.get("stdinSha256"), facts.get("bundledSourceSha256"));
        assertNotEquals(facts.get("stdinSha256"), facts.get("exactActionSha256"));
        assertEquals(current.get("completeExecutableInput").asText().getBytes(StandardCharsets.UTF_8).length, facts.get("utf8Bytes"));
        assertFalse(facts.containsKey("approved"));
        assertFalse(facts.containsKey("installed"));
        assertFalse(facts.containsKey("safe"));
        assertEquals(phase.equals("native") ? "NATIVE_BUILD" : phase.equals("dependencies") ? "PYTHON"
            : phase.equals("bind") ? "EXACT_TOOLCHAIN_BINDING" : "RUST", facts.get("profile"));
    }

    @Test
    void controllerNavigationPointsIntoTheCompleteCurrentSourceWithoutAddingPermission() {
        var prepare = current("prepare");
        var binding = current("bind");
        var source = prepare.path("completeExecutableInput").asText();
        var prepared = DeploymentAgentToolchainInputEvidence.select(prepare).orElseThrow().facts();
        var bound = DeploymentAgentToolchainInputEvidence.select(binding).orElseThrow().facts();
        assertEquals(prepared.get("locations"), bound.get("locations"));
        assertEquals(prepared.get("bundledSourceSha256"), bound.get("bundledSourceSha256"));
        assertEquals(Math.toIntExact(source.lines().count()), prepared.get("lineCount"));
        var locations = JSON.valueToTree(prepared.get("locations"));
        var lines = source.lines().toList();
        var names = new java.util.HashSet<String>();
        for (var location : locations) {
            assertEquals(Set.of("function", "sourcePointer", "startLine", "endLine"),
                new java.util.HashSet<>(JSON.convertValue(location, java.util.Map.class).keySet()));
            String name = location.path("function").asText();
            int first = location.path("startLine").asInt();
            int last = location.path("endLine").asInt();
            assertTrue(names.add(name));
            assertEquals("/completeExecutableInput", location.path("sourcePointer").asText());
            assertTrue(first >= 1 && last >= first && last <= lines.size());
            assertTrue(lines.get(first - 1).startsWith("def " + name + "("));
            assertFalse(String.join("\n", lines.subList(first, last)).contains("\ndef "));
        }
        assertTrue(names.containsAll(Set.of("resolution_validate", "download", "extract", "agent_sandbox_properties", "agent_worker",
            "agent_vendor", "agent_execute", "agent_freeze", "agent_stopped", "agent_promote", "agent_installation_key", "agent_installation_selection", "agent_record",
            "agent_identity_reject", "agent_exception_facts", "agent_lifecycle_cause", "agent_valid_lifecycle_cause", "agent_cli")));
        assertFalse(DeploymentAgentToolchainInputEvidence.select(current("resolve")).orElseThrow().facts().containsKey("locations"));
        assertFalse(prepared.containsKey("approved"));
        assertFalse(prepared.containsKey("safe"));
        assertFalse(prepared.containsKey("liveIsolationVerified"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dependencies", "native"})
    void nativeNavigationUsesCompleteCurrentSourceAndActualClosureWithoutClaimingAuthentication(String phase) {
        var current = current(phase);
        var facts = DeploymentAgentToolchainInputEvidence.select(current).orElseThrow().facts();
        var source = current.path("completeExecutableInput").asText();
        var lines = source.lines().toList();
        var names = new java.util.HashSet<String>();
        for (var location : JSON.valueToTree(facts.get("locations"))) {
            String name = location.path("function").asText();
            int first = location.path("startLine").asInt();
            int last = location.path("endLine").asInt();
            assertTrue(names.add(name));
            assertEquals("/completeExecutableInput", location.path("sourcePointer").asText());
            assertTrue(first >= 1 && last >= first && last <= lines.size());
            assertTrue(lines.get(first - 1).startsWith("def " + name + "("));
            assertFalse(String.join("\n", lines.subList(first, last)).contains("\ndef "));
        }
        assertTrue(names.containsAll(Set.of("dependency_protected", "dependency_registered", "dependency_tool", "dependency_validate_dpkg",
            "dependency_apt_source_authentication", "dependency_validate_apt", "dependency_validate_dnf", "dependency_main")));
        var transaction = JSON.valueToTree(facts.get("nativeTransaction"));
        String profile = phase.equals("native") ? "NATIVE_BUILD" : "PYTHON";
        for (String manager : List.of("APT", "DNF")) {
            assertEquals(JSON.valueToTree(DeploymentAgentToolchainPrograms.packages(manager, profile)),
                transaction.path("requestedPackages").path(manager));
            assertTrue(transaction.path("transactionTargets").toString().contains(manager + ":authenticated-dependency-closure:" + profile));
        }
        assertEquals("CURRENT_SOURCE_AND_DECLARED_SCOPE_ONLY", transaction.path("evidenceKind").asText());
        assertTrue(transaction.path("osBaseline").asText().contains("no fixed OS binary SHA or observed download authentication result"));
        assertFalse(transaction.has("approved"));
        assertFalse(transaction.has("authenticated"));
        assertFalse(transaction.has("installed"));
        assertEquals(DeploymentAgentToolchainPrograms.dependencies(), source);
    }

    @Test
    void currentBodyAndMeasuredHeaderCannotBorrowAProductName() {
        var changed = current("prepare");
        changed.put("completeExecutableInput", changed.path("completeExecutableInput").asText() + "\n#changed");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(changed));
        var header = current("prepare");
        header.put("exactAction", header.path("exactAction").asText().replace("stdinSha256=", "stdinSha256=0"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(header));
        var kind = current("prepare");
        kind.put("inputKind", "OPAQUE_BOUND_DATA");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(kind));
    }

    @Test
    void launchFlagsExtraCommandsAndPhaseReplacementAreRejected() {
        for (String script : List.of(DeploymentAgentToolchainPrograms.command(arguments("prepare")).replace(" -I -S ", " -I "),
            DeploymentAgentToolchainPrograms.command(arguments("prepare")) + "\n/bin/true",
            DeploymentAgentToolchainPrograms.command(arguments("bind")),
            DeploymentAgentToolchainPrograms.command(arguments("prepare")).replace("/usr/bin/python3", "/tmp/python3"))) {
            var current = current("prepare");
            rebind(current, script, Duration.ofSeconds(60));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(current));
        }
    }

    @Test
    void targetOmissionAdditionDuplicatesAndOperationReplacementAreRejected() {
        var omitted = current("prepare");
        omitted.withArray("resourceTargets").remove(0);
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(omitted));
        var added = current("prepare");
        added.withArray("resourceTargets").add(JSON.valueToTree(new ActionTarget(SERVER.id(), "system-package", "gcc")));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(added));
        var duplicate = current("prepare");
        duplicate.withArray("resourceTargets").add(duplicate.withArray("resourceTargets").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(duplicate));
        var changedOperation = current("prepare");
        var args = new ArrayList<>(arguments("prepare"));
        args.set(6, "b".repeat(32));
        rebind(changedOperation, DeploymentAgentToolchainPrograms.command(args), Duration.ofSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(changedOperation));
    }

    @Test
    void dependencyProfileCannotChooseArbitraryPackagesOrBorrowControllerTargets() {
        var arbitrary = current("dependencies");
        rebind(arbitrary, DeploymentAgentToolchainPrograms.command(List.of("RUST", OPERATION)), Duration.ofSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(arbitrary));
        var scope = current("dependencies");
        scope.set("resourceTargets", JSON.valueToTree(DeploymentAgentToolchainCommands.controllerTargets(SERVER, OPERATION)));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(scope));
        for (String phase : List.of("dependencies", "native")) {
            String profile = phase.equals("native") ? "NATIVE_BUILD" : "PYTHON";
            for (String missing : List.of("APT:configured-authenticated-sources", "DNF:configured-authenticated-sources", "DNF:centos:crb:transaction-only",
                "APT:authenticated-dependency-closure:" + profile, "DNF:authenticated-dependency-closure:" + profile)) {
                var incomplete = current(phase);
                var targets = incomplete.withArray("resourceTargets");
                for (int index = 0; index < targets.size(); index++)
                    if (targets.get(index).path("id").asText().equals(missing)) {
                        targets.remove(index);
                        break;
                    }
                assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(incomplete));
            }
            var extra = current(phase);
            extra.withArray("resourceTargets").add(JSON.valueToTree(new ActionTarget(SERVER.id(), "system-repository", "DNF:third-party")));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(extra));
            var wrongProfile = current(phase);
            wrongProfile.withArray("resourceTargets").add(JSON.valueToTree(new ActionTarget(SERVER.id(), "system-package-transaction", "APT:authenticated-dependency-closure:RUBY")));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(wrongProfile));
        }
    }

    @Test
    void bindingMustRemainCanonicalAndBudgetMayClipTheSshDeadline() {
        var invalid = current("bind");
        rebind(invalid, DeploymentAgentToolchainPrograms.command(List.of("bind", "Jw==", "60", OPERATION)), Duration.ofSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(invalid));
        var clipped = current("prepare");
        rebind(clipped, DeploymentAgentToolchainPrograms.command(arguments("prepare")), Duration.ofSeconds(15));
        assertEquals("PT15S", DeploymentAgentToolchainInputEvidence.select(clipped).orElseThrow().facts().get("timeout"));
    }

    @Test
    void foreignOperationsCannotSupplyTheReservedBinding() {
        var current = current("prepare");
        current.put("operation", "deployment.model.command.v1");
        assertTrue(DeploymentAgentToolchainInputEvidence.select(current).isEmpty());
        current.putObject(DeploymentAgentToolchainInputEvidence.FIELD).put("inputMatchesRenderer", true);
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(current));
    }

    private static ObjectNode current(String phase) {
        String actualPhase = phase.equals("native") ? "dependencies" : phase;
        String profile = phase.equals("native") ? "NATIVE_BUILD" : "PYTHON";
        boolean dependencies = actualPhase.equals("dependencies");
        var current = JSON.createObjectNode().put("operation", "deployment.agent.toolchain." + actualPhase + ".v1")
            .put("businessRevision", REVISION).put("inputKind", "EXECUTABLE_SCRIPT")
            .put("completeExecutableInput", dependencies ? DeploymentAgentToolchainPrograms.dependencies()
                : phase.equals("resolve") ? DeploymentAgentToolchainPrograms.resolver() : DeploymentAgentToolchainPrograms.controller());
        current.set("serverTargets", JSON.valueToTree(List.of(SERVER.id())));
        Set<ActionTarget> targets = dependencies ? DeploymentAgentToolchainCommands.dependencyTargets(SERVER, profile)
            : phase.equals("resolve") ? DeploymentAgentToolchainCommands.resolverTargets(SERVER, gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.RUST, "1.95", "1.95.0")
            : DeploymentAgentToolchainCommands.controllerTargets(SERVER, OPERATION);
        current.set("resourceTargets", JSON.valueToTree(targets));
        rebind(current, DeploymentAgentToolchainPrograms.command(arguments(phase)), Duration.ofSeconds(60));
        return current;
    }

    private static List<String> arguments(String phase) {
        return switch (phase) {
            case "prepare" -> List.of("prepare", "RUST", "1.95", "1.95.0", "60", "-", OPERATION,
                DeploymentAgentToolchainReleaseTest.encoded("RUST", "1.95", "1.95.0", OPERATION));
            case "resolve" -> List.of("resolve", "RUST", "1.95", "1.95.0", "60", OPERATION);
            case "bind" -> List.of("bind", DeploymentAgentToolchainCommandsTest.binding(), "60", OPERATION);
            case "dependencies" -> List.of("PYTHON", OPERATION);
            case "native" -> List.of("NATIVE_BUILD", OPERATION);
            default -> throw new IllegalArgumentException("Unknown test phase");
        };
    }

    private static void rebind(ObjectNode current, String script, Duration timeout) {
        try (var request = new CommandRequest(script, current.path("completeExecutableInput").asText().getBytes(StandardCharsets.UTF_8), timeout, 65536)) {
            current.put("exactAction", RemoteActions.command(SERVER, REVISION, request).exactAction());
        }
    }
}
