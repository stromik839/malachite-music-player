package app.malachite.player.ui.settings

import app.malachite.core.i18n.tr
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.malachite.core.soundid.AcoustId
import app.malachite.player.MalachiteApp
import app.malachite.player.data.SoundIdKey
import app.malachite.player.ui.LocalNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the user's own AcoustID key is entered, with how to get one. */
@Composable
fun SoundIdSettingsScreen() {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(SoundIdKey.value) }
    var shown by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val saved = key.trim() == SoundIdKey.value
    SettingsPage {
        Group(tr("Узнавание песен по звуку")) {
            Hint(
                tr("Для песен с непонятными названиями («115620301», «tmpeicn5cwv», «Track 3») или без исполнителя. Плеер слушает первые две минуты песни, " +
                    "составляет её звуковой отпечаток и спрашивает открытую базу AcoustID (её ведут сами слушатели вместе с MusicBrainz), что это за песня. " +
                    "Найденные название, исполнитель и альбом записываются в файл через «Исцеление»."),
            )
            Hint(tr("Отправляется только отпечаток (набор чисел) и длина песни — не сам звук и не имя файла."))
        }
        Group(tr("Ключ AcoustID")) {
            OutlinedTextField(
                key, { key = it.trim(); result = null },
                label = { Text(tr("Ключ приложения (API key)")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { shown = !shown }) {
                        Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (shown) tr("Скрыть ключ") else tr("Показать ключ"))
                    }
                },
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = !saved, onClick = {
                    SoundIdKey.value = key
                    g.healing.clearSoundKeyProblem()
                    nav.toast(if (key.isBlank()) tr("Ключ убран") else tr("Ключ сохранён"))
                }) { Text(tr("Сохранить")) }
                OutlinedButton(enabled = key.isNotBlank() && !checking, onClick = {
                    checking = true
                    result = null
                    scope.launch {
                        val a = withContext(Dispatchers.IO) { runCatching { SoundIdKey.check(AcoustId(g.http), key) }.getOrNull() }
                        checking = false
                        result = when (a) {
                            is AcoustId.Answer.Found -> true to tr("Ключ работает ✓")
                            is AcoustId.Answer.KeyProblem -> false to keyTroubleText(a.trouble, a.detail)
                            is AcoustId.Answer.Failed -> false to tr("Сервис не ответил (${a.detail}). Проверьте интернет и попробуйте ещё раз.")
                            null -> false to tr("Проверка не удалась")
                        }
                        if (a is AcoustId.Answer.Found && !saved) {
                            SoundIdKey.value = key
                            g.healing.clearSoundKeyProblem()
                        }
                    }
                }) { Text(tr("Проверить ключ")) }
                if (SoundIdKey.present) TextButton(onClick = { key = ""; SoundIdKey.value = ""; result = null; nav.toast(tr("Ключ убран")) }) { Text(tr("Убрать ключ")) }
            }
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            result?.let { (ok, text) ->
                Text(text, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
            }
            if (!SoundIdKey.present) Hint(tr("Ключа нет — исцеление пропускает узнавание по звуку, всё остальное работает как обычно."))
        }
        Group(tr("Как получить ключ (бесплатно, 2 минуты)")) {
            val steps = listOf(
                tr("1. Откройте сайт acoustid.org (кнопка ниже) и нажмите «Sign in» — войти можно через аккаунт MusicBrainz или Google."),
                tr("2. Вверху откройте «Applications» и нажмите «Register a new application» (новое приложение)."),
                tr("3. Name — любое название, например «Malachite на моём телефоне». Version — 1.0. Website можно не заполнять. Нажмите «Register»."),
                tr("4. В списке приложений появится ваше — скопируйте его «API key» (строка из букв и цифр)."),
                tr("5. Вставьте ключ в поле выше и нажмите «Проверить ключ»."),
            )
            steps.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Hint(tr("Нужен именно ключ приложения (API key). «User API key» из профиля — для отправки своих отпечатков в базу, плееру он не нужен."))
            Row {
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://acoustid.org/new-application")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { nav.toast(tr("Не нашлось браузера")) }
                }) { Text(tr("Открыть acoustid.org")) }
            }
        }
    }
}

/** Words for a key problem: shown in the settings and in the window after healing. */
fun keyTroubleText(trouble: AcoustId.KeyTrouble, detail: String): String = when (trouble) {
    AcoustId.KeyTrouble.MISSING -> tr("Ключа AcoustID нет в настройках.")
    AcoustId.KeyTrouble.INVALID -> tr("Сервис не принял ключ: такого ключа нет или он больше не действителен") + (if (detail.isNotBlank()) " ($detail)." else ".")
    AcoustId.KeyTrouble.NOT_ALLOWED -> tr("Сервис отключил этот ключ") + (if (detail.isNotBlank()) " ($detail)." else ".") + tr(" Сделайте новый ключ на acoustid.org.")
}
