"""Read reviewed upstream Git blobs, never an upstream working-tree edit."""
import fnmatch
import hashlib
import json
import re
import subprocess
from pathlib import Path


class SourceInputs:
    def __init__(self, lock_path: Path, workspace: Path):
        self.workspace = workspace
        raw = lock_path.read_bytes()
        self.sha256 = hashlib.sha256(raw).hexdigest()
        self.lock = json.loads(raw)
        if self.lock.get('schema') != 'weibian-source-inputs-v1':
            raise ValueError('unknown source input lock')
        self.verified_repositories = set()
        self.cache = {}

    def require(self, path: Path) -> bool:
        key = str(path.relative_to(self.workspace))
        if key not in self.lock['files']:
            raise ValueError(f'unpinned source input: {key}')
        return True

    def read(self, path: Path) -> str:
        self.require(path)
        key = str(path.relative_to(self.workspace))
        if key in self.cache:
            return self.cache[key]
        item = self.lock['files'][key]
        source = self.lock['sources'][item['source']]
        repo = self.workspace / source['directory']
        if Path(source['directory']).is_absolute() or '..' in Path(source['directory']).parts:
            raise ValueError('source repository must remain inside the workspace')
        if not re.fullmatch(r'[a-f0-9]{40}', source['commit']):
            raise ValueError('source commit must be exact')
        if item['source'] not in self.verified_repositories:
            remote = subprocess.check_output(['git', '-C', str(repo), 'remote', 'get-url', 'origin'], text=True).strip()
            slug = source['repository']
            if remote not in [f'https://github.com/{slug}', f'https://github.com/{slug}.git', f'git@github.com:{slug}.git']:
                raise ValueError(f'wrong source repository: {item["source"]}')
            self.verified_repositories.add(item['source'])
        blob = subprocess.check_output(['git', '-C', str(repo), 'show', f'{source["commit"]}:{item["path"]}'])
        if hashlib.sha256(blob).hexdigest() != item['sha256']:
            raise ValueError(f'source blob hash mismatch: {key}')
        self.cache[key] = blob.decode('utf-8')
        return self.cache[key]

    def files(self, directory: Path, pattern: str) -> list[Path]:
        prefix = str(directory.relative_to(self.workspace)) + '/'
        paths = [self.workspace / key for key in self.lock['files'] if key.startswith(prefix) and fnmatch.fnmatch(key[len(prefix):], pattern)]
        if not paths:
            raise ValueError(f'no pinned inputs under {prefix}')
        return sorted(paths)
