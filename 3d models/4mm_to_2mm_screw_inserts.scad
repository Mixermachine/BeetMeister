// OpenSCAD 2021.01 compatible
// Press-fit reducer inserts / bushings for existing 4.0 mm holes
//
// Purpose:
// - drop into an existing 4.0 mm printed hole
// - top flange prevents the insert from falling through
// - inner hole fits ~2.0 mm screws with easy clearance
//
// Default design intent:
// - outside body slightly undersized for a printed 4.0 mm hole
// - light taper helps insertion
// - flange keeps the insert from dropping through
//
// Print a few test pieces first and tweak body diameters if needed.

$fn = 64;

// -----------------------------
// User parameters
// -----------------------------
existing_hole_d       = 4.0;   // hole already in your printed part
plate_thickness       = 7.0;   // thickness of the part the insert sits in

// Fit / bushing geometry
body_d_top            = 3.95;  // near the flange
body_d_bottom         = 3.85;  // small taper for easier insertion
flange_d              = 6.6;   // larger than 4 mm hole so it cannot fall through
flange_thickness      = 1.2;   // top lip thickness
lead_in_chamfer_h     = 0.8;   // little taper at the bottom

// Screw clearance
screw_clear_d         = 2.2;   // good starting point for 2.0 mm screw to turn freely

// Batch layout for printing
insert_count          = 1;
insert_spacing        = 9.0;   // distance between insert centers in print layout

// -----------------------------
// Derived
// -----------------------------
body_h = max(0.1, plate_thickness - flange_thickness);

// -----------------------------
// Geometry
// -----------------------------
module insert_single() {
    difference() {
        union() {
            // flange
            cylinder(h = flange_thickness, d = flange_d);

            // main body
            translate([0, 0, flange_thickness])
                cylinder(h = body_h, d1 = body_d_top, d2 = body_d_bottom);

            // small lead-in chamfer at bottom
            translate([0, 0, flange_thickness + body_h - lead_in_chamfer_h])
                cylinder(h = lead_in_chamfer_h, d1 = body_d_bottom, d2 = max(0.2, body_d_bottom - 0.35));
        }

        // through hole for the screw
        translate([0, 0, -1])
            cylinder(h = plate_thickness + 3, d = screw_clear_d);
    }
}

module insert_array() {
    for (i = [0 : insert_count - 1]) {
        translate([i * insert_spacing, 0, 0])
            insert_single();
    }
}

// Uncomment ONE of these if you want a different output:
// insert_single();
insert_array();
