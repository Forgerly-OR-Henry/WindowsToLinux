package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish.DeploymentAssistedGraphPublishRunner;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish.DeploymentAssistedSinglePublishRunner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.DeploymentStandardWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentAssistedBuildCorrection;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardModeSupport;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardRecoverySupport;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy.DeploymentPublishPolicyContext;

/** Independent assisted mode entry using shared deterministic publication. / 使用共用确定性发布的独立辅助模式入口。 */
public final class DeploymentAssistedWorkflow implements Entry {
    /** Shared deterministic pipeline bound to this mode entry. / 绑定当前模式入口的共用确定性流程。 */
    private final DeploymentStandardWorkflow workflow;

    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param references original formal publication and reference flow / 原正式发布及引用流程
     * @param recoverySupport assembled original recovery route / 已装配原恢复路由 */
    public DeploymentAssistedWorkflow(ModContext context, DeploymentRefFlow references, DeploymentStandardRecoverySupport recoverySupport) {
        workflow = new DeploymentStandardWorkflow(context, references, support(), recoverySupport, DeploymentAssistedSinglePublishRunner::new, DeploymentAssistedGraphPublishRunner::new);
    }

    /** Admits one source, review and publication task using captured page inputs. / 使用已捕获页面输入准入一项来源、审阅与发布任务。
     * @param server exact original target server / 精确原目标服务器
     * @param draft captured source and page values / 已捕获来源及页面值
     * @param git optional captured Git source / 可选已捕获 Git 来源
     * @param reviewer original human review adapter / 原人工审阅适配
     * @param approval captured approval policy / 已捕获审批策略
     * @param remember captured exact-action reuse preference / 已捕获精确动作复用偏好
     * @return handle for the admitted assisted deployment task / 已准入辅助部署任务的句柄
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public TaskHandle<Void> submit(ServerId server, Draft draft, Optional<GitSource> git, Reviewer reviewer, String approval, boolean remember) throws ApiException {
        return workflow.submit(server, draft, git, reviewer, approval, remember);
    }

    /** Admits one source, review and publication task using captured page inputs. / 使用已捕获页面输入准入一项来源、审阅与发布任务。
     * @param server exact original target server / 精确原目标服务器
     * @param draft captured source and page values / 已捕获来源及页面值
     * @param reviewer original human review adapter / 原人工审阅适配
     * @return handle for the admitted assisted deployment task / 已准入辅助部署任务的句柄
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public TaskHandle<Void> submit(ServerId server, Draft draft, Reviewer reviewer) throws ApiException {
        return submit(server, draft, Optional.empty(), reviewer, "MANUAL_REVIEW", true);
    }

    /** Admits one source, review and publication task using captured page inputs. / 使用已捕获页面输入准入一项来源、审阅与发布任务。
     * @param server exact original target server / 精确原目标服务器
     * @param draft captured source and page values / 已捕获来源及页面值
     * @param git optional captured Git source / 可选已捕获 Git 来源
     * @param reviewer original human review adapter / 原人工审阅适配
     * @return original validated result or captured adapter / 原已校验结果或已捕获适配
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public TaskHandle<Void> submit(ServerId server, Draft draft, Optional<GitSource> git, Reviewer reviewer) throws ApiException {
        return submit(server, draft, git, reviewer, "MANUAL_REVIEW", true);
    }

    /** Validates human or suggested values against the original deterministic form. / 按原确定性表单校验人工或建议值。
     * @param prompt original deterministic review prompt / 原确定性审阅问题
     * @param answer reviewed component answer / 已审阅组件答案 */
    public void validateReview(Prompt prompt, Answers answer) {
        workflow.validateReview(prompt, answer);
    }

    /** Supplies the mode adapter assembled by the standard entry. / 提供标准入口装配的模式适配。
     * @return assembled standard mode adapter / 已装配标准模式适配 */
    public static DeploymentStandardModeSupport support() {
        return new DeploymentStandardModeSupport() {
            /** Identifies whether the captured entry permits assisted extensions. / 标识已捕获入口是否允许辅助扩展。
             * @return true because this entry enables assisted extensions / 当前入口启用辅助扩展，因此为真 */
            public boolean assisted() {
                return true;
            }

            /** Captures the original task mode, approval policy and model revision. / 捕获原任务模式、审批策略及模型修订。
             * @param context original Mod capabilities / 原 Mod 能力
             * @param task original admitted task / 原已准入任务
             * @param approval captured approval policy / 已捕获审批策略
             * @param remember captured exact-action reuse preference / 已捕获精确动作复用偏好
             * @return frozen capabilities and model revision / 冻结能力及模型修订
             * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
            public Scope freeze(ModContext context, TaskContext task, String approval, boolean remember) throws ApiException {
                requireSkills();
                var frozen = DeploymentAssistedScope.freeze(context, task, approval, remember);
                return new Scope(frozen, frozen.modelRevision());
            }

            /** Rebinds the original captured mode without creating another task. / 重新绑定原捕获模式，不另建任务。
             * @param context original Mod capabilities / 原 Mod 能力
             * @param task original admitted task / 原已准入任务
             * @param revision captured original model revision / 已捕获原模型修订
             * @param approval captured approval policy / 已捕获审批策略
             * @param remember captured exact-action reuse preference / 已捕获精确动作复用偏好
             * @return capabilities rebound to the captured original mode / 重新绑定原捕获模式的能力
             * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
            public ModContext recover(ModContext context, TaskContext task, String revision, String approval, boolean remember) throws ApiException {
                var frozen = new DeploymentAssistedScope(context, task, revision, approval, remember);
                frozen.ai().configurationRevision(gold.debug.windowstolinux.appapi.ai.model.AiPurpose.LANGUAGE, task);
                return frozen;
            }

            /** Creates task-local mode adapters for the shared deterministic pipeline. / 为共用确定性流程建立任务所属的模式适配。
             * @param context original Mod capabilities / 原 Mod 能力
             * @param validate deterministic review validator / 确定性审阅校验器
             * @return task-local mode extension adapter / 任务所属模式扩展适配 */
            public Session session(ModContext context, java.util.function.BiConsumer<Prompt, Answers> validate) {
                var capabilities = ((DeploymentPublishPolicyContext) context).assistedCapabilities();
                var advice = new DeploymentAssistedAdvisor(capabilities);
                var review = new DeploymentAssistedReviewCoordinator(context, validate);
                return new Session() {
                    @Override
                    public void sourceCaptured(ServerId server, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial material,
                                               TaskContext task) throws ApiException {
                        ((DeploymentAssistedScope) capabilities).sourceCaptured(server, material, task);
                    }

                    public gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request workspaceDemand(
                        gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts facts,
                        gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive archive, Answers answer,
                        long availableBytes, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial material, TaskContext task) throws Exception {
                        ((DeploymentAssistedScope) capabilities).archiveCaptured(material, archive, facts.toolchainRequirements(), task);
                        if (answer.workspaceRequest().mode() == gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Mode.EXPLICIT)
                            return answer.workspaceRequest();
                        try {
                            return gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.DeploymentStandardWorkspaceEstimate.estimate(facts);
                        } catch (IllegalArgumentException unknown) {
                            var fields = java.util.List.of(
                                new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField("workspaceMinimumBytes", "maxWorkspaceBytes", "", "", java.util.List.of()),
                                new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField("workspacePreferredBytes", "maxWorkspaceBytes", "", "", java.util.List.of()));
                            var estimate = advice.analyze(new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256()), fields,
                                java.util.Map.of("phase", "WORKSPACE_BUDGET_BEFORE_FINAL_CONFIRMATION", "buildTool", facts.buildTool().name(),
                                    "archiveBytes", Long.toString(archive.byteCount()), "unpackedBytes", Long.toString(archive.uncompressedByteCount()),
                                    "availableBytes", Long.toString(availableBytes), "meaning", "Initial build budget estimate, not measured dependency size; final human confirmation still required."), task);
                            return gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request.automatic(
                                Long.parseLong(estimate.values().get("workspaceMinimumBytes")), Long.parseLong(estimate.values().get("workspacePreferredBytes")));
                        }
                    }

                    /** Compares independently observed source units with deterministic discovery. / 比较独立观测的来源单元与确定性发现。
                     * @param server exact original target server / 精确原目标服务器
                     * @param browser bounded frozen source browser / 有界冻结来源浏览器
                     * @param discovered components discovered from the frozen source / 从冻结来源发现的组件
                     * @param task original admitted task / 原已准入任务
                     * @return components selected from the verified source comparison / 从已核实来源比较选择的组件
                     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
                    public java.util.List<DiscoveredProjectPart> compare(ServerId server,
                                                                         DeploymentSourceBrowser browser,
                                                                         java.util.List<DiscoveredProjectPart> discovered, TaskContext task) throws Exception {
                        return DeploymentSourceComparison.compare(capabilities, server, browser, discovered, task);
                    }

                    /** Applies bounded technical suggestions to the original review prompt. / 将有界技术建议应用于原审阅问题。
                     * @param server exact original target server / 精确原目标服务器
                     * @param prompt original deterministic review prompt / 原确定性审阅问题
                     * @param material original open frozen source handle / 原开放冻结来源句柄
                     * @param task original admitted task / 原已准入任务
                     * @return original form with accepted bounded suggestions / 带已接受有界建议的原表单
                     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
                    public Prompt prompt(ServerId server, Prompt prompt, DeploymentSourceMaterial material, TaskContext task) throws Exception {
                        return review.prompt(server, prompt, advice, material, task);
                    }

                    /** Collects exact component answers and retains independent host approval. / 收集精确组件答案并保留独立宿主审批。
                     * @param server exact original target server / 精确原目标服务器
                     * @param revision captured original model revision / 已捕获原模型修订
                     * @param prompts complete original component review set / 完整原组件审阅集合
                     * @param reviewer original human review adapter / 原人工审阅适配
                     * @param task original admitted task / 原已准入任务
                     * @return complete validated component answers or original refusal / 完整已校验组件答案或原拒绝
                     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
                    public OperationResult<Map<String, Answers>> review(ServerId server, String revision, List<Prompt> prompts, Reviewer reviewer, TaskContext task) throws Exception {
                        return review.review(server, revision, prompts, reviewer, advice, task);
                    }

                    /** Binds bounded build correction to the reviewed component and source. / 将有界构建纠正绑定到已审阅组件及来源。
                     * @param prompt original deterministic review prompt / 原确定性审阅问题
                     * @param initial last deterministically accepted component answers / 上次确定性接受的组件答案
                     * @param reviewer original human review adapter / 原人工审阅适配
                     * @return bounded component correction adapter / 有界组件纠正适配 */
                    public DeploymentAssistedBuildCorrection correction(Prompt prompt, Answers initial, Reviewer reviewer) {
                        return review.correction(prompt, initial, reviewer, advice);
                    }

                    /** Routes the original publication through its captured mode. / 按已捕获模式路由原发布。
                     * @param ready classified result from deterministic publication preparation / 确定性发布准备的分类结果
                     * @return original classified publication preparation or recovery route / 原分类发布准备或恢复路由 */
                    public OperationResult<DeploymentRefFlow.PreparedPublish> publication(OperationResult<DeploymentRefFlow.PreparedPublish> ready) {
                        return DeploymentAssistedReviewCoordinator.publication(ready);
                    }
                };
            }
        };
    }

    /** Validates the assisted instructions before task effects. / 任务效果前校验辅助说明。 */
    private static void requireSkills() {
        DeploymentSkillResources.require(
            gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelExecutionContract.class, "host-command.md");
        DeploymentSkillResources.require(DeploymentSourceComparison.class,
            "source-identification.md", "component-ownership.md", "source-calibration.md");
        DeploymentSkillResources.require(DeploymentAssistedAnalysis.class, "source-analysis.md");
        DeploymentSkillResources.require(DeploymentAssistedAdvisor.class, "failure-advice.md");
        DeploymentSkillResources.require(DeploymentAssistedApproval.class, "fixed-effect-review.md");
        DeploymentSkillResources.require(
            gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish.DeploymentAssistedDeployment.class,
            "assisted-deployment.md", "assisted-deployment-tools.properties", "preparation-gap.md");
        DeploymentSkillResources.require(DeploymentAssistedSinglePublishRunner.class,
            "assisted-single-publish-runner-tools.properties");
        DeploymentSkillResources.require(DeploymentAssistedGraphPublishRunner.class,
            "assisted-graph-publish-runner-tools.properties");
        DeploymentSkillResources.requireDescriptions(
            gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish.DeploymentAssistedDeployment.class,
            "assisted-deployment-tools.properties", "inspect-host", "supplement");
        DeploymentSkillResources.requireDescriptions(DeploymentAssistedSinglePublishRunner.class,
            "assisted-single-publish-runner-tools.properties", "stop", "finish", "retry", "prepare", "build", "publish");
        DeploymentSkillResources.requireDescriptions(DeploymentAssistedGraphPublishRunner.class,
            "assisted-graph-publish-runner-tools.properties", "stop", "finish", "retry", "publish", "prepare", "build");
        DeploymentSkillResources.require(
            gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision.class, "review-response.md");
        DeploymentSkillResources.require(
            gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval.class, "command-review.md");
        DeploymentSkillResources.require(
            gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.class,
            "finite-command-review.md");
    }

}
