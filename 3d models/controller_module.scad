// OpenSCAD 2021.01 compatible
// ESP32 breakout-board carrier plate with relay-style rounded lugs
//
// Purpose:
// - holds an ESP32 breakout board with screw terminals
// - provides 3 mm clearance under the ESP32 board
// - adds a terminal-block area on the side without rounded lugs
// - has a protective 5 mm rim around the electronics area
// - adds a 3 mm flat cover-rest shelf outside the rim for a future lid
// - has rounded lugs, like the relay-board plate, for screwing this board
//   to the short side of the relay-board assembly
// - uses four connection lugs while preserving the existing 27.313 mm
//   lug-hole spacing from the already-built extension board
// - extends the right-side extra-space marking in +Y with margin to match
//   the enlarged carrier body
// - adds four extra support standoffs in one centered line in the
//   right-side extra-space area
// - adds two additional rounded lugs on the RIGHT side at the two
//   opposite-side corners
//
// Coordinate convention:
// - ESP32 board is 70 mm wide in X and 80 mm high/deep in Y
// - rounded connection lugs are on the LEFT side by default
// - terminal-block expansion area is on the RIGHT side by default

$fn = 72;

// -----------------------------
// ESP32 breakout board dimensions
// -----------------------------
esp_board_width        = 70.0;   // X
esp_board_depth        = 80.0;   // Y
esp_hole_x_from_side   = 19.0;   // from left and right board edges
esp_hole_y_from_edge   = 3.0;    // from top and bottom board edges

// ESP32 standoffs
plate_thickness        = 4.0;
esp_under_clearance    = 3.0;    // requested underside clearance
esp_standoff_height    = esp_under_clearance;
esp_standoff_diameter  = 7.5;
esp_standoff_pilot_d   = 1.9;    // pilot for 2.0 mm screws to bite into PETG; printed holes run tight
mid_standoff_y_frac_1  = 1/5;    // extra support positions in one line along Y
mid_standoff_y_frac_2  = 2/5;
mid_standoff_y_frac_3  = 3/5;
mid_standoff_y_frac_4  = 4/5;

// Relay-connection rounded lugs
lug_side               = "left"; // "left" or "right"
lug_hole_d             = 2.3;    // clearance for 2.0 mm screws through this board
lug_spacing            = 27.313; // matches relay-side screw spacing
lug_count              = 4;      // four lugs; spacing stays unchanged
lug_pad_diameter       = 10.0;
lug_neck_diameter      = 10.0;
lug_hole_offset_x      = 11.0;    // distance from body side to lug hole center

// Electronics area
pcb_margin             = 5.0;    // 5 mm seam/rim around electronics
rim_height             = 5.0;
rim_thickness          = 2.0;
cover_rest_width       = 3.0;    // flat ledge outside the rim for a lid to sit on

// Extra area for positive/negative terminal-block connector boards
terminal_area_width    = 40.0;   // extra area on the side without lugs
terminal_area_gap      = 6.0;    // gap between ESP32 board and terminal area
terminal_area_depth    = esp_board_depth;
extra_board_space_y    = lug_spacing; // extra Y room, added away from existing board holes

// Outer/body margins
outer_margin_x         = 0.0;
outer_margin_y         = 0.0;

// Kept as a planning parameter for a future cover/case.
// This base plate itself is not 50 mm high.
case_total_height      = 50.0;

// -----------------------------
// Derived dimensions
// -----------------------------
electronics_width = esp_board_width + terminal_area_gap + terminal_area_width;
electronics_depth = max(esp_board_depth, terminal_area_depth) + extra_board_space_y;

body_width  = electronics_width + 2 * pcb_margin + 2 * outer_margin_x;
body_depth  = electronics_depth + 2 * pcb_margin + 2 * outer_margin_y;

// Two added RIGHT-side lugs at the opposite corners. The centers are inset
// by the lug radius so the rounded pads touch the body corner area cleanly.
right_corner_lug_y1 = lug_pad_diameter / 2;
right_corner_lug_y2 = body_depth - lug_pad_diameter / 2;

// ESP32 board placement inside body
esp_x0 = pcb_margin + outer_margin_x;
esp_y0 = pcb_margin + outer_margin_y;

// Terminal area placement on the side without lugs
terminal_x0 = esp_x0 + esp_board_width + terminal_area_gap;
terminal_y0 = pcb_margin + outer_margin_y;

// ESP32 board mounting holes
esp_hole_x1 = esp_x0 + esp_hole_x_from_side;
esp_hole_x2 = esp_x0 + esp_board_width - esp_hole_x_from_side;
esp_hole_y1 = esp_y0 + esp_hole_y_from_edge;
esp_hole_y2 = esp_y0 + esp_board_depth - esp_hole_y_from_edge;

// Right-side footprint marking: extend it in +Y so it matches the enlarged
// carrier depth while keeping the same outer margin around it
terminal_mark_depth = body_depth - 2 * pcb_margin - 2 * outer_margin_y;

// Additional support standoffs: four standoffs in one centered line in the
// right-side extra-space area, distributed along Y. The left ESP32 / extension
// board area stays clear except for the original ESP32 mounting holes.
mid_standoff_x  = terminal_x0 + terminal_area_width / 2;
mid_standoff_y1 = terminal_y0 + terminal_mark_depth * mid_standoff_y_frac_1;
mid_standoff_y2 = terminal_y0 + terminal_mark_depth * mid_standoff_y_frac_2;
mid_standoff_y3 = terminal_y0 + terminal_mark_depth * mid_standoff_y_frac_3;
mid_standoff_y4 = terminal_y0 + terminal_mark_depth * mid_standoff_y_frac_4;

// Lug Y positions.
// The first three positions are intentionally kept where they were in the
// previous 3-lug carrier so the already-built extension board hole spacing
// is not disturbed. The fourth lug is added at the same pitch.
previous_lug_count     = 3;
previous_body_depth    = max(esp_board_depth, terminal_area_depth)
                       + 2 * pcb_margin + 2 * outer_margin_y;
previous_lug_span      = (previous_lug_count - 1) * lug_spacing;
lug_span               = (lug_count - 1) * lug_spacing;
lug_y0                 = previous_body_depth / 2 - previous_lug_span / 2;

// Rim dimensions
rim_x0 = 0;
rim_y0 = 0;
rim_outer_width = body_width;
rim_outer_depth = body_depth;

// -----------------------------
// Helpers
// -----------------------------
module main_body() {
    // The rim still starts at X/Y = 0. The base plate extends outward
    // by cover_rest_width so a future lid has a flat shelf to rest on
    // without moving the existing board or lug-hole coordinates.
    translate([-cover_rest_width, -cover_rest_width, 0])
        cube([
            body_width + 2 * cover_rest_width,
            body_depth + 2 * cover_rest_width,
            plate_thickness
        ]);
}

module lug_left(yc) {
    hull() {
        translate([-lug_hole_offset_x, yc, 0])
            cylinder(h = plate_thickness, d = lug_pad_diameter);

        translate([0, yc, 0])
            cylinder(h = plate_thickness, d = lug_neck_diameter);
    }
}

module lug_right(yc) {
    hull() {
        translate([body_width + lug_hole_offset_x, yc, 0])
            cylinder(h = plate_thickness, d = lug_pad_diameter);

        translate([body_width, yc, 0])
            cylinder(h = plate_thickness, d = lug_neck_diameter);
    }
}

module all_lugs() {
    for (i = [0 : lug_count - 1]) {
        y = lug_y0 + i * lug_spacing;
        if (lug_side == "left")
            lug_left(y);
        else
            lug_right(y);
    }
}

module right_corner_lugs() {
    lug_right(right_corner_lug_y1);
    lug_right(right_corner_lug_y2);
}

module lug_holes() {
    for (i = [0 : lug_count - 1]) {
        y = lug_y0 + i * lug_spacing;
        if (lug_side == "left")
            translate([-lug_hole_offset_x, y, -1])
                cylinder(h = plate_thickness + 2, d = lug_hole_d);
        else
            translate([body_width + lug_hole_offset_x, y, -1])
                cylinder(h = plate_thickness + 2, d = lug_hole_d);
    }
}

module right_corner_lug_holes() {
    translate([body_width + lug_hole_offset_x, right_corner_lug_y1, -1])
        cylinder(h = plate_thickness + 2, d = lug_hole_d);
    translate([body_width + lug_hole_offset_x, right_corner_lug_y2, -1])
        cylinder(h = plate_thickness + 2, d = lug_hole_d);
}

module esp_standoff(x, y) {
    difference() {
        translate([x, y, plate_thickness])
            cylinder(h = esp_standoff_height, d = esp_standoff_diameter);

        translate([x, y, plate_thickness - 1])
            cylinder(h = esp_standoff_height + 2, d = esp_standoff_pilot_d);
    }
}

module esp_standoffs() {
    // Original four board-mount standoffs
    esp_standoff(esp_hole_x1, esp_hole_y1);
    esp_standoff(esp_hole_x2, esp_hole_y1);
    esp_standoff(esp_hole_x1, esp_hole_y2);
    esp_standoff(esp_hole_x2, esp_hole_y2);

    // Four extra support standoffs in one centered line in the right-side extra-space area
    esp_standoff(mid_standoff_x, mid_standoff_y1);
    esp_standoff(mid_standoff_x, mid_standoff_y2);
    esp_standoff(mid_standoff_x, mid_standoff_y3);
    esp_standoff(mid_standoff_x, mid_standoff_y4);
}

module protective_rim() {
    difference() {
        translate([rim_x0, rim_y0, plate_thickness])
            cube([rim_outer_width, rim_outer_depth, rim_height]);

        translate([rim_x0 + rim_thickness,
                   rim_y0 + rim_thickness,
                   plate_thickness - 1])
            cube([
                rim_outer_width - 2 * rim_thickness,
                rim_outer_depth - 2 * rim_thickness,
                rim_height + 2
            ]);
    }
}

// Optional visual guide only: board footprints as very shallow raised outlines.
// Set show_footprints=false before final printing if you do not want them.
show_footprints = true;
footprint_line_w = 0.8;
footprint_h = 0.4;

module footprint_rect(x0, y0, w, d) {
    if (show_footprints) {
        translate([x0, y0, plate_thickness])
            difference() {
                cube([w, d, footprint_h]);
                translate([footprint_line_w, footprint_line_w, -0.1])
                    cube([w - 2 * footprint_line_w, d - 2 * footprint_line_w, footprint_h + 0.2]);
            }
    }
}

module esp32_carrier_plate() {
    difference() {
        union() {
            main_body();
            all_lugs();
            right_corner_lugs();
        }

        lug_holes();
        right_corner_lug_holes();
    }

    esp_standoffs();

    // 5 mm rim around the full electronics area. The 3 mm cover shelf
    // is the flat base plate area just outside this rim.
    protective_rim();

    // Optional footprint outlines
    footprint_rect(esp_x0, esp_y0, esp_board_width, esp_board_depth);
    footprint_rect(terminal_x0, terminal_y0, terminal_area_width, terminal_mark_depth);
}

esp32_carrier_plate();
