package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

/** One strict serial autonomous tool request. / 一项严格串行的自主工具请求。 */
public sealed interface DeploymentAgentProposal {
    /** Local model-authored reply to one original native OPEN denial, without execution authority. */
    record ReviewResponse(String reviewBinding, String message) implements DeploymentAgentProposal {
    }

    record LoadSkill(String id) implements DeploymentAgentProposal {
    }

    record SourceIndex(String path, int offset, int limit) implements DeploymentAgentProposal {
    }

    record EvidenceIndex(int offset, int limit) implements DeploymentAgentProposal {
    }

    /** Reads a bounded page of the retained current plan. / 读取留存当前计划的有界页。
     * @param component declared component / 已声明组件
     * @param offset character offset / 字符偏移
     * @param limit page size / 页大小 */
    record PlanState(String component, int offset, int limit) implements DeploymentAgentProposal {
    }

    /** Reads actual retained feedback without executing a tool. / 不执行工具而读取真实留存反馈。
     * @param id observation ID / 观察标识
     * @param offset character offset / 字符偏移
     * @param limit page size / 页大小 */
    record Observation(String id, int offset, int limit) implements DeploymentAgentProposal {
    }

    /** Decision and action share one model response. / 决策与动作共用一次模型响应。
     * @param action selected action / 选定动作
     * @param assessment public evidence summary / 公开证据摘要 */
    record Assessed(DeploymentAgentProposal action,
                    DeploymentAgentAssessment assessment) implements DeploymentAgentProposal {
    }

    /** Independent identification. / 独立识别。
     * @param findings bounded source findings / 有界源码发现 */
    record Identify(java.util.List<DeploymentAgentIdentification> findings) implements DeploymentAgentProposal {
    }

    /** Optional narrow deployment capability. / 可选窄部署能力。
     * @param name registered action name / 已登记动作名称
     * @param component component identity / 组件身份
     * @param arguments exact typed arguments / 精确类型化参数 */
    record Capability(String name, String component,
                      com.fasterxml.jackson.databind.JsonNode arguments) implements DeploymentAgentProposal {
    }

    /** Browses current component snapshot after a patch. / 补丁后浏览当前组件快照。
     * @param component component / 组件
     * @param kind list, read or search / 列举、读取或检索
     * @param path relative path or literal query / 相对路径或字面查询
     * @param offset page offset / 页偏移
     * @param limit page limit / 页上限 */
    record CurrentSource(String component, String kind, String path, int offset,
                         int limit) implements DeploymentAgentProposal {
    }

    /** Lists a safe source directory. / 列举安全源码目录。
     * @param path relative path / 相对路径
     * @param offset page cursor / 页游标
     * @param limit page limit / 页限制 */
    record ListSource(String path, int offset, int limit) implements DeploymentAgentProposal {
    }

    /** Reads frozen source. / 读取冻结源码。
     * @param path relative file / 相对文件
     * @param offset line cursor / 行游标
     * @param limit line limit / 行限制 */
    record ReadSource(String path, int offset, int limit) implements DeploymentAgentProposal {
    }

    /** Searches frozen source. / 检索冻结源码。
     * @param query literal query / 字面查询
     * @param offset file cursor / 文件游标
     * @param limit file limit / 文件限制 */
    record SearchSource(String query, int offset, int limit) implements DeploymentAgentProposal {
    }

    /** Inspects actual server facts. / 检查实际服务器事实。 */
    record InspectServer() implements DeploymentAgentProposal {
    }

    /** Proposes a source-backed application graph. / 提议来源支持的应用图。
     * @param plan typed graph / 类型化图
     * @param explanation nonsecret rationale / 不含秘密的理由 */
    record Plan(DeploymentAgentPlan plan, String explanation) implements DeploymentAgentProposal {
    }

    /** Runs one scoped build command. / 执行一项限定范围的构建命令。
     * @param component declared component / 已声明组件
     * @param script bounded exact script / 有界精确脚本
     * @param seconds deadline / 截止秒数 */
    record Command(String component, String script, int seconds) implements DeploymentAgentProposal {
    }

    /** Executes the current typed verification in the candidate, never published health. */
    record ValidateRuntime(String component) implements DeploymentAgentProposal {
    }

    /** Model-authored host prerequisite subject to independent review before dispatch. / 派发前须独立审核的模型主机前置命令。
     * @param script complete original reviewed executable script / 完整原已审阅可执行脚本
     * @param seconds bounded command timeout in seconds / 有界命令超时秒数
     * @param reason bounded evidence-supported review explanation / 有界依据支持的审核说明 */
    record HostCommand(String script, int seconds, String reason) implements DeploymentAgentProposal {
    }

    /** Reads current remote task source. / 读取当前远端任务源码。
     * @param component declared component / 已声明组件
     * @param path relative file / 相对文件
     * @param offset line cursor / 行游标
     * @param limit line limit / 行限制 */
    record ReadRemote(String component, String path, int offset, int limit) implements DeploymentAgentProposal {
    }

    /** Applies an exact revision-bound patch. / 应用精确修订绑定补丁。
     * @param component declared component / 已声明组件
     * @param patch structured revision-bound edit / 结构化修订绑定编辑 */
    record Patch(String component, DeploymentAgentPatch patch) implements DeploymentAgentProposal {
    }

    /** Seals one verified build output. / 封存一项已核实构建输出。
     * @param component declared component / 已声明组件 */
    record Seal(String component) implements DeploymentAgentProposal {
    }

    /** Requests full managed publication and health. / 请求完整受管发布与健康核验。 */
    record Deliver() implements DeploymentAgentProposal {
    }

    /** Requests missing human input. / 请求缺失人工输入。
     * @param question bounded question / 有界问题 */
    record NeedInput(String question) implements DeploymentAgentProposal {
    }

    /** Stops after model inability. / 模型无法处理时停止。
     * @param reason bounded reason / 有界原因 */
    record Unable(String reason) implements DeploymentAgentProposal {
    }
}
