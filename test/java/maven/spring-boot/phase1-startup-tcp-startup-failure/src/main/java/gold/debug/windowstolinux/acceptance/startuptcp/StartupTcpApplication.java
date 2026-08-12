package gold.debug.windowstolinux.acceptance.startuptcp;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class StartupTcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(StartupTcpApplication.class, args);
    }

    @Bean
    CommandLineRunner failAfterBootstrapping() {
        return arguments -> {
            throw new IllegalStateException("phase-one startup failure acceptance fixture");
        };
    }
}
