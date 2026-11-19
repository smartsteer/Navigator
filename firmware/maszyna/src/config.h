// Ustawienia płytki Maszyna (piny wg hardware/maszyna/kicad/maszyna.py i hardware/maszyna/README.md).
#pragma once
#include "sensors.h"

#define SS_FW_VERSION "0.1 (etap 18a)"
#define SS_BT_NAME "SmartSteer Maszyna"

// SPI do łańcucha 6 × 74HC595 (wyjścia mostków) i 74HC165 (nFAULT)
#define PIN_SCK 18
#define PIN_MOSI 23
#define PIN_MISO 19
#define PIN_RCLK 5     // zatrzaśnięcie 74HC595
#define PIN_PL 13      // ~PL 74HC165 (0 = wczytaj nFAULT)
#define PIN_NSLEEP 4   // nSLEEP wszystkich DRV8962 i DRV8701E (0 = uśpione = wszystko wyłączone)
#define PIN_SDA 21
#define PIN_SCL 22
#define PIN_CAN_TX 32
#define PIN_CAN_RX 33
#define PIN_GPS_RX 16
#define PIN_GPS_TX 17
#define PIN_FLOW 35    // wejście tylko, filtr RC + 74LVC1G17 na płytce
#define PIN_LAMP 25    // IN BTS50015
#define PIN_LED_G 26
#define PIN_LED_R 27
#define PIN_MOT_PWM 14 // silnik 15 A (DRV8701E EN) — w 18a stale 0 (hamowanie)
#define PIN_MOT_DIR 15
#define PIN_JP_CAN 36  // zworka: założona (0) = bez CAN, tylko USB / Bluetooth

#define ADS1115_ADDR 0x48
#define GPS_BAUD 9600

// Czujnik ciśnienia — do czasu ustawień z Navigatora (etap 18b) wpisane tutaj.
static const ss::PressureInput PRESSURE_INPUT = ss::PressureInput::VOLT_05_45;
static const float PRESSURE_RANGE_BAR = 10.0f;
