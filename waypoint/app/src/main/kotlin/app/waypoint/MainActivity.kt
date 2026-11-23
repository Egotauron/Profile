package app.waypoint

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.waypoint.session.hasLocationPermission
import app.waypoint.ui.BuilderViewModel
import app.waypoint.ui.NavigationScreen
import app.waypoint.ui.PlanState
import app.waypoint.ui.SavedScreen
import app.waypoint.ui.SearchScreen
import app.waypoint.ui.SettingsScreen
import app.waypoint.ui.TimelineList
import app.waypoint.ui.TimelineScreen
import app.waypoint.ui.TripBuilderScreen
import app.waypoint.ui.WaypointTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = graph
        // A trip already running (app reopened from the notification): go straight to navigation.
        val startDestination = if (graph.session.state.value.active) "navigate" else "builder"
        setContent {
            WaypointTheme {
                val dark = isSystemInDarkTheme()
                val nav = rememberNavController()
                val vm: BuilderViewModel = viewModel()
                val session = graph.session

                // Ask once for location so "Current location" works when planning.
                val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshLocation() }
                LaunchedEffect(Unit) {
                    if (!hasLocationPermission()) {
                        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }

                // Start needs location (and, on Android 13+, notifications for the trip card).
                val startPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                    val plan = (vm.plan.value as? PlanState.Ready)?.plan
                    if (hasLocationPermission() && plan != null) {
                        session.start(plan)
                        nav.navigate("navigate")
                    } else if (result.isNotEmpty()) {
                        Toast.makeText(this, "Navigation needs location access", Toast.LENGTH_LONG).show()
                    }
                }
                fun startTrip() {
                    val perms = buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    startPermissions.launch(perms.toTypedArray())
                }

                Surface(Modifier.fillMaxSize()) {
                    NavHost(nav, startDestination = startDestination) {
                        composable("builder") {
                            TripBuilderScreen(
                                vm = vm,
                                dark = dark,
                                onSearch = { target -> vm.pickTarget = target; nav.navigate("search") },
                                onTimeline = { nav.navigate("timeline") },
                                onStart = ::startTrip,
                                onSaved = { nav.navigate("saved") },
                                onSettings = { nav.navigate("settings") },
                            )
                        }
                        composable("search") { SearchScreen(vm) { nav.popBackStack() } }
                        composable("timeline") { TimelineScreen(vm) { nav.popBackStack() } }
                        composable("saved") { SavedScreen(vm) { nav.popBackStack() } }
                        composable("settings") { SettingsScreen { nav.popBackStack() } }
                        composable("navigate") {
                            NavigationScreen(
                                session = session,
                                music = graph.music,
                                dark = dark,
                                onTimeline = { nav.navigate("live-timeline") },
                                onExit = {
                                    if (!nav.popBackStack("builder", inclusive = false)) {
                                        nav.navigate("builder") { popUpTo(0) }
                                    }
                                },
                            )
                        }
                        composable("live-timeline") {
                            val s by session.state.collectAsState()
                            Scaffold(
                                topBar = {
                                    TopAppBar(
                                        title = { Text("Trip timeline") },
                                        navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                                    )
                                },
                            ) { pad -> s.plan?.let { TimelineList(it, Modifier.padding(pad)) } }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        graph.music.refreshAccess()
    }
}
