<?php

return (new PhpCsFixer\Config())
    ->setRiskyAllowed(false)
    ->setUsingCache(false)
    ->setIndent('    ')
    ->setLineEnding("\n")
    ->setRules([
        'array_indentation' => true,
        'binary_operator_spaces' => ['default' => 'single_space'],
        'blank_line_after_namespace' => true,
        'braces_position' => true,
        'cast_spaces' => true,
        'concat_space' => ['spacing' => 'one'],
        'function_declaration' => true,
        'indentation_type' => true,
        'method_argument_space' => ['on_multiline' => 'ensure_fully_multiline'],
        'no_spaces_after_function_name' => true,
        'no_spaces_around_offset' => true,
        'no_trailing_whitespace' => true,
        'no_whitespace_in_blank_line' => true,
        'single_blank_line_at_eof' => true,
        'statement_indentation' => true,
        'type_declaration_spaces' => true,
        'whitespace_after_comma_in_array' => true,
    ]);
