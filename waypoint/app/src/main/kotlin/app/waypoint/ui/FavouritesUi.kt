package app.waypoint.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.waypoint.data.Favourite
import app.waypoint.data.FavouriteCategories
import app.waypoint.data.FavouriteCategories.HOME
import app.waypoint.data.FavouriteCategories.WORK
import app.waypoint.trip.Place

/** Each category's icon; your own categories share one, uncategorised favourites keep the star. */
fun categoryIcon(category: String?): ImageVector = when (FavouriteCategories.normalise(category)) {
    null -> Icons.Filled.Star
    HOME -> Icons.Filled.Home
    WORK -> Icons.Filled.Work
    "Family" -> Icons.Filled.FamilyRestroom
    "Friends" -> Icons.Filled.Group
    "Food & drink" -> Icons.Filled.Restaurant
    "Shopping" -> Icons.Filled.ShoppingBag
    "Gym & sport" -> Icons.Filled.FitnessCenter
    "Health" -> Icons.Filled.LocalHospital
    else -> Icons.AutoMirrored.Filled.Label
}

private val CUSTOM_COLOURS = listOf(0xFF5E35B1, 0xFF00838F, 0xFF6D4C41, 0xFF3949AB, 0xFFAD1457, 0xFF558B2F)

/** Each category's colour (on the map pins and the quick buttons); your own get one of a few, by name. */
fun categoryColour(category: String?): Color = Color(
    when (val c = FavouriteCategories.normalise(category)) {
        null -> 0xFFF9A825
        HOME -> 0xFF1A73E8
        WORK -> 0xFF795548
        "Family" -> 0xFFE91E63
        "Friends" -> 0xFFFB8C00
        "Food & drink" -> 0xFFF4511E
        "Shopping" -> 0xFF00897B
        "Gym & sport" -> 0xFF43A047
        "Health" -> 0xFFE53935
        else -> CUSTOM_COLOURS[Math.floorMod(c.lowercase().hashCode(), CUSTOM_COLOURS.size)]
    },
)

/**
 * The quick buttons under "Where to?": Home, Work, and a third of your choice. Tapping one plans
 * the way there from where you are; an unset Home or Work asks where it is. Holding Home or Work
 * changes it; holding the third changes which favourite it is.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickButtons(
    favourites: List<Favourite>,
    onGo: (Favourite) -> Unit,
    onSet: (category: String) -> Unit,
    onChooseThird: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun Chip(icon: ImageVector, tint: Color, label: String?, description: String, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
        val shape = RoundedCornerShape(20.dp)
        Surface(
            shape = shape, tonalElevation = 3.dp, shadowElevation = 2.dp,
            modifier = Modifier.clip(shape).combinedClickable(onClickLabel = description, onClick = onClick, onLongClick = onLongClick),
        ) {
            Row(Modifier.padding(horizontal = if (label == null) 10.dp else 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, if (label == null) description else null, tint = tint, modifier = Modifier.size(18.dp))
                if (label != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                }
            }
        }
    }

    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (category in listOf(HOME, WORK)) {
            val place = if (category == HOME) FavouriteCategories.home(favourites) else FavouriteCategories.work(favourites)
            Chip(
                categoryIcon(category), categoryColour(category),
                label = if (place != null) category else "Set ${category.lowercase()}",
                description = if (place != null) "Directions to $category" else "Set your ${category.lowercase()}",
                onClick = { if (place != null) onGo(place) else onSet(category) },
                onLongClick = { onSet(category) },
            )
        }
        val third = FavouriteCategories.quick(favourites)
        if (third != null) {
            Chip(
                categoryIcon(third.category), categoryColour(third.category), third.place.label, "Directions to ${third.place.label}",
                onClick = { onGo(third) }, onLongClick = onChooseThird,
            )
        } else {
            Chip(Icons.Filled.Add, MaterialTheme.colorScheme.onSurfaceVariant, null, "Add a quick button", onClick = onChooseThird, onLongClick = null)
        }
    }
}

/** Choosing the favourite on the third quick button. */
@Composable
fun ThirdButtonPicker(favourites: List<Favourite>, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val choices = favourites.filterNot { FavouriteCategories.isUnique(FavouriteCategories.normalise(it.category)) }
        .sortedBy { it.place.label.lowercase() }
    val current = FavouriteCategories.quick(favourites)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Third quick button") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (choices.isEmpty()) {
                    Text(
                        "Save a place as a favourite first: star it in search, or hold the map and Save. Home and Work have their own buttons.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                for (f in choices) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(f.id) }.padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(categoryIcon(f.category), null, tint = categoryColour(f.category))
                        Spacer(Modifier.width(14.dp))
                        Text(f.place.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (f.id == current?.id) Text("On the button", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (current != null) TextButton(onClick = { onPick(null) }) { Text("No third button") } },
    )
}

/**
 * A favourite's name, category and quick button. Home and Work move here from any other place
 * (it says so); they have their own buttons, so the quick-button switch is for other categories.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavouriteEditor(
    title: String,
    id: String?,
    initialName: String,
    initialCategory: String?,
    initialQuick: Boolean,
    favourites: List<Favourite>,
    onSave: (name: String, category: String?, quick: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var category by remember { mutableStateOf(FavouriteCategories.normalise(initialCategory)) }
    var quick by remember { mutableStateOf(initialQuick) }
    var newCategory by remember { mutableStateOf<String?>(null) }
    val categories = remember(favourites) { FavouriteCategories.all(favourites) }
    val chosen = newCategory?.let { FavouriteCategories.match(it, favourites) } ?: category
    val unique = FavouriteCategories.isUnique(chosen)
    val holder = favourites.firstOrNull { it.id != id && FavouriteCategories.normalise(it.category) == chosen && unique }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Category", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = chosen == null, onClick = { category = null; newCategory = null }, label = { Text("None") })
                    for (c in categories) {
                        FilterChip(
                            selected = newCategory == null && category == c,
                            onClick = { category = c; newCategory = null },
                            label = { Text(c) },
                            leadingIcon = { Icon(categoryIcon(c), null, tint = categoryColour(c), modifier = Modifier.size(18.dp)) },
                        )
                    }
                    FilterChip(selected = newCategory != null, onClick = { newCategory = newCategory ?: "" }, label = { Text("New…") })
                }
                newCategory?.let { typed ->
                    OutlinedTextField(typed, { newCategory = it }, label = { Text("New category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                holder?.let {
                    Text(
                        "${it.place.label} is your ${chosen!!.lowercase()} now; this place takes over.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Quick button on the map")
                        Text(
                            if (unique) "$chosen has its own button." else "The third button, next to Home and Work.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = quick && !unique, onCheckedChange = { quick = it }, enabled = !unique)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, chosen, quick && !unique) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** A favourite tapped on the map: where it is, directions there from you, or edit it. */
@Composable
fun FavouriteCard(favourite: Favourite, modifier: Modifier, onDirections: () -> Unit, onEdit: () -> Unit, onClose: () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(categoryIcon(favourite.category), null, tint = categoryColour(favourite.category))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(favourite.place.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(FavouriteCategories.normalise(favourite.category), favourite.place.address).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDirections) {
                    Icon(Icons.Filled.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Directions")
                }
                FilledTonalButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Edit")
                }
            }
        }
    }
}

/** What the editor is editing: a saved favourite, or a place (a dropped pin) to save. */
sealed interface FavouriteEdit {
    data class Existing(val favourite: Favourite) : FavouriteEdit
    data class New(val place: Place) : FavouriteEdit
}

/** The editor for [edit], wired to the builder's favourites. */
@Composable
fun FavouriteEditorFor(edit: FavouriteEdit, vm: BuilderViewModel, favourites: List<Favourite>, onDone: () -> Unit) {
    when (edit) {
        is FavouriteEdit.Existing -> FavouriteEditor(
            title = "Edit favourite", id = edit.favourite.id,
            initialName = edit.favourite.place.label, initialCategory = edit.favourite.category, initialQuick = edit.favourite.quick,
            favourites = favourites,
            onSave = { name, category, quick -> vm.editFavourite(edit.favourite.id, name, category, quick); onDone() },
            onDelete = { vm.unfavourite(edit.favourite.id); onDone() },
            onDismiss = onDone,
        )
        is FavouriteEdit.New -> FavouriteEditor(
            title = "Save as favourite", id = null,
            initialName = edit.place.label.takeUnless { it == "Dropped pin" }.orEmpty(), initialCategory = null, initialQuick = false,
            favourites = favourites,
            onSave = { name, category, quick -> vm.saveFavourite(edit.place, name, category, quick); onDone() },
            onDelete = null,
            onDismiss = onDone,
        )
    }
}
