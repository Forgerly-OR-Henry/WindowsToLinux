package gold.debug.windowstolinux.mod.files.service;

import java.util.regex.Pattern;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Fixed non-secret batch evidence preventing partial work from becoming whole-batch success. / 固定非秘密批次依据，防止部分工作变成整批成功。 */
final class FilesPlan {
    /** Original owner-local checkpoint key. / 原所有者内部检查点键。 */
    static final String KEY = "files-batch";

    /** Exact fixed schema containing only a bounded count and literal enums. / 仅包含有界计数及原样枚举的精确固定结构。 */
    private static final Pattern FORMAT = Pattern.compile("\\{\"items\":([1-9][0-9]{0,5}),\"returned\":(true|false),\"outcome\":\"(SUCCEEDED|FAILED|CANCELLED|UNKNOWN)\"}");

    /** Prevents utility construction. / 禁止构造工具。 */
    private FilesPlan() {
    }

    /** Encodes only controlled counts and enum literals. / 仅编码受控计数及枚举原文。
     * @param items requested effect count / 请求副作用计数
     * @param returned whether author work actually returned / 作者逻辑是否确已返回
     * @param outcome actually returned outcome or initial UNKNOWN / 实际已返回结果或初始UNKNOWN
     * @return fixed bounded JSON without paths or secrets / 不含路径或秘密的固定有界JSON */
    static String encode(int items, boolean returned, TaskResult outcome) {
        if (items < 1 || items > 100000) throw new IllegalArgumentException("Invalid batch count");
        return "{\"items\":" + items + ",\"returned\":" + returned + ",\"outcome\":\"" + outcome.name() + "\"}";
    }

    /** Parses only the exact original fixed schema. / 仅解析精确原固定结构。
     * @param payload actual original checkpoint / 实际原检查点
     * @return validated original batch evidence / 已核验原批次依据
     * @throws ApiException if original evidence is invalid / 原依据无效时 */
    static Evidence read(String payload) throws ApiException {
        var matcher = FORMAT.matcher(payload);
        if (!matcher.matches())
            throw FilesProblems.error(ProblemCode.UNKNOWN, "FILES_BATCH_EVIDENCE", "Original file batch evidence is invalid");
        int items = Integer.parseInt(matcher.group(1));
        if (items > 100000)
            throw FilesProblems.error(ProblemCode.UNKNOWN, "FILES_BATCH_CAPACITY", "Original file batch count is invalid");
        return new Evidence(items, Boolean.parseBoolean(matcher.group(2)), TaskResult.valueOf(matcher.group(3)));
    }

    /** Exact original batch evidence. / 精确原批次依据。
     * @param items requested count / 请求计数
     * @param returned actual callback marker / 实际回调标记
     * @param outcome original callback outcome / 原回调结果 */
    record Evidence(int items, boolean returned, TaskResult outcome) {
    }
}
