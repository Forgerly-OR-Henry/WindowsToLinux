import os
import re
from dataclasses import dataclass


@dataclass(frozen=True)
class Configuration:
    port: int
    mode: str
    status: int
    label: str

    @classmethod
    def load(cls):
        raw = os.environ.get('PORT', '')
        if not re.fullmatch(r'[0-9]+', raw) or not 1 <= int(raw) <= 65535:
            raise ValueError('Invalid PORT')
        mode = 'config'
        label = os.environ.get('FIXTURE_LABEL', 'runtime-config-default') if mode == 'config' else 'deployment-smoke-ok'
        return cls(int(raw), mode, 200, label)
