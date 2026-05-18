# Vision

Help people in the same physical space discover what they have in common.

Core beliefs:

- **Face-to-Face interaction** - The most meaningful form of interacting with other humans
- **Shared interests** - Find people that share your passion.
- **You are in control** - You can explicitly see and control your interests
- **Encounters are private** - nodepiazza only tries to bring the encounters to you; no data is collected

# nodepiazza-app

An Android app that quietly finds nearby people who share an interest with you — over Bluetooth. No servers, no accounts, no internet.

## How it works

1. You write down what you're into — music, hiking, a specific board game...
2. You can also jot down a private instruction for your local model. It stays on your phone.
3. When another phone running the app comes within Bluetooth range (~30m), the two phones swap interest lists in the background.
4. If your Gemma model thinks you share a passion, you get a notification
5. Tap them to open a chat. The messages travel directly phone-to-phone — nothing leaks

Matching runs on-device using a small language model (Gemma 4 - E2B or E4B, via LiteRT-LM), so it understands semantic interest over different languages and phrasing.
