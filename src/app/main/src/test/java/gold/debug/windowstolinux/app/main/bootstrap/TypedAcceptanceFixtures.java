package gold.debug.windowstolinux.app.main.bootstrap;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Creates dependency-free deterministic sources for live typed deployment acceptance. / 为类型化部署实时验收创建无依赖的确定性源码。 */
final class TypedAcceptanceFixtures {
    private static final String PYTHON_IMAGE_DIGEST =
            "sha256:6d43704baacd1bfbe7c295d7f13079d5d8104ed33568873133f8fc69980419df";

    private TypedAcceptanceFixtures() { }

    static Path javaJar(Path parent, String applicationId) throws IOException {
        return javaJar(parent, applicationId, "java-live-ok", true);
    }

    static Path javaJar(Path parent, String applicationId, String marker, boolean healthy) throws IOException {
        Path root = directory(parent, applicationId);
        Path source = root.resolve("src/acceptance/Probe.java");
        String program = healthy ? """
                package acceptance;
                import java.io.*;
                import java.net.*;
                import java.nio.charset.StandardCharsets;
                public final class Probe {
                    public static void main(String[] args) throws Exception {
                        int port = Integer.parseInt(System.getenv("PORT"));
                        try (ServerSocket server = new ServerSocket(port)) {
                            while (true) {
                                try (Socket socket = server.accept()) {
                                    try {
                                        socket.setSoTimeout(3000);
                                        InputStream input = socket.getInputStream();
                                        int state = 0;
                                        while (state < 4) {
                                            int value = input.read();
                                            if (value < 0) break;
                                            state = switch (state) {
                                                case 0 -> value == '\\r' ? 1 : 0;
                                                case 1 -> value == '\\n' ? 2 : 0;
                                                case 2 -> value == '\\r' ? 3 : 0;
                                                default -> value == '\\n' ? 4 : 0;
                                            };
                                        }
                                        byte[] body = "%s".getBytes(StandardCharsets.UTF_8);
                                        String head = "HTTP/1.1 200 OK\\r\\nContent-Length: " + body.length
                                                + "\\r\\nConnection: close\\r\\n\\r\\n";
                                        socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
                                        socket.getOutputStream().write(body);
                                        socket.getOutputStream().flush();
                                    } catch (IOException ignored) {
                                        // A disconnected probe must not terminate the managed process. / 已断开的探测不得终止受管进程。
                                    }
                                }
                            }
                        }
                    }
                }
                """.formatted(marker) : """
                package acceptance;
                public final class Probe {
                    public static void main(String[] args) {
                        System.exit(36);
                    }
                }
                """;
        write(source, program);
        Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-d", classes.toString(), source.toString());
        if (compiled != 0) throw new IOException("acceptance Java fixture compilation failed: " + compiled);
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, "acceptance.Probe");
        attributes.putValue("Build-Jdk-Spec", "21");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(root.resolve("app.jar")), manifest)) {
            jar.putNextEntry(new JarEntry("acceptance/Probe.class"));
            jar.write(Files.readAllBytes(classes.resolve("acceptance/Probe.class")));
            jar.closeEntry();
        }
        return root;
    }

    static Path node(Path parent, String applicationId, boolean healthy, String marker) throws IOException {
        Path root = directory(parent, applicationId);
        write(root.resolve("package.json"), """
                {
                  "name": "%s",
                  "version": "1.0.0",
                  "private": true,
                  "engines": { "node": "18" },
                  "scripts": { "build": "node build.js", "start": "node server.js" }
                }
                """.formatted(applicationId));
        write(root.resolve("package-lock.json"), """
                {
                  "name": "%s",
                  "version": "1.0.0",
                  "lockfileVersion": 3,
                  "requires": true,
                  "packages": {
                    "": { "name": "%s", "version": "1.0.0", "engines": { "node": "18" } }
                  }
                }
                """.formatted(applicationId, applicationId));
        write(root.resolve("build.js"), """
                const fs = require('fs');
                if (process.env.BUILD_LABEL !== 'bounded-build') process.exit(31);
                fs.writeFileSync('build.marker', 'built');
                """);
        String server = healthy ? """
                const crypto = require('crypto');
                const fs = require('fs');
                const http = require('http');
                if (process.env.BUILD_LABEL) process.exit(32);
                const secretPath = process.env.WINDOWSTOLINUX_SECRET_DEPLOYMENT_PROBE_FILE;
                const digest = crypto.createHash('sha256').update(fs.readFileSync(secretPath)).digest('hex');
                if (digest !== process.env.EXPECTED_SECRET_SHA256) process.exit(33);
                http.createServer((request, response) => {
                  response.writeHead(200, {'content-type': 'text/plain'});
                  response.end('%s');
                }).listen(Number(process.env.PORT), '0.0.0.0');
                """.formatted(marker) : "process.exit(34);\n";
        write(root.resolve("server.js"), server);
        return root;
    }

    static Path python(Path parent, String applicationId) throws IOException {
        Path root = directory(parent, applicationId);
        write(root.resolve("pyproject.toml"), """
                [project]
                name = "%s"
                version = "1.0.0"
                requires-python = "==3.12"
                """.formatted(applicationId));
        write(root.resolve("requirements.lock"), "");
        write(root.resolve("demo/__init__.py"), "");
        write(root.resolve("demo/__main__.py"), """
                import http.server
                import os

                class Handler(http.server.BaseHTTPRequestHandler):
                    def do_GET(self):
                        body = b"python-live-ok"
                        self.send_response(200)
                        self.send_header("Content-Length", str(len(body)))
                        self.end_headers()
                        self.wfile.write(body)
                    def log_message(self, format, *args):
                        pass

                http.server.ThreadingHTTPServer(("0.0.0.0", int(os.environ["PORT"])), Handler).serve_forever()
                """);
        return root;
    }

    static Path staticSite(Path parent, String applicationId) throws IOException {
        Path root = directory(parent, applicationId);
        write(root.resolve("index.html"), "<!doctype html><title>review source</title>\n");
        write(root.resolve("public/index.html"), "<!doctype html><title>static-live-ok</title>static-live-ok\n");
        return root;
    }

    static Path container(Path parent, String applicationId, boolean healthy, String marker) throws IOException {
        Path root = directory(parent, applicationId);
        write(root.resolve("Dockerfile"), """
                FROM python:3.12-alpine@%s
                WORKDIR /app
                COPY server.py /app/server.py
                CMD ["python", "/app/server.py"]
                """.formatted(PYTHON_IMAGE_DIGEST));
        String server = healthy ? """
                import http.server
                import os
                class Handler(http.server.BaseHTTPRequestHandler):
                    def do_GET(self):
                        body = b"%s"
                        self.send_response(200)
                        self.send_header("Content-Length", str(len(body)))
                        self.end_headers()
                        self.wfile.write(body)
                    def log_message(self, format, *args):
                        pass
                http.server.ThreadingHTTPServer(("0.0.0.0", int(os.environ["PORT"])), Handler).serve_forever()
                """.formatted(marker) : "raise SystemExit(35)\n";
        write(root.resolve("server.py"), server);
        return root;
    }

    private static Path directory(Path parent, String name) throws IOException {
        Path root = parent.resolve(name);
        Files.createDirectories(root);
        return root;
    }

    private static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }
}
