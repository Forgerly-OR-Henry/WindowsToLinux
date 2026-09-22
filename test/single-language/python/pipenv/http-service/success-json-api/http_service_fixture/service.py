import re
from jsonschema import Draft202012Validator
from .model import Summary

_validator = Draft202012Validator(
    {'type': 'array', 'minItems': 1, 'maxItems': 20, 'items': {'type': 'integer', 'minimum': 0, 'maximum': 10000}}
)


def summarize(raw):
    if raw is None:
        items = [1, 2, 3]
    else:
        tokens = raw.split(',')
        if len(tokens) > 20 or any(not re.fullmatch(r'[0-9]{1,10}', token) for token in tokens):
            raise ValueError('invalid-values')
        items = [int(token) for token in tokens]
    _validator.validate(items)
    return Summary(tuple(items))
