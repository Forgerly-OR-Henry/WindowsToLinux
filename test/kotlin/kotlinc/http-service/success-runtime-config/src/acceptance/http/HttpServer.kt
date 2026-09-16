package acceptance.http
import java.io.IOException
import java.net.ServerSocket
class HttpServer(private val port: Int, private val router: Router) {
    fun run() {
        ServerSocket(port).use { server ->
            while (true) server.accept().use { socket ->
                try {
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val line = input.readLine() ?: return@use
                    var headerBytes = line.length
                    while (true) {
                        val header = input.readLine() ?: break
                        if (header.isEmpty()) break
                        headerBytes += header.length
                        if (headerBytes > 8192) throw IOException("request headers too large")
                    }
                    val target = line.split(' ').getOrElse(1) { "/" }
                    val response = router.route(target)
                    val body = response.body.toByteArray(Charsets.UTF_8)
                    val reason = if (response.status == 200) "OK" else if (response.status == 503) "Service Unavailable" else "Bad Request"
                    val headers = "HTTP/1.1 ${response.status} $reason\r\nContent-Type: ${response.type}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush()
                    }
                } catch (error: IOException) { /* Disconnected probes do not stop the service. */ }
            }
        }
    }
}
