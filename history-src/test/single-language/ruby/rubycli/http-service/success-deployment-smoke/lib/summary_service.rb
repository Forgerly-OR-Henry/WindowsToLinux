require_relative 'summary'
module Fixture
  class SummaryService
    def summarize(raw)
      tokens = raw.nil? ? ['1', '2', '3'] : raw.split(',', -1)
      raise ArgumentError, 'invalid-values' unless (1..20).cover?(tokens.length)

      items = tokens.map do |token|
        raise ArgumentError, 'invalid-values' unless token.match?(/\A[0-9]{1,10}\z/) && token.to_i <= 10000

        token.to_i
      end
      Summary.new(items)
    end
  end
end
