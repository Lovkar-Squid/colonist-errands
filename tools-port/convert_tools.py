#!/usr/bin/env python3
"""Mechanical port of the 42 Errands tools from mc_talking 1.7 PlayerFunctionAction to the
Talking Colonists 2.0 tool contract (ErrandCommand / ErrandQuery)."""
import re, sys, pathlib

ROOT = pathlib.Path('/root/errands3/src/me/lovkar/errands')
TOOLS = ROOT / 'tools'

QUERIES = {'citizen_report', 'colony_report', 'why_unhappy', 'research_status', 'check_stock',
           'trade_status', 'build_status', 'courier_board'}

GROUPS = {
    # chat
    'citizen_report': 'CHAT', 'colony_report': 'CHAT', 'why_unhappy': 'CHAT', 'research_status': 'CHAT',
    'find_citizen': 'CHAT', 'check_stock': 'CHAT', 'make_promise': 'CHAT', 'resolve_promise': 'CHAT',
    'guard_leaderboard': 'CHAT', 'trade_status': 'CHAT', 'remember_fallen': 'CHAT', 'build_status': 'CHAT',
    'call_me': 'CHAT',
    # errands
    'send_to_building': 'ERRANDS', 'follow_player': 'ERRANDS', 'stop_errand': 'ERRANDS', 'come_here': 'ERRANDS',
    'wait_here': 'ERRANDS', 'gather_at': 'ERRANDS', 'send_messenger': 'ERRANDS', 'fetch_item': 'ERRANDS',
    'deliver_item': 'ERRANDS', 'farmer_plant': 'ERRANDS', 'notify_when': 'ERRANDS', 'back_to_work': 'ERRANDS',
    'call_citizen': 'ERRANDS', 'request_craft': 'ERRANDS', 'courier_board': 'ERRANDS', 'dismiss': 'ERRANDS',
    # military
    'guard_me': 'MILITARY', 'summon_guards': 'MILITARY', 'defend_here': 'MILITARY', 'everyone_home': 'MILITARY',
    'red_alert': 'MILITARY', 'patrol_here': 'MILITARY', 'guard_gear': 'MILITARY', 'arm_guards': 'MILITARY',
    # jobs
    'take_job': 'JOBS', 'mint_coins': 'JOBS', 'prioritize': 'JOBS',
}
ALL_NAMES = set(GROUPS) | {'leave_conversation', 'note_player_conduct'}

PRIM = {'STRING': 'string', 'INTEGER': 'integer', 'NUMBER': 'number', 'BOOLEAN': 'bool'}


def convert_property(expr: str) -> str:
    """'(Property) new ObjectProperty(new HashMap<String, Property>() {{ put(...); }})' -> params(...)"""
    entries = re.findall(r'put\(\s*"([^"]+)"\s*,\s*new\s+(EnumProperty|PrimitiveProperty)\((.*?)\)\s*\)\s*;', expr, re.S)
    if not entries:
        raise SystemExit('no put() entries in property: ' + expr[:200])
    parts = []
    for key, kind, args in entries:
        args = args.strip()
        if kind == 'EnumProperty':
            m = re.match(r'(.*),\s*(true|false)\s*$', args, re.S)
            parts.append(f'"{key}", enumOf({m.group(1).strip()}, {m.group(2)})')
        else:
            m = re.match(r'PrimitiveProperty\.Type\.(\w+)\s*,\s*(true|false)\s*$', args, re.S)
            parts.append(f'"{key}", {PRIM[m.group(1)]}({m.group(2)})')
    return 'params(' + ', '.join(parts) + ')'


def convert(path: pathlib.Path) -> None:
    src = path.read_text(encoding='utf-8')
    orig = src
    m = re.search(r'super\(\s*"([a-z_]+)"', src)
    name = m.group(1)
    is_query = name in QUERIES
    base = 'ErrandQuery' if is_query else 'ErrandCommand'
    group = GROUPS.get(name)
    group_expr = f'RankGuard.GROUP_{group}' if group else 'null'

    # imports
    src = re.sub(r'import me\.sshcrack\.mc_talking\.manager\.tools\.PlayerFunctionAction;\n', f'import me.lovkar.errands.tc.{base};\n', src)
    src = re.sub(r'import me\.sshcrack\.gemini_live_lib\.gson\.properties\.\w+;\n', '', src)
    src = re.sub(r'import me\.sshcrack\.mc_talking\.ConversationManager;\n', '', src)
    src = re.sub(r'import static me\.sshcrack\.mc_talking\.ConversationManager\.\w+;\n', '', src)
    src = re.sub(r'import me\.sshcrack\.mc_talking\.duck\.CitizenDataMemoryExtended;\n', 'import me.lovkar.errands.tc.Talk;\n', src)
    src = re.sub(r'import org\.jetbrains\.annotations\.NotNull;\n', '', src)
    src = re.sub(r'import org\.jetbrains\.annotations\.Nullable;\n', '', src)
    if 'import net.minecraft.server.level.ServerPlayer;' not in src:
        src = src.replace(f'import me.lovkar.errands.tc.{base};\n', f'import me.lovkar.errands.tc.{base};\nimport net.minecraft.server.level.ServerPlayer;\n')
    if group and 'import me.lovkar.errands.RankGuard;' not in src:
        src = src.replace(f'import me.lovkar.errands.tc.{base};\n', f'import me.lovkar.errands.RankGuard;\nimport me.lovkar.errands.tc.{base};\n')
    src = src.replace('import java.util.HashMap;\n', '') if 'HashMap' not in src.replace('import java.util.HashMap;\n', '') else src

    # class declaration
    src = src.replace('extends PlayerFunctionAction', f'extends {base}')

    # constructor: the super(...) call up to the closing ');' of the constructor body
    cm = re.search(r'(super\(\s*"' + re.escape(name) + r'"\s*,)(.*?)(\);\n    \}\n)', src, re.S)
    if not cm:
        raise SystemExit(f'{path.name}: super call not found')
    body = cm.group(2)
    pm = re.search(r',\s*\(Property\)\s*new ObjectProperty\(', body)
    if pm:
        desc = body[:pm.start()]
        prop = body[pm.start():]
        new_body = desc + ',\n                ' + convert_property(prop) + ', ' + group_expr
    else:
        new_body = body.rstrip() + ', ' + group_expr
    src = src[:cm.start()] + cm.group(1) + new_body + cm.group(3) + src[cm.end():]

    # execute -> run
    src = re.sub(r'\n\s*@Override\s*\n\s*@NotNull\s*\n\s*public JsonObject execute\(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters\)',
                 '\n    @Override\n    protected JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player)', src)
    if 'protected JsonObject run(' not in src:
        raise SystemExit(f'{path.name}: execute() signature not matched')

    # the conversing player is a parameter now
    src = src.replace('ServerPlayer player = (playerId == null || server == null) ? null : server.getPlayerList().getPlayer(playerId);', '')
    src = re.sub(r'(me\.sshcrack\.mc_talking\.)?ConversationManager\.getPlayerForEntity\(citizen\.getUUID\(\)\)', 'player.getUUID()', src)
    src = src.replace('me.lovkar.errands.PlayerIdentityBlock.conversingPlayerName(citizen)', 'player.getGameProfile().getName()')
    src = src.replace('PlayerIdentityBlock.conversingPlayerName(citizen)', 'player.getGameProfile().getName()')

    # memory duck -> Talk.remember
    src = re.sub(r'\(\((?:me\.sshcrack\.mc_talking\.duck\.)?CitizenDataMemoryExtended\)\s*([\w.]+)\)\s*\.mc_talking\$getOrInitializeMemory\(\)\s*\.addEvent\(',
                 r'Talk.remember(\1, ', src)
    if 'Talk.remember' in src and 'import me.lovkar.errands.tc.Talk;' not in src:
        src = src.replace(f'import me.lovkar.errands.tc.{base};\n', f'import me.lovkar.errands.tc.{base};\nimport me.lovkar.errands.tc.Talk;\n')

    # descriptions: other Errands tools referenced by name -> {name} placeholders (resolved to the model's names)
    dm = re.search(r'(super\(\s*"' + re.escape(name) + r'"\s*,)(.*?)(,\n\s*params\(|, (?:RankGuard\.GROUP_\w+|null)\)\s*;)', src, re.S)
    desc_region = dm.group(2)
    fixed = desc_region
    for other in sorted(ALL_NAMES, key=len, reverse=True):
        if other == name or '_' not in other:
            continue
        fixed = re.sub(r'(?<![{\w])' + re.escape(other) + r'(?![}\w])', '{' + other + '}', fixed)
    src = src[:dm.start(2)] + fixed + src[dm.end(2):]

    if src != orig:
        path.write_text(src, encoding='utf-8')
        print(f'{path.name}: {name} -> {base} ({group or "ungated"})')


for p in sorted(TOOLS.glob('*.java')):
    if p.name == 'LeaveConversationAction.java':
        continue  # done by hand
    convert(p)
