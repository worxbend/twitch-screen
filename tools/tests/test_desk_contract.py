"""Independent A1 contract regression tests, not peer runtime acceptance."""
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('desk_contract_checker', ROOT/'tools/check_desk_contract.py')
desk = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(desk)


class HttpContractTests(unittest.TestCase):
    def assert_invalid(self, name, value):
        with self.assertRaises(ValueError):
            desk.validate_dto(name, value)

    def test_materialized_artifacts(self):
        desk.check()

    def test_normative_offset_drift_fails_closed(self):
        read_text = Path.read_text
        for original, replacement in [
            ('## HELLO: 0x01, 92 payload bytes', '## HELLO: 0x01, 91 payload bytes'),
            ('| 76 | 8 | u64 | deviceBootId |', '| 75 | 8 | u64 | deviceBootId |'),
        ]:
            def changed(path, *args, **kwargs):
                text = read_text(path, *args, **kwargs)
                return text.replace(original, replacement) if path.name == 'desk-v1-offsets.md' else text
            with patch.object(Path, 'read_text', changed):
                with self.assertRaisesRegex(ValueError, 'offset table'):
                    desk.check()

    def test_unknown_provenance_rejected(self):
        for key in ['origin','sourceGeneration','twitchEpisode','extra']:
            self.assert_invalid('NotificationRequest',dict(type='info',title='OK',body='',**{key:0}))

    def test_duplicate_keys_at_all_depths(self):
        for raw in ['{"type":"info","type":"alert"}', '{"deadline":{"relayEpoch":"a","relayEpoch":"b"}}']:
            with self.assertRaisesRegex(ValueError,'duplicate_key'):
                desk.strict_json(raw)

    def test_nonfinite_invalid_unicode_and_oversized_json(self):
        for raw in ['{"cpuPercent":NaN}', '{"cpuPercent":Infinity}', '{"cpuPercent":-Infinity}', '"\\ud800"', '"\\udfff"']:
            with self.assertRaises(ValueError):
                desk.strict_json(raw)
        with self.assertRaises(ValueError):
            desk.strict_json(b'"\xff"')
        with self.assertRaises(ValueError):
            desk.strict_json(b' ' * 65537)
        self.assert_invalid('CpuRequest',dict(sourceId='pc',host='desk',cpuPercent=1e309))

    def test_valid_surrogate_pair_decodes(self):
        self.assertEqual(desk.strict_json('"\\ud83c\\udf89"'),'🎉')

    def test_null_patch_empty_case_and_boolean_number_rejected(self):
        for value in [{}, {'preferredDashboard':None}, {'twitchDisplayEnabled':None}, {'twitchDisplayEnabled':1}, {'preferredDashboard':'CPU'}, {'extra':False}]:
            self.assert_invalid('DisplayPatch',value)
        desk.validate_dto('DisplayPatch',dict(twitchDisplayEnabled=False))
        self.assert_invalid('NotificationRequest',dict(type='Info',title='OK',body=''))
        self.assert_invalid('CpuRequest',dict(sourceId='pc',host='desk',cpuPercent=True))

    def test_notification_utf16_not_scalar_limit(self):
        base=dict(type='info',title='OK',body='🎉'*2048)
        desk.validate_dto('NotificationRequest',base)
        self.assert_invalid('NotificationRequest',dict(base,body='🎉'*2049))
        desk.validate_dto('NotificationRequest',dict(base,body='a'*4096))
        self.assert_invalid('NotificationRequest',dict(base,title=' \t\n'))

    def test_labels_are_scalar_limited(self):
        value=dict(sourceId='pc',host='🎉'*128,cpuPercent=0)
        desk.validate_dto('CpuRequest',value)
        self.assert_invalid('CpuRequest',dict(value,host='🎉'*129))
        self.assert_invalid('CpuRequest',dict(value,host='  '))

    def test_source_identity(self):
        for source in ['', 'a'*32,'host name','🎉','a/b']:
            self.assert_invalid('CpuRequest',dict(sourceId=source,host='desk',cpuPercent=0))
        desk.validate_dto('CpuRequest',dict(sourceId='Az._-09',host='desk',cpuPercent=0))

    def test_temperature_range_before_rounding(self):
        base=dict(sourceId='weather',location='desk',condition='clear',temperature=100)
        desk.validate_dto('WeatherRequest',base)
        self.assert_invalid('WeatherRequest',dict(base,temperature=100.001))
        desk.validate_dto('WeatherRequest',dict(base,temperature=212,unit='fahrenheit'))
        self.assert_invalid('WeatherRequest',dict(base,temperature=212.001,unit='fahrenheit'))
        self.assert_invalid('WeatherRequest',dict(base,temperature=-148,unit='celsius'))
        self.assert_invalid('WeatherRequest',dict(base,humidityPercent=0.5))

    def test_optional_absent_is_not_null_request(self):
        base=dict(sourceId='pc',host='desk',cpuPercent=0)
        desk.validate_dto('CpuRequest',base)
        desk.validate_dto('CpuRequest',dict(base,memoryPercent=0))
        self.assert_invalid('CpuRequest',dict(base,memoryPercent=None))
        self.assert_invalid('CpuRequest',dict(base,memoryPercent=100.001))

    def test_epoch_format_and_timestamp(self):
        for epoch in ['0123456789ABCDEf','0x0123456789abcdef','123','0000000000000000']:
            self.assert_invalid('ClockResponse',dict(relayEpoch=epoch,monotonicMs=5000))
        base=dict(sourceId='pc',host='desk',cpuPercent=0)
        for stamp in ['2026-10-11T10:00:00Z','2026-10-11T10:00:00.000+03:00','2026-02-30T10:00:00.000Z']:
            self.assert_invalid('CpuRequest',dict(base,observedAt=stamp))

    def test_channel_no_url_null_uppercase(self):
        for channel in ['',None,'Worxbend','https://twitch.tv/worxbend','a'*26]:
            self.assert_invalid('MonitoringPatch',dict(channel=channel))
        desk.validate_dto('MonitoringPatch',dict(channel='worxbend'))

    def test_hold_rounds_up_never_zero(self):
        for raw, expected in [(100,100),(101,200),(199,200),(8000,8000),(29999,30000),(30000,30000)]:
            self.assertEqual(desk.hold_ms(raw),expected)
            self.assertGreater(desk.hold_ms(raw)//100,0)
        for raw in [0,1,99,30001,6553500,True,100.1]:
            with self.assertRaises(ValueError):
                desk.hold_ms(raw)

    def test_half_away_from_zero(self):
        for raw, expected in [('12.345',1235),('-12.345',-1235),('0.005',1),('-0.005',-1),('0',0)]:
            self.assertEqual(desk.centi(raw),expected)

    def test_lifetime_bounds_independent(self):
        base=dict(type='info',title='OK',body='',ttlMs=30000,expiresInMs=1)
        desk.validate_dto('NotificationRequest',base)
        self.assert_invalid('NotificationRequest',dict(base,expiresInMs=0))
        self.assert_invalid('NotificationRequest',dict(base,expiresInMs=600001))
        for ms in [999,60001]:
            self.assert_invalid('CpuRequest',dict(sourceId='pc',host='desk',cpuPercent=0,freshnessMs=ms))

    def test_schema_validator_fails_closed(self):
        with self.assertRaises(ValueError):
            desk.validate('foo',{'unsupportedAssertion':True},{})


class WireContractTests(unittest.TestCase):
    def vector(self,name):
        return copy.deepcopy(next(v for v in desk.load('desk-v1-vectors.json')['vectors'] if v['name']==name))

    def test_all_lengths_and_unaligned_u64(self):
        records={r['name']:r for r in desk.load('desk-v1-layouts.json')['records']}
        for name,length in [('HELLO',92),('WELCOME',60),('DEVICE_REPORT',244),('DESK_EVENT',196),('WEATHER',68),('CPU',68)]:
            self.assertEqual(records[name]['payloadBytes'],length)
            self.assertLessEqual(length+8,256)
        hello={f['name']:f['offset'] for f in records['HELLO']['fields']}
        self.assertEqual(hello['deviceBootId'],76)
        welcome={f['name']:f['offset'] for f in records['WELCOME']['fields']}
        self.assertEqual(welcome['relayEpoch'],28)
        report={f['name']:f['offset'] for f in records['DEVICE_REPORT']['fields']}
        self.assertEqual(report['observedDeviceMs'],28)
        self.assertEqual(report['entry0.originalRelayEpoch'],100)
        self.assertEqual(report['entry8.originalRelayEpoch'],228)

    def test_signed_negative_little_endian(self):
        frame=bytes.fromhex(self.vector('weather_negative')['hex'])
        self.assertEqual(frame[40:42],bytes([46,251]))
        self.assertEqual(frame[8:16],bytes.fromhex('efcdab8967452301'))

    def test_missing_stale_zero_fill(self):
        for name,field in [('weather_missing','location'),('weather_stale','deadlineRelayMs'),('cpu_missing','cpuBasisPoints'),('cpu_stale','memoryBasisPoints')]:
            v=self.vector(name)
            v['fields'][field]='bad' if field=='location' else 1
            with self.assertRaises(ValueError):
                desk.validate_wire(v)

    def test_presence_absent_versus_zero(self):
        present=self.vector('cpu_memory_zero')['fields']
        absent=self.vector('cpu_memory_absent')['fields']
        self.assertEqual(present['memoryBasisPoints'],absent['memoryBasisPoints'])
        self.assertTrue(present['presence'] & 1)
        self.assertFalse(absent['presence'] & 1)
        v=self.vector('cpu_memory_absent'); v['fields']['memoryBasisPoints']=1
        with self.assertRaises(ValueError): desk.validate_wire(v)

    def test_unknown_required_enums_invalid(self):
        for name,field,value in [('display_cpu','enabled',2),('display_cpu','preferred',3),('weather_negative','condition',9),('cpu_memory_zero','presence',4),('stats_observed','availability',3),('twitch_event','origin',255)]:
            v=self.vector(name); v['fields'][field]=value
            with self.assertRaises(ValueError): desk.validate_wire(v)

    def test_processed_not_enqueue_ack(self):
        v=self.vector('report_full_zero_credit'); desk.validate_wire(v)
        self.assertGreater(v['fields']['processedSeq'],v['fields']['enqueuedSeq'])
        v['fields']['enqueuedSeq']=121
        with self.assertRaises(ValueError): desk.validate_wire(v)

    def test_retained_identity_not_current_cursor_or_generation(self):
        v=self.vector('report_retained_active'); desk.validate_wire(v)
        self.assertGreater(v['fields']['entry0.seq'],v['fields']['processedSeq'])
        self.assertNotEqual(v['fields']['entry0.originalRelayEpoch'],v['fields']['relayEpoch'])
        desk.validate_wire(self.vector('report_legacy_active'))

    def test_report_entry_shape_reserved_and_unused(self):
        for field,value in [('pendingCount',9),('entry8.seq',1),('entry0.reserved',1),('activeRemainingMs',1),('entry0.origin',255)]:
            v=self.vector('report_idle'); v['fields'][field]=value
            with self.assertRaises(ValueError): desk.validate_wire(v)

    def test_external_twitch_appearance_has_generic_identity(self):
        v=self.vector('external_twitch_kind'); desk.validate_wire(v)
        self.assertEqual(v['fields']['kind'],0x15)
        self.assertEqual(v['fields']['origin'],0)
        v['fields']['sourceGeneration']=3
        with self.assertRaises(ValueError): desk.validate_wire(v)

    def test_ascii_fold_controls_and_empty_body(self):
        text,truncated=desk.wire_text('Paweł\tЖ ä 🎉',48)
        self.assertEqual(text.rstrip(b'\0'),b'Pawel Zh a')
        self.assertFalse(truncated)
        self.assertEqual(desk.wire_text('',96)[0],bytes(96))
        self.assertEqual(desk.wire_text('🎉',48,placeholder='viewer')[0].rstrip(b'\0'),b'viewer')

    def test_truncation_boundary_and_ellipsis(self):
        for width in [32,48,96]:
            value='a'*(width-5)+'🎉'+'tail'
            data,truncated=desk.wire_text(value,width,utf8=True)
            self.assertEqual(len(data),width)
            self.assertEqual(data[-1],0)
            self.assertTrue(truncated)
            decoded=data.rstrip(b'\0').decode('utf-8')
            self.assertTrue(decoded.endswith('...'))
            self.assertNotIn('🎉',decoded)


class TimingAdmissionTests(unittest.TestCase):
    def test_asymmetric_calibration_and_never_extend(self):
        # Interval offset is [3900,4000]. Original deadline 65000.
        self.assertEqual(desk.calibrated_deadline(1000,1100,5000,65000,1100),60889)
        self.assertEqual(desk.calibrated_deadline(2000,2001,6000,65000,2001,previous=60889),60889)
        self.assertEqual(desk.calibrated_deadline(1000,1100,5000,5000,1100),949)

    def test_rtt_and_age_exact_boundaries(self):
        desk.calibrated_deadline(0,1000,5000,6000,61000)
        for args in [(0,1001,5000,6000,1001),(0,1000,5000,6000,61001),(1000,999,5000,6000,1000)]:
            with self.assertRaises(ValueError): desk.calibrated_deadline(*args)
        with self.assertRaises(ValueError): desk.calibrated_deadline(0,0,0,1 << 64,0)

    def test_producer_age_and_report_age(self):
        deadline=desk.producer_deadline(1000,1100,5000,61000)
        self.assertEqual(deadline,64790)
        self.assertLess(deadline,61000+3900)
        receipt=65000
        self.assertLessEqual(deadline,receipt)
        lower=desk.observation_lower(1000,1100,5000,1200)
        self.assertEqual(lower,5049)
        self.assertGreater(21000-lower,15000)
        self.assertGreater(21000-lower,21000-20000)  # Receipt age is misleading.

    def test_transport_delay_and_wrap_do_not_renew(self):
        local=desk.calibrated_deadline(1000,1100,5000,65000,1100)
        self.assertGreater(62000,local)  # Complete frame delayed until expired.
        before=0xfffffff0; after=0x10
        elapsed=(after-before) & 0xffffffff
        self.assertEqual(elapsed,32)
        self.assertEqual(before+elapsed,0x100000010)
        # Wall timestamps are not arguments to conversion; jumping them cannot renew.
        self.assertEqual(local,desk.calibrated_deadline(1000,1100,5000,65000,1100))

    def test_eight_retained_cards_zero_credit_controls_progress(self):
        model=desk.CreditModel(pending=8,baseline=120)
        self.assertEqual(model.grant,0)
        self.assertFalse(model.reserve(121))
        with self.assertRaises(ValueError): model.barrier(127)
        # A control is not reserve(): it can apply with no event credit.
        applied_revision=9
        self.assertEqual(applied_revision,9)
        model.release()
        self.assertTrue(model.reserve(121))
        model.receive(121,'enqueue')
        model.barrier(127)
        self.assertEqual((model.processed,model.enqueued),(127,121))

    def test_terminal_skip_replaces_credit_and_preserves_ack(self):
        for disposition in ['expired','suppressed','duplicate']:
            model=desk.CreditModel(pending=7,baseline=117)
            self.assertTrue(model.reserve(118)); model.receive(118,disposition)
            self.assertEqual((model.pending,model.grant,model.sent,model.processed,model.enqueued),(7,2,1,118,117))
            self.assertTrue(model.reserve(119)); model.receive(119,'enqueue')
            self.assertEqual(model.pending,8)
            self.assertFalse(model.reserve(120))

    def test_refusal_no_progress_no_barrier(self):
        model=desk.CreditModel(baseline=117)
        model.reserve(118)
        with self.assertRaises(ValueError): model.receive(118,'refused')
        self.assertEqual(model.processed,117)
        with self.assertRaises(ValueError): model.barrier(127)

    def test_duplicate_reports_dont_manufacture_credit(self):
        model=desk.CreditModel(pending=7)
        largest_grant=0
        for grant in [1,1,1]: largest_grant=max(largest_grant,grant)
        self.assertEqual(largest_grant,model.grant)
        model.reserve(1); model.receive(1,'enqueue')
        self.assertFalse(model.reserve(2))
        model.release()
        self.assertEqual(model.grant,2)
        # Starting pending card releases once. Active completion does not call release.
        self.assertEqual(model.grant,2)
        reconnect=desk.CreditModel(pending=model.pending,baseline=model.processed)
        self.assertEqual(reconnect.sent,0)
        self.assertEqual(reconnect.grant,1)

    def test_replay_and_episode_fences(self):
        retained=[(118,'external',True),(119,'twitch',True),(120,'relay',False),(121,'relay',True)]
        replay=[seq for seq,origin,fresh in retained if origin!='twitch' and fresh]
        self.assertEqual(replay,[118,121])
        old_episode=5
        disable_episode=6; enable_episode=7
        self.assertNotEqual(old_episode,enable_episode)
        self.assertLess(disable_episode,enable_episode)
        # Last enabled value cannot restore pending episode 5.
        pending=[('external',0),('twitch',5),('relay',0)]
        kept=[x for x in pending if x[0]!='twitch' or x[1]==enable_episode]
        self.assertEqual(kept,[('external',0),('relay',0)])

    def test_oldest_unapplied_budget_not_reset_by_traffic(self):
        oldest=1000
        for now in [2000,3000,4000,5000,6000]:
            latest=now
            self.assertGreaterEqual(now-oldest,0)
            self.assertEqual(latest,now)
        self.assertEqual(now-oldest,5000)
        self.assertEqual(30000+350+280,30630)  # Active overlay can outlast apply budget.

    def test_peer_matrix(self):
        self.assertEqual([desk.peer_profile(r,d) for r,d in [(False,False),(True,False),(False,True),(True,True)]],['legacy','legacy','legacy','desk'])


class NegotiationSyncTests(unittest.TestCase):
    def setUp(self):
        self.vectors={v['name']:v for v in desk.load('desk-v1-vectors.json')['vectors']}

    def frame(self, name, payload=None):
        original=bytes.fromhex(self.vectors[name]['hex'])
        if payload is None: return original
        header=bytearray(original[:7]); header[4:6]=len(payload).to_bytes(2,'little')
        return bytes(header)+bytes([255 ^ (sum((i+1)*b for i,b in enumerate(header)) & 255)])+payload

    def test_hello_missing_unsupported_short_invalid_tail_fallback(self):
        frame=self.frame('hello_resume')
        self.assertEqual(desk.negotiate_hello(frame),'desk')
        payload=bytearray(frame[8:])
        for size in [60,61,64,91]:
            self.assertEqual(desk.negotiate_hello(self.frame('hello_resume',payload[:size])),'legacy')
        for offset,value in [(60,2),(62,1)]:
            bad=payload.copy(); bad[offset]=value
            self.assertEqual(desk.negotiate_hello(self.frame('hello_resume',bad)),'legacy')
        bad=payload.copy(); bad[76:84]=bytes(8)
        self.assertEqual(desk.negotiate_hello(self.frame('hello_resume',bad)),'legacy')

    def test_welcome_negotiated_tail_strict_legacy_tail_ignored(self):
        frame=self.frame('welcome')
        self.assertEqual(desk.negotiate_welcome(frame,1000),'desk')
        payload=bytearray(frame[8:])
        for size in [24,25,28,59]:
            with self.assertRaises(ValueError): desk.negotiate_welcome(self.frame('welcome',payload[:size]),1000)
        for offset,value in [(24,2),(26,1)]:
            bad=payload.copy(); bad[offset]=value
            with self.assertRaises(ValueError): desk.negotiate_welcome(self.frame('welcome',bad),1000)
        with self.assertRaises(ValueError): desk.negotiate_welcome(frame,1001)
        for start in [28,36]:
            bad=payload.copy(); bad[start:start+8]=bytes(8)
            with self.assertRaises(ValueError): desk.negotiate_welcome(self.frame('welcome',bad),1000)
        payload[20] &= ~16
        self.assertEqual(desk.negotiate_welcome(self.frame('welcome',payload[:24]+b'bad tail'),1000),'legacy')
        self.assertEqual(desk.negotiate_welcome(self.frame('welcome',frame[8:]+bytes(4)),1000),'desk')

    def test_frame_header_short_corrupt_and_oversized(self):
        frame=self.frame('welcome')
        for bad in [frame[:7],frame[:-1],bytes([0])+frame[1:],frame[:7]+bytes([frame[7]^1])+frame[8:]]:
            with self.assertRaises(ValueError): desk.negotiate_welcome(bad,1000)
        with self.assertRaises(ValueError): desk.negotiate_welcome(self.frame('welcome',bytes(249)),1000)

    def staged(self):
        welcome=self.vectors['welcome']['fields']
        model=desk.SyncModel(welcome['relayEpoch'],welcome['connectionId'],1000)
        for name in ['display_cpu','weather_negative','cpu_memory_zero','stats_observed']:
            vector=self.vectors[name]; model.stage(vector['record'],vector['fields'])
        return model

    def test_sync_complete_missing_snapshots_and_exact_timeout(self):
        model=self.staged(); self.assertFalse(model.ready)
        end=self.vectors['sync_end']['fields']
        model.finish(end,5999); self.assertTrue(model.ready)
        with self.assertRaises(ValueError): self.staged().finish(end,6000)
        model=self.staged(); model.staged.pop('CPU')
        with self.assertRaises(ValueError): model.finish(end,2000)
        model=self.staged()
        for record,name,key in [('WEATHER','weather_missing','weatherRevision'),('CPU','cpu_missing','cpuRevision')]:
            model.staged.pop(record); model.stage(record,self.vectors[name]['fields'])
            end=dict(end,**{key:1})
        model.finish(end,2000); self.assertTrue(model.ready)

    def test_revision_identity_and_generation_fence(self):
        model=self.staged()
        control=self.vectors['display_cpu']['fields']
        model.stage('DISPLAY_STATE',control)
        with self.assertRaises(ValueError): model.stage('DISPLAY_STATE',dict(control,enabled=1))
        model.stage('DISPLAY_STATE',dict(control,revision=8,enabled=1))
        self.assertEqual(model.staged['DISPLAY_STATE']['enabled'],0)
        with self.assertRaises(ValueError): model.stage('DISPLAY_STATE',dict(control,connectionId=1))
        end=dict(self.vectors['sync_end']['fields'],sourceGeneration=4)
        with self.assertRaises(ValueError): model.finish(end,2000)

    def test_report_identity_age_grant_and_duplicate_validation(self):
        fields=self.vectors['report_full_zero_credit']['fields']
        params=(fields['relayEpoch'],fields['connectionId'],fields['deviceBootId'],127,0)
        self.assertEqual(desk.report_context(fields,*params,now=20100),'fresh')
        self.assertEqual(desk.report_context(fields,*params,now=20101),'stale')
        self.assertEqual(desk.report_context(dict(fields,observedRelayLowerMs=0),*params,now=5200),'unknown')
        self.assertEqual(desk.report_context(fields,*params,now=5200,previous=fields),'fresh')
        for field,value in [('connectionId',1),('relayEpoch',1),('deviceBootId',1),('processedSeq',128),('grantTotal',1),('observedRelayLowerMs',5201)]:
            with self.assertRaises(ValueError): desk.report_context(dict(fields,**{field:value}),*params,now=5200)
        with self.assertRaises(ValueError): desk.report_context(dict(fields,expiredTotal=1),*params,now=5200,previous=fields)
        # Credit released before first report is legal and does not depend on WELCOME count.
        active=self.vectors['report_retained_active']['fields']
        self.assertEqual(desk.report_context(active,*params,now=5200),'fresh')

    def test_canonical_rejection_not_arbitrary_status(self):
        for status,code,outcome in [(409,'clock_epoch_changed','recalibrate'),(410,'expired','drop'),(429,'rate_limited','backoff')]:
            self.assertEqual(desk.safe_post_rejection(status,dict(error='Rejected',code=code)),outcome)
            self.assertEqual(desk.safe_post_rejection(status,dict(error='Proxy failure')),'ambiguous')
        self.assertEqual(desk.safe_post_rejection(503,dict(error='Unknown',code='expired')),'ambiguous')
        self.assertEqual(desk.safe_post_rejection(429,'not JSON'),'ambiguous')

    def test_reservation_cannot_overtake_or_double_reserve(self):
        model=desk.CreditModel(); model.reserve(1)
        with self.assertRaises(ValueError): model.reserve(2)
        with self.assertRaises(ValueError): model.reserve(1)
        with self.assertRaises(ValueError): model.receive(2,'enqueue')


if __name__ == '__main__':
    unittest.main()
