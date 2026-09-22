package acceptance;

import java.net.InetSocketAddress;

import acceptance.config.FixtureConfiguration;
import acceptance.http.FixtureHandler;
import com.sun.net.httpserver.HttpServer;

public final class Main {
    public static void main(String[] args) throws Exception {
        FixtureConfiguration configuration = FixtureConfiguration.load();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", configuration.port()), 16);
        server.createContext("/", new FixtureHandler(configuration));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
    }
}
