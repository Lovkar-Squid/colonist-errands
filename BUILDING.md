# Building Colonist Errands

This project is intentionally built **without Gradle**: plain `javac` against the real mod jars,
then `jar`. It compiles in seconds and needs nothing but a JDK 21.

## 1. Collect the dependency jars into `libs/`

Copy these from a working modpack instance (e.g. `<instance>/mods/` and the versions you play with):

- `mc_talking-1.7.x-neoforge+1.21.1.jar` (Talking Colonists)
- `gemini_live_lib-*.jar` (ships alongside Talking Colonists)
- `minecolonies-1.1.13xx-1.21.1.jar`
- `structurize-*.jar`, `blockui-*.jar`, `domum-ornamentum-*.jar`, `multipiston-*.jar` (MineColonies deps)
- `voicechat-neoforge-1.21.1-*.jar` (Simple Voice Chat)
- NeoForge: `neoforge-21.1.x-universal.jar` and `neoforge-21.1.x-client.jar`
- FML: `loader-*.jar`, `bus-*.jar`, `sponge-mixin-*.jar` (from the NeoForge libraries folder)
- Minecraft client jar (mojmap/official names, e.g. the `client-extra`/versions jar of your instance)
- `gson.jar`, `slf4j-api.jar`, JetBrains `annotations.jar`
- optional: `mctradepost-*.jar` (MC Trade Post) — only needed to compile the marketplace/economy code paths

None of these jars are redistributed in this repository - bring your own.

## 2. Compile

```bash
javac -encoding UTF-8 --release 21 -proc:none \
      -cp "stubs:libs/*" \
      -d build \
      $(find src -name '*.java')
```

`stubsrc/` contains three tiny compile-only stubs (`com.mojang.authlib.GameProfile`,
`com.mojang.brigadier.Message`, `com.mojang.serialization.Keyable`) for classes that exist at
runtime but are awkward to put on the compile classpath. Since 3.0 the mod compiles against
**Talking Colonists' API jar only** (`mc_talking-api-<version>-1.21.1-neoforge.jar` in `libs/`,
from its GitHub release), never the full mod jar - the full jar is needed only at runtime, so
keep it out of `libs/` (a `libs-runtime/` folder is the convention here). Compile them into `stubs/` first if you don't have that folder yet:

```bash
javac -encoding UTF-8 --release 21 -cp "libs/*" -d stubs $(find stubsrc -name '*.java')
```

They are **not** packed into the jar.

## 3. Package

```bash
jar cf colonist_errands-<version>.jar -C build . -C resources .
```

That's the whole build. The jar contains the compiled classes, `META-INF/neoforge.mods.toml`,
and `colonist_errands.mixins.json` (2 mixins since 3.0, both on MineColonies classes,
`remap=false`; the ten mixins on Talking Colonists internals went away with the port to its addon
API).

## Headless smoke test

`tools/errandstest/` is a tiny server-side test mod (like Voyager's `colonytest`): in a colony the
Voyager colony test builds it fetches every Errands tool back out of Talking Colonists' registry and
calls it through the public contract with a fake player as colony owner, checks the activity lease
an errand takes, asks the prompt contributor for its blocks from the server thread and from a
background thread, writes and reads a memory, starts a pair chat and a controlled session. Compile
it against `build/` and drop it into the test server's `mods/` next to the Errands jar; it prints
`[errandstest] DONE` when it got through. Without a Gemini key the conversations fail at the
provider, which is expected - the test is about the plumbing.

## Notes for porting to new dependency versions

Since 3.0 everything Talking Colonists-side goes through its addon API (`me.sshcrack.mc_talking.api`,
see `src/me/lovkar/errands/tc/`): tools, prompt contributor, memory, conversations, speech and
urgency rules, pregeneration. Two things still reach past it by reflection and fail soft if they
move: `/errands reloadtalking` (the `McTalkingConfig` class) and `ToolNames.verify` (asks the
internal tool runtime what function name the model sees, to keep tool cross-references right).
The MineColonies module/settings APIs used in `ErrandBuildings`, `GuardSettings` and `TakeJobAction`
still deserve a `javap` look when bumping MineColonies.

The watchdogs read MineColonies internals that are easy to miss when porting:
`AbstractEntityCitizen.getEntityStateController()` (the `CitizenAIState` enum),
`AbstractAISkeleton.getState()` / `AIWorkerState.isOkayToEat()`, `ICitizenData.isIdleAtJob()`,
`BedHandlingModule.onBlockPlacedInBuilding/removeBed`, `BuildingHospital.registerBlockPosition`,
`IColony.getWorkManager().getWorkOrders()`, `IBuilding.createRequest` plus
`IRequestManager.getRequestForToken`, and `ICitizenSkillHandler.addXpToSkill`. `IRequest.getDeliveries()`
returns a Guava `ImmutableList`, so it is read reflectively rather than pulling Guava onto the
compile classpath.
