package acceptance

import acceptance.http.HttpServer
import acceptance.http.Router

fun main() {
    val configuration = Configuration.load()
    HttpServer(configuration.port, Router(configuration)).run()
}
