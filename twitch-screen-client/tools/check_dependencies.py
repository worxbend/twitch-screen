"""Inventory exact locked wheel metadata and bundled notices without network access.

Hash/version/notice evidence is not a comprehensive security or rights clearance.
"""
import argparse
from email.parser import BytesParser
import hashlib
import json
from pathlib import Path
import re
import zipfile

LOCK_ROW = re.compile(r'([A-Za-z0-9_.-]+)==([^ ]+) --hash=sha256:([0-9a-f]{64})')


def normalized(name):
    return re.sub('[-_.]+', '-', name).lower()


def inspect(root, wheelhouse):
    wheels = {}
    for path in sorted(wheelhouse.glob('*.whl')):
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest in wheels:
            raise ValueError('duplicate locked wheel hash')
        wheels[digest] = path
    packages = []
    for lock in ('requirements.lock', 'requirements-build.lock'):
        for row in (root / lock).read_text().splitlines():
            if not row or row.startswith('#'):
                continue
            match = LOCK_ROW.fullmatch(row)
            if match is None:
                raise ValueError('unsupported lock row')
            name, version, digest = match.groups()
            if digest not in wheels:
                raise ValueError('locked wheel unavailable: ' + name)
            path = wheels[digest]
            with zipfile.ZipFile(path) as wheel:
                metadata_names = [item for item in wheel.namelist() if item.endswith('.dist-info/METADATA')]
                if len(metadata_names) != 1:
                    raise ValueError('one distribution metadata entry required')
                metadata = BytesParser().parsebytes(wheel.read(metadata_names[0]))
                if normalized(metadata['Name']) != normalized(name) or metadata['Version'] != version:
                    raise ValueError('wheel metadata differs from lock: ' + name)
                notices = []
                for item in wheel.namelist():
                    leaf = Path(item).name.casefold()
                    if not item.endswith('/') and ('.dist-info/licenses/' in item or
                            leaf.startswith(('license', 'licence', 'copying', 'notice', 'authors'))):
                        wire = wheel.read(item)
                        notices.append({'path': item, 'sha256': hashlib.sha256(wire).hexdigest(), 'bytes': len(wire)})
                packages.append({'scope': 'runtime' if lock == 'requirements.lock' else 'build',
                                 'name': name, 'version': version, 'wheel': path.name, 'sha256': digest,
                                 'licenseExpression': metadata.get('License-Expression'),
                                 'legacyLicense': metadata.get('License'),
                                 'licenseClassifiers': [item for item in metadata.get_all('Classifier', []) if item.startswith('License ::')],
                                 'projectUrls': metadata.get_all('Project-URL', []),
                                 'bundledNotices': notices,
                                 'noticeReviewRequired': not bool(notices)})
    return {'source': 'hash-verified locked wheel metadata and notice entries',
            'networkAccess': False, 'securityAuditComplete': False, 'rightsClearance': False,
            'packageCount': len(packages), 'packages': packages}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wheelhouse', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = inspect(Path(__file__).resolve().parents[1], args.wheelhouse)
    wire = json.dumps(report, indent=2) + '\n'
    if args.output:
        args.output.write_text(wire)
    print(wire, end='')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
