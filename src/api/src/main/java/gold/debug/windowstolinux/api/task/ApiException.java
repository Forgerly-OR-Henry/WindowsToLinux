package gold.debug.windowstolinux.api.task;

/** Reports a rejected request or failed local capability access. / 报告未接纳的请求或失败的本地能力访问。 */
public class ApiException extends Exception {
    /** Structured reason without provider implementation types. / 不包含提供方实现类型的结构化原因。 */
    private final Problem problem;

    /**
     * Preserves the reason without inferring a business outcome. / 保留原因，不据此推断业务结果。
     * @param problem structured failure or uncertainty evidence / 结构化的失败或不确定性证据
     */
    public ApiException(Problem problem) {
        super(java.util.Objects.requireNonNull(problem, "problem").message());
        this.problem = problem;
    }

    /**
     * Returns the immutable public problem. / 返回不可变的公开问题原因。
     * @return structured rejection or local failure / 结构化请求拒绝或本地失败
     */
    public Problem problem() {
        return problem;
    }
}
