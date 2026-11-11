"""Focused launcher checks without Java, MySQL or network access."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import warnings
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

    def test_engine_guards_reject_changed_sources_manifests_and_jar_entries(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(runtime, 'M', Path(folder)):
            module = Path(folder)
            source = module / 'engine.java'
            source.write_bytes(b'accepted source')
            jar = module / 'target/matching-engine-0.1-SNAPSHOT.jar'
            jar.parent.mkdir()
            freeze = {'version': 'stage6-accepted-1ebb240ff4bc',
                      'mainSourceSHA256': {'engine.java': runtime.digest(source)},
                      'jarSHA256': 'historical ZIP SHA'}
            content = {'originalJarSHA256': freeze['jarSHA256'],
                       'entries': {'engine.class': hashlib.sha256(b'accepted class').hexdigest()}}
            cases = ('valid', 'version', 'source', 'manifest', 'changed', 'extra', 'duplicate')
            for case in cases:
                with self.subTest(case=case):
                    current = dict(freeze)
                    expected = dict(content)
                    source.write_bytes(b'changed' if case == 'source' else b'accepted source')
                    if case == 'version': current['version'] = 'unaccepted'
                    if case == 'manifest': expected['originalJarSHA256'] = 'unrelated'
                    with warnings.catch_warnings(), zipfile.ZipFile(jar, 'w') as package:
                        warnings.simplefilter('ignore', UserWarning)
                        package.writestr('engine.class', b'changed' if case == 'changed' else b'accepted class')
                        if case == 'extra': package.writestr('extra.class', b'extra')
                        if case == 'duplicate': package.writestr('engine.class', b'accepted class')
                    with patch.object(runtime, 'read', side_effect=[current, expected]):
                        if case == 'valid': self.assertEqual(current, runtime.verify_engine())
                        else:
                            with self.assertRaises(RuntimeError): runtime.verify_engine()

    def test_run_checks_initialization_schema_and_build_exit_codes(self):
        for phase in ('initialize', 'schema', 'build'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as folder:
                stage = Path(folder)
                root = stage / 'run'
                root.mkdir()
                (stage / 'sql').mkdir()
                (stage / 'sql/001_schema.sql').write_bytes(b'fixture')
                init = Mock(); init.wait.return_value = 7 if phase == 'initialize' else 0
                server = Mock(); server.poll.return_value = None
                build = Mock(); build.wait.return_value = 9
                def spawn(args, log_path, job, env=None):
                    name = log_path.stem
                    if name == 'mysql-initialize':
                        (root / 'mysql-data/auto.cnf').write_text('server-uuid=fixture\n')
                        return init
                    return {'mysql-server': server, 'build-test': build}[name]
                schema = Mock(returncode=8 if phase == 'schema' else 0, stdout=b'', stderr=b'')
                with patch.object(runtime, 'S', stage), patch.object(runtime, 'M', stage), \
                     patch.object(runtime, 'Lease'), patch.object(runtime, 'Job'), \
                     patch.object(runtime, 'own_info', return_value={'pid': 27}), \
                     patch.object(runtime, 'verify_engine'), patch.object(runtime, 'retire', return_value=[]), \
                     patch.object(runtime.socket, 'socket'), \
                     patch.object(runtime, 'checked_mysql', return_value=stage / 'mysql'), \
                     patch.object(runtime, 'start_owned', side_effect=spawn) as start, \
                     patch.object(runtime.subprocess, 'run', return_value=schema):
                    with self.assertRaisesRegex(RuntimeError, {'initialize': 'MySQL initialization', 'schema': 'schema initialization', 'build': 'Build/test failed'}[phase]):
                        runtime.run(root, False, stage / 'mysql')
                    init.wait.assert_called_once_with(timeout=180)
                    if phase == 'build': build.wait.assert_called_once_with(timeout=900)
                    else: build.wait.assert_not_called()
                    self.assertNotIn('application', [call.args[1].stem for call in start.call_args_list])

    def test_job_assignment_failure_stops_real_created_child(self):
        with tempfile.TemporaryDirectory() as folder:
            error = OSError('simulated Job assignment failure')
            job = Mock(); job.add.side_effect = error
            popen = subprocess.Popen
            children = []
            def spawn(*args, **kwargs):
                child = popen(*args, **kwargs); children.append(child); return child
            try:
                with patch.object(runtime.subprocess, 'Popen', side_effect=spawn):
                    with self.assertRaises(OSError) as caught:
                        runtime.start_owned([sys.executable, '-B', '-c', 'import time; time.sleep(60)'], Path(folder) / 'child.log', job)
                self.assertIs(error, caught.exception)
                self.assertEqual(1, len(children))
                self.assertIsNotNone(children[0].poll())
                job.add.assert_called_once_with(children[0])
            finally:
                for child in children:
                    if child.poll() is None: child.kill()
                    child.wait(timeout=10)

    def test_job_assignment_cleanup_retries_and_preserves_original_error(self):
        for failure in (subprocess.TimeoutExpired('child', 10), OSError('terminate failed')):
            with self.subTest(failure=type(failure).__name__), tempfile.TemporaryDirectory() as folder:
                error = OSError('assignment failed')
                job = Mock(); job.add.side_effect = error
                child = Mock(); child.poll.return_value = None
                if isinstance(failure, OSError): child.terminate.side_effect = failure
                else: child.wait.side_effect = [failure, 1]
                with patch.object(runtime.subprocess, 'Popen', return_value=child):
                    with self.assertRaises(OSError) as caught:
                        runtime.start_owned(['fixture'], Path(folder) / 'child.log', job)
                self.assertIs(error, caught.exception)
                child.terminate.assert_called_once()
                child.kill.assert_called_once()
                self.assertEqual(30, child.wait.call_args.kwargs['timeout'])

    def test_job_assignment_preserves_both_errors_if_cleanup_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            error = OSError('assignment failed')
            job = Mock(); job.add.side_effect = error
            child = Mock(); child.poll.return_value = None
            child.terminate.side_effect = OSError('terminate failed')
            child.kill.side_effect = OSError('kill failed')
            with patch.object(runtime.subprocess, 'Popen', return_value=child):
                with self.assertRaises(OSError) as caught:
                    runtime.start_owned(['fixture'], Path(folder) / 'child.log', job)
            self.assertIs(error, caught.exception)
            self.assertIn('kill failed', error.__notes__[0])


if __name__ == '__main__':
    unittest.main()
