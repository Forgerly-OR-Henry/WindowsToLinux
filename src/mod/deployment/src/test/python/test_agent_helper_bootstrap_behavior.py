"""Exercise the fixed helper-runtime profile with local package-manager and OS-file seams."""

import contextlib
import io
import json
import pathlib
import re
import types
import unittest
from unittest import mock


SOURCE = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/service/agent/toolchain'
INSTALLER = pathlib.Path(__file__).resolve().parents[2] / 'main/java/gold/debug/windowstolinux/mod/deployment/linux/helper/DeploymentHelperInstallerScript.java'
OPERATION = '0123456789abcdef0123456789abcdef'


def dependencies():
    table = json.loads((SOURCE / 'dependency-packages.json').read_text(encoding='utf-8'))
    module = types.ModuleType('helper_bootstrap_fixture')
    module.DEPENDENCY_PACKAGES = table
    exec(compile((SOURCE / 'agent-toolchain-dependencies.py').read_text(encoding='utf-8'), '<fixed-dependencies>', 'exec'), module.__dict__)
    return module, table


class AgentHelperBootstrapBehaviorTest(unittest.TestCase):
    def setUp(self):
        self.d, self.table = dependencies()
        self.commands = []
        self.profile = {'id': 'ubuntu', 'version': '24.04', 'manager': 'APT'}
        self.effective_apt = ''

    def command(self, command):
        self.commands.append(command)
        if command[0].endswith('/apt-config'):
            return self.effective_apt
        if command[0].endswith('/dpkg-query'):
            return ''.join(name + '\t1.0-1\n' for name in self.table['APT']['HELPER_RUNTIME'])
        if command[0].endswith('/rpm'):
            if '-qf' in command:
                return 'centos-gpg-keys\n'
            if '-Vf' in command:
                return ''
            if '-q' in command:
                profile = 'NATIVE_BUILD' if 'cmake' in command else 'HELPER_RUNTIME'
                return ''.join(name + '\t1:1.0-1.x86_64\n' for name in self.table['DNF'][profile])
        return ''

    @contextlib.contextmanager
    def os_files(self, files):
        with mock.patch.object(self.d.os, 'geteuid', return_value=0, create=True), \
             mock.patch.object(self.d, 'dependency_profile', lambda: self.profile), \
             mock.patch.object(self.d, 'dependency_protected', lambda path: pathlib.Path(path)), \
             mock.patch.object(self.d, 'dependency_text', lambda path: files[pathlib.Path(path).as_posix()]), \
             mock.patch.object(self.d, 'dependency_bytes', return_value=b'controlled official key'), \
             mock.patch.object(self.d.pathlib.Path, 'exists', lambda path: path.as_posix() in files), \
             mock.patch.object(self.d.pathlib.Path, 'is_symlink', return_value=False), \
             mock.patch.object(self.d.pathlib.Path, 'is_dir', return_value=False), \
             mock.patch.object(self.d.pathlib.Path, 'iterdir', lambda path: iter(pathlib.Path(name) for name in files if pathlib.Path(name).parent == path)), \
             mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name), \
             mock.patch.object(self.d, 'dependency_run', self.command):
            yield

    def execute(self, files, profile='HELPER_RUNTIME'):
        stream = io.StringIO()
        with self.os_files(files), contextlib.redirect_stdout(stream):
            result = self.d.dependency_cli([profile, OPERATION])
        return result, stream.getvalue()

    def assert_no_transaction(self):
        self.assertFalse(any(command[0].endswith(('/apt-get', '/dnf')) for command in self.commands), self.commands)

    def test_profile_uses_exact_installer_packages_and_rejects_model_package_profiles(self):
        source = INSTALLER.read_text(encoding='utf-8')
        for manager in ('APT', 'DNF'):
            expected = re.search(r'public static final String ' + manager + r'_PACKAGES = "([^"]+)";', source)[1].split()
            self.assertEqual(expected, self.d.dependency_packages(manager, 'HELPER_RUNTIME'))
        with mock.patch.object(self.d.os, 'geteuid', return_value=0, create=True):
            for args in (['HELPER_RUNTIME', OPERATION, 'model-package'], ['MODEL_RUNTIME', OPERATION],
                         ['HELPER_RUNTIME', '../operation'], ['HELPER_RUNTIME', 'A' * 32]):
                with self.subTest(args=args), self.assertRaises(self.d.DependencyFailure):
                    self.d.dependency_main(args)
        self.assertEqual([], self.commands)

    def test_dangerous_apt_configuration_source_key_and_binary_are_rejected_before_transactions(self):
        base = {'/etc/apt/apt.conf': '', '/etc/apt/sources.list': 'deb https://archive.ubuntu.com/ubuntu noble main\n'}
        cases = [({'/etc/apt/apt.conf': 'APT::Get::AllowUnauthenticated "true";'}, ''),
                 ({'/etc/apt/apt.conf': 'DPkg::Options {"--force-all";};'}, ''),
                 ({'/etc/apt/sources.list': 'deb https://attacker.example/ubuntu noble main\n'}, ''),
                 ({'/etc/apt/sources.list': 'deb [signed-by=/project/private-key] https://archive.ubuntu.com/ubuntu noble main\n'}, ''),
                 ({'/etc/dpkg/dpkg.cfg': 'force-unsafe-io\n'}, ''),
                 ({}, 'Dir::Bin::dpkg "/project/private-executable";')]
        for changes, effective in cases:
            with self.subTest(changes=changes, effective=effective):
                self.commands.clear()
                self.effective_apt = effective
                result, receipt = self.execute({**base, **changes})
                self.assertEqual(65, result, receipt)
                self.assert_no_transaction()
                self.assertNotIn('private-key', receipt)
                self.assertNotIn('private-executable', receipt)

    def test_guarded_apt_transaction_forces_auth_and_emits_real_operation_receipt(self):
        files = {'/etc/apt/apt.conf': '', '/etc/apt/sources.list': 'deb https://archive.ubuntu.com/ubuntu noble main\n'}
        result, receipt = self.execute(files)
        self.assertEqual(0, result, receipt)
        self.assertEqual(1, len(receipt.splitlines()))
        value = json.loads(receipt.removeprefix('TOOLCHAIN_DEPENDENCIES='))
        self.assertEqual(OPERATION, value['operation'])
        self.assertEqual('HELPER_RUNTIME', value['ecosystem'])
        self.assertEqual(set(self.table['APT']['HELPER_RUNTIME']), set(value['packages']))
        transactions = [command for command in self.commands if command[0].endswith('/apt-get')]
        self.assertEqual(2, len(transactions))
        self.assertIn('update', transactions[0])
        self.assertEqual(self.table['APT']['HELPER_RUNTIME'], transactions[1][-13:])
        for command in transactions:
            self.assertIn('APT::Get::AllowUnauthenticated=false', command)
            self.assertIn('Acquire::Check-Valid-Until=true', command)
            self.assertIn('Acquire::AllowInsecureRepositories=false', command)

    def test_dangerous_dnf_configuration_source_and_key_are_rejected_before_transactions(self):
        self.profile = {'id': 'centos', 'version': '9', 'manager': 'DNF'}
        repo = '[baseos]\nbaseurl=https://mirror.stream.centos.org/BaseOS/\ngpgcheck=1\ngpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-centosofficial\n'
        for changes in ({'/etc/dnf/dnf.conf': '[main]\ngpgcheck=0\n'},
                        {'/etc/dnf/dnf.conf': '[main]\nreposdir=/project\n'},
                        {'/etc/yum.repos.d/base.repo': repo.replace('gpgcheck=1', 'gpgcheck=0')},
                        {'/etc/yum.repos.d/base.repo': repo.replace('mirror.stream.centos.org', 'attacker.example')},
                        {'/etc/yum.repos.d/base.repo': repo.replace('/etc/pki/rpm-gpg/RPM-GPG-KEY-centosofficial', '/project/private-key')}):
            with self.subTest(changes=changes):
                self.commands.clear()
                result, receipt = self.execute({'/etc/dnf/dnf.conf': '[main]\ngpgcheck=1\n', '/etc/yum.repos.d/base.repo': repo, **changes})
                self.assertEqual(65, result, receipt)
                self.assert_no_transaction()
                self.assertNotIn('private-key', receipt)

    def test_helper_dnf_does_not_enable_crb_and_keeps_authenticated_active_repository_checks(self):
        self.profile = {'id': 'centos', 'version': '9', 'manager': 'DNF'}
        base = '[baseos]\nbaseurl=https://mirror.stream.centos.org/BaseOS/\ngpgcheck=1\ngpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-centosofficial\n'
        disabled = '[crb]\nenabled=0\ngpgcheck=1\nbaseurl=https://inactive.example/\n'
        files = {'/etc/yum.repos.d/base.repo': base, '/etc/yum.repos.d/crb.repo': disabled}
        result, receipt = self.execute(files)
        self.assertEqual(0, result, receipt)
        transaction = next(command for command in self.commands if command[0].endswith('/dnf'))
        self.assertNotIn('--enablerepo=crb', transaction)
        self.assertIn('--disableplugin=*', transaction)
        self.assertIn('--setopt=gpgcheck=1', transaction)
        self.assertIn('--setopt=localpkg_gpgcheck=1', transaction)
        self.assertIn('--setopt=sslverify=1', transaction)
        self.assertEqual(self.table['DNF']['HELPER_RUNTIME'], transaction[-13:])
        self.d.OS_PROFILE = self.profile
        with self.assertRaises(self.d.DependencyFailure):
            self.d.dependency_dnf_config(disabled, True)

    def test_existing_native_build_profile_still_enables_crb(self):
        self.profile = {'id': 'centos', 'version': '9', 'manager': 'DNF'}
        with self.os_files({}), mock.patch.object(self.d, 'dependency_validate_dnf') as checked, contextlib.redirect_stdout(io.StringIO()):
            self.d.dependency_main(['NATIVE_BUILD', OPERATION])
        checked.assert_called_once_with()
        transaction = next(command for command in self.commands if command[0].endswith('/dnf'))
        self.assertIn('--enablerepo=crb', transaction)
        self.assertEqual(self.table['DNF']['NATIVE_BUILD'], transaction[-4:])


if __name__ == '__main__':
    unittest.main()
