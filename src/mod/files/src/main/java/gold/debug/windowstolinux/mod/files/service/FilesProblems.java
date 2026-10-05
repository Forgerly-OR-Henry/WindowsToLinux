package gold.debug.windowstolinux.mod.files.service;

import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Checked diagnostics and explicit aggregate outcomes. / 受检诊断及明确汇总结果。 */
final class FilesProblems {
    /** Prevents utility construction. / 禁止构造工具。 */
    private FilesProblems() {
    }

    /** Creates a bounded public diagnostic. / 创建有界公开诊断。
     * @param code actual problem category / 实际问题类别
     * @param event stable local event / 稳定内部事件
     * @param message non-secret explanation / 非秘密说明
     * @return immutable diagnostic / 不可变诊断 */
    static Problem problem(ProblemCode code, String event, String message) {
        return new Problem(code, event, message, Map.of(), Optional.empty());
    }

    /** Creates a checked refusal. / 创建受检拒绝。
     * @param code actual category / 实际类别
     * @param event stable event / 稳定事件
     * @param message explanation / 说明
     * @return checked diagnostic / 受检诊断 */
    static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(problem(code, event, message));
    }

    /** Maps only explicit cancellation or uncertainty categories specially. / 仅特殊映射明确取消或不确定类别。
     * @param problem actual problem / 实际问题
     * @return corresponding business outcome / 对应业务结果 */
    static TaskResult outcome(Problem problem) {
        return problem.code() == ProblemCode.UNKNOWN ? TaskResult.UNKNOWN
            : problem.code() == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED;
    }
}
