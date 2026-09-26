#include <stdio.h>
#include <string.h>
#include <vector>
#include "proto_codec.h"
#include "vectors.h"

namespace {
constexpr uint32_t FNV_OFFSET = 2166136261u;
constexpr uint32_t FNV_PRIME = 16777619u;
constexpr size_t MAX_CHUNK = 128;

uint32_t randomWord() {
  static uint32_t state = 0x53a70003u;
  state ^= state << 13;
  state ^= state >> 17;
  state ^= state << 5;
  return state;
}
uint32_t fnv(uint32_t hash, uint32_t value) { return (hash ^ value) * FNV_PRIME; }
struct Result {
  uint32_t hash;
  uint32_t frames;
  uint32_t resync;
  uint32_t bytes;
  bool fatal;
};
// Decodes the reader's current frame and folds its outcome into the hash.
uint32_t hashFrame(tsb::FrameReader &reader, uint32_t hash) {
  tsb::InboundFrame frame;
  const tsb::DecodeResult decoded = tsb::decodeInboundPayload(
      reader.header(), reader.payload(), reader.payloadLength(), frame);
  reader.noteDecode(decoded);
  hash = fnv(hash, static_cast<uint32_t>(decoded));
  hash = fnv(hash, reader.header().type);
  for (size_t i = 0; i < reader.payloadLength(); ++i) hash = fnv(hash, reader.payload()[i]);
  reader.consumeFrame();
  return hash;
}
Result parse(const std::vector<uint8_t> &bytes, size_t maxChunk) {
  tsb::FrameReader reader;
  uint32_t hash = FNV_OFFSET;
  size_t offset = 0;
  while (offset < bytes.size() && !reader.isFatal()) {
    const size_t chunk = maxChunk == 1 ? 1 : 1 + randomWord() % maxChunk;
    size_t end = offset + chunk;
    if (end > bytes.size()) end = bytes.size();
    while (offset < end && !reader.isFatal()) {
      const size_t used = reader.feed(bytes.data() + offset, end - offset);
      offset += used;
      if (reader.hasFrame()) hash = hashFrame(reader, hash);
      else if (used == 0) break;
    }
  }
  const auto &c = reader.counters();
  return {hash, c.framesDecoded, c.resyncEvents, c.bytesReceived, reader.isFatal()};
}
bool sameResult(const Result &a, const Result &b) {
  return a.hash == b.hash && a.frames == b.frames && a.resync == b.resync &&
         a.bytes == b.bytes && a.fatal == b.fatal;
}
// Appends one golden frame, sometimes with a single corrupted byte.
void appendGoldenPiece(std::vector<uint8_t> &bytes) {
  const auto &v = gv::ALL_VECTORS[randomWord() % gv::ALL_COUNT];
  const size_t start = bytes.size();
  bytes.insert(bytes.end(), v.frame, v.frame + v.size);
  if ((randomWord() & 1) != 0)
    bytes[start + randomWord() % v.size] ^= (uint8_t)randomWord();
}
void appendNoisePiece(std::vector<uint8_t> &bytes) {
  const unsigned count = randomWord() % 96;
  for (unsigned i = 0; i < count; ++i) bytes.push_back((uint8_t)randomWord());
}
std::vector<uint8_t> randomStream() {
  std::vector<uint8_t> bytes;
  const unsigned pieces = 1 + randomWord() % 12;
  for (unsigned piece = 0; piece < pieces; ++piece) {
    if ((randomWord() & 3) != 0) appendGoldenPiece(bytes);
    else appendNoisePiece(bytes);
  }
  return bytes;
}
// Appends header-proof noise and one intact golden frame, updating the oracle.
void appendOraclePiece(std::vector<uint8_t> &bytes, Result &expected) {
  const unsigned noise = randomWord() % 32;
  bytes.insert(bytes.end(), noise, 0x55);
  expected.resync += noise;
  const auto &vector = gv::ALL_VECTORS[randomWord() % gv::ALL_COUNT];
  bytes.insert(bytes.end(), vector.frame, vector.frame + vector.size);
  ++expected.frames;
  expected.bytes += vector.size;
  const auto result = vector.frame[3] < 0x20 ? tsb::DecodeResult::WrongDirection
                                           : tsb::DecodeResult::Ok;
  expected.hash = fnv(expected.hash, static_cast<uint32_t>(result));
  expected.hash = fnv(expected.hash, vector.frame[3]);
  for (size_t i = tsb::HEADER_SIZE; i < vector.size; ++i)
    expected.hash = fnv(expected.hash, vector.frame[i]);
}
}  // namespace

int main() {
  int checks = 0;
  int failures = 0;
  // One seed, reproducible arbitrary bytes plus mutated and intact golden
  // frames. Chunk size must never alter decoding, counters, or fatal budgets.
  for (unsigned iteration = 0; iteration < 3000; ++iteration) {
    const std::vector<uint8_t> bytes = randomStream();
    const Result bytewise = parse(bytes, 1);
    const Result chunked = parse(bytes, MAX_CHUNK);
    ++checks;
    if (!sameResult(bytewise, chunked)) {
      ++failures; printf("FAIL: chunk invariance at seed iteration %u\n", iteration);
    }
  }
  // Independent construction oracle: intact golden frames separated by bytes
  // that cannot begin a header. A parser that drops every frame would satisfy
  // chunk invariance above, but fails these exact count/hash/budget expectations.
  for (unsigned iteration = 0; iteration < 1000; ++iteration) {
    std::vector<uint8_t> bytes;
    Result expected = {FNV_OFFSET, 0, 0, 0, false};
    const unsigned frames = 1 + randomWord() % 16;
    for (unsigned piece = 0; piece < frames; ++piece) appendOraclePiece(bytes, expected);
    const Result got = parse(bytes, MAX_CHUNK);
    ++checks;
    if (!sameResult(got, expected)) {
      ++failures; printf("FAIL: golden/noise oracle iteration %u\n", iteration);
    }
  }
  for (unsigned noise : {4095u, 4096u, 4097u}) {
    const Result result = parse(std::vector<uint8_t>(noise, 0x55), MAX_CHUNK);
    ++checks;
    if (result.frames != 0 || result.fatal != (noise >= 4096) ||
        result.resync != (noise >= 4096 ? 4096 : noise)) {
      ++failures; printf("FAIL: resync budget oracle at %u bytes\n", noise);
    }
  }
  printf("%d checks, %d failures\n", checks, failures);
  return failures != 0;
}
