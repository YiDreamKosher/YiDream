"""Bundle the existing production APK for a website-only publication.

The website cannot advance an Android version or replace its binary. Downloads
are restricted to exact official Pages URLs, without following redirects.
The workflow also verifies the preserved APK against the production certificate.
"""
import hashlib
import json
from pathlib import Path
import re
import sys
from urllib.request import HTTPRedirectHandler, Request, build_opener

ORIGIN = 'https://yidreamkosher.github.io'
MANIFEST_URL = ORIGIN + '/YiDream/version.json'
APK_URL = ORIGIN + '/YiDream/admin/yidream.apk'
BUILD_URL = ORIGIN + '/YiDream/admin/yidream-build.json'
LEGACY_APK_URL = 'https://qinfrance.github.io/YiDream/admin/yidream.apk'
MAX_APK_BYTES = 250 * 1024 * 1024


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def download(url, limit, opener=None):
    if url not in (MANIFEST_URL, APK_URL, BUILD_URL):
        raise ValueError('Source de téléchargement non autorisée.')
    opener = opener or build_opener(NoRedirect())
    request = Request(url, headers={'Cache-Control': 'no-cache', 'User-Agent': 'YiDream-Site-Publisher/1'})
    with opener.open(request, timeout=60) as response:
        if response.status != 200 or response.geturl() != url:
            raise ValueError('La source officielle a changé ou est indisponible.')
        data = response.read(limit + 1)
    if not data or len(data) > limit:
        raise ValueError('Taille de téléchargement invalide.')
    return data


def validate_manifest(source, published, build):
    if build.get('releaseSigned') is not True:
        raise ValueError('La version publiée ne porte pas la signature de production.')
    if (type(published.get('versionCode')) is not int or published['versionCode'] < 12 or
            published.get('versionCode') != source.get('versionCode') or
            published.get('versionName') != source.get('versionName')):
        raise ValueError('Publiez d’abord l’APK correspondant à la version du dépôt.')
    if (source.get('apkUrl') != APK_URL or
            published.get('apkUrl') not in (APK_URL, LEGACY_APK_URL) or
            not re.fullmatch(r'[0-9a-fA-F]{64}', published.get('apkSha256', ''))):
        raise ValueError('Manifeste de production invalide.')
    result = dict(published)
    result['apkUrl'] = APK_URL
    return result


def prepare(source_file, output, fetch=download):
    source = json.loads(Path(source_file).read_text())
    published = json.loads(fetch(MANIFEST_URL, 65536))
    build = json.loads(fetch(BUILD_URL, 4096))
    manifest = validate_manifest(source, published, build)
    apk = fetch(APK_URL, MAX_APK_BYTES)
    if hashlib.sha256(apk).hexdigest() != manifest['apkSha256'].lower():
        raise ValueError('L’APK ne correspond pas à l’empreinte de production.')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    (output / 'yidream.apk').write_bytes(apk)
    (output / 'yidream-build.json').write_text(json.dumps({'releaseSigned': True}) + '\n')
    (output / 'version.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print('APK de production conservé : ' + manifest['versionName'] + ', code ' + str(manifest['versionCode']))


if __name__ == '__main__':
    prepare(*sys.argv[1:])
