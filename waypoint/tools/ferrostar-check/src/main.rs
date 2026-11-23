use ferrostar::deviation_detection::RouteDeviationTracking;
use ferrostar::models::{Route, UserLocation};
use ferrostar::navigation_controller::models::{
    CourseFiltering, NavigationControllerConfig, TripState, WaypointAdvanceMode,
};
use ferrostar::navigation_controller::step_advance::{
    step_advance_distance_entry_and_exit, step_advance_distance_to_end_of_step,
};
use ferrostar::navigation_controller::Navigator;
use ferrostar::navigation_session::create_navigation_session;
use ferrostar::routing_adapters::osrm::OsrmResponseParser;
use ferrostar::routing_adapters::RouteResponseParser;
use ferrostar::simulation::{advance_location_simulation, location_simulation_from_route, LocationBias};
use std::path::Path;

/// The same settings the app uses (TripSession.navigationConfig).
fn config() -> NavigationControllerConfig {
    NavigationControllerConfig {
        waypoint_advance: WaypointAdvanceMode::WaypointWithinRange(100.0),
        step_advance_condition: step_advance_distance_entry_and_exit(30, 5, 32),
        arrival_step_advance_condition: step_advance_distance_to_end_of_step(10, 32),
        route_deviation_tracking: RouteDeviationTracking::StaticThreshold {
            minimum_horizontal_accuracy: 15,
            max_acceptable_deviation: 50.0,
        },
        snapped_location_course_filtering: CourseFiltering::SnapToRoute,
    }
}

fn parse(dir: &Path, i: usize) -> Option<Route> {
    let route = std::fs::read_to_string(dir.join(format!("leg{i}.route.json"))).ok()?;
    let waypoints = std::fs::read_to_string(dir.join(format!("leg{i}.waypoints.json"))).ok()?;
    // Same path as Route.fromOsrm on Android: one OSRM route plus its waypoints.
    let response = format!(r#"{{"code":"Ok","routes":[{route}],"waypoints":{waypoints}}}"#);
    let mut routes = OsrmResponseParser::new(6)
        .parse_response(response.into_bytes())
        .unwrap_or_else(|e| panic!("leg {i}: Ferrostar could not parse it: {e:?}"));
    Some(routes.remove(0))
}

fn main() {
    let dir = std::env::args().nth(1).expect("usage: ferrostar-check <split-legs dir>");
    let dir = Path::new(&dir);
    let mut i = 0;
    while let Some(route) = parse(dir, i) {
        let session = create_navigation_session(route.clone(), config(), vec![]);
        let mut sim = location_simulation_from_route(&route, Some(10.0), LocationBias::None).unwrap();
        let start: UserLocation = sim.current_location;
        let mut state = session.get_initial_state(start);
        let mut updates = 0;
        let mut deviations = 0;
        loop {
            let next = advance_location_simulation(&sim);
            if next.current_location == sim.current_location {
                break;
            }
            sim = next;
            state = session.update_user_location(sim.current_location, state);
            updates += 1;
            if let TripState::Navigating { deviation, .. } = state.trip_state() {
                if !matches!(deviation, ferrostar::deviation_detection::RouteDeviation::NoDeviation) {
                    deviations += 1;
                }
            }
            if matches!(state.trip_state(), TripState::Complete { .. }) {
                break;
            }
        }
        let done = matches!(state.trip_state(), TripState::Complete { .. });
        println!(
            "leg {i}: {} steps, {} waypoints, {:.0} m, {updates} location updates, {deviations} off-route, {}",
            route.steps.len(),
            route.waypoints.len(),
            route.distance,
            if done { "reached the stop (Complete)" } else { "NOT complete" }
        );
        assert!(done, "leg {i} did not complete");
        assert_eq!(route.waypoints.len(), 2, "leg {i} should have 2 waypoints");
        assert_eq!(deviations, 0, "leg {i}: simulated drive went off route");
        i += 1;
    }
    assert!(i > 0, "no legs found in {}", dir.display());
    println!("All {i} legs parse and navigate to completion in Ferrostar 0.59.0.");
}
