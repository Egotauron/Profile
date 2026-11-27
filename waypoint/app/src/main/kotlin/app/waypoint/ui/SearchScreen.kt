package app.waypoint.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.waypoint.data.Repository
import app.waypoint.graph
import app.waypoint.trip.Geo
import app.waypoint.trip.LatLng
import app.waypoint.trip.Place
import kotlinx.coroutines.delay

/**
 * Place search (spec §2.1): Photon, debounced to about one request a second, with favourites and
 * recent places first because Photon is weaker than Google on shop and restaurant names.
 */
@Composable
fun SearchScreen(vm: BuilderViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val here by vm.here.collectAsState()
    val favourites by vm.favourites.collectAsState()
    val recents by vm.recents.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val target = vm.pickTarget

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); error = null; return@LaunchedEffect }
        delay(1_000)
        loading = true
        runCatching { context.graph.places.search(query, here, 10) }
            .onSuccess { results = it; error = null }
            .onFailure { error = it.message }
        loading = false
    }

    fun choose(place: Place, arrival: LatLng? = null) {
        vm.pick(place, arrival)
        onDone()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        when (target) {
                            PickTarget.Origin -> "Start from…"
                            is PickTarget.CarPark -> "Car park or entrance…"
                            is PickTarget.Favourite -> "Your ${target.category.lowercase()}: search for it…"
                            else -> "Search places and addresses"
                        },
                    )
                },
                singleLine = true,
                trailingIcon = { if (loading) CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp) },
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (target == PickTarget.Origin) item {
                PlaceRow(Icons.Filled.MyLocation, "Current location", null, null) { vm.useCurrentLocationAsOrigin(); onDone() }
            }
            error?.let { item { Text("Search failed: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            if (query.isBlank()) {
                if (favourites.isNotEmpty()) item { SectionLabel("Favourites", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
                items(favourites, key = { "f" + it.id }) { f ->
                    PlaceRow(categoryIcon(f.category), f.place.label, f.place.address, distanceText(here, f.place.latLng)) { choose(f.place, f.arrivalPoint) }
                }
                if (recents.isNotEmpty()) item { SectionLabel("Recent", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
                items(recents, key = { "r" + Repository.placeKey(it) }) { p ->
                    PlaceRow(Icons.Filled.History, p.label, p.address, distanceText(here, p.latLng)) { choose(p) }
                }
            } else {
                val favKeys = favourites.map { it.id }.toSet()
                items(results, key = { "s" + Repository.placeKey(it) }) { p ->
                    val fav = Repository.placeKey(p) in favKeys
                    PlaceRow(
                        Icons.Filled.Place, p.label, p.address, distanceText(here, p.latLng),
                        trailing = {
                            IconButton(onClick = { if (fav) vm.unfavourite(Repository.placeKey(p)) else vm.favourite(p) }) {
                                Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, if (fav) "Remove favourite" else "Add to favourites")
                            }
                        },
                    ) { choose(p) }
                }
                if (results.isEmpty() && !loading && error == null) item {
                    Text(
                        "No matches yet. Photon knows addresses and landmarks best; save shops you use as favourites.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            item {
                Text(
                    "Search by Photon · © OpenStreetMap contributors",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

private fun distanceText(here: LatLng?, p: LatLng): String? = here?.let {
    val m = Geo.distanceMeters(it, p)
    if (m < 1000) "${(m / 10).toInt() * 10} m" else "%.1f km".format(java.util.Locale.UK, m / 1000)
}

@Composable
private fun PlaceRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    trailingText: String?,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailingText?.let { Text(it, style = MaterialTheme.typography.labelMedium.merge(Numbers), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        trailing?.invoke()
    }
}
