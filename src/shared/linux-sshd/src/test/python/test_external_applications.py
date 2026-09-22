import contextlib
import importlib.util
import io
from pathlib import Path
import stat
import sys
import types
import unittest
from unittest.mock import patch, mock_open

SOURCE = (
    Path(__file__).parents[2]
    / 'main/resources/gold/debug/windowstolinux/shared/linux/sshd/runtime/external-applications.py'
)
SPEC = importlib.util.spec_from_file_location('external_applications', SOURCE)
APP = importlib.util.module_from_spec(SPEC)
previous_bytecode_policy = sys.dont_write_bytecode
try:
    sys.dont_write_bytecode = True
    SPEC.loader.exec_module(APP)
finally:
    sys.dont_write_bytecode = previous_bytecode_policy
FP = 'a' * 64
CID = 'b' * 64


class ExternalApplicationTest(unittest.TestCase):
    def test_stopped_services_containers_deduplicate_and_report_partial_failures(self):
        def run(args, timeout=8):
            if 'list-unit-files' in args:
                return (
                    'app.service disabled\nalias.service enabled\nfailed.service disabled\ntemplate@.service disabled'
                )
            if 'list-units' in args:
                return 'app.service loaded inactive dead Application'
            self.assertEqual(['docker', 'container', 'ls', '--all', '--quiet', '--no-trunc'], args)
            return CID

        def systemd(unit):
            if unit == 'failed.service':
                raise PermissionError()
            return ('SYSTEMD', 'app.service', FP, 'Application', 'STOPPED', True, True, False)

        out = io.StringIO()
        with (
            patch.object(APP.shutil, 'which', return_value='/usr/bin/tool'),
            patch.object(APP, 'run', side_effect=run),
            patch.object(APP, 'systemd', side_effect=systemd),
            patch.object(APP, 'docker', return_value=('DOCKER', CID, CID, 'Container', 'STOPPED', True, True, False)),
            contextlib.redirect_stdout(out),
        ):
            APP.scan()
        self.assertEqual(2, out.getvalue().count('APP\t'))
        self.assertIn('ISSUE\tSYSTEMD_PERMISSION', out.getvalue())
        self.assertTrue(out.getvalue().endswith('END\t2\n'))

    def test_unit_fingerprint_tracks_original_file_identity_and_contents(self):
        response = 'Id=app.service\nDescription=Application\nActiveState=inactive\nFragmentPath=/etc/systemd/system/app.service\nDropInPaths=\nLoadState=loaded\nNeedDaemonReload=no\nCanStart=yes\nCanStop=yes'
        identity = types.SimpleNamespace(st_mode=stat.S_IFREG, st_size=5, st_ino=10, st_dev=1, st_mtime_ns=1)
        with (
            patch.object(APP, 'run', return_value=response),
            patch.object(APP.os, 'fstat', return_value=identity),
            patch('builtins.open', mock_open(read_data=b'first')),
        ):
            first = APP.systemd('app.service')
        with (
            patch.object(APP, 'run', return_value=response),
            patch.object(APP.os, 'fstat', return_value=identity),
            patch('builtins.open', mock_open(read_data=b'other')),
        ):
            second = APP.systemd('app.service')
        self.assertEqual('STOPPED', first[4])
        self.assertNotEqual(first[2], second[2])

    def test_changed_target_and_managed_marker_prevent_mutation(self):
        out = io.StringIO()
        with (
            patch.object(
                APP, 'systemd', return_value=('SYSTEMD', 'app.service', FP, 'App', 'STOPPED', True, True, False)
            ),
            patch.object(APP, 'run') as run,
            contextlib.redirect_stdout(out),
        ):
            self.assertEqual(3, APP.execute('SYSTEMD', 'app.service', 'c' * 64, 'START'))
            run.assert_not_called()
        self.assertIn('IDENTITY_CHANGED', out.getvalue())
        with (
            patch.object(
                APP, 'systemd', return_value=('SYSTEMD', 'app.service', FP, 'App', 'STOPPED', True, True, True)
            ),
            patch.object(APP, 'run') as run,
            contextlib.redirect_stdout(io.StringIO()),
        ):
            self.assertEqual(3, APP.execute('SYSTEMD', 'app.service', FP, 'START'))
            run.assert_not_called()

    def test_lifecycle_uses_fixed_arguments_and_rechecks_after_action(self):
        before = ('SYSTEMD', 'app.service', FP, 'App', 'STOPPED', True, True, False)
        after = ('SYSTEMD', 'app.service', FP, 'App', 'RUNNING', True, True, False)
        with (
            patch.object(APP, 'systemd', side_effect=[before, after]) as inspect,
            patch.object(APP, 'run') as run,
            patch.object(APP.os, 'geteuid', return_value=1000, create=True),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            self.assertEqual(0, APP.execute('SYSTEMD', 'app.service', FP, 'START'))
            run.assert_called_once_with(
                ['sudo', '-n', 'systemctl', '--no-ask-password', 'start', '--', 'app.service'], 45
            )
            self.assertEqual(2, inspect.call_count)
        for action in ('ENABLE_AUTOSTART', 'rm', 'start; command'):
            with self.assertRaises(ValueError):
                APP.execute('SYSTEMD', 'app.service', FP, action)

    def test_docker_selects_only_named_metadata_and_keeps_empty_label_columns(self):
        raw = (CID + '\t/web\texited\t\t\n').encode()
        completed = types.SimpleNamespace(stdout=raw, stderr=b'', returncode=0)
        with patch.object(APP.subprocess, 'run', return_value=completed) as run:
            result = APP.docker(CID)
            args = run.call_args.args[0]
            self.assertIn('--format', args)
            self.assertNotIn('.Env', str(args))
            self.assertNotIn('.Mounts', str(args))
            self.assertFalse(run.call_args.kwargs.get('shell', False))
            self.assertEqual('STOPPED', result[4])
            self.assertFalse(result[7])

    def test_no_runtime_is_distinct_from_empty_success(self):
        out = io.StringIO()
        with patch.object(APP.shutil, 'which', return_value=None), contextlib.redirect_stdout(out):
            APP.scan()
        self.assertIn('SYSTEMD_UNAVAILABLE', out.getvalue())
        self.assertIn('DOCKER_UNAVAILABLE', out.getvalue())
        self.assertTrue(out.getvalue().endswith('END\t0\n'))


if __name__ == '__main__':
    unittest.main()
