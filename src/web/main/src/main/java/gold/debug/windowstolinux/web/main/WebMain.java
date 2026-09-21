package gold.debug.windowstolinux.web.main;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts the local Web application with normal startup configuration supplied by YAML.
 * <p>使用 YAML 提供的常规启动配置启动本地 Web 应用。
 */
@SpringBootApplication(scanBasePackages = "gold.debug.windowstolinux.web", proxyBeanMethods = false)
public class WebMain {
    /**
     * Starts the application using its configured composition root.
     * <p>使用已配置的组合根启动应用。
     *
     * @param args args / 参数
     */
    public static void main(String[] args) { SpringApplication.run(WebMain.class, args); }
}
