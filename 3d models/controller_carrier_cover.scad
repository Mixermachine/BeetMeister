// OpenSCAD 2021.01 compatible
// Protective cover for esp32_breakout_carrier_with_lugs_v6_right_corner_lugs.scad
//
// Features:
// - open-bottom cover that fits over the ESP32 carrier rim
// - cover outer footprint matches the carrier's 3 mm cover-rest shelf
// - two centered cable openings/roofs on the same sides as the lugs
//   * left / -X lug side: 40 mm roof
//   * right / +X lug side: 80 mm roof
// - rain roofs with side cheeks, following the relay/pump cover style

$fn = 64;

// -----------------------------
// Matching ESP32 carrier v6 dimensions
// -----------------------------
carrier_body_width  = 126.0;
carrier_body_depth  = 117.313;  // 80 mm board + one 27.313 mm lug pitch + 2*5 mm margin
cover_rest_width    = 3.0;      // shelf available around the carrier rim

// -----------------------------
// Cover fit and height
// -----------------------------
fit_clearance   = 0.6;   // side clearance around the carrier rim
wall_thickness  = 2.4;   // with clearance, outside lands on the 3 mm shelf
inside_height   = 47.0;  // underside of top above carrier/rim
top_thickness   = 3.0;

// Derived cover dimensions
inner_width  = carrier_body_width  + 2 * fit_clearance;
inner_depth  = carrier_body_depth  + 2 * fit_clearance;
outer_width  = inner_width  + 2 * wall_thickness;
outer_depth  = inner_depth  + 2 * wall_thickness;
outer_height = inside_height + top_thickness;

// -----------------------------
// Cable openings and rain roofs on lug sides
// -----------------------------
left_roof_width  = 60.0;  // requested 4 cm roof, centered on -X lug side
right_roof_width = 80.0;  // requested 8 cm roof, centered on +X lug side

slot_top_z        = 12.0;  // openings are open from z=0 to this height
roof_clearance    = 1.0;
roof_projection   = 3.0;
roof_drop         = 6.0;
roof_thickness    = 2.0;
roof_side_margin  = 6.0;   // slot is smaller than roof, roof width stays exact
roof_cheek_thick  = 1.8;

left_slot_width  = left_roof_width  - 2 * roof_side_margin;
right_slot_width = right_roof_width - 2 * roof_side_margin;

left_center_y  = outer_depth / 2.5;
right_center_y = outer_depth / 2;

left_roof_y0  = left_center_y  - left_roof_width / 2;
left_roof_y1  = left_center_y  + left_roof_width / 2;
right_roof_y0 = right_center_y - right_roof_width / 2;
right_roof_y1 = right_center_y + right_roof_width / 2;

left_slot_y0  = left_center_y  - left_slot_width / 2;
right_slot_y0 = right_center_y - right_slot_width / 2;

roof_back_z  = slot_top_z + roof_clearance;
roof_front_z = roof_back_z - roof_drop;

// Optional small inner chamfer near bottom so the cover starts easily over the rim
bottom_chamfer_h  = 1.2;
bottom_chamfer_in = 0.7;

// -----------------------------
// Core shell
// -----------------------------
module outer_shell() {
    cube([outer_width, outer_depth, outer_height]);
}

module inner_cavity() {
    translate([wall_thickness, wall_thickness, 0])
        cube([inner_width, inner_depth, inside_height + 0.1]);
}

module bottom_softening_cut() {
    if (bottom_chamfer_h > 0 && bottom_chamfer_in > 0) {
        translate([wall_thickness - 0.01, wall_thickness - 0.01, 0])
            difference() {
                cube([inner_width + 0.02, inner_depth + 0.02, bottom_chamfer_h]);
                translate([bottom_chamfer_in, bottom_chamfer_in, -0.01])
                    cube([
                        inner_width - 2 * bottom_chamfer_in + 0.02,
                        inner_depth - 2 * bottom_chamfer_in + 0.02,
                        bottom_chamfer_h + 0.02
                    ]);
            }
    }
}

// -----------------------------
// Slot cutouts on the lug-side walls
// -----------------------------
module cable_slot_cutout_left(y0, width, top_z) {
    translate([-1, y0, 0])
        cube([wall_thickness + 2, width, top_z]);
}

module cable_slot_cutout_right(y0, width, top_z) {
    translate([outer_width - wall_thickness - 1, y0, 0])
        cube([wall_thickness + 2, width, top_z]);
}

// -----------------------------
// Left roof: x=0 side, sloping outward toward -X
// -----------------------------
module rain_roof_left(y0, width) {
    hull() {
        translate([-roof_thickness / 2, y0, roof_back_z])
            cube([roof_thickness, width, roof_thickness]);
        translate([-roof_projection - roof_thickness / 2, y0, roof_front_z])
            cube([roof_thickness, width, roof_thickness]);
    }
}

module rain_cheek_left_bottom(y0) {
    hull() {
        translate([-roof_thickness / 2, y0 - roof_cheek_thick, slot_top_z])
            cube([roof_thickness, roof_cheek_thick, roof_back_z + roof_thickness - slot_top_z]);
        translate([-roof_projection - roof_thickness / 2, y0 - roof_cheek_thick, roof_front_z])
            cube([roof_thickness, roof_cheek_thick, roof_thickness]);
    }
}

module rain_cheek_left_top(y1) {
    hull() {
        translate([-roof_thickness / 2, y1, slot_top_z])
            cube([roof_thickness, roof_cheek_thick, roof_back_z + roof_thickness - slot_top_z]);
        translate([-roof_projection - roof_thickness / 2, y1, roof_front_z])
            cube([roof_thickness, roof_cheek_thick, roof_thickness]);
    }
}

// -----------------------------
// Right roof: x=outer_width side, sloping outward toward +X
// -----------------------------
module rain_roof_right(y0, width) {
    hull() {
        translate([outer_width - roof_thickness / 2, y0, roof_back_z])
            cube([roof_thickness, width, roof_thickness]);
        translate([outer_width + roof_projection - roof_thickness / 2, y0, roof_front_z])
            cube([roof_thickness, width, roof_thickness]);
    }
}

module rain_cheek_right_bottom(y0) {
    hull() {
        translate([outer_width - roof_thickness / 2, y0 - roof_cheek_thick, slot_top_z])
            cube([roof_thickness, roof_cheek_thick, roof_back_z + roof_thickness - slot_top_z]);
        translate([outer_width + roof_projection - roof_thickness / 2, y0 - roof_cheek_thick, roof_front_z])
            cube([roof_thickness, roof_cheek_thick, roof_thickness]);
    }
}

module rain_cheek_right_top(y1) {
    hull() {
        translate([outer_width - roof_thickness / 2, y1, slot_top_z])
            cube([roof_thickness, roof_cheek_thick, roof_back_z + roof_thickness - slot_top_z]);
        translate([outer_width + roof_projection - roof_thickness / 2, y1, roof_front_z])
            cube([roof_thickness, roof_cheek_thick, roof_thickness]);
    }
}

module left_roof_and_cheeks() {
    rain_roof_left(left_roof_y0, left_roof_width);
    rain_cheek_left_bottom(left_roof_y0);
    rain_cheek_left_top(left_roof_y1);
}

module right_roof_and_cheeks() {
    rain_roof_right(right_roof_y0, right_roof_width);
    rain_cheek_right_bottom(right_roof_y0);
    rain_cheek_right_top(right_roof_y1);
}

module esp32_carrier_cover() {
    difference() {
        union() {
            outer_shell();
            left_roof_and_cheeks();
            right_roof_and_cheeks();
        }

        inner_cavity();
        bottom_softening_cut();

        cable_slot_cutout_left(left_slot_y0, left_slot_width, slot_top_z);
        cable_slot_cutout_right(right_slot_y0, right_slot_width, slot_top_z);
    }
}

esp32_carrier_cover();
