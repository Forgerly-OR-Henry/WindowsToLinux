package gold.debug.windowstolinux.mod.deployment.linux.command.contract;

import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

/** Fixed model command execution boundary. / 固定模型命令执行边界。 */
public final class DeploymentModelExecutionContract {
    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentModelExecutionContract() {
    }

    /** Fixed original model command execution contract. / 固定原模型命令执行合同。 */
    public static final String EXECUTION_CONTRACT = DeploymentSkillResources.text(DeploymentModelExecutionContract.class, "host-command.md");
}
