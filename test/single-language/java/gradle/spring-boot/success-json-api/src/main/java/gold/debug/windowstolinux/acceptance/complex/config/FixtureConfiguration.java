package gold.debug.windowstolinux.acceptance.complex.config;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
@Component
public final class FixtureConfiguration {
    private final Environment environment;
    public FixtureConfiguration(Environment environment) { this.environment = environment; }
    public String label() { return mode().equals("config") ? environment.getProperty("FIXTURE_LABEL", "runtime-config-default") : "deployment-smoke-ok"; }
    public static String mode() { return "json"; }
    public static int status() { return 200; }
}
