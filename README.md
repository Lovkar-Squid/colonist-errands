# Colonist Errands

**Voice-command addon for [Talking Colonists](https://www.curseforge.com/minecraft/mc-mods/talking-colonists) + [MineColonies](https://www.curseforge.com/minecraft/mc-mods/minecolonies).**

Talking Colonists gives your MineColonies citizens AI voices. Colonist Errands gives those
conversations **consequences**: what you say to a colonist actually happens in the colony.

> ⚠️ **ALPHA.** Built and battle-tested on one heavily played world (single-player and LAN co-op),
> but expect rough edges. Report issues with your `latest.log` attached.

## What you can do by talking

**Errands & logistics** — send citizens to any building ("go eat at the restaurant" literally feeds
them, "go to the hospital" registers sick citizens as patients), call citizens to you by name, have
couriers check warehouse stock, fetch items to you or deliver them building-to-building (multi-
warehouse rounds, honest "we only have 40" answers), messengers that physically walk and bring the
recipient back to you, "find X and lead me to them" guide walks, inventory watch ("tell me when you
find diamonds").

**Jobs** — "become a farmer" hires the unemployed; "go work at the bakery instead" makes employed
citizens switch jobs (only into free positions, with profession words like *carpenter* correctly
resolving to the sawmill). If the nearest guard tower is full, the next free one is used.

**Military** — bodyguards ("guard me", single tower or all towers), defensive lines at any border,
voice-set patrol routes, red alert (civilians home, towers manned), gather/dismiss formations.
Raids are announced per colony, pinpointed **before** raiders spawn, and the defense line forms
automatically - then stands down by itself.

**Promises** — promise a citizen something and they write it down, remind you when it's due,
stop pestering you about the promised problem while they trust you, and get a real happiness
boost/penalty when you keep/break it. The colony **notices on its own** when you keep a promise
(the promised building gets built, the homeless citizen gets a home...).

**Relationships (multiplayer-aware)** — citizens know who is speaking (name + colony rank),
remember how each player treats them (rapport from kind/rude moments and kept/broken promises),
gossip about players' manners and reliability, and answer to configurable rank permissions
(by default guests can chat but only Officers may order job changes or military actions).

**Colony life** — citizens chat with each other standing together or strolling (per-profession
policy), family members talk *as* family, births are celebrated, deaths are mourned, and helping
builders with the Assistant Hammer earns you their gratitude.

**Audio fixes** — no doubled goodbyes, interruptible pre-generated clips with true barge-in
semantics, citizen-to-citizen audio that follows the speakers, self-learning blocklist for TTS
voices the Gemini API rejects (no more permanently mute citizens), and a fix for citizen memories
being lost when the model wraps JSON in markdown fences.

## Requirements

- Minecraft **1.21.1**, NeoForge **21.1.x**
- **Talking Colonists (mc_talking) 1.7.x** — with a working Gemini API key configured
- **MineColonies 1.1.1300+**
- Simple Voice Chat (required by Talking Colonists) and a microphone

In multiplayer only the **host/server** needs the API key; clients just install the same mods.

## Configuration

Files appear in `config/` after first launch:

| File | Purpose |
|---|---|
| `colonist_errands_permissions.properties` | which colony rank may voice-command what (chat/errands/military/jobs, per-tool overrides) |
| `colonist_errands_aliases.properties` | per-player "call me X" nicknames |
| `colonist_errands_promises.json` | promise storage (per maker) |
| `colonist_errands_relations.json` | per-citizen/per-player rapport |
| `colonist_errands_blocked_voices.txt` | self-learned list of TTS voices the API rejects |

## Building from source

See [BUILDING.md](BUILDING.md). Short version: drop the dependency mod jars into `libs/`, then
`javac` against them and `jar` the result — no Gradle needed.

## Credits & license

Made by **Lovkar & Claude** (Anthropic's Claude wrote the code pair-programming style through
hundreds of in-game test reports). Huge thanks to **sshcrack** for Talking Colonists and to the
MineColonies team.

Licensed under **GPL-3.0-or-later** (see [LICENSE](LICENSE)) — matching MineColonies' license.
Talking Colonists is used as a dependency per its "Don't Be a Jerk" license; this repository
contains only Colonist Errands code, none of the mods it depends on.
