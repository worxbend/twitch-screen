#!/usr/bin/env python3
"""Run actual capable relay kernel-writer and continuous-control/native pairing.

Snapshot production sources plus one existing socket helper into private TMPDIR;
compile only the firmware-owned integration main. No board or peer edits.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from runtime_lifecycle import verify_callbacks, verify_report_phases
from runtime_observations import verify_application_sessions


def verify_control(records, schedule):
    verify_report_phases(records)
    verify_application_sessions(records)
    ready = [r for r in records if r['event']=='desk_ready']
    reports = [r for r in records if r['event']=='device_report_written']
    views = [r for r in records if r['event']=='application_view']
    summary = next(r for r in records if r['event']=='summary')
    assert len(ready)==1 and reports and views
    assert [r['seq'] for r in records if r['event']=='started']==[1,3]
    assert all(summary[k]==2 for k in ('started','holds','exits','completed'))
    assert summary['success'] and summary['deskReadyObserved']
    assert len(schedule)==121 and all(b['revision']>a['revision'] for a,b in zip(schedule,schedule[1:]))
    by_revision = {r['revision']:r for r in schedule}
    assert all(r['connection']==ready[0]['connection'] and r['epoch']==ready[0]['epoch'] for r in reports)
    assert all(b['revision']>a['revision'] and b['observedDeviceMs']>=a['observedDeviceMs']
               and b['displayRevision']>=a['displayRevision'] and b['sourceGeneration']>=a['sourceGeneration']
               and b['twitchEpisode']>=a['twitchEpisode'] and b['processedSeq']>=a['processedSeq']
               and b['enqueuedSeq']>=a['enqueuedSeq'] for a,b in zip(reports,reports[1:]))
    dequeued = 0
    for r in reports:
        if r['phase']:
            active_index = (1,3).index(r['activeSeq']) + 1
            assert dequeued <= active_index <= dequeued + 1
            dequeued = active_index
        assert r['enqueuedSeq']-r['pending']==dequeued+r['suppressedTotal']
        wanted = [seq for seq in range(1,r['enqueuedSeq']+1)
                  if not (seq==1 and dequeued>=1 or seq==3 and dequeued>=2 or seq==2 and r['suppressedTotal'])]
        assert [e['seq'] for e in r['pendingEntries']]==wanted
        assert r['pending']==len(r['pendingEntries'])<=8 and r['enqueuedSeq']<=r['processedSeq']<=3
        assert r['grantTotal']==8+r['enqueuedSeq']-r['pending']
        assert r['expiredTotal']==r['refusedTotal']==0 and r['suppressedTotal'] in (0,1)
        assert r['displayRevision'] in by_revision or r['displayRevision']==1
        assert r['phase']==0 or r['activeSeq'] in (1,3)
        assert not r['phase'] or (r['activeOrigin']==0 and r['activeEpoch']==ready[0]['epoch'])
        seqs = [e['seq'] for e in r['pendingEntries']]
        assert seqs==sorted(set(seqs)) and all(s in (2,3) for s in seqs)
        assert all(e['epoch']==ready[0]['epoch'] and e['origin']==(1 if e['seq']==2 else 0) for e in r['pendingEntries'])
        assert not r['suppressedTotal'] or 2 not in seqs
        if r['displayRevision'] in by_revision:
            accepted = by_revision[r['displayRevision']]
            assert (r['sourceGeneration'],r['twitchEpisode'])==(accepted['sourceGeneration'],accepted['twitchEpisode'])
            assert (r['baseScreen'],r['reason'])==((2,6) if accepted['enabled'] else (0,4))
    assert any(r['pending']==2 and r['activeSeq']==1 for r in reports)
    assert any(r['displayRevision']>=100 and r['activeSeq']==1 and r['phase']==2 for r in reports)
    assert reports[-1]['displayRevision']==schedule[-1]['revision']
    assert reports[-1]['grantTotal']==11 and reports[-1]['pending']==reports[-1]['phase']==0
    assert reports[-1]['suppressedTotal']==1
    # Coalescing is allowed, but continuous newer writes cannot hide an ancient
    # applied fence. Use both clocks' conservative lower observation bound; this
    # is an upper age bound, not a synchronized wall-time assumption.
    control_reports = [r for r in reports if r['displayRevision'] in by_revision]
    assert len(control_reports)>=5
    age_bounds = []
    for r in control_reports:
        earliest_unapplied = next((s for s in schedule if s['revision']>r['displayRevision']
                                  and s['acceptedRelayMs']<=r['observedRelayLowerMs']),None)
        if earliest_unapplied:
            age = r['observedRelayLowerMs']-earliest_unapplied['acceptedRelayMs']
            assert age<5000, 'Continuous control masked oldest unapplied fence'
            age_bounds.append(age)
    for index, r in enumerate(records):
        if r['event'] != 'application_view':
            continue
        assert not r['cpuVisible'] and not r['weatherVisible']
        assert r['displayRevision'] in by_revision or r['displayRevision'] in (0,1)
        if r['displayRevision'] in by_revision:
            state = by_revision[r['displayRevision']]
            if (r['baseScreen'],r['reason'])==(1,1):
                final_report_index = max(i for i,v in enumerate(records) if v['event']=='device_report_written')
                assert index > final_report_index, 'Link loss masked a ready view'
            else:
                assert (r['baseScreen'],r['reason'])==((2,6) if state['enabled'] else (0,4))
            assert r['statsAvailability']==0, 'Source fence exposed prior generation statistics'
    assert any(r['covered'] and r['displayRevision']>=100 for r in views)
    assert sum(a['covered'] and not b['covered'] for a,b in zip(views,views[1:]))==summary['exits'], 'Missing an actual uncover'
    assert any(not r['covered'] and r['displayRevision']==schedule[-1]['revision'] and (r['baseScreen'],r['reason'])==(0,4) for r in views)
    lifecycle = verify_callbacks(records, (10000, 100))
    return dict(passed=True,completedCards=2,controlWrites=len(schedule),socketWrittenReports=len(reports),
                **lifecycle,
                applicationViewsChecked=len(views),everyReportCreditConserved=True,everyViewChecked=True,
                maxObservedUnappliedAgeLowerMs=max(age_bounds,default=0),
                internalProfileOptIn=True,productionAdvertisementVerified=False,physicalVerified=False)


def verify_remote(records, schedule, observations, layout):
    verify_report_phases(records)
    reports = {r['revision']:r for r in records if r['event']=='device_report_written'}
    assert observations and all(b['sampledRelayMs']>=a['sampledRelayMs'] for a,b in zip(observations,observations[1:]))
    first_sample = observations[0]['sampledRelayMs']
    accepted_seen = False
    maximum_age = 0
    checked = 0
    last_revision = 0
    pairs = (('reportRevision','revision'),('grantTotal','grantTotal'),('processedSeq','processedSeq'),
             ('enqueuedSeq','enqueuedSeq'),('displayRevision','displayRevision'),('sourceGeneration','sourceGeneration'),
             ('twitchEpisode','twitchEpisode'),('weatherRevision','weatherRevision'),('cpuRevision','cpuRevision'),
             ('baseScreen','baseScreen'),('reason','reason'),('activePhase','phase'),('pendingCount','pending'),
             ('activeRemainingMs','remainingMs'),('expiredTotal','expiredTotal'),('suppressedTotal','suppressedTotal'),
             ('refusedTotal','refusedTotal'),('observedDeviceMs','observedDeviceMs'),('observedRelayLowerMs','observedRelayLowerMs'))
    for sample in observations:
        if sample['reportHex'] is None:
            assert not accepted_seen and sample['sampledRelayMs']-first_sample<5000
            continue
        accepted_seen = True
        checked += 1
        frame = bytes.fromhex(sample['reportHex'])
        assert len(frame)==252 and frame[:4]==bytes.fromhex('a7530306')
        assert frame[7]==255 ^ (sum((i+1)*frame[i] for i in range(7)) & 255)
        decoded = {f['name']:int.from_bytes(frame[8+f['offset']:8+f['offset']+f['width']],'little') for f in layout['fields'] if f['offset']<100}
        local = reports[decoded['reportRevision']]
        assert decoded['reportRevision']>=last_revision, 'Remote accepted authority regressed'
        last_revision = decoded['reportRevision']
        assert all(decoded[a]==local[b] for a,b in pairs), 'Accepted report differs from local whole-frame observation'
        assert f"{decoded['relayEpoch']:016x}"==local['epoch']
        assert f"{decoded['connectionId']:016x}"==local['connection']
        assert decoded['deviceBootId']==0x0500000000000001
        entries = [dict(epoch=local['activeEpoch'],seq=local['activeSeq'],origin=local['activeOrigin']) if local['phase'] else None]
        entries += local['pendingEntries'] + [None] * (8-local['pending'])
        for index, entry in enumerate(entries):
            raw = frame[108+16*index:124+16*index]
            assert len(raw)==16 and raw[14:]==b'\0\0'
            if entry is None:
                assert raw==bytes(16), 'Remote unused report entry is not empty'
            else:
                assert f"{int.from_bytes(raw[:8],'little'):016x}"==entry['epoch']
                assert int.from_bytes(raw[8:12],'little')==entry['seq'] and raw[13]==entry['origin']
        assert sample['sampledRelayMs']>=decoded['observedRelayLowerMs']
        missing = next((s for s in schedule if s['revision']>decoded['displayRevision'] and s['acceptedRelayMs']<=sample['sampledRelayMs']),None)
        if missing:
            age = sample['sampledRelayMs']-missing['acceptedRelayMs']
            assert age<5000, 'New controls masked oldest remotely unapplied revision'
            maximum_age = max(maximum_age,age)
    assert checked>=5 and observations[-1]['reportHex'] is not None
    assert last_revision==max(reports), 'Missing final remotely accepted authority'
    return dict(remoteAcceptedSnapshotsChecked=checked,maxSampledOldestUnappliedMs=maximum_age)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',required=True,type=Path)
    parser.add_argument('--evidence',required=True,type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    assert not (root/'.agent-workflow/STOP').exists()
    assert args.binary.is_file()
    os.umask(0o077)
    args.evidence.mkdir(parents=True,exist_ok=True)
    source = root/'twitch-screen-relay'
    scratch = Path(tempfile.mkdtemp(prefix='firmware-writer-owner-',dir=os.environ['TMPDIR']))/'relay'
    scratch.mkdir()
    for name in ('src','resources'):
        shutil.copytree(source/name,scratch/name)
    helper = Path('test/src/twitchscreen/relay/device/TestDevice.scala')
    (scratch/helper).parent.mkdir(parents=True)
    shutil.copy2(source/helper,scratch/helper)
    for name in ('build.mill','mill','.scalafmt.conf'):
        shutil.copy2(source/name,scratch/name)
    manifest = []
    for p in sorted(scratch.rglob('*')):
        if p.is_file():
            assert p.read_bytes()==(source/p.relative_to(scratch)).read_bytes(), 'Concurrent snapshot change'
            manifest.append(dict(path=str(p.relative_to(scratch)),sha256=hashlib.sha256(p.read_bytes()).hexdigest()))
    (args.evidence/'source-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    shutil.copy2(Path(__file__).with_name('RelayWriterPairing.scala'),scratch/'test/src/twitchscreen/relay/device/RelayWriterPairing.scala')
    command = ['./mill','--no-server','-j','1','test.runMain','twitchscreen.relay.device.RelayWriterPairing',
               str(args.binary.resolve()),str(args.evidence.resolve())]
    env = {k:v for k,v in os.environ.items() if not k.startswith(('RELAY_','FIRMWARE_PEER_','OTEL_'))}
    with (args.evidence/'owner.log').open('w') as log:
        result = subprocess.run(command,cwd=scratch,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=500)
    assert result.returncode==0, ('Actual owner command',result.returncode)
    records = [json.loads(line) for line in (args.evidence/'native-observations.jsonl').read_text().splitlines()]
    schedule = [json.loads(line) for line in (args.evidence/'control-schedule.jsonl').read_text().splitlines()]
    verdict = verify_control(records,schedule)
    kernel = json.loads((args.evidence/'kernel-writer.json').read_text())
    assert kernel['passed'] and kernel['actualKernelWrite'] and not kernel['injectedWriteFailure']
    final = next(r for r in reversed(records) if r['event']=='device_report_written')
    frame = bytes.fromhex((args.evidence/'remote-final-report.hex').read_text())
    layouts = json.loads((root/'protocol/desk-v1-layouts.json').read_text())['records']
    layout = next(r for r in layouts if r['name']=='DEVICE_REPORT')
    remote_observations = [json.loads(line) for line in (args.evidence/'remote-report-observations.jsonl').read_text().splitlines()]
    verdict.update(verify_remote(records,schedule,remote_observations,layout))
    assert len(frame)==252 and frame[3]==6
    decoded = {f['name']:int.from_bytes(frame[8+f['offset']:8+f['offset']+f['width']],'little') for f in layout['fields'] if f['offset']<100}
    for raw,local in (('reportRevision','revision'),('grantTotal','grantTotal'),('processedSeq','processedSeq'),
                      ('enqueuedSeq','enqueuedSeq'),('displayRevision','displayRevision'),('sourceGeneration','sourceGeneration'),
                      ('twitchEpisode','twitchEpisode'),('baseScreen','baseScreen'),('reason','reason'),
                      ('activePhase','phase'),('pendingCount','pending'),('suppressedTotal','suppressedTotal')):
        assert decoded[raw]==final[local], ('Final remote report mismatch',raw)
    owner = json.loads((args.evidence/'owner-result.json').read_text())
    assert owner['passed'] and owner['ownedChildReaped'] and owner['ownedListenerClosed']
    verdict.update(kernelWriter=kernel,remoteFinalReportAccepted=True,owner=owner,scratchSource=str(scratch))
    (args.evidence/'verified.json').write_text(json.dumps(verdict,indent=2)+'\n')
    print(json.dumps(verdict,indent=2))


if __name__=='__main__':
    main()
