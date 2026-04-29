// OpenSCAD 2021.01 compatible
// Protective cover / housing for the pump holder
//
// Features:
// - exact OUTER dimensions: 229.5 x 35 x 40 mm
// - open on the bottom
// - cable slit in the front long side wall
// - slit is open down to z = 0
// - sloped rain roof above the slit
// - additional side cheeks on left and right for outdoor rain protection

$fn = 64;

// -----------------------------
// User parameters
// -----------------------------
outer_width  = 235.0;   // X 228.5 is the plate
outer_depth  = 40.5;    // Y 34 is the plate
outer_height = 40.0;    // Z

wall_thickness = 3;   // side wall thickness
top_thickness  = 3;   // top wall thickness

// Cable slit
cable_slot_x      = outer_width * 0.58;  // center position along width
cable_slot_width  = 100.0;                // opening width in X
cable_slot_top_z  = 10.0;                // opening top height; bottom is fixed at z = 0

// Rain roof
roof_clearance    = 1.2;   // gap from slot top to roof underside
roof_projection   = 3.0;   // how far the roof sticks out
roof_drop         = 6.0;   // how much the outer edge drops for runoff
roof_thickness    = 2.0;   // roof material thickness
roof_side_margin  = 8.0;   // extra width beyond slot on each side

// Side cheeks
side_cheek_thickness = 2.0; // thickness of the side rain cheeks

// -----------------------------
// Helpers
// -----------------------------
roof_x0 = cable_slot_x - cable_slot_width / 2 - roof_side_margin;
roof_x1 = cable_slot_x + cable_slot_width / 2 + roof_side_margin;
roof_width = roof_x1 - roof_x0;

roof_back_z = cable_slot_top_z + roof_clearance;
roof_front_z = roof_back_z - roof_drop;

module outer_shell() {
    cube([outer_width, outer_depth, outer_height]);
}

module inner_cavity() {
    translate([wall_thickness, wall_thickness, 0])
        cube([
            outer_width - 2 * wall_thickness,
            outer_depth - 2 * wall_thickness,
            outer_height - top_thickness
        ]);
}

module cable_slot_cutout_front() {
    translate([
        cable_slot_x - cable_slot_width / 2,
        -1,
        0
    ])
        cube([
            cable_slot_width,
            wall_thickness + 2,
            cable_slot_top_z
        ]);
}

// Main sloped rain roof above the slit
module rain_roof_front() {
    hull() {
        // rear strip, close to wall
        translate([roof_x0, -roof_thickness / 2, roof_back_z])
            cube([roof_width, roof_thickness, roof_thickness]);

        // front strip, lower for runoff
        translate([roof_x0, -roof_projection - roof_thickness / 2, roof_front_z])
            cube([roof_width, roof_thickness, roof_thickness]);
    }
}

// Left side cheek
module rain_cheek_left() {
    hull() {
        // rear vertical strip near wall
        translate([roof_x0 - side_cheek_thickness, -roof_thickness / 2, cable_slot_top_z])
            cube([side_cheek_thickness, roof_thickness, roof_back_z + roof_thickness - cable_slot_top_z]);

        // front upper strip at roof tip
        translate([roof_x0 - side_cheek_thickness, -roof_projection - roof_thickness / 2, roof_front_z])
            cube([side_cheek_thickness, roof_thickness, roof_thickness]);
    }
}

// Right side cheek
module rain_cheek_right() {
    hull() {
        // rear vertical strip near wall
        translate([roof_x1, -roof_thickness / 2, cable_slot_top_z])
            cube([side_cheek_thickness, roof_thickness, roof_back_z + roof_thickness - cable_slot_top_z]);

        // front upper strip at roof tip
        translate([roof_x1, -roof_projection - roof_thickness / 2, roof_front_z])
            cube([side_cheek_thickness, roof_thickness, roof_thickness]);
    }
}

module protective_cover() {
    difference() {
        union() {
            outer_shell();
            rain_roof_front();
            rain_cheek_left();
            rain_cheek_right();
        }

        // Bottom-open cavity
        inner_cavity();

        // Cable slit
        cable_slot_cutout_front();
    }
}

protective_cover();
