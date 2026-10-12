"""Finite in-memory handoff; capture never waits on HTTP."""
from collections import deque


class CardQueue:
    def __init__(self, clock):
        self.clock = clock
        self.cards = deque()
        self.bytes = 0
        self.expired = 0
        self.refused = 0

    def discard_expired(self):
        now = self.clock()
        retained = deque()
        for card in self.cards:
            if card.deadline_ms <= now:
                self.bytes -= card.encoded_bytes
                self.expired += 1
            else:
                retained.append(card)
        self.cards = retained

    def offer(self, card):
        self.discard_expired()
        if card.deadline_ms <= self.clock():
            self.expired += 1
            return False
        if len(self.cards) >= 32 or self.bytes + card.encoded_bytes > 65536:
            self.refused += 1
            return False
        self.cards.append(card)
        self.bytes += card.encoded_bytes
        return True

    def take(self):
        self.discard_expired()
        if not self.cards:
            return None
        card = self.cards.popleft()
        self.bytes -= card.encoded_bytes
        return card
