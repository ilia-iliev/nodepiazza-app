# nodepiazza-phase3

> Work in progress.

An Android app that finds nearby people looking for the same thing — over Bluetooth Low Energy, with no servers or internet.

You write what you're seeking ("tennis partner", "selling a road bike", "anyone want espresso?"). Other devices running the app within BLE range do the same. When two devices' prompts are similar enough, they unlock a direct peer-to-peer chat.

## How it works

Each phone runs both halves of BLE at once:

- **GATT server + advertiser** — exposes a service with a readable embedding characteristic and a writable chat characteristic.
- **Scanner + GATT client** — discovers peers advertising the same service UUID, reads their embedding, and writes chat messages to them.

Prompts are embedded on-device and compared against peers'; matched peers become tappable and open a chat.

## Project layout

```
app/src/main/java/com/nodepiazza/phase3/
├── Protocol.kt           # UUIDs, embedding, payload codec
├── AppState.kt           # Prompts, peers, chat state
├── Services.kt           # Service locator (embedder, llm, ble)
├── EmbeddingService.kt   # Embedding match interface + stub
├── LlmService.kt         # LLM match interface + stub
├── ble/
│   ├── BleCore.kt        # Scan, advertise, GATT server + client
│   └── BleScanService.kt # Foreground service + match notifications
└── ui/
    ├── MainActivity.kt   # Activity + permission-gated RootScreen
    ├── Permissions.kt    # Runtime permission helpers + gate UI
    ├── MainScreen.kt     # Prompts and nearby peers list
    └── ChatScreen.kt     # Per-peer chat
```

## Build

```
./gradlew :app:installDebug
```

Needs two real devices with BLE peripheral support (most emulators won't work).
