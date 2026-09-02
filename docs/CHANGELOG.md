# Colonist Errands 2.0.0-beta.41 — conversations that finish, and nothing cut short

Five builds since beta.36, all of them from one complaint: colonists were still being cut off
mid-sentence. Three separate causes were found in the audio and slot handling and all three are
fixed; the rest of this release is the group huddle and a warehouse fix.

## Nothing is cut off any more

- **Live conversations closed while still speaking.** mc_talking closes a session the moment
  Gemini reports the turn *generated*, and closing empties the audio queue - so the last sentence,
  and in a citizen-to-citizen conversation the second speaker's whole reply, was thrown away. The
  stream is now drained before it is closed: the sentence plays out, a held reply is released once
  the other speaker is done, and the citizen stands and finishes instead of walking off with it.
  The player addressing a citizen still cuts them short - you come first.
- **Flash/TTS dropped the goodbye.** The final chunk of every rendered conversation sat below the
  stream's processing threshold and was never played - up to four seconds, always the end. Flushed
  now.
- **A new conversation killed a running one.** With all agent slots in use, mc_talking evicts the
  oldest non-player session rather than refusing - and reports evictable sessions as free capacity,
  so everything believed there was room. Small talk (random, family, shop, huddle, mumbling) may now
  only take a free slot or one whose holder has gone quiet; if everyone is still talking, it waits.
  A citizen coming to you with a need, and guard threats, keep the right to interrupt chatter.
- Pairs are kept together until their audio has really ended, not until the last chunk arrived.

## Shop talk ends politely

At two minutes the pair are asked to bring it to a close; at three the current sentence is the
last; only if they carry on well past that is the audio cut. A customer walking in gets one line
of excuse and twenty-five seconds. Timers count server ticks, so pausing the game no longer counts
against anyone.

## Three of them at once

Gemini voices at most two speakers and the live mode wires exactly two sessions, so a true
three-way is not possible - instead three colonists standing together hold a **round**: A with B,
then B turns to C, then C rounds it off with A, each part picking up from the last. Once per
quarter hour at most, only within earshot of a player. `group_chats=false` in
`colonist_errands_settings.properties` turns it off.

## Network storage counts as warehouse stock

If you link a MineColonies Compatibility *Common Network Storage* to your warehouse, couriers fill
the chests behind it before the racks. `check_stock`, fetch and delivery errands, the courier board
and `request_craft` now see those chests too, and our couriers put things there first, in the same
order as the mod itself. Optional - nothing changes without that mod.

## Smaller

- The homeless-colonist bed warning is said once per session, not every evening.
- The huddle search runs every 30 s and logs, at most every five minutes, why nothing started.

Source and issue tracker: https://github.com/Lovkar-Squid/colonist-errands

Authors: Lovkar & Claude.

---

# Colonist Errands 2.0.0-beta — voice commands, and colonists who tell you the truth

This is the first **Beta** release. It replaces alpha.10 and is a very large step
up from it: 42 voice tools instead of 33, a guard leaderboard with weekly
seasons, and — the theme of the whole release — a long series of fixes for
colonists who used to say things that were not true.

Requires **MineColonies**, **Talking Colonists (mc_talking)** and **Simple Voice
Chat**. MC Trade Post is optional; the marketplace features simply stay quiet
without it.

## New voice tools since alpha.10

- **`request_craft`** — ask any colonist to have something made. Multiple items in
  one sentence, delivered to a building or carried to you personally by a courier.
- **`courier_board`** — a shared order board. Ask a courier who is mid-delivery and
  they pass it on; the first one free picks it up.
- **`guard_gear`** and **`arm_guards`** — who is missing armour, a weapon or a
  shield, and an order to equip the lot of them at once.
- **`trade_status`** and **`mint_coins`** — the MC Trade Post economy, explained by
  a colonist and acted on by voice.
- **`remember_fallen`** — the colony's roll of honour. Guards "fall defending";
  everyone else is simply remembered.
- **`prioritize`** — reorder the build queue by voice, including "build this after
  the one you are on now".
- **`build_status`** — what every builder is really doing, and why they are not
  laying blocks this second.

## The guard leaderboard is now a weekly season

Scores wipe every Minecraft week. The top three are announced in chat and given
real MineColonies skill experience in their own primary and secondary skill, and
the colony's past champions are remembered. Career totals are kept separately and
never reset, so a fallen guard is still remembered for everything they ever did.

Assists count: anyone who did real damage to something another guard finished off
gets a share. Taking hits is measured per fight rather than in total, so turning
up to more fights can never lower your score, and ties break on kills rather than
on whatever order the game happened to list people in.

## Colonists stopped saying things that were not true

This is most of the release. Each one was a real report from play:

- **"I'm stuck building a residence" — while standing on a kitchen.** Builders now
  know which building they are actually on, and a stall is only reported when they
  are awake, on site, fully supplied and their job AI really is laying blocks.
  Asleep, eating, fetching materials, waiting on a delivery, mourning, hospitalised
  or sheltering from a raid are all reported honestly instead.
- **"My leg hurts."** It did not. Colonists now carry their real health, and are
  told plainly never to invent an ache.
- **"There is no defence on the western wall."** There was no wall. They now know
  the real compass layout of the colony, which side each guard post is on, and that
  they must not invent walls, gates or watchposts.
- **Guards complaining about gear they were wearing** — and about other guards'
  gear. Old complaints lived on in memories and spread through the rumour mill, so
  every guard is now told the truth about their own kit each time they speak.
- **Colonists who could not get into their own house** now say so, with the way in
  to check, instead of standing outside all night in silence.

## Beds, hospitals and stuck workers

MineColonies hands each resident the bed at *their own index* in the resident
list, so one broken entry leaves the same person standing every night. Colonist
Errands now repairs the list: beds filed on the foot end are re-filed onto the
pillow, entries pointing at nothing are dropped, duplicates are trimmed, and real
beds the schematic never registered are added. Hospital beds have a stricter rule
of their own and are repaired too — and when a bed genuinely cannot be used, the
exact block to break is named.

A restart scheduled from a hut only ever runs while the worker is paused, which is
why a scheduled restart could sit there indefinitely. It is now completed
automatically, and the worker handed straight back to work.

## Colonists talk to each other more, and about better things

- **Shop talk.** When there is no customer in the Marketplace or on the way, the
  shopkeeper and whoever is at the counter chat — across the street if there are
  two shops facing each other, without either leaving their till. A customer
  arriving ends it immediately.
- **They react to what you build.** Every finished building and every upgrade is
  noticed, and what they say depends on what it means to them — the person who
  works there, the person who lives there, the builders who put it up, and
  everyone else.
- **They react to research**, and know what it unlocked, not just its name.
- Conversation audio now stays with the speakers until the last word has played,
  and sits between the two of them rather than coming out of one person's mouth.

## Fixes worth naming

- Ordered items are tracked by the colony's own request, so an order that cannot be
  made tells you within a minute instead of leaving you waiting.
- Guard scores can no longer go negative, and a stale on-screen leaderboard left in
  a world save is cleaned up.
- Colonists no longer greet you with "good morning" in the evening, no longer run
  at you before a greeting has finished, and no longer leave the hospital to
  complain about being ill.
- Buildings under construction are left alone by every check — while a builder is
  working, blocks and beds legitimately come and go.

## Known gaps

Raids, resurrection and births are built but rarely seen, so they have had less
play-testing than the rest. Two colonists talking share a single audio channel,
which is a limitation of the conversation API rather than something this addon can
fix — the sound is placed between them as the best available approximation.

Authors: Lovkar & Claude.
