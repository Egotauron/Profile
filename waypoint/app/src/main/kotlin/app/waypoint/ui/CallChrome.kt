package app.waypoint.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waypoint.calls.CallContact
import app.waypoint.calls.LiveCall
import kotlin.math.absoluteValue
import kotlinx.coroutines.delay

private val CallGreen = Color(0xFF1E8E3E)
private val CallRed = Color(0xFFD93025)
/** Calls sit on a dark card in light and dark mode alike, as in Android Auto. */
private val CallCard = Color(0xFF202124)
private val OnCallCard = Color.White
private val OnCallCardDim = Color(0xB8FFFFFF)
private val AvatarColors = listOf(
    Color(0xFF1A73E8), Color(0xFF188038), Color(0xFFE37400), Color(0xFFC5221F), Color(0xFF9334E6), Color(0xFF007B83),
)

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
 * WhatsApp contacts as a row of faces, favourites (starred in Contacts) first; one tap calls. Big
 * targets and pictures rather than a list to read while driving.
 */
@Composable
fun CallPanel(
    contacts: List<CallContact>?,
    hasContactsAccess: Boolean,
    whatsAppInstalled: Boolean,
    appIcon: ImageBitmap?,
    photo: suspend (CallContact) -> Bitmap?,
    onAllowContacts: () -> Unit,
    onCall: (CallContact) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the report of the last call attempt (diagnostic build). */
    onLastAttempt: (() -> Unit)? = null,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, tonalElevation = 2.dp) {
        Box(contentAlignment = Alignment.CenterStart) {
        Column(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (appIcon != null) {
                    Image(appIcon, null, Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)))
                } else {
                    Icon(Icons.Filled.Call, null, Modifier.size(20.dp), tint = CallGreen)
                }
                Spacer(Modifier.width(8.dp))
                Text("Call on WhatsApp", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (onLastAttempt != null) {
                    Text(
                        "Last attempt", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onLastAttempt).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
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
                else -> LazyRow(Modifier.padding(top = 2.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(contacts, key = { it.dataId }) { c -> ContactTile(c, photo) { onCall(c) } }
                }
            }
        }
        }
    }
}

@Composable
private fun ContactTile(contact: CallContact, photo: suspend (CallContact) -> Bitmap?, onCall: () -> Unit) {
    val picture by produceState<Bitmap?>(null, contact.dataId) { value = photo(contact) }
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "Call ${contact.name}", onClick = onCall)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Avatar(contact.name, picture, 48.dp)
            Box(
                Modifier.align(Alignment.BottomEnd).size(20.dp)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .background(CallGreen, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Call, null, Modifier.size(11.dp), tint = Color.White) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            shortName(contact.name), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** "Anna Rossi" → "Anna R.", so names fit under the faces. */
internal fun shortName(name: String): String {
    val words = name.trim().split(Regex("\\s+"))
    return if (words.size < 2) name.trim() else "${words.first()} ${words.last().first().uppercaseChar()}."
}

/** The contact's photo, or their initials on a colour that stays the same for them. */
@Composable
private fun Avatar(name: String?, picture: Bitmap?, size: Dp) {
    val color = AvatarColors[(name?.hashCode() ?: 0).absoluteValue % AvatarColors.size]
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        when {
            picture != null -> Image(picture.asImageBitmap(), null, Modifier.size(size), contentScale = ContentScale.Crop)
            name.isNullOrBlank() -> Icon(Icons.Filled.Phone, null, tint = Color.White)
            else -> Text(
                initials(name), color = Color.White, fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.32f).sp,
            )
        }
    }
}

private fun initials(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.let { w ->
        if (w.size >= 2) "${w.first().first()}${w.last().first()}" else w.firstOrNull()?.take(1).orEmpty()
    }.uppercase()

/**
 * The call in progress (or ringing), in place of the music controls: a dark call card with who it
 * is, how long, and the call app's own answer / decline / hang-up buttons. Tap it to open the call.
 */
@Composable
fun LiveCallPanel(
    call: LiveCall,
    photo: Bitmap?,
    hasNotificationAccess: Boolean,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onOpen: () -> Unit,
    onAllowAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = CallCard, shadowElevation = 8.dp) {
        Box(contentAlignment = Alignment.CenterStart) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = "Open call", onClick = onOpen),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(call.caller, photo, 48.dp)
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        Text(
                            call.caller ?: if (call.incoming) "Incoming call" else "On a call",
                            style = MaterialTheme.typography.titleMedium, color = OnCallCard,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (call.since != null) {
                                Box(Modifier.size(8.dp).background(Color(0xFF34A853), CircleShape))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                listOfNotNull(call.appName, callStatus(call)).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium.merge(Numbers), color = OnCallCardDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (call.incoming && call.answer != null) {
                    CallAction(Icons.Filled.CallEnd, "Decline", CallRed, onDecline)
                    Spacer(Modifier.width(10.dp))
                    CallAction(Icons.Filled.Call, "Answer", CallGreen, onAnswer)
                } else if (call.hangUp != null || call.decline != null) {
                    CallAction(Icons.Filled.CallEnd, "Hang up", CallRed, onHangUp, wide = true)
                }
            }
            if (!hasNotificationAccess) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Allow notification access to see who it is and hang up from here.",
                        style = MaterialTheme.typography.bodySmall, color = OnCallCardDim, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAllowAccess) { Text("Allow", color = Color(0xFF8AB4F8)) }
                }
            }
        }
        }
    }
}

/** "Ringing", "Calling…" or the call's running time. */
@Composable
private fun callStatus(call: LiveCall): String? {
    val since = call.since ?: return if (call.incoming) "Ringing" else if (call.appName != null) "Calling…" else null
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

/** A round (or, for hang-up, pill-shaped) call button. */
@Composable
private fun CallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit, wide: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        color = color,
        modifier = Modifier.size(width = if (wide) 72.dp else 52.dp, height = 52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp)) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * Diagnostic build: what happened when a WhatsApp call was started, to copy and relay. Names and
 * numbers in it are masked.
 */
@Composable
fun CallReportDialog(report: app.waypoint.calls.CallReport, onCopy: () -> Unit, onOpenChat: (() -> Unit)?, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (report.failed) "The call didn't start" else "Last call attempt") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text(
                    "Copy this report and pass it on to get calling fixed. Names and phone numbers are masked.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(report.text, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text("Copy") } },
        dismissButton = {
            Row {
                onOpenChat?.let { TextButton(onClick = it) { Text("Open chat") } }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
