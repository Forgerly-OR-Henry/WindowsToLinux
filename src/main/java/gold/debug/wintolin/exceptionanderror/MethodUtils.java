package gold.debug.wintolin.exceptionanderror;

import java.lang.reflect.Method;

/**
 * 方法工具类。
 *
 * <p><b>开发规范：</b></p>
 * <p>
 * 本类主要用于获取当前业务方法对应的 {@link java.lang.reflect.Method} 对象，
 * 以配合 {@code MyException} 记录异常来源类与来源方法。
 * </p>
 *
 * <p>
 * 在项目开发中，凡是某个方法对应的 {@code Method} 对象是固定的、
 * 且会被重复用于异常抛出、日志定位或统一错误处理时，
 * 应优先在所属类中定义为 <b>private static final</b> 常量，
 * 而不应在业务方法内部重复调用本工具类获取。
 * </p>
 *
 * <p><b>推荐写法：</b></p>
 * <pre>
 * private static final Method METHOD_LINUX_DISTRIBUTION_GET =
 *         MethodUtils.getCurrentMethod(
 *                 TLinux.class,
 *                 "LinuxDistributionGet",
 *                 ALinux.class
 *         );
 * </pre>
 *
 * <p><b>这样做的目的：</b></p>
 * <ul>
 *     <li>避免在方法每次调用时重复通过反射获取 {@code Method} 对象</li>
 *     <li>保证同一方法的元信息仅初始化一次，便于统一管理</li>
 *     <li>使业务方法本体更加简洁，提升可读性与维护性</li>
 *     <li>统一项目中异常定位与方法标识的书写风格</li>
 * </ul>
 *
 * <p>
 * 仅在临时测试代码、一次性方法、或方法签名尚处于频繁变动阶段时，
 * 才允许在方法体内部局部获取 {@code Method} 对象。
 * 正式业务代码默认应遵循“<b>类内静态常量优先</b>”原则。
 * </p>
 */

public final class MethodUtils {

    private static final String ERROR_INTERNAL_METHOD = "ERROR_INTERNAL_METHOD";

    private MethodUtils() {
    }

    public static Method getCurrentMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            return clazz.getDeclaredMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new MyException(
                    clazz,
                    null,
                    "Failed to resolve method object: " + methodName,
                    ERROR_INTERNAL_METHOD,
                    e
            );
        }
    }
}
