"""Loads production fragments with their extracted platform resource inserts."""

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/shared/linux/sshd'
INSERTS = {
    '# @compat:apparmor@\n': 'execution/protocol/helper/fragments/workspace/apparmor-namespace.sh',
    '# @compat:systemd-isolation@\n': 'runtime/systemd/helper/systemd-manager-isolation.sh',
    '# @compat:selinux-entry@\n': 'runtime/systemd/helper/selinux-command-entry.sh',
    '# @compat:centos-repositories@\n': 'distro/dnf/centos-source-repositories.py',
}


def read_fragment(path):
    text = path.read_text(encoding='utf-8')
    for marker, resource in INSERTS.items():
        if marker in text:
            text = re.sub(
                r'(?m)^[ \t]*' + re.escape(marker), lambda _: (ROOT / resource).read_text(encoding='utf-8'), text
            )
    return text
