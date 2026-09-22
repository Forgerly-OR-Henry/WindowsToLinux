import re
from .model import Summary


def summarize(raw):
    if raw is None:
        items = [1, 2, 3]
    else:
        tokens = raw.split(',')
        if len(tokens) > 20 or any(not re.fullmatch(r'[0-9]{1,10}', token) for token in tokens):
            raise ValueError('invalid-values')
        items = [int(token) for token in tokens]
    if not 1 <= len(items) <= 20 or any(not 0 <= item <= 10000 for item in items):
        raise ValueError('invalid-values')
    return Summary(tuple(items))
