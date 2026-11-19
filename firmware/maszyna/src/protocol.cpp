#include "protocol.h"

namespace ss {

static void put16(uint8_t* d, uint16_t v) { d[0] = v & 0xFF; d[1] = v >> 8; }
static void put32(uint8_t* d, uint32_t v) {
  d[0] = v & 0xFF; d[1] = (v >> 8) & 0xFF; d[2] = (v >> 16) & 0xFF; d[3] = (v >> 24) & 0xFF;
}

uint8_t crc(const uint8_t* frame, size_t total) {
  uint32_t sum = 0;
  for (size_t i = 2; i + 1 < total; i++) sum += frame[i];
  return sum & 0xFF;
}

static bool decodeBase(const uint8_t* d, Command& c) {
  uint8_t n = d[4];
  if (n < 1 || n > MAX_SECTIONS) return false;
  c.counter = d[0];
  c.mainOpen = d[1] & 1;
  c.lights = d[1] & 2;
  c.threePin = d[1] & 4;
  c.sectionCount = n;
  c.sections = (uint16_t)(d[2] | (d[3] << 8)) & (uint16_t)((1u << n) - 1);
  c.twoPinDriveMs = d[5] == 0 ? 3000 : d[5] * 100;
  return true;
}

bool decodeCommand(const uint8_t* d, size_t n, Command& c) {
  if (n < 8) return false;
  Command t;
  if (!decodeBase(d, t)) return false;
  t.regPulseMs = (int16_t)(d[6] | (d[7] << 8));
  c = t;
  return true;
}

void encodeStatusData(const Status& s, uint8_t d[20]) {
  d[0] = s.counter;
  d[1] = (s.linkOk ? 1 : 0) | (s.mainOpen ? 2 : 0) | (s.lights ? 4 : 0) | (s.failsafe ? 8 : 0);
  put32(d + 2, s.pulseCount);
  put32(d + 6, s.pulsePeriodUs);
  int32_t p = s.pressureCentiBar;
  put16(d + 10, p < 0 ? 0xFFFF : (uint16_t)(p > 0xFFFE ? 0xFFFE : p));
  put16(d + 12, s.sectionFaults);
  d[14] = s.faults;
  d[15] = s.supplyDeciVolts;
  int32_t v = s.speedCentiKmh;
  put16(d + 16, v < 0 ? 0xFFFF : (uint16_t)(v > 0xFFFE ? 0xFFFE : v));
  d[18] = s.sats;
  d[19] = s.fix;
}

size_t buildStatusFrame(const Status& s, uint8_t out[26]) {
  out[0] = 0x80; out[1] = 0x81; out[2] = SOURCE_MACHINE; out[3] = PGN_STATUS; out[4] = 20;
  encodeStatusData(s, out + 5);
  out[25] = crc(out, 26);
  return 26;
}

bool decodeCanCmd(const uint8_t* d, uint8_t len, Command& c) {
  if (len < 6) return false;
  Command t = c;
  if (!decodeBase(d, t)) return false;
  c = t;
  return true;
}

bool decodeCanValve(const uint8_t* d, uint8_t len, uint8_t& counter, int16_t& pulseMs) {
  if (len < 3) return false;
  counter = d[0];
  pulseMs = (int16_t)(d[1] | (d[2] << 8));
  return true;
}

void encodeCanFlow(const Status& s, uint8_t out[8]) {
  uint8_t d[20];
  encodeStatusData(s, d);
  for (int i = 0; i < 8; i++) out[i] = d[2 + i];
}

void encodeCanState(const Status& s, uint8_t out[8]) {
  uint8_t d[20];
  encodeStatusData(s, d);
  out[0] = d[0]; out[1] = d[1]; out[2] = d[10]; out[3] = d[11];
  out[4] = d[12]; out[5] = d[13]; out[6] = d[14]; out[7] = d[15];
}

void encodeCanGps(const Status& s, uint8_t out[4]) {
  uint8_t d[20];
  encodeStatusData(s, d);
  for (int i = 0; i < 4; i++) out[i] = d[16 + i];
}

bool FrameParser::feed(uint8_t b) {
  if (pos_ == 0) {
    if (b == 0x80) buf_[pos_++] = b;
    return false;
  }
  if (pos_ == 1) {
    if (b == 0x81) buf_[pos_++] = b;
    else pos_ = (b == 0x80) ? 1 : 0;
    return false;
  }
  buf_[pos_++] = b;
  if (pos_ < 5) return false;
  size_t total = (size_t)buf_[4] + 6;
  if (pos_ < total) return false;
  pos_ = 0;
  if (buf_[total - 1] != crc(buf_, total)) {
    crcErrors_++;
    return false;
  }
  return true;
}

}  // namespace ss
