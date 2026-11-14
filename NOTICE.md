# Malachite — licence, authorship and additional terms

**Malachite** — an offline music player for Android.

Copyright © 2026 **Krysmed** — the idea, the design decisions and the direction of the whole
project. The source code was written by the AI assistant Claude (Anthropic) at Krysmed's request.

This program is free software: you can redistribute it and/or modify it under the terms of the
**GNU General Public License** as published by the Free Software Foundation, either **version 3**
of the License, or (at your option) any later version. The full text is in the file
[`LICENSE`](LICENSE).

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

## Additional terms (section 7 of the GNU GPL version 3)

The following terms supplement the licence, as its section 7 permits.

1. **The creator's attribution stays** — section 7(b). The "About" screen of the application
   (and any equivalent notice shown by a work based on this program) must keep naming **Krysmed**
   as the main creator of the player, together with the creator's logo, as in the original
   version. This attribution must not be removed, hidden or replaced.

2. **Modified versions say so** — section 7(c). You may not misrepresent the origin of this
   program: do not present it, its source code or substantial parts of it as your own original
   work. A modified version must be marked in a reasonable way as modified and as different from
   the original — for example by another name, a clear note, or by adding the people who changed
   it to the `MODIFIED_BY` list in `app/src/main/kotlin/app/malachite/player/Credits.kt`, which the
   "About" screen shows under "Изменили код / модифицировали".

3. **Name and logo** — section 7(e). The name "Krysmed" and the creator's logo identify the
   creator. This licence gives no permission to use them for anything other than the
   attribution required above.

## Коротко по-русски

- Код открыт: его можно изучать, менять и распространять.
- Изменённая версия должна оставаться открытой на тех же условиях (GNU GPL 3 или новее).
- **Создатель плеера — Krysmed.** Его имя и логотип в разделе «О приложении» убирать или
  заменять нельзя.
- Нельзя выдавать плеер или его код за собственную разработку. Изменили код — пометьте версию
  как изменённую. Проще всего — вписать себя в список `MODIFIED_BY` в файле `Credits.kt`:
  приложение покажет вас в разделе «Изменили код / модифицировали».
- Приложение даётся «как есть», без гарантий. Им пользуются по собственному желанию и на свой
  риск; создатель не несёт ответственности за его использование и последствия.

## Third-party components

| Component | Licence |
|---|---|
| AndroidX, Jetpack Compose, Media3, WorkManager, Palette | Apache License 2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache License 2.0 |
| OkHttp, Coil | Apache License 2.0 |
| jaudiotagger | GNU LGPL 2.1 or later |
| Shizuku API | Apache License 2.0 |

Lyrics, covers and album data come from online services at the user's request; they belong to
their right holders. The player ships no music.
