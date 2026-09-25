require 'uri'
require_relative 'summary_service'
module Fixture
  class Router
    def initialize(configuration)
      @configuration = configuration
    end

    def route(path, query)
      status, body, type = @configuration.status, @configuration.label, 'text/plain; charset=utf-8'
      if status != 503 && (path == '/api/summary' || @configuration.mode == 'json')
        begin
          pair = URI.decode_www_form(query || '').find { |key, _| key == 'values' }
          body = SummaryService.new.summarize(path == '/api/summary' && pair ? pair[1] : nil).to_json
          type = 'application/json; charset=utf-8'
        rescue ArgumentError
          status, body = 400, 'invalid-values'
        end
      end
      [status, { 'content-type' => type, 'content-length' => body.bytesize.to_s }, [body]]
    end
  end
end
