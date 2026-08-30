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

None of these jars are redistributed in this repository - bring your own.

## 2. Compile

```bash
javac -encoding UTF-8 --release 21 -proc:none \
      -cp "stubs:libs/*" \
      -d build \
      $(find src -name '*.java')
```

`stubsrc/` contains two tiny compile-only stubs (`com.mojang.brigadier.Message`,
`dev.isxander.yacl3.api.NameableEnum`) for classes that exist at runtime but are awkward to put on
the compile classpath. Compile them into `stubs/` first if you don't have that folder yet:

```bash
javac -encoding UTF-8 --release 21 -cp "libs/*" -d stubs $(find stubsrc -name '*.java')
```

They are **not** packed into the jar.

## 3. Package

```bash
jar cf colonist_errands-<version>.jar -C build . -C resources .
```

That's the whole build. The jar contains the compiled classes, `META-INF/neoforge.mods.toml`,
and `colonist_errands.mixins.json` (9 mixins, `remap=false` - all targets are mod classes with
stable names).

## Notes for porting to new dependency versions

The addon reaches into Talking Colonists and MineColonies internals (mixins + reflection). When
bumping either dependency, re-verify the touched members exist with `javap -c` before shipping:
`AITools.playerConversationOnlyTools`, `ConversationManager.markBusy/startPlayerConversation/
getPlayerForEntity`, `GeminiStream` buffer fields, `PregenerationPlayback.ACTIVE_PREGENERATED_PLAYBACK`,
`CitizenPromptService.generate*` signatures, and the MineColonies module/settings APIs used in
`ErrandBuildings`, `GuardSettings` and `TakeJobAction`.
