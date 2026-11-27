package app.waypoint.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import app.waypoint.data.FavouriteCategories
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Saved trips, favourite places and presets (spec §6.6). */
@Composable
fun SavedScreen(vm: BuilderViewModel, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Trips", "Places", "Presets").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> TripsTab(vm, onBack)
                1 -> PlacesTab(vm, onBack)
                else -> PresetsTab(vm, onBack)
            }
        }
    }
}

@Composable
private fun TripsTab(vm: BuilderViewModel, onLoaded: () -> Unit) {
    val trips by vm.savedTrips.collectAsState()
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        if (trips.isEmpty()) item { Empty("Trips you save from the builder appear here, ready to start again.") }
        items(trips, key = { it.id }) { t ->
            Row2(
                title = t.name ?: "Trip",
                subtitle = t.stops.joinToString(" → ") { it.place.label },
                onClick = { vm.load(t); onLoaded() },
                onDelete = { vm.deleteSaved(t.id) },
            )
        }
    }
}

/** Favourites by category (Home and Work first); tap to add as a stop, the pencil to edit. */
@Composable
private fun PlacesTab(vm: BuilderViewModel, onAdded: () -> Unit) {
    val favourites by vm.favourites.collectAsState()
    var editing by remember { mutableStateOf<FavouriteEdit?>(null) }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        if (favourites.isEmpty()) {
            item { Empty("Star places in search, or hold the map and Save, to keep them here. Tap one to add it as a stop.") }
        }
        for ((category, list) in FavouriteCategories.grouped(favourites)) {
            item(key = "c:" + (category ?: "")) {
                Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(categoryIcon(category), null, tint = categoryColour(category), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    SectionLabel(category ?: "No category")
                }
            }
            items(list, key = { it.id }) { f ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { vm.pickTarget = PickTarget.AddStop; vm.pick(f.place, f.arrivalPoint); onAdded() }
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(f.place.label, style = MaterialTheme.typography.titleMedium)
                        val details = listOfNotNull(
                            f.place.address,
                            "car park saved".takeIf { f.arrivalPoint != null },
                            "quick button".takeIf { f.quick && !FavouriteCategories.isUnique(FavouriteCategories.normalise(f.category)) },
                        ).joinToString(" · ")
                        if (details.isNotBlank()) {
                            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = { editing = FavouriteEdit.Existing(f) }) { Icon(Icons.Filled.Edit, "Edit ${f.place.label}") }
                }
            }
        }
    }
    editing?.let { FavouriteEditorFor(it, vm, favourites) { editing = null } }
}

@Composable
private fun PresetsTab(vm: BuilderViewModel, onApplied: () -> Unit) {
    val presets by vm.presets.collectAsState()
    var naming by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            OutlinedButton(onClick = { naming = true }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("Save the current trip's modes as a preset")
            }
        }
        if (presets.isEmpty()) item { Empty("A preset remembers how you travel (mode or Fastest chips, Auto/Manual, stay) to apply to any trip.") }
        items(presets, key = { it.id }) { p ->
            Row2(
                title = p.name,
                subtitle = "${modeChoiceLabel(p.defaultMode)} · ${if (p.defaultContinuation == app.waypoint.trip.Continuation.Manual) "Manual" else "Auto"}",
                onClick = { vm.applyPreset(p); onApplied() },
                onDelete = { vm.deletePreset(p.id) },
            )
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Preset name") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Commute") }) },
            confirmButton = { TextButton(onClick = { vm.savePresetFromTrip(name.ifBlank { "Preset" }); naming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Row2(title: String, subtitle: String, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
    }
}
