package app.malachite.player.ui.components

import app.malachite.core.i18n.tr
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.malachite.core.library.TrackInfo
import app.malachite.player.MalachiteApp
import app.malachite.player.data.CoverLoader
import app.malachite.player.data.coverKey
import app.malachite.player.data.Frost
import app.malachite.player.ui.theme.LastLook
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A song's cover on its way: [bitmap] (for a moment perhaps a smaller copy that was already in
 * memory, until the size asked for is read), and [settled] once the file has been read – only then
 * is a missing [bitmap] really "no cover".
 */
@Immutable
class CoverState(val bitmap: Bitmap?, val settled: Boolean)

/** Loads the cover embedded in the song file (cached, off the main thread). */
@Composable
fun rememberCoverState(track: TrackInfo?, sizePx: Int = 300): CoverState {
    val revision by MalachiteApp.graph.library.revision.collectAsState()
    // a song played from the internet has no file: its cover comes from the catalogue
    val onlineCover = MalachiteApp.graph.library.onlineItem(track?.path)?.coverUrl
    val file = if (onlineCover != null) null else track?.let { MalachiteApp.graph.library.file(it) }
    // A new song starts from what is ready for it (usually loaded ahead, see CoverPrefetch), never
    // from the previous song's cover: that one stayed on the screen, with its colours, while the
    // new title was already shown. Not ready in this size: the same cover in another size that is
    // in memory stands in, and "no cover" is only said when it is known.
    val state = remember(track?.path, track?.lastModified, revision) {
        // the file's time from the library's index: no call to the storage while a list scrolls
        val modified = track?.lastModified ?: 0L
        fun exactOf(f: File) = if (modified > 0) CoverLoader.cached(f, modified, sizePx) else CoverLoader.cached(f, sizePx)
        fun missingOf(f: File) = if (modified > 0) CoverLoader.isKnownMissing(f, modified) else CoverLoader.isKnownMissing(f)
        fun anyOf(f: File) = if (modified > 0) CoverLoader.cachedAny(f, modified, sizePx) else CoverLoader.cachedAny(f, sizePx)
        val exact = onlineCover?.let { CoverLoader.cachedUrl(it, sizePx) } ?: file?.let(::exactOf)
        val none = when {
            track == null -> true
            onlineCover != null -> CoverLoader.isKnownMissingUrl(onlineCover, sizePx)
            else -> file == null || missingOf(file)
        }
        mutableStateOf(
            when {
                exact != null -> CoverState(exact, true)
                none -> CoverState(null, true)
                else -> CoverState(onlineCover?.let { CoverLoader.cachedUrlAny(it, sizePx) } ?: file?.let(::anyOf), false)
            },
        )
    }
    LaunchedEffect(track?.path, track?.lastModified, revision) {
        if (state.value.settled) return@LaunchedEffect
        val loaded = when {
            onlineCover != null -> withContext(Dispatchers.IO) { CoverLoader.loadUrl(onlineCover, sizePx) }
            file == null -> null
            else -> withContext(Dispatchers.IO) { CoverLoader.load(file, sizePx) }
        }
        state.value = CoverState(loaded ?: state.value.bitmap, true)
    }
    return state.value
}

/** The cover of the song file (cached, off the main thread); null while it is read and when there is none. */
@Composable
fun rememberCover(track: TrackInfo?, sizePx: Int = 300): Bitmap? = rememberCoverState(track, sizePx).bitmap

/**
 * The cover of [track] blurred beforehand into a small picture (see [Frost]): the frosted glass
 * behind the window, the panels and the song screen. While the next song's one is made the
 * previous one stays; a song without a cover has none. [waiting]: the player has not said yet
 * which song it has (the app has just started): the glass shown last stands in (see LastLook).
 */
@Composable
fun rememberFrost(track: TrackInfo?, waiting: Boolean = false): Bitmap? {
    val key = track?.let { coverKey(it.path, it.lastModified) }
    val cover = rememberCoverState(track, 200)
    val ready = key?.let { Frost.cached(it) }
    val made = remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(key, cover.bitmap, cover.settled) {
        val pic = cover.bitmap
        made.value = when {
            key == null -> null
            ready != null -> ready
            pic != null -> withContext(Dispatchers.Default) { Frost.of(key, pic) }
            cover.settled -> null
            else -> made.value
        }
        // kept for the next start of the app
        if (key != null && (ready != null || pic != null || cover.settled)) LastLook.keepFrost(key, made.value)
    }
    return when {
        key == null -> if (waiting) LastLook.frost() else null
        ready != null -> ready
        cover.settled && cover.bitmap == null -> null
        else -> made.value ?: LastLook.frost()
    }
}

/**
 * The frosted cover [frost] (see [rememberFrost]) filling the place, at [opacity]; the next song's
 * one comes in softly ([animate]). Drawn as a ready picture, never blurred while drawing: light
 * even on weak phones.
 */
@Composable
fun FrostLayer(frost: Bitmap?, opacity: Float, animate: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    androidx.compose.animation.Crossfade(
        frost,
        animationSpec = if (animate) androidx.compose.animation.core.tween(450) else androidx.compose.animation.core.snap(),
        label = "frost",
    ) { f ->
        if (f != null) Image(f.asImageBitmap(), null, modifier.graphicsLayer { alpha = opacity }, contentScale = ContentScale.Crop)
    }
}

@Composable
fun CoverImage(
    track: TrackInfo?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    corner: Dp = 8.dp,
    sizePx: Int = 200,
) {
    val bmp = rememberCover(track, sizePx)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size * 0.45f))
        }
    }
}

/** One thing that can be done: its words, its picture, whether it destroys something ([danger], shown in red). */
data class MenuAction(val title: String, val icon: ImageVector? = null, val danger: Boolean = false, val action: () -> Unit)

@Composable
fun TrackRow(
    track: TrackInfo,
    playing: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    actions: List<MenuAction>,
    showCover: Boolean = true,
    number: Int? = null,
    onLongClick: (() -> Unit)? = null,
    /** While several songs are being chosen: whether this one is; null otherwise. */
    selected: Boolean? = null,
    /** A long press while choosing: makes sure the song is chosen before the menu opens. */
    onLongPressChoosing: () -> Unit = {},
    /** The menu made only when it opens (the actions for all chosen songs); replaces [actions]. */
    menuActions: (() -> List<MenuAction>)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected == true) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (selected != null) null else onLongClick ?: { menu = true },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text(
                if (number > 0) number.toString() else "–",
                Modifier.width(32.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (showCover) {
            CoverImage(track, size = 46.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.displayTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOf(track.displayArtist, track.album).filter { it.isNotBlank() }.joinToString(" • ").ifEmpty { tr("Неизвестно") },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (playing) Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        if (favorite) Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp).size(16.dp))
        Box {
            if (selected != null) androidx.compose.material3.Checkbox(selected, { onClick() })
            else IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tr("Ещё")) }
        }
    }
    ActionSheet(
        menu, { menu = false }, track.displayTitle,
        listOf(track.displayArtist, track.album).filter { it.isNotBlank() }.joinToString(" • "),
        leading = { CoverImage(track, size = 44.dp) },
    ) { menuActions?.invoke() ?: actions }
}


@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.padding(6.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(3.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.padding(8.dp))
            action()
        }
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Runs [block] once when [key] changes – small helper to keep screens readable. */
@Composable
fun OnChange(key: Any?, block: suspend () -> Unit) {
    LaunchedEffect(key) { block() }
}

/**
 * Opens the system picker for one picture and hands over its bytes (null if it could not be
 * read or is absurdly big). Works without any storage permission.
 */
@Composable
fun rememberImagePicker(onPicked: (ByteArray?) -> Unit): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val current by androidx.compose.runtime.rememberUpdatedState(onPicked)
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        if (uri != null) scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        val limit = 40 * 1024 * 1024
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > limit) return@use null
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            current(bytes)
        }
    }
    return { launcher.launch(arrayOf("image/*")) }
}

/** Picture of a playlist: its own one, else the cover of its first song, else an icon. */
@Composable
fun PlaylistCover(name: String, firstTrack: TrackInfo?, size: Dp, corner: Dp = 10.dp, modifier: Modifier = Modifier) {
    val g = app.malachite.player.MalachiteApp.graph
    val version by g.library.playlistCovers.collectAsState()
    val file = remember(name, version) { g.library.playlistCover(name) }
    when {
        file != null -> coil.compose.AsyncImage(
            model = file, contentDescription = null,
            modifier = modifier.size(size).clip(RoundedCornerShape(corner)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        firstTrack != null -> CoverImage(firstTrack, modifier, size = size, corner = corner)
        else -> Box(
            modifier.size(size).clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(androidx.compose.material.icons.Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size * 0.45f))
        }
    }
}

