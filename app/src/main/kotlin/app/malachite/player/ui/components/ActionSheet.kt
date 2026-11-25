package app.malachite.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The pictures of the actions, in one place. */
object ActionIcons {
    val play: ImageVector get() = Icons.Filled.PlayArrow
    val shuffle: ImageVector get() = Icons.Filled.Shuffle
    val next: ImageVector get() = Icons.Filled.SkipNext
    val queue: ImageVector get() = Icons.Filled.QueueMusic
    val playlist: ImageVector get() = Icons.Filled.PlaylistAdd
    val favorite: ImageVector get() = Icons.Filled.Favorite
    val unfavorite: ImageVector get() = Icons.Filled.FavoriteBorder
    val lyrics: ImageVector get() = Icons.Filled.Lyrics
    val sync: ImageVector get() = Icons.Filled.Sync
    val cover: ImageVector get() = Icons.Filled.Image
    val tags: ImageVector get() = Icons.Filled.Edit
    val artist: ImageVector get() = Icons.Filled.Person
    val album: ImageVector get() = Icons.Filled.Album
    val info: ImageVector get() = Icons.Filled.Info
    val delete: ImageVector get() = Icons.Filled.Delete
    val choose: ImageVector get() = Icons.Filled.CheckBox
    val heal: ImageVector get() = Icons.Filled.AutoFixHigh
    val pin: ImageVector get() = Icons.Filled.PushPin
    val remove: ImageVector get() = Icons.Filled.RemoveCircleOutline
    val up: ImageVector get() = Icons.Filled.ArrowUpward
    val down: ImageVector get() = Icons.Filled.ArrowDownward
    val dislike: ImageVector get() = Icons.Filled.ThumbDown
    val dislikeOff: ImageVector get() = Icons.Filled.ThumbDownOffAlt
    val best: ImageVector get() = Icons.Filled.Star
    val bestOff: ImageVector get() = Icons.Filled.StarBorder
    val equalizer: ImageVector get() = Icons.Filled.Equalizer
    val sleep: ImageVector get() = Icons.Filled.Bedtime
    val editLines: ImageVector get() = Icons.Filled.EditNote
    val download: ImageVector get() = Icons.Filled.Download
    val rename: ImageVector get() = Icons.Filled.DriveFileRenameOutline
}

/**
 * What can be done with a song, an album or the chosen things: a sheet from below with what it is
 * for on top and the actions as tiles – a few rows instead of a list as tall as the screen. It is
 * not tied to the place it was opened from, so it stays put whatever happens on the screen.
 */
@Composable
fun ActionSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: () -> List<MenuAction>,
) {
    if (!visible) return
    // made once when the sheet opens (the list reads the library)
    val list = remember { actions() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                val columns = when {
                    maxWidth >= 720.dp -> 7
                    maxWidth >= 520.dp -> 5
                    else -> 4
                }
                val tile = maxWidth / columns
                FlowRow(Modifier.fillMaxWidth()) {
                    list.forEach { a -> ActionTile(a, Modifier.width(tile)) { onDismiss(); a.action() } }
                }
            }
        }
    }
}

@Composable
private fun ActionTile(a: MenuAction, modifier: Modifier, onClick: () -> Unit) {
    val danger = a.danger
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                a.icon ?: Icons.Filled.MoreHoriz, null, Modifier.size(24.dp),
                tint = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        // a long word gets a smaller size instead of being cut in two («Синхронизир / овать»)
        FitText(
            a.title, MaterialTheme.typography.labelMedium, maxLines = 2, minSize = 9.sp, textAlign = TextAlign.Center,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
