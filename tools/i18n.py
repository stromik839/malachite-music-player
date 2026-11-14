#!/usr/bin/env python3
"""Translations of the interface.

The source language of the interface is Russian: the texts are written in the code as they
are shown. Every text that the user can see goes through `tr("…")`, which looks the Russian
text up in `core/src/main/resources/i18n/en.txt` when the interface language is English
(the gettext way: the source text is the key).

    python3 tools/i18n.py check    every text inside tr(…) has a translation (used by CI)
    python3 tools/i18n.py keys     prints the texts found in the code, one per line
    python3 tools/i18n.py missing  prints the texts that have no translation yet
    python3 tools/i18n.py wrap     wraps Russian string literals that are not wrapped yet
                                   (a line with the comment `// i18n:off` is left alone)

In a key, `{}` stands for a value put into the text (`$name`, `${expression}`); the
translation uses `{}` for the values in the same order, or `{1}`, `{2}`… to reorder them.
Lines of en.txt: the Russian text, the English text, an empty line; `\\n` is a line break.
"""
import io
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DICT = ROOT / "core/src/main/resources/i18n/en.txt"
SOURCES = [ROOT / "app/src/main/kotlin", ROOT / "core/src/main/kotlin"]
CYRILLIC = re.compile(r"[А-Яа-яЁёІіЇїЄєҐґ]")

# Core files whose Russian strings are data or rules (transliteration tables, words that are
# cut out of titles, names of folders on disk), not interface texts: never wrapped.
NOT_INTERFACE = {
    "Translit.kt", "LyricsCleaner.kt", "FolderHints.kt", "ImportFilter.kt", "Watermarks.kt",
    "LibraryLayout.kt", "QueryVariants.kt", "TextFold.kt", "AlbumFinder.kt", "LyricsFinder.kt",
    "Tr.kt", "AppLog.kt", "Language.kt",
}
# A literal on a line with one of these is not interface text (log lines, rules, constants).
SKIP_LINE = re.compile(r"AppLog\.|CoreLog\.|Regex\(|\bconst val\b|\.toRegex\(")
# A line with this comment is left alone whatever it holds:  val letters = "АБВ" // i18n:off
OFF = "i18n:off"


# ----------------------------------------------------------------------------- Kotlin lexer

def block_comment_end(s: str, i: int) -> int:
    depth, j = 1, i + 2
    while j < len(s) and depth:
        if s.startswith("/*", j):
            depth += 1
            j += 2
        elif s.startswith("*/", j):
            depth -= 1
            j += 2
        else:
            j += 1
    return j


def raw_string_end(s: str, i: int) -> int:
    j = s.find('"""', i + 3)
    if j < 0:
        return len(s)
    j += 3
    while j < len(s) and s[j] == '"':
        j += 1
    return j


def char_end(s: str, i: int) -> int:
    j = i + 1
    if j < len(s) and s[j] == "\\":
        j += 2
    else:
        j += 1
    k = s.find("'", j)
    return (k + 1) if 0 <= k < j + 8 else i + 1


def code_block_end(s: str, i: int) -> int:
    """Index just after the `}` that closes a `${` whose body starts at i."""
    depth, j = 1, i
    while j < len(s):
        c = s[j]
        if s.startswith("//", j):
            k = s.find("\n", j)
            j = len(s) if k < 0 else k
        elif s.startswith("/*", j):
            j = block_comment_end(s, j)
        elif s.startswith('"""', j):
            j = raw_string_end(s, j)
        elif c == '"':
            j = string_end(s, j)
        elif c == "'":
            j = char_end(s, j)
        elif c == "{":
            depth += 1
            j += 1
        elif c == "}":
            depth -= 1
            j += 1
            if depth == 0:
                return j
        else:
            j += 1
    return j


def string_end(s: str, i: int) -> int:
    """Index just after the closing quote of the ordinary string literal that starts at i."""
    j = i + 1
    while j < len(s):
        c = s[j]
        if c == "\\":
            j += 2
        elif c == '"':
            return j + 1
        elif c == "$" and j + 1 < len(s) and s[j + 1] == "{":
            j = code_block_end(s, j + 2)
        else:
            j += 1
    return j


def skip_blank(s: str, i: int) -> int:
    """Skips spaces, line breaks and comments."""
    while i < len(s):
        if s[i] in " \t\r\n":
            i += 1
        elif s.startswith("//", i):
            k = s.find("\n", i)
            i = len(s) if k < 0 else k
        elif s.startswith("/*", i):
            i = block_comment_end(s, i)
        else:
            break
    return i


ESCAPES = {"n": "\n", "t": "\t", '"': '"', "\\": "\\", "$": "$", "'": "'", "r": "\r", "b": "\b"}


def literal_parts(s: str, i: int, end: int):
    """The parts of the literal s[i:end]: ('text', str) and ('code', (start, end)) for `${…}`/`$name`."""
    parts, buf, j = [], [], i + 1
    while j < end - 1:
        c = s[j]
        if c == "\\":
            n = s[j + 1]
            if n == "u":
                buf.append(chr(int(s[j + 2:j + 6], 16)))
                j += 6
            else:
                buf.append(ESCAPES.get(n, n))
                j += 2
        elif c == "$" and s[j + 1] == "{":
            k = code_block_end(s, j + 2)
            parts.append(("text", "".join(buf)))
            buf = []
            parts.append(("code", (j + 2, k - 1)))
            j = k
        elif c == "$" and (s[j + 1].isalpha() or s[j + 1] == "_"):
            k = j + 1
            while k < end - 1 and (s[k].isalnum() or s[k] == "_"):
                k += 1
            parts.append(("text", "".join(buf)))
            buf = []
            parts.append(("name", (j + 1, k)))
            j = k
        else:
            buf.append(c)
            j += 1
    parts.append(("text", "".join(buf)))
    return parts


def chain_at(s: str, i: int):
    """The string literals joined by `+` starting at the quote at i: [(start, end), …]."""
    lits, j = [], i
    while True:
        e = string_end(s, j)
        lits.append((j, e))
        k = skip_blank(s, e)
        if k + 1 < len(s) and s[k] == "+" and s[k + 1] not in "+=":
            k2 = skip_blank(s, k + 1)
            if k2 < len(s) and s[k2] == '"' and not s.startswith('"""', k2):
                j = k2
                continue
        return lits


def chain_key(s: str, lits) -> str:
    out = []
    for a, b in lits:
        for kind, value in literal_parts(s, a, b):
            out.append(value if kind == "text" else "{}")
    key = "".join(out)
    return re.sub(r"(\{\})+", "{}", key)


def own_text(s: str, lits) -> str:
    return "".join(v for a, b in lits for kind, v in literal_parts(s, a, b) if kind == "text")


# ----------------------------------------------------------------------------- wrapping

def wrap_code(s: str, skip_all: bool, counter: list) -> str:
    out, i, n = [], 0, len(s)
    while i < n:
        c = s[i]
        if s.startswith("//", i):
            k = s.find("\n", i)
            k = n if k < 0 else k
            out.append(s[i:k])
            i = k
        elif s.startswith("/*", i):
            k = block_comment_end(s, i)
            out.append(s[i:k])
            i = k
        elif s.startswith('"""', i):
            k = raw_string_end(s, i)
            out.append(s[i:k])
            i = k
        elif c == "'":
            k = char_end(s, i)
            out.append(s[i:k])
            i = k
        elif c == '"':
            lits = chain_at(s, i)
            end = lits[-1][1]
            line_start = s.rfind("\n", 0, i) + 1
            before = s[line_start:i]
            done = "".join(out)
            wrapped_already = done.rstrip().endswith("tr(")
            line_end = s.find("\n", i)
            whole_line = s[line_start:(n if line_end < 0 else line_end)]
            skip = skip_all or wrapped_already or bool(SKIP_LINE.search(before)) or OFF in whole_line

            def rebuilt(a, b, skip_inner):
                # the code inside ${…} may hold literals of its own
                res, pos = [], a
                for kind, value in literal_parts(s, a, b):
                    if kind == "code":
                        ca, cb = value
                        res.append(s[pos:ca])
                        res.append(wrap_code(s[ca:cb], skip_inner, counter))
                        pos = cb
                res.append(s[pos:b])
                return "".join(res)

            if not skip and CYRILLIC.search(own_text(s, lits)):
                counter[0] += 1
                if len(lits) > 1 and end < n and s[end] == ".":
                    # "a" + "b".format(x): a call belongs to the last literal only – wrap one by one
                    pos = i
                    for a, b in lits:
                        out.append(s[pos:a])
                        out.append("tr(" + rebuilt(a, b, False) + ")" if CYRILLIC.search(own_text(s, [(a, b)])) else rebuilt(a, b, False))
                        pos = b
                else:
                    out.append("tr(")
                    pos = i
                    for a, b in lits:
                        out.append(s[pos:a])
                        out.append(rebuilt(a, b, False))
                        pos = b
                    out.append(")")
            else:
                pos = i
                for a, b in lits:
                    out.append(s[pos:a])
                    out.append(rebuilt(a, b, skip or skip_all))
                    pos = b
            i = end
        else:
            out.append(c)
            i += 1
    return "".join(out)


def sources():
    for root in SOURCES:
        for p in sorted(root.rglob("*.kt")):
            yield p


def wrap() -> None:
    total = 0
    for p in sources():
        if p.name in NOT_INTERFACE:
            continue
        text = io.open(p, encoding="utf-8").read()
        counter = [0]
        new = wrap_code(text, False, counter)
        if counter[0] and new != text:
            if "import app.malachite.core.i18n.tr\n" not in new:
                m = re.search(r"^import .*$", new, re.M)
                if m:
                    new = new[:m.start()] + "import app.malachite.core.i18n.tr\n" + new[m.start():]
                else:
                    m = re.search(r"^package .*$", new, re.M)
                    new = new[:m.end()] + "\n\nimport app.malachite.core.i18n.tr" + new[m.end():]
            io.open(p, "w", encoding="utf-8").write(new)
            total += counter[0]
            print(f"{counter[0]:4d}  {p.relative_to(ROOT)}")
    print("wrapped:", total)


# ----------------------------------------------------------------------------- keys

TR_CALL = re.compile(r"(?<![A-Za-z0-9_.])tr\(")


def keys_of(text: str, where: str, problems: list):
    found = []

    def walk(s: str):
        i, n = 0, len(s)
        while i < n:
            c = s[i]
            if s.startswith("//", i):
                k = s.find("\n", i)
                i = n if k < 0 else k
            elif s.startswith("/*", i):
                i = block_comment_end(s, i)
            elif s.startswith('"""', i):
                i = raw_string_end(s, i)
            elif c == "'":
                i = char_end(s, i)
            elif c == '"':
                e = string_end(s, i)
                for kind, value in literal_parts(s, i, e):
                    if kind == "code":
                        walk(s[value[0]:value[1]])
                i = e
            elif c == "t" and TR_CALL.match(s, i):
                j = skip_blank(s, i + 3)
                if j < n and s[j] == '"' and not s.startswith('"""', j):
                    lits = chain_at(s, j)
                    end = skip_blank(s, lits[-1][1])
                    if end < n and s[end] == ")":
                        found.append(chain_key(s, lits))
                    else:
                        line = s.count("\n", 0, i) + 1
                        problems.append(f"{where}:{line}: tr(…) must hold only string literals joined by +")
                    for a, b in lits:
                        for kind, value in literal_parts(s, a, b):
                            if kind == "code":
                                walk(s[value[0]:value[1]])
                    i = lits[-1][1]
                else:
                    i += 3  # tr(variable): translated when the variable's text is in the dictionary
            else:
                i += 1

    walk(text)
    return found


def all_keys():
    problems, keys, seen = [], [], set()
    for p in sources():
        text = io.open(p, encoding="utf-8").read()
        for k in keys_of(text, str(p.relative_to(ROOT)), problems):
            if k not in seen:
                seen.add(k)
                keys.append((k, p.name))
    return keys, problems


def escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace("\n", "\\n")


def unescape(s: str) -> str:
    out, i = [], 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s):
            out.append("\n" if s[i + 1] == "n" else s[i + 1])
            i += 2
        else:
            out.append(s[i])
            i += 1
    return "".join(out)


def read_dict():
    pairs, problems = {}, []
    if not DICT.is_file():
        return pairs, [f"{DICT.relative_to(ROOT)} does not exist"]
    lines = [ln for ln in io.open(DICT, encoding="utf-8").read().split("\n")]
    block = []
    for n, ln in enumerate(lines + [""], 1):
        if ln.startswith("#"):
            continue
        if ln.strip() == "":
            if len(block) == 2:
                pairs[unescape(block[0])] = unescape(block[1])
            elif block:
                problems.append(f"en.txt, line {n}: an entry is two lines (Russian, English), found {len(block)}")
            block = []
        else:
            block.append(ln)
    return pairs, problems


def check(show_missing: bool) -> int:
    keys, problems = all_keys()
    pairs, dict_problems = read_dict()
    problems += dict_problems
    missing = [k for k, _ in keys if k not in pairs]
    for k, v in pairs.items():
        holes = k.count("{}")
        used = len(re.findall(r"\{\d*\}", v))
        numbered = [int(x) for x in re.findall(r"\{(\d+)\}", v)]
        if (numbered and max(numbered) > holes) or (not numbered and used != holes):
            problems.append(f"en.txt: «{escape(k)[:60]}» has {holes} value(s), its translation uses {used}")
    if show_missing:
        for k in missing:
            print(escape(k))
        return 0
    for p in problems:
        print("PROBLEM:", p)
    for k in missing[:40]:
        print("NO TRANSLATION:", escape(k)[:150])
    unused = len([k for k in pairs if k not in {x for x, _ in keys}])
    print(f"{len(keys)} texts in the code, {len(missing)} without a translation, {unused} unused translation(s)")
    return 1 if (missing or problems) else 0


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "check"
    if mode == "wrap":
        wrap()
        return 0
    if mode == "keys":
        keys, _ = all_keys()
        for k, where in keys:
            print(escape(k))
        return 0
    if mode == "missing":
        return check(True)
    return check(False)


if __name__ == "__main__":
    sys.exit(main())
