import json
from dataclasses import dataclass


@dataclass(frozen=True)
class Summary:
    items: tuple[int, ...]

    @property
    def total(self):
        return sum(self.items)

    def to_json(self):
        return json.dumps(
            {'status': 'ok', 'items': self.items, 'total': self.total}, ensure_ascii=False, separators=(',', ':')
        ).encode('utf-8')
