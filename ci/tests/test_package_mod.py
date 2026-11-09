import json
import tempfile
import unittest
import zipfile
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from package_mod import inspect_jar, version

class JarTests(unittest.TestCase):
    def jar(self, root, name, files):
        path = Path(root) / name
        with zipfile.ZipFile(path, 'w') as out:
            for name, contents in files.items(): out.writestr(name, contents)
        return path

    def test_fabric_version_and_minecraft(self):
        with tempfile.TemporaryDirectory() as root:
            data = json.dumps({'id': 'feed', 'version': version(), 'depends': {'minecraft': '=1.21.1'}})
            path = self.jar(root, 'x.jar', {'com/feed/Feed.class': 'x', 'fabric.mod.json': data})
            self.assertTrue(inspect_jar(path, 'fabric', '1.21.1', version()))
            with self.assertRaisesRegex(ValueError, 'Minecraft'): inspect_jar(path, 'fabric', '1.20.1', version())
            with self.assertRaisesRegex(ValueError, 'internal version'): inspect_jar(path, 'fabric', '1.21.1', '0.0.0')

    def test_neo_metadata(self):
        with tempfile.TemporaryDirectory() as root:
            meta = f'[[mods]]\nmodId="feed"\nversion="{version()}"\n[[dependencies.feed]]\nmodId="minecraft"\nversionRange="[1.20.1,1.20.2)"\n'
            path = self.jar(root, 'x.jar', {'com/feed/Feed.class': 'x', 'META-INF/mods.toml': meta})
            self.assertTrue(inspect_jar(path, 'neoforge', '1.20.1', version()))
            self.assertFalse(inspect_jar(path, 'fabric', '1.20.1', version()))

class ReleaseGuardTests(unittest.TestCase):
    def test_tag_and_development_api_block_release(self):
        import check_release
        from unittest.mock import patch
        with patch.object(check_release, 'version', return_value='0.2.0-dev19'):
            with self.assertRaisesRegex(ValueError, 'stable'): check_release.check('v0.2.0')
        with patch.object(check_release, 'version', return_value='0.2.0'):
            with self.assertRaisesRegex(ValueError, 'development API'): check_release.check('v0.2.0')

if __name__ == '__main__': unittest.main()
