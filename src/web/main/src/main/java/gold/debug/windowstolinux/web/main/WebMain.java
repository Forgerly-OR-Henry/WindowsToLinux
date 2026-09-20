package gold.debug.windowstolinux.web.main;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Local Phase 5 web application; all normal startup configuration comes from YAML. */
@SpringBootApplication(scanBasePackages = "gold.debug.windowstolinux.web", proxyBeanMethods = false)
public class WebMain {
    public static void main(String[] args) { SpringApplication.run(WebMain.class, args); }
}
