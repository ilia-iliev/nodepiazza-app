# Privacy Policy

**App:** nodepiazza
**Last updated:** 14 May 2026
**Contact:** ilia.agentov@gmail.com

nodepiazza is built to keep your data on your device. There are no nodepiazza
servers, no accounts, and no analytics. This policy explains exactly what data
the app handles and where it goes.

## The short version

- nodepiazza has **no backend**. We never receive, store, or have access to
  your data.
- Your interests and a random device identifier are shared **directly with
  nearby devices over Bluetooth** so the app can find shared interests.
- Chat messages are sent **directly to the other device over Bluetooth** and
  are held only in memory — they are not written to disk and are gone when the
  app closes.
- The only internet connection the app makes is to download the on-device
  language model. No personal data is sent in that request.

## Data you provide

- **Interests** — short text labels you add. These are stored on your device
  and broadcast over Bluetooth to nearby devices running nodepiazza so it can
  detect shared interests.
- **About me** — an optional free-text note. This is stored **only on your
  device** and is never transmitted to anyone. It is used locally to improve
  on-device matching.

## Data shared with nearby devices

When Bluetooth is enabled, nodepiazza shares the following with other nodepiazza
devices in Bluetooth range:

- Your list of interests.
- A **random device identifier** generated when you install the app. It is not
  linked to your name, phone number, account, or hardware identifiers. Removing
  and reinstalling the app generates a new one.
- Chat messages you choose to send, delivered directly to the recipient's
  device.

This exchange is peer-to-peer over Bluetooth Low Energy. It does not pass
through any server.

## Data stored on your device

- Interests, the "about me" note, your Bluetooth on/off preference, the random
  device identifier, the list of device identifiers you have blocked, and
  whether you have accepted the in-app policies.
- The downloaded language model file.

Chat history is **not** persisted — it exists only in memory while the app is
running. Uninstalling the app removes everything listed above.

## Internet use

The app connects to the internet for one purpose: to download the on-device
language model from a public model host (Hugging Face). This request downloads
a file; it does not send your interests, messages, or any personal data.

## Permissions

- **Bluetooth (scan, advertise, connect)** — to discover nearby devices and
  exchange interests and messages. Bluetooth scanning is configured to not
  derive physical location.
- **Notifications** — to tell you when a nearby device matches your interests.
- **Foreground service** — to keep Bluetooth discovery and the model download
  running reliably. A persistent notification is shown while it is active.

nodepiazza does not request or use precise or coarse location.

## Reporting and blocking

If you report another user from within the app, nodepiazza opens your email app
with a pre-filled message addressed to us. That message contains the reported
device's random identifier and the recent conversation, so we can review it.
**Nothing is sent until you press send in your email app.** Reports are
reviewed manually by the developer.

Blocking a user is entirely local to your device.

## Third parties

nodepiazza contains no advertising SDKs, no analytics SDKs, and no third-party
tracking. The only external service contacted is the public model host used for
the one-time model download.

## Children

nodepiazza is not directed to children and is intended for adults. We do not
knowingly collect data from children.

## Changes

If this policy changes, the "Last updated" date above will change and the
revised policy will be published at this URL.

## Contact

Questions about this policy or about your data: ilia.iliev94@gmail.com
