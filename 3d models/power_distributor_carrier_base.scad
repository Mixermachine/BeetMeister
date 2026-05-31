// OpenSCAD 2021.01 compatible
// Power-distributor carrier base, split from the combined carrier/cover file.
//
// Style references used from the uploaded models:
// - relay_module.scad: PCB corner standoffs with pilot holes
// - controller_module.scad / relay_module.scad: rounded connection lugs
// - controller_carrier_cover.scad / relay_module_cover.scad / pump_module_cover.scad: separate base/cover workflow
//
// Requested finished-board envelope:
// - length: 90 mm
// - width:  40 mm
// - height: 35 mm; height is used by the separate cover file, not by this base.

$fn = 72;

// -----------------------------
// Finished power-distributor board footprint
// -----------------------------
board_length = 90.0;   // X direction
board_width  = 40.0;   // Y direction
board_height = 35.0;   // kept here as documentation; cover uses this value too

// -----------------------------
// Carrier / rim dimensions
// -----------------------------
plate_thickness      = 4.0;
board_margin         = 5.0;   // rim zone around the 90 x 40 board footprint
rim_height           = 5.0;
rim_thickness        = 2.0;
cover_rest_width     = 3.0;   // flat ledge outside the rim for the cover to sit on

// Optional footprint marking on the carrier floor
show_footprint       = true;
footprint_line_w     = 0.8;
footprint_h          = 0.4;

// -----------------------------
// Board standoffs
// -----------------------------
// Same basic construction as relay_module.scad: round post with a central pilot hole.
standoffs_enabled    = true;
standoff_height      = 4.0;
standoff_diameter    = 7.5;
standoff_pilot_d     = 1.9;   // pilot for 2.0 mm screws to bite into printed plastic
standoff_edge_offset = 9.0;   // from each finished-board edge to standoff/hole center

// -----------------------------
// One-sided relay/controller-style mounting lugs
// -----------------------------
lugs_enabled         = true;
lug_side             = "front"; // "front" = -Y side, "back" = +Y side
lug_count            = 4;
lug_spacing          = 27.313;  // same pitch used in the existing relay/controller parts
lug_hole_d           = 2.3;     // clearance for 2.0 mm screws through this carrier
lug_pad_diameter     = 10.0;
lug_neck_diameter    = 10.0;
lug_hole_offset_y    = 11.0;    // distance from rim/body side to lug-hole center

// -----------------------------
// Derived dimensions
// -----------------------------
rim_outer_length = board_length + 2 * board_margin;
rim_outer_width  = board_width  + 2 * board_margin;

base_x0     = -cover_rest_width;
base_y0     = -cover_rest_width;
base_length = rim_outer_length + 2 * cover_rest_width;
base_width  = rim_outer_width  + 2 * cover_rest_width;

board_x0 = board_margin;
board_y0 = board_margin;

// Four board-support standoff positions inside the finished-board footprint.
standoff_x1 = board_x0 + standoff_edge_offset;
standoff_x2 = board_x0 + board_length - standoff_edge_offset;
standoff_y1 = board_y0 + standoff_edge_offset;
standoff_y2 = board_y0 + board_width - standoff_edge_offset;

lug_span = (lug_count - 1) * lug_spacing;
lug_x0   = rim_outer_length / 2 - lug_span / 2;

// -----------------------------
// Carrier geometry
// -----------------------------
module carrier_base_body() {
    translate([base_x0, base_y0, 0])
        cube([base_length, base_width, plate_thickness]);
}

module rounded_lug_front(xc) {
    hull() {
        translate([xc, -lug_hole_offset_y, 0])
            cylinder(h = plate_thickness, d = lug_pad_diameter);
        translate([xc, 0, 0])
            cylinder(h = plate_thickness, d = lug_neck_diameter);
    }
}

module rounded_lug_back(xc) {
    hull() {
        translate([xc, rim_outer_width + lug_hole_offset_y, 0])
            cylinder(h = plate_thickness, d = lug_pad_diameter);
        translate([xc, rim_outer_width, 0])
            cylinder(h = plate_thickness, d = lug_neck_diameter);
    }
}

module all_lugs() {
    if (lugs_enabled) {
        for (i = [0 : lug_count - 1]) {
            x = lug_x0 + i * lug_spacing;
            if (lug_side == "front")
                rounded_lug_front(x);
            else if (lug_side == "back")
                rounded_lug_back(x);
        }
    }
}

module lug_holes() {
    if (lugs_enabled) {
        for (i = [0 : lug_count - 1]) {
            x = lug_x0 + i * lug_spacing;
            if (lug_side == "front")
                translate([x, -lug_hole_offset_y, -1])
                    cylinder(h = plate_thickness + 2, d = lug_hole_d);
            else if (lug_side == "back")
                translate([x, rim_outer_width + lug_hole_offset_y, -1])
                    cylinder(h = plate_thickness + 2, d = lug_hole_d);
        }
    }
}

module pcb_mount_standoff(x, y) {
    difference() {
        translate([x, y, plate_thickness])
            cylinder(h = standoff_height, d = standoff_diameter);

        translate([x, y, plate_thickness - 1])
            cylinder(h = standoff_height + 2, d = standoff_pilot_d);
    }
}

module board_standoffs() {
    if (standoffs_enabled) {
        pcb_mount_standoff(standoff_x1, standoff_y1);
        pcb_mount_standoff(standoff_x2, standoff_y1);
        pcb_mount_standoff(standoff_x1, standoff_y2);
        pcb_mount_standoff(standoff_x2, standoff_y2);
    }
}

module protective_rim() {
    difference() {
        translate([0, 0, plate_thickness])
            cube([rim_outer_length, rim_outer_width, rim_height]);
        translate([rim_thickness, rim_thickness, plate_thickness - 1])
            cube([
                rim_outer_length - 2 * rim_thickness,
                rim_outer_width  - 2 * rim_thickness,
                rim_height + 2
            ]);
    }
}

module board_footprint_mark() {
    if (show_footprint) {
        translate([board_x0, board_y0, plate_thickness])
            difference() {
                cube([board_length, board_width, footprint_h]);
                translate([footprint_line_w, footprint_line_w, -0.1])
                    cube([
                        board_length - 2 * footprint_line_w,
                        board_width  - 2 * footprint_line_w,
                        footprint_h + 0.2
                    ]);
            }
    }
}

module power_distributor_carrier_base() {
    difference() {
        union() {
            carrier_base_body();
            all_lugs();
        }
        lug_holes();
    }

    board_standoffs();
    protective_rim();
    board_footprint_mark();
}

power_distributor_carrier_base();
