require 'json'
require 'rack'
require 'webrick'
require_relative 'scoring'
app = proc do |env|
  begin
    if env['PATH_INFO'] == '/healthz'
      status = 200; body = { 'status' => 'ok', 'component' => 'ruby-scoring', 'version' => 2 }
    elsif env['PATH_INFO'] == '/score' && env['REQUEST_METHOD'] == 'POST'
      input = env['rack.input'].read(65537); raise ArgumentError, 'request too large' if input.bytesize > 65536

      status = 200; body = Scoring.calculate(JSON.parse(input))
    else
      status = 404; body = { 'error' => 'not found' }
    end
  rescue ArgumentError, JSON::ParserError => e
    status = 400; body = { 'error' => e.message }
  end
  text = JSON.generate(body);
  [status, { 'Content-Type' => 'application/json; charset=utf-8', 'Content-Length' => text.bytesize.to_s }, [text]]
end
Rack::Handler::WEBrick.run(app, Host: ENV.fetch('HOST', '127.0.0.1'), Port: Integer(ENV.fetch('PORT', '18142')),
                                MaxClients: Integer(ENV.fetch('MAX_CLIENTS', '32')),
                                RequestTimeout: Integer(ENV.fetch('REQUEST_TIMEOUT_SECONDS', '10')), AccessLog: [])
