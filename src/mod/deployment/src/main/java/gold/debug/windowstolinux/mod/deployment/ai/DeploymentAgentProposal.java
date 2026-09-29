package gold.debug.windowstolinux.mod.deployment.ai;

/** One strict serial autonomous tool request. / 一项严格串行的自主工具请求。 */
public sealed interface DeploymentAgentProposal {
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
