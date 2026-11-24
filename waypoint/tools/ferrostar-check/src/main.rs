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

/// The same settings the app uses (FerrostarNavigationEngine.navigationConfig).
fn config() -> NavigationControllerConfig {
    config_with(25, 35.0)
}

fn config_with(minimum_horizontal_accuracy: u16, max_acceptable_deviation: f64) -> NavigationControllerConfig {
    NavigationControllerConfig {
        waypoint_advance: WaypointAdvanceMode::WaypointWithinRange(100.0),
        step_advance_condition: step_advance_distance_entry_and_exit(30, 5, 32),
        arrival_step_advance_condition: step_advance_distance_to_end_of_step(10, 32),
        route_deviation_tracking: RouteDeviationTracking::StaticThreshold {
            minimum_horizontal_accuracy,
            max_acceptable_deviation,
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

/// `metres` from `from` towards `bearing` (degrees from north).
fn moved(from: &UserLocation, bearing: f64, metres: f64) -> UserLocation {
    let mut out = *from;
    let b = bearing.to_radians();
    out.coordinates.lat += metres * b.cos() / 111_320.0;
    out.coordinates.lng += metres * b.sin() / (111_320.0 * from.coordinates.lat.to_radians().cos());
    out
}

/// Drives a third of the way along the route, then leaves it at a right angle with fixes of the
/// given accuracy: how far off the route you get before Ferrostar says so (None: not within 300 m).
fn metres_until_off_route(route: &Route, config: NavigationControllerConfig, accuracy: f64) -> Option<f64> {
    let session = create_navigation_session(route.clone(), config, vec![]);
    let mut sim = location_simulation_from_route(route, Some(10.0), LocationBias::None).unwrap();
    let mut state = session.get_initial_state(sim.current_location);
    let mut driven = 0.0;
    while driven < route.distance / 3.0 {
        let next = advance_location_simulation(&sim);
        if next.current_location == sim.current_location {
            break;
        }
        sim = next;
        state = session.update_user_location(sim.current_location, state);
        driven += 10.0;
    }
    let turn_off = sim.current_location;
    let course = turn_off.course_over_ground.map(|c| c.degrees as f64).unwrap_or(0.0);
    for i in 1..=60 {
        let off = i as f64 * 5.0;
        let mut fix = moved(&turn_off, course + 90.0, off);
        fix.horizontal_accuracy = accuracy;
        state = session.update_user_location(fix, state);
        if let TripState::Navigating { deviation, .. } = state.trip_state() {
            if !matches!(deviation, ferrostar::deviation_detection::RouteDeviation::NoDeviation) {
                return Some(off);
            }
        }
    }
    None
}

/// Off-route reports while driving the whole route with every fix `offset` metres to one side.
fn false_alarms(route: &Route, config: NavigationControllerConfig, offset: f64, accuracy: f64) -> usize {
    let session = create_navigation_session(route.clone(), config, vec![]);
    let mut sim = location_simulation_from_route(route, Some(10.0), LocationBias::Right(offset)).unwrap();
    let mut state = session.get_initial_state(sim.current_location);
    let mut alarms = 0;
    loop {
        let next = advance_location_simulation(&sim);
        if next.current_location == sim.current_location {
            break;
        }
        sim = next;
        let mut fix = sim.current_location;
        fix.horizontal_accuracy = accuracy;
        state = session.update_user_location(fix, state);
        if let TripState::Navigating { deviation, .. } = state.trip_state() {
            if !matches!(deviation, ferrostar::deviation_detection::RouteDeviation::NoDeviation) {
                alarms += 1;
            }
        }
        if matches!(state.trip_state(), TripState::Complete { .. }) {
            break;
        }
    }
    alarms
}

fn show(m: Option<f64>) -> String {
    m.map(|m| format!("{m:.0} m")).unwrap_or_else(|| "never".into())
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

    // Leaving the route (spec: re-route quickly), old settings (15 m accuracy, 50 m) vs new.
    let drive = parse(dir, 0).unwrap();
    println!("\nTurning off leg 0 at a right angle; off route reported after:");
    for accuracy in [8.0, 20.0] {
        let old = metres_until_off_route(&drive, config_with(15, 50.0), accuracy);
        let new = metres_until_off_route(&drive, config(), accuracy);
        println!("  fixes accurate to {accuracy:.0} m: before {}, now {}", show(old), show(new));
        assert!(new.is_some(), "should notice leaving the route");
        assert!(old.is_none_or(|o| new.unwrap() < o), "should notice sooner than before");
    }
    // GPS that's consistently off to one side mustn't look like leaving the route.
    for offset in [15.0, 25.0] {
        let alarms = false_alarms(&drive, config(), offset, 20.0);
        println!("  driving the route with every fix {offset:.0} m to one side: {alarms} false off-route reports");
        assert_eq!(alarms, 0);
    }
}
