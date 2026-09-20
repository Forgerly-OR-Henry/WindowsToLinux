module Scoring
  def self.calculate(payload)
    raise ArgumentError, 'protocolVersion must be 2' unless payload['protocolVersion'] == 2
    revision = payload['revisionId']
    questions = payload.dig('content', 'questions')
    answers = payload['answers']
    raise ArgumentError, 'invalid revision, questions or answers' unless revision.is_a?(Integer) && revision.positive? && questions.is_a?(Array) && (1..30).cover?(questions.size) && answers.is_a?(Hash)
    raise ArgumentError, 'unknown answer' unless (answers.keys - questions.map { |q| q['id'] }).empty?
    parts = []; dimensions = {}; hidden = []
    questions.each do |q|
      id = q['id']; rule = q.fetch('rule'); condition = q['visibleWhen']
      if condition && answers[condition['question']] != condition['equals']
        hidden << id
        next
      end
      answer = answers[id]
      missing = answer.nil? || answer == []
      raise ArgumentError, "#{id}: required answer missing" if q['required'] && missing
      weight = rule['weight']; dimension = rule['dimension']
      raise ArgumentError, "#{id}: invalid weight or dimension" unless weight.is_a?(Integer) && (1..10).cover?(weight) && dimension.is_a?(String) && !dimension.empty?
      raw = 0; maximum = 0; explanation = ''
      case q['type']
      when 'scale'
        minimum = q['min']; maximum_value = q['max']
        raise ArgumentError, "#{id}: invalid scale" unless minimum.is_a?(Integer) && maximum_value.is_a?(Integer) && minimum >= 0 && maximum_value <= 100 && minimum < maximum_value
        raise ArgumentError, "#{id}: answer outside scale" unless missing || (answer.is_a?(Integer) && (minimum..maximum_value).cover?(answer))
        maximum = maximum_value - minimum
        raw = rule['reverse'] ? maximum_value - answer : answer - minimum unless missing
        explanation = missing ? '未填写可选题，计 0 分' : (rule['reverse'] ? "反向计分 #{maximum_value} - #{answer}" : "量表计分 #{answer} - #{minimum}")
      when 'single', 'multi'
        options = q['options']
        raise ArgumentError, "#{id}: invalid options" unless options.is_a?(Array) && (1..10).cover?(options.size)
        scores = options.to_h { |o| [o.fetch('value'), o.fetch('score')] }
        raise ArgumentError, "#{id}: invalid option scores" unless scores.size == options.size && scores.values.all? { |s| s.is_a?(Integer) && (0..100).cover?(s) }
        selected = missing ? [] : (q['type'] == 'single' ? [answer] : answer)
        raise ArgumentError, "#{id}: invalid selection" unless selected.is_a?(Array) && selected.uniq == selected && selected.all? { |s| s.is_a?(String) && scores.key?(s) }
        maximum = q['type'] == 'single' ? scores.values.max : scores.values.sum
        raw = selected.sum { |s| scores.fetch(s) }
        raw = maximum - raw if rule['reverse'] && !missing
        explanation = missing ? '未填写可选题，计 0 分' : "#{rule['reverse'] ? '反向选项' : '选项'} #{selected.join(', ')} => #{raw}"
      else
        raise ArgumentError, "#{id}: unknown question type"
      end
      raise ArgumentError, "#{id}: zero maximum" unless maximum.positive?
      weighted = raw * weight; ceiling = maximum * weight
      parts << { 'id' => id, 'dimension' => dimension, 'raw' => raw, 'weight' => weight, 'weighted' => weighted, 'maximum' => ceiling, 'explanation' => "#{explanation}，权重 #{weight}" }
      bucket = (dimensions[dimension] ||= { 'weighted' => 0, 'maximum' => 0 })
      bucket['weighted'] += weighted; bucket['maximum'] += ceiling
    end
    dimensions.each_value { |d| d['score'] = (d['weighted'] * 100.0 / d['maximum']).round(2) }
    total = parts.sum { |p| p['weighted'] }; maximum = parts.sum { |p| p['maximum'] }
    { 'protocolVersion' => 2, 'component' => 'ruby-scoring', 'revisionId' => revision, 'score' => maximum.zero? ? 0 : (total * 100.0 / maximum).round(2), 'weightedTotal' => total, 'maximum' => maximum, 'parts' => parts, 'dimensions' => dimensions, 'hidden' => hidden }
  end
end
