#!/usr/bin/env python3
"""
Walks through Malachite on an emulator the way a person would: the first-run screens, the
permissions, the main folder, importing the test songs, then the songs list, the song screen,
every tab, healing, settings, help and search. Buttons are found by the words on the screen
(the emulator speaks English, so the app does too; the words come from the app's own
dictionary). After every step the words on the screen are printed, so the build log shows
what each Android version displayed.

A missing button is reported (the interface may have changed), a crash of the app is a
failure. Usage (from the repository root, with one emulator attached): device_walk.py <api>
"""
import html
import os
import re
import subprocess
import sys
import time

PKG = "app.malachite.player"
API = sys.argv[1] if len(sys.argv) > 1 else "?"
OUT = f"device-report/api-{API}"
os.makedirs(OUT, exist_ok=True)
missing = []


def adb(*args, timeout=60):
    try:
        return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def load_english():
    words, block = {}, []
    for line in open("core/src/main/resources/i18n/en.txt", encoding="utf-8").read().split("\n") + [""]:
        if line.startswith("#"):
            continue
        if not line.strip():
            if len(block) == 2:
                words[block[0]] = block[1]
            block = []
        else:
            block.append(line)
    return words


EN = load_english()


def pattern(ru):
    """The English text of a Russian one as a regular expression ({} holes match anything)."""
    return "^" + ".*".join(re.escape(part) for part in EN.get(ru, ru).split("{}")) + "$"


size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
W, H = (int(size.group(1)), int(size.group(2))) if size else (1080, 2400)


dismissed = 0


def dump():
    """The screen's elements; a system "… isn't responding" window over it is closed with "Wait"."""
    global dismissed
    nodes = []
    for _ in range(4):
        nodes = raw_dump()
        # another app crashed ("Messages has stopped"): close its window and go on
        crash = next((n for n in nodes if re.search(r"(has stopped|keeps stopping|остановлено|постоянно останавливается)$", n["text"])), None)
        if crash and dismissed < 12:
            dismissed += 1
            if "Malachite" in crash["text"]:
                print(f"::error::Android API {API}: «{crash['text']}» — the app itself crashed")
            else:
                print(f"  (system window «{crash['text']}» over the screen: closed)")
            close = next((n for n in nodes if n["text"] in ("Close app", "Close", "OK", "Закрыть приложение", "Закрыть", "ОК")), None)
            if close:
                tap(close)
            else:
                adb("shell", "input", "keyevent", "4")
            time.sleep(3)
            continue
        anr = next((n for n in nodes if re.search(r"(isn.t responding|не отвечает)$", n["text"])), None)
        wait = next((n for n in nodes if n["text"] in ("Wait", "Подождать")), None)
        close = next((n for n in nodes if n["text"] in ("Close app", "Закрыть приложение")), None)
        if not anr or not (wait or close) or dismissed >= 20:
            return nodes
        dismissed += 1
        if "Malachite" in anr["text"]:
            # the app itself: given the time it asks for, and reported
            print(f"::warning::Android API {API}: «{anr['text']}» — the app itself was not responding")
            tap(wait or close)
        else:
            # another app of the emulator (its launcher, System UI) hangs at boot: waiting only
            # brought the window back again and again, so it is closed (Android starts it anew)
            print(f"  (system window «{anr['text']}» over the screen: closed)")
            tap(close or wait)
        time.sleep(4)
    return nodes


def raw_dump():
    # (the dump waits for the screen to be still: anything moving makes it fail, so playback is
    # paused before the screens are read)
    for _ in range(3):
        adb("shell", "rm", "-f", "/sdcard/ui.xml")
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=20)
        xml = adb("shell", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in xml:
            nodes = []
            for m in re.finditer(r"<node ([^>]*?)/?>", xml):
                a = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
                b = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
                if not b:
                    continue
                x1, y1, x2, y2 = map(int, b.groups())
                if x2 <= x1 or y2 <= y1:
                    continue
                nodes.append({
                    "cls": a.get("class", ""),
                    "text": html.unescape(a.get("text", "")).strip(),
                    "desc": html.unescape(a.get("content-desc", "")).strip(),
                    "enabled": a.get("enabled") == "true",
                    "box": (x1, y1, x2, y2),
                })
            return nodes
        time.sleep(1.5)
    return []


def words(nodes):
    seen = []
    for n in nodes:
        for v in (n["text"], n["desc"]):
            if v and v not in seen:
                seen.append(v)
    return " | ".join(seen)[:1400] or "(nothing readable)"


def alive():
    return adb("shell", "pidof", PKG).strip() != ""


def responsive():
    return adb("shell", "echo", "ok", timeout=20).strip() == "ok"


def give_up_if_dead(where):
    """An emulator that stopped answering cannot be walked any further: the rest only waits."""
    if not responsive():
        print(f"::error::Android API {API}: the emulator stopped answering ({where})")
        sys.exit(3)


shots = 0


def screen(name):
    global shots
    nodes = dump()
    shots += 1
    # numbered: "songs" and "Songs" must not be one file where letter case does not count
    safe = re.sub(r"[^\w-]+", "-", name)
    with open(f"{OUT}/walk-{shots:02d}-{safe}.png", "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    print(f"  [{name}] {words(nodes)}")
    return nodes


def find(nodes, ru=None, regex=None, desc=False, lowest=False, enabled=True):
    pat = re.compile(regex or pattern(ru))
    hits = [n for n in nodes if pat.search(n["desc"] if desc else n["text"]) and (n["enabled"] or not enabled)]
    if not hits:
        return None
    return max(hits, key=lambda n: n["box"][1]) if lowest else hits[0]


def tap(n):
    x1, y1, x2, y2 = n["box"]
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def swipe_up():
    # slow: a fast swipe flings the list on, and a moving screen cannot be read
    adb("shell", "input", "swipe", str(W // 2), str(int(H * 0.7)), str(W // 2), str(int(H * 0.4)), "700")
    time.sleep(1.2)


def scroll_top():
    for _ in range(4):
        adb("shell", "input", "swipe", str(W // 2), str(int(H * 0.3)), str(W // 2), str(int(H * 0.8)), "300")
    time.sleep(1.5)


def back():
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.5)


STATES = {"0": "NONE", "1": "STOPPED", "2": "PAUSED", "3": "PLAYING", "6": "BUFFERING", "7": "ERROR", "8": "CONNECTING"}


def media_state():
    """The player's state and position as Android sees them (what the notification and lock screen show)."""
    out = adb("shell", "dumpsys", "media_session", timeout=20)
    at = out.find("package=" + PKG)
    m = re.search(r"PlaybackState \{state=(\w+)(?:\((\d+)\))?, position=(-?\d+)", out[at:] if at >= 0 else out)
    if not m:
        return None, None
    name = STATES.get(m.group(2) or m.group(1), m.group(1))
    return name, int(m.group(3))


def media_info():
    """The actions Android offers for the player (notification, lock screen) and the song it shows."""
    out = adb("shell", "dumpsys", "media_session", timeout=20)
    at = out.find("package=" + PKG)
    part = out[at:] if at >= 0 else out
    a = re.search(r"actions=(\d+)", part)
    d = re.search(r"description=([^,\n]*)", part)
    return (int(a.group(1)) if a else None), (d.group(1).strip() if d else None)


def wait_state(want, seconds):
    end = time.time() + seconds
    st = pos = None
    while time.time() < end:
        st, pos = media_state()
        if st == want:
            break
        time.sleep(0.2)
    return st, pos


def follow(seconds):
    """States one after another for a while after a tap: "PLAYING 0.0 s → BUFFERING 0.3 s → PLAYING 0.6 s"."""
    t0 = time.time()
    seen = []
    pos = None
    while time.time() - t0 < seconds:
        st, pos = media_state()
        if st and (not seen or seen[-1][0] != st):
            seen.append((st, time.time() - t0))
    return " → ".join(f"{s} {t:.1f} s" for s, t in seen) or "nothing reported", seen, pos


def step(name, ru=None, regex=None, desc=False, lowest=False, scroll=0, wait=12, optional=False):
    """Waits for a button (scrolling down when asked) and taps it."""
    end = time.time() + wait
    swipes = 0
    nodes = []
    while True:
        nodes = dump()
        n = find(nodes, ru, regex, desc, lowest)
        if n:
            print(f"OK   {name}")
            tap(n)
            time.sleep(2)
            if not alive():
                print(f"::error::Android API {API}: the app is gone after «{name}»")
            return True
        if swipes < scroll:
            swipe_up()
            swipes += 1
            continue
        if time.time() > end:
            if not nodes:
                give_up_if_dead(f"looking for «{name}»")
            print(f"{'SKIP' if optional else 'MISS'} {name} — on the screen: {words(nodes)}")
            if not optional:
                missing.append(name)
            return False
        time.sleep(1.5)


adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
time.sleep(6)
screen("start")

# --- first run
step("Next — the user agreement", "Далее — пользовательское соглашение")
step("Accept the agreement", "Принять", scroll=30, wait=60)
step("Got it (one main folder)", "Понятно")
step("Next (permissions)", "Дальше", wait=20)
step("Next (where the main folder is)", "Дальше")
step("Next (full move or not)", "Дальше")
step("Find music on the device", "Найти музыку на устройстве", wait=20)
step("Add the found songs", "Добавить {} в главную папку", wait=120, scroll=3)
step("Done (import)", "Готово", wait=120, scroll=3)
time.sleep(4)
screen("main screen")
step("Skip the tour", "Пропустить", optional=True, wait=10)

# --- songs and the song screen
screen("songs")
step("Play a test song", regex=r"^First Song$", wait=25)
time.sleep(4)
# older Android prints "state=3", newer "state=PLAYING(3)"
playing = re.search(r"state=(3\b|PLAYING)", adb("shell", "dumpsys", "media_session")) is not None
print(f"  playing: {'yes' if playing else 'no (or not reported)'}")
adb("shell", "input", "keyevent", "127")  # pause: a moving progress bar keeps the screen from being read
time.sleep(1)
step("Open the song screen from the mini player", regex=r"^First Song$", lowest=True, wait=10)
time.sleep(2)
nodes = screen("song screen")

# --- seeking and play/pause on the song screen, the way people use them (taps on the bar,
# several quick taps while it plays, pause right after a seek). The song is 45 s long.
bar = next((n for n in nodes if n["cls"].endswith("SeekBar")), None)
play = find(nodes, regex=r"^(Play|Pause)$", desc=True)
if not bar or not play:
    print(f"MISS seek bar or play button — on the screen: {words(nodes)}")
    missing.append("seek bar / play button")
else:
    x1, y1, x2, y2 = bar["box"]

    def tap_bar(frac):
        adb("shell", "input", "tap", str(int(x1 + (x2 - x1) * frac)), str((y1 + y2) // 2))

    def secs(ms):
        return "?" if ms is None else f"{ms / 1000:.1f} s"

    tap_bar(0.75)
    time.sleep(2)
    st, pos = media_state()
    print(f"  seek to 3/4 while paused: {st} at {secs(pos)}")
    if st != "PAUSED" or pos is None or not 25_000 <= pos <= 42_000:
        print(f"::warning::Android API {API}: a seek while paused did not land at about 34 s ({st} at {secs(pos)})")
        missing.append("seek while paused")
    screen("after a seek while paused")

    tap(play)
    st, pos = wait_state("PLAYING", 8)
    print(f"  play button: {st} at {secs(pos)}")
    time.sleep(2)
    busy = adb("shell", "top", "-b", "-n", "1", "-m", "8", timeout=20).strip().splitlines()
    print("  busiest processes while it plays: " + (" / ".join(l.strip()[:110] for l in busy[-9:]) if busy else "no answer"))
    give_up_if_dead("playing on the song screen")
    if st != "PLAYING":
        print(f"::warning::Android API {API}: the play button did not start playback ({st})")
        missing.append("play button")
    time.sleep(1)
    tap_bar(0.25)
    line, seen, pos = follow(3)
    print(f"  seek to 1/4 while playing: {line}; now at {secs(pos)}")
    if pos is None or not 5_000 <= pos <= 20_000:
        # once more: tells a tap lost by the emulator from a seek that does not work
        tap_bar(0.25)
        line2, seen2, pos2 = follow(3)
        works = pos2 is not None and 5_000 <= pos2 <= 20_000
        print(f"::warning::Android API {API}: a seek while playing did not move the player (at {secs(pos)}); "
              f"a second tap: {line2}, at {secs(pos2)} — {'the first tap was lost' if works else 'seeking while playing does not work'}")
        missing.append("seek while playing" + ("" if works else " (twice)"))
        line, seen, pos = line2, seen2, pos2
    back_at = next((t for s2, t in seen[1:] if s2 == "PLAYING"), None)
    if seen and seen[-1][0] != "PLAYING":
        print(f"::warning::Android API {API}: after a seek while playing the player did not play on ({line})")
        missing.append("seek while playing")
    elif back_at is not None:
        print(f"  playing again {back_at * 1000:.0f} ms after the seek")
    # quick taps one after another, like a person looking for a place in the song
    for frac in (0.6, 0.3, 0.8, 0.45, 0.5):
        tap_bar(frac)
        time.sleep(0.25)
    line, seen, pos = follow(3)
    print(f"  five quick seeks while playing: {line}; now at {secs(pos)}")
    if not seen or seen[-1][0] != "PLAYING" or pos is None or not 18_000 <= pos <= 32_000:
        print(f"::warning::Android API {API}: after quick seeks the player is not playing near the middle ({line}, {secs(pos)})")
        missing.append("quick seeks")
    # pause right after a seek: the moment the button used to be wrong
    tap_bar(0.7)
    time.sleep(0.15)
    tap(play)
    st, pos = wait_state("PAUSED", 4)
    print(f"  pause right after a seek: {st} at {secs(pos)}")
    if st != "PAUSED":
        print(f"::warning::Android API {API}: pause right after a seek did not pause ({st})")
        missing.append("pause after a seek")
        adb("shell", "input", "keyevent", "127")
    if not alive():
        print(f"::error::Android API {API}: the app is gone after seeking")
    give_up_if_dead("after seeking")
    time.sleep(1)
    nodes = screen("after seeking and pause")

    # "next" on the last song of the queue: the system must still offer it, and it wraps round
    # to the first song (the button used to vanish there and "next" did nothing)
    nxt = find(nodes, regex=r"^Next$", desc=True)
    if not nxt:
        print("MISS the next button on the song screen")
        missing.append("next button")
    else:
        for _ in range(2):  # First → Second → Third (the songs list is sorted by title)
            tap(nxt)
            time.sleep(1.5)
        acts, now = media_info()
        offered = acts is not None and acts & 32 != 0  # PlaybackState.ACTION_SKIP_TO_NEXT
        print(f"  on the last song «{now}»: next {'offered' if offered else 'NOT offered'} (actions={acts})")
        if not offered:
            print(f"::warning::Android API {API}: on the last song the system does not offer «next»")
            missing.append("next on the last song")
        tap(nxt)
        time.sleep(2)
        acts, now = media_info()
        print(f"  next on the last song → «{now}»")
        if not (now or "").startswith("First Song"):
            print(f"::warning::Android API {API}: «next» on the last song did not go round to the first one («{now}»)")
            missing.append("next wraps round")
        if media_state()[0] == "PLAYING":
            adb("shell", "input", "keyevent", "127")
        time.sleep(1)
# the song's menu: the best moment and "don't like" are there
if step("Song screen menu", "Ещё", desc=True, optional=True):
    time.sleep(1)
    nodes = screen("song screen menu")
    # a menu item may carry a value ("… здесь (0:05)"): its words before the brackets are enough
    for key in ("Отметить лучший момент здесь ({})", "Не нравится"):
        word = key.split(" (")[0]
        en = EN.get(key, word).split(" (")[0]
        if not any(n["text"].startswith(en) or n["text"].startswith(word) for n in nodes):
            print(f"MISS «{en}» in the song's menu")
            missing.append(f"song menu: {en}")
    back()
step("Lyrics on the song screen", "Текст", desc=True, optional=True)
time.sleep(3)
screen("lyrics panel")
adb("shell", "input", "keyevent", "127")  # pause: a still screen is easier to read
back()

# --- every tab
for tab in ["Альбомы", "Исполнители", "Избранное", "Плейлисты", "Исцеление", "Статистика", "Процессы", "Песни"]:
    if step(f"Tab «{EN.get(tab, tab)}»", tab, lowest=True, optional=True):
        time.sleep(2)
        screen(EN.get(tab, tab))
        if tab == "Исцеление":
            # one analysis reads the tags of every song (the tag library must survive R8)
            # what to heal: each step by itself or all at once (nothing is started here)
            if step("Healing: choose what to heal", "Исцелить всё или выбрать, что именно…", optional=True):
                time.sleep(1)
                screen("what to heal")
                if not step("Healing: cancel the choice", "Отмена", optional=True):
                    back()
            if step("Healing: check", "Проверить", optional=True, scroll=2):
                time.sleep(8)
                screen("healing checked")

# --- choosing several songs (the "Songs" tab is open after the loop above)
if step("Choose several songs", "Выбрать несколько", desc=True, optional=True):
    time.sleep(1)
    if step("Choose all songs", "Выбрать все", optional=True):
        time.sleep(1)
        screen("all songs chosen")
    if step("What to do with the chosen songs", "Что сделать с выбранным", desc=True, optional=True):
        time.sleep(1)
        screen("actions for chosen songs")
        if step("Heal the chosen songs", "Исцелить выбранные…", optional=True):
            time.sleep(1)
            screen("what to heal in the chosen songs")
            if not step("Cancel healing the chosen songs", "Отмена", optional=True):
                back()
        else:
            back()
    # hold a song and slide over the others: they are chosen one after another
    if step("Untick all", "Снять все", optional=True):
        time.sleep(1)
        nodes = dump()
        rows = [n for n in nodes if n["text"] in ("First Song", "Second Song", "Third Song")]
        if len(rows) >= 2:
            a, b = rows[0]["box"], rows[-1]["box"]
            out = adb("shell", "input", "draganddrop", str((a[0] + a[2]) // 2), str((a[1] + a[3]) // 2),
                      str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2), "2000")
            time.sleep(2)
            nodes = dump()
            chosen = [n["text"] for n in nodes if n["text"].startswith("Chosen:")]
            print(f"  hold and slide over {len(rows)} songs: {chosen or 'no count on the screen'} {out.strip()[:80] if out else ''}")
            screen("chosen by sliding")
    step("Stop choosing", "Закончить выбор", desc=True, optional=True)

# --- settings, help, search
if step("Settings", "Настройки", desc=True, optional=True):
    screen("settings")
    scroll_top()
    if step("Settings: lyrics", "Тексты песен", optional=True, scroll=3):
        screen("settings lyrics")
        back()
    scroll_top()
    if step("Settings: playback", "Воспроизведение и звук", optional=True, scroll=3):
        screen("settings playback")
        # the DJ mixer: turn LOW all the way down (KILL) and let the music play through it
        if step("Equalizer and DJ mixer", "Эквалайзер и DJ-микшер", optional=True, scroll=6):
            time.sleep(1)
            nodes = screen("equalizer and mixer")
            knob = find(nodes, regex=r"^LOW: ", desc=True)
            if not knob:
                print(f"MISS the LOW knob — on the screen: {words(nodes)}")
                missing.append("mixer knob LOW")
            else:
                x1, y1, x2, y2 = knob["box"]
                cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
                adb("shell", "input", "swipe", str(cx), str(cy), str(cx - 380), str(cy + 380), "700")
                time.sleep(1)
                killed = find(dump(), regex=r"^KILL$") is not None
                print(f"  LOW turned all the way down: {'KILL shown' if killed else 'no KILL on the screen'}")
                if not killed:
                    missing.append("mixer KILL")
                adb("shell", "input", "keyevent", "126")  # play: the sound goes through the mixer
                time.sleep(5)
                screen("mixer playing with the bass killed")
                adb("shell", "input", "keyevent", "127")
                time.sleep(1)
                if not alive():
                    print(f"::error::Android API {API}: the app is gone while the mixer played")
            back()
        back()
    scroll_top()
    if step("Settings: recognition by sound", "Узнавание песен по звуку", optional=True, scroll=4):
        screen("settings sound recognition")
        back()
    # which build is installed, and what it brought
    scroll_top()
    if step("Settings: what's new", "Что нового", optional=True, scroll=8):
        time.sleep(1)
        screen("what's new")
        if not step("What's new: got it", "Понятно", optional=True):
            back()
    back()
if step("Questions and answers", "Вопросы и ответы", desc=True, optional=True):
    screen("help")
    back()
if step("Search", "Поиск", desc=True, optional=True):
    adb("shell", "input", "text", "First")
    time.sleep(3)
    screen("search First")
    back()
    back()

print(f"Walk on Android API {API}: {'every step found' if not missing else 'not found: ' + ', '.join(missing)}")
if missing:
    print(f"::warning::Android API {API}: the walk did not find {len(missing)} step(s): {', '.join(missing)}")
