# Vision

Help people in the same physical space discover what they have in common.

Core beliefs:

- **Face-to-Face interaction** - The most meaningful form of interacting with other humans
- **Shared interests** - Talk about real interests. Meaningful interaction, not weather small talk.
- **You are in control** - You can explicitly see and control your interests
- **Encounters are private** - You decide if an encounter was meaningful, nodepiazza only tries to bring the encounters to you

# nodepiazza-app

An Android app that finds nearby people looking sharing an interest — over Bluetooth, no servers, no internet.

You have your profile of what interests you. Other devices running the app within BLE range do the same. When two devices' share an interest, they unlock a direct peer-to-peer chat.

## How it works

Each phone runs both halves of BLE at once:

- **GATT server + advertiser** — exposes a service with a readable interests and a writable chat characteristic.
- **Scanner + GATT client** — discovers peers advertising the same UUID and writes chat messages to them.

Interests and compared against peers'; matched peers become tappable and open a chat.

## Project layout

```
app/src/main/java/com/nodepiazza/phase3/
├── Protocol.kt           # UUIDs, payload codec
├── AppState.kt           # Prompts, peers, chat state
├── Services.kt           # Service locator (llm, ble)
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
