"""Conservative companion-to-relay deadline conversion."""
from dataclasses import dataclass
import re

MAX_SAFE = 9007199254740991


@dataclass(frozen=True)
class Calibration:
    epoch: str
    sent_ms: int
    received_ms: int
    relay_ms: int
    active_received_ms: int | None = None

    @classmethod
    def checked(cls, epoch, relay_ms, sent_ms, received_ms, active_received_ms=None):
        if (not isinstance(epoch, str) or not re.fullmatch('[0-9a-f]{16}', epoch) or epoch == '0000000000000000'
                or type(relay_ms) is not int or not 0 <= relay_ms <= MAX_SAFE
                or received_ms < sent_ms or received_ms - sent_ms > 1000):
            raise ValueError('invalid calibration')
        return cls(epoch, sent_ms, received_ms, relay_ms, active_received_ms)

    def usable(self, now, active_now=None):
        age = now - self.received_ms
        # The 10 ms sampling tolerance is inside the 50 ms conversion guard.
        uninterrupted = (self.active_received_ms is None or
                         (active_now is not None and
                          abs(age - (active_now - self.active_received_ms)) <= 10))
        return 0 <= age <= 60000 and uninterrupted

    def deadline(self, local_ms, now, active_now=None):
        if not self.usable(now, active_now) or local_ms <= now:
            raise ValueError('expired local work or calibration')
        # L+(r-d1)-50-ceil((abs(L-d1)+RTT)/1000), never renewed on retry.
        guard = 50 + (abs(local_ms - self.received_ms) + self.received_ms - self.sent_ms + 999) // 1000
        result = local_ms + self.relay_ms - self.received_ms - guard
        if not 0 <= result <= MAX_SAFE:
            raise ValueError('deadline outside JSON-safe range')
        return {'relayEpoch': self.epoch, 'monotonicMs': result}
