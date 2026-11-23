package app.waypoint.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.waypoint.music.LibraryItem
import app.waypoint.music.MediaLibrary
import app.waypoint.music.PlaylistChoice

/** Browse a player's library and pick something to play when a trip starts. */
@Composable
fun PlaylistPicker(library: MediaLibrary, packageName: String, playerName: String, onDismiss: () -> Unit, onPick: (PlaylistChoice) -> Unit) {
    // Path of (mediaId, title); null id = the library root.
    val path = remember { mutableStateListOf<Pair<String?, String>>(null to playerName) }
    var items by remember { mutableStateOf<List<LibraryItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val here = path.last()

    LaunchedEffect(here.first, path.size) {
        items = null
        error = null
        runCatching { library.browse(packageName, here.first) }
            .onSuccess { items = it }
            .onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (path.size > 1) IconButton(onClick = { path.removeAt(path.lastIndex) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(here.second, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                when {
                    error != null -> Text(error!!, style = MaterialTheme.typography.bodyMedium)
                    items == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                    items!!.isEmpty() -> Text("Nothing here.", style = MaterialTheme.typography.bodyMedium)
                    else -> LazyColumn {
                        items(items!!, key = { it.mediaId }) { item ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (item.playable) onPick(PlaylistChoice(packageName, item.mediaId, item.title))
                                    else if (item.browsable) path.add(item.mediaId to item.title)
                                }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(if (item.playable) Icons.Filled.PlayArrow else Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(20.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                                if (item.browsable && !item.playable) Icon(Icons.Filled.ChevronRight, null)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
