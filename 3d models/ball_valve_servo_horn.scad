// OpenSCAD 2021.01 compatible
// Coupler for DS3218 round servo horn to 4.7 x 4.7 mm square ball-valve stem
// v3 changes: 3.0 mm middle screw hole, tighter valve socket, deeper/narrower center screw-head pocket.
//
// Assembly intent:
// - first mount the original round servo horn to the DS3218 servo
// - then screw this printed coupler to the round horn using four screws
// - the square socket slips over the ball valve's square stem
//
// Important:
// - Do not print a servo spline. The original horn carries the spline load.
// - Servo travel must be limited in software because the original valve knob
//   contained the 90 degree stop.

$fn = 72;

// -----------------------------
// Servo horn interface
// -----------------------------
horn_d                  = 20.6;  // measured round horn outer diameter
coupler_flange_d        = 20.0;  // slightly smaller than horn, avoids overhang mismatch
horn_screw_radius       = 7.0;   // four holes, measured from center
horn_screw_count        = 4;
horn_screw_pilot_d      = 1.9;   // pilot holes for screws to bite into printed plastic
horn_screw_pilot_depth  = 4.2;

// Clearance pocket for the center screw head that fixes the horn to the servo.
// Adjust if your center screw head is larger.
center_screw_pocket_d   = 6.2;
center_screw_pocket_h   = 2.8;

// Additional centered screw hole through the direct-drive coupler.
// Default is a tight pilot matching the other 1.9 mm printed screw holes.
middle_screw_hole_enabled = true;
middle_screw_hole_d       = 3.0;

// -----------------------------
// Valve stem socket
// -----------------------------
valve_stem_w            = 4.7;
valve_stem_d            = 4.7;
valve_socket_clearance  = 0.15;  // total clearance, not per side; tighter grip on 4.7 mm square stem
valve_socket_w          = valve_stem_w + valve_socket_clearance;
valve_socket_d          = valve_stem_d + valve_socket_clearance;
valve_socket_depth      = 5.6;

// Lower nose must fit into the 9.5 mm orange circular recess.
// Keep this comfortably below 9.5 mm.
// The nose is deliberately tall: the wider flange starts only above the raised
// 22.9 mm orange actuator area so the coupler does not rub on it.
drive_nose_d            = 8.2;
drive_nose_h            = 11.0;
flange_h                = 3.5;
transition_h            = 0.8;

// -----------------------------
// Derived
// -----------------------------
coupler_total_h = drive_nose_h + flange_h;

// -----------------------------
// Geometry
// -----------------------------
module coupler_solid() {
    union() {
        // Lower drive nose into the orange recess.
        cylinder(h = drive_nose_h, d = drive_nose_d);

        // Main flange against the underside of the servo horn.
        translate([0, 0, drive_nose_h])
            cylinder(h = flange_h, d = coupler_flange_d);

        // Small transition fillet-like cone for strength.
        translate([0, 0, drive_nose_h - transition_h])
            cylinder(h = transition_h, d1 = drive_nose_d, d2 = min(coupler_flange_d, drive_nose_d + 5.0));
    }
}

module square_socket_cut() {
    translate([-valve_socket_w / 2, -valve_socket_d / 2, -0.1])
        cube([valve_socket_w, valve_socket_d, valve_socket_depth + 0.1]);
}

module horn_screw_pilot_cuts() {
    for (i = [0 : horn_screw_count - 1]) {
        a = 360 / horn_screw_count * i;
        translate([
            horn_screw_radius * cos(a),
            horn_screw_radius * sin(a),
            coupler_total_h - horn_screw_pilot_depth
        ])
            cylinder(h = horn_screw_pilot_depth + 0.5, d = horn_screw_pilot_d);
    }
}

module center_screw_head_pocket_cut() {
    translate([0, 0, coupler_total_h - center_screw_pocket_h])
        cylinder(h = center_screw_pocket_h + 0.3, d = center_screw_pocket_d);
}

module middle_screw_hole_cut() {
    if (middle_screw_hole_enabled) {
        translate([0, 0, -0.1])
            cylinder(h = coupler_total_h + 0.2, d = middle_screw_hole_d);
    }
}

module horn_coupler() {
    difference() {
        coupler_solid();
        square_socket_cut();
        horn_screw_pilot_cuts();
        center_screw_head_pocket_cut();
        middle_screw_hole_cut();
    }
}

horn_coupler();
