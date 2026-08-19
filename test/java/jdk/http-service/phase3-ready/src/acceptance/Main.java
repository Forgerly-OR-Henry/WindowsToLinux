package acceptance;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class Main {
    private static final int STATUS_CODE = 200;
    private static final String MARKER = "phase3-live-ok";

    private Main() { }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv("PORT"));
        try (ServerSocket server = new ServerSocket(port)) {
            while (true) {
                try (Socket socket = server.accept()) {
                    BufferedReader input = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) { }
                    byte[] body = MARKER.getBytes(StandardCharsets.UTF_8);
                    String reason = STATUS_CODE == 200 ? "OK" : "Service Unavailable";
                    byte[] headers = ("HTTP/1.1 " + STATUS_CODE + " " + reason + "\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                    OutputStream output = socket.getOutputStream();
                    output.write(headers);
                    output.write(body);
                }
            }
        }
    }
}
