#!/usr/bin/env bash
# Headless run of the bed-check chunk probe against ONE Colonist Errands jar.
#   tools/bedchurn/run.sh <colonist_errands jar> [label]
# Builds a throw-away server in /root/bedtest (libraries are symlinked from the rig), starts it on a
# fresh flat world with MineColonies' keep-loaded switch OFF (nobody online, the colony is not held),
# lets the probe mod build the colony and make two evenings, and prints what it measured.
ERR=${1:?path to colonist_errands jar}
LABEL=${2:-run}
SB=/root/bedtest
NF=/root/nfserver
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$SB/mods" "$SB/config"
ln -sfn "$NF/libraries" "$SB/libraries"
cd "$SB" || exit 1
if [ -f server.pid ] && kill -0 "$(cat server.pid)" 2>/dev/null; then kill "$(cat server.pid)"; sleep 8; fi
pkill -x tail 2>/dev/null
rm -rf bedworld logs crash-reports server.out cmd.fifo
rm -f mods/*.jar
for j in minecolonies-1.1.1403-1.21.1-snapshot.jar structurize-1.0.835-1.21.1-snapshot.jar \
         blockui-1.0.212-1.21.1-snapshot.jar domum-ornamentum-1.0.236-snapshot-main.jar \
         multipiston-1.2.58-1.21.1.jar mc_talking-2.0.0-beta.1-cf.jar \
         gemini_live_lib-2.4.1-neoforge+1.21.1.jar voicechat-neoforge-1.21.1-2.6.24.jar \
         yet_another_config_lib_v3-3.8.2+1.21.1-neoforge.jar architectury-13.0.11-neoforge.jar \
         Patchouli-1.21.1-93-NEOFORGE.jar; do
  cp "$NF/mods/$j" mods/ || { echo "missing $j"; exit 1; }
done
cp "$ERR" mods/
cp "$HERE/bedchurn-1.0.0.jar" mods/
# configs: the rig's, minus anything of Errands', and the colony NOT force-loaded
rm -rf config && mkdir config
cp "$NF"/config/minecolonies-*.toml "$NF"/config/structurize-server.toml "$NF"/config/neoforge-*.toml config/ 2>/dev/null
cp "$NF"/config/yacl*.json5 config/ 2>/dev/null
rm -f config/*.bak
sed -i 's/^\([[:space:]]*\)forceloadcolony[[:space:]]*=.*/\1forceloadcolony = false/' config/minecolonies-server.toml
echo "eula=true" > eula.txt
cat > server.properties <<P
level-name=bedworld
level-type=minecraft\:flat
online-mode=false
difficulty=peaceful
spawn-protection=0
view-distance=4
simulation-distance=4
server-port=25599
enable-rcon=false
motd=bedtest
P
mkfifo cmd.fifo
( tail -f cmd.fifo | java -Dbedtest=1 -Xmx3G @libraries/net/neoforged/neoforge/21.1.249/unix_args.txt nogui > server.out 2>&1 ) &
sleep 4
pgrep -f '[D]bedtest=1' | head -1 > server.pid
echo "[$LABEL] started pid $(cat server.pid), errands jar: $(basename "$ERR")"
for i in $(seq 1 90); do
  sleep 5
  grep -q '\[bedchurn\] DONE' logs/latest.log 2>/dev/null && break
  kill -0 "$(cat server.pid)" 2>/dev/null || break
  grep -q 'FAILED at tick' logs/latest.log 2>/dev/null && break
done
sleep 5
kill "$(cat server.pid)" 2>/dev/null; pkill -x tail 2>/dev/null
echo "=== [$LABEL] bedchurn summary ==="
grep -E '\[bedchurn\] (Colonist|MineColonies|colony|residence|registered|settle|dusk|RESULT|DONE|FAILED)' logs/latest.log | sed -E 's/^\[[0-9:]+\] \[[^]]*\] \[[^]]*\]: //'
echo "=== [$LABEL] Errands bed lines ==="
grep -E '\[Beds\]' logs/latest.log | sed -E 's/^\[[0-9:]+\] \[[^]]*\] \[[^]]*\]: //' | head -20
echo "=== [$LABEL] chunk events (colony) ==="
grep -E 'bedchurn\] event' logs/latest.log | grep -E '\(colony\)' | sed -E 's/^.*bedchurn\] event //' | head -60
echo "=== [$LABEL] errors from colonist_errands / bedchurn ==="
grep -cE '(colonist_errands|bedchurn).*(WARN|ERROR)|\[(WARN|ERROR)\].*(colonist_errands|bedchurn)' logs/latest.log
