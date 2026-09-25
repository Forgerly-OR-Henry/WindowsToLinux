require 'json'
module Fixture
  class Summary
    def initialize(items)
      @items = items.freeze
    end

    def to_json
      JSON.generate(status: 'ok', items: @items, total: @items.sum)
    end
  end
end
