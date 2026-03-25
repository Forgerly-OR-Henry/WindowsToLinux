package gold.debug.wintolin.exceptionanderror;

public class MyResult {
    private final boolean isOK; // 结果
    private final String problem; // 问题

    private MyResult(boolean isOK, String problem) {
        this.isOK = isOK;
        this.problem = problem;
    }

    public boolean isOK() {
        return isOK;
    }

    public String getProblem() {
        return problem;
    }

    public static MyResult ok() {
        return new MyResult(true, "OK");
    }

    public static MyResult fail(String problem) {
        return new MyResult(false, problem == null ? "Unknown problem" : problem);
    }

    @Override
    public String toString() {
        return "Result { isOK = " + isOK + ", problem = ' " + problem + " ' }";
    }
}
