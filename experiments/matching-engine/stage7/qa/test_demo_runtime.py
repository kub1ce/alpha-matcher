"""Focused launcher checks without Java, MySQL or network access."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import demo_runtime as runtime


class LauncherTest(unittest.TestCase):
    def test_status_and_stop_before_first_start(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(runtime, 'S', Path(folder)):
            for action in ('status', 'stop'):
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    runtime.status(action)
                self.assertEqual('NOT_STARTED', json.loads(output.getvalue())['state']['phase'])
                self.assertFalse((Path(folder) / 'runtime').exists())

    def test_start_creates_runtime_without_prior_directory(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(runtime, 'S', Path(folder)), patch.object(runtime, 'Lease'), patch.object(runtime, 'ensure_engine'), patch.object(runtime, 'prepare_mysql', return_value=Path(folder)), patch.object(runtime.subprocess, 'Popen'), patch.object(runtime, 'own_info', return_value={'pid': 27}), contextlib.redirect_stdout(io.StringIO()):
            runtime.start(False)
            self.assertTrue((Path(folder) / 'runtime/ACTIVE.json').is_file())

    def test_stop_rejects_path_outside_owned_runtime(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(runtime, 'S', Path(folder)):
            pointer = Path(folder) / 'runtime/ACTIVE.json'
            pointer.parent.mkdir()
            pointer.write_text(json.dumps({'run': str(Path(folder) / 'elsewhere'), 'owner': {}}))
            with self.assertRaisesRegex(RuntimeError, 'Invalid owned run path'):
                runtime.status('stop')

    def test_missing_jar_uses_accepted_builder(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(runtime, 'M', Path(folder)), patch.object(runtime.subprocess, 'run') as build, patch.object(runtime, 'verify_engine') as verify:
            runtime.ensure_engine()
            build.assert_called_once()
            self.assertTrue(build.call_args.kwargs['check'])
            self.assertIn('build_accepted.py', build.call_args.args[0][-1])
            verify.assert_called_once()

    def test_present_jar_is_verified_without_rebuild(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(runtime, 'M', Path(folder)), patch.object(runtime.subprocess, 'run') as build, patch.object(runtime, 'verify_engine') as verify:
            jar = Path(folder) / 'target/matching-engine-0.1-SNAPSHOT.jar'
            jar.parent.mkdir()
            jar.write_bytes(b'fixture')
            runtime.ensure_engine()
            build.assert_not_called()
            verify.assert_called_once()

    def test_explicit_mysql_override(self):
        with patch.dict(os.environ, {'STAGE7_MYSQL_HOME': 'owned-install'}), patch.object(runtime, 'checked_mysql', return_value=Path('owned-install')) as check:
            self.assertEqual(Path('owned-install'), runtime.prepare_mysql())
            check.assert_called_once_with('owned-install')

    def package(self, filename=None):
        out = io.BytesIO()
        with zipfile.ZipFile(out, 'w') as package:
            package.writestr(filename or runtime.MYSQL_FOLDER + '/bin/mysqld.exe', b'fixture')
        return out.getvalue()

    def prepare(self, folder, payload, expected):
        response = io.BytesIO(payload)
        response.geturl = lambda: runtime.MYSQL_URL
        with patch.object(runtime, 'S', Path(folder)), patch.dict(os.environ, {'STAGE7_MYSQL_HOME': ''}), patch.object(runtime.shutil, 'which', return_value=None), patch.object(runtime, 'MYSQL_SHA256', expected), patch.object(runtime.urllib.request, 'urlopen', return_value=response) as download, patch.object(runtime, 'checked_mysql', side_effect=Path):
            home = runtime.prepare_mysql()
            self.assertTrue((home / 'bin/mysqld.exe').exists())
            self.assertEqual(home, runtime.prepare_mysql())
            download.assert_called_once()

    def test_official_download_checksum_extraction_and_cache(self):
        payload = self.package()
        with tempfile.TemporaryDirectory() as folder:
            self.prepare(folder, payload, hashlib.sha256(payload).hexdigest())

    def test_checksum_mismatch_is_never_extracted(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(RuntimeError, 'SHA256 mismatch'):
                self.prepare(folder, self.package(), '0' * 64)
            self.assertFalse((Path(folder) / 'runtime/dependencies' / runtime.MYSQL_FOLDER).exists())

    def test_archive_traversal_is_rejected(self):
        payload = self.package(runtime.MYSQL_FOLDER + '/../outside.exe')
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(RuntimeError, 'Unsafe MySQL archive'):
                self.prepare(folder, payload, hashlib.sha256(payload).hexdigest())


if __name__ == '__main__':
    unittest.main()
