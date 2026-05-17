# Vision

Help people in the same physical space discover what they have in common.

Core beliefs:

- **Face-to-Face interaction** - The most meaningful form of interacting with other humans
- **Shared interests** - Talk about real interests. Meaningful interaction, not weather small talk.
- **You are in control** - You can explicitly see and control your interests
- **Encounters are private** - You decide if an encounter was meaningful, nodepiazza only tries to bring the encounters to you

# nodepiazza-app

An Android app that quietly finds nearby people who share an interest with you — over Bluetooth. No servers, no accounts, no internet.

## How it works

1. You write down what you're into — music, hiking, a specific board game, whatever.
2. You can also jot down a private "about me" note. It stays on your phone.
3. When another phone running the app comes within Bluetooth range (a room, a café, the same train carriage), the two phones swap interest lists in the background.
4. If the app thinks you share something, the other person shows up in your "Nearby" list, and you in theirs.
5. Tap them to open a chat. The messages travel directly phone-to-phone — nothing leaves the room.

Matching runs on-device using a small language model (Gemma), so it understands that "hiking" and "senderismo" are the same thing, and that "Skoda" and "Toyota" both count as cars. The model is downloaded once on first launch.

Your "about me" is never broadcast. Only the interest list is shared with nearby phones, and only while scanning is on.


