package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Retains transaction evidence and publishes each actual event as it occurs. / 保留事务证据，并在每个实际事件发生时发布。
 */
final class DeploymentEventJournal extends ArrayList<DeploymentEvent> {
    /**
     * Progress.
     * <p>进度。
     */
    private final Consumer<DeploymentEvent> progress;
    /**
     * Validates and binds the inputs required by deployment event journal.
     * <p>校验并绑定部署事件日志所需输入。
     *
     * @param progress progress / 进度
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    DeploymentEventJournal(Consumer<DeploymentEvent> progress) { this.progress = java.util.Objects.requireNonNull(progress); }
    /**
     * Adds deployment event journal.
     * <p>添加部署事件日志。
     *
     * @param event state or UI event being processed / 正在处理的状态或 UI 事件
     * @return true when adds deployment event journal, false otherwise / 添加部署事件日志时为 true，否则为 false
     */
    @Override public boolean add(DeploymentEvent event) {
        boolean added = super.add(event);
        progress.accept(event);
        return added;
    }
}
