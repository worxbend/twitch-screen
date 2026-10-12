#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Check saved GitHub run/artifact metadata against independently reviewed identities.

Offline only. Metadata files are not authenticated by this tool. It neither fetches,
downloads, dispatches nor publishes anything. Optional archive matching checks
compressed bytes against an independent digest. An additional reviewed manifest
pin enables static content verification from that same admitted byte buffer.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

from artifact_archive import MAX_ARCHIVE_BYTES, archive_bytes_directory
from build import MAX_JSON_BYTES, strict_json
from verify_artifact import read_member, verify

REPOSITORY = 'worxbend/twitch-screen'
WORKFLOW = '.github/workflows/pages-build.yml'
API = 'https://api.github.com/repos/' + REPOSITORY


def positive_id(value, label):
    if type(value) is not int or value <= 0:
        raise ValueError(label + ' must be a positive integer')
    return value


def object_field(value, key):
    item = value.get(key)
    if not isinstance(item, dict):
        raise ValueError('missing object: ' + key)
    return item


def check(run, artifacts, run_id, artifact_id, head_sha, event,
          archive=None, expected_archive_sha256=None, expected_manifest_sha256=None):
    """Match one complete first-attempt build; refuse ambiguous rerun artifacts."""
    if expected_manifest_sha256 is not None:
        if archive is None:
            raise ValueError('expected manifest identity requires --archive')
        if (not isinstance(expected_manifest_sha256, str)
                or not re.fullmatch(r'[0-9a-f]{64}', expected_manifest_sha256)):
            raise ValueError('expected manifest identity must be 64 lowercase hexadecimal characters')
    if archive is None and expected_archive_sha256 is not None:
        raise ValueError('expected archive identity requires --archive')
    if archive is not None and (not isinstance(expected_archive_sha256, str)
                               or not re.fullmatch(r'[0-9a-f]{64}', expected_archive_sha256)):
        raise ValueError('archive requires an independent 64 lowercase hexadecimal SHA-256 pin')
    positive_id(run_id, 'expected run ID')
    positive_id(artifact_id, 'expected artifact ID')
    if not isinstance(head_sha, str) or not re.fullmatch(r'[0-9a-f]{40}', head_sha):
        raise ValueError('expected head SHA must be 40 lowercase hexadecimal characters')
    if event not in ('push', 'pull_request', 'workflow_dispatch'):
        raise ValueError('expected event is not an artifact-build trigger')
    if not isinstance(run, dict) or not isinstance(artifacts, dict):
        raise ValueError('metadata roots must be objects')
    repository = object_field(run, 'repository')
    repository_id = positive_id(repository.get('id'), 'repository ID')
    head_repository_id = positive_id(object_field(run, 'head_repository').get('id'), 'head repository ID')
    if (repository.get('full_name') != REPOSITORY or type(run.get('id')) is not int
            or run['id'] != run_id or run.get('path') != WORKFLOW
            or run.get('url') != API + '/actions/runs/' + str(run_id)
            or run.get('head_sha') != head_sha or run.get('event') != event):
        raise ValueError('run identity differs from reviewed repository/workflow/head/event')
    if run.get('status') != 'completed' or run.get('conclusion') != 'success':
        raise ValueError('run is not completed successfully')
    if type(run.get('run_attempt')) is not int or run['run_attempt'] != 1:
        raise ValueError('rerun artifact attempt is ambiguous; independent manual review required')
    if not isinstance(run.get('head_branch'), str) or not run['head_branch']:
        raise ValueError('run head branch missing')
    items = artifacts.get('artifacts')
    count = artifacts.get('total_count')
    if (not isinstance(items, list) or type(count) is not int or count < 0
            or count != len(items) or count > 100 or any(not isinstance(item, dict) for item in items)):
        raise ValueError('artifact inventory incomplete or malformed; fetch all pages')
    ids = [positive_id(item.get('id'), 'artifact ID') for item in items]
    if len(set(ids)) != len(ids):
        raise ValueError('duplicate artifact IDs')
    matches = [item for item in items if item['id'] == artifact_id]
    if len(matches) != 1:
        raise ValueError('reviewed artifact ID absent from exact run inventory')
    artifact = matches[0]
    workflow_run = object_field(artifact, 'workflow_run')
    if (artifact.get('name') != 'twitch-screen-site-' + head_sha
            or artifact.get('expired') is not False
            or artifact.get('url') != API + '/actions/artifacts/' + str(artifact_id)
            or artifact.get('archive_download_url') != API + '/actions/artifacts/' + str(artifact_id) + '/zip'):
        raise ValueError('artifact identity/name/expiry/download target differs')
    for key, expected in (('id', run_id), ('repository_id', repository_id),
                          ('head_repository_id', head_repository_id)):
        if type(workflow_run.get(key)) is not int or workflow_run[key] != expected:
            raise ValueError('artifact workflow-run identity differs: ' + key)
    if workflow_run.get('head_sha') != head_sha or workflow_run.get('head_branch') != run['head_branch']:
        raise ValueError('artifact head differs from reviewed run')
    size = artifact.get('size_in_bytes')
    if type(size) is not int or not 0 < size <= MAX_ARCHIVE_BYTES:
        raise ValueError('artifact compressed size outside bounded ZIP policy')
    digest = artifact.get('digest')
    if not isinstance(digest, str) or not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
        raise ValueError('artifact archive digest missing or malformed')
    result = {'status': 'metadata-matched', 'repository': REPOSITORY, 'workflow': WORKFLOW,
              'runId': run_id, 'runAttempt': 1, 'event': event, 'headSha': head_sha,
              'artifactId': artifact_id, 'artifactName': artifact['name'],
              'archiveDownloadUrl': artifact['archive_download_url'], 'archiveDigest': digest,
              'metadataAuthenticated': False, 'artifactBytesVerified': False,
              'environmentProtectionVerified': False, 'publicationAuthorized': False,
              'archiveBytesMatched': False}
    if archive is not None:
        if not isinstance(expected_archive_sha256, str) or digest != 'sha256:' + expected_archive_sha256:
            raise ValueError('metadata archive digest differs from independent reviewed pin')
        archive = Path(archive).absolute()
        data = read_member(archive.parent, archive.name, MAX_ARCHIVE_BYTES)
        if len(data) != size:
            raise ValueError('archive size differs from reviewed metadata')
        actual = hashlib.sha256(data).hexdigest()
        if actual != expected_archive_sha256:
            raise ValueError('archive bytes differ from independent reviewed pin')
        result.update(archiveBytesMatched=True, archiveSha256=actual, archiveSizeBytes=len(data))
        if expected_manifest_sha256 is not None:
            # Parse the already matched bytes, never reopen the mutable input path.
            with archive_bytes_directory(data) as directory:
                content = verify(directory, expected_manifest_sha256)
            result.update(artifactBytesVerified=True, artifact=content)
    return result


def read_metadata(path):
    path = Path(path).absolute()
    return strict_json(read_member(path.parent, path.name, MAX_JSON_BYTES).decode('utf-8'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-json', type=Path, required=True)
    parser.add_argument('--artifacts-json', type=Path, required=True)
    parser.add_argument('--run-id', type=int, required=True)
    parser.add_argument('--artifact-id', type=int, required=True)
    parser.add_argument('--head-sha', required=True)
    parser.add_argument('--event', required=True)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--expected-archive-sha256')
    parser.add_argument('--expected-manifest-sha256')
    args = parser.parse_args()
    try:
        result = check(read_metadata(args.run_json), read_metadata(args.artifacts_json),
                       args.run_id, args.artifact_id, args.head_sha, args.event,
                       args.archive, args.expected_archive_sha256, args.expected_manifest_sha256)
    except (ValueError, OSError, KeyError) as error:
        parser.exit(1, f'hosted identity rejected: {error}\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
