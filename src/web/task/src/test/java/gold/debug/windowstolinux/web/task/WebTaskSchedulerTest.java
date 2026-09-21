package gold.debug.windowstolinux.web.task;

import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;

import gold.debug.windowstolinux.web.db.WebPersistence;
import gold.debug.windowstolinux.web.db.WebDatabaseTestContext;
import gold.debug.windowstolinux.web.db.persistence.repository.WebTaskRepository;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.task.scheduler.WebTaskScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WebTaskSchedulerTest {
    @TempDir Path root;
    private WebDatabaseTestContext database;
    @org.junit.jupiter.api.AfterEach void cleanup() { if (database != null) database.close(); }
    private static final WebRequestContext CONTEXT = new WebRequestContext("internal", "internal");

    @Test void tasksOutliveTheirSubmitterAndEventsReplayInOrder() throws Exception {
        var repository = repository();
        try (var scheduler = new WebTaskScheduler(repository, policy(Duration.ofMinutes(30)))) {
            String id = scheduler.submit(CONTEXT, work(false, interaction -> {
                interaction.progress("ANALYSIS_STARTED", WebJsonCodec.object()); return WebJsonCodec.object().put("value", 42);
            }));
            await(scheduler, id, "SUCCEEDED");
            assertEquals(42, scheduler.get(CONTEXT, id).path("result").path("value").asInt());
            var events = scheduler.events(CONTEXT, id, 0);
            assertEquals(4, events.size());
            assertEquals(3, scheduler.events(CONTEXT, id, 1).get(1).path("sequence").asLong());
            assertThrows(SecurityException.class, () -> scheduler.get(new WebRequestContext("other", "internal"), id));
        }
    }

    @Test void decisionsPersistResumeExactlyOnceAndNeverAcceptAnExpiredAnswer() throws Exception {
        var repository = repository();
        try (var scheduler = new WebTaskScheduler(repository, policy(Duration.ofSeconds(5)))) {
            String id = scheduler.submit(CONTEXT, work(false, interaction -> interaction.decide("HOST_KEY", WebJsonCodec.object().put("fingerprint", "SHA256:test"))));
            await(scheduler, id, "WAITING_DECISION");
            String decision = scheduler.get(CONTEXT, id).path("decision").path("id").asText();
            assertFalse(decision.isEmpty());
            scheduler.answer(CONTEXT, id, decision, WebJsonCodec.object().put("accepted", true));
            await(scheduler, id, "SUCCEEDED");
            assertThrows(Exception.class, () -> scheduler.answer(CONTEXT, id, decision, WebJsonCodec.object()));
        }
        try (var scheduler = new WebTaskScheduler(repository, policy(Duration.ofMillis(30)))) {
            String id = scheduler.submit(CONTEXT, work(false, interaction -> interaction.decide("REVIEW", WebJsonCodec.object())));
            await(scheduler, id, "FAILED");
            assertEquals("DECISION_EXPIRED", scheduler.get(CONTEXT, id).path("errorCode").asText());
        }
    }

    @Test void cancellationWakesPendingDecisionAndDoesNotMarkMutationSuccessful() throws Exception {
        try (var scheduler = new WebTaskScheduler(repository(), policy(Duration.ofMinutes(30)))) {
            String id = scheduler.submit(CONTEXT, work(true, interaction -> interaction.decide("REVIEW", WebJsonCodec.object())));
            await(scheduler, id, "WAITING_DECISION"); scheduler.cancel(CONTEXT, id);
            await(scheduler, id, "REVALIDATION_REQUIRED");
            assertTrue(scheduler.get(CONTEXT, id).path("result").isNull());
        }
    }

    @Test void sameTargetMutationsAreSerialized() throws Exception {
        try (var scheduler = new WebTaskScheduler(repository(), policy(Duration.ofMinutes(30)))) {
            AtomicInteger simultaneous = new AtomicInteger(); AtomicInteger peak = new AtomicInteger();
            CountDownLatch firstEntered = new CountDownLatch(1), release = new CountDownLatch(1);
            var operation = work(true, interaction -> {
                peak.accumulateAndGet(simultaneous.incrementAndGet(), Math::max); firstEntered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS)); simultaneous.decrementAndGet(); return WebJsonCodec.object();
            });
            String first = scheduler.submit(CONTEXT, operation); assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            String second = scheduler.submit(CONTEXT, operation); release.countDown();
            await(scheduler, first, "SUCCEEDED"); await(scheduler, second, "SUCCEEDED"); assertEquals(1, peak.get());
        }
    }

    @Test void restartMarksEveryUnfinishedTaskForRevalidationAndLeavesTerminalResults() throws Exception {
        var repository = repository(); var scope = WebPersistence.INTERNAL_SCOPE;
        repository.create(scope, "running", "DEPLOY", "{}", List.of(), true, null, null, null);
        repository.transition(scope, "running", Set.of("QUEUED"), "RUNNING", null, null);
        repository.create(scope, "done", "ANALYZE", "{}", List.of(), false, null, null, null);
        repository.transition(scope, "done", Set.of("QUEUED"), "SUCCEEDED", "{\"ok\":true}", null);
        try (var scheduler = new WebTaskScheduler(repository, policy(Duration.ofMinutes(30)))) {
            assertEquals("REVALIDATION_REQUIRED", scheduler.get(CONTEXT, "running").path("state").asText());
            assertEquals("SUCCEEDED", scheduler.get(CONTEXT, "done").path("state").asText());
            assertTrue(scheduler.get(CONTEXT, "done").path("result").path("ok").asBoolean());
        }
    }

    @Test void rawExceptionsNeverBecomePersistedTaskErrors() throws Exception {
        try (var scheduler = new WebTaskScheduler(repository(), policy(Duration.ofMinutes(30)))) {
            String id = scheduler.submit(CONTEXT, work(false, interaction -> { throw new Exception("password=sensitive-value /private/path SQL"); }));
            await(scheduler, id, "FAILED");
            assertFalse(scheduler.get(CONTEXT, id).toString().contains("sensitive"));
            assertFalse(scheduler.events(CONTEXT, id, 0).toString().contains("sensitive"));
        }
    }

    @Test void rejectedDecisionPayloadsDoNotPersistAndValidAnswersRemainPossible() throws Exception {
        var repository=repository();
        try(var scheduler=new WebTaskScheduler(repository, policy(Duration.ofMinutes(30)))) {
            String id=scheduler.submit(CONTEXT,work(false,interaction->interaction.decide("SECRET",WebJsonCodec.object().put("code","backup.password"))));
            await(scheduler,id,"WAITING_DECISION");String decision=scheduler.get(CONTEXT,id).path("decision").path("id").asText();
            assertThrows(IllegalArgumentException.class,()->scheduler.answer(CONTEXT,id,decision,WebJsonCodec.object().put("value","must-not-persist")));
            assertEquals("WAITING_DECISION",scheduler.get(CONTEXT,id).path("state").asText());
            scheduler.answer(CONTEXT,id,decision,WebJsonCodec.object().put("secretId","encrypted-reference"));await(scheduler,id,"SUCCEEDED");
            
            try(var connection=database.source().getConnection();var q=connection.createStatement();var rows=q.executeQuery("SELECT answer_json FROM task_decisions")) {
                assertTrue(rows.next());assertFalse(rows.getString(1).contains("must-not-persist"));assertTrue(rows.getString(1).contains("encrypted-reference"));
            }
        }
    }

    @Test void exactInputFieldsAndChoicesAreCheckedBeforeResuming() throws Exception {
        try(var scheduler=new WebTaskScheduler(repository(), policy(Duration.ofMinutes(30)))) {
            var fields=WebJsonCodec.read("{\"fields\":[{\"id\":\"app/type\",\"choices\":[\"STATIC_SITE\"]}]}");
            String id=scheduler.submit(CONTEXT,work(false,interaction->interaction.decide("INPUTS",fields)));
            await(scheduler,id,"WAITING_DECISION");String decision=scheduler.get(CONTEXT,id).path("decision").path("id").asText();
            for(String invalid:List.of("{\"values\":{\"password\":\"private\"}}","{\"values\":{\"app/type\":\"SHELL\"}}","{\"values\":{\"app/type\":true}}"))
                assertThrows(IllegalArgumentException.class,()->scheduler.answer(CONTEXT,id,decision,WebJsonCodec.read(invalid)));
            scheduler.answer(CONTEXT,id,decision,WebJsonCodec.read("{\"values\":{\"app/type\":\"STATIC_SITE\"}}"));await(scheduler,id,"SUCCEEDED");
        }
    }

    @Test void secretLikeConfigurationIsRejectedBeforeDecisionPersistence() throws Exception {
        try(var scheduler=new WebTaskScheduler(repository(), policy(Duration.ofMinutes(30)))) {
            var prompt=WebJsonCodec.read("{\"fields\":[{\"id\":\"app/configuration\",\"choices\":[]}]}");
            String id=scheduler.submit(CONTEXT,work(false,interaction->interaction.decide("INPUTS",prompt)));
            await(scheduler,id,"WAITING_DECISION");String decision=scheduler.get(CONTEXT,id).path("decision").path("id").asText();
            assertThrows(RuntimeException.class,()->scheduler.answer(CONTEXT,id,decision,WebJsonCodec.read("{\"values\":{\"app/configuration\":\"API_TOKEN=must-not-persist\"}}")));
            scheduler.answer(CONTEXT,id,decision,WebJsonCodec.read("{\"values\":{\"app/configuration\":\"PORT=8080\"}}"));await(scheduler,id,"SUCCEEDED");
            assertFalse(scheduler.get(CONTEXT,id).toString().contains("must-not-persist"));
        }
    }
    private static gold.debug.windowstolinux.web.task.model.WebTaskPolicy policy(Duration timeout) {
        return new gold.debug.windowstolinux.web.task.model.WebTaskPolicy(4, 32, 10000, timeout, Duration.ofSeconds(30), Duration.ofSeconds(10));
    }
    private WebTaskRepository repository() throws Exception {
        if (database == null) database = new WebDatabaseTestContext(root); return database.tasks();
    }
    private static PreparedWebOperation work(boolean mutating, PreparedWebOperation.WebWork action) {
        return new PreparedWebOperation("TEST", WebJsonCodec.object(), List.of(), List.of("test.invalid:22"), mutating, null, null, null, action);
    }
    private static void await(WebTaskScheduler scheduler, String id, String state) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            if (scheduler.get(CONTEXT, id).path("state").asText().equals(state)) return;
            Thread.sleep(10);
        }
        fail("Task did not reach " + state + ": " + scheduler.get(CONTEXT, id));
    }
}
