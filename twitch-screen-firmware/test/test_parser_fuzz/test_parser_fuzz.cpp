#include <stdio.h>
#include <string.h>
#include <vector>
#include "proto_codec.h"
#include "vectors.h"

uint32_t randomState = 0x53a70003u;
uint32_t randomWord() {
  randomState ^= randomState << 13;
  randomState ^= randomState >> 17;
  randomState ^= randomState << 5;
  return randomState;
}
struct Result { uint32_t hash, frames, resync, bytes; bool fatal; };
Result parse(const std::vector<uint8_t> &bytes, size_t maxChunk) {
  tsb::FrameReader reader;
  uint32_t hash = 2166136261u;
  size_t offset = 0;
  while (offset < bytes.size() && !reader.isFatal()) {
    const size_t chunk = maxChunk == 1 ? 1 : 1 + randomWord() % maxChunk;
    size_t end = offset + chunk;
    if (end > bytes.size()) end = bytes.size();
    while (offset < end && !reader.isFatal()) {
      const size_t used = reader.feed(bytes.data() + offset, end - offset);
      offset += used;
      if (reader.hasFrame()) {
        tsb::InboundFrame frame;
        const tsb::DecodeResult decoded = tsb::decodeInboundPayload(
            reader.header(), reader.payload(), reader.payloadLength(), frame);
        reader.noteDecode(decoded);
        hash = (hash ^ (uint32_t)decoded) * 16777619u;
        hash = (hash ^ reader.header().type) * 16777619u;
        for (size_t i = 0; i < reader.payloadLength(); ++i)
          hash = (hash ^ reader.payload()[i]) * 16777619u;
        reader.consumeFrame();
      } else if (used == 0) break;
    }
  }
  const auto &c = reader.counters();
  return {hash, c.framesDecoded, c.resyncEvents, c.bytesReceived, reader.isFatal()};
}
int main() {
  int checks = 0, failures = 0;
  // One seed, reproducible arbitrary bytes plus mutated and intact golden
  // frames. Chunk size must never alter decoding, counters, or fatal budgets.
  for (unsigned iteration = 0; iteration < 3000; ++iteration) {
    std::vector<uint8_t> bytes;
    const unsigned pieces = 1 + randomWord() % 12;
    for (unsigned piece = 0; piece < pieces; ++piece) {
      if ((randomWord() & 3) != 0) {
        const auto &v = gv::ALL_VECTORS[randomWord() % gv::ALL_COUNT];
        const size_t start = bytes.size();
        bytes.insert(bytes.end(), v.frame, v.frame + v.size);
        if ((randomWord() & 1) != 0)
          bytes[start + randomWord() % v.size] ^= (uint8_t)randomWord();
      } else {
        const unsigned count = randomWord() % 96;
        for (unsigned i = 0; i < count; ++i) bytes.push_back((uint8_t)randomWord());
      }
    }
    const Result bytewise = parse(bytes, 1), chunked = parse(bytes, 128);
    ++checks;
    if (bytewise.hash != chunked.hash || bytewise.frames != chunked.frames ||
        bytewise.resync != chunked.resync || bytewise.bytes != chunked.bytes ||
        bytewise.fatal != chunked.fatal) {
      ++failures; printf("FAIL: chunk invariance at seed iteration %u\n", iteration);
    }
  }
  printf("%d checks, %d failures\n", checks, failures);
  return failures != 0;
}
