"""Offline source archive and signing-secret exclusion audit; never prints secrets."""
from pathlib import Path
from zipfile import ZipFile
import hashlib,json,re
root=Path(__file__).resolve().parents[1]
version=re.search(r'versionName = "([^"]+)"',(root/'app/build.gradle.kts').read_text(encoding='utf-8-sig'))[1]
source=root/f'artifacts/bluedotOFF-{version}-source.zip'
secrets=[]
for path in (root.parent/'.private').glob('*'):
    if path.suffix in ('.p12','.jks','.keystore'):secrets.append(path.read_bytes())
    if path.name.endswith('-release.properties'):
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            if '=' in line and 'password' in line.split('=',1)[0].lower():
                value=line.split('=',1)[1].strip()
                if value:secrets.append(value.encode())
assert secrets,'Local signing configuration required for secret exclusion audit'
with ZipFile(source) as archive:
    names=archive.namelist()
    assert not any(set(Path(n).parts)&{'.private','build','artifacts','.gradle','local.properties'} or Path(n).suffix in ('.p12','.jks','.keystore') for n in names)
    for name in names:assert all(secret not in archive.read(name) for secret in secrets),'Secret exclusion failed'
record={'version':version,'sourceZipSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'sourceFiles':len(names),'sourceSecretExclusion':'PASS'}
(root/f'artifacts/delivery-audit-{version}.json').write_text(json.dumps(record,indent=2),encoding='utf-8')
print(json.dumps(record,indent=2))
