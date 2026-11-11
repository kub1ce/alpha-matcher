"""Accepted builder regression checks; no Maven, javac or real engine rebuild."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

import build_accepted as builder


class AcceptedBuildTest(unittest.TestCase):
    def fixture(self, root):
        module = root / 'engine'
        here = module / 'maintenance'
        here.mkdir(parents=True)
        (module / 'src').mkdir()
        (module / 'src/Main.java').write_bytes(b'accepted source')
        (module / 'pom.xml').write_text('<project/>')
        (module / 'stage4batch/baseline-4b1/src/main/java').mkdir(parents=True)
        (module / 'stage3/corpora').mkdir(parents=True)
        freeze = module / 'stage6/final-acceptance/ENGINE_FREEZE.json'
        freeze.parent.mkdir(parents=True)
        freeze.write_text(json.dumps({'mainSourceSHA256': {'src/Main.java': builder.sha(module / 'src/Main.java')}}))
        (module / 'Harness.java').write_bytes(b'accepted harness')
        (here / 'ACCEPTED_HARNESS_SOURCES.json').write_text(json.dumps({'sources': {'Harness.java': builder.sha(module / 'Harness.java')}}))
        entries = {f'engine/Class{i}.class': f'class{i}'.encode() for i in range(108)}
        entries.update({f'META-INF/entry{i}': f'metadata{i}'.encode() for i in range(12)})
        (here / 'ACCEPTED_JAR_CONTENT_SHA256.json').write_text(json.dumps({'entries': {n: hashlib.sha256(b).hexdigest() for n, b in entries.items()}}))
        return module, here, entries

    def test_missing_jar_builds_separately_and_preserves_previous_work(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            module, here, entries = self.fixture(root)
            old = here / 'build-isolated'
            old.mkdir(); (old / 'evidence.txt').write_bytes(b'keep unchanged')
            attempted = []
            def run(command, **kwargs):
                if len(attempted) % 2 == 0:
                    pom_flag = '-Pom' if os.name == 'nt' else '-f'
                    work = Path(command[command.index(pom_flag) + 1]).parent
                    attempted.append(work)
                    classes = work / 'target/classes'
                    classes.mkdir(parents=True)
                    for name, data in entries.items():
                        if name.endswith('.class'):
                            path = classes / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
                    with zipfile.ZipFile(work / 'target/matching-engine-0.1-SNAPSHOT.jar', 'w') as jar:
                        for name, data in entries.items():
                            if not name.endswith('.class') or int(name.split('Class')[1].split('.')[0]) < 97:
                                jar.writestr(name, data)
                else:
                    attempted.append('javac')
            with patch.object(builder, 'HERE', here), patch.object(builder, 'MODULE', module), \
                 patch.object(builder, 'ROOT', root), patch.dict(os.environ, {'JAVA_HOME': str(root / 'jdk')}), \
                 patch.object(builder.subprocess, 'run', side_effect=run) as commands, contextlib.redirect_stdout(io.StringIO()):
                builder.main()
                dest = module / 'target/matching-engine-0.1-SNAPSHOT.jar'
                builder.verify(dest)
                before = dest.read_bytes()
                builder.main()
                self.assertEqual(2, commands.call_count, 'Existing accepted JAR must not be rebuilt')
                self.assertEqual(before, dest.read_bytes())
            self.assertNotEqual(old, attempted[0])
            self.assertEqual(here, attempted[0].parent)
            self.assertEqual(b'keep unchanged', (old / 'evidence.txt').read_bytes())
            with zipfile.ZipFile(dest) as jar:
                self.assertEqual(120, len(jar.namelist()))
                self.assertEqual(108, sum(n.endswith('.class') for n in jar.namelist()))

    def test_builder_rejects_changed_source_before_build(self):
        with tempfile.TemporaryDirectory() as folder:
            module, here, _ = self.fixture(Path(folder))
            (module / 'src/Main.java').write_bytes(b'changed source')
            with patch.object(builder, 'HERE', here), patch.object(builder, 'MODULE', module), patch.object(builder.subprocess, 'run') as run:
                with self.assertRaisesRegex(RuntimeError, 'source SHA256 mismatch'): builder.main()
                run.assert_not_called()

    def test_builder_rejects_changed_harness_without_publishing_jar(self):
        with tempfile.TemporaryDirectory() as folder:
            module, here, _ = self.fixture(Path(folder))
            (module / 'Harness.java').write_bytes(b'changed harness')
            with patch.object(builder, 'HERE', here), patch.object(builder, 'MODULE', module), patch.object(builder.subprocess, 'run') as run:
                with self.assertRaisesRegex(RuntimeError, 'harness source SHA256 mismatch'): builder.main()
                self.assertEqual(1, run.call_count)
                self.assertFalse((module / 'target/matching-engine-0.1-SNAPSHOT.jar').exists())

    def test_builder_rejects_changed_extra_missing_and_duplicate_entries(self):
        with tempfile.TemporaryDirectory() as folder:
            module, here, entries = self.fixture(Path(folder))
            jar = module / 'fixture.jar'
            for case in ('changed', 'extra', 'missing', 'duplicate'):
                with self.subTest(case=case), warnings.catch_warnings():
                    warnings.simplefilter('ignore', UserWarning)
                    with zipfile.ZipFile(jar, 'w') as package:
                        for name, data in entries.items():
                            if case == 'missing' and name == 'engine/Class0.class': continue
                            package.writestr(name, b'changed' if case == 'changed' and name == 'engine/Class0.class' else data)
                        if case == 'extra': package.writestr('extra.class', b'extra')
                        if case == 'duplicate': package.writestr('engine/Class0.class', entries['engine/Class0.class'])
                    with patch.object(builder, 'HERE', here):
                        with self.assertRaises(RuntimeError): builder.verify(jar)


if __name__ == '__main__':
    unittest.main()
