"""Create a persistent local release identity once. Never prints credentials or overwrites a key.
The private directory is outside the distributable source tree and is gitignored.
"""
from pathlib import Path
import os, secrets, subprocess
root=Path(__file__).resolve().parents[1]
private=root.parent/'.private'
private.mkdir(exist_ok=True)
(private/'.gitignore').write_text('*\n',encoding='ascii')
key=private/'bluedotOFF-release.p12'
props=private/'bluedotOFF-release.properties'
if key.exists() or props.exists():
    if not (key.exists() and props.exists()):
        raise SystemExit('Incomplete signing files; refusing to overwrite. Review private files locally.')
    print('Existing release identity preserved.')
else:
    password=secrets.token_hex(32)
    environment=dict(os.environ, BLUEDOT_SIGNING_PASSWORD=password)
    tool=Path('C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/keytool.exe')
    result=subprocess.run([str(tool),'-genkeypair','-keystore',str(key),'-storetype','PKCS12','-alias','bluedotoff-release','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=bluedotOFF Local Release','-storepass:env','BLUEDOT_SIGNING_PASSWORD','-keypass:env','BLUEDOT_SIGNING_PASSWORD'],env=environment,capture_output=True)
    if result.returncode:
        raise SystemExit('Release key generation failed; credentials are not logged. Review keytool installation.')
    props.write_text(f'storeFile={key.as_posix()}\nstorePassword={password}\nkeyAlias=bluedotoff-release\nkeyPassword={password}\n',encoding='ascii')
    print('Persistent release identity created in gitignored .private directory. No credentials printed.')
(private/'README.md').write_text('Keep both bluedotOFF-release.p12 and bluedotOFF-release.properties in a secure offline backup. They are needed for every future release update. Never share them, commit them, or include them in source ZIPs. The generated key is local to this computer. Debug APKs have a different signer: restore targets before an explicitly approved migration; do not uninstall automatically.\n',encoding='utf-8')
