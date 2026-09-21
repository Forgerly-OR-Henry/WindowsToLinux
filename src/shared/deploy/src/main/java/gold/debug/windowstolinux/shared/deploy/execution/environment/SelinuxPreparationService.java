package gold.debug.windowstolinux.shared.deploy.execution.environment;

import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyObservation;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentApprovalException;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentApprovalFailureType;
import gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan;
import gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationState;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Orchestrates approved system changes, bounded reboot recovery and fresh authenticated verification. / 编排已批准系统变更、有界重启恢复和重新认证验证。
 */
final class SelinuxPreparationService {
    /**
     * Timeout.
     * <p>超时。
     */
    private final Duration timeout;
    /**
     * Interval.
     * <p>间隔。
     */
    private final Duration interval;

    /**
     * Initializes selinux preparation service through its shared constructor contract.
     * <p>通过共享构造契约初始化Selinux准备服务。
     */
    SelinuxPreparationService() {
        this(Duration.ofMinutes(15), Duration.ofSeconds(5));
    }

    /**
     * Validates and binds the inputs required by selinux preparation service.
     * <p>校验并绑定Selinux准备服务所需输入。
     *
     * @param timeout timeout / 超时
     * @param interval interval / 间隔
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    SelinuxPreparationService(Duration timeout, Duration interval) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (timeout.isNegative() || timeout.isZero() || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("System preparation waits must be positive");
        }
    }

    /**
     * Pins the approved SSH identity, performs confirmed SELinux preparation and verifies reconnection/enforcement when a reboot is required.
     * <p>固定已批准 SSH 身份、执行已确认 SELinux 准备，并在需要重启时验证重连及强制执行状态。
     *
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param verifier verifier / 验证器
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved host key evaluator / 构造或解析得到的主机键Evaluator
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    HostKeyEvaluator prepare(LinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
                             HostKeyEvaluator verifier, Predicate<SelinuxPreparationPlan> confirmation)
            throws LinuxOperationException {
        HostKeyEvaluator pinned = pinned(verifier);
        boolean reboot;
        boolean mutationStarted = false;
        try (var session = gateway.connect(endpoint, credential.duplicate(), pinned)) {
            var operation = session.selinuxPreparation();
            var inspected = operation.inspect();
            if (inspected.isEmpty()) return pinned;
            var plan = inspected.orElseThrow();
            if (plan.state() == SelinuxPreparationState.COMPLETE) return pinned;
            if (!endpoint.serverId().equals(plan.serverId()) || !confirmation.test(plan)) {
                throw new DeploymentApprovalException(DeploymentApprovalFailureType.CONFIRMATION_REQUIRED,
                        "Explicit confirmation is required for SELinux changes and server reboot");
            }
            if (Thread.currentThread().isInterrupted()) throw incomplete("System preparation cancelled before mutation");
            reboot = plan.state() == SelinuxPreparationState.UNPREPARED
                    || plan.state() == SelinuxPreparationState.REBOOT_PENDING;
            if (reboot) { mutationStarted = true; operation.prepareReboot(plan); }
            else if (plan.state() == SelinuxPreparationState.READY_TO_ENFORCE) {
                mutationStarted = true; operation.enableEnforcement(plan);
            }
            else if (plan.state() != SelinuxPreparationState.ENFORCEMENT_PENDING) {
                throw incomplete("Unexpected SELinux preparation checkpoint");
            }
        } catch (LinuxOperationException failure) {
            if (!mutationStarted) throw LinuxOperationException.beforeEnvironmentPreparation(failure);
            throw failure;
        }
        if (reboot) waitAndEnforce(gateway, endpoint, credential, pinned);
        // A new authenticated connection is required after enforcing mode is enabled. / 启用强制模式后必须通过新连接重新认证。
        try (var session = gateway.connect(endpoint, credential.duplicate(), pinned)) {
            var operation = session.selinuxPreparation();
            var verified = operation.inspect().orElseThrow(() -> incomplete("Target distribution changed"));
            if (verified.state() != SelinuxPreparationState.ENFORCEMENT_PENDING) {
                throw incomplete("Enforcing verification did not complete; safety rollback remains available");
            }
            operation.commitEnforcement(verified);
            if (operation.inspect().orElseThrow(() -> incomplete("Target distribution changed")).state() != SelinuxPreparationState.COMPLETE) {
                throw incomplete("SELinux preparation did not reach its verified final state");
            }
        }
        return pinned;
    }

    /**
     * Waits through the approved reboot window and verifies the expected SELinux enforcement outcome using the pinned host identity.
     * <p>使用固定主机身份等待已批准重启窗口，并验证预期 SELinux 强制执行结果。
     *
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param verifier verifier / 验证器
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private void waitAndEnforce(LinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
                               HostKeyEvaluator verifier) throws LinuxOperationException {
        long deadline = System.nanoTime() + timeout.toNanos();
        LinuxOperationException last = null;
        while (System.nanoTime() < deadline) {
            pause();
            try (var session = gateway.connect(endpoint, credential.duplicate(), verifier)) {
                var operation = session.selinuxPreparation();
                var plan = operation.inspect().orElseThrow(() -> incomplete("Target distribution changed"));
                if (plan.state() == SelinuxPreparationState.REBOOT_PENDING) continue;
                if (plan.state() == SelinuxPreparationState.ENFORCEMENT_PENDING) return;
                if (plan.state() != SelinuxPreparationState.READY_TO_ENFORCE) {
                    throw incomplete("Reboot returned an unexpected SELinux checkpoint");
                }
                operation.enableEnforcement(plan);
                return;
            } catch (LinuxOperationException failure) {
                String code = failure.failure().code();
                if (!code.equals(LinuxOperationFailureType.CONNECTION_FAILED.code())
                        && !code.equals(LinuxOperationFailureType.SSH_COMMAND_FAILED.code())) throw failure;
                last = failure;
            }
        }
        throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                "Timed out waiting for the approved server reboot; inspect target preparation state before resuming", last);
    }

    /**
     * Waits for the configured poll interval, preserving interruption and reporting preparation failure if interrupted.
     * <p>等待配置的轮询间隔；被中断时保留中断状态并报告准备失败。
     *
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private void pause() throws LinuxOperationException {
        try {
            Thread.sleep(interval);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                    "System preparation wait interrupted; approved remote reboot may still be pending", failure);
        }
    }

    /**
     * Builds the structured failure descriptor for incomplete.
     * <p>为未完成构建结构化失败描述。
     *
     * @param detail detail / 详情
     * @return the structured failure descriptor for incomplete / 为未完成构建结构化失败描述
     */
    private static LinuxOperationException incomplete(String detail) {
        return LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED, detail);
    }

    /**
     * Builds host key evaluator from the supplied pinned inputs.
     * <p>根据所提供已固定输入构建主机键Evaluator。
     *
     * @param original original / 原始
     * @return host key evaluator from the supplied pinned inputs / 根据所提供已固定输入构建主机键Evaluator
     */
    private static HostKeyEvaluator pinned(HostKeyEvaluator original) {
        return new HostKeyEvaluator() {
            /**
             * Pinned or freshly observed host-key fingerprint.
             * <p>固定或新近观测的主机密钥指纹。
             */
            private String fingerprint;
            /**
             * Verifies host key decision.
             * <p>验证主机键决定。
             *
             * @param endpoint reviewed network endpoint / 已审阅网络端点
             * @param observed observed / 已观测
             * @return constructed or resolved host key decision / 构造或解析得到的主机键决定
             */
            @Override public HostKeyDecision verify(SshEndpoint endpoint, String observed) {
                if (fingerprint != null && !fingerprint.equals(observed)) return HostKeyDecision.REJECT;
                return original.verify(endpoint, observed);
            }
            /**
             * Verifies host key decision.
             * <p>验证主机键决定。
             *
             * @param endpoint reviewed network endpoint / 已审阅网络端点
             * @param observed observed / 已观测
             * @return constructed or resolved host key decision / 构造或解析得到的主机键决定
             */
            @Override public HostKeyDecision verify(SshEndpoint endpoint, HostKeyObservation observed) {
                if (fingerprint != null && !fingerprint.equals(observed.sshSha256())) return HostKeyDecision.REJECT;
                return original.verify(endpoint, observed);
            }
            /**
             * Tests the authenticated predicate against the supplied evidence.
             * <p>根据所提供证据检查已认证条件。
             *
             * @param endpoint reviewed network endpoint / 已审阅网络端点
             * @param observed observed / 已观测
             * @return true when authenticated predicate against the supplied evidence, false otherwise / 根据所提供证据检查已认证条件时为 true，否则为 false
             */
            @Override public boolean authenticated(SshEndpoint endpoint, HostKeyObservation observed) {
                if (fingerprint != null && !fingerprint.equals(observed.sshSha256())) return false;
                if (!original.authenticated(endpoint, observed)) return false;
                fingerprint = observed.sshSha256();
                return true;
            }
        };
    }
}
