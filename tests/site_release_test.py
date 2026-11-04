import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('site_release', ROOT / 'site/tools/prepare-release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class SiteReleaseTest(unittest.TestCase):
    def setUp(self):
        self.apk = b'existing signed APK fixture'
        self.manifest = {'versionCode': 17, 'versionName': 'v5.9.1', 'apkUrl': release.APK_URL,
                         'apkSha256': hashlib.sha256(self.apk).hexdigest(), 'changelog': 'Existing release'}
        self.source = dict(self.manifest)
        self.build = {'releaseSigned': True}

    def test_site_bundle_preserves_existing_binary_and_release(self):
        for published_url in (release.APK_URL, release.LEGACY_APK_URL):
            with self.subTest(published_url=published_url), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                source = root / 'source.json'
                source.write_text(json.dumps(self.source))
                metadata = {**self.manifest, 'apkUrl': published_url}
                calls = []
                def fetch(url, limit):
                    calls.append(url)
                    return {release.MANIFEST_URL: json.dumps(metadata).encode(),
                            release.BUILD_URL: json.dumps(self.build).encode(), release.APK_URL: self.apk}[url]
                release.prepare(source, root / 'out', fetch)
                self.assertEqual((root / 'out/yidream.apk').read_bytes(), self.apk)
                self.assertEqual(json.loads((root / 'out/version.json').read_text()), self.manifest)
                self.assertEqual(calls, [release.MANIFEST_URL, release.BUILD_URL, release.APK_URL])

    def test_unpublished_version_or_debug_release_is_rejected(self):
        for changes in ({'versionCode': 18}, {'versionCode': True}, {'versionName': 'different'},
                        {'apkUrl': 'https://other.example/app.apk'}, {'apkSha256': ''}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                release.validate_manifest(self.source, {**self.manifest, **changes}, self.build)
        for signed in (False, 'true', None):
            with self.subTest(signed=signed), self.assertRaises(ValueError):
                release.validate_manifest(self.source, self.manifest, {'releaseSigned': signed})

    def test_corrupt_apk_does_not_create_publication_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'source.json'
            source.write_text(json.dumps(self.source))
            def fetch(url, limit):
                return {release.MANIFEST_URL: json.dumps(self.manifest).encode(),
                        release.BUILD_URL: json.dumps(self.build).encode(), release.APK_URL: b'corrupt'}[url]
            with self.assertRaises(ValueError):
                release.prepare(source, root / 'out', fetch)
            self.assertFalse((root / 'out').exists())

    def test_download_refuses_other_source_redirect_and_oversized_data(self):
        with self.assertRaises(ValueError):
            release.download(release.APK_URL + '?other=source', 10)
        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def geturl(self): return release.APK_URL
            def read(self, size): return b'12345'
        class Opener:
            def open(self, *args, **kwargs): return Response()
        with self.assertRaises(ValueError): release.download(release.APK_URL, 4, Opener())
        Response.status = 302
        with self.assertRaises(ValueError): release.download(release.APK_URL, 8, Opener())
        self.assertIsNone(release.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example'))

    def test_site_and_android_workflows_have_separate_triggers(self):
        android = (ROOT / '.github/workflows/deploy-webadb.yml').read_text().split('workflow_dispatch:')[0]
        web = (ROOT / '.github/workflows/deploy-site.yml').read_text()
        for path in ("'site/client/**'", "'webadb/**'", "'version.json'", "'.github/workflows/deploy-webadb.yml'"):
            self.assertNotIn(path, android)
            self.assertIn(path, web)
        for path in ("'app/**'", "'build.gradle'", "'settings.gradle'", "'gradle.properties'"):
            self.assertIn(path, android)
        self.assertNotIn('assembleRelease', web)
        self.assertNotIn('action-gh-release', web)
        self.assertIn('group: "pages"', web)
        self.assertIn('verify-apk-certificate.py', web)


if __name__ == '__main__':
    unittest.main()
