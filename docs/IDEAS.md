# Colonist Errands — feature plan & dev log

Addon for Talking Colonists (mc_talking) + MineColonies, 1.21.1 NeoForge.
Authors: Lovkar & Claude. Source: `src/` in this repository.

## Done — v1.0.0
- `send_to_building` — a colonist walks to a chosen building (home / own_building / nearest of type X), 4 min timeout
- `follow_player` — follows the player for up to 5 min
- `stop_errand` — cancel the task, back to routine
- Tested in game 28 Aug 2026: send_to_building and follow_player confirmed working (Lovkar).

## Done — v1.1.0 (28 Aug 2026, awaiting test)
- `come_here` — one-time walk to where the player stands right now (no following)
- `wait_here` — wait in place N minutes (minutes parameter 1–15, default 2); holds position if they wander
- `gather_at` — assembly: up to 10 nearest FREE colonists come to the player's position (guards excluded, staggered dispatch every 4 ticks for TPS)
- `everyone_home` — curfew: every colonist with a home goes home (guards excluded, cap 60, staggered)
- `guard_me` — bodyguard: saves the guard building's current GUARD_TASK, sets FOLLOW + setPlayerToFollow(player); ends via stop_errand ("that's enough"), when the player leaves, or after the 20 min safety limit → restores the previous task
- `send_messenger` (idea #11) — messenger: colonist A walks to a chosen building (guardtower/barrackstower now in the list), finds worker B there via getAllAssignedCitizen and mc_talking's UrgentContactHandler.triggerWalkToPlayer(B, player) brings B to the player, where B starts a conversation on their own; fallback without a conversation if the trigger fails; the player gets a chat notice "[Messenger] ..."
- `citizen_report` — report: happiness 0–10, (un)happiness reasons (happiness modifiers + factors), health, job, home
- `stop_errand` extended: also cancels guard follow and returns the tower to its previous task
- `send_to_building`: guardtower and barrackstower added
- **memory_fence_fix (idea #12)** — mixin on GeminiFlash.sendFlashRequest (@At RETURN): strips the markdown fence (```...```) from the response before mc_talking parses it → post-conversation memories are no longer lost. First successful use is logged: "[ColonistErrands] Stripped markdown fences...".

## Done — v1.1.1 (28 Aug 2026, hotfix after the first test)
- `send_messenger` fixed: mc_talking's triggerWalkToPlayer stopped the guard immediately ("urgent need resolved, aborting walk" — updateWalkingCitizens demands CitizenNeedAssessor.calculateUrgencyWeight > 0 every tick, which a healthy colonist doesn't have). B is now driven by our own engine (Kind CONTACT_PLAYER: moveTo the player every 20 ticks, arrival ≤ 3 blocks); on arrival it calls ConversationManager.startPlayerConversation(player, citizen) — a REGULAR conversation that checkUrgentContactAbort can't kill. Messenger A lingers ~3 s after the handoff (WAIT 60 ticks).
- Anti-repetition: mc_talking rejects end_conversation while the player is still speaking ("You cannot end this conversation - a player is speaking to you") → the model repeated the goodbye/answer = "double replies". All tool descriptions now instruct: on rejection do NOT repeat, wait and try again.
- Found during the test: config yacl-mc_talking.json5 had RESET itself (pregen on, c2c on, random on, agents 3, background 3, language en-US) → background sessions load the free tier again (second cause of doubling). After closing the game restore: enablePregeneration false, enablePlayerGreetingPregen false, enableCitizenToCitizenConversation false, enableRandomConversations false, maxConcurrentAgents 1, maxConcurrentBackground 1, language en-US (Lovkar's choice), memoryMode FLASH (compaction over HTTP flash instead of a WS session; the fence mixin covers that path too).
- Memory fence fix CONFIRMED working (log: "Stripped markdown fences..." → "[PlayerMemory] Saved..." repeatedly).

## Done — v1.1.2 (28 Aug 2026)
- **`leave_conversation`** — NEW tool: politely ends a conversation with the player. Discovery: mc_talking's end_conversation is ALWAYS blocked for player conversations (it only checks getPlayerForEntity != null; the "a player is speaking" message is misleading) — conversations therefore only ended when the player walked away. Our tool calls GeminiWsClient.endConversationWhenPossible() (public, graceful: the colonist finishes the goodbye, then at end of turn calls ConversationManager.endConversation(playerUUID,false) itself with full cleanup). All our tools now tell the AI to use leave_conversation instead of end_conversation → errands start IMMEDIATELY after the goodbye, without walking away; regular conversations also end on "bye".
- Messenger chat messages in English ([Messenger] ...) — Lovkar: "better that everything is in English"; config language stays en-US too.

## Done — v1.1.3 (28 Aug 2026)
- `gather_at` gets an optional `target` parameter: "here" (default, player's position) or a building type (e.g. townhall) → assembly at the nearest building of that type. Reason: Lovkar said "gather at the town hall" but the tool only knew "at me" — 10 colonists walked to his position AT THAT MOMENT and "nobody came to the town hall". The AI now also announces the assembly point.
- Confirmed working from the log already in v1.1.0: guard_me (activation, "already escorting", stop_errand restore), citizen_report (happiness 10.0, factors, health), gather_at mechanics (10 dispatched, no errors).

## Done — v1.1.4 (28 Aug 2026, after the second test)
- Building lookup BY NAME: new optional `building_name` parameter on send_messenger, send_to_building and gather_at — matches the building's custom name (`IBuilding.getCustomName()`, contains, case-insensitive; takes priority over type). Reason: "gatehouse" is not a MineColonies building type → the AI picked the nearest guardtower (wrong tower). Lovkar renames the building in its GUI (e.g. "Gatehouse"), then voice finds it by name.
- leave_conversation: stricter instructions (exactly 2 cases: the player clearly said goodbye / after announcing the errand; NEVER mid-conversation; when in doubt keep listening). Before: sometimes called too early (ended mid-conversation), sometimes not at all.
- Messenger: minimum ~2 s of walking before the "handoff" (walkTicks ≥ 40 — an instant delivery at a building right next to you looked like she never moved).
- CONTACT_PLAYER: before startPlayerConversation now forceRemoveCooldown + canCitizenSpeak(citizen,true) check (only fails if the colonist is SLEEPING or a visitor — a night guard in bed = why "he never came"); breadcrumb INFO logs for the whole chain ([Errand]/[Messenger]: walk start, progress every 10 s, arrival, conversation start, skip reasons).

## Done — v1.1.5 (28 Aug 2026, after the third test)
- The v1.1.4 test confirmed the WHOLE messenger chain (Gunilda 17 s of walking → handoff → Clarice came to the player and started the conversation HERSELF) — but the AI didn't fill the building_name field (sent only target=guardtower) → nearest tower again instead of the Gatehouse.
- Fixes: building_name on send_messenger now REQUIRED ("copy the EXACT word the player used, verbatim, in the player's language"); "gatehouse" added to the BUILDING_TYPES enum (if the AI puts the player's word into target, the resolve chain still finds it); ErrandBuildings.resolve(type, name): name → type → type-as-name; bestName label everywhere.
- gather_at gets a who parameter: "anyone" (default, civilians; guards stay on duty) / "guards" (military muster — ONLY guards come, and return to duty by themselves afterwards). Reason: "assemble, guards" used to bring random civilians because guards were deliberately excluded.

## Done — v1.2.0 (29 Aug 2026, big batch)
- **Assembly holds formation**: gather_at/summon switch to HOLD on arrival (WAIT with group="gather", 10 min) — nobody leaves until dismissed. Fixes "some leave before others arrive".
- **`dismiss`** — one command ("dismissed / stand down") releases EVERYTHING: the gathered, bodyguards (guard_me restore) and the defensive formation.
- **`summon_guards {count}`** (#13) — the strongest guards (ranked by Adaptability+Agility+Stamina+Strength via getCitizenSkillHandler) come to the player and hold position until dismissed.
- **`defend_here`** (#14) — every guard tower: saves GUARD_TASK, sets GUARD + setGuardPos to a point on a line through the player's position (perpendicular to the center→player direction, spacing 4, offsets 0,+1,-1,...); stand-down via dismiss or automatically after 30 min.
- **`call_me {name}`** (#16) — nickname in config/colonist_errands_aliases.properties (account→name); CitizenPromptServiceMixin (me.sshcrack.mc_talking.api.prompt.CitizenPromptService, generateCitizenRoleplayPrompt + generateSystemControlledRoleplayPrompt @RETURN) adds a block to EVERY colonist's prompt. Immediate, permanent, works for the girlfriend too.
- **`check_stock {item}` + `fetch_item {item,count}`** (#17) — ItemFinder (registry path + display name matching); stock via AbstractTileEntityRack.getCount across all warehouses; fetch is COURIER-only: FETCH_PICKUP (racks → InventoryCitizen via IItemHandler, cap 128) → FETCH_DELIVER (walk to the player, placeItemBackInInventory + chat).
- **`farmer_plant {crop}`** (#15) — FARMER-only: ItemFinder.findSeedFor (prefers "X seeds"), nearest FarmField (building extensions, collected via a getMatchingBuildingExtension side-effect predicate) → setSeed + markBuildingExtensionsDirty; the farmer plants through their own AI.
- **guard_me fix**: FOLLOW_MODE → TIGHT (save+restore; before, "he didn't keep up with me" = probably LOOSE follow with a large distance).
- **Anti-parrot**: models read "short goodbye and leave conversation" out loud — all info strings are now Texts.GOODBYE/SILENT: "(Silent instruction - never read this aloud...)".
- 18 tools total. New compile stubs: com.mojang.serialization.Keyable, com.mojang.authlib.GameProfile (besides brigadier Message).

## Done — v1.2.1 (29 Aug 2026, hotfixes after the night test)
- The v1.2.0 test confirmed: the WHOLE courier chain (messenger→courier arrives+conversation→64 cobblestone handed over), call_me, summon_guards with ranking (2nd call correctly the next 5), defend/dismiss mechanics (released 20/17), guard muster.
- check_stock returned 0 (rack.getCount(stack,true,true) semantics) → counting now via getItemHandlerCap slot iteration (same path as fetch, which worked).
- **kickGuardAI**: restoring guard settings did NOT wake the guard's AI → "he's still guarding me after I said he can go". setPlayerToFollow internally fires AIOneTimeEventTarget(PREPARING) for each guard — we now do the same kick on EVERY restore (guard_me stop, dismiss, stand-down) and on defend_here activation (march immediately).
- guard_me `who: all_towers` — "all guards guard me" through anyone; ends with dismiss. (Before: only the speaker's tower.)
- defend_here `direction: here/north/south/east/west` — "defensive line at the east border" without walking there: anchor at the edge of the buildings' bounding box (+8), line along the border, each point's y via the heightmap (before: the player's y — underground/in the air). Kick on activation; the info says they need a few minutes to march (Lovkar used to dismiss after 24 s).
- Chasing the player (messenger/courier): timeout 4→8 min + chat notice "couldn't catch up" when it expires (Thurbarius chased him 130 blocks without a trace).
- stop_errand synonyms ("you can go", "you're free") + leave_conversation MANDATORY on a mutual goodbye (sometimes they said bye and just hung around).

## Done — v1.2.2 (29 Aug 2026, farmer v2)
- Tests of v1.2.0/1: named guard muster at the Gatehouse ✓, wait_here ✓, everyone_home (19) ✓; farmer_plant ran, but: it picked culturaldelights "Eggplant Seeds" instead of minecolonies (same path "eggplant_seeds/eggplant_seed", registry order won) and overwrote the onion field (took the nearest).
- ItemFinder.findSeedFor v2: stem matching (path without _seed/_seeds, singular/plural) + namespace priority minecolonies(+30) > minecraft(+20) > farmersdelight(+10); seed item +100.
- farmer_plant v2: (1) a field with the same seed already exists → changes NOTHING, the farmer goes to work there; (2) otherwise the nearest EMPTY field; (3) if all are taken → does NOT overwrite, lists the assignments and the AI asks the player; only replace=true overwrites the nearest. In all successes the farmer gets a TO_POS errand to the field (visibly "gets the task").
- Note for Lovkar: tonight's overwritten onion field (581,93,3681, now culturaldelights eggplant) can be restored with "plant onions" (+replace, because the field isn't empty).

## Done — v1.2.3 + v1.3.0 (29 Aug 2026, night session 2)
- v1.2.3: command tools (dismiss/defend/gather/summon) confirm AND end the conversation themselves ("dismissed" → "Yes sir, bye" without waiting for the player's bye); the leave_conversation rule extended to final commands; **GeminiWsClientMixin** — onTurnComplete ends the conversation when GENERATION finishes while audio is still playing → @Redirect endConversation into ErrandManager.runLater(40 ticks) = 2 s of grace, the colonist finishes their goodbye (endConversation is idempotent, the race with distance-end is harmless).
- v1.3.0 — **`notify_when`** (Lovkar's idea #18): "tell me when you find diamonds/gold" → WatchManager watches the colonist's inventory (poll every 40 ticks, contains-match on item path — "gold" catches raw_gold/gold_ore/gold_ingot; the baseline lowers on deposit); on gaining ≥ count: chat "[Report] ..." or notify=come_to_me → the colonist gets **an event written into mc_talking memory** ("I just found ... on my way to report") + CONTACT_PLAYER walk → on arrival they truly KNOW what they're reporting. A watch expires after 60 min; stop_errand clears watches too; one item per call.
- Gemini credits: 01:04 "prepayment credits are depleted" (1011) — Google's new Prepay model; topped up at ai.studio/projects.

## Done — v1.4.0 (29 Aug 2026, raid system — Lovkar's idea #19)
- **RaidWatcher** (tick every 100): monitors IRaiderManager of all colonies. On raid start: direction from getLastSpawnPoints() vs colony center (8 directions), chat "[Alarm] RAID! Attackers are coming from the X!", the nearest GUARD runs to warn the player in person (memory event + CONTACT_PLAYER — our proven pattern). On end: "[Alarm] The raid is over". Bonus: willRaidTonight() edge → "[Alarm] Scouts report: a raid is expected TONIGHT!".
- defend_here extended: 8 directions (northeast/northwest/southeast/southwest — "south-west border" is ONE call; before, the AI called south+west and the second overwrote the first) + **direction='raid'** — the line is placed AGAINST the active raid's direction (RaidWatcher.raidVector; without a raid, a polite error). Anchor per-axis: >0.3 → max+margin, <-0.3 → min-margin, else center (works for cardinals and diagonals).
- check_stock: counting via server.submit().join() — tools run on Gemini WS threads and vanilla getBlockEntity returns null off-thread (hence the eternal zeros; fetch worked because the engine ticks on the server thread). RULE for all tools: world/block access always on the server thread!
- From the log: messenger to the farmer ✓ (Jace arrived + conversation), the second courier correctly "standing by" because the player was mid-conversation; guard_me all_towers ✓; the double defend south+west confirmed as the reason for the southwest request.

## Done — v1.4.1 (29 Aug 2026, audio fixes after the morning report)
- **AudioGate** (new class): central audio cutting. Lovkar's two symptoms: (1) when sending someone to the dining hall etc., the colonist "repeats the sentence twice", (2) if a colonist starts a conversation on their own and Lovkar interrupts, they finish the old line AND answer as well.
- **Double goodbye after errand tools**: Gemini MUST respond to the tool result of leave_conversation and loves repeating the farewell. Fix: leave_conversation arms the AudioGate → once the goodbye generation drains (generationComplete or a 2.5 s grace; if it finished before the call, mute immediately), ALL further audio of that session is DISCARDED in onGeneratedAudio (mixin cancel) before reaching the speaker. + a sharper result text ("respond with COMPLETE SILENCE") + a global prompt rule "one goodbye total". Cleanup: client.close() → clear; 30 s expiry safety.
- **Double reply on "Session token invalidated"**: mc_talking DELETES the session token on this and RESENDS the last prompt into a fresh session → the model answers again (proven in the onClose decompilation). Fix: mixin injected exactly at setSessionToken("") in onClose → AudioGate.clearQueuedAudio (reflection: audioFrames/incomingData/remainingSamples) throws away the not-yet-played old answer; the new one plays once.
- **Interruptible pregen clips**: pregenerated greetings/delivery clips play on a bare GeminiStream WITHOUT a live session — the microphone never reaches them (handleMicPacket drops the voice if there's no active conversation), so they always played to the end. Fix: PregenerationPlaybackMixin (2× @Redirect: markBusy captures the colonist into a ThreadLocal, addGeminiPcmWithPitch registers the stream in AudioGate) + McTalkingVoicechatPluginMixin (handleMicPacket HEAD: a player voice packet >10 B → stopPregenNear within 16 blocks: stream.stop + reflective release of ACTIVE_PREGENERATED_PLAYBACK + markNotBusy → a live conversation can start IMMEDIATELY) + GeminiWsClientMixin.onGeneratedAudio: when the same colonist's live session starts speaking → the pregen clip goes silent (no overlap/duplication).
- Log breadcrumbs: "[AudioGate] ..." (armed/muted/dropped/Cut pregenerated clip).

## Done — v1.4.2 (29 Aug 2026, "voices with no people")
- Lovkar's report: "sometimes I hear them talking to each other, but they're not even there". Cause (CitizenConversation decompilation): Flash/TTS c2c conversations play through a STATIC LocationalAudioChannel placed at the average of the participants' positions WHEN GENERATION STARTS — generation (Flash+TTS) takes a while, the two colonists wander off, and the whole dialogue plays at the old spot into thin air. (The live c2c mode uses EntityAudioChannel and escapes this.)
- Fix: **C2cAudioFollower** + CitizenConversationMixin (@Inject at constructLocationalAudioChannel RETURN registers channel+participants). Every 5 ticks: channel.updateLocation onto the FIRST living participant (the voice always comes from an actual colonist, never from thin air); if they separate >24 blocks or both disappear → conversation.abort() (a sanctioned path, same as mc_talking uses when a player conversation starts); safety expiries: state ENDED (reflection) or 10 min. Breadcrumb: "[C2C] ...". 7 mixins total.

## Done — v1.5.0 (29 Aug 2026, c2c the way Lovkar wants it — idea #21)
- Lovkar's design after v1.4.2: instead of the conversation FALLING APART when the two wander off, (a) they should stand TOGETHER while talking, (b) only those without work should chat, (c) the player can send them "back to work".
- **C2cAudioFollower → chaperone**: while the conversation runs: >5 blocks apart → both walk toward each other (EntityNavigationUtils.walkToPos, every 10 ticks); together → both stand and LOOK at each other (per-tick navigation.stop + LookControl.setLookAt — the same trick mc_talking uses for player conversations, ServerEventHandler); the audio channel follows the first living participant (every 5 ticks). Abort only as a safety: >32 blocks (teleport) / a participant disappears / 10 min / state ENDED. + RECENT_PARTNER memory (3 min) for back_to_work.
- **RandomConversationHandlerMixin**: @Redirect on BOTH canCitizenSpeak calls in checkForRandomConversations → also isFreeToChat: the jobless/children any time, workers only at night (after the workday), guards never (always on duty).
- **back_to_work tool** (20th tool): ends the addressee's chat, AI kick START_WORKING for them AND for the (recent) partner, ErrandManager.cancel for both; the unemployed honestly say they have no job. Trigger phrases: "back to work / stop chatting / quit gossiping". Order pattern: a short apology + leave_conversation.
- Live-mode c2c (the websocket variant) has no chaperone yet (entity channels → no ghosts; freezing would need a different hook) — later if needed.

## Done — v1.6.0 (29 Aug 2026, big batch — 11 new tools, 31 total)
- **call_citizen** — "call Elyse over": calling anyone by name (Citizens.findByName: exact > name > contains, tiebreak nearest; memory event + CONTACT_PLAYER).
- **find_citizen** — "where is Hada?": profession, workplace, distance + direction (dirName8); **lead=true** → the colonist personally WALKS you to the target (new GUIDE errand: waits if you fall >20 blocks behind, goal = within 5 blocks of the target).
- **colony_report** — population/children/guards/unemployed, the sick, the hungry (<10 sat), the homeless, raid now/tonight, research count, work orders.
- **why_unhappy** — the real happiness score + modifiers (getFactor<1 = bad) — the colonist gives the actual reasons.
- **research_status** — university level, researchers + their "mana" (offline credit), running researches with ~% (progress / (72·2^(depth-1))).
- **red_alert** — civilians home (everyone_home logic) + ALL towers set to GUARD with the post on their own tower (registerDefense → "stand down" restores). For direction/raid, defend_here is called on top.
- **take_job** (#6) — an unemployed colonist (not children) gets hired: resolve building → WorkerBuildingModule !isFull → setHiringMode(MANUAL) + assignCitizen + AI kick + walk there. Already employed: an honest referral to the hut GUI.
- **deliver_item** (#8) — courier: warehouse → PICKUP → new DELIVER_BUILDING errand → insertItemStacked into the target building's racks; keeps the surplus + chat "[Courier] delivered N× ...".
- **patrol_here** (#9) — guard: mode=start/add/reset; addPatrolTarget(player's position), PATROL_MODE=MANUAL (reset→AUTO + resetPatrolTargets), GUARD_TASK=PATROL, kick. The whole tower patrols the points.
- **PROMISES (Lovkar's idea #22)**: make_promise {promise, due_in_days} + resolve_promise {kept} + PromiseStore (config/colonist_errands_promises.json, colony day from overworld getDayTime/24000, tick every 100). Open promises are injected into the prompt of EVERY conversation of that colonist (CitizenPromptServiceMixin now receives CitizenPromptView → view.name()): the colonist brings them up, warns about the due date/OVERDUE, is grateful/disappointed for 2 days after resolution; memory events alongside; cap 6 open per colonist.
- Engine: Kind.GUIDE + Kind.DELIVER_BUILDING, Errand.destBuilding/targetCitizen, startDeliverErrand/startGuideErrand, deliverToBuilding/insertIntoRacks.

## Done — v1.6.1 (29 Aug 2026, promises v2 — Lovkar's ideas #23 + #24)
- **#23 A promise = peace**: a colonist with an open, not-yet-due promise STOPS nagging about the promised thing. make_promise gets an about param (housing/food/health/work/general); CitizenNeedAssessorMixin recomputes urgency with the promised component muted (housing: homeless +0.7 and for the homeless also the happiness component; food: hunger; health: sick+hp; general: happiness) — "stuck job" is NEVER muted (a technical problem). Without a due date, patience lasts 4 colony days; once the promise is OVERDUE they nag again (and the prompt says "OVERDUE"). Prompt rule: "while open and not overdue do not nag - you trust the player".
- **#24 Mood reward/penalty**: resolve_promise with kept=true adds ExpirationBasedHappinessModifier("promise", weight 2.0, factor 2.0, 3 days) — the same mechanism as MineColonies quest happiness rewards; kept=false → factor 0.4 for 3 days. Visible in the happiness GUI and in why_unhappy.

## Done — v1.6.2 (29 Aug 2026, chatting the way Lovkar wants it, v2)
- isFreeToChat reworked per Lovkar's clarification: not "after the workday" but "whoever CURRENTLY has no task". The jobless/children any time; workers when data.isIdleAtJob() (a cook who isn't cooking right now); guards when standing still (navigation.isDone) and NOT on our escort/defense duty — they can chat with the cook at their post; researchers even while working (also with each other). No more time-of-day (isDay) condition.

## Done — v1.6.3 (29 Aug 2026, per-profession chat policy — Lovkar's idea #25)
- **JobChatPolicy**: a review of ALL 47 MineColonies professions (44 civilian + knight/ranger/druid), each gets a policy:
  - CHATTY (chats DURING work, the chaperone sits them together): researcher, student, teacher, pupil, cook, chef, baker, florist, enchanter, alchemist, healer, composter, beekeeper, all herders (shepherd/cowboy/swine/chicken/rabbit/stablemaster), fisherman (fishermen by the water!), combat/archer training.
  - WALKER (chats WHILE WALKING — the chaperone does NOT freeze them, the voices walk along; barracks patrols in pairs, couriers): knight, ranger, druid, deliveryman. If they separate >20 blocks mid-walk, the conversation ends quietly.
  - FOCUSED (only when isIdleAtJob — heavy/dangerous/loud work): builder, miner, quarrier, lumberjack, farmer, smelter, stonemason, sawmill, blacksmith, mechanic, stonesmeltery, glassblower, dyer, fletcher, crusher, sifter, concretemixer, planter, netherworker, undertaker. Unknown (modded) professions = FOCUSED.
- Guards never during a raid or on our escort/defense duty. Walking-chat pairs show in the log as "[C2C] ... walking chat - they stroll together".

## Done — v1.6.4 (29 Aug 2026, after the morning test of 1.6.3 + Lovkar's idea #26)
- **Bugfix c2c insta-cut**: log 15:42:20 — a conversation registered and killed the same ms ("participants got separated") because the handler picked a pair >32 blocks apart (radius around the PLAYER, not between them). Now: everTogether flag — before their first meeting the pair WALKS toward each other (allowed up to 64 blocks, no abort); the 32-block safety only applies after they've met. Walking pairs: if they separate >12 blocks mid-walk or don't meet within 30 s → they quietly switch to "stop and finish standing" (instead of a cut mid-sentence).
- **Bugfix cutting on noise**: barge-in for pregen clips now debounced (≥5 voice packets >15 B within ~100 ms) — a cough/click/open-mic noise no longer cuts clips. (For live sessions "they start, then change their mind" = Gemini's VAD on mic noise — mitigation: a higher voice-activation threshold in Simple Voice Chat or PTT.)
- **#26 Mourning**: DeathWatcher (tick/100) watches the colony event log (CitizenDiedEvent) and graveyards (getGravePositions count). On a death: up to 6 witnesses (mourners preferred, radius 64) get the memory "X just DIED (cause)" → they talk about it on their own; two free witnesses IMMEDIATELY start a mourning c2c conversation (2 min cooldown; not during a raid). A new grave: a "laid to rest" memory for witnesses near the graveyard (radius 48). Log: "[Mourning] ...".
- Confirmed from the 1.6.3 log: 3× make_promise (housing, with due dates!), resolve_promise, guide ("Lota led the player to Gerardus" + chat), back_to_work AI kick, why_unhappy (even a "death 0%" modifier), find/call_citizen, goodbye gate 12/12. New mc_talking tools observed: end_conversation×7 (blocked, the model still tries), describe_surroundings, get_current_situation, list_citizens.

## Done — v1.6.5 (29 Aug 2026, honest stock)
- Lovkar's report "I say 30, he brings 25": at 03:43 he ordered 30 books, the warehouse had 25 — the behavior was right, the communication wasn't. Now: fetch_item and deliver_item count the stock AT ORDER TIME (CheckStockAction.countStock, server thread) — if it's 0, the courier honestly declines; if it's less than requested, they say "we only have X, I'll bring them all" and cap at X. If the stock drops further mid-walk, at pickup: chat "[Courier] only found X of the Y - bringing what's there."

## Done — v1.6.6 (29 Aug 2026, VoiceFix + honest scouts)
- **VoiceFix (the 1007 bug)**: Lovkar's log — "GeminiWsClient closed: No matching speaker voice found for name: Archid ... code 1007". Cause: mc_talking's hardcoded voice list (AvailableAI) contains names the Gemini Live API no longer accepts for en-US ("Archid"); the voice is picked deterministically from the UUID → the affected colonist can NEVER speak (all 5 reconnects pick the same voice). Fix: @Redirect on getRandomVoice in getSetup → VoiceFix.pickVoice (a salted-UUID reroll past the blocklist, deterministic, fallback Puck/Kore) + @Inject onClose HEAD: on a 1007 the voice name from the close reason is automatically added to a PERSISTENT blocklist (config/colonist_errands_blocked_voices.txt) → the very next attempt succeeds. Self-learning for any other broken voices.
- **Scouts**: "the scout announced a raid, but there wasn't one" — MineColonies can cancel a predicted raid (willRaidTonight falls back to false). The warning now hedges ("scouts have been wrong before") + on cancellation a retraction: "[Alarm] Scouts stand corrected: no raid is expected tonight after all."
- The remaining "double dialog" (they start one thing, switch to another) = Gemini's VAD on mic noise + reconnects — mitigations already in 1.6.4/5; the recommendation stands: a higher voice-activation threshold in Simple Voice Chat, or PTT.

## Done — v1.6.7 (29 Aug 2026, automatic defense — Lovkar's report)
- Report: "the guards didn't line up on the defence line the raid came from". Log forensics: the line WAS placed (9 towers, direction north) but ordered only 42 s after the spawn; the raid lasted 3 min — the guards were mid-fight (combat AI overrides the march to the post) and never made it. Ordering during a raid is too late.
- Fix: **automatic defense**. RaidWatcher now scans colony.getEventManager().getEvents() for SCHEDULED raid events (IColonyRaidEvent, status != DONE/CANCELED) — the spawn point is known BEFORE the raiders arrive (getSpawnPos via reflection). On detection: "[Alarm] Scouts pinpointed the raid: attackers will come from the X!" + the line forms ITSELF (formLineToward — the same math as defend_here border mode) with minutes of head start. If the pre-spawn phase isn't caught, the line forms at spawn. After the raid: automatic stand-down + "[Alarm] guards stand down". A manual line (defend_here) takes priority — auto doesn't interfere if a defense is already standing; raidVector is now known even BEFORE the spawn (defend_here direction=raid works already in the scout phase).

## Done — v1.6.8 (29 Aug 2026, the courier's round + builders)
- Lovkar's report "162 in stock, said 62, brought 54": check_stock counts ALL warehouses, but pickup only searched the nearest one. Fix: **the courier's round** — if warehouse #1 isn't enough, the courier continues to the next (and the next), summing up (collectedSoFar/visitedWarehouses), delivers the total; log "[Courier] X has N/M - continuing to the next warehouse". If it still falls short (empty everywhere or a full backpack): chat "only gathered X of Y (stock ran short or their bags are full)".
- Lovkar's feeling that "builders work slower": FOCUSED workers chat when idle — but a builder is "idle" precisely while waiting for materials, and then stands in a conversation for 1-2 min instead of resuming. Fix: a builder with a CLAIMED work order doesn't chat (the colony is waiting for him), even if currently idle; + a global cooldown: each colonist at most one c2c chat per ~3 min (RECENT_PARTNER doubling as the cooldown) — no more chat chains.

## Done — v1.6.9 (29 Aug 2026, gentler barge-in)
- Lovkar's report "a whole lot of interrupted conversations around me" — log: 29 pregen clips cut (the 16-block barge-in radius around a talkative player mows down everything nearby), the chaperone CLEAN (0 unwanted cuts, the only stop = his back_to_work), 6 cuts = the Archid voice (VoiceFix already in 1.6.6+). Fix: barge-in radius 16 → 7 blocks (only the clip talking to the player's face goes quiet), speech threshold 5 → 6 packets (~120 ms).

## Done — v1.7.0 (29 Aug 2026, job switching — Lovkar's idea #27)
- take_job extended into job SWITCHING: an employed colonist, on "go work at the bakery instead", quits and takes the new position IF it's free. Safe ordering: first check for space in the new building, only then removeCitizen from the old one (the module that contains them), setHiringMode(MANUAL) + assignCitizen into the new one; if the assign fails → rollback to the old position (nobody ends up jobless). An occupied position = the honest answer "the job is taken, nothing changed". Same building = "you already work here". Children still can't.

## Done — v1.7.1 (29 Aug 2026, profession → building)
- Lovkar's report: "the lumberjack should become a carpenter" was rejected because there's no "carpenter" building — a carpenter works at the SAWMILL. Extracted the official list of all building types from the jar (ModBuildings: 55 types, including kitchen/smeltery/stable/simplequarry...) and added a JOB_ALIASES dictionary to ErrandBuildings: carpenter→sawmill, courier→deliveryman, chef→kitchen, smelter→smeltery, stone smelter→stonesmeltery, researcher→university, student→library, teacher/pupil→school, healer/doctor→hospital, undertaker→graveyard, planter→plantation, stablemaster→stable, rabbit herder→rabbithutch, knight/ranger/druid/guard/archer→guardtower, quarrier/quarry→all three quarry sizes (endsWith), restaurant→cook, storage→warehouse, mason→stonemason, bakery→baker... + normalizeType strips "'s hut"/" hut"/" building". Works everywhere buildings are looked up (take_job, send_to_building, messenger, gather, deliver_item...). The BUILDING_TYPES enum extended with professions so the model offers them at all.

## Done — v1.7.2 (29 Aug 2026, family — Lovkar's idea #28)
- **FamilyChats**: every ~2 min (global cooldown 5 min, per-pair 30 min) find a nearby (<20 blocks) FREE family pair — partners, parent+child or siblings (ICitizenData getPartner/getChildren/getSiblings) — both get the memory "You run into your wife/son/sister <name> - have a warm FAMILY chat" and start a conversation; the chaperone keeps them together. Log "[Family] X (father) and Y (daughter) start a family chat".
- **Birth (#28b)**: CitizenBornEvent from the colony event log → the parents (found via the newborn's getParents()) get "your baby X was just born - overjoyed", neighbors (radius 64, 6 of them) "congratulate them warmly", and if both parents are free they IMMEDIATELY celebrate together with a conversation. Log "[Family] A baby was born / Proud parents ...".
- **Growing up**: CitizenGrownUpEvent → the parents get "your child X just GREW UP - proud parent".

## Done — v1.7.3 (29 Aug 2026, true barge-in semantics)
- Lovkar's report "they start talking to me and get cut off immediately": greetings aimed at the player always start WITHIN 7 blocks — and if the player happens to be speaking right then (almost always), barge-in killed them at birth. Fix: cut ONLY if the player's speech burst (the streak start is recorded) began AFTER the clip started (+300 ms tolerance) — a clip that starts while the player is already speaking survives; a real interruption mid-clip still cuts. Confirmed this session: the deliver_item chain (64x quartz → stonemason racks), the blacksmith→Carpenter@sawmill switch, a promise kept + mood boost.

## Done — v1.7.4 (29 Aug 2026, take_job crash on a full building + a free tower of the same type)
- The log caught a crash: `take_job guardtower` for Richard → IllegalArgumentException from MineColonies `getModuleMatching` — that method THROWS when no module matches (it doesn't return null!), so my "position taken" answer was dead code and a full building threw "Something went wrong". Fix: safe lookup via `getModules(WorkerBuildingModule.class)` (never throws) in both places (the new module + the old module when switching jobs).
- Bonus: if the NEAREST building of the type is full, we now check whether ANY OTHER building of the same type has a free slot (multiple guard towers!) — the colonist goes there; but they never "move" into their own building. If everything is full: the honest answer "(checked all N of them - every position is filled)". New messages also for buildings that don't employ anyone (warehouse/residence): "is not a workplace anyone can be hired at".
- The 18:37–19:31 session (1.7.1) otherwise clean: goodbye gate 12/12, the carpenter@sawmill switch ✓, promises kept+boost ✓, the restaurant honestly "no food" (Elyse — the kitchen was empty), courier zinc 59/128 with an honest message (one warehouse, the stock dropped before pickup — works as designed), 29 pregen cuts = exactly the symptom 1.7.3 (now in mods) removes.

## Done — v1.7.5 (29 Aug 2026, barge-in finally right: a protective window + real bursts)
- Lovkar's report on 1.7.4 ("it still cuts them off", 5 cuts in 20 min): two holes in the 1.7.3 logic. (1) The streak/burst was only tracked WHILE a pregen clip was playing — at clip start the state was stale and the first packets ALWAYS looked like a "new burst". Speech is now tracked on EVERY mic packet; the burst start is real. (2) The "new burst" boundary was 400 ms — natural pauses between words are 200–600 ms, so continuing YOUR OWN sentence counted as an interruption; now 900 ms (a pause below that = the same sentence). (3) A new hard rule: a clip is UNTOUCHABLE for its first 2 seconds (EARLY_PROTECT_MS) — the colonist always gets at least the first part of the sentence out; only then does barge-in apply. A deliberate interruption ("stop, wait...") after 2 s still cuts; the log reason is now "player talked over it".

## Done — v1.8.0 (29 Aug 2026, multiplayer polish — Lovkar + girlfriend + little sister)
- **Promises per player**: every promise remembers WHO made it (`byPlayer` account; old records = "the player"). The prompt lists them by name ("Lovkar promised: ...") with the rule: remind/thank/blame ONLY the one who promised — never nag someone else about another player's promise. resolve_promise first closes the CURRENT speaker's promise (only if they have none, someone else's — noting whose it was). Memories at promise/resolution carry the name.
- **Track record & gossip**: the colonist's prompt shows their experience with each player ("Lovkar: kept 2, broke 1 - you are rightly skeptical") and the colony-wide reputation ("word around the colony: X kept N / broke M promise(s)") — colonists gossip, everyone knows who keeps their word.
- **MULTIPLAYER AWARENESS block** (PlayerIdentityBlock in every prompt): (1) always store memories about players WITH the name ("Lovkar promised me...", never "the player") and never attribute one player's deeds to another; (2) who is speaking right now + their MineColonies RANK (mc_talking's PlayerRelationView already supplies name+rank from colony permissions) with behavior: Owner/Officer = your leadership (obey with respect), a guest (Neutral) = friendly, but politely redirect bigger decisions (jobs, alarms, defense) to the leadership, Hostile = reserved, no commands; (3) alias-aware ("Lovkar (account ...)").
- **Raid warning in MP**: the guard who personally runs to warn picks the player NEAREST the colony (before: always the first in the list). The broadcast still goes to everyone.
- Setup for the girlfriend + little sister: a mirrored modpack (the same jar), officer rank in Town Hall permissions, one API key (Lovkar's, server-side), headphones/PTT when in the same room.

## Done — v1.9.0 (29 Aug 2026, how you treat colonists + rank permissions — Lovkar's ideas #29 + #30)
- **#29 They remember HOW you talk to them**: new tool `note_player_conduct` (32nd tool) — the colonist itself (the model) quietly notes when you were notably KIND to them (a sincere compliment, gratitude, a gift, patience) or RUDE (insults, yelling, belittling); max once per conversation, never mentions the note-taking. RelationStore (config/colonist_errands_relations.json): rapport −100..+100 per (colonist, player) pair + the last 4 remembered moments with their days; kept/broken promises feed rapport automatically (+8/−10). Prompt: "How Lovkar treats YOU (rapport 22/100): consistently kind - be warm and extra helpful", or when low: "be cool, short and visibly reluctant" (leadership orders still get carried out, just without warmth). Colony gossip about manners: "X is known as kind" / "Y has been rude to several colonists - people grumble".
- **#30 Configurable which rank may command what**: config/colonist_errands_permissions.properties (creates itself with defaults and comments; read at startup). Groups → minimum rank: chat=neutral (questions, reports, promises), errands=friend (fetch/send/call/deliver/messenger/farmer...), military=officer (defend, red alert, patrol, guard_me, summon, everyone_home), jobs=officer (take_job); per-tool override "tool.take_job=owner"; values owner/officer/friend/neutral/nobody. The Owner may ALWAYS do everything, Hostile never anything. Enforced on both ends: a RankGatedAction wrapper around the tools (too low a rank → the polite refusal "ask the Owner or an Officer", logged as [RankGuard]) AND in the prompt ("Commands this person's rank MAY give you: ... / NOT allowed: ..."), so the colonist explains the rules instead of just failing. The rank comes live from MineColonies Town Hall permissions (owner/isColonyManager/friend/hostile; custom ranks by the isColonyManager flag).

## Done — v1.9.1 (29 Aug 2026, raid alarm v3 + assistant hammer reactions — Lovkar's report + idea #31)
- **False raid retraction eliminated**: the log caught the exact sequence — MineColonies "consumes" the willRaidTonight flag when creating the real raid event, and my old falling-edge logic read that as a cancellation, announcing "Scouts stand corrected" THE SAME SECOND as "Scouts pinpointed the raid" (22:24:39.885 vs .908). New logic: the "raid expected TONIGHT" warning only around dusk (dayTime 11000–21000, once per day, and not if an event is already pinpointed); a retraction ONLY via PINNED_EVENT tracking — only if a pinpointed event truly disappears/is CANCELED without a raid does "The raiders turned back" fire + stand down. The falling edge of the flag means nothing anymore.
- **StringSetting crash on towers under construction**: 2 towers threw IndexOutOfBounds when the line formed (a GUARD_TASK setting with no value — a tower under construction/without guards). Now: towers with no assigned guards are skipped (they can't defend), getValue wrapped with a PATROL fallback. The pinpoint message now honestly says "they will be here within MINUTES".
- **#31 Reaction to the assistant hammer**: the 9th mixin (ItemAssistantHammerMixin → placeBlock, server-side, bytecode-verified) — when the player personally helps build with the assistant hammer: that construction's builder gets a grateful memory ("X personally helped YOU build ...") + a real rapport boost (RelationStore, once per 4 min per pair), and up to 2 passing colonists notice with a "the boss lays blocks himself" memory. Log tag [Builder].
- **Blacksmith & assistant hammer explained** (not an addon bug): the iron/diamond assistant hammer recipes are BUILT-IN blacksmith crafterrecipes, unlocked by the research Technology → "Hitting Iron" → **"Assistant Hammers"** (2 iron blocks, blacksmith lvl 1). Once THAT research is done, the recipes appear automatically in the blacksmith's hut UI (Recipes tab) — the hammer is not "taught", it is ORDERED through the request system (postbox/clipboard: request item). The gold variant isn't made by the blacksmith at all — the player crafts it (structurize gold scepter + 8 gold + a stick). If the recipe isn't in the hut UI → check that the research actually completed ("Assistant Hammers", not just "Hitting Iron").

## Done — v1.9.2 (29 Aug 2026, guard settings hotfix — root cause found)
- Log from the 1.9.0 session: `guard_me activation failed` (IndexOutOfBounds: index -1, length 1) — which also explains the raid's "towers under construction". The REAL cause: MineColonies `StringSetting.set(value)` does `currentIndex = indexOf(value)` — if the tower doesn't OFFER that option (restricted option lists!), -1 gets stored and the setting is PERMANENTLY broken: every getValue from then on throws (including MineColonies' own GUI/AI). So my own `set(GUARD/FOLLOW)` on such towers had broken their GUARD_TASK.
- Fix: a new `GuardSettings` helper — `set()` only if the tower really offers the option (otherwise the tower is skipped), `value()` with repair + fallback, `repair()` heals an already broken index (-1 → patrol/first option). Used EVERYWHERE: guard_me (+FOLLOW_MODE), defend_here, red_alert, patrol_here, the RaidWatcher auto-line, restore/stand-down in the ErrandManager. On top, a once-a-minute sweep over all towers automatically HEALS settings broken by older versions (log "[GuardFix] Repaired...").
- The 1.9.0 session otherwise: note_player_conduct works LIVE (Lota noted a cooking compliment as KIND, Folclune an unkept commitment as RUDE), the RankGuard config created, 7 family chats, 0 errors, cuts now logged with "player talked over it".

## Done — v1.9.3 (29 Aug 2026, promises fulfill THEMSELVES — Lovkar's report)
- Report: "I promised a guard tower, built it — and it didn't register". Promises only closed through conversation (resolve_promise). New: **PromiseWatcher** — the colony notices fulfillment on its own:
  - **A building built/upgraded**: BuildingBuiltEvent/BuildingUpgradedEvent from the colony event log (the same incremental scan as deaths/births) is compared against the text of open promises — "He will build a guard tower..." + a built "Guard Tower" → the promise is automatically KEPT. Matching: squashed containment ("guardtower") or both words of the building name; single-word buildings (Barracks, Sawmill) on their word. Unit-tested (8 cases).
  - **Needs (about=housing/work/health)**: the watcher first SEES the need unmet (homeless/jobless/sick) and only when the state flips (gets a home/job/recovers) → KEPT. Two-phase so that a "bigger house" promise doesn't get falsely fulfilled by an existing home.
  - On auto-fulfillment: the same mood boost as a manual resolve + a delighted memory ("You just realized Lovkar's promise came TRUE — thank them next time you talk") + rapport + a **chat notice to the promiser** "[Promises] X noticed you KEPT your promise", so the registration is VISIBLE. Food promises stay conversation-only (too much room for false detection).

## Done — v1.9.4 (30 Aug 2026, patients stop pestering — first co-op session with Lovkar's girlfriend!)
- Lovkar's report (they played together): he sent sick Gunilda to the hospital, registered as a patient at 00:23:24, but at 00:24:18 mc_talking's urgent-contact pulled her out of the hospital to come complain to him that she's sick (sickness raises urgency, and "under care" wasn't among my mutes). Fix in CitizenNeedAssessorMixin: a colonist who is sick/hurt AND `sleepsAtHospital` (under hospital care) gets the "health" topic muted — the same machinery as promises; on recovery the flag drops and everything is back to normal. On registration the patient also gets the memory "you are admitted and being treated - REST, do not chase anyone to complain".

## Done — v1.9.5 (30 Aug 2026, couriers don't chat mid-delivery)
- Lovkar's report: couriers carrying materials to builders sometimes never drop them off — they start chatting or wander away. Cause: the courier (Deliveryman) was a WALKER profession = ALWAYS eligible for c2c chat, but a conversation freezes their work AI (the delivery stalls), and the chaperone's "come together" phase could even physically pull them away from the builder. Fix in isFreeToChat: a courier with a NON-EMPTY delivery queue (JobDeliveryman getCurrentTask/getTaskQueue) doesn't chat until everything is delivered — logistics the colony is waiting on beats small talk. On top: NOBODY with one of MY active errands (fetch/deliver/guide/messenger...) can be picked for a chat (hasErrand check). Applies to random c2c AND family chats (both go through the same filter).

## Done — v1.9.6 (30 Aug 2026, alarms name the colony + per-colony defense)
- Lovkar's wish: the alert should say WHICH colony is about to be attacked (there are two now — his and his girlfriend's). All alarm messages now carry the colony name: "Scouts of SquidVile report...", "Scouts pinpointed the raid on SquidVile: from the EAST", "RAID on SquidVile!", "...forming a defensive line at SquidVile's east border", "The raid on SquidVile is over", including the retraction and the guard's personal warning.
- A hole discovered and fixed along the way: the defense line and stand-down were GLOBAL — the end of a raid on one colony would have withdrawn the other colony's defense too, had they run simultaneously. Now: AUTO_DEFENSE per colony (a Set), ErrandManager.standDownDefense(colonyId) withdraws only THAT colony's towers (hasActiveDefense(colonyId) likewise), and the red_alert/defend_here stand-down is scoped to the speaker's colony as well.

## Done — v2.0.0-alpha.2 … alpha.10 (30 Aug 2026, release day - built from live testing)
- **Launch fix**: the voicechat optional dependency range `[2.5,)` broke loading - Simple Voice Chat reports itself as `1.21.1-2.6.22` (MC-prefixed), which compares below 2.5. Range is now `[0,)`; never version-gate voicechat.
- **Hungry citizens stop begging (alpha.2/3)**: FoodCheck mirrors the vanilla EatTask checks (FoodUtils.getBestFoodForCitizen on their inventory incl. menu fallback, checkForFoodInBuilding on the restaurant racks) and mutes the "food" urgency component while hunger is self-solvable; 10 s cache so a handed-over meal silences the begging within seconds; FOOD RULES prompt line ("you are carrying food - never ask; handed food = solved, thank once"). A citizen whose food is inedible FOR THEM (home-level quality tiers) or whose colony has nothing still complains - that is real feedback.
- **Workers stop pestering for ordered tools (alpha.3)**: SupplyCheck - while any of the worker's open requests is actively being fulfilled (ASSIGNED/IN_PROGRESS/RESOLVED/FOLLOWUP/FINALIZING: a crafter is making it or the courier is delivering), the stuck-job urgency component pauses and a SUPPLY RULES prompt line says the courier is on the way. A request stuck with no resolver keeps the complaint alive.
- **Courier pickups fixed (alpha.4/6)**: the "ordered 100, got 25" bug was the courier's bags being full of leftovers - before a pickup an idle courier (empty delivery queue; cargo is never touched) now stashes non-gear, non-food clutter into the warehouse racks; rack slots are drained fully (extractItem is capped at one stack per call); hauls respect the deliveryman hut rule (2^(level-1)+1 stacks, unlimited at max level, gear counts); partial deliveries name the exact cause ("bags FULL even after stashing" / "hut is level N - upgrade it" / "stock ran short"); order cap raised to 256.
- **The couriers' order board (alpha.5/8)**: ask a MID-DELIVERY courier for items and they no longer refuse or drop their cargo - the order goes on a shared per-colony board (cap 8, 30 min expiry) and a dispatcher hands it to the first truly free courier; because queues are never empty in a living colony, after 60 s the LEAST-BUSY courier squeezes it in between deliveries (their MineColonies queue pauses and resumes). The chosen courier gets a memory of who ordered what, the player gets chat notices for every outcome (taken, squeezed in, expired, stock ran out).
- **Configurable tavern limit (alpha.7)**: MineColonies hard-codes one tavern per colony in BlockHutTavern.canPlaceAt - the 10th mixin replaces it with a count check against `max_taverns` in the new config/colonist_errands_settings.properties (default 1 = vanilla; 2-10 lifts the limit at your own balance risk). More taverns = more visitors = more marketplace customers with MC Trade Post.
- **GUARD LEADERBOARD, idea #33 (alpha.9/10)**: every guard earns a combat score - raider kill 15, monster kill 10, minus 1 per 2 damage taken, clamped at 0 (the malus only eats earned points). Kills credited via LivingDeathEvent (arrow kills too), damage via LivingDamageEvent.Post; persisted per guard in config/colonist_errands_guard_scores.json. The 33rd voice tool `guard_leaderboard` announces the top 5 herald-style and toggles a live vanilla scoreboard SIDEBAR ("put the leaderboard on screen" / "hide it"); after each raid a chat MVP line crowns the best killer. Rivalry is roleplay: each guard's prompt carries their score, rank and nearest rival, and taking the lead writes a proud memory. Confirmed live: sidebar toggling, first blood (and first place) taken by Alfridus C. Chudde.
- Net effect of the mute work: whole play sessions now pass with ZERO urgent-contact walks - citizens only come to the player when something genuinely needs them.

## Done — v2.0.0-beta.1 (30 Aug 2026, first BETA — 38 tools)
- **`request_craft`, idea #32 (5 tools added this round)**: order a CRAFT by voice - "forge me an assistant hammer", "craft 20 iron ingots". Files a real request in the MineColonies request system exactly the way a postbox does (`IBuilding.createRequest(new Stack(item, count, count), false)` on the colony's postbox, falling back to the town hall), so a crafter makes it and a courier delivers it. ANY colonist takes the order - they hand it to the colony, they do not have to be the crafter - and a whole SHOPPING LIST goes in one breath (`items`: "assistant hammer, 20 iron ingots, 3 oak saplings"), each entry parsed for its own amount, ordered and reported on separately. A `to` parameter files the request on a NAMED BUILDING instead of the postbox ("make 64 veggie soup and put it in the restaurant"), so the courier stocks that building directly - the same mechanism the building's own requests use. And `to: "me"` hands the last leg to our own courier engine: MineColonies can only ever deliver to a BUILDING, so the order is filed at the postbox and a new CraftWatch polls it (every 2 s; the hut block counts as a rack, which is why a courier can empty it) - the moment the goods land, a dispatchable courier carries them to the player, with a chat line naming who is on the way. Partial after 15 min, gives up after 2 h with an honest message. The honesty part matters more than the plumbing: MineColonies never *fails* an impossible request, it silently parks it on the player resolver forever - so before ordering we scan every staffed crafting building for a recipe (`ICraftingBuildingModule.getFirstRecipe`) and answer plainly when nobody knows it, when the building that knows it has no worker, or when the warehouse already has some (offering fetch_item instead).
- **`courier_board`**: the couriers' order board out loud - what is queued, for whom, and how long it has waited - plus cancelling your own orders ("cancel my order", "forget the iron ingots", cancel: "all"). The board was a black box between placing an order and the chat notice.
- **`guard_gear`**: "are my guards armed?" - lists guards missing armor pieces, a weapon or a shield, with their hut level. Mirrors the game's own rules: worn armor from `InventoryCitizen.getArmorInSlot` (authoritative - the guard AI deliberately clears the entity's vanilla armor slots), and the weapon test is the same `InventoryUtils.hasItemHandlerEquipmentWithLevel` check the guard AI's `hasTool()` uses, capped by the hut level. Pairs with the leaderboard: an unarmed guard just farms damage.
- **`trade_status` + `mint_coins` (MC Trade Post)**: the marketplace economy answered from the live colony - treasury (the colony statistic `current_balance`), each marketplace with level, shopkeeper, stocked shelves, items sold, value earned and coins minted, and whether minting is possible at all. `mint_coins` mints out of the treasury and hands the coins over, explaining the two real refusals: marketplace below the configured minting level, or not enough funds. Everything is reflective behind a class-exists guard, so the addon still loads fine without MC Trade Post.
- Rank groups for the new tools: request_craft and courier_board = errands, guard_gear = military, trade_status = chat, mint_coins = jobs (it spends colony money).
- Released as **Beta**, not Alpha, for a practical reason: a CurseForge project whose files are all Alpha never syncs to the CurseForge desktop app - it needs at least one approved Release or Beta file to appear there.

## Done — v2.0.0-beta.2 (31 Aug 2026, the pirate raid)

The first raid to actually land was a PIRATE raid, and it exposed three things at once:

- **Guards drowned.** A defense line is computed from the colony bounds, and on a coastal colony half of it falls in the sea - guards walked in and drowned on the way to their post. Every post is now snapped to dry, solid ground (`ErrandManager.safePost`: free space for feet and head, no fluid anywhere, solid footing, searched outward in rings up to 14 blocks); a tower whose stretch of line is all water keeps its normal task, and `defend_here` says so out loud instead of quietly sending people into the waves.
- **The line barely fought back.** MineColonies' `AbstractEntityAIGuard.getPersecutionDistance()` gives a plain GUARD post only 10 blocks of leash - 30 for knights, 10 for archers - so a guard pinned to a post ignored pirates walking past it. Posts now also set a RALLY location (the mechanism behind the game's own rally banner): 30 blocks for every guard, they glow, and `canBeInterrupted()` returns false so nothing pulls them out of a fight. Stand-down clears the rally along with the task.
- **The raid never ended.** Pirates that stay out at sea keep `isRaided()` true forever, and our stand-down hangs off that flag's falling edge - so the whole guard force stayed pinned to the shore indefinitely. A line is now a temporary order: after 20 minutes it releases itself, with a chat line explaining that towers cover the colony better on patrol than nailed to a post. (To end such a raid outright, MineColonies' own `/mc kill raider` also marks the event DONE.)
- **The leaderboard sidebar was a ghost.** A vanilla scoreboard objective lives in the WORLD save, so a board switched on in an older session hung there frozen - which is why pre-clamp negative scores were still on screen after the clamp shipped. The sidebar's on/off state is now remembered in `config/colonist_errands_guard_sidebar.txt`, restored and redrawn on the first server tick, and any leftover objective is cleared at startup when the board is meant to be off.

## Done — v2.0.0-beta.36 (1 Sep 2026, the token that was dropped on the floor)

- The whole point of beta.30 was to stop guessing at inventory and follow the REQUEST. The log said otherwise: `[Craft] Still waiting: 16x Carrot Soup (request untracked, ...)`. `add()` took the token as a parameter and then built the `Pending` without it, so `p.token` was always null and every order silently fell back to the stock arithmetic beta.30 existed to replace.
- Fixed by actually assigning it, and the log now states which mode it is in at order time - `tracking request StandardToken{...}` or `NO request token - falling back to stock counting` - so this class of failure can never be silent again.
- **Confirmed end to end the same evening**, and the numbers show exactly why the old approach could never have worked:
  ```
  20:49:17  Ordered 10x Source Berry Pie (crafter: Lota L. Clyfton at kitchen)
  20:49:19  request IN_PROGRESS, 0 delivered
  20:54:20  request IN_PROGRESS, 10 delivered   <- 0 at the postbox, 0 in the colony
  20:54:24  Reizar Sheizen -> fetching 10x from warehouse
  20:54:58  Reizar Sheizen delivered 10x Source Berry Pie to the player
  ```
  Ten pies delivered against the request while the postbox and the colony-wide count both read zero. Only the request knew.

## Done — v2.0.0-beta.35 (1 Sep 2026, they stop making things up)

- Lovkar: "most of them tell me their leg hurts, or that there is no defence on the western wall."
- Neither was a game bug. The prompt said nothing about a colonist's body and nothing about where anything stands, so when a conversation wanted a concrete detail the model invented a plausible one. A language model with no grounding always will.
- New `ColonyMap` supplies the two missing facts and forbids the invention:
  - **health** from `getCitizenDiseaseHandler()` - "you are perfectly well, NEVER invent an ache, a bad leg, a cough"; a real illness is named and may be spoken about.
  - **bearings** - compass direction and distance from the town hall for their own workplace and home, using Minecraft's -Z = north.
  - **defences** - every `AbstractBuildingGuards` by compass side, then either "every side has a post - do NOT claim any side is undefended" or the honestly bare sides. Plus: "this colony has NO walls unless you have seen them built."
- `[Built]` announcements and the recent-builds prompt now carry the direction too, so "they put that new kitchen up on the north-east side" is a thing they can actually say.

## Done — v2.0.0-beta.31 to beta.34 (1 Sep 2026, the voice from an empty spot, and four falsehoods)

- **The voice that talked to nobody.** mc_talking gives player conversations and pregenerated clips an ENTITY audio channel, which follows the speaker by itself. Citizen-to-citizen chats get a LOCATIONAL one, which follows nothing - our chaperone moves it. We dropped the entry the moment the conversation reported ENDED, while the stream was still draining, so the pair walked off and the tail played on where they had stood. Now we keep following for a 20 s tail, and move the channel every tick instead of every fifth (a walking citizen drifted a block between updates). The same stale channel explains the "cut off mid-sentence" reports: the player walked out of range of a sound that had stopped following anybody.
- **Both voices from one head.** One conversation is one stream on one channel with no indication of who is speaking, so the voices cannot be separated. Anchoring on the first participant put the whole dialogue in one person's mouth; the channel now sits at the MIDPOINT, which is where the conversation actually is.
- **Guards moaning about gear they had.** Once a gear report is spoken it lives in that citizen's memories and the rumour mill carries it round the colony, so a complaint outlives the problem and spreads to guards it was never about. Memories cannot be un-said; the prompt is authoritative and current, so every guard is now told the truth about their own kit and forbidden to speak about anyone else's. While fixing it, the per-guard check was extracted into one `missingFor()` used by both `report()` and the prompt - the same divergence that bit us in the tavern could not happen twice.
- **`[Built]` reactions** (`ConstructionWatcher`): MineColonies fires nothing for "a building finished", so building levels are snapshotted and a RISE is the event - 0 to 1 is new, higher is an upgrade, and the first pass only seeds. Everyone gets a memory shaped by what the building means to them: the person who works there, the person who lives there, the builders who put it up, and everybody else. First live hit: `[Built] Colony 1: Graveyard (level 2 -> 3)`.
- **Hospital beds are a separate list with a stricter rule.** `EntityAISickTask.findEmptyBed` skips any bed that is not a bed, is occupied, is not the HEAD half, or lacks `world.isEmptyBlock(pos.above())` - literally AIR, where ordinary sleeping accepts a trapdoor or a panel. If nothing passes it returns WAIT_FOR_CURE and the patient stands in the hospital forever. And guards meet this code far more than anyone: `CitizenAI` sends a guard to SICK the moment they fall ill, while everyone else only goes once `sleepsAtHospital()` is already true. We re-register foot-filed hospital beds through `registerBlockPosition` (which normalises FOOT to HEAD) and name the exact block to clear when nothing is usable.
- **The sick stopped walking over to complain.** Our mute required `underCare`, which only becomes true once a bed has been found - so it never applied to the very patients a bedless hospital left untreated. Being ill or hurt is now reason enough.
- **Buildings under construction are left alone.** Lovkar: "that Residence is being built right now, and while they build, the builder moves the beds." Exactly so - blocks come and go for minutes, beds vanish from the world, entries get dropped as "no longer there" and the residents look bedless when the house is simply half built. Any building with an open work order (from `getWorkManager().getWorkOrders()`) is now skipped by every bed path, including "cannot get home".

## Done — v2.0.0-beta.25 to beta.30 (1 Sep 2026, the shop that talks, and the order that arrives)

- **Counter chat.** Lovkar's idea: when there is no customer in the shop or on the way, and two people are stood at the Marketplace, let them talk. "Customer" is checkable rather than guessed - MC Trade Post sends tavern visitors shopping through `EntityAIShoppingTask` (GOING_SHOPPING / IS_SHOPPING / PICK_DISPLAY), so a shopper is either near the counter or somewhere in the colony carrying one of those states.
- The first build could never have fired: MC Trade Post registers the marketplace with `new WorkerBuildingModule(shopkeeper, Creativity, Knowledge, false, b -> 1)` - **one** shopkeeper at every level, so "two marketplace employees" do not exist. The pair is now the shopkeeper plus whoever else is actually in the shop.
- Lovkar then showed two marketplaces facing each other across a street. Right shape, wrong mechanics: the chaperone would have walked one shopkeeper away from his counter. So the chaperone gained a STATIONARY mode - such a pair is never walked together, only turned to face each other - and the range went to 16 blocks. Two shopkeepers now call across the road without leaving their tills.
- And the obvious follow-up from Lovkar - "what if a customer comes?" - was a hole I had made: the pair are held stationary, so a shopkeeper would have stood frozen while somebody waited. The chat now re-checks for a customer every two seconds and breaks off ("Business first"), with a 3-minute cap regardless. All three paths confirmed live: two chats ended `a customer came in`, one ended `it had gone on long enough`.
- **The order that never arrived.** 64 bowls were crafted and delivered - the delivery was tracked as `Bowl x64` - while we reported "0 at the postbox", because a finished craft does not come to rest in the building the request was filed against. Widening the search to the warehouses and measuring the RISE in stock fixed the location but not the principle: bowls are eaten by the restaurant, so the pile rose by 3 and fell below its own baseline, and a 64-bowl order hand-delivered 3. Stock was always the wrong measure. beta.30 moved to the request token itself - `getState()` and `getDeliveries()` - which is exact, immune to consumption, and reports FAILED in a minute instead of 45 minutes of silence.

## Done — v2.0.0-beta.19 to beta.24 (31 Aug 2026, three silent MineColonies traps)

- **"He has a home, and he still stands in the tavern."** The answer is one state earlier than the bed code, in `EntityAISleep.walkHome`: with a home you must be INSIDE it (`homeBuilding.isInBuilding(myPos)`) before FIND_BED is ever reached; without one, being near the tavern is enough. A colonist who cannot walk into their own house never leaves WALKING_HOME, so `goHome()` is retried forever, silently, and they stand where the failed path left them - the same shape as the builder stall. Now detected after four minutes with coordinates, distance and the way in to check.
- **Beds that can never be slept in.** The only entry MineColonies ever removes is one whose block is gone; a FOOT-half entry passes that test and fails the HEAD test, so it is a permanent dead slot that hands one resident nothing every night. We run MineColonies' own normalisation over the stored list - foot entries re-filed onto the pillow, entries pointing at nothing dropped, duplicates trimmed - and, when a building is then short, register real beds the schematic never filed. Live: the tavern's `549,79,3612` moved to its pillow at `549,79,3611`, plus two phantoms elsewhere, and the nightly complaints stopped.
- **The restart that waits forever.** `scheduleRestart()` only raises a flag; the restart is a STATE_BLOCKING event gated on `shouldRestart() && isPaused()`, so a restart on its own sits there indefinitely. Pause-then-unpause was never a trick - it is the only mechanism. `RestartNudge` now finishes the job: a restart pending more than 20 s gets a pause, and the worker is handed straight back once the flag clears. We only un-pause workers we paused, and give up after three minutes rather than leave anybody parked.
- Same investigation explained why a wedged builder also stops eating: `canAIBeInterrupted()` is `getState().isOkayToEat()`, and BUILDING_STEP, MINE_BLOCK and LOAD_STRUCTURE are all flagged false. Not eating, not building and not walking were one fault, not three.
- **A leaderboard that rewarded hiding.** Week 11 closed with three guards tied on 10 points and the crown went to whichever the map iterated first - while Benedict, with twice the kills, came third. The malus subtracted TOTAL damage taken, which grows with every fight you turn up to, so it punished the role rather than the skill. It is now a RATE - damage per fight, capped at a third of what was earned - so one more kill always raises your score, and ties break on kills, raider kills, assists, then less damage taken. Week 12 closed at 111 points with two lead changes on the way.

## Done — v2.0.0-beta.17/18 (31 Aug 2026, assists, and where the homeless sleep)

### Assists on the guard leaderboard
- Lovkar: "what about giving some points to the ones who helped and did damage, not just the one who killed it?"
- Every hit a guard lands on a hostile is now remembered per victim (`LivingDamageEvent.Post`, with `getSource().getEntity()` so arrows credit the shooter). On death the killer takes the kill as before and everyone else who did real damage takes an assist: raider assist 6, monster assist 4, against kills of 15 and 10.
- "Real damage" is the smaller of 15% of the victim's max health and 4 - one stray arrow does not count, two decent hits do. If the PLAYER lands the killing blow the guards who wore the thing down still get their assists; `killerId` is simply null.
- Wounds are dropped on death and purged after 90 s, so nothing accumulates. Assists count in both the weekly and career totals and reset with the season.

### Prudence stands in the tavern and never sleeps
- Lovkar: "builder Prudence was just in the tavern without going to sleep, and she does not even live there."
- **`CitizenData.getHomePosition()` is the answer.** With no home building it falls back to the colony's tavern, then the town hall:
  ```java
  if (homeBuilding != null) return homeBuilding.getPosition();
  IBuilding tavern = ...getFirstBuildingMatching(b -> b.getBuildingType() == ModBuildings.tavern.get());
  if (tavern != null && tavern.getBuildingLevel() > 0) return tavern.getPosition();
  ```
  `EntityAISleep.goHome()` walks to that position, and `findBedAndTryToSleep()` is wrapped in `if (getHomeBuilding() instanceof AbstractBuilding)` - so a homeless citizen is sent to the tavern and the bed code never runs at all. She was homeless, not confused.
- BedCheck skipped exactly this case (it `continue`d on a null home). It now reports homeless non-guards by name, says where the game parks them, and the colonist can say it themselves.

### Two smaller things the live test exposed
- The chat line printed `com.minecolonies.building.tavern` - `getBuildingDisplayName()` returns a translation KEY, not a name. Now resolved through `Component.translatable(...)`, with the last segment of the key capitalised as a fallback.
- Fixing the FOOT-half bed was not enough on its own: `BedHandlingModule.onBlockPlacedInBuilding` normalises FOOT to HEAD only when a player places the block (schematic beds never go through it), and the re-registered bed is **appended to the end** of `bedList`, so the index-to-resident mapping shifts. The advice now says to unassign and re-assign the resident afterwards, which is what Lovkar had to work out for himself. It also explains why the warning named Hada Fuyumi one night and Gunilda Z. Benett the next for the same bed at 549, 79, 3612.

## Done — v2.0.0-beta.16 (31 Aug 2026, barracks are not bedrooms)

- beta.15's bed check immediately fired for the Barracks and the Guard Towers. Two mistakes, both mine:
  - It walked BUILDINGS and asked each for `getFirstModuleOccurance(AbstractAssignedCitizenModule.class)`. A barracks or a guard tower carries an assigned-citizen module too - the guard WORK roster - so the check compared guards-on-duty against beds and called every one of them bedless. `EntityAISleep` never hits this because it only ever looks at `getHomeBuilding()`, which is a home by definition.
  - Guards never sleep at all. In `CitizenAI.calculateNextState` an `AbstractJobGuard` short-circuits to EATING, SICK or WORK and the sleep branch is never reached, so a tower with fewer beds than guards is not a fault - it is how the game works.
- BedCheck now walks CITIZENS, skips anyone with a guard job, and asks each remaining one for `getHomeBuilding()` - the same object the sleep AI uses. The "not enough beds" count only counts residents who actually sleep, and the daily warning is now keyed by citizen rather than by bed position, so two residents of one building can each be named.

## Done — v2.0.0-beta.15 (31 Aug 2026, the leaderboard becomes a season, and the tavern bed mystery)

### Weekly guard seasons
- Lovkar: "maybe the guard leaderboard should reset every Minecraft week, so the same guards are not always on top, and the weekly top 3 get a reward."
- The board is now a SEASON. Every entry carries two sets of numbers: `weekKills`/`weekRaiderKills`/`weekDamageTaken`, wiped every seven Minecraft days, and the career totals, which never reset. `score()` is the weekly score (leaderboard, sidebar, rivalry prompt); `careerScore()` is the lifetime one, and the memorial reads that, so a fallen guard is still remembered for everything they ever did.
- The week is `overworld.getDayTime() / 24000 / 7`, persisted in `config/colonist_errands_guard_week.txt`. On a world that has never had the file the current week is seeded **silently** - no award can fire on the pass that first learns what week it is.
- On rollover, per colony: the top three are announced in chat, each gets real MineColonies skill experience through `ICitizenSkillHandler.addXpToSkill` on their own primary and secondary skill (120/70/40, secondary at half), `wins`/`podiums`/`bestWeek`/`lastWeekRank` are updated, and the weekly counters are wiped. Note the prize is capped by MineColonies itself - `addXpToSkill` refuses past `(homeLevel + 1) * 10` - so housing your best fighters properly is what lets them actually collect it.
- Everyone who fought that week gets a memory about it: the champion a proud one naming the win number, the other two a podium-but-not-the-crown one, everyone else "the board is back to zero, this is my chance". The prompt now carries days left in the week, last week's placing, career kills and how many weeks they have won, so the rivalry has a history instead of one running number.
- `guard_leaderboard` reports the week, the days left and a "past champions" hall of fame; the sidebar title reads "Guards - Week N".
- Migration: existing score files keep their career numbers and start the first season at zero, so the board looks empty until the next kill. That is the season starting, not lost data.

### The tavern bed that never works
- Lovkar: "every night one colonist in my level 3 tavern gets stuck and cannot get into bed until I unassign their home and assign it back."
- **Root cause, in `EntityAISleep.findBedAndTryToSleep`.** MineColonies does not look for a free bed. It hands each resident the bed at *their own index in the assigned-citizen list*: `index = assignedCitizens.indexOf(me)`, then `bedList.get(index)`. If `index >= bedList.size()`, or if that one bed fails its checks, `usedBed = homePos` - the hut block - and the resident walks there and stands all night. Always the same colonist. Un-assigning and re-assigning the home works because it moves them to a different index, which hands them a different bed.
- The bed also has to pass two tests that are easy to fail in a tavern: it must be registered on the `BedPart.HEAD` half, and the block directly above the pillow must be a bed, a `PanelBlock`, a `TrapDoorBlock`, or non-solid. A ceiling block or a slab above the pillow makes that bed permanently unusable - and MineColonies never removes it from the list, so the resident at that index is stuck forever.
- New `BedCheck` runs the same three tests ourselves, every in-game evening, for every building with a bed module. It broadcasts the exact block to break or the bed to add ("has a solid block sitting right above the pillow (Oak Planks) - break the block at x, y, z"), feeds the affected colonist a prompt block so they can say why they are not asleep, and `why_unhappy` now mentions it for that citizen.
- We deliberately did NOT mixin the sleep AI to pick a free bed instead. That would touch every colonist's sleep in the colony; naming the broken block is the smaller and safer fix.

## Done — v2.0.0-beta.14 (31 Aug 2026, a stall is not the same as a night's sleep)

- beta.13 shipped the stall detector and it immediately cried wolf: it warned that builders had "made no progress" while they were asleep, and again while they were simply waiting on a delivery. "No progress" on its own is not a fault - at 2am it is the correct behaviour.
- **The gate.** Before the stall clock is allowed to run, BuildWatch now asks MineColonies *why* the builder is not laying blocks, and only counts the silence when the honest answer is "no reason at all". Everything it asks is a MineColonies fact, verified by decompile, not a guess:
  - `CitizenAIState` (via `AbstractEntityCitizen.getEntityStateController().getState()`) is the colonist's master state machine. `CitizenAI.calculateNextState()` returns SLEEP, EATING, SICK, MOURN, FLEE, IDLE or WORK/WORKING, and only in WORK/WORKING is the job AI ticked at all. Anything else means the builder is not even trying to build.
  - `IBuilding.getOpenRequests(citizenId)` is the builder's real material queue; requests still in a live state (not COMPLETED / RECEIVED / CANCELLED / OVERRULED / FAILED) are named in the answer via `IRequest.getShortDisplayString()`.
  - `ICitizenData.isIdleAtJob()` is exactly `jobStatus == JobStatus.STUCK`, and in `AbstractEntityAIBasic` that flag is set by `checkForToolOrWeapon` - it means *missing a tool*, never a failed walk. So it is safe to treat as "blocked", and it can never mask the real stall.
  - plus our own signals: on an errand for the player, in a conversation, under hospital care, `shouldRestart()`, and raining while `workersAlwaysWorkInRain` is off.
- While any of those hold the stall clock is **frozen** (not merely paused - it is reset every pass), so a builder who sleeps eight minutes does not wake up eight minutes closer to a false alarm.
- The colonist now says the honest reason in their own words - "asleep", "eating", "waiting for materials - still on order: 64 Oak Planks", "mourning a death in the colony" - and is explicitly told they are NOT stuck and nothing is broken. `build_status` reports the same, and closes with "nothing is stalled" instead of the walk-stuck advice when no build is genuinely stuck.
- **New, separate warning:** a build that sits still *specifically* on unfilled deliveries for 20 minutes gets its own chat line ("has been sitting on X for N minutes - waiting for materials - still on order: ... Nothing is broken: check the warehouse stock and whether a courier is free."). That is a supply problem, not a stall, and it is worded as one.
- The real stall warning now says "awake, on site and not short of anything" so the message itself carries the proof that it is not a false alarm.
- **The decisive signal, found in Lovkar's beta.13 log.** The false warnings named a position each time - Beatrice B. Chubb was reported "no progress" at 499,90,3626, then 545,97,3693, then 522,79,3645. She had walked hundreds of blocks between warnings, so she was plainly not stuck; the build's `(order, stage, iterator)` triple simply does not advance while a builder is off fetching. So BuildWatch now reads the JOB ai's own state (`AbstractAISkeleton.getState()`, public and final) and only lets the stall clock run in the states where blocks are actually being laid: BUILDING_STEP, MINE_BLOCK, START_BUILDING, LOAD_STRUCTURE, COMPLETE_BUILD. That is precise rather than lucky - the silent stall is `return this.getState()` inside `structureStep` (BUILDING_STEP) and `doMining` (MINE_BLOCK), so it can only ever happen in that set.
- Every other builder state is reported honestly instead: NEEDS_ITEM ("waiting for materials - still on order: ..."), GATHERING_REQUIRED_MATERIALS ("fetching the next load of materials from their hut"), INVENTORY_FULL/DUMPING, PICK_UP, PAUSED ("their hut is switched to pause"), IDLE/START_WORKING ("between steps, not on the wall yet").
- Because of that, an open request no longer suppresses anything on its own: a builder in BUILDING_STEP who cannot walk is still reported as stuck even with a delivery pending, which is right - `structureStep` never looks at materials.

## Done — v2.0.0-beta.13 (31 Aug 2026, the builder who would not finish — 42 tools)
- Lovkar's builder sat at 99% on the Chef's Kitchen with every resource in hand, and when asked, said she was stuck on a RESIDENCE she had finished long ago. Two symptoms, one cause: nobody was telling anyone the truth about the build.
- **Why builds stall silently.** In `AbstractEntityAIStructure.structureStep` the AI returns the SAME state forever when the builder cannot WALK to the next position - no error, no chat, no progress. Materials are irrelevant to it. (A failed PLACEMENT does log "Failed placement at: x y z"; his log had none, which is how we knew it was the walk.) The last stages - DECORATE and SPAWN, the fittings and entities - are where the unreachable spots live.
- **`build_status` + a watcher.** Every builder's work order, stage, standing position and time-without-progress are now tracked; a build that has not moved for six minutes puts a `[Build]` warning in chat saying where the builder stands and that the way needs clearing. The tool answers "how's the kitchen coming?" and "where are you stuck?" on demand.
- **And the builder stops naming the wrong building.** Their own prompt now carries the current work order and stage, with an explicit instruction never to name a different one - plus, when stalled, the honest "I cannot reach the next spot" so the answer is useful instead of mysterious.

## Done — v2.0.0-beta.12 (31 Aug 2026, what a research actually DOES)
- Lovkar, watching the first live research announcement: "'Improved Swords' is the name, but what it really did was unlock the Combat Academy." Research titles are marketing; the meaning lives in the effects, and MineColonies already stores that in plain English on every research (`IGlobalResearch.getEffects()` -> "Unlocks Combat Academy"). Completion announcements, the memory every citizen gets, the in-progress list in each colonist's prompt and the `research_status` tool now all carry the effect alongside the name, with the prompt telling colonists that the bracketed part is the bit people actually care about - "we'll be able to build a combat academy", not "they finished Improved Swords".

## Done — v2.0.0-beta.11 (31 Aug 2026, three small lies fixed)
- **Patients stay put, properly this time.** The first hospital fix keyed on `sleepsAtHospital()`, which only turns true once the citizen is already tucked into a bed - so everyone on their way there, or waiting on the healer, was still fair game for an urgent walk across the colony. The hospital keeps a PATIENT FILE (`BuildingHospital.getPatients()`) from the moment it takes someone on; that file is now the signal, for the health complaint AND for citizen-to-citizen gossip, so a patient is not lured out of bed for a chat either.
- **No more "good morning" at dusk.** Pregenerated greetings are written and voiced ahead of time, with the prompt describing the world at THAT moment, then sit in a cache until the player walks past - possibly many in-game hours later. Rather than throwing away good audio, the clock is taken out of the clips: every pregeneration prompt now asks for a greeting that works at any hour and forbids "good morning/evening" and references to light, weather or meals. Live conversations, which do know the time, get an explicit THE HOUR line instead.
- **They greet before they run.** A colonist with a hello queued for a nearby player was being picked by the urgent-contact system at the same time, so they sprinted over on top of their own greeting. While a greeting is queued for a player standing within 24 blocks, that citizen's urgency is held at zero - the greeting IS the contact.

## Done — v2.0.0-beta.10 (31 Aug 2026, "they say they have no home")
- **HOME TRUTH prompt line.** Lovkar's colonists talked as though they slept in the mud despite having houses. Two MineColonies facts collide, neither of them a bug: a citizen counts as homeless only while `getHomeBuilding() == null` - BUILDING a house is not enough, they must be ASSIGNED to one, and a residence only holds so many by its level - and the housing happiness factor is literally `homeLevel / 3.0`, so a level 1 house scores 0.33, which Talking Colonists renders as "the shack you're living in barely counts as a proper home". Every colonist now carries the plain truth about their own roof: the house's name and level with an explicit "never claim you are homeless" when they have one (and, at level 1-2, permission to wish for an UPGRADE, which is the honest version of the complaint); a guard is told their tower is their quarters; and someone genuinely unassigned is told so, plus why - so the complaint they make is the one the player can actually act on.

## Done — v2.0.0-beta.8 (31 Aug 2026, the colony cares about research)
- **Honest wording for ordinary deaths (beta.9)**: the first real memorial line read "Roger A. Marescallo fell defending the colony against a Zombie" - Roger was a builder caught by a zombie, not a soldier. Guards now "fall defending", everyone else "was killed by", and the prompt asks colonists to speak of the GUARDS who died fighting as brave. The memorial's whole premise is that nothing in it is invented, and that included the framing.
- **Research reactions and awareness.** The university's work is the colony's biggest shared story - months of a researcher's life, every trade feels the result - yet nobody ever mentioned it unless the player asked. Now: the moment a research completes, the colony hears about it (a `[Research]` chat line plus a memory for EVERY citizen, so they raise it themselves afterwards; the researcher gets a prouder, first-person one), and every colonist's prompt carries what is on the benches right now with rough progress plus the last three finished, so "what are they working on up there?" gets a real answer from anyone, not just the university staff. On the first pass of a session everything already completed is recorded silently - no colony announces a hundred researches it finished months ago.

## Done — v2.0.0-beta.7 (31 Aug 2026, the ones who came back)
- **Resurrection, caught before it became a bug.** Lovkar asked what happens if the graveyard's undertaker brings someone back - and he was right: MineColonies really does resurrect the buried (`CitizenManager.resurrectCivilianData` from the grave's stored NBT), which would have left a walking, talking colonist sitting in the roll of the dead forever. Every few seconds the roll is now checked against the living: a match by name AND citizen id (the grave NBT keeps the id, so a new colonist handed a dead one's name does not count) moves them from "our dead" to "came back from the dead", puts them back on the guard leaderboard, and announces it in chat. Colonists then speak of them as a wonder rather than mourning someone who is standing in front of them.

## Done — v2.0.0-beta.5 (31 Aug 2026, telling the colony what matters — 41 tools)
- **`arm_guards`, Lovkar's "prioritise arming all my guards"**: files the colony's own equipment requests for every missing armor piece and weapon at once, using MineColonies' `Tool` requestable with each hut's level cap - the same request the guard's own AI would eventually make at their hut, only now, for everyone, in one order. Pieces already on order are counted and not duplicated, and the answer is honest about the ceiling: a piece that never arrives means nobody in the colony can craft it and it is parked on the player's clipboard.
- **`prioritize` (idea #10)**: move a build order to the front of the builders' queue by voice, or just have the queue read back. Real mechanism, not theatre - `WorkManager.getOrderedList()` hands every builder their work sorted by work-order priority, so raising one genuinely reorders the queue (a builder mid-job still finishes that job first, and the tool says so).
- **Relative ordering (beta.6)**: "build the barracks after the one you're on now", "do the warehouse after the hospital". `after` takes another build order's name, or `current` - which resolves to the order claimed by the builder you are TALKING to, falling back to the only claimed order and honestly refusing to guess when several builders are busy. Because priorities are only a sort key, the queue is laid out again from scratch in the wanted sequence rather than nudging one number and hoping.
- Deliveries deliberately NOT touched: MineColonies fixes delivery order by when a request was made and offers no setting for it (their own FAQ says so), so the tool tells the player that instead of pretending. Pickup priority is a per-hut setting and a different thing entirely.

## Done — v2.0.0-beta.4 (31 Aug 2026, the raid that would not end)
- **Stuck-raid watchdog.** A pirate raid stayed "in progress" with every pirate dead and MineColonies' own `/mc kill raider` reporting *0 entities killed*. The reason is in `AbstractShipRaidEvent.isRaidActive()`: a ship raid counts as active while its **spawners**, raiders or respawns are non-empty - so the empty pirate ship parked offshore kept the event, the alarm and the defense line alive indefinitely, and killing entities could never clear it. The watcher now counts living raiders in the world every 30 s while the raid flag is up, and after five minutes with not one alive it marks the raid event DONE itself - the same call the kill command makes - which lets the normal end-of-raid chain (stand-down, MVP, chat) finally run.

## Done — v2.0.0-beta.3 (31 Aug 2026, the colony remembers its dead)
- **THE FALLEN, 39th tool**: every citizen who dies is written into a roll of honour (`config/colonist_errands_fallen.json`) with their job, who or what killed them, the colony day, and - for guards - the kill record pulled straight from the leaderboard. That roll is appended to EVERY colonist's prompt, so they speak of their dead by name and of the ones who died fighting as brave, bringing them up on their own when the talk turns to raids, danger, guards or graves. Nothing is invented: only real deaths and real deeds are stored, so "Waring took six pirates with him" is the truth. A `remember_fallen` voice tool reads the roll on request ("who did we lose?"), and a combat death now also puts a `[Memorial]` line in chat naming what they did first. Fallen guards leave the live leaderboard - their deeds move to the memorial.
- **Leaderboard rebalance, from real raid numbers**: the damage malus is now capped at HALF of what a guard earned. The first pirate raid showed why - a guard who killed six pirates took 157 damage doing it and scored 11, while one who killed three and died scored 0, the same as a guard who did nothing. Same raid under the new rule: 63 / 45 / 28 / 20 / 20 / 15 / 13 / 5, an actual ranking.
- **No false alarm on load**: a raid still running when the world loads (pirates stuck at sea keep `isRaided()` true forever) used to fire the whole "RAID!" chain again on every login. The watcher now seeds each colony's raid state silently on its first pass and only reacts to real changes after that.

## Planned — what is actually left
- **`build_priority` (#10)** — influence the construction order by voice (the work-order API; the riskiest one left).
- **`undertaker_collect` (#20)** — send the undertaker to a specific grave.
- **More per-profession commands (#15)** — the pattern works (farmer_plant); next candidates are the lumberjack's tree type and courier pickup priority. A universal "do exactly X" stays impossible: each profession is a fixed state machine.
- **Live-mode c2c chaperone** — the citizen-to-citizen chaperone currently covers the pregenerated path only.
- **Leaderboard polish** — a weekly-leader happiness boost, deliberately skipped for the first release.
- **Upstream** — offer sshcrack the memory-fence fix (`response_mime_type: "application/json"` on the Flash request) and the voice-name blocklist, both currently worked around by mixins here.

## Built but still unproven in game
Everything below works in code and compiles clean, but has not yet been seen firing during play.

**Proven in play since the last revision of this list** — the stall detector telling four busy builders apart from one genuinely wedged, `build_status` naming each builder's real reason, weekly guard seasons closing with awards, the bed-list repair, `[Built]` reactions, the counter chat with its customer interrupt, the memorial on a real death, and `request_craft` delivering into the player's hands.

**Still unseen:**
- The automatic defense line actually forming (beta.42 anchor), stand-down, post-raid MVP. Seen on 2 Sep: dusk warning, pinpoint 25 s before MineColonies' own horde message, the alarm, the messenger running 73 blocks to the player, "the colony held". (Raids can be forced with `/mc colony raid <colonyID> tonight` followed by `/time set 13000`.)
- The rank gate against a non-officer player; `patrol_here`; a `notify_when` trigger firing; a multi-warehouse courier round; mourning and births.
- Assists actually scoring - every podium so far has read "0 assists".
- `RestartNudge` completing a stuck restart by itself.
- The hospital bed repair, and a sick guard staying put to be cured.
- `ColonyMap` grounding - no more invented aches or invented walls.
- The 20-second audio tail, and a two-person conversation sounding as if it comes from between them.

## Done - v2.0.0-beta.37 (1 Sep 2026)

**Conversations that end properly instead of being cut off** (Lovkar's report: a counter chat
that reached the three minute cap was chopped off mid-word). The log had the case exactly:
22:19:22 start, 22:22:22 cut, on "Turn 7 of 10" with the second speaker still talking.
- New `ChatWindDown`: a conversation is now asked to finish rather than killed. At two minutes
  a line is dropped into both live sessions telling them to bring it to a close; at three,
  mc_talking's own `endConversationWhenPossible()` lets the current sentence play out and then
  closes cleanly; only at 3:45, if they somehow carry on, is the audio cut. The order matters -
  setting the end flag first would close the session before the goodbye was ever spoken.
- A customer walking in gets the same treatment with a twelve second grace: one line to excuse
  themselves, then back to the counter.
- Only possible for LIVE_WEBSOCKETS conversations. FLASH_TTS renders the whole dialogue as a
  single clip before you hear a word of it, so for that kind there is nothing to negotiate with -
  it ends when the clip ends, and `endAfterThisLine` reports that it could not help.
- **Bug found while doing it:** `ShopChats` never noticed a conversation ending by itself, so the
  shop stayed "busy" indefinitely and every chat was eventually ended by the clock, whether or not
  it was still going. It now registers `setOnStateChanged` and clears immediately.

**Three-way huddles** (Lovkar's question: can more than two of them talk at once?). Not in one
voice - and it is worth recording why, so nobody tries again:
- Gemini's multi-speaker TTS accepts **at most two** speaker voices. Confirmed against Google's
  own docs for `gemini-3.1-flash-tts-preview`, so a third name in the transcript has no voice.
- LIVE_WEBSOCKETS wires two live sessions as peers, each one's transcript fed to the other, one
  holding its audio while the other speaks. The wiring is one-to-one; there is no third socket.

So `GroupChats` builds a huddle the way people actually stand in one: three of them together, and
the conversation goes round the circle - A with B, B turns to C, C rounds it off with A. Each leg
is a real two-way dialogue, and because mc_talking writes each pair a memory of what they just
discussed, the next leg carries on rather than starting over. Two mc_talking rules are worked
around deliberately: the per-citizen cooldown is lifted for the pair about to speak (and only
them), and a leg that cannot get two free agent slots is skipped rather than evicting somebody
else's conversation. Rare by design - one huddle per quarter hour, the same trio at most every
three quarters, and only where a player is close enough to hear it. `group_chats=false` in
`colonist_errands_settings.properties` turns it off; the key is appended to an existing settings
file automatically.

**Also worth knowing** (from the same log): `conversationMode` is AUTO, and Gemini TTS failed on
6 of 18 conversations with "Expected audio chunks for TTS generation, but none were streamed" -
those fell back to Live WebSockets. Both paths are therefore live in normal play, which is why
the wind-down had to handle each of them differently.

## Done - v2.0.0-beta.38 (2 Sep 2026) - from the first beta.37 log

**The wind-down works.** 23:04:18 counter chat starts (live mode, Turn 4 of 10 by two minutes);
23:06:18 "asked them to wrap up"; 23:06:33 Theobaldus's own session calls `end_conversation`;
23:06:42 his closing line finishes ("...let's hope that marketplace upgrade goes smoothly") and
both sessions close cleanly - "Counter chat over - they finished it themselves". Without the
nudge it would have run to ten turns, about five minutes.

- **Chat timers now count server ticks, not the wall clock.** The one customer case in the log
  read "they did not stop" - but Lovkar had paused the game (ESC) a second after the customer
  walked in, the server stood still for 22 seconds, and on resume the 12 s grace had "expired"
  with nobody having had a chance to speak. A paused game must not count against anyone.
  `ShopChats` and `GroupChats` both converted.
- **No huddle in 32 minutes** - selection was too strict. It required all three to be off
  mc_talking's 120 s per-citizen cooldown at the same instant, near the player - exactly where
  mumbles and greetings keep everybody on cooldown. The cooldown is no longer a bar for picking
  the trio (it is lifted for each pair anyway); the search runs every 30 s instead of every two
  minutes (cheap - the global and trio cooldowns are what keep rounds rare); and once per five
  minutes the log says why nothing started ("2 group(s) of three stood together, 2 with no
  player near enough to hear..."), so a quiet feature can be told from a broken one.
- **Homeless warning once per session.** The same three homeless names were in chat three times
  in twenty minutes: once per game evening as designed, but a skipped night is a new evening and
  the advice ("build them a house") does not change. `Problem.homeless` marks it; `WARNED_HOMELESS`
  says it once. Broken-bed warnings keep their once-per-evening rule, because those do change.
- Zero WARN/ERROR from colonist_errands across the session; world saved cleanly at 23:24:33 and
  the client stopped normally at 23:24:38 - the black screen Lovkar saw afterwards was outside
  Minecraft.

## Done - v2.0.0-beta.39/40 (2 Sep 2026) - "sometimes they are still cut off mid-sentence"

Lovkar's report after beta.38. The wind-down was not the culprit; the cuts are in mc_talking's own
audio shutdown, two of them, one per conversation mode. Both found by reading the decompiled code,
both fixed on our side.

**Live sessions: `close()` throws the last sentence away.** `GeminiWsClient.close()` ends with
`stream.close()`, and `GeminiStream.close()` empties the audio queue and stops the voice-chat player
at once. A session is closed the moment Gemini reports the turn *generated* - and generation runs
well ahead of playback - so whatever was still queued is gone. In a citizen-to-citizen live
conversation it is worse: the second speaker's reply is HELD until the first has finished, and
mc_talking only releases it to a peer whose socket is still open, so the close always arrived first
and the reply was never heard at all. That also means our own `endConversationWhenPossible()`
wind-down was cutting the goodbye it had just asked for. New `StreamDrain`: a `@Redirect` on that
one `stream.close()` call hands the stream over instead; it is flushed, a held reply is released once
the other speaker has run dry (whoever closed first speaks first), the citizen is kept busy and
standing until the queue is empty and the player has stopped, and only then is the stream closed -
30 s hard limit, and a new session for the same citizen cuts it at once, so the player still comes
first. Barge-in is untouched (that path is `stop()`, not `close()`).

**Flash/TTS: the goodbye was never played.** Audio arrives in chunks; the stream only moves them to
the player once 192,000 bytes (four seconds at 24 kHz) have piled up, and whatever sits below that
line when the last chunk lands only plays on `flushAudio()`. `GeminiWsClient` and the pregen player
flush; the Flash conversation path never did, so up to four seconds of the end of *every* Flash
conversation - the goodbye - was dropped. `CitizenConversationMixin` now flushes at `setState(ENDED)`,
the exact moment the last chunk is in.

**And the pair walked off with the audio.** ENDED is when the last chunk is RECEIVED; half a minute
of dialogue can still be queued, and mc_talking marks the pair not-busy at that point, so
MineColonies took their legs back while their voices carried on. `C2cAudioFollower` no longer trusts
a 20 s tail: it watches the stream itself, keeps both participants busy and standing until it has run
dry (three minute cap), then lets them go. The player addressing one of them cuts the audio and
frees them - they come first. `GroupChats` waits for the previous part's audio (any of the three
busy) before the next part starts, so the parts of a huddle no longer overlap or fail on a busy check.

New log tags: `[Voice] <name> - stream closed: played to the end` and
`[C2C] Conversation audio finished - the pair are free to go`.

## Done - v2.0.0-beta.40 (2 Sep 2026) - network storage counts as warehouse stock

Lovkar linked a MineColonies Compatibility **Common Network Storage** block to his warehouse and
the couriers started filling the chests behind it. Read from that mod's code, for the record:

- The block combines every inventory on its six sides into one "network storage view"; right-click
  sets Insert/Extract/both; the **Warehouse** (only) has the Network Storage module and GUI tab.
- Placing the block by hand registers nothing - MineColonies only reports builder-placed blocks to a
  building. The block is found by a **periodic flood-fill search** from the warehouse hut block
  (the warehouse has no citizens of its own; couriers belong to the Courier's Hut), capped at
  8000 nodes and bounded by the schematic - so it must sit within ~10-15 blocks of the hut block,
  and the result lands a colony tick later (1-2 min). That is why "Not linked" persisted until he
  moved it next to the hut block.
- Their courier-dump mixin puts items into linked storage **before** the racks; requests and
  courier pickups read it too. The warehouse window's "Empty Slots: 80/864" is MineColonies' own
  rack count and knows nothing about it - cosmetic. "Warehouse is full" only fires when chests
  and racks both refuse a stack.

Consequence for us: everything in this addon read racks only, so `check_stock` (and everything
built on `countStock` - fetch, deliver, courier board, request_craft's stock check), fetch errands
and CraftWatch's sourcing would all have missed most of the stock. New `NetworkStorage` helper -
reflection only, MineColonies Compatibility stays optional - counts linked storage, lets fetch
errands take from it once the racks run dry, and makes our own courier stashes go there first,
in the same order as the mod's own dump. Logs `[Stock] ... network storage found` once, and
`[Courier] X took Nx item from the warehouse's network storage` when it is used.

(beta.39 was never installed - superseded by this build before the game was closed.)

## Done - v2.0.0-beta.41 (2 Sep 2026) - small talk no longer evicts a running conversation

From the beta.38 session log (84 min, zero WARN/ERROR from us): 21 citizen conversations, and
**eleven "Evicted slot ... to make room"**. mc_talking has `maxConcurrentAgents` slots (four here);
a live citizen-to-citizen conversation takes two, a mumble one. When the pool is full,
`claimSlot` does not refuse - it evicts the OLDEST non-player session, closing it mid-sentence - and
`hasLowPriorityCapacity` counts those evictable sessions as free, so every caller thinks there is
room. A third random conversation somewhere in the colony therefore kills the one the player is
listening to. Together with the two audio bugs fixed in beta.39/40, that accounts for the cuts.

New `SlotGuard` + `ConversationManagerMixin`, priority by caller:
- **Small talk** (live c2c of any origin - random, family, shop, huddle - and idle mumbling) may only
  take a free slot or one whose holder has gone quiet (session closed, not an urgent contact, not
  walking to the player). Otherwise it does not start: `[Voice] No free slot for small talk and
  everyone is still talking - X waits instead of cutting somebody off`.
- **Urgent contact** and **guard threats** keep the right to evict chatter, but eviction now prefers
  a quiet slot over a busy one.
- **The player** is untouched - always gets a slot.
The marking is done by the callers themselves (`SlotGuard.enter/exit` around
`performLiveWebsocketConversation` and `startMumbling`, reset every tick), so nothing is guessed.
`hasLowPriorityCapacity` became honest for everybody, including our own ShopChats/GroupChats checks
that had believed it.

Also from that log:
- **Both huddles fired.** 14:42 Hada/Gunilda/Gerard, all three parts, "the three of them had all had
  their say"; 15:09 Michael/Sampson/Petronilla, two parts, then "Michael could not pick the
  conversation up" (busy). In beta.38 the parts came 35 s apart in Flash mode - the audio of one
  part still playing while the next generated; beta.40's wait-for-busy fixes that.
- Shop customer grace raised 12 s -> 25 s: a live session must finish its sentence before the
  excuse can even be generated; the log showed "did not stop" at 14 s.
- Homeless warning: once per citizen per session, confirmed (3 at 14:19, one new at 14:49).

## Released - v2.0.0 (2 Sep 2026)

The beta.42 code, rebuilt with the version `2.0.0` and nothing else changed. CurseForge file of
type **Release** (beta.36/41/42 stay as Beta files), GitHub release `v2.0.0` marked latest, README
and the CurseForge description no longer carry the BETA banner. The honest gaps stay in the docs:
resurrection and births rarely seen, the raid line verified offline on Lovkar's save but not yet
watched forming in play.

## Done - v2.0.0-beta.42 (2 Sep 2026) - the defense line that formed in the sea

Lovkar forced a raid on beta.41. The alarm chain worked end to end - dusk warning, `[Alarm]
Scheduled raid pinpointed: from the north-east (spawn 592, 63, 3516)` 25 s before MineColonies'
own "huge horde" message, the RAID broadcast, Bartolomew running 73 blocks to warn the player and
starting the conversation on arrival, "the colony held" after `/kill` - and the defense line failed
completely: **13x "[Defense] No dry ground near 704,3505", AUTO line - 0 tower(s)**, twice (pinpoint
and raid start).

Cause: the anchor was the colony's bounding-box corner (`maxX + 8` because the direction was mostly
east). SquidVile sprawls far to the east (a building at x=696) while the raid spawned at x=592 - so
the "line" stood 110 blocks BEHIND the attackers, in the sea, with no fallback at all. The same
math served the defend_here voice command's border and raid modes.

New `DefenseLine.anchor(colony, out, spawn, posts)`, shared by both:
- The anchor lies on the AXIS of the attack, `MARGIN` (8) past the outermost building projected on
  that axis - never at a corner the colony does not reach in that direction.
- When the raid spawn is known (scheduled event's `getSpawnPos`, or the average of
  `getLastSpawnPoints()` at raid start - both now remembered in `RAID_SPAWN`), the anchor is capped
  at 75 % of the way there, so the line always stands BETWEEN raiders and colony, also for colonies
  that grew past their own raid distance.
- Dry-ground check on the middle five posts; if fewer than half are dry the line retreats toward
  the town hall in 8-block steps down to 12 blocks out. Logs
  `[Defense] Line anchored N blocks from the town hall at x,z (k/5 posts dry, pulled back from M -
  water)`, or gives up honestly: `... no dry ground, towers stay on their normal tasks`.
- Unmanned towers are filtered out BEFORE the anchor is chosen, so `posts` is the real count.
- defend_here's border/raid mode reports "Every spot for a line at ... is WATER" instead of
  silently placing nothing; the 20-minute line cap now also applies to a line formed at raid start.

## Technical notes (for continuing development)
- Key trick: `me.sshcrack.mc_talking.ConversationManager.markBusy/markNotBusy` — mc_talking's mixins keep a busy colonist in IDLE so the MineColonies AI doesn't take over their legs. We refresh busy every tick.
- Walking: `com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils.walkToPos(entity, pos, range, safe)` — call repeatedly; following: `getNavigation().moveTo(player, 1.2)` every 20 ticks.
- Group commands: ErrandManager.enqueuePosErrand → a PENDING queue, one start per 4 ticks (TPS). PENDING is drained BEFORE iterating ERRANDS (ConcurrentModification!).
- Guard follow: `AbstractBuildingGuards.GUARD_TASK` (ISettingKey<GuardTaskSetting>), `setting.getValue()/set(...)`, constants GuardTaskSetting.PATROL/GUARD/FOLLOW (translation-key strings!), `setPlayerToFollow(player)`, `building.markDirty()`. Restore keeps the previous task; if it was FOLLOW, it restores PATROL.
- Messenger: `building.getAllAssignedCitizen()` → Set<ICitizenData>, the entity via `cd.getEntity()` (Optional); a non-busy worker is preferred. WARNING: UrgentContactHandler.triggerWalkToPlayer does NOT work for healthy colonists (the urgency-weight abort) — use ConversationManager.startPlayerConversation(ServerPlayer, AbstractEntityCitizen) (public, a regular conversation, no urgency coupling; check isPlayerInConversation + isCitizenBusy before calling).
- Report: `data.getCitizenHappinessHandler().getHappiness(colony, data)`, `getModifiers()` → names, `getModifier(name).getFactor(data)` (<1 unhappy, >1 happy).
- Tool registration: reflection into `AITools.playerConversationOnlyTools` (private static Map), in FMLCommonSetupEvent.enqueueWork; mods.toml has ordering AFTER mc_talking.
- A colonist only leaves AFTER the conversation ends (mc_talking stops the active partner's navigation every tick) — tool descriptions instruct the AI: goodbye + end_conversation.
- MIXIN infra (since v1.1.0): `colonist_errands.mixins.json` (package me.lovkar.errands.mixin, required=false, defaultRequire=0, JAVA_21, no refmap — mod classes aren't obfuscated, remap=false) + the `[[mixins]]` entry in neoforge.mods.toml. Compiled against sponge-mixin.jar from libs. @Unique on helper fields/methods.
- Building without Gradle: javac against the jars (mc-client srg=mojmap, neoforge universal+client, loader, bus, mc_talking, gemini_live_lib, minecolonies, gson, slf4j, annotations, sponge-mixin) + **a stub for com.mojang.brigadier.Message** (stubs/ dir; needed only to compile Component.literal, runtime has the real brigadier). maven.neoforged.net is NOT reachable from the sandbox (502) — the jars are staged from Lovkar's PC.
- Tool parameters: gemini_live_lib.gson.properties — ObjectProperty(Map), EnumProperty(List, required), PrimitiveProperty(Type.INTEGER/STRING/NUMBER/BOOLEAN, required).
- Inspecting a colonist's memory: `/talking_colonists memory @e[type=minecolonies:citizen,limit=1,sort=nearest]` (permission 2 / cheats). Compaction above 15 facts (memoryCompactionThreshold, memoryMode LIVE/FLASH). During a conversation the AI saves via add_event as it goes.
- Versions at v1.1.0: mc_talking 1.7.1, MineColonies 1.1.1368, NeoForge 21.1.248. When mc_talking updates, check: `markBusy`/`playerConversationOnlyTools`/`PlayerFunctionAction`/`UrgentContactHandler.triggerWalkToPlayer` + whether the memory fence bug is fixed upstream (if so, the mixin can go).
- mc_talking memory bug (idea #12, fixed on our side): gemini-flash-lite sometimes wraps the JSON in a markdown fence → the GsonMemoryResponse parse fails → the conversation's memories are discarded. Upstream main (2026-08-08) is NOT fixed and no issue exists — a candidate for a first PR to sshcrack (the real fix: response_mime_type="application/json" in GeminiFlash).
- mc_talking license: CoFH "Don't Be a Jerk" — a fork/addon is OK, the author invites PRs. Goal: PR upstream once stable.
- The old v1.0.0 jar is archived in colonist_errands\old\ on Lovkar's PC.
- Offline verification against Lovkar's world (used for beta.42): colony data lives in `saves/<world>/minecolonies/minecraft/overworld/colony<id>.dat` (gzip NBT: `center`, `buildingManager.buildings[].location/type/level`, `raidhistory[].spawnInfo`), terrain in `region/r.<x>>>9.<z>>>9.mca`. A 60-line Anvil reader (`/root/mca.py`: heightmap `MOTION_BLOCKING_NO_LEAVES`, palette-indexed sections) reproduces `safePost` exactly - the beta.41 sea anchor came out as water, the beta.42 anchor as 13/13 dry posts. The device bridge stages files at most 7 folders deep, so copy them to `colonist_errands\tmp\` first.
