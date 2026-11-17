"""Validate the actual distributable layout and keep host-provided runtimes out."""
from pathlib import Path
import json
import zipfile

archives = list(Path('build/libs').glob('*.spmod'))
assert len(archives) == 1, f'Expected one plugin, found {archives}'
with zipfile.ZipFile(archives[0]) as archive:
    assert archive.testzip() is None
    names = set(archive.namelist())
    manifest = archive.read('classes/META-INF/MANIFEST.MF').decode('utf-8')
    assert 'Plugin-Class: io.github.saltasmr.SaltAsmrPlugin' in manifest
    assert 'Plugin-Id: io.github.saltasmr' in manifest
    assert 'Plugin-Has-Config: true' in manifest
    assert 'Plugin-Permissions:' not in manifest
    assert 'classes/io/github/saltasmr/SaltAsmrPlugin.class' in names
    config = json.loads(archive.read('classes/preference_config.json'))
    assert config['configs'][0]['preferences'][0]['on_click'].endswith('SaltAsmrPlugin.openLibrary')
    libraries = sorted(name for name in names if name.startswith('lib/') and name.endswith('.jar'))
    assert any('sqlite-jdbc' in name for name in libraries)
    assert not any(any(term in name.lower() for term in ['kotlin-stdlib', 'pf4j', 'spw-workshop', 'compose']) for name in libraries)
    print(f'{archives[0]}: archive, manifest, config and dependencies verified')
    print('Runtime JARs:', ', '.join(libraries))
