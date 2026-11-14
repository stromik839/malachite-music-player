# Как менять Malachite / How to change Malachite

Спасибо, что хотите улучшить плеер. Правила короткие.

## Если вы выпускаете свою версию

1. Не убирайте создателя. В `app/src/main/kotlin/app/malachite/player/Credits.kt` поля `CREATOR`,
   `CREATOR_DISPLAY` и `LOGO_PATH` остаются как есть — это условие лицензии (`NOTICE.md`).
2. Впишите себя в `MODIFIED_BY` в том же файле:

   ```kotlin
   val MODIFIED_BY: List<Modder> = listOf(
       Modder("Ваше имя или ник", "что вы изменили", "https://ссылка-на-вас"),
   )
   ```

   Раздел «О приложении» покажет вас под заголовком «Изменили код / модифицировали».
3. Дайте версии другое имя или ясную пометку, что она изменена, и поменяйте `applicationId`
   в `app/build.gradle.kts`, чтобы она не выдавала себя за оригинал и ставилась рядом с ним.
4. Поменяйте `SOURCE_URL` и `ISSUES_URL` на свой репозиторий — сообщения об ошибках вашей
   версии должны приходить вам.
5. Код вашей версии должен оставаться открытым на тех же условиях (GNU GPL 3 или новее).

## Если вы присылаете исправление в этот репозиторий

- `./gradlew :core:test` должен проходить. Всё, что работает с файлами, тегами и текстами,
  лежит в `core/` и покрыто тестами на настоящих аудиофайлах — добавьте тест на свой случай.
- Регулярные выражения проверяются на совместимость с Android: `python3 tools/check_android_regex.py core/src/main app/src/main`.
- Любое изменение музыкального файла идёт только через `SafeFiles` (копия → проверка звука → замена).
- Никаких закрытых библиотек, рекламы, аналитики и трекеров — приложение рассчитано на F-Droid.
- Сеть — только по действию пользователя (поиск текста, обложки, альбома).

## In English, briefly

Keep the creator's attribution (`Credits.kt`: `CREATOR`, `CREATOR_DISPLAY`, `LOGO_PATH`), add
yourself to `MODIFIED_BY`, mark your version as modified (another name and `applicationId`),
point `SOURCE_URL` / `ISSUES_URL` to your repository, keep the source open under GPL-3.0-or-later.
For patches: `./gradlew :core:test` must pass; every change to a music file goes through
`SafeFiles`; no proprietary libraries, ads, analytics or trackers.
