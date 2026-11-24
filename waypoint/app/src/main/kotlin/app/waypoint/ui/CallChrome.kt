package app.waypoint.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.waypoint.calls.CallContact
import app.waypoint.calls.LiveCall
import kotlinx.coroutines.delay

private val CallGreen = Color(0xFF1E8E3E)
private val CallRed = Color(0xFFD93025)

/** Round phone button under the music one; green while a call is on. */
@Composable
fun CallButton(live: Boolean, open: Boolean, onToggle: () -> Unit) {
    RoundMapButton(if (open) "Hide calls" else "Call someone on WhatsApp", onToggle) {
        if (live) {
            Box(Modifier.size(40.dp).background(CallGreen, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Phone, null, tint = Color.White)
            }
        } else {
            Icon(Icons.Filled.Phone, null)
        }
    }
}

/**
 * WhatsApp contacts to call, favourites (starred in Contacts) first. One tap calls: no menus to
 * read while driving.
 */
@Composable
fun CallPanel(
    contacts: List<CallContact>?,
    hasContactsAccess: Boolean,
    whatsAppInstalled: Boolean,
    photo: suspend (CallContact) -> Bitmap?,
    onAllowContacts: () -> Unit,
    onCall: (CallContact) -> Unit,
) {
    PanelSurface {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "Call on WhatsApp",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            when {
                !whatsAppInstalled -> Hint("WhatsApp isn't installed on this phone.")
                !hasContactsAccess -> Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Allow contacts to see who you can call.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAllowContacts) { Text("Allow") }
                }
                contacts == null -> Hint("Loading…")
                contacts.isEmpty() -> Hint("None of your contacts are on WhatsApp (or WhatsApp can't see your contacts).")
                else -> LazyColumn(Modifier.heightIn(max = 196.dp)) {
                    items(contacts, key = { it.dataId }) { c -> ContactRow(c, photo) { onCall(c) } }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: CallContact, photo: suspend (CallContact) -> Bitmap?, onCall: () -> Unit) {
    val picture by produceState<Bitmap?>(null, contact.dataId) { value = photo(contact) }
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Call ${contact.name}", onClick = onCall).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(contact.name, picture)
        Text(
            contact.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        if (contact.starred) Icon(Icons.Filled.Star, "Favourite", Modifier.size(18.dp).padding(end = 4.dp), tint = Palette.Amber)
        Icon(Icons.Filled.Call, null, tint = CallGreen)
    }
}

@Composable
private fun Avatar(name: String?, picture: Bitmap?) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            Image(picture.asImageBitmap(), null, Modifier.size(40.dp), contentScale = ContentScale.Crop)
        } else if (name.isNullOrBlank()) {
            Icon(Icons.Filled.Phone, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        } else {
            Text(name.trim().take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The call in progress (or ringing), in place of the music controls: who, how long, and the call
 * app's own answer / decline / hang-up buttons. Tap it to open the call screen.
 */
@Composable
fun LiveCallPanel(
    call: LiveCall,
    hasNotificationAccess: Boolean,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onOpen: () -> Unit,
    onAllowAccess: () -> Unit,
) {
    PanelSurface {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.clickable(onClickLabel = "Open call", onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
                Avatar(call.caller, null)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        call.caller ?: if (call.incoming) "Incoming call" else "Call in progress",
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(call.appName, callStatus(call)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.merge(Numbers), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (call.incoming && call.answer != null) {
                    RoundAction(Icons.Filled.CallEnd, "Decline", CallRed, onDecline)
                    RoundAction(Icons.Filled.Call, "Answer", CallGreen, onAnswer)
                } else if (call.hangUp != null || call.decline != null) {
                    RoundAction(Icons.Filled.CallEnd, "Hang up", CallRed, onHangUp)
                }
            }
            if (!hasNotificationAccess) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Allow notification access to see who it is and hang up from here.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAllowAccess) { Text("Allow") }
                }
            }
        }
    }
}

/** "Ringing", "Calling…" or the call's running time. */
@Composable
private fun callStatus(call: LiveCall): String? {
    val since = call.since ?: return if (call.incoming) "Ringing" else null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = color, modifier = Modifier.padding(start = 8.dp).size(52.dp)) {
        IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
    }
}

@Composable
private fun PanelSurface(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
