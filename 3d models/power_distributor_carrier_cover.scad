 // OpenSCAD 2021.01 compatible
// Power-distributor carrier cover.
//
// Matching dimensions for power_distributor_carrier_base.scad.
// The cover is an open-bottom shell sized to land on the 3 mm cover-rest shelf.
// This revision places two equally sized rain roofs on the left and right sides
// of the cover, i.e. on the -X and +X walls.

$fn = 72;

// -----------------------------
// Finished power-distributor board envelope
// -----------------------------
board_length = 90.0;   // X direction
board_width  = 40.0;   // Y direction
board_height = 35.0;   // Z direction; internal board height to cover

// -----------------------------
// Matching base/rim dimensions
// -----------------------------
board_margin         = 5.0;
cover_rest_width     = 3.0;   // documented here to keep the base/cover design tied together

rim_outer_length = board_length + 2 * board_margin;
rim_outer_width  = board_width  + 2 * board_margin;

// -----------------------------
// Cover parameters
// -----------------------------
fit_clearance          = 0.6;
wall_thickness         = 2.4;   // sized so the cover lands on the 3 mm rest shelf
cover_top_thickness    = 3.0;
cover_height_clearance = 1.0;
cover_inside_height    = board_height + cover_height_clearance;

// -----------------------------
// Equal left/right cable openings and rain roofs
// -----------------------------
// The slots are centered along Y on both X-side walls.  Both roofs use exactly
// the same dimensions so the left and right cover sides are symmetrical.
side_slots_enabled     = true;
side_slot_width        = 40.0;  // opening length along Y on each side wall
side_slot_top_z        = 12.0;
roof_clearance         = 1.0;
roof_projection        = 3.0;
roof_drop              = 6.0;
roof_thickness         = 2.0;
roof_side_margin       = 5.0;
roof_cheek_thickness   = 1.8;
bottom_chamfer_h       = 1.2;
bottom_chamfer_in      = 0.7;

// -----------------------------
// Derived cover dimensions
// -----------------------------
cover_inner_length = rim_outer_length + 2 * fit_clearance;
cover_inner_width  = rim_outer_width  + 2 * fit_clearance;
cover_outer_length = cover_inner_length + 2 * wall_thickness;
cover_outer_width  = cover_inner_width  + 2 * wall_thickness;
cover_outer_height = cover_inside_height + cover_top_thickness;

side_slot_center_y = cover_outer_width / 2;
side_slot_y0       = side_slot_center_y - side_slot_width / 2;
side_slot_y1       = side_slot_center_y + side_slot_width / 2;

side_roof_y0       = side_slot_y0 - roof_side_margin;
side_roof_y1       = side_slot_y1 + roof_side_margin;
side_roof_width    = side_roof_y1 - side_roof_y0;
side_roof_back_z   = side_slot_top_z + roof_clearance;
side_roof_front_z  = side_roof_back_z - roof_drop;

// -----------------------------
// Cover shell geometry
// -----------------------------
module cover_outer_shell() {
    cube([cover_outer_length, cover_outer_width, cover_outer_height]);
}

module cover_inner_cavity() {
    translate([wall_thickness, wall_thickness, 0])
        cube([cover_inner_length, cover_inner_width, cover_inside_height + 0.1]);
}

module cover_bottom_softening_cut() {
    if (bottom_chamfer_h > 0 && bottom_chamfer_in > 0) {
        translate([wall_thickness - 0.01, wall_thickness - 0.01, 0])
            difference() {
                cube([cover_inner_length + 0.02, cover_inner_width + 0.02, bottom_chamfer_h]);
                translate([bottom_chamfer_in, bottom_chamfer_in, -0.01])
                    cube([
                        cover_inner_length - 2 * bottom_chamfer_in + 0.02,
                        cover_inner_width  - 2 * bottom_chamfer_in + 0.02,
                        bottom_chamfer_h + 0.02
                    ]);
            }
    }
}

// -----------------------------
// Left/right slot cutouts
// -----------------------------
module cover_slot_cutout_left(center_y, width, top_z) {
    if (side_slots_enabled)
        translate([-1, center_y - width / 2, 0])
            cube([wall_thickness + 2, width, top_z]);
}

module cover_slot_cutout_right(center_y, width, top_z) {
    if (side_slots_enabled)
        translate([cover_outer_length - wall_thickness - 1, center_y - width / 2, 0])
            cube([wall_thickness + 2, width, top_z]);
}

// -----------------------------
// Left-side rain roof and cheeks, on -X wall
// -----------------------------
module rain_roof_left(y0, width, back_z, front_z) {
    hull() {
        translate([-roof_thickness / 2, y0, back_z])
            cube([roof_thickness, width, roof_thickness]);
        translate([-roof_projection - roof_thickness / 2, y0, front_z])
            cube([roof_thickness, width, roof_thickness]);
    }
}

module rain_cheek_left_lower_y(y0, slot_top, back_z, front_z) {
    hull() {
        translate([-roof_thickness / 2, y0 - roof_cheek_thickness, slot_top])
            cube([roof_thickness, roof_cheek_thickness, back_z + roof_thickness - slot_top]);
        translate([-roof_projection - roof_thickness / 2, y0 - roof_cheek_thickness, front_z])
            cube([roof_thickness, roof_cheek_thickness, roof_thickness]);
    }
}

module rain_cheek_left_upper_y(y1, slot_top, back_z, front_z) {
    hull() {
        translate([-roof_thickness / 2, y1, slot_top])
            cube([roof_thickness, roof_cheek_thickness, back_z + roof_thickness - slot_top]);
        translate([-roof_projection - roof_thickness / 2, y1, front_z])
            cube([roof_thickness, roof_cheek_thickness, roof_thickness]);
    }
}

// -----------------------------
// Right-side rain roof and cheeks, on +X wall
// -----------------------------
module rain_roof_right(y0, width, back_z, front_z) {
    hull() {
        translate([cover_outer_length - roof_thickness / 2, y0, back_z])
            cube([roof_thickness, width, roof_thickness]);
        translate([cover_outer_length + roof_projection - roof_thickness / 2, y0, front_z])
            cube([roof_thickness, width, roof_thickness]);
    }
}

module rain_cheek_right_lower_y(y0, slot_top, back_z, front_z) {
    hull() {
        translate([cover_outer_length - roof_thickness / 2, y0 - roof_cheek_thickness, slot_top])
            cube([roof_thickness, roof_cheek_thickness, back_z + roof_thickness - slot_top]);
        translate([cover_outer_length + roof_projection - roof_thickness / 2, y0 - roof_cheek_thickness, front_z])
            cube([roof_thickness, roof_cheek_thickness, roof_thickness]);
    }
}

module rain_cheek_right_upper_y(y1, slot_top, back_z, front_z) {
    hull() {
        translate([cover_outer_length - roof_thickness / 2, y1, slot_top])
            cube([roof_thickness, roof_cheek_thickness, back_z + roof_thickness - slot_top]);
        translate([cover_outer_length + roof_projection - roof_thickness / 2, y1, front_z])
            cube([roof_thickness, roof_cheek_thickness, roof_thickness]);
    }
}

module equal_left_right_roofs_and_cheeks() {
    if (side_slots_enabled) {
        rain_roof_left(side_roof_y0, side_roof_width, side_roof_back_z, side_roof_front_z);
        rain_cheek_left_lower_y(side_roof_y0, side_slot_top_z, side_roof_back_z, side_roof_front_z);
        rain_cheek_left_upper_y(side_roof_y1, side_slot_top_z, side_roof_back_z, side_roof_front_z);

        rain_roof_right(side_roof_y0, side_roof_width, side_roof_back_z, side_roof_front_z);
        rain_cheek_right_lower_y(side_roof_y0, side_slot_top_z, side_roof_back_z, side_roof_front_z);
        rain_cheek_right_upper_y(side_roof_y1, side_slot_top_z, side_roof_back_z, side_roof_front_z);
    }
}

module equal_left_right_slot_cutouts() {
    cover_slot_cutout_left(side_slot_center_y, side_slot_width, side_slot_top_z);
    cover_slot_cutout_right(side_slot_center_y, side_slot_width, side_slot_top_z);
}

module power_distributor_carrier_cover() {
    difference() {
        union() {
            cover_outer_shell();
            equal_left_right_roofs_and_cheeks();
        }
        cover_inner_cavity();
        cover_bottom_softening_cut();
        equal_left_right_slot_cutouts();
    }
}

power_distributor_carrier_cover();
