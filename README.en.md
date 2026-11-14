<div align="center">

# Malachite

**An offline music player for Android that tidies up your music by itself.**

Missing covers, broken tags, synced lyrics, duplicates and files called `115620301.mp3` —
Malachite finds and fixes them without touching the sound itself.

[Download the APK](https://github.com/stromik839/malachite/releases) · [По-русски](README.md) · [All features in detail (RU)](docs/FEATURES.md)

Android 8.0+ · no ads · no accounts · no tracking

</div>

---

## Why another player

Music on a phone piles up from everywhere: Telegram and VK downloads, websites, an old phone.
Half the songs end up without covers, an album is called "AyuGram", the artist is "Various
artists" and the file is `AUD-20240101-WA0003.mp3`.

Malachite gathers all your music into one folder and heals it. Every change is made on a copy
of the file, checked byte for byte, and only then replaces the original. Any healing can be undone.

## What it does

**Healing the library**
- finds and embeds covers (iTunes, Deezer, MusicBrainz and more), fixes one picture shared by
  different albums;
- fills empty albums and artists, fixes typos, garbled encodings and site watermarks;
- recognises songs by their sound (its own Chromaprint + the open AcoustID database) when only
  digits are left of the title;
- removes duplicates, broken files and "not music" — voice notes and videos from messengers;
- lays everything out as `Artist / Album / Song`. You choose what to heal.

**Lyrics**
- synced lyrics with a smooth karaoke-like word fill;
- one-button manual sync: hold it while a line is sung;
- favourite lines, line-by-line editing, lyrics in the home screen widget.

**The player itself**
- crossfade, loudness levelling, speed and pitch independent of each other;
- a visualizer fed by the player's own sound (no microphone permission);
- "best moment" — the chorus marked on the seek bar;
- "don't like" — a song or an artist stays out of shuffle;
- playlist sorting, drag to reorder, choosing several songs at once;
- listening statistics, themes, custom fonts, English and Russian interface.

## Install

1. Open [Releases](https://github.com/stromik839/malachite/releases) and download the APK of the
   fresh build.
2. Install it. New versions install over old ones; your music and settings stay.

For automatic updates, add this repository to [Obtainium](https://github.com/ImranR98/Obtainium).
An F-Droid submission is planned.

## Privacy

The player works offline. It goes online only when you search for lyrics or a cover yourself,
or turn on the online section (off by default). No analytics, ads or accounts. The error log is
off and, if you switch it on, stays in a file on your phone.

## How it was made

Idea, decisions and testing on a real phone — **Krysmed**. All the code was written by the AI
**Claude** (Anthropic), and the app says so itself.

- `core/` — plain Kotlin without Android: names, tags, lyrics, healing, the sound fingerprint;
- `app/` — the app: Jetpack Compose, Media3 (ExoPlayer), WorkManager, the widget;
- `tools/` — checks that run on every build.

Build it yourself: `./gradlew :app:assembleRelease` (JDK 17, Android SDK 35).
Found a bug? [Tell us](https://github.com/stromik839/malachite/issues/new/choose) and attach the log.
Interface texts are written in Russian in the code and translated in
`core/src/main/resources/i18n/en.txt`; `python3 tools/i18n.py check` makes sure nothing is left out.

## Support the author

Donation addresses are listed in the [Russian README](README.md#поддержать-автора) and in the app
(Settings → About → Support the author). Send each coin only through the network shown.

## Licence

[GNU GPL 3.0 or later](LICENSE) with additional terms about attribution ([NOTICE.md](NOTICE.md)).
You may study, change and share the code, keeping the creator's name and the note that the code
was written by AI.
