# Colonist Errands

**Voice-command addon for [Talking Colonists](https://www.curseforge.com/minecraft/mc-mods/talking-colonists) + [MineColonies](https://www.curseforge.com/minecraft/mc-mods/minecolonies).**

Talking Colonists gives your MineColonies citizens AI voices. Colonist Errands gives those
conversations **consequences**: what you say to a colonist actually happens in the colony — and
what a colonist tells you is checked against the colony's real state first.

> **2.1.1** fixes the colonist who stood under "Listening" all night and never went to bed - a
> Talking Colonists session that kept reconnecting after Gemini had dropped it (see the changelog).
> **2.1.0** added the optional integration with [Voyager - End Expeditions for MineColonies](https://www.curseforge.com/minecraft/mc-mods/voyager-end-expeditions-for-minecolonies)
> (see below). 2.0.0 was the first full release: 42 voice tools, built and play-tested daily on one
> heavily played world (single-player and LAN co-op). Resurrection and births exist but are rarely
> seen, so they have had the least testing. Report issues with your `latest.log` attached.

## What you can do by talking

**Errands & logistics** — send citizens to any building ("go eat at the restaurant" literally feeds
them, "go to the hospital" registers sick citizens as patients), call citizens to you by name, have
couriers check warehouse stock, fetch items to you or deliver them building-to-building (multi-
warehouse rounds, honest "we only have 40" answers), messengers that physically walk and bring the
recipient back to you, "find X and lead me to them" guide walks, inventory watch ("tell me when you
find diamonds").

**Crafting & orders** — `request_craft` asks any colonist to have something made: several items in
one sentence, delivered to a building or carried to you personally. The order is followed through
the colony's own request system, so you are told when it is actually ready, and when it fails.
`courier_board` is a shared order board — ask a courier who is mid-delivery and they pass it on;
the first one free picks it up. `prioritize` reorders the build queue by voice.

**Jobs** — "become a farmer" hires the unemployed; "go work at the bakery instead" makes employed
citizens switch jobs (only into free positions, with profession words like *carpenter* correctly
resolving to the sawmill). If the nearest guard tower is full, the next free one is used.

**Military** — bodyguards ("guard me", single tower or all towers), defensive lines at any border,
voice-set patrol routes, red alert (civilians home, towers manned), gather/dismiss formations.
Raids are announced per colony, pinpointed **before** raiders spawn, and the defense line forms
automatically — then stands down by itself. `guard_gear` names exactly who is missing armour, a
weapon or a shield; `arm_guards` equips the lot of them at once.

**The guard leaderboard** — kills, raider kills, assists and damage taken, scored into a **weekly
season**. The week closes on its own with a podium announcement, and the top three are paid in
real skill XP. Careers, wins, podiums and personal bests survive across seasons.

**Promises** — promise a citizen something and they write it down, remind you when it's due,
stop pestering you about the promised problem while they trust you, and get a real happiness
boost/penalty when you keep/break it. The colony **notices on its own** when you keep a promise
(the promised building gets built, the homeless citizen gets a home...).

**Relationships (multiplayer-aware)** — citizens know who is speaking (name + colony rank),
remember how each player treats them (rapport from kind/rude moments and kept/broken promises),
gossip about players' manners and reliability, and answer to configurable rank permissions
(by default guests can chat but only Officers may order job changes or military actions).

**Colony life** — citizens chat with each other standing together or strolling (per-profession
policy), family members talk *as* family, three colonists standing together hold a round-robin
huddle, marketplace staff talk shop when no customer is around,
births are celebrated, deaths are mourned and the fallen are remembered by name, and helping
builders with the Assistant Hammer earns you their gratitude. When a building goes up or gains a
level, the people who live, work or built there react to it.

**Colonists who tell you the truth** — the release theme. A colonist's prompt is grounded in the
colony's real state before they open their mouth: the compass direction and distance of every
building, which guard towers cover which side, their own real health, their own real equipment,
what the builders are actually doing right now and why, whose beds are broken, what research is
running. Invented aches, invented gaps in the defence and complaints about gear they are already
wearing are gone.

**Watchdogs that fix things** — a builder who has genuinely stopped laying blocks is reported with
the real reason (asleep, eating, on an errand, missing a tool, waiting for materials) instead of a
false alarm; broken bed registrations are re-filed onto the right block and phantom entries dropped;
hospital beds blocked from above are named; a worker whose restart has been pending too long is
nudged through it; and a session watchdog ends Talking Colonists sessions that would otherwise
reconnect forever after Gemini drops them - the colonist frozen under "Listening" who never went
to bed - along with stale pregeneration slots, orphaned slots and busy marks nobody lifted (player
conversations are never touched).

**Audio fixes** — no doubled goodbyes, interruptible pre-generated clips with true barge-in
semantics, citizen-to-citizen audio that follows the speakers, sessions that are drained before they
are closed so no sentence is cut short, small talk that never evicts a running conversation, a
self-learning blocklist for TTS voices the Gemini API rejects (no more permanently mute citizens),
and a fix for citizen memories being lost when the model wraps JSON in markdown fences.

**Voyagers (optional, with the Voyager mod)** — the End-explorer profession joins the conversation.
A Voyager knows which look their Departure Point has (a rocket that really lifts off, or an End Gate
that beams them up), what they are doing right now (packing rations, waiting for supplies, tools,
the launch window or the rocket, boarding, out in the End, just back), who their crewmate is, and
what the last expedition brought home - or how it ended - straight from the hut's Expedition Log.
They embellish the feelings, never the facts. Two Voyagers who share a Departure Point talk shop
like astronauts while they wait, and the one who stayed behind welcomes the other back from the End
and hears the story. Everyone else in the colony knows who flies, who is out there right now and
who just came home.

## Requirements

- Minecraft **1.21.1**, NeoForge **21.1.x**
- **Talking Colonists (mc_talking) 1.7.x** — with a working Gemini API key configured
- **MineColonies 1.1.1300+**
- Simple Voice Chat (required by Talking Colonists) and a microphone
- **MC Trade Post** is optional — the marketplace and economy features simply stay quiet without it
- **MineColonies Compatibility** is optional — a linked *Common Network Storage* counts as warehouse stock
- **Voyager 0.2.0+** is optional — Voyagers get their lore, crew talk and colony news; without it nothing changes

In multiplayer only the **host/server** needs the API key; clients just install the same mods.

## Configuration

Files appear in `config/` after first launch:

| File | Purpose |
|---|---|
| `colonist_errands_settings.properties` | general switches: `max_taverns`, `group_chats` |
| `colonist_errands_permissions.properties` | which colony rank may voice-command what (chat/errands/military/jobs, per-tool overrides) |
| `colonist_errands_aliases.properties` | per-player "call me X" nicknames |
| `colonist_errands_promises.json` | promise storage (per maker) |
| `colonist_errands_relations.json` | per-citizen/per-player rapport |
| `colonist_errands_guard_scores.json` | guard careers and the current season |
| `colonist_errands_guard_week.txt` | which week the current season belongs to |
| `colonist_errands_guard_sidebar.txt` | scoreboard sidebar state |
| `colonist_errands_fallen.json` | the colony's roll of honour |
| `colonist_errands_blocked_voices.txt` | self-learned list of TTS voices the API rejects |

To silence MineColonies' own canned citizen lines (the "is there a problem" / "right away" barks)
while keeping the AI voices, set `enablecitizenvoices = false` in
`config/minecolonies-client.toml`.

## Building from source

See [BUILDING.md](BUILDING.md). Short version: drop the dependency mod jars into `libs/`, then
`javac` against them and `jar` the result — no Gradle needed.

## Documentation

- [docs/CHANGELOG.md](docs/CHANGELOG.md) — what changed in this release
- [docs/IDEAS.md](docs/IDEAS.md) — the full development log, version by version

## Credits & license

Made by **Lovkar & Claude** (Anthropic's Claude wrote the code pair-programming style through
hundreds of in-game test reports). Huge thanks to **sshcrack** for Talking Colonists and to the
MineColonies team.

Licensed under **GPL-3.0-or-later** (see [LICENSE](LICENSE)) — matching MineColonies' license.
Talking Colonists is used as a dependency per its "Don't Be a Jerk" license; this repository
contains only Colonist Errands code, none of the mods it depends on.
