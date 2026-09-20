package gold.debug.samples.tasks;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.*;
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        var app = new SpringApplication(Application.class);
        app.setDefaultProperties(Map.of("server.port", System.getenv().getOrDefault("PORT", "18101"),
                                        "server.address", System.getenv().getOrDefault("HOST", "127.0.0.1")));
        app.run(args);
    }
    @Bean
    WebMvcConfigurer cors() {
        return new WebMvcConfigurer() {
            public void addCorsMappings(CorsRegistry r) {
                r.addMapping("/**")
                    .allowedOrigins(System.getenv().getOrDefault("WEB_ORIGIN", "http://127.0.0.1:18100"))
                    .exposedHeaders("X-Sample-Protocol")
                    .allowedMethods("GET", "POST", "PATCH", "OPTIONS");
            }
        };
    }
    @Bean
    jakarta.servlet.Filter protocol() {
        return (request, response, chain) -> {
            ((jakarta.servlet.http.HttpServletResponse)response).setHeader("X-Sample-Protocol", "2");
            chain.doFilter(request, response);
        };
    }
}
