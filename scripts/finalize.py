"""Release audit: run after the Gradle tests/builds/lint in README. Version comes from app/build.gradle.kts."""
from pathlib import Path
import subprocess,os,re,json,hashlib,shutil,zipfile,struct,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1];art=root/'artifacts';art.mkdir(exist_ok=True)
android='{http://schemas.android.com/apk/res/android}'
env=dict(os.environ,JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot')
sdk=Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home()/'AppData/Local/Android/Sdk')
tools=sdk/'build-tools/36.0.0'
permissions={'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS','android.permission.FOREGROUND_SERVICE','android.permission.WRITE_SECURE_SETTINGS','android.permission.ACCESS_NETWORK_STATE','android.permission.INTERNET','android.permission.ACCESS_LOCAL_NETWORK','android.permission.POST_NOTIFICATIONS','android.permission.RECEIVE_BOOT_COMPLETED','app.bluedotoff.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}
gradle=(root/'app/build.gradle.kts').read_text(encoding='utf-8-sig')
result={'version':re.search(r'versionName = "([^"]+)"',gradle)[1],'versionCode':int(re.search(r'versionCode = (\d+)',gradle)[1]),'compileSdk':37,'targetSdk':37,'apks':{},'testCounts':{},'lint':{}}
for variant,suffix,expected in [('localDebug','','3b3b208f92d3d437acf30c333d9a7b5b0bc8f535056e18edd82df2701db2be0c'),('localRelease','-release','619fe1ecbdf59d5d0b838ddc6420e58be90f828be11cb61fe6c8e6aa4ceaad62')]:
    kind='debug' if variant=='localDebug' else 'release'
    apk=root/f'app/build/outputs/apk/local/{kind}/app-local-{kind}.apk'
    manifest=ET.parse(root/f'app/build/intermediates/merged_manifests/{variant}/process{variant[0].upper()+variant[1:]}Manifest/AndroidManifest.xml').getroot()
    assert manifest.attrib[android+'versionName']==result['version']
    assert int(manifest.attrib[android+'versionCode'])==result['versionCode']
    assert {e.attrib[android+'name'] for e in manifest.findall('uses-permission')}==permissions
    application=manifest.find('application');assert application.attrib[android+'allowBackup']=='false'
    services=application.findall('service');assert len(services)==1
    assert services[0].attrib[android+'name']=='app.bluedotoff.adb.PairingLifetimeService'
    assert services[0].attrib[android+'exported']=='false' and services[0].attrib[android+'foregroundServiceType']=='shortService' and services[0].attrib[android+'stopWithTask']=='true'
    assert all('shizuku' not in str(e.attrib).lower() for e in manifest.iter())
    assert next(e for e in application.findall('activity') if e.attrib[android+'name'].endswith('LocalAdbSetupActivity')).attrib[android+'exported']=='false'
    assert next(e for e in application.findall('receiver') if e.attrib[android+'name'].endswith('PairingReplyReceiver')).attrib[android+'exported']=='false'
    assert next(e for e in application.findall('receiver') if e.attrib[android+'name'].endswith('LifecycleReceiver')).attrib[android+'exported']=='false'
    providers={p.attrib[android+'name']:p for p in application.findall('provider')}
    assert set(providers)=={'app.bluedotoff.adb.WatchBridgeProvider','androidx.startup.InitializationProvider'}
    watch=providers['app.bluedotoff.adb.WatchBridgeProvider']
    assert watch.attrib[android+'authorities']=='app.bluedotoff.watch' and watch.attrib[android+'permission']=='android.permission.INTERACT_ACROSS_USERS_FULL'
    assert providers['androidx.startup.InitializationProvider'].attrib[android+'exported']=='false'
    sign=subprocess.run([str(tools/'apksigner.bat'),'verify','--print-certs',str(apk)],capture_output=True,text=True,env=env);assert sign.returncode==0
    cert=next(line.split(': ',1)[1].strip() for line in sign.stdout.splitlines() if 'certificate SHA-256 digest:' in line);assert cert==expected
    align=subprocess.run([str(tools/'zipalign.exe'),'-c','-P','16','4',str(apk)],capture_output=True);assert align.returncode==0
    with zipfile.ZipFile(apk) as z:
        assert all(not re.search('shizuku|rikka',name,re.I) for name in z.namelist())
        for name in z.namelist():
            if name.endswith('.dex'):assert b'Lrikka/shizuku/' not in z.read(name) and b'Lmoe/shizuku/' not in z.read(name)
        native=z.read('lib/arm64-v8a/libbluedot_adb.so');assert native[:5]==b'\x7fELF\x02'
        phoff=struct.unpack_from('<Q',native,32)[0];size,count=struct.unpack_from('<HH',native,54)
        loads=[struct.unpack_from('<IIQQQQQQ',native,phoff+i*size)[7] for i in range(count) if struct.unpack_from('<I',native,phoff+i*size)[0]==1]
        assert loads and all(a>=16384 for a in loads)
        assert not re.search(rb'[A-Za-z]:[\\/]Users[\\/]',native)  # no build-machine paths (-ffile-prefix-map)
        licenses=[name for name in z.namelist() if name.startswith('assets/') and ('LICENSE' in name or 'NOTICE' in name)];assert len(licenses)>=3
    body=(root/f'app/build/reports/lint-results-{variant}.txt').read_text(encoding='utf-8-sig');match=re.search(r'(\d+) errors, (\d+) warnings',body);assert match and int(match[1])==0
    result['lint'][kind]={'errors':0,'warnings':int(match[2])}
    output=art/f'bluedotOFF-{result["version"]}{suffix}.apk';shutil.copy2(apk,output)
    result['apks'][kind]={'file':output.name,'sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'bytes':output.stat().st_size,'signerSha256':cert,'nativeLoadAlignment':loads,'zipAlignmentPass':True,'licenseAssets':licenses}
for directory in ['core/build/test-results/test','app/build/test-results/testLocalDebugUnitTest','app/build/test-results/testLocalReleaseUnitTest']:
    suites=[ET.parse(p).getroot() for p in (root/directory).glob('TEST-*.xml')];assert suites and all(int(s.get(k,0))==0 for s in suites for k in ['errors','failures','skipped'])
    result['testCounts'][directory]=sum(int(s.get('tests',0)) for s in suites)
source=list((root/'app/src/main/java').rglob('*.kt'))+list((root/'app/src/local/java').rglob('*.kt'))+list((root/'core/src/main').rglob('*.kt'))
for p in source:
    if p.name in {"MinimalPrivilegedHelper.kt","WirelessControls.kt"}:continue
    text=p.read_text(encoding='utf-8-sig');assert not re.search(r'Runtime\.getRuntime|ProcessBuilder|bindUserService\(|grantRuntimePermission\(|revokeRuntimePermission\(|setSystemProperty\(|Settings\.(?:Global|Secure|System)\.put|setLocationEnabled\(|import (?:rikka|moe)\.shizuku',text),p
native=(root/'app/src/main/cpp/native_adb.cpp').read_text();assert 'addr.sin_addr.s_addr=htonl(INADDR_LOOPBACK)' in native and native.count('int r=connect(')==1
helper=(root/'app/src/main/java/app/bluedotoff/adb/MinimalPrivilegedHelper.kt').read_text()
assert helper.count('parcelRead("permissionmgr","updatePermissionFlags"')==1
assert 'it.writeString("android.permission.ACCESS_FINE_LOCATION");it.writeInt(256);it.writeInt(wanted);it.writeInt(1);it.writeString("default:0");it.writeInt(0)' in helper
controls=(root/'app/src/main/java/app/bluedotoff/adb/WirelessControls.kt').read_text(encoding='utf-8')
assert controls.count('Settings.Global.putInt(')==2
assert 'Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",1)' in controls
assert 'Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",0)' in controls
assert helper.count('ProcessBuilder(')==2
for operation in ['grant','revoke']:
    assert 'ProcessBuilder("/system/bin/pm","'+operation+'","--user","0","app.bluedotoff","android.permission.WRITE_SECURE_SETTINGS")' in helper
assert not re.search(r'Settings\.(?:Global|Secure|System)\.put',helper)
assert not re.search(r'ProcessBuilder|Runtime\.getRuntime|updatePermissionFlags',controls)
# Provenance: which source the audited APKs came from, and whether it had uncommitted changes.
git=lambda *a:subprocess.run(['git',*a],cwd=root,capture_output=True,text=True,check=True).stdout.strip()
result['source']={'commit':git('rev-parse','HEAD'),'uncommittedChanges':bool(git('status','--porcelain','--','.'))}
result['mergedPermissions']=sorted(permissions);result['staticAudit']='fixed-loopback, typed allowlist, single narrow FINE setter; pattern/manifest/dex checks, not a formal proof'
(art/f"build-audit-{result['version']}.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
# Published next to the release APK so anyone (or any analysis tool) can confirm a file is the official build.
rel=result['apks']['release'];sig=':'.join(rel['signerSha256'][i:i+2] for i in range(0,64,2)).upper()
(art/f"bluedotOFF-{result['version']}-checksums.txt").write_text(''.join(line+'\n' for line in [rel['file'],'SHA-256: '+rel['sha256'],'Signing certificate SHA-256: '+sig]),encoding='utf-8')
if result['source']['uncommittedChanges']:print('WARNING: built from uncommitted changes; commit before publishing.')
print(json.dumps(result,ensure_ascii=False,indent=2))
