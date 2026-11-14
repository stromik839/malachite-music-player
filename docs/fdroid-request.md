# Заявка в F-Droid: что нажимать и что вставлять

Этот файл — для автора проекта. Сначала по-русски, что делать; ниже — готовый английский текст,
который нужно вставить в заявку.

## Перед заявкой (один раз)

1. **Проверьте сборку на телефоне** — особенно английский язык («Оформление → Язык
   интерфейса → English»).
2. **Поставьте метку версии.** На GitHub откройте репозиторий → справа **Releases** →
   **Draft a new release** → в поле «Choose a tag» напишите `v0.2.0` и выберите
   «Create new tag» → **Publish release**. F-Droid собирает приложение именно по метке.
3. Если хотите переименовать репозиторий — сделайте это до заявки и скажите Claude новый
   адрес: его нужно поправить в приложении и в тексте ниже.

## Сама заявка (GitLab)

Проект в GitLab создавать **не нужно**. Окно «GitLab Duo» можно просто закрыть крестиком —
это реклама их помощника.

1. Войдите на https://gitlab.com под своим аккаунтом.
2. Откройте ссылку https://gitlab.com/fdroid/rfp/-/issues/new — это форма новой заявки
   («Request For Packaging», просьба упаковать приложение).
3. В поле **Title** вставьте: `Malachite - offline music player that tidies the library`
4. В большом поле **Description** уже будет шаблон с вопросами. Сотрите его и вставьте текст
   из раздела «Текст заявки» ниже (или впишите ответы из него в поля шаблона — как удобнее).
5. Нажмите **Create issue**.

Дальше ждать: заявки разбирают добровольцы, это занимает от пары недель до нескольких месяцев.
Вопросы они пишут в комментариях к заявке; уведомление придёт на почту аккаунта GitLab. Если
вопрос непонятен — перешлите его Claude, он подготовит ответ.

## О чём могут спросить

- **Код написан ИИ.** В заявке это сказано прямо — так же, как в самом приложении. Запрета в
  правилах F-Droid, насколько известно, нет, но решение за проверяющими. Не скрывайте: за
  сокрытие из каталогов исключают. (Каталог IzzyOnDroid такие приложения не принимает совсем.)
- **Несвободные сетевые сервисы.** Поиск текстов и обложек идёт через сторонние сервисы —
  приложение получит пометку NonFreeNet. Это нормально.
- **Онлайн-раздел.** Выключен по умолчанию, источники — открытые каталоги и официальные
  30-секундные отрывки. Если попросят убрать его из сборки для F-Droid — это делается.
- **Скриншоты.** На них обложки чужих альбомов. Если попросят заменить — снимем на песнях
  со свободными обложками.

## Текст заявки

```
### Summary

Malachite is an offline music player for Android that also puts the music library in order:
it fixes tags and names, embeds covers into the files, finds duplicates, and shows synced lyrics.
No ads, no accounts, no trackers.

* [x] The app complies with the inclusion criteria as listed in https://f-droid.org/docs/Inclusion_Policy/
* [x] The app is not already listed in the repo or issue tracker.
* [x] The original app author has been notified (and supports the inclusion). — I am the author.

### Link to the source code

https://github.com/stromik839/malachite-music-player

### License used

GPL-3.0-or-later (LICENSE), with additional terms permitted by section 7 of the GPL
(attribution, marking of modified versions) in NOTICE.md.

### Category

Multimedia

### Description

* Collects all music into one main folder laid out as "Artist / Album / Song"; playlists, lyrics
  and settings live in the same folder, so it can be zipped and moved to another phone.
* "Healing": repairs names with typos and transliteration, garbled tags, wrong file extensions,
  empty albums and artists, meaningless file names, duplicates and missing covers. Every change
  is made on a copy and the audio is verified to stay identical byte for byte.
* Lyrics: synced first, plain second; lyrics can be synced by hand.
* Speed and pitch, equalizer, sleep timer, themes, a resizable widget with lyric lines.
* Interface in English and Russian.

### Build

* Gradle, one command: `gradle :app:assembleRelease` (subdir `app`), tag `v0.2.0`,
  versionName 0.2.0, versionCode 2.
* Dependencies come only from Google Maven and Maven Central; no Google Play Services, Firebase,
  ads or analytics.
* Fastlane metadata (descriptions, icon, screenshots, changelog) is in the repository.
* A draft of the metadata file is in docs/fdroid-metadata.yml.

### Things you should know

* Network: the app works offline. It goes online only on the user's action — to look up lyrics,
  covers and album names through third-party services (LRCLIB, MusicBrainz, iTunes, Deezer,
  Genius and others). I expect the NonFreeNet anti-feature.
* There is an optional online section, off by default: search in the open catalogues Audius and
  Internet Archive, and 30-second previews from the public Deezer API. Nothing is downloaded
  that the catalogues do not offer for download themselves.
* Transparency: the ideas and design are mine; the code was written with an AI assistant
  (Claude). This is stated in the app itself and in the README.
* Donations: a Bitcoin address is in the metadata draft.
```
