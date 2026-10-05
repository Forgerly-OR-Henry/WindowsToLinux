package gold.debug.windowstolinux.mod.files.ui;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.files.file.model.FilesOutcome;
import gold.debug.windowstolinux.mod.files.service.FilesService;

/** Observes actual tasks without waiting forever on UNKNOWN completion. / 观察实际任务，不永久等待 UNKNOWN 完成。 */
final class FilesTasks implements AutoCloseable {
    /** Original public service. / 原公开服务。 */
    private final FilesService service;

    /** Original evidence surface. / 原依据界面。 */
    private final FilesFeedback feedback;

    /** View-owned submission and diagnostic worker. / 视图所属提交及诊断工作线程。 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        var thread = new Thread(action, "files-view-observation");
        thread.setDaemon(true);
        return thread;
    });

    /** Actual accepted short reads, cancellable on lock or close. / 锁定及关闭可取消的实际已接受短读取。 */
    private final List<TaskHandle<?>> reads = new CopyOnWriteArrayList<>();

    /** Actual accepted mutations retained independently of the view. / 独立于视图保留的实际已接受修改。 */
    private final List<Mutation> mutations = new CopyOnWriteArrayList<>();

    /** EDT polling scheduler, never itself doing file work. / EDT 轮询调度器，自身不执行文件操作。 */
    private final Timer timer;

    /** Original callback informing the root of new returned facts. / 将新返回事实告知根的原回调。 */
    private final Runnable changed;

    /** Original returned-effect refresh, separate from read completion. / 与读取完成分开的原返回副作用刷新。 */
    private final Runnable returned;

    /** Submission currently not yet accepted or refused. / 当前尚未被接受或拒绝的提交。 */
    private volatile boolean submitting;

    /** Callback generation for lock and release. / 锁定及释放的回调代次。 */
    private volatile long generation;

    /** View observation lifecycle. / 视图观测生命周期。 */
    private volatile boolean paused, closed, polling;

    /** Captures service without starting any business task. / 捕获服务，不启动任何业务任务。
     * @param service original service / 原服务
     * @param feedback original result display / 原结果展示
     * @param changed result and state callback / 结果及状态回调
     * @param returned actual returned-effect refresh / 实际返回副作用刷新 */
    FilesTasks(FilesService service, FilesFeedback feedback, Runnable changed, Runnable returned) {
        this.service = service;
        this.feedback = feedback;
        this.changed = changed;
        this.returned = returned;
        timer = new Timer(350, event -> poll());
        timer.start();
    }

    /** Submits a short read and filters all revoked callbacks. / 提交短读取并过滤所有已撤销回调。
     * @param <T> actual read payload / 实际读取载荷
     * @param submit original service submission / 原服务提交
     * @param accept observed value callback / 已观测值回调 */
    <T> void read(Callable<TaskHandle<T>> submit, Consumer<T> accept) {
        long epoch = generation;
        worker.execute(() -> {
            try {
                if (!live(epoch)) return;
                var handle = submit.call();
                reads.add(handle);
                if (!live(epoch)) cancel(handle);
                handle.completion().whenComplete((result, failure) -> {
                    reads.remove(handle);
                    SwingUtilities.invokeLater(() -> {
                        if (!live(epoch)) return;
                        feedback.observe(handle.snapshot(), java.util.Optional.empty());
                        if (failure != null) feedback.problem(failure);
                        else if (result != null) {
                            result.problem().ifPresent(problem -> feedback.problem(new IllegalStateException(problem.message())));
                            result.value().ifPresent(accept);
                        }
                        changed.run();
                    });
                });
            } catch (Exception failure) {
                report(epoch, failure);
            }
        });
    }

    /** Submits one mutation without making view release cancel accepted work. / 提交一项修改，视图释放不取消已接受工作。
     * @param submit original mutation submission / 原修改提交 */
    void mutate(Callable<TaskHandle<FilesOutcome>> submit) {
        long epoch = generation;
        submitting = true;
        worker.execute(() -> {
            try {
                if (!live(epoch)) return;
                var handle = submit.call();
                mutations.add(new Mutation(handle));
                SwingUtilities.invokeLater(() -> {
                    if (live(epoch)) poll();
                });
            } catch (Exception failure) {
                report(epoch, failure);
            } finally {
                submitting = false;
                SwingUtilities.invokeLater(() -> {
                    if (live(epoch)) changed.run();
                });
            }
        });
    }

    /** Polls actual state, callback facts and original diagnostics on the worker. / 在线程读取实际状态、回调事实及原诊断。 */
    private void poll() {
        if (closed || paused || polling) return;
        polling = true;
        long epoch = generation;
        worker.execute(() -> {
            try {
                for (var handle : reads) {
                    if (!live(epoch)) break;
                    var state = handle.snapshot();
                    SwingUtilities.invokeLater(() -> {
                        if (live(epoch)) feedback.observe(state, java.util.Optional.empty());
                    });
                }
                for (var mutation : mutations) {
                    if (!live(epoch)) break;
                    var snapshot = mutation.handle.snapshot();
                    var observation = service.observation(mutation.handle.id());
                    SwingUtilities.invokeLater(() -> {
                        if (!live(epoch)) return;
                        feedback.observe(snapshot, observation);
                        changed.run();
                        if (!mutation.refreshed && observation.filter(value -> value.callbackReturned()).isPresent()) {
                            mutation.refreshed = true;
                            returned.run();
                        }
                    });
                    try {
                        var log = service.diagnostics(mutation.handle.id());
                        SwingUtilities.invokeLater(() -> {
                            if (live(epoch)) {
                                feedback.logs(mutation.handle.id(), log);
                                if (snapshot.state() == TaskState.ENDED) mutations.remove(mutation);
                            }
                        });
                    } catch (Exception failure) {
                        report(epoch, failure);
                        if (snapshot.state() == TaskState.ENDED) SwingUtilities.invokeLater(() -> {
                            if (live(epoch)) mutations.remove(mutation);
                        });
                    }
                }
            } catch (Exception failure) {
                report(epoch, failure);
            } finally {
                SwingUtilities.invokeLater(() -> {
                    polling = false;
                });
            }
        });
    }

    /** Reports only a still-owned callback failure. / 仅报告仍归属本视图的回调失败。
     * @param epoch original callback generation / 原回调代次
     * @param failure actual failure / 实际失败 */
    private void report(long epoch, Throwable failure) {
        SwingUtilities.invokeLater(() -> {
            if (live(epoch)) {
                feedback.problem(failure);
                changed.run();
            }
        });
    }

    /** Checks whether callback input remains authorized by the view lifecycle. / 核对回调输入是否仍符合视图生命周期。
     * @param epoch original generation / 原代次
     * @return still active / 仍活动 */
    private boolean live(long epoch) {
        return !closed && !paused && epoch == generation;
    }

    /** Returns whether a currently accepted mutation still blocks new input. / 返回当前已接受修改是否仍阻断新输入。
     * @return actual running mutation / 实际运行中的修改 */
    boolean busy() {
        return submitting || mutations.stream().anyMatch(value -> value.handle.snapshot().state() != TaskState.ENDED
            && value.handle.snapshot().state() != TaskState.UNKNOWN
            && service.observation(value.handle.id()).filter(observed -> observed.callbackReturned()).isEmpty());
    }

    /** Requests cancellation only through original current handles. / 仅通过当前原句柄请求取消。 */
    void cancel() {
        for (var handle : reads) cancel(handle);
        for (var value : mutations) if (value.handle.snapshot().state() != TaskState.ENDED) cancel(value.handle);
    }

    /** Sends an explicit original cancellation request without inferring completion. / 发送明确原取消请求，不推断已完成。
     * @param handle original accepted task / 原已接受任务 */
    private void cancel(TaskHandle<?> handle) {
        try {
            handle.requestCancel();
        } catch (Exception failure) {
            report(generation, failure);
        }
    }

    /** Stops view reading and revokes short read inputs while preserving accepted mutations. / 停止视图读取并撤销短读取输入，保留已接受修改。
     * @param value whether host is locked / 宿主是否锁定 */
    void pause(boolean value) {
        paused = value;
        generation++;
        if (value) {
            timer.stop();
            for (var handle : reads) cancel(handle);
        } else {
            timer.start();
            poll();
        }
    }

    /** Stops and verifies actual owned observation worker cleanup. / 停止并核实实际所属观测线程清理。
     * @throws Exception if original observer does not stop / 原观测线程未停止时 */
    @Override
    public void close() throws Exception {
        closed = true;
        generation++;
        timer.stop();
        for (var handle : reads) cancel(handle);
        worker.shutdownNow();
        if (!worker.awaitTermination(2, TimeUnit.SECONDS))
            throw new IllegalStateException("Files view observation cleanup pending");
    }

    /** Original mutation and one-time returned-fact refresh marker. / 原修改及一次返回事实刷新标记。 */
    private static final class Mutation {
        /** Original authentic accepted handle. / 原真实已接受句柄。 */
        private final TaskHandle<FilesOutcome> handle;

        /** Whether callback-returned facts refreshed directories. / 回调返回事实是否已刷新目录。 */
        private boolean refreshed;

        /** Captures the actual original handle. / 捕获实际原句柄。
         * @param handle original task / 原任务 */
        private Mutation(TaskHandle<FilesOutcome> handle) {
            this.handle = handle;
        }
    }
}
