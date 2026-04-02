package gold.debug.wintolin.exceptionanderror;

/**
 * 自定义业务异常：
 * 支持记录错误信息、错误类、错误方法、错误码以及原始异常原因。
 */
public class MyException extends RuntimeException {

    /**
     * 默认错误码
     */
    public static final String ERROR_DEFAULT_CODE = "ERROR_DEFAULT_CODE";

    /**
     * 产生异常的类
     */
    private final Class<?> sourceClass;

    /**
     * 产生异常的方法名
     */
    private final String sourceMethod;

    /**
     * 错误信息
     */
    private final String message;

    /**
     * 错误码
     */
    private final String errorCode;

    /**
     * 最完整构造器
     *
     * @param sourceClass  产生异常的类
     * @param sourceMethod 产生异常的方法名
     * @param errorCode    错误码
     * @param message      错误信息
     * @param cause        原始异常
     */
    public MyException(Class<?> sourceClass, String sourceMethod, String errorCode, String message, Throwable cause) {
        super(message == null ? "Unknown problem" : message, cause);
        this.sourceClass = sourceClass;
        this.sourceMethod = sourceMethod;
        this.errorCode = errorCode == null || errorCode.isBlank() ? ERROR_DEFAULT_CODE : errorCode;
        this.message = message == null ? "Unknown problem" : message;
    }

    /**
     * 不带 cause 的完整构造器
     */
    public MyException(Class<?> sourceClass, String sourceMethod, String errorCode, String message) {
        this(sourceClass, sourceMethod, errorCode, message, null);
    }

    /**
     * 不带错误码，使用默认错误码
     */
    public MyException(Class<?> sourceClass, String sourceMethod, String message, Throwable cause) {
        this(sourceClass, sourceMethod, ERROR_DEFAULT_CODE, message, cause);
    }

    /**
     * 不带错误码，也不带 cause
     */
    public MyException(Class<?> sourceClass, String sourceMethod, String message) {
        this(sourceClass, sourceMethod, ERROR_DEFAULT_CODE, message, null);
    }

    /**
     * 获取错误信息
     */
    @Override
    public String getMessage() {
        return message;
    }

    /**
     * 获取产生异常的类对象
     */
    public Class<?> getSourceClass() {
        return sourceClass;
    }

    /**
     * 获取产生异常的完整类名
     */
    public String getSourceClassName() {
        return sourceClass == null ? "UnknownClass" : sourceClass.getName();
    }

    /**
     * 获取产生异常的简单类名
     */
    public String getSourceSimpleClassName() {
        return sourceClass == null ? "UnknownClass" : sourceClass.getSimpleName();
    }

    /**
     * 获取产生异常的方法名
     */
    public String getSourceMethod() {
        return sourceMethod == null ? "UnknownMethod" : sourceMethod;
    }

    /**
     * 获取错误码
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * 获取格式化后的简要异常信息
     */
    public String getFormattedMessage() {
        return "[errorCode=" + errorCode +
                ", class=" + getSourceClassName() +
                ", method=" + getSourceMethod() +
                "] " + message;
    }

    /**
     * 快速抛出：完整参数
     */
    public static void fail(Class<?> sourceClass, String sourceMethod, String errorCode, String message, Throwable cause) {
        throw new MyException(sourceClass, sourceMethod, errorCode, message, cause);
    }

    /**
     * 快速抛出：不带 cause
     */
    public static void fail(Class<?> sourceClass, String sourceMethod, String errorCode, String message) {
        throw new MyException(sourceClass, sourceMethod, errorCode, message);
    }

    /**
     * 快速抛出：使用默认错误码，带 cause
     */
    public static void fail(Class<?> sourceClass, String sourceMethod, String message, Throwable cause) {
        throw new MyException(sourceClass, sourceMethod, ERROR_DEFAULT_CODE, message, cause);
    }

    /**
     * 快速抛出：使用默认错误码，不带 cause
     */
    public static void fail(Class<?> sourceClass, String sourceMethod, String message) {
        throw new MyException(sourceClass, sourceMethod, ERROR_DEFAULT_CODE, message);
    }

    @Override
    public String toString() {
        return "MyException{" +
                "errorCode='" + errorCode + '\'' +
                ", sourceClass=" + getSourceClassName() +
                ", sourceMethod='" + getSourceMethod() + '\'' +
                ", message='" + message + '\'' +
                ", cause=" + (getCause() == null ? "null" : getCause().getClass().getName()) +
                '}';
    }
}