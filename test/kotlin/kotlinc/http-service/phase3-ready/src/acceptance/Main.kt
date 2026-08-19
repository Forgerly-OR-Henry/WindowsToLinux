package acceptance

import java.net.ServerSocket

private const val STATUS_CODE = 200
private const val MARKER = "phase3-live-ok"

fun main() {
    val port = System.getenv("PORT").toInt()
    ServerSocket(port).use { server ->
        while (true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                while (!input.readLine().isNullOrEmpty()) { }
                val body = MARKER.toByteArray(Charsets.UTF_8)
                val reason = if (STATUS_CODE == 200) "OK" else "Service Unavailable"
                val headers = "HTTP/1.1 $STATUS_CODE $reason\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(headers.toByteArray(Charsets.US_ASCII))
                    write(body)
                    flush()
                }
            }
        }
    }
}
