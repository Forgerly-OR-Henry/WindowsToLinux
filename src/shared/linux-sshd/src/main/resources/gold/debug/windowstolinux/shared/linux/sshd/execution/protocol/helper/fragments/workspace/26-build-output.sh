# The root controller counts all output independently of project-owned logs.
monitor_build_output() {
  local candidate="$1" limit="$2"; shift 2
  if [ -e "$candidate/.output-used" ]; then assert_root_owned_regular "$candidate/.output-used"; fi
  /usr/bin/python3 -I - "$candidate" "$limit" "$@" <<'WTL_BUILD_OUTPUT'
import os, signal, subprocess, sys
from pathlib import Path
candidate, limit = Path(sys.argv[1]), int(sys.argv[2])
counter = candidate / '.output-used'
used = int(counter.read_text()) if counter.exists() else 0
if used < 0 or used >= limit:
    print('BUILD_LIMIT=output')
    raise SystemExit(43)
flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW
counter_fd = os.open(counter, flags, 0o600)
process = None
try:
    process = subprocess.Popen(sys.argv[3:], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, start_new_session=True)
    overflow = False
    while True:
        block = os.read(process.stdout.fileno(), min(8192, limit - used + 1))
        if not block:
            break
        accepted = block[:max(0, limit - used)]
        used += len(block)
        os.lseek(counter_fd, 0, os.SEEK_SET)
        os.write(counter_fd, str(used).encode('ascii'))
        os.ftruncate(counter_fd, os.lseek(counter_fd, 0, os.SEEK_CUR))
        if accepted:
            sys.stdout.buffer.write(accepted)
            sys.stdout.buffer.flush()
        if used > limit:
            overflow = True
            break
    if overflow:
        print('\nBUILD_LIMIT=output', flush=True)
        raise SystemExit(43)
    raise SystemExit(process.wait())
finally:
    if process is not None:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
        process.stdout.close()
    os.close(counter_fd)
WTL_BUILD_OUTPUT
}
