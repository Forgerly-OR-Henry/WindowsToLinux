package gold.debug.windowstolinux.mod.deployment.service.core;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRequest;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitSetup;

/** Real original-task checkpoint for preparation-only fixtures. / 仅准备夹具的真实原任务检查点。 */
public final class DeploymentGitJournalFixture {
    private DeploymentGitJournalFixture() {
    }

    /** Persists all identities before effects; no source project write is proposed. / 效果前持久全部身份，不提议来源项目写入。
     * @param context original context / 原上下文
     * @param task original task / 原任务
     * @param request reviewed request / 审阅请求
     * @return durable observer / 持久观察器
     * @throws ApiException if original checkpoint is unavailable / 原检查点不可用时 */
    public static DeploymentGitSetup.Observer begin(ModContext context, TaskContext task, DeploymentGitRequest request) throws ApiException {
        return begin(context, task, request, 2 * 1024 * 1024, 100);
    }

    public static DeploymentGitSetup.Observer begin(ModContext context, TaskContext task, DeploymentGitRequest request,
                                                    long maximumBytes, int maximumEntries) throws ApiException {
        var journal = DeploymentGitSourceActionLog.begin(context.tasks(), task, UUID.randomUUID(), "Preparation fixture",
            Optional.empty(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), request, maximumBytes, maximumEntries);
        return new DeploymentGitSetup.Observer() {
            public DeploymentGitSetup.Planned planned() {
                return journal.planned();
            }

            public void beforeRequest() throws ApiException {
                journal.beforeRequest();
            }

            public void receipt(DeploymentGitSetup.Receipt receipt) throws ApiException {
                journal.receipt(receipt);
                journal.close();
            }
        };
    }
}
