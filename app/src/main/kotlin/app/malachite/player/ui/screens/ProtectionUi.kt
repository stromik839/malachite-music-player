package app.malachite.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.malachite.core.i18n.tr
import app.malachite.player.MalachiteApp
import app.malachite.player.data.Protection
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.settings.Hint
import app.malachite.player.ui.settings.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_PASSWORD = 6

/**
 * The system's fingerprint prompt (Android 10+). [cipher] is unlocked by it; [onOk] gets it back
 * ready to use, [onFail] gets the reason (null when the user chose the password instead).
 */
fun askFingerprint(
    context: android.content.Context, cipher: javax.crypto.Cipher, title: String, subtitle: String,
    onOk: (javax.crypto.Cipher) -> Unit, onFail: (String?) -> Unit,
) {
    if (android.os.Build.VERSION.SDK_INT < 29) { onFail(null); return }
    val executor = context.mainExecutor
    val prompt = android.hardware.biometrics.BiometricPrompt.Builder(context)
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButton(tr("Использовать пароль"), executor) { _, _ -> onFail(null) }
        .build()
    prompt.authenticate(
        android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher), android.os.CancellationSignal(), executor,
        object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onOk) ?: onFail(tr("Отпечаток не подтверждён"))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // closed by the user or by the "password" button: no message needed
                val quiet = errorCode == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                    errorCode == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                onFail(if (quiet) null else errString.toString())
            }
        },
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, onDone: () -> Unit = {}) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

/** Shown instead of the player while its protected data waits for the password. */
@Composable
fun UnlockScreen() {
    val g = MalachiteApp.graph
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val fingerprint = remember { Protection.biometricAvailable(context) && Protection.biometricOn(context) }
    fun useFingerprint() {
        val cipher = Protection.biometricCipherToUnlock(context)
        if (cipher == null) {
            problem = tr("Отпечатки на телефоне изменились — войдите по паролю и включите вход по отпечатку снова.")
            return
        }
        askFingerprint(context, cipher, tr("Вход в Malachite"), tr("Приложите палец, чтобы открыть данные плеера"), onOk = { ok ->
            busy = true
            scope.launch {
                val opened = withContext(Dispatchers.Default) { g.unlockWithBiometric(ok) }
                busy = false
                if (!opened) problem = tr("Не получилось открыть по отпечатку — введите пароль.")
            }
        }, onFail = { msg -> if (msg != null) problem = msg })
    }
    // like banking apps: the fingerprint prompt opens by itself, the password stays one tap away
    androidx.compose.runtime.LaunchedEffect(Unit) { if (fingerprint) useFingerprint() }
    fun submit() {
        if (busy || password.isEmpty()) return
        busy = true; wrong = false
        scope.launch {
            // the password is checked slowly on purpose (that is what makes guessing it slow)
            val ok = withContext(Dispatchers.Default) { g.unlock(password.toCharArray()) }
            busy = false
            if (!ok) { wrong = true; password = "" }
        }
    }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(tr("Данные плеера защищены паролем"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                tr("Введите пароль, чтобы открыть историю прослушиваний, плейлисты, избранное и статистику. Музыка от пароля не зависит."),
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PasswordField(password, { password = it; wrong = false }, tr("Пароль"), ::submit)
            if (wrong) Text(tr("Пароль не подходит"), color = MaterialTheme.colorScheme.error)
            Button(onClick = ::submit, enabled = !busy && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(tr("Открыть"))
            }
            if (fingerprint) {
                OutlinedButton(onClick = ::useFingerprint, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Fingerprint, null, Modifier.size(22.dp))
                    Text(tr("Войти по отпечатку пальца"), Modifier.padding(start = 8.dp))
                }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { forgot = true }, enabled = !busy) { Text(tr("Забыли пароль?")) }
        }
    }
    if (forgot) AlertDialog(
        onDismissRequest = { forgot = false },
        title = { Text(tr("Начать без защищённых данных?")) },
        text = {
            Text(tr("Без пароля историю прослушиваний, плейлисты, избранное и статистику прочитать нельзя — в этом и смысл защиты. Музыка не пострадает: песни, их названия, тексты и обложки останутся. Защищённые данные будут отложены в папку .malachite/locked-…, а плеер начнёт их заново. Если пароль вспомнится, эту папку сможет открыть только специалист."))
        },
        confirmButton = {
            TextButton(onClick = {
                forgot = false
                scope.launch { withContext(Dispatchers.IO) { g.startOverWithoutProtectedData() } }
            }) { Text(tr("Начать заново")) }
        },
        dismissButton = { TextButton(onClick = { forgot = false }) { Text(tr("Отмена")) } },
    )
}

/** The first group of "Processes": switching the password protection of the player's data on and off. */
@Composable
fun ProtectionGroupContent() {
    val g = MalachiteApp.graph
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val state by Protection.state.collectAsState()
    val work by Protection.work.collectAsState()
    val settings by g.settings.state.collectAsState()
    var enabling by remember { mutableStateOf(false) }
    var disabling by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    val root = g.library.root
    fun resave() {
        g.library.store?.resavePlaylists(g.library.tracks.value.associateBy { it.path })
        g.library.reloadPlaylists()
    }

    Text(
        tr("Под защиту попадают данные самого плеера: история прослушиваний и избранное, плейлисты, статистика, любимые строки, незаконченные синхронизации. Сами песни, их названия (это имена файлов и папок), тексты и обложки остаются как есть."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    work?.let { w ->
        Text(w.title, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(progress = { if (w.total > 0) w.done.toFloat() / w.total else 0f }, modifier = Modifier.fillMaxWidth())
        Text("${w.done} / ${w.total}  ${w.item}", style = MaterialTheme.typography.bodySmall)
    }
    when (state) {
        Protection.State.OFF -> {
            Text(tr("Защита выключена: эти данные хранятся в главной папке открытым текстом."), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { enabling = true }, enabled = work == null && root != null) { Text(tr("Включить защиту паролем")) }
        }
        Protection.State.ON -> {
            Text(tr("Защита включена: AES-256, ключ из пароля (PBKDF2, 600 000 повторов)."), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            SwitchRow(
                tr("Спрашивать пароль при каждом запуске"), settings.askPasswordEveryStart,
                tr("Выключено — ключ хранится в защищённом хранилище этого телефона, и плеер открывается сразу. После переустановки или на другом телефоне пароль спросят один раз"),
            ) { v ->
                g.settings.update { it.copy(askPasswordEveryStart = v) }
                Protection.rememberOnThisPhone(context, !v)
            }
            // a fingerprint instead of typing the password every time
            if (settings.askPasswordEveryStart && Protection.biometricAvailable(context)) {
                var bio by remember { mutableStateOf(Protection.biometricOn(context)) }
                SwitchRow(
                    tr("Входить по отпечатку пальца"), bio,
                    tr("При запуске откроется окно отпечатка; пароль можно ввести всегда. После переустановки или если добавить на телефон новый отпечаток, нужен будет пароль"),
                ) { v ->
                    if (!v) {
                        Protection.disableBiometric(context)
                        bio = false
                    } else {
                        val cipher = Protection.biometricCipherToEnable()
                        if (cipher == null) nav.toast(tr("Отпечаток на этом телефоне недоступен"))
                        else askFingerprint(context, cipher, tr("Вход по отпечатку"), tr("Подтвердите отпечатком, чтобы включить"), onOk = { ok ->
                            bio = Protection.finishBiometricEnable(context, ok)
                            nav.toast(if (bio) tr("Вход по отпечатку включён") else tr("Не получилось включить вход по отпечатку"))
                        }, onFail = { msg -> msg?.let { nav.toast(it) } })
                    }
                }
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { changing = true }, enabled = work == null) { Text(tr("Сменить пароль")) }
                OutlinedButton(onClick = { disabling = true }, enabled = work == null) { Text(tr("Выключить")) }
            }
        }
        Protection.State.LOCKED -> Text(tr("Данные заблокированы — введите пароль."))
    }

    if (enabling && root != null) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var understood by remember { mutableStateOf(false) }
        val ok = p1.length >= MIN_PASSWORD && p1 == p2 && understood
        AlertDialog(
            onDismissRequest = { enabling = false },
            title = { Text(tr("Защита паролем")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordField(p1, { p1 = it }, tr("Пароль (не короче 6 знаков)"))
                    PasswordField(p2, { p2 = it }, tr("Пароль ещё раз"))
                    if (p2.isNotEmpty() && p1 != p2) Text(tr("Пароли не совпадают"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(understood, { understood = it })
                        Text(tr("Я понимаю: если забыть пароль, эти данные не восстановить. Музыка не пострадает."), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    enabling = false
                    val pw = p1.toCharArray()
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Protection.enable(context, root, pw, settings.askPasswordEveryStart, ::resave) } }
                        nav.toast(if (r.isSuccess) tr("Защита включена") else tr("Не получилось: ${r.exceptionOrNull()?.message}"))
                    }
                }) { Text(tr("Включить")) }
            },
            dismissButton = { TextButton(onClick = { enabling = false }) { Text(tr("Отмена")) } },
        )
    }

    if (disabling && root != null) AlertDialog(
        onDismissRequest = { disabling = false },
        title = { Text(tr("Выключить защиту?")) },
        text = { Text(tr("Данные плеера снова будут храниться открытым текстом, плейлисты снова получат файлы .m3u8 для других плееров.")) },
        confirmButton = {
            TextButton(onClick = {
                disabling = false
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Protection.disable(context, root, ::resave) } }
                    nav.toast(if (r.isSuccess) tr("Защита выключена") else tr("Не получилось: ${r.exceptionOrNull()?.message}"))
                }
            }) { Text(tr("Выключить")) }
        },
        dismissButton = { TextButton(onClick = { disabling = false }) { Text(tr("Отмена")) } },
    )

    if (changing && root != null) {
        var old by remember { mutableStateOf("") }
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) changing = false },
            title = { Text(tr("Сменить пароль")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordField(old, { old = it; wrong = false }, tr("Старый пароль"))
                    if (wrong) Text(tr("Пароль не подходит"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    PasswordField(p1, { p1 = it }, tr("Новый пароль (не короче 6 знаков)"))
                    PasswordField(p2, { p2 = it }, tr("Новый пароль ещё раз"))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && old.isNotEmpty() && p1.length >= MIN_PASSWORD && p1 == p2, onClick = {
                    busy = true
                    scope.launch {
                        val done = withContext(Dispatchers.IO) {
                            runCatching { Protection.changePassword(context, root, old.toCharArray(), p1.toCharArray(), settings.askPasswordEveryStart) }.getOrDefault(false)
                        }
                        busy = false
                        if (done) { changing = false; nav.toast(tr("Пароль изменён")) } else wrong = true
                    }
                }) { Text(tr("Сменить")) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { changing = false }) { Text(tr("Отмена")) } },
        )
    }
}
