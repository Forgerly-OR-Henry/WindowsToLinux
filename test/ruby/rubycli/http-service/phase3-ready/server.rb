require 'socket'

status = 200
marker = 'phase3-live-ok'
server = TCPServer.new('0.0.0.0', Integer(ENV.fetch('PORT')))

loop do
  client = server.accept
  while (line = client.gets)
    break if line == "\r\n"
  end
  reason = status == 200 ? 'OK' : 'Service Unavailable'
  client.write("HTTP/1.1 #{status} #{reason}\r\nContent-Length: #{marker.bytesize}\r\nConnection: close\r\n\r\n#{marker}")
  client.close
end
