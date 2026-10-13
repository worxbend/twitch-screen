"""Fail closed before compiling pinned historical sources; never fetch or alter the checkout."""
from pathlib import Path
import re
import subprocess


def baseline_repository(root: Path, revision: str) -> Path:
    if re.fullmatch(r'[0-9a-f]{40}', revision) is None:
        raise ValueError('Baseline revision must be an exact lowercase forty-character commit ID')
    result = subprocess.run(['git', 'cat-file', '-e', revision + '^{commit}'], cwd=root,
                            capture_output=True, text=True, timeout=10)
    if result.returncode != 0:
        raise RuntimeError(f'Pinned baseline {revision} is absent from the checkout. '
                           'Use actions/checkout with fetch-depth: 0 (or obtain this exact commit locally) '
                           'before running the immutable baseline gate; no substitute source is permitted.')
    return root
