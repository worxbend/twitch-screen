"""Privacy-aware submission mapping, with no action/icon/hint forwarding."""
from dataclasses import dataclass
from html.parser import HTMLParser
import json
import unicodedata

from .config import Config


class Text(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style'):
            self.hidden += 1
        elif not self.hidden:
            self.parts.append(' ')

    def handle_endtag(self, tag):
        if tag in ('script', 'style'):
            self.hidden = max(0, self.hidden - 1)
        elif not self.hidden:
            self.parts.append(' ')

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def bounded_text(text, units=4096):
    text = ' '.join(''.join(' ' if unicodedata.category(c).startswith('C') else c for c in text).split())
    # UTF-16 units differ from Unicode scalar count. Never split a surrogate pair.
    result = []
    used = 0
    for char in text:
        width = 2 if ord(char) > 0xffff else 1
        if used + width > units:
            break
        result.append(char)
        used += width
    return ''.join(result)


def plain(value, units=4096):
    parser = Text()
    parser.feed(value)
    return bounded_text(''.join(parser.parts), units)


@dataclass(frozen=True)
class Card:
    payload: dict
    deadline_ms: int
    encoded_bytes: int


def normalize(body, config: Config, now_ms):
    if len(body) != 8:
        return None
    app, replacement, icon, summary, text, actions, hints, timeout = body
    if not isinstance(app, str) or not config.permits(app):
        return None
    if not all(isinstance(value, str) for value in (summary, text)):
        return None
    if type(timeout) is not int or not isinstance(hints, dict):
        return None
    urgency = hints.get('urgency')
    urgency = getattr(urgency, 'value', 1) if getattr(urgency, 'signature', None) == 'y' else 1
    kind = {0: 'info', 1: 'message', 2: 'alert'}.get(urgency, 'message')
    title = plain(summary) or plain(app) or 'Notification'
    content = plain(text) if config.forward_body else ''
    content = bounded_text(plain(app, 128) + ': ' + content) if content else ''
    lifetime = 60000 if timeout <= 0 else min(60000, max(1000, timeout))
    payload = {'type': kind, 'title': title, 'body': content, 'ttlMs': 8000, 'expiresInMs': lifetime}
    size = len(json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode())
    # Leave room for the calibrated deadline; refuse oversize rather than log content.
    if size + 128 > 16384:
        return None
    return Card(payload, now_ms + lifetime, size)
