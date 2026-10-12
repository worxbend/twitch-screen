#pragma once
#include "generic_state.h"
namespace generic {
enum class FetchError : uint8_t { None, Dns, Tls, Deadline, Http, Headers, TooLarge, Truncated, Redirect, Json, Jpeg, Memory, Clock, Cancelled };
const char *errorName(FetchError error);
struct Url { char host[64] = {}, path[384] = {}; };
bool photoUrl(const char *input, Url &url);
struct Response { int status = 0; size_t size = 0; char location[448] = {}; };
// read: byte 0..255, EOF -1, deadline/cancellation -2. No allocations.
using ReadByte = int (*)(void *context);
FetchError readResponse(ReadByte read, void *context, uint8_t *body, size_t capacity, Response &response);
// Strict bounded JSON document, semantic lookup by keys (never comma positions).
bool parseWeather(const uint8_t *data, size_t size, Weather &weather, uint32_t &sampleEpoch);
bool validateJpeg(const uint8_t *data, size_t size);
struct DecodeMemory { size_t peak = 0, live = 0; bool exhausted = false; };
// Single worker only; all temporary allocations capped including progressive coefficients.
bool decodePhoto(const uint8_t *data, size_t size, uint16_t *rgb565, DecodeMemory &memory,
                 size_t allocationLimit = DecodeLimit);
}
