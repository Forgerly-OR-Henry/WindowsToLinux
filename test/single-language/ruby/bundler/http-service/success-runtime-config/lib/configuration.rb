module Fixture
  class Configuration
    attr_reader :mode, :status, :label
    def initialize
      @mode = 'config'
      @status = 200
      @label = @mode == 'config' ? ENV.fetch('FIXTURE_LABEL', 'runtime-config-default') : 'deployment-smoke-ok'
    end
    def port
      raw = ENV.fetch('PORT', '')
      raise ArgumentError, 'Invalid PORT' unless raw.match?(/\A[0-9]+\z/) && (1..65535).cover?(raw.to_i)
      raw.to_i
    end
  end
end
