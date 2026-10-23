package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Workflow evidence cannot become a command grant or survive its original scope. */
class DeploymentWorkflowEvidenceRegressionTest {
    private final UUID id = UUID.randomUUID();

    private final TaskContext task = task(id);

    private final ServerId server = new ServerId("fixture-server");

    @Test
    void failedHostCloseRetainsReadOnlyPolicyUntilTheOriginalCloseIsConfirmed() throws Exception {
        var attempts = new AtomicInteger();
        var permission = open(() -> {
            if (attempts.incrementAndGet() == 1)
                throw new ApiException(new Problem(ProblemCode.UNKNOWN, "FIXTURE_HOST_CLOSE_UNCONFIRMED", "Host close is unconfirmed", java.util.Map.of(), Optional.empty()));
        });
        assertThrows(ApiException.class, permission::close);
        var retained = DeploymentApprovalPolicies.controller(task).orElseThrow();
        assertFalse(retained.state().editable());
        assertEquals(Optional.of("DEPLOYMENT_APPROVAL_POLICY_SCOPE_CLOSE_UNCONFIRMED"), retained.state().problem());
        assertThrows(ApiException.class, () -> DeploymentApprovalPolicies.boundary(task, "AUTOMATIC"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task, server));
        permission.close();
        assertEquals(2, attempts.get());
        assertTrue(DeploymentApprovalPolicies.controller(task).isEmpty());
    }

    @Test
    void preservesTheActualHandoffAndClosesItsOriginalHandleOnce() throws Exception {
        var closed = new AtomicInteger();
        try (var permission = open(() -> closed.incrementAndGet())) {
            var facts = DeploymentWorkflowAuthorization.reviewFacts(permission, task, command(server.id(), server.id()));
            assertEquals(id.toString(), facts.get("task"));
            assertEquals(List.of(server.id()), facts.get("serverTargets"));
            assertEquals("selected source and deployment prerequisites", facts.get("frozenWorkflowRequest"));
            assertEquals("AUTOMATIC", facts.get("approvalPolicy"));
            assertEquals(true, facts.get("hostHandoffOpened"));
            assertEquals(false, facts.get("recovery"));
            assertFalse(facts.containsKey("commandGrant"));
            permission.close();
            assertThrows(IllegalArgumentException.class,
                () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task, command(server.id(), server.id())));
        }
        assertEquals(1, closed.get());
    }

    @Test
    void rejectsSubstitutedTaskServerResourceAndForgedHandle() throws Exception {
        try (var permission = open(() -> {
        })) {
            assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task(id), command(server.id(), server.id())));
            assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task, command("other-server", "other-server")));
            assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task, command(server.id(), "other-server")));
            assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(() -> {
            }, task, command(server.id(), server.id())));
        }
    }

    @Test
    void failedCleanupRevokesEvidenceButStillRetriesTheOriginalRelease() throws Exception {
        var releases = new AtomicInteger();
        var permission = open(() -> {
            if (releases.incrementAndGet() == 1)
                throw new ApiException(new Problem(ProblemCode.IO_FAILURE, "FIXTURE_RELEASE", "release failed", java.util.Map.of(), Optional.empty()));
        });
        assertThrows(ApiException.class, permission::close);
        assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task, command(server.id(), server.id())));
        permission.close();
        permission.close();
        assertEquals(2, releases.get());
    }

    @Test
    void assistedScopeKeepsOneHandoffAndRejectsClosedOrDifferentPolicyEvidence() throws Exception {
        var ai = stub(gold.debug.windowstolinux.appapi.ai.AiService.class, (proxy, method, args) -> {
            assertEquals("configurationRevision", method.getName());
            return "model-revision";
        });
        var context = stub(ModContext.class, (proxy, method, args) -> {
            assertEquals("ai", method.getName());
            return ai;
        });
        var scope = new gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope(
            context, task, "model-revision", "AUTOMATIC", true);
        try (var permission = open(() -> {
        })) {
            scope.workflowOpened(permission);
            assertTrue(scope.facts(command(server.id(), server.id()), null, task).containsKey("workflowAuthorization"));
            assertThrows(IllegalStateException.class, () -> scope.workflowOpened(permission));
            var changedPolicy = new gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope(
                context, task, "model-revision", "AUTOMATIC", false);
            changedPolicy.workflowOpened(permission);
            assertThrows(IllegalArgumentException.class, () -> changedPolicy.facts(command(server.id(), server.id()), null, task));
            permission.close();
            assertThrows(IllegalArgumentException.class, () -> scope.verify(command(server.id(), server.id()), null, task));
        }
    }

    @Test
    void actualSourceSelectionIsImmutableAndBoundToTheOriginalActiveTaskAndServer() throws Exception {
        var scope = sourceScope("model-revision");
        var source = material(UUID.randomUUID(), "a".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> scope.sourceCaptured(server, source, task));
        try (var permission = open(() -> {
        })) {
            scope.workflowOpened(permission);
            assertThrows(IllegalArgumentException.class, () -> scope.sourceCaptured(server, source, task(id)));
            assertThrows(IllegalArgumentException.class, () -> scope.sourceCaptured(new ServerId("other"), source, task));
            assertFalse(scope.facts(command(server.id(), server.id()), null, task).containsKey("sourceSelection"));
            scope.sourceCaptured(server, source, task);
            scope.sourceCaptured(server, source, task);
            var selection = (java.util.Map<?, ?>) scope.facts(command(server.id(), server.id()), null, task).get("sourceSelection");
            assertEquals(source.snapshot().id().toString(), selection.get("snapshotId"));
            assertEquals(source.snapshot().sha256(), selection.get("sourceRevision"));
            assertEquals(1, selection.get("includedFiles"));
            assertFalse(selection.containsKey("commandGrant"));
            assertThrows(IllegalStateException.class, () -> scope.sourceCaptured(server, material(UUID.randomUUID(), "a".repeat(64)), task));
            assertThrows(IllegalStateException.class, () -> scope.sourceCaptured(server, material(source.snapshot().id(), "b".repeat(64)), task));
            permission.close();
            assertThrows(IllegalArgumentException.class, () -> scope.sourceCaptured(server, source, task));
            assertThrows(IllegalArgumentException.class, () -> scope.facts(command(server.id(), server.id()), null, task));
        }
    }

    @Test
    void sourceObservationRejectsInvalidSnapshotsAndChangedModelRoutes() throws Exception {
        var scope = sourceScope("model-revision");
        try (var permission = open(() -> {
        })) {
            scope.workflowOpened(permission);
            assertThrows(IllegalArgumentException.class, () -> scope.sourceCaptured(server, material(UUID.randomUUID(), "invalid"), task));
            var changed = sourceScope("changed-model");
            changed.workflowOpened(permission);
            assertThrows(ApiException.class, () -> changed.sourceCaptured(server, material(UUID.randomUUID(), "a".repeat(64)), task));
        }
    }

    @Test
    void archiveObservationBindsTheActualOriginalSourceAndIncludedCompanionPaths() throws Exception {
        var scope = sourceScope("model-revision");
        var original = material(UUID.randomUUID(), "a".repeat(64));
        var source = new gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial("fixture", original.snapshot(),
            List.of("project/cli/Cargo.toml", "project/native/CMakeLists.txt", "outside/private.txt"), List.of());
        var archive = archive(source, source.snapshot().id(), "a".repeat(64), "project");
        assertThrows(IllegalArgumentException.class, () -> scope.archiveCaptured(source, archive, List.of(), task));
        try (var permission = open(() -> {
        })) {
            scope.workflowOpened(permission);
            scope.sourceCaptured(server, source, task);
            scope.archiveCaptured(source, archive, List.of(), task);
            scope.archiveCaptured(source, archive, List.of(), task);
            assertTrue(scope.effectFacts(command(server.id(), server.id()), task).containsKey("sourceArchiveObservations"));
            var intent = command(server.id(), server.id()).withSemantics(new ActionSemantics("deployment.build.run.v1",
                Set.of(new ActionTarget(server.id(), "deployment-build", "/fixture/candidate"))));
            var evidence = (java.util.Map<?, ?>) scope.effectFacts(intent, task).get("sourceArchiveObservations");
            var entries = (List<?>) evidence.get("archives");
            assertEquals(1, entries.size());
            var entry = (java.util.Map<?, ?>) entries.getFirst();
            assertEquals(source.snapshot().id().toString(), entry.get("originSnapshotId"));
            assertEquals("a".repeat(64), entry.get("sourceManifestSha256"));
            assertEquals("b".repeat(64), entry.get("archiveSha256"));
            assertEquals(List.of("cli/Cargo.toml", "native/CMakeLists.txt"), entry.get("fileNames"));
            assertEquals(2, entry.get("fileCount"));
            assertEquals(false, entry.get("fileNamesTruncated"));
            assertEquals(archive.path().toString(), entry.get("localArchivePath"));
            var upload = intent.withSemantics(new ActionSemantics("deployment.candidate.upload.v1",
                Set.of(new ActionTarget(server.id(), "deployment-candidate", "/fixture/candidate/mutable/source.tar.gz"))));
            assertEquals(evidence, scope.effectFacts(upload, task).get("sourceArchiveObservations"));
            for (String operation : List.of("deployment.toolchain.prepare.v1", "deployment.toolchain.bind.v1",
                "deployment.toolchain.verify.v1", "deployment.publication.snapshot.v1", "deployment.publication.publish.v1")) {
                var current = intent.withSemantics(new ActionSemantics(operation, intent.semantics().orElseThrow().targets()));
                assertEquals(evidence, scope.effectFacts(current, task).get("sourceArchiveObservations"), operation);
            }
            assertFalse(entry.containsKey("commandGrant"));
            assertThrows(IllegalArgumentException.class, () -> scope.archiveCaptured(source, archive, List.of(), task(id)));
            assertThrows(IllegalArgumentException.class, () -> scope.archiveCaptured(source, archive(source, UUID.randomUUID(), "a".repeat(64), "project"), List.of(), task));
            assertThrows(IllegalArgumentException.class, () -> scope.archiveCaptured(source, archive(source, source.snapshot().id(), "c".repeat(64), "project"), List.of(), task));
            permission.close();
            assertThrows(IllegalArgumentException.class, () -> scope.archiveCaptured(source, archive, List.of(), task));
            assertThrows(IllegalArgumentException.class, () -> scope.effectFacts(intent, task));
        }
    }

    @Test
    void archiveFileNamesAreExplicitlyBoundedWithoutInventingACompleteListing() throws Exception {
        var scope = sourceScope("model-revision");
        var original = material(UUID.randomUUID(), "a".repeat(64));
        var source = new gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial("fixture", original.snapshot(),
            java.util.stream.IntStream.range(0, 80).mapToObj(i -> "file-" + i).toList(), List.of());
        try (var permission = open(() -> {
        })) {
            scope.workflowOpened(permission);
            scope.sourceCaptured(server, source, task);
            scope.archiveCaptured(source, archive(source, source.snapshot().id(), "a".repeat(64), ""), List.of(), task);
            var intent = command(server.id(), server.id()).withSemantics(new ActionSemantics("deployment.build.run.v1",
                Set.of(new ActionTarget(server.id(), "deployment-build", "/fixture/candidate"))));
            var evidence = (java.util.Map<?, ?>) scope.effectFacts(intent, task).get("sourceArchiveObservations");
            var entry = (java.util.Map<?, ?>) ((List<?>) evidence.get("archives")).getFirst();
            assertEquals(80, entry.get("fileCount"));
            assertEquals(64, ((List<?>) entry.get("fileNames")).size());
            assertEquals(true, entry.get("fileNamesTruncated"));
        }
    }

    @Test
    void bindKeepsExactGuardedInputsAndAuthorityWithoutAddingTheSeparateInstallerBody() throws Exception {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        String payload = "WTL-TOOLS-1\tcurrent\nJAVA\tRUNTIME\tMjE=\tcG9tLnhtbA==\tSERIES\t21\t21.0.12.1+1\t"
            + layout.toolchains() + "/versions/java-" + "a".repeat(64) + "\tMANAGED\t"
            + java.util.Base64.getEncoder().encodeToString("https://example.test/official-jdk.tar.gz".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + "\t" + "b".repeat(64) + "\n";
        String encoded = java.util.Base64.getEncoder().encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var scope = sourceScope("model-revision");
        try (var permission = open(() -> {
        }); var request = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest(
            gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.verifiedCommand(
                "'" + layout.helperPath() + "' prepare-toolchains 'bind' '" + encoded + "'", layout),
            new byte[0], java.time.Duration.ofSeconds(30), 8192)) {
            scope.workflowOpened(permission);
            var intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, "frozen", request)
                .withSemantics(new ActionSemantics("deployment.toolchain.bind.v1", Set.of(new ActionTarget(server.id(), "deployment-build", layout.toolchains()))));
            var facts = new java.util.LinkedHashMap<String, Object>(DeploymentCommandEvidence.facts(intent, request, layout));
            facts.put("currentCommandEvidence", scope.facts(intent, request, task));
            var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(facts);
            assertEquals("managed-helper-review.v2", json.path("platformContractRevision").asText());
            assertEquals("bind", json.path("toolchainSubcommand").asText());
            assertEquals("21.0.12.1+1", json.at("/decodedToolchainBinding/toolchains/0/version").asText());
            assertEquals(true, json.at("/decodedToolchainBinding/literalValidation/canonicalBase64").asBoolean());
            assertEquals(id.toString(), json.at("/currentCommandEvidence/workflowAuthorization/task").asText());
            assertEquals(intent.exactAction(), json.path("exactAction").asText());
            assertEquals("EMPTY", json.path("inputKind").asText());
            assertFalse(json.path("currentCommandEvidence").has("toolchainImplementation"));
            assertFalse(json.has("commandGrant"));
            assertTrue(json.toString().length() < 32768);
            permission.close();
            assertThrows(IllegalArgumentException.class, () -> scope.facts(intent, request, task));
        }
    }

    @Test
    void buildPreservesTheWholeExecutableBodyCollectorSourceAndAuthorityUnderThePinnedIsolationContract() throws Exception {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var workspace = new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace("fixture-build", "b".repeat(64), layout);
        var prepareMaven = gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.java.MavenBuildWriter.class.getDeclaredMethod("prepareMaven");
        prepareMaven.setAccessible(true);
        String fragment = (String) prepareMaven.invoke(null);
        String body = "test \"$(sha256sum \"$archive\" | awk '{print $1}')\" = '" + "b".repeat(64)
            + "'\n" + fragment + "\nprintf 'project body remains untrusted and needs full review\\n'\n";
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        String collector = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildOutputProtocol.script(
            workspace, 60, 16, 256, 8192, "ordinary", digest, "-", List.of());
        var scope = sourceScope("model-revision");
        var source = material(UUID.randomUUID(), "a".repeat(64));
        try (var permission = open(() -> {
        }); var request = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest(
            gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.verifiedCommand(collector, layout),
            bytes, java.time.Duration.ofSeconds(75), 65536)) {
            scope.workflowOpened(permission);
            scope.sourceCaptured(server, source, task);
            var captured = archive(source, source.snapshot().id(), "a".repeat(64), "");
            scope.archiveCaptured(source, captured, List.of(), task);
            var reviewedRequest = new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest(
                server,
                new gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts(
                    source.snapshot().directory(), workspace.applicationId(),
                    gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.SPRING_BOOT,
                    gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.MAVEN,
                    List.of(), List.of(), List.of()), source, captured,
                gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot.create(
                    workspace.applicationId(), 1, "fixture-build", java.time.Instant.EPOCH, List.of()),
                List.of(), new gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings(List.of(), Optional.of(List.of())),
                new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.SpringBoot("21",
                    new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck.Http(
                        java.net.URI.create("http://127.0.0.1:46140/"), 200, 10)),
                Optional.empty(), gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits(),
                new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent(
                    workspace.applicationId(), server.id(), source.snapshot().sha256(), captured.sha256(), java.time.Instant.EPOCH),
                false, true, layout);
            var reviewed = new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan(reviewedRequest,
                List.of(gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentPlanAction.VERIFY_SOURCE_IDENTITY,
                    gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentPlanAction.CHECK_HEALTH,
                    gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentPlanAction.ROLLBACK_ON_FAILURE));
            scope.bindPreparation(reviewed, task);
            var intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, "frozen", request)
                .withSemantics(new ActionSemantics("deployment.build.run.v1", Set.of(new ActionTarget(server.id(), "deployment-build", workspace.candidateRoot()))));
            var facts = new java.util.LinkedHashMap<String, Object>(DeploymentCommandEvidence.facts(intent, request, layout));
            facts.put("currentCommandEvidence", scope.facts(intent, request, task));
            var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(facts);
            assertEquals(body, json.path("completeExecutableInput").asText());
            assertArrayEquals(request.stdin(), json.path("completeExecutableInput").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(json.has("candidateMavenBootstrap"));
            assertTrue(scope.reviewInstructions(intent, request, task).contains("Current Maven product fragment:"));
            assertEquals("managed-helper-review.v2", json.path("platformContractRevision").asText());
            assertEquals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.buildEntrySha256(layout),
                json.path("installedBuildEntrySha256").asText());
            assertEquals("b".repeat(64), json.at("/currentCommandEvidence/currentBuildArchive/expectedArchiveSha256").asText());
            assertEquals(workspace.candidateRoot(), json.at("/buildCollector/candidateRoot").asText());
            assertEquals(id.toString(), json.at("/currentCommandEvidence/workflowAuthorization/task").asText());
            assertFalse(json.path("currentCommandEvidence").has("buildIsolationImplementation"));
            assertFalse(json.has("commandGrant"));
            assertTrue(json.toString().length() < 32768);
        }
    }

    @Test
    void nodePreparationUsesProductionReviewWithin40KiBAndDenialCannotAuthorizeOrDispatch() throws Exception {
        var fixture = new gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture();
        fixture.configurationRevision = "model-revision";
        fixture.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("native-denial", "submit_result",
            "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled denial\"}"));
        var authorizations = new AtomicInteger();
        var interaction = stub(InteractionService.class, (proxy, method, args) -> {
            if (method.getName().equals("openReviewedWorkflow"))
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of((WorkflowGrant) () -> {
                }), Optional.empty());
            authorizations.incrementAndGet();
            throw new AssertionError("Denial cannot reach host grants: " + method.getName());
        });
        var ui = stub(gold.debug.windowstolinux.appapi.ui.UiService.class, (proxy, method, args) -> {
            assertEquals("environment", method.getName());
            return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH, "LIGHT", false);
        });
        var context = stub(ModContext.class, (proxy, method, args) -> {
            if (method.getName().equals("interaction")) return interaction;
            if (method.getName().equals("ui")) return ui;
            try {
                return method.invoke(fixture.context, args);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
        var scope = new gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope(
            context, fixture.task, "model-revision", "AUTOMATIC", true);
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        try (var permission = DeploymentWorkflowAuthorization.open(context, fixture.task, server, "model-revision", "AUTOMATIC", true,
            "Deploy the selected survey-scoring application including official Node prerequisites on this server");
             var request = DeploymentToolchainImplementationEvidenceRegressionTest.request(layout, "NODE", "22", "-", "-")) {
            scope.workflowOpened(permission);
            var intent = DeploymentToolchainImplementationEvidenceRegressionTest.intent(server, request, layout.toolchains());
            var failure = assertThrows(ApiException.class, () -> ((gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview) scope.interaction()).approveCommand(intent, request, fixture.task));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", failure.problem().eventCode());
            assertEquals(0, authorizations.get());
            assertEquals(1, fixture.requests.size());
            var call = fixture.requests.getFirst();
            byte[] encoded = gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec.forProtocol(
                gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS).encode("deepseek-flash", call,
                32768, gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel.LOW, java.util.Map.of());
            assertTrue(encoded.length <= 40 * 1024, "Actual encoded request bytes=" + encoded.length);
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(call.messages().getLast().text());
            assertEquals(intent.exactAction(), json.path("exactAction").asText());
            assertEquals(fixture.task.id().toString(), json.at("/currentCommandEvidence/workflowAuthorization/task").asText());
            assertTrue(json.path("currentCommandEvidence").has("toolchainPreparationContract"));
            assertFalse(json.path("currentCommandEvidence").has("toolchainImplementation"));
            assertTrue(call.messages().getFirst().text().contains("selected ecosystem NODE"));
            permission.close();
            assertThrows(IllegalArgumentException.class, () -> scope.facts(intent, request, fixture.task));
            assertEquals(1, fixture.requests.size());
            System.out.println("ASSISTED_NODE_ENCODED_REQUEST_BYTES=" + encoded.length);
        }
    }

    @Test
    void preparationReceivesTheActualSourceRequirementWithItsOriginalArchiveAssociation() throws Exception {
        var scope = sourceScope("model-revision");
        var source = material(UUID.randomUUID(), "a".repeat(64));
        var archive = archive(source, source.snapshot().id(), "a".repeat(64), "");
        var rust = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.RUST;
        var requirement = new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement(
            rust, "1.85", "Cargo.toml",
            gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement.PurposeType.LANGUAGE_TARGET,
            gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement.ConstraintType.MINIMUM,
            Optional.of(gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion.parse(rust, "1.85.0").orElseThrow()));
        try (var permission = open(() -> {
        })) {
            scope.workflowOpened(permission);
            scope.sourceCaptured(server, source, task);
            scope.archiveCaptured(source, archive, List.of(requirement), task);
            var intent = command(server.id(), server.id()).withSemantics(new ActionSemantics("deployment.toolchain.prepare.v1",
                Set.of(new ActionTarget(server.id(), "deployment-build", "/fixture/tools"))));
            var facts = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(scope.effectFacts(intent, task));
            assertEquals("a".repeat(64), facts.at("/sourceArchiveObservations/archives/0/sourceManifestSha256").asText());
            assertEquals("Cargo.toml", facts.at("/sourceArchiveObservations/archives/0/sourceToolchainRequirements/0/source").asText());
            assertEquals("MINIMUM", facts.at("/sourceArchiveObservations/archives/0/sourceToolchainRequirements/0/constraint").asText());
            assertEquals("1.85.0", facts.at("/sourceArchiveObservations/archives/0/sourceToolchainRequirements/0/version").asText());
            assertThrows(IllegalStateException.class, () -> scope.archiveCaptured(source, archive, List.of(), task));
        }
    }

    private static gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive archive(
        gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial source, UUID origin, String manifest, String root) {
        return new gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive(source.snapshot(), "b".repeat(64), 100, 200,
            origin, manifest, root);
    }

    private gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope sourceScope(String revision) {
        var ai = stub(gold.debug.windowstolinux.appapi.ai.AiService.class, (proxy, method, args) -> {
            assertEquals("configurationRevision", method.getName());
            return "model-revision";
        });
        var tasks = stub(gold.debug.windowstolinux.appapi.task.TaskService.class, (proxy, method, args) -> {
            assertEquals("findCheckpoint", method.getName());
            assertSame(task, args[0]);
            assertEquals("deployment.config.remote-layout.v1", args[1]);
            return Optional.empty();
        });
        var context = stub(ModContext.class, (proxy, method, args) -> switch (method.getName()) {
            case "ai" -> ai;
            case "tasks" -> tasks;
            default -> throw new AssertionError("No execution capability in the evidence fixture: " + method.getName());
        });
        return new gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope(context, task, revision, "AUTOMATIC", true);
    }

    private static gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial material(UUID id, String hash) {
        var snapshot = stub(gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot.class, (proxy, method, args) -> switch (method.getName()) {
            case "id" -> id;
            case "sha256" -> hash;
            case "directory" -> java.nio.file.Path.of("C:/fixture/snapshot").toAbsolutePath();
            default -> throw new AssertionError("No source bytes or live capabilities expected: " + method);
        });
        return new gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial("fixture source", snapshot, List.of("Cargo.toml"), List.of());
    }

    private WorkflowGrant open(WorkflowGrant original) throws Exception {
        var interaction = stub(InteractionService.class, (proxy, method, args) -> {
            assertEquals("openReviewedWorkflow", method.getName());
            assertSame(task, args[4]);
            assertEquals(CommandReviewMode.AUTOMATIC, args[1]);
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(original), Optional.empty());
        });
        var tasks = gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture.approvalCheckpoints(task);
        var context = stub(ModContext.class, (proxy, method, args) -> switch (method.getName()) {
            case "interaction" -> interaction;
            case "tasks" -> tasks;
            case "identity" ->
                new gold.debug.windowstolinux.appapi.mod.model.ModIdentity("fixture", "1.0.0", Optional.of("1"));
            default -> throw new AssertionError("No execution capability in evidence fixture: " + method.getName());
        });
        return DeploymentWorkflowAuthorization.open(context, task, server, "model-revision", "AUTOMATIC", true,
            "selected source and deployment prerequisites");
    }

    private static TaskContext task(UUID id) {
        return stub(TaskContext.class, (proxy, method, args) -> switch (method.getName()) {
            case "id" -> id;
            case "cancelToken" -> (gold.debug.windowstolinux.appapi.task.run.CancelToken) () -> false;
            default -> throw new AssertionError("No task execution in evidence fixture: " + method.getName());
        });
    }

    private static ActionIntent command(String server, String resourceServer) {
        return new ActionIntent("platform.ssh.execute", List.of(server), "fixture", "business-revision", "exact", "effect")
            .withSemantics(new ActionSemantics("fixture.command", Set.of(new ActionTarget(resourceServer, "directory", "/fixture"))));
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
