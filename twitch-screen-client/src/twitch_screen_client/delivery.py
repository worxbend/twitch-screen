"""Bounded calibrated HTTP delivery; no automatic ambiguous POST replay."""
import asyncio
import json
import random
import re
import time

import httpx

from .clock import Calibration
from .responses import accepted, matches_submitted_cpu


def check_cancellation():
    """Retain owner cancellation even if an awaited dependency suppresses it."""
    task = asyncio.current_task()
    if task is not None and task.cancelling():
        raise asyncio.CancelledError


class Exchange:
    def __init__(self, base_url, clock):
        self.clock = clock
        self.client = httpx.AsyncClient(base_url=base_url, follow_redirects=False, trust_env=False,
                                       timeout=httpx.Timeout(3, connect=1),
                                       limits=httpx.Limits(max_connections=1, max_keepalive_connections=1))

    async def close(self):
        await self.client.aclose()

    async def request(self, method, path, payload=None):
        wire = None if payload is None else json.dumps(payload, ensure_ascii=False, allow_nan=False,
                                                       separators=(',', ':')).encode()
        if wire is not None and len(wire) > 16384:
            raise ValueError('outbound request exceeds 16 KiB')
        sent = self.clock()
        async with asyncio.timeout(3):
            async with self.client.stream(method, path, content=wire,
                                          headers={'Content-Type': 'application/json', 'Accept-Encoding': 'identity'} if wire is not None
                                          else {'Accept-Encoding': 'identity'}) as response:
                if response.headers.get('content-encoding', 'identity') != 'identity':
                    raise ValueError('encoded response refused')
                body = bytearray()
                async for chunk in response.aiter_raw(chunk_size=4096):
                    if len(body) + len(chunk) > 65536:
                        raise ValueError('response exceeds 64 KiB')
                    body.extend(chunk)
                received = self.clock()
                return response.status_code, dict(response.headers), bytes(body), sent, received


def strict_json(body):
    def pairs(items):
        value = {}
        for key, item in items:
            if key in value:
                raise ValueError('duplicate JSON key')
            value[key] = item
        return value
    return json.loads(body, object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite JSON')))


def no_store(value):
    # Commas inside quoted extension values are not directive boundaries.
    parts = []
    start, quoted, escaped = 0, False, False
    for index, char in enumerate(value):
        if escaped:
            escaped = False
        elif quoted and char == '\\':
            escaped = True
        elif char == '"':
            quoted = not quoted
        elif char == ',' and not quoted:
            parts.append(value[start:index])
            start = index + 1
    if quoted or escaped:
        return False
    parts.append(value[start:])
    return any(part.strip().lower() == 'no-store' for part in parts)


class Delivery:
    def __init__(self, config, clock, cards, status, active_clock=None):
        self.config, self.clock, self.cards, self.status = config, clock, cards, status
        self.active_clock = active_clock or (lambda: time.monotonic_ns() // 1000000)
        self.card_event = asyncio.Event()
        self.cpu_event = asyncio.Event()
        self.cpu = None
        self.calibration = None
        self.calibration_lock = asyncio.Lock()
        self.exchanges = [Exchange(config.relay_url, clock), Exchange(config.relay_url, clock)]

    def submit(self, card):
        accepted = self.cards.offer(card)
        self.status['notification_refused'] = self.cards.refused
        self.status['notification_queue_expired'] = self.cards.expired
        if accepted:
            self.card_event.set()
        return accepted

    def sample(self, measured, values):
        self.cpu = (measured + self.config.freshness_ms,
                    {'sourceId': self.config.source_id, 'host': self.config.host,
                     'freshnessMs': self.config.freshness_ms, **values})
        self.cpu_event.set()

    async def clock_exchange(self, exchange, method, path, payload=None):
        boot, active = self.clock(), self.active_clock()
        result = await exchange.request(method, path, payload)
        check_cancellation()
        now, active_now = self.clock(), self.active_clock()
        if abs((now - boot) - (active_now - active)) > 10:
            self.calibration = None
            raise ValueError('clock discontinuity during exchange')
        return result, now, active_now

    def invalidate(self, calibration):
        # A late response owns only the sample used to prepare its request.
        if self.calibration is calibration:
            self.calibration = None

    async def calibrated(self, exchange):
        async with self.calibration_lock:
            if self.calibration is not None and self.calibration.usable(self.clock(), self.active_clock()):
                return self.calibration
            self.calibration = None
            result, now, active = await self.clock_exchange(exchange, 'GET', '/api/v1/clock')
            code, headers, body, sent, received = result
            value = strict_json(body)
            if (code != 200 or not no_store(headers.get('cache-control', '')) or not isinstance(value, dict)
                    or set(value) != {'relayEpoch', 'monotonicMs'}):
                raise ValueError('clock endpoint unavailable')
            self.calibration = Calibration.checked(value['relayEpoch'], value['monotonicMs'], sent, now, active)
            return self.calibration

    async def upload(self, exchange, method, path, payload, deadline, observation=None):
        # Calibration failure cannot fall back to receipt-aged lifetime.
        try:
            calibration = await self.calibrated(exchange)
            # Calibration and its shared lock can outlive the unsent CPU slot.
            # No await separates this identity/freshness check from request start.
            if observation is not None and self.cpu is not observation:
                return 'superseded'
            if observation is not None and deadline <= self.clock():
                return 'expired'
            upload = {**payload, 'deadline': calibration.deadline(deadline, self.clock(), self.active_clock())}
            previous = getattr(exchange, 'deadline_cap', None)
            if previous is not None and previous[:2] == (deadline, calibration.epoch):
                upload['deadline']['monotonicMs'] = min(previous[2], upload['deadline']['monotonicMs'])
            exchange.deadline_cap = (deadline, calibration.epoch, upload['deadline']['monotonicMs'])
        except (httpx.HTTPError, TimeoutError, ValueError, UnicodeError, RecursionError):
            self.status['relay'] = 'clock_unavailable'
            return 'retry'
        try:
            result, now, active = await self.clock_exchange(exchange, method, path, upload)
            code, headers, body, sent, received = result
        except (httpx.ConnectError, httpx.ConnectTimeout):
            self.status['relay'] = 'connect_failed'
            return 'retry'  # Confirmed pre-send only.
        except (httpx.HTTPError, TimeoutError, ValueError, UnicodeError):
            self.status['relay'] = 'response_unavailable'
            return 'ambiguous' if method == 'POST' else 'retry'
        try:
            value = strict_json(body)
            if not isinstance(value, dict):
                raise ValueError('object response required')
        except (ValueError, UnicodeError, RecursionError):
            # Malformed success/error bodies are not healthy delivery. Invalidate
            # only this request's sample; another worker may have refreshed it.
            self.invalidate(calibration)
            self.status['relay'] = 'invalid_response'
            return 'ambiguous' if method == 'POST' else 'retry'
        if code == 200:
            try:
                valid = value.get('relayEpoch') == calibration.epoch and (
                    matches_submitted_cpu(value, payload) if method == 'PUT' else accepted(value, method, payload))
            except (ValueError, TypeError, OverflowError, RecursionError):
                valid = False
            if not valid:
                self.invalidate(calibration)
                self.status['relay'] = 'invalid_response'
                return 'ambiguous' if method == 'POST' else 'retry'
            self.status['relay'] = 'available'
            # Sampled success headers may refresh only the accepted epoch.
            try:
                epoch = headers['x-relay-epoch']
                if epoch != calibration.epoch:
                    raise ValueError('header epoch mismatch')
                if self.calibration is not calibration or not calibration.usable(now, active):
                    raise ValueError('superseded or interrupted calibration')
                sample = headers['x-relay-monotonic-ms']
                if not re.fullmatch('[0-9]{1,16}', sample):
                    raise ValueError('invalid monotonic sample header')
                self.calibration = Calibration.checked(epoch, int(sample), sent, now, active)
            except (KeyError, ValueError):
                # Commit acceptance is still final; only future work recalibrates.
                self.invalidate(calibration)
            return 'accepted'
        safe = {409: 'clock_epoch_changed', 410: 'expired', 429: 'rate_limited'}
        if code in safe and value.get('code') == safe[code] and isinstance(value.get('error'), str):
            if code == 409:
                self.invalidate(calibration)
            return 'expired' if code == 410 else 'retry'
        if 400 <= code < 500:
            self.status['relay'] = 'contract_rejected'
            return 'rejected'
        self.status['relay'] = 'uncertain_response'
        return 'ambiguous' if method == 'POST' else 'retry'

    async def notifications(self):
        exchange = self.exchanges[0]
        tokens, token_time = 4.0, self.clock()
        while True:
            self.card_event.clear()
            card = self.cards.take()
            if card is None:
                await self.card_event.wait()
                continue
            backoff = 1
            while card.deadline_ms > self.clock():
                now = self.clock()
                tokens = min(4, tokens + max(0, now - token_time) / 500)
                token_time = now
                if tokens < 1:
                    await asyncio.sleep((1 - tokens) / 2)
                    continue
                tokens -= 1
                outcome = await self.upload(exchange, 'POST', '/api/v1/notifications', card.payload, card.deadline_ms)
                check_cancellation()
                if outcome != 'retry':
                    key = {'accepted': 'notification_accepted', 'ambiguous': 'notification_ambiguous',
                           'expired': 'notification_expired', 'rejected': 'notification_rejected'}[outcome]
                    self.status[key] += 1
                    break
                await asyncio.sleep(min(backoff * random.uniform(1, 1.25),
                                        max(0, card.deadline_ms - self.clock()) / 1000))
                backoff = min(30, backoff * 2)
            else:
                self.status['notification_expired'] += 1

    async def wait_cpu_retry(self, delay):
        # New measurements wake expiry pruning, never reset or bypass backoff.
        retry_at = self.clock() + int(delay * 1000)
        while True:
            self.cpu_event.clear()
            now = self.clock()
            observation = self.cpu
            if observation is not None and observation[0] <= now:
                self.cpu = None
                self.status['cpu_expired'] += 1
                observation = None
            if now >= retry_at:
                return
            wake_at = min(retry_at, observation[0]) if observation is not None else retry_at
            try:
                async with asyncio.timeout((wake_at - now) / 1000):
                    await self.cpu_event.wait()
            except TimeoutError:
                pass

    async def metrics(self):
        exchange = self.exchanges[1]
        backoff = 1
        while True:
            self.cpu_event.clear()
            observation = self.cpu
            if observation is None:
                await self.cpu_event.wait()
                continue
            deadline, payload = observation
            if deadline <= self.clock():
                if self.cpu is observation:
                    self.cpu = None
                self.status['cpu_expired'] += 1
                continue
            outcome = await self.upload(exchange, 'PUT', '/api/v1/cpu', payload, deadline,
                                        observation=observation)
            check_cancellation()
            if outcome == 'superseded':
                continue  # Prepare the latest slot without resetting failure backoff.
            if outcome in ('accepted', 'expired', 'rejected'):
                self.status['cpu_accepted' if outcome == 'accepted' else 'cpu_dropped'] += 1
                if self.cpu is observation:
                    self.cpu = None
                backoff = 1
            else:
                await self.wait_cpu_retry(backoff * random.uniform(1, 1.25))
                backoff = min(30, backoff * 2)

    async def close(self):
        # Each owned client gets a close attempt even if another fails or stalls.
        results = await asyncio.gather(*(exchange.close() for exchange in self.exchanges),
                                       return_exceptions=True)
        for result in results:
            if isinstance(result, BaseException):
                raise result
