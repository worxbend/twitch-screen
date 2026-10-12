// Independent DESK/1 byte encoder. No firmware headers or generated layouts.
// Compile with C++11; fixture path is argv[1]. This is not a runtime codec.
#include <cstdint>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

struct Record { unsigned code; unsigned length; std::string widths; };
static std::vector<std::string> split(const std::string& s, char delimiter) {
  std::vector<std::string> result;
  std::string part;
  std::istringstream input(s);
  while (std::getline(input, part, delimiter)) result.push_back(part);
  if (!s.empty() && s.back() == delimiter) result.push_back("");
  return result;
}
static std::vector<uint8_t> unhex(const std::string& s) {
  if (s.size() % 2) throw std::runtime_error("odd hex");
  std::vector<uint8_t> result;
  for (size_t i = 0; i < s.size(); i += 2)
    result.push_back(static_cast<uint8_t>(std::stoul(s.substr(i, 2), nullptr, 16)));
  return result;
}
int main(int argc, char** argv) {
  if (argc != 2) return 2;
  std::string report = "8 8 8 4 8 8 8 4 4 4 4 4 4 4 1 1 1 1 4 4 4 4";
  for (int i = 0; i < 9; ++i) report += " 8 4 1 1 2";
  const std::map<std::string, Record> records = {
    {"HELLO", {1,92,"4 4 2 2 b32 b16 2 2 8 4 8 8"}},
    {"WELCOME", {32,60,"4 4 4 2 2 2 2 4 2 2 8 8 8 8"}},
    {"STATS", {34,60,"4 4 4 4 4 4 4 2 1 1 8 8 4 4 1 b3"}},
    {"TIME_REQUEST", {5,8,"8"}}, {"DEVICE_REPORT", {6,244,report}},
    {"TIME_RESPONSE", {38,32,"8 8 8 8"}},
    {"DISPLAY_STATE", {39,32,"8 8 4 4 4 1 1 2"}},
    {"WEATHER", {40,68,"8 8 4 8 1 1 1 1 -2 1 1 b32"}},
    {"CPU", {41,68,"8 8 4 8 1 1 2 2 -2 b32"}},
    {"SYNC_END", {42,40,"8 8 4 4 4 4 4 4"}},
    {"DESK_EVENT", {43,196,"4 4 4 b4 2 2 1 1 1 1 b48 b96 8 4 4 8 1 b3"}},
    {"PROGRESS_BARRIER", {44,20,"8 8 4"}}
  };
  std::ifstream input(argv[1]);
  if (!input) return 2;
  std::string line;
  unsigned count = 0;
  while (std::getline(input, line)) {
    const auto columns = split(line, '\t');
    if (columns.size() != 5) throw std::runtime_error("TSV shape");
    const Record& r = records.at(columns[1]);
    const auto values = split(columns[4], ',');
    const auto widths = split(r.widths, ' ');
    if (values.size() != widths.size()) throw std::runtime_error("field count");
    std::vector<uint8_t> payload;
    for (size_t i = 0; i < widths.size(); ++i) {
      const std::string& token = widths[i];
      if (token[0] == 'b') {
        const size_t width = std::stoul(token.substr(1));
        auto text = unhex(values[i]);
        if (text.size() >= width) throw std::runtime_error("text bound");
        text.resize(width, 0);
        payload.insert(payload.end(), text.begin(), text.end());
      } else {
        const bool signedField = token[0] == '-';
        const unsigned width = std::stoul(signedField ? token.substr(1) : token);
        uint64_t value = signedField ? static_cast<uint64_t>(std::stoll(values[i])) : std::stoull(values[i]);
        for (unsigned j = 0; j < width; ++j) {
          payload.push_back(static_cast<uint8_t>(value & 255));
          value >>= 8;
        }
      }
    }
    if (payload.size() != r.length || payload.size() > 248) throw std::runtime_error("payload length");
    std::vector<uint8_t> frame = {167,83,3,static_cast<uint8_t>(r.code),static_cast<uint8_t>(r.length),0,static_cast<uint8_t>(std::stoul(columns[2]))};
    unsigned sum = 0;
    for (unsigned i = 0; i < frame.size(); ++i) sum += (i + 1) * frame[i];
    frame.push_back(static_cast<uint8_t>(255 ^ (sum & 255)));
    frame.insert(frame.end(), payload.begin(), payload.end());
    if (frame != unhex(columns[3])) throw std::runtime_error(columns[0] + ": independent bytes differ");
    ++count;
  }
  if (count != 23) throw std::runtime_error("vector count");
  std::cout << "C++ independent encoder: " << count << " DESK vectors passed\n";
}
