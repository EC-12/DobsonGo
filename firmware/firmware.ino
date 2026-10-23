/*
  Useful Videos:
  https://www.youtube.com/watch?v=0Yvd_k0hbVs
  https://www.youtube.com/watch?v=s3yoZa6kzus
  https://www.youtube.com/watch?v=GY_UYr-LmjM
  https://www.youtube.com/watch?v=oCMOYS71NIU
  https://www.youtube.com/watch?v=lj99N6pW-Ew

  For generating UUIDs: https://www.uuidgenerator.net/

  both "NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC" are required because NIMBLE_PROPERTY::READ_ENC
  just tells android to not read the value, and if it readable "NIMBLE_PROPERTY::READ" then it would need ENC.

  Print formatted data
  https://cplusplus.com/reference/cstdio/printf/
*/

#include <cstdio>
#include <NimBLEDevice.h>
#include "mbedtls/md.h"

#define SCOPE_MODEL "ES Dob UL 300 Gen II"
#define CONN_KEY "CHANGE_ME" // Key used for the HMAC-Sha256 challenge

#define GOTOCTRL_SERVICE_UUID "f170c5c8-cead-4be4-b5dc-7d6f6977fed4"
#define AUTH_SERVICE_UUID "183D"
#define ENV_SERVICE_UUID "181A"

#define authChallengeCharac_UUID "358c44fb-60f8-4ccd-9dda-5459c8695681"
#define authResponseCharac_UUID "111b3755-b893-436b-b5bd-1e215aee5fed"
#define authIsClientTrustworthy_UUID "9ef29165-5a49-4a90-8031-04d84835a8c4"
#define envBatteryCharac_UUID "2A19"
#define gotoCalibCharac_UUID "eebf18e1-d441-49c2-82d4-492fd19cfc2c"

static NimBLEServer* pGServer;
uint64_t clientCount = 0;
bool isTrustworthyClientConnected = false;

const uint64_t INTERVAL_5M = 5UL * 60UL * 1000UL;
uint64_t lastRun_5M = 0;

const uint64_t INTERVAL_30S = 30UL * 1000UL;
uint64_t lastRun_30S = 0;

// Global Characs //
NimBLECharacteristic* pChallengeCharac;
NimBLECharacteristic* pBatteryCharac;
NimBLECharacteristic* pCalibCharac;
// ------ //

char* pChallenge; 
uint8_t* pAuthMac;
int battery_level = 100;
int calibStars = 0;

static void print_hex(uint8_t* buf, size_t len) {
  for (size_t i = 0; i < len; i++) {
    if (buf[i] < 16) Serial.print('0');
    Serial.print(buf[i], HEX);
  }
}

void getRandomChallenge(char* pOut) {
  uint8_t nonce[32];
  static const char ALPHABET[] =
  "!\"#$%&'()*+,-./"
  "0123456789"
  ":;<=>?@"
  "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
  "[\\]^_`"
  "abcdefghijklmnopqrstuvwxyz"
  "{|}~";

  esp_fill_random(nonce, sizeof(nonce));
  for (size_t i = 0; i < 32; i++) {
    pOut[i] = ALPHABET[nonce[i] % (sizeof(ALPHABET) - 1)];
  }
  pOut[32] = '\0';
}

bool hmac_sha256(const uint8_t* pMsg, size_t msg_len, uint8_t* pOut) {
  const char* pKey = CONN_KEY;
  size_t key_len = strlen(pKey);

  const mbedtls_md_info_t* md_info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  if (!md_info) return false;

  int rc = mbedtls_md_hmac(md_info, (const uint8_t*)pKey, key_len, pMsg, msg_len, pOut);
  return rc == 0;
}

void disconnectClient(NimBLEConnInfo& connInfo, std::string reason) {
  NimBLEDevice::getServer()->disconnect(connInfo.getConnHandle());
  Serial.printf("Forced %s to disconnect: %s\n", connInfo.getAddress().toString().c_str(), reason.c_str());
}

void create2901(NimBLECharacteristic* pCharacteristic, std::string name) {
  NimBLEDescriptor* pCdsc = pCharacteristic->createDescriptor(
    "2901",
    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC
  );
  pCdsc->setValue(name.c_str());
}

class ESP32ServerCallbacks: public NimBLEServerCallbacks {
  void onMTUChange(uint16_t MTU, NimBLEConnInfo& connInfo) override {
    Serial.printf("MTU updated to %u for client %s\n", MTU, connInfo.getAddress().toString().c_str());
  }

  void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
    if (NimBLEDevice::onWhiteList(connInfo.getAddress())) {
      isTrustworthyClientConnected = true;
    }
    clientCount++;
    NimBLEDevice::startAdvertising();
    std::string trustedStr = isTrustworthyClientConnected ? "(TRUSTED) " : "";
    Serial.printf("-------- %sCLIENT CONNECTED: %s | (%llu total) | (WL: %u) --------\n", trustedStr.c_str(), connInfo.getAddress().toString().c_str(), clientCount, NimBLEDevice::getWhiteListCount());
  }

  void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
    if (NimBLEDevice::onWhiteList(connInfo.getAddress())) {
      isTrustworthyClientConnected = false;
    }
    clientCount--;
    NimBLEDevice::startAdvertising();
    Serial.printf("-------- CLIENT DISCONNECTED: %s | (%llu left) | (WL: %u) --------\n", connInfo.getAddress().toString().c_str(), clientCount, NimBLEDevice::getWhiteListCount());
  }

  // Check that encryption (pairing) was successful, if not we disconnect the client //
  void onAuthenticationComplete(NimBLEConnInfo& connInfo) override {
    if (!connInfo.isEncrypted()) {
      disconnectClient(connInfo, "encrypt connection failed");
      return;
    }
    Serial.printf("Secured pairing connection to: %s\n", connInfo.getAddress().toString().c_str());
  }
};

class ESP32CharacteristicCallbacks: public NimBLECharacteristicCallbacks {

  void onRead(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo& connInfo) override {
    bool isClientTrustworthy = NimBLEDevice::onWhiteList(connInfo.getAddress());

    if (pCharacteristic->getUUID() == NimBLEUUID(authIsClientTrustworthy_UUID)) {
      uint8_t value = isClientTrustworthy ? 1 : 0;
      pCharacteristic->setValue(&value, 1);
    } else if (!isClientTrustworthy) {
      if (isTrustworthyClientConnected) {
        disconnectClient(connInfo, "Only one authenticated client at a time");
      } else if (pCharacteristic->getUUID() != NimBLEUUID(authChallengeCharac_UUID)) {
        disconnectClient(connInfo, "Authentication failed");
      }
    }
  }

  void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo& connInfo) override {
    bool isClientTrustworthy = NimBLEDevice::onWhiteList(connInfo.getAddress());

    if (pCharacteristic->getUUID() == NimBLEUUID(authResponseCharac_UUID)) {
      if (isTrustworthyClientConnected) {
        disconnectClient(connInfo, "Only one authentified client at a time");
      } else if (pAuthMac == nullptr) {
        disconnectClient(connInfo, "Error when calculating HMAC (server side)!");
      } else {
        const std::string& rawValue = pCharacteristic->getValue();
        if (rawValue.size() != 32) {
          disconnectClient(connInfo, "Authentication failed");
          return;
        }
        uint8_t respMac[32];
        memcpy(respMac, rawValue.data(), 32);
        Serial.printf("%s challenge MAC: ", connInfo.getAddress().toString().c_str());
        print_hex(respMac, sizeof(respMac));
        Serial.println();

        if (memcmp(pAuthMac, respMac, 32) == 0) {
          Serial.printf("%s trusted\n", connInfo.getAddress().toString().c_str());
          NimBLEDevice::whiteListAdd(connInfo.getAddress());
          isTrustworthyClientConnected = true;
        } else {
          disconnectClient(connInfo, "Authentication failed");
        }
      }
    } else if (!isClientTrustworthy) {
      if (isTrustworthyClientConnected) {
        disconnectClient(connInfo, "Only one authentified client at a time");
      } else {
        disconnectClient(connInfo, "Authentication failed");
      }
    }
  }

  void onSubscribe(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo& connInfo, uint16_t subValue) override {
    if (subValue == 1) {
      if (!connInfo.isEncrypted()) {
          disconnectClient(connInfo, "Connection not encrypted");
      } else {
        bool isClientTrustworthy = NimBLEDevice::onWhiteList(connInfo.getAddress());
        if (!isClientTrustworthy) {
          if (isTrustworthyClientConnected) {
            disconnectClient(connInfo, "Only one authentified client at a time");
          } else {
            disconnectClient(connInfo, "Authentication failed");
          }
        } else {
          pCharacteristic->notify();
        }
      }
    }
  }

} chrCallbacks;



void setup() {
  Serial.begin(9600);

  NimBLEDevice::init(SCOPE_MODEL);
  pGServer = NimBLEDevice::createServer();
  pGServer->setCallbacks(new ESP32ServerCallbacks());
  NimBLEDevice::setMTU(517);

  // ########### AUTH SERVICE ###########//
  NimBLEService* pAuthService = pGServer->createService(AUTH_SERVICE_UUID);

  pChallengeCharac = pAuthService->createCharacteristic(
    authChallengeCharac_UUID,
    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC
  );
  pChallengeCharac->setCallbacks(&chrCallbacks);
  create2901(pChallengeCharac, "challenge");
  NimBLE2904* pChallenge2904 = pChallengeCharac->create2904();
  pChallenge2904->setFormat(NimBLE2904::FORMAT_UTF8);


  NimBLECharacteristic* pResponseCharac = pAuthService->createCharacteristic(
    authResponseCharac_UUID,
    NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC
  );
  pResponseCharac->setCallbacks(&chrCallbacks);
  create2901(pResponseCharac, "response");
  NimBLE2904* pResponse2904 = pResponseCharac->create2904();
  pResponse2904->setFormat(NimBLE2904::FORMAT_UINT32);


  NimBLECharacteristic* pIsCtTwyCharac = pAuthService->createCharacteristic(
    authIsClientTrustworthy_UUID,
    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC
  );
  pIsCtTwyCharac->setCallbacks(&chrCallbacks);
  create2901(pIsCtTwyCharac, "is_client_trustworthy");
  NimBLE2904* pIsCtTwy2904 = pIsCtTwyCharac->create2904();
  pIsCtTwy2904->setFormat(NimBLE2904::FORMAT_BOOLEAN);

  // ########### GOTO SERVICE ########### //
  NimBLEService* pGotoCtrlService = pGServer->createService(GOTOCTRL_SERVICE_UUID);

  pCalibCharac = pGotoCtrlService->createCharacteristic(
    gotoCalibCharac_UUID,
    NIMBLE_PROPERTY::NOTIFY
  );
  pCalibCharac->setCallbacks(&chrCallbacks);
  pCalibCharac->setValue((uint8_t*)&calibStars, sizeof(uint8_t));
  create2901(pCalibCharac, "calibrated_stars");
  NimBLE2904* pCalib2904 = pCalibCharac->create2904();
  pCalib2904->setFormat(NimBLE2904::FORMAT_UINT8);

  // ########### ENV SERVICE ########### //
  NimBLEService* pEnvService = pGServer->createService(ENV_SERVICE_UUID);

  pBatteryCharac = pEnvService->createCharacteristic(
    envBatteryCharac_UUID,
    NIMBLE_PROPERTY::NOTIFY
  );
  pBatteryCharac->setCallbacks(&chrCallbacks);
  pBatteryCharac->setValue((uint8_t*)&battery_level, sizeof(uint8_t));
  create2901(pBatteryCharac, "battery_level");
  NimBLE2904* pBattery2904 = pBatteryCharac->create2904();
  pBattery2904->setFormat(NimBLE2904::FORMAT_UINT8);
  // ###################### //



  pAuthService->start();
  pGotoCtrlService->start();
  pEnvService->start();

  NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
  pAdvertising->setName(SCOPE_MODEL);
  pAdvertising->setManufacturerData("Terroroo's Factory");

  pAdvertising->addServiceUUID(pAuthService->getUUID());
  pAdvertising->addServiceUUID(pGotoCtrlService->getUUID());
  pAdvertising->addServiceUUID(pEnvService->getUUID());

  pAdvertising->enableScanResponse(true);
  pAdvertising->start();

  Serial.printf("Scope Started (WL: %u)\n", NimBLEDevice::getWhiteListCount());
  lastRun_5M = millis() - INTERVAL_5M;
  lastRun_30S = millis() - INTERVAL_30S;
  randomSeed(analogRead(A0));
}

void loop() {
  uint64_t now = millis();

  if (now - lastRun_5M >= INTERVAL_5M) {
    if (!isTrustworthyClientConnected) {
      lastRun_5M = now;

      delete[] pChallenge;
      pChallenge = nullptr;
      pChallenge = new char[33];  // +1 pour le '\0'

      delete[] pAuthMac;
      pAuthMac = nullptr;
      pAuthMac = new uint8_t[32];

      getRandomChallenge(pChallenge);
      bool canCalculateHmac = hmac_sha256((const uint8_t*)pChallenge, strlen(pChallenge), pAuthMac);
      if (canCalculateHmac == false) {
        Serial.println("Error when calculating HMAC!");
        delete[] pAuthMac;
        pAuthMac = nullptr;
      } else {
        Serial.printf("Refreshing credentials -> challenge: %s | MAC: ", pChallenge);
        print_hex(pAuthMac, 32);
        Serial.println();
        pChallengeCharac->setValue((const uint8_t*)pChallenge, 32);
      }
    }
  }

  if (now - lastRun_30S >= INTERVAL_30S) {
    lastRun_30S = now;
  }
}
