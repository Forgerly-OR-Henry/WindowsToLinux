"""Loads production fragments with their extracted platform resource inserts."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/shared/linux/sshd'
INSERTS = {
    '# @compat:apparmor@\n': 'execution/protocol/helper/apparmor-namespace.sh',
    '# @compat:systemd-isolation@\n': 'runtime/systemd/systemd-manager-isolation.sh',
    '# @compat:selinux-entry@\n': 'runtime/systemd/selinux-command-entry.sh',
    '# @compat:centos-repositories@\n': 'distro/dnf/centos-source-repositories.py',
}

def read_fragment(path):
    text = path.read_text(encoding='utf-8')
    for marker, resource in INSERTS.items():
        if marker in text:
            text = text.replace(marker, (ROOT / resource).read_text(encoding='utf-8'))
    return text
