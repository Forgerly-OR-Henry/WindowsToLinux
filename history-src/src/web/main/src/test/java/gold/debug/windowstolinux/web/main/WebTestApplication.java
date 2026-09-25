package gold.debug.windowstolinux.web.main;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;

/** Starts the real embedded Spring MVC server against isolated test storage. */
final class WebTestApplication implements AutoCloseable {
    private final ConfigurableApplicationContext context;
    private WebTestApplication(ConfigurableApplicationContext context) {
        this.context = context;
    }

    static WebTestApplication start(Path root, Map<String, Object> changes) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("server.port", 0);
        properties.put("w2l.storage.root", root.resolve("data").toString());
        properties.put("w2l.secrets.directory", root.resolve("keys").toString());
        properties.put("logging.level.root", "ERROR");
        properties.putAll(changes);
        var app = new SpringApplication(WebMain.class, IsolatedGateway.class);
        app.addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test-values", properties)));
        return new WebTestApplication(app.run());
    }

    String origin() {
        return "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port");
    }

    <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    @Override
    public void close() {
        context.close();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedGateway {
        @Bean
        @Primary
        DeploymentLinuxGateway isolatedGateway() {
            return (endpoint, credential, verifier) -> {
                throw new AssertionError("Local Web tests must not contact SSH");
            };
        }
    }
}
