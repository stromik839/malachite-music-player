#!/usr/bin/env python3
"""
Android's regex engine (ICU) is stricter than Java's: a bare "}" or a "{" that is not a
quantifier is a syntax error on the phone, while the same pattern works in JVM unit tests.
This check fails the build when such a pattern appears in Kotlin sources.
Usage: check_android_regex.py <dir> [<dir>...]
"""
import re,sys,glob
# Finds literal { or } in Kotlin regex strings that are not quantifiers {n}, {n,}, {n,m} and not escaped.
bad=[]
for path in [p for d in sys.argv[1:] for p in glob.glob(d+'/**/*.kt', recursive=True)]:
    src=open(path,encoding='utf-8').read()
    for m in re.finditer(r'Regex\(\s*"((?:[^"\\]|\\.)*)"', src):
        raw=m.group(1)
        # Kotlin string -> regex text (handle \\ escapes only)
        s=raw.replace('\\\\','\\')
        i=0
        while i < len(s):
            c=s[i]
            if c=='\\':
                # \p{L}, \P{Lu}: Unicode property – valid in ICU
                if i+2 < len(s) and s[i+1] in 'pP' and s[i+2]=='{':
                    i=s.index('}',i)+1; continue
                i+=2; continue
            if c=='$' and i+1<len(s) and (s[i+1]=='{' or s[i+1].isalpha()):
                # kotlin template ${...} or $NAME
                if s[i+1]=='{':
                    j=s.index('}',i); i=j+1; continue
                i+=1; continue
            if c=='{':
                q=re.match(r'\{\d+(,\d*)?\}', s[i:])
                if q: i+=len(q.group(0)); continue
                bad.append((path, src[:m.start()].count('\n')+1, raw)); break
            if c=='}':
                bad.append((path, src[:m.start()].count('\n')+1, raw)); break
            i+=1
for b in bad: print(f"{b[0]}:{b[1]}: {b[2]}")
print(len(bad),'regex pattern(s) that would crash on Android')
sys.exit(1 if bad else 0)
