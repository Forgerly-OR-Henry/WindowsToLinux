require 'socket'
module Fixture
  class HttpServer
    def initialize(configuration, router)
      @configuration, @router = configuration, router
    end
    def run
      server = TCPServer.new('0.0.0.0', @configuration.port)
      loop do
        client = server.accept
        begin
          request = client.gets || ''
          while (line = client.gets) && line != "\r\n"; end
          target = request.split(' ')[1] || '/'
          path, query = target.split('?', 2)
          status, headers, chunks = @router.route(path, query)
          reason = status == 200 ? 'OK' : status == 503 ? 'Service Unavailable' : 'Bad Request'
          client.write("HTTP/1.1 #{status} #{reason}\r\n")
          headers.each { |key, value| client.write("#{key}: #{value}\r\n") }
          client.write("Connection: close\r\n\r\n")
          chunks.each { |chunk| client.write(chunk) }
        rescue IOError, SystemCallError
          # A disconnected probe does not stop the service.
        ensure
          client.close
        end
      end
    ensure
      server&.close
    end
  end
end
