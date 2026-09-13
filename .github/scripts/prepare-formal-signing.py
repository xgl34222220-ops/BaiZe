"""Use the existing repository signing secrets; never export the private key as an artifact."""
import base64
import hashlib
import os
from pathlib import Path
import subprocess


def secret(name: str) -> str:
    value = os.environ[name].replace('\r', '').replace('\n', '').strip()
    if value.startswith(name + '='):
        value = value[len(name) + 1:].strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    if not value:
        raise ValueError(f'Missing {name}')
    print('::add-mask::' + value, flush=True)
    return value


def main() -> None:
    temp = Path(os.environ['RUNNER_TEMP'])
    store = temp / 'baize-formal.jks'
    certificate = temp / 'baize-formal.der'
    store.write_bytes(base64.b64decode(''.join(secret('BAIZE_KEYSTORE_BASE64').split()), validate=True))
    store.chmod(0o600)
    password = secret('BAIZE_KEYSTORE_PASSWORD')
    alias = secret('BAIZE_KEY_ALIAS')
    key_password = secret('BAIZE_KEY_PASSWORD')
    subprocess.run(['keytool', '-exportcert', '-keystore', str(store), '-storepass', password,
                    '-alias', alias, '-file', str(certificate)], check=True, timeout=60)
    if hashlib.sha256(certificate.read_bytes()).hexdigest() != os.environ['BAIZE_CERT_SHA256']:
        raise ValueError('Formal signing certificate mismatch; refusing publication')
    with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as stream:
        stream.write(f'BAIZE_KEYSTORE_PATH={store}\nBAIZE_KEYSTORE_PASSWORD={password}\n'
                     f'BAIZE_KEY_ALIAS={alias}\nBAIZE_KEY_PASSWORD={key_password}\n')


if __name__ == '__main__':
    main()
