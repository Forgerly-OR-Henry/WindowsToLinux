"""Exercise release retention with real temporary releases and failed inventory. / 验证实际版本保留和目录枚举失败。"""

import pathlib
import tempfile
import unittest

from test_managed_storage_behavior import LINUX, invoke


BODY = r'''
manifest=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
root="$(app_root demo)"
mkdir -p "$root/releases"
for index in 1 2 3 4 5; do
    digest="$(printf '%064x' "$index")"
    release="$root/releases/$digest"
    mkdir -p "$release"
    printf '%s' "$manifest" > "$release/.windowstolinux-owner"
    touch -d "2000-01-01 00:00:0$index" "$release"
done
ln -s "releases/$(printf '%064x' 1)" "$root/current"
remove_container_secret_delivery() { printf '%s\n' "$2" >> "$fixture/removed-credentials"; }
if [ "$1" = unavailable ]; then
    find() { command find "$@"; return 1; }
fi
retain_releases demo "$manifest"
'''


class ReleaseRetentionBehaviorTest(unittest.TestCase):
    def test_current_and_two_recent_old_releases_are_retained(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            result = invoke(root, BODY, (str(LINUX / 'runtime/systemd/60-lifecycle.sh'),), ('valid',))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            releases = root / 'apps/demo/releases'
            self.assertEqual({f'{index:064x}' for index in (1, 4, 5)}, {path.name for path in releases.iterdir()})
            self.assertEqual({f'{index:064x}' for index in (2, 3)},
                             set((root / 'removed-credentials').read_text().splitlines()))
            self.assertIn('RETAINED=3\n', result.stdout)

    def test_failed_inventory_cannot_delete_releases_or_report_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            result = invoke(root, BODY, (str(LINUX / 'runtime/systemd/60-lifecycle.sh'),), ('unavailable',))
            self.assertEqual(64, result.returncode, result.stdout + result.stderr)
            self.assertNotIn('RETAINED=', result.stdout)
            self.assertEqual(5, len(list((root / 'apps/demo/releases').iterdir())))
            self.assertFalse((root / 'removed-credentials').exists())
