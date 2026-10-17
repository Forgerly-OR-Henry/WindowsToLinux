"""Pure public-resource validation; no observer/producer execution or Linux source access."""
import argparse
import ast
from decimal import Decimal
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import unittest

parser = argparse.ArgumentParser()
parser.add_argument('--selection', type=Path, required=True)
arguments, remaining = parser.parse_known_args()
RESOURCES = Path(__file__).resolve().parents[3] / 'src/mod/deployment/src/test/resources/gold/debug/windowstolinux/mod/deployment/service/standard'


def definitions(name):
    tree = ast.parse((RESOURCES / name).read_bytes(), filename=name)
    names = {'need', 'fields', 'strict_json', 'path', 'integer', 'selection'}
    pure = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names]
    assert len(pure) == len(names)
    constants = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1:
            target = node.targets[0]
            if isinstance(target, ast.Name) and target.id == 'FORMAT': constants['FORMAT'] = ast.literal_eval(node.value)
            if isinstance(target, ast.Tuple) and [value.id for value in target.elts] == ['MAX_INPUT', 'MAX_MANIFEST']:
                constants['MAX_INPUT'] = ast.literal_eval(node.value)[0]
    namespace = dict(constants, json=json, re=re, PurePosixPath=PurePosixPath, Decimal=Decimal, hashlib=hashlib)
    exec(compile(ast.Module(body=pure, type_ignores=[]), name, 'exec'), namespace)
    return namespace, tree


class CurrentBackupPaths(unittest.TestCase):
    def test_actual_selection_literal_dollar_rejections_and_fixed_failure(self):
        raw = arguments.selection.read_bytes()
        self.assertEqual(72427, len(raw), 'Use the frozen actual next-FILES selection, not a synthetic substitute')
        expected = json.loads(raw)
        root = str(PurePosixPath(expected['operationRoot']).parent)
        self.assertEqual(276, len(expected['items']))
        self.assertEqual([24, 26, 28, 29, 30, 31, 32], [index for index, item in enumerate(expected['items']) if '$' in item['source']])
        cases = (
            ('current-pre-reinstall-content-digest.py', 'CONTENT_DIGEST_UNCONFIRMED'),
            ('current-pre-reinstall-backup-files-v2.py', 'FILES_SNAPSHOT_UNCONFIRMED'),
        )
        for name, error in cases:
            with self.subTest(resource=name):
                namespace, tree = definitions(name)
                self.assertEqual(expected, namespace['selection'](raw, root))
                for valid in ('/safe/Outer$Inner.class', '/safe/$literal/Outer$1.class'):
                    self.assertEqual(valid, str(namespace['path'](valid)))
                for invalid in ('/safe/../Outer$Inner.class', '/safe/./Outer$Inner.class', '/safe//Inner.class', '/safe/Inner.class/',
                                '/safe/$(touch x).class', '/safe/${HOME}.class', '/safe/`cmd`.class', '/safe/Inner\x00.class', 'relative$Inner.class'):
                    with self.subTest(invalid=repr(invalid)), self.assertRaises(ValueError): namespace['path'](invalid)
                with self.assertRaises(ValueError): namespace['selection'](raw + b'{}', root)
                with self.assertRaises(ValueError): namespace['selection'](raw, '/different/backups')
                main = next(node for node in tree.body if isinstance(node, ast.If))
                guarded = next(node for node in main.body if isinstance(node, ast.Try))
                self.assertEqual(1, len(guarded.handlers))
                handler = guarded.handlers[0]
                self.assertIsInstance(handler.type, ast.Name)
                self.assertEqual('Exception', handler.type.id)
                self.assertIsNone(handler.name, 'No raw exception/body may become output data')
                output = handler.body[0].value
                self.assertEqual('print', output.func.id)
                self.assertIsInstance(output.args[0], ast.Constant)
                frame = json.loads(output.args[0].value)
                self.assertIs(False, frame['complete'])
                self.assertEqual(error, frame['errorCode'])
                self.assertNotIn('stdout', frame)
                self.assertNotIn('message', frame)
                self.assertEqual(1, handler.body[1].value.args[0].value)


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0], *remaining])
