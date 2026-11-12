from pathlib import Path
import xml.etree.ElementTree as E
p=Path(__file__).resolve().parents[1]; n={'a':'http://schemas.android.com/apk/res/android'}; key='{'+n['a']+'}name'
r=E.parse(p/'app/src/main/AndroidManifest.xml').getroot()
launchers=[a for a in r.iter() if a.tag in ('activity','activity-alias') and a.find("intent-filter/category[@a:name='android.intent.category.LAUNCHER']",n) is not None]
assert [a.attrib[key] for a in launchers]==['.YiDreamLauncher'],launchers
assert launchers[0].attrib['{'+n['a']+'}targetActivity']=='.MainActivity'
assert not r.findall(".//category[@a:name='android.intent.category.HOME']",n)
s=(p/'app/src/main/java/com/yidream/mdm/MainActivity.kt').read_text(); assert all('"'+tab+'" to findViewById' in s for tab in ['home','store','settings','sites'])
assert 'deviceOwnerManager.leaveDedicatedMode()' in s
migration=s[s.index('private fun restoreFilterAppRole()'):s.index('// Navigation par onglets')]
assert 'clearDeviceOwnerApp' not in migration and 'removeBaseRestrictions' not in migration
old=(p/'app/src/main/java/com/yidream/mdm/LauncherActivity.kt').read_text();assert 'MainActivity::class.java' in old and 'WebView' not in old
print('PASS: YiDream opens filter dashboard, has no HOME role, retains owner and filtering during migration.')

resources=p/'app/src/main/res'
layout=(resources/'layout/activity_main.xml').read_text()
assert 'pageSites' in layout and 'storeContent' in layout and '<WebView' not in layout
assert '@drawable/bg_app_gradient' in layout
assert '<color name="accent_navy">#0C1C38</color>' in (resources/'values/colors.xml').read_text()
controller=(p/'app/src/main/java/com/yidream/mdm/MdmPortalController.kt').read_text()
assert 'OfflineRequests.enqueue' in controller and 'UUID.randomUUID()' in controller
assert 'portal.refreshStore()' in s and 'portal.refreshSites()' in s
print('PASS: native store and site requests use the original YiDream palette.')

root_layout=E.parse(resources/'layout/activity_main.xml').getroot()
a='{http://schemas.android.com/apk/res/android}'
settings=next(e for e in root_layout.iter() if e.get(a+'id')=='@+id/pageSettings')
assert any(e.get(a+'id')=='@+id/sectionAbout' for e in settings.iter())
assert not any(e.get(a+'id') in ('@+id/tabAbout','@+id/pageAbout') for e in root_layout.iter())
assert all(any(e.get(a+'id')=='@+id/'+id for e in settings.iter()) for id in ['versionLabel','settingsContactContent','rowRemoveFilter'])
print('PASS: About, version, contact and protected filter removal remain inside Settings; optional four-tab navigation.')
