"""Package reviewable sources with a path/hash audit; never accesses device private files or signing secrets."""
from pathlib import Path
import hashlib,json,os,zipfile,re
P=Path(__file__).resolve().parents[1];A=P/'artifacts';A.mkdir(exist_ok=True)
version=re.search(r'versionName = "([^"]+)"',(P/'app/build.gradle.kts').read_text(encoding='utf-8-sig'))[1]
roots=['app/src/main','app/src/local','app/src/test','core/src','gradle/wrapper','docs','scripts']
singles=['.gitignore','README.md','README.ko.md','LICENSE','app/proguard-rules.pro','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat','app/build.gradle.kts','core/build.gradle.kts']
excluded={'.git','.gradle','.kotlin','.cxx','build','artifacts','research','.private','__pycache__','no_backup','.idea','tools','captures'}
files={P/s for s in singles}
for root in roots:
    for directory,dirs,names in os.walk(P/root):
        dirs[:]=[d for d in dirs if d not in excluded]
        files.update(Path(directory)/n for n in names)
def sha(data):return hashlib.sha256(data).hexdigest()
payload={}
for file in sorted(files):
    rel=file.relative_to(P)
    assert file.is_file() and not file.is_symlink()
    assert not excluded.intersection(rel.parts),rel
    assert file.name not in {'local.properties','signing.properties','keystore.properties'} and not file.name.endswith('-release.properties'),rel
    assert file.suffix.lower() not in {'.jks','.keystore','.p12','.apk','.so','.o','.pyc'},rel
    assert 'reference' not in rel.parts
    payload[rel.as_posix()]=file.read_bytes()
required=['LICENSE','README.md','README.ko.md','app/proguard-rules.pro','gradle/wrapper/gradle-wrapper.jar','app/src/main/cpp/native_adb.cpp','app/src/main/cpp/third_party/boringssl/CMakeLists.txt','app/src/main/assets/licenses/AOSP-ADB-NOTICE.txt','app/src/main/assets/licenses/BoringSSL-LICENSE.txt','app/src/main/assets/licenses/Fiat-Crypto-LICENSE.txt']
assert all(n in payload for n in required)
# The complete vendored source subset and licenses must be reproduced exactly.
provenance=json.loads(payload['app/src/main/cpp/third_party/provenance.json'])
for name,digest in provenance['boringsslFiles'].items():
    assert sha(payload['app/src/main/cpp/third_party/boringssl/'+name])==digest,name
manifest={name:sha(data) for name,data in payload.items()}
output=A/f'bluedotOFF-{version}-source.zip'
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for name,data in payload.items():
        entry=zipfile.ZipInfo('bluedotOFF/'+name,date_time=(2026,10,6,0,0,0))
        entry.compress_type=zipfile.ZIP_DEFLATED
        entry.external_attr=(0o755 if name=='gradlew' else 0o644)<<16
        z.writestr(entry,data)
    entry=zipfile.ZipInfo('bluedotOFF/SOURCE-SHA256.json',date_time=(2026,10,6,0,0,0))
    entry.compress_type=zipfile.ZIP_DEFLATED;entry.external_attr=0o644<<16
    z.writestr(entry,json.dumps(manifest,indent=2))
with zipfile.ZipFile(output) as z:
    assert z.testzip() is None
    assert len(z.namelist())==len(payload)+1 and len(z.namelist())==len(set(z.namelist()))
    assert all(z.read('bluedotOFF/'+name)==data for name,data in payload.items())
audit={'version':version,'files':len(payload),'zipFile':output.name,'sha256':sha(output.read_bytes()),'bytes':output.stat().st_size,'zipIntegrityPass':True,'allArchivedFilesMatchSource':True,'gradleWrapperIncluded':True,'vendorProvenanceVerified':True,'excluded':['research/device dumps/journals/keys/codes','build/.cxx/.gradle caches','local.properties','keystores/signing secrets','historical device mutation scripts','Shizuku reference'],'sourceHashes':manifest}
(A/f'source-audit-{version}.json').write_text(json.dumps(audit,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in audit.items() if k!='sourceHashes'},indent=2))
