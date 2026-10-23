package gold.debug.windowstolinux.mod.deployment.ai.agent;

import java.util.*;
import java.util.concurrent.*;
import gold.debug.windowstolinux.appapi.ai.model.*;

/** Parallelizes only registered frozen local reads; commits on the caller in model order. */
public final class DeploymentToolScheduler {
    public enum Execution { LOCAL_READ, EXCLUSIVE }
    public interface Prepared {
        Object run() throws Exception;
        AiToolResult commit(AiToolCall call, Object immutableResult) throws Exception;
    }
    public interface Adapter {
        Execution execution(AiToolCall call);
        Prepared prepare(AiToolCall call) throws Exception;
        void checkpoint() throws Exception;
        boolean stopped();
    }
    private DeploymentToolScheduler() { }

    public static List<AiToolResult> execute(List<AiToolCall> calls, Adapter adapter) throws Exception {
        if (calls.size() > 4 || calls.stream().map(AiToolCall::id).distinct().count() != calls.size())
            return calls.stream().map(call -> new AiToolResult(call.id(), "BATCH_REJECTED: invalid native call count or duplicate ID", true)).toList();
        var output = new ArrayList<AiToolResult>();
        var workers = Executors.newFixedThreadPool(4, Thread.ofPlatform().name("deployment-read-", 0).factory());
        try {
            for (int cursor = 0; cursor < calls.size();) {
                adapter.checkpoint();
                if (adapter.stopped()) {
                    for (; cursor < calls.size(); cursor++) output.add(new AiToolResult(calls.get(cursor).id(), "NOT_DISPATCHED: session ended", true));
                    break;
                }
                var call = calls.get(cursor);
                if (adapter.execution(call) != Execution.LOCAL_READ) {
                    output.add(executeExclusive(call, adapter)); cursor++; continue;
                }
                int end = cursor;
                while (end < calls.size() && end - cursor < 4 && adapter.execution(calls.get(end)) == Execution.LOCAL_READ) end++;
                var prepared = new ArrayList<Prepared>();
                var pending = new ArrayList<Future<Object>>();
                for (int i = cursor; i < end; i++) {
                    adapter.checkpoint();
                    if (adapter.stopped()) throw new CancellationException("Session stopped before read dispatch");
                    try {
                        var work = adapter.prepare(calls.get(i)); prepared.add(work); pending.add(workers.submit(work::run));
                    } catch (IllegalArgumentException invalid) {
                        prepared.add(null); pending.add(CompletableFuture.completedFuture(invalid));
                    }
                }
                // Drain the whole group even when a checkpoint or a worker fails.
                Exception failure = null;
                var values = new ArrayList<Object>();
                boolean interrupted = false;
                for (var future : pending) {
                    while (true) {
                        try { values.add(future.get()); break; }
                        catch (InterruptedException cancelled) { interrupted = true; failure = cancelled; }
                        catch (ExecutionException failed) { values.add(failed.getCause()); break; }
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                if (failure != null) throw failure;
                adapter.checkpoint();
                for (int i = 0; i < values.size(); i++) {
                    var value = values.get(i); var original = calls.get(cursor+i);
                    if (value instanceof IllegalArgumentException invalid) output.add(rejected(original, invalid));
                    else if (value instanceof Exception failed) throw failed;
                    else if (value instanceof Error failed) throw failed;
                    else {
                        try { output.add(prepared.get(i).commit(original, value)); }
                        catch (IllegalArgumentException invalid) { output.add(rejected(original, invalid)); }
                    }
                }
                cursor = end;
            }
            return List.copyOf(output);
        } finally {
            workers.shutdown();
            boolean interrupted = Thread.interrupted();
            while (!workers.isTerminated()) {
                try { workers.awaitTermination(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException waiting) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static AiToolResult executeExclusive(AiToolCall call, Adapter adapter) throws Exception {
        try {
            var work = adapter.prepare(call);
            adapter.checkpoint();
            return work.commit(call, work.run());
        } catch (IllegalArgumentException invalid) { return rejected(call, invalid); }
    }

    public static AiToolResult rejected(AiToolCall call, IllegalArgumentException invalid) {
        String reason = String.valueOf(invalid.getMessage()).replaceAll("(?i)(password|token|secret)(\\s*[:=]\\s*)[^\\s,;]+", "$1$2[redacted]");
        return new AiToolResult(call.id(), "TOOL_REJECTED: " + reason.substring(0, Math.min(512, reason.length())), true);
    }
}
