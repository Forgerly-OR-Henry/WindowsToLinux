package gold.debug.windowstolinux.shared.agent.execution.protocol;

import java.util.*;

import gold.debug.windowstolinux.shared.deploy.delivery.ManagedDelivery;
import gold.debug.windowstolinux.shared.linux.workspace.RemoteSourcePatch;

/** One strict autonomous tool request, executed serially. / 一个严格且串行执行的自主工具请求。 */
public sealed interface AgentProposal {
    /** Enumerates a safe source directory. / 列举安全源码目录。
     * @param path relative directory / 相对目录
     * @param offset cursor / 游标
     * @param limit page size / 分页大小
     */
    record ListSource(String path, int offset, int limit) implements AgentProposal {
    }

    /** Reads actual file contents. / 读取实际文件内容。
     * @param path relative file / 相对文件
     * @param offset line offset / 行偏移
     * @param limit line count / 行数
     */
    record ReadSource(String path, int offset, int limit) implements AgentProposal {
    }

    /** Searches a bounded file page. / 搜索有界文件分页。
     * @param query literal text / 字面文本
     * @param offset file cursor / 文件游标
     * @param limit file count / 文件数
     */
    record SearchSource(String query, int offset, int limit) implements AgentProposal {
    }

    /** Obtains fresh bounded server facts. / 获取最新有界服务器事实。 */
    record InspectServer() implements AgentProposal {
    }

    /** Proposes an evidence-backed delivery graph. / 提议有证据支持的交付图。
     * @param delivery proposed graph / 提议图
     * @param explanation nonsecret plan explanation / 非秘密方案说明
     */
    record Plan(ManagedDelivery delivery, String explanation) implements AgentProposal {
    }

    /** Executes model-generated script in the task sandbox. / 在任务沙箱中执行模型生成脚本。
     * @param component declared component / 已声明组件
     * @param script exact script / 精确脚本
     * @param seconds time limit / 时间限制
     */
    record Command(String component, String script, int seconds) implements AgentProposal {
    }

    /** Reads the current Linux task source. / 读取当前 Linux 任务源码。
     * @param component component identity / 组件身份
     * @param path relative file / 相对文件
     * @param offset line offset / 行偏移
     * @param limit line count / 行数
     */
    record ReadRemote(String component, String path, int offset, int limit) implements AgentProposal {
    }

    /** Proposes a dedicated source patch. / 提议专用源码补丁。
     * @param component component identity / 组件身份
     * @param patch revision-bound diff / 修订绑定差异
     */
    record Patch(String component, RemoteSourcePatch patch) implements AgentProposal {
    }

    /** Seals a component's built artifact. / 封存组件构建制品。
     * @param component component identity / 组件身份
     */
    record Seal(String component) implements AgentProposal {
    }

    /** Requests managed publication and whole-application health verification. / 请求受管发布及整应用健康验证。 */
    record Deliver() implements AgentProposal {
    }

    /** Requests missing user input instead of inventing it. / 请求缺失用户输入，不编造输入。
     * @param question bounded nonsecret question / 有界非秘密问题
     */
    record NeedInput(String question) implements AgentProposal {
    }

    /** Requests a forward model handoff after inability. / 无法处理时请求向后接替模型。
     * @param reason bounded reason / 有界原因
     */
    record Unable(String reason) implements AgentProposal {
    }
}
