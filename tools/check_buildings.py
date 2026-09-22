"""Can a player point at a MineColonies building by the name written on it?

The name on the hut is not the name in the registry, and MineColonies does not treat them as the
same thing: the **Forester's Hut** is registered `lumberjack`, the **Cowhand's Hut** is `cowboy`,
a **Farm** is `farmer`, a **Residence** is `citizen`, and a **Rabbit Hutch** used to come out of
normalizeType as "rabbitch" because the suffix strip ate the middle of the word. Twenty-six of the
fifty-two huts differ from their own label.

That matters twice over:

  the model     is told to say the hut's real name and then hands that name back to a tool. A
                fuzzy display-name fallback catches most of it, but only most, and only because it
                is a contains() over whatever three names a building happens to carry.
  WorkTruth     reads the registry path straight, with no fallback at all. "forester" written
                there matched nothing in any colony, so every citizen in the game was told his
                colony had no workplace for chopping wood while a Forester's Hut stood in front of
                him. That is the bug report's own failure, shipped inside the fix for it.

So this holds both tables to the jar that is actually installed:

  every BY_THE_WORK value names a hut that exists
  every hut's display name leads back to that same hut through normalizeType

  python3 tools/check_buildings.py [path/to/minecolonies.jar] [repo]
"""
import json
import pathlib
import re
import sys
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
JARS = [
    pathlib.Path('/root/nfserver/mods-ww-aside'),
    pathlib.Path('/root/nfserver/mods'),
    REPO / 'libs',
]


def find_jar(argv):
    if len(argv) > 1 and argv[1].endswith('.jar'):
        return pathlib.Path(argv[1])
    for d in JARS:
        if d.is_dir():
            hits = sorted(d.glob('minecolonies-*.jar'))
            if hits:
                return hits[-1]
    raise SystemExit('check_buildings: no minecolonies jar found - pass one as the first argument')


def huts(jar):
    """{registry path: display name} for every hut block the lang file names."""
    with zipfile.ZipFile(jar) as z:
        lang = json.loads(z.read('assets/minecolonies/lang/en_us.json').decode('utf-8'))
    out = {}
    for key, shown in lang.items():
        m = re.fullmatch(r'block\.minecolonies\.blockhut([a-z0-9_]+)', key)
        if m:
            out[m.group(1)] = shown
    return out


def tables(src):
    """BUILDING_TYPES, JOB_ALIASES and BY_THE_WORK, read out of the source rather than guessed."""
    eb = (src / 'me/lovkar/errands/ErrandBuildings.java').read_text(encoding='utf-8')
    wt = (src / 'me/lovkar/errands/WorkTruth.java').read_text(encoding='utf-8')
    types = set(re.findall(r'"([a-z0-9_ \']+)"', eb.split('BUILDING_TYPES = List.of(')[1].split(');')[0]))
    aliases = dict(re.findall(r'Map\.entry\("([^"]+)",\s*"([^"]+)"\)', eb))
    work = dict(re.findall(r'BY_THE_WORK\.put\("([^"]+)",\s*"([^"]+)"\)', wt))
    return types, aliases, work


def normalize(raw, aliases):
    """normalizeType, in Python. Kept in step by the suffix test below."""
    q = raw.strip().lower()
    q = re.sub(r"(?:'s)?\s+hut$", '', q)
    q = re.sub(r'\s+building$', '', q).strip()
    return aliases.get(q, q)


def main(argv):
    jar = find_jar(argv)
    repo = pathlib.Path(argv[2]) if len(argv) > 2 else REPO
    hut = huts(jar)
    types, aliases, work = tables(repo / 'src')
    if len(hut) < 30 or not work:
        raise SystemExit(f'check_buildings: read {len(hut)} hut(s) and {len(work)} work line(s) - wrong jar or repo?')

    bad = []

    # the strip must take a suffix off the end and leave the middle of a word alone
    if normalize('Rabbit Hutch', {}) != 'rabbit hutch':
        bad.append('  STRIP    "Rabbit Hutch" is being mangled by the " hut" strip')
    if normalize("Builder's Hut", {}) != 'builder':
        bad.append('  STRIP    "Builder\'s Hut" no longer reduces to "builder"')

    for workword, path in work.items():
        if path not in hut:
            bad.append(f'  NO HUT   WorkTruth: {workword!r} points at {path!r}, which is not a '
                       f'MineColonies building. Did you write the name on the hut instead of its '
                       f'registry path?')

    for path, shown in sorted(hut.items()):
        got = normalize(shown, aliases)
        if got != path and not (got in types and aliases.get(got, got) == path):
            bad.append(f'  UNNAMED  {shown!r} (the {path} hut) normalizes to {got!r} and finds '
                       f'nothing. Add Map.entry("{got}", "{path}") to JOB_ALIASES.')

    print('buildings:')
    print(f'  {jar.name}')
    print(f'  {len(hut)} hut(s), {len(work)} kind(s) of work, {len(aliases)} alias(es)')
    print(f'  {sum(1 for p, s in hut.items() if normalize(s, {}) != p)} hut(s) whose label differs '
          f'from their registry path, every one of them mapped')
    if bad:
        print()
        for line in bad:
            print(line)
        return 1
    print('check_buildings: ok')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
