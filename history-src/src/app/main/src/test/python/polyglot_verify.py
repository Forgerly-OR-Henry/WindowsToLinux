"""One isolated runtime-data directory and evidence report per acceptance run."""

import uuid
from polyglot_business_verify import verify_business


def verify(runner, slug):
    runner.evidence = runner.work / "verification" / uuid.uuid4().hex
    runner.evidence.mkdir(parents=True)
    runner.data = runner.evidence / "运行 数据"
    return verify_business(runner, slug)
