"""Does every {tool} placeholder resolve, and is every one of them somewhere it CAN resolve?

Talking Colonists 2.0 shows the model a derived name - `tc_7_errands_take_job`, not `take_job` -
so nothing in this mod may ever print a bare tool name. Two mechanisms exist for that, and they are
not interchangeable:

  in a TOOL DESCRIPTION    write `{take_job}`. ToolNames.resolve runs over the description every
                           time the core reads description(), so the placeholder becomes the real
                           provider name at the moment the model is shown it.
  everywhere else          call ToolNames.providerName("take_job") yourself. A prompt block, a
                           chat line, a tool's own info string - none of these ever go through
                           resolve, so a `{take_job}` written there reaches Gemini as five literal
                           characters and a word it has never heard of.

That is a silent failure: it compiles, it loads, the block renders, and the model is politely told
to use a tool that does not exist. It cost one WRONG in the errandstest run for the WORK TRUTH
block and would have cost the same again. So:

  no stray placeholders    no `{tool}` in any source file outside tools/, where descriptions live
  no invented tools        every `{x}` inside a description names one of the 42 registered tools
  no bare names            a description that mentions a tool must do it through a placeholder

  python3 tools/check_placeholders.py [repo]
"""
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
TOOLS = "me/lovkar/errands/tools"

# a word in braces that looks like a tool name; {0} and {} (slf4j/format) are not ours
PLACEHOLDER = re.compile(r"\{([a-z][a-z0-9_]{2,40})\}")


def tool_names(src):
    """The 42 names, read out of the constructors that register them."""
    names = set()
    for f in (src / TOOLS).glob("*.java"):
        m = re.search(r'super\(\s*"([a-z_]+)"', f.read_text(encoding="utf-8"))
        if m:
            names.add(m.group(1))
    return names


def main(argv):
    repo = pathlib.Path(argv[1]) if len(argv) > 1 else REPO
    src = repo / "src"
    names = tool_names(src)
    if len(names) < 20:
        raise SystemExit(f"check_placeholders: only found {len(names)} tool names - wrong repo?")

    bad = []
    stray = 0
    unknown = 0
    used = set()
    for f in sorted(src.rglob("*.java")):
        rel = f.relative_to(repo).as_posix()
        in_tools = TOOLS in rel
        for line_no, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            for word in PLACEHOLDER.findall(line):
                if word not in names:
                    continue                    # {...} that is not a tool name is somebody else's
                used.add(word)
                if not in_tools:
                    stray += 1
                    bad.append(f"  STRAY    {rel}:{line_no} writes {{{word}}} outside a tool "
                               f"description - it is never resolved. Use "
                               f"ToolNames.providerName(\"{word}\")")
    # ...and a placeholder that names nothing: {take_jobb} renders literally and silently
    for f in sorted((src / TOOLS).glob("*.java")):
        text = f.read_text(encoding="utf-8")
        for word in PLACEHOLDER.findall(text):
            if word in names or "_" not in word:
                continue
            unknown += 1
            bad.append(f"  UNKNOWN  {f.name} writes {{{word}}}, which is not a registered tool")

    print("placeholders:")
    print(f"  {len(names)} tool(s) registered, {len(used)} of them referenced by a placeholder")
    print(f"  {stray} stray placeholder(s) outside tool descriptions, {unknown} naming no tool")
    if bad:
        print()
        for line in bad:
            print(line)
        return 1
    print("check_placeholders: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
