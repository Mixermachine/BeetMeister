// OpenSCAD 2021.01 compatible
// Cover for the relay-board extension plate
//
// Features:
// - primary slit with rain roof on one long wall
// - secondary control-cable slit on the opposing +Y wall
// - secondary slit centered in the housing along X
// - rain roof added for BOTH slits
// - OpenSCAD 2021.01 safe variable ordering

$fn = 64;

// -----------------------------
// Matching plate parameters
// -----------------------------
pcb_width      = 136.0;  // mm
pcb_depth      = 53.0;   // mm
pcb_margin     = 5.0;    // mm border around PCB on the plate

// -----------------------------
// Cover parameters
// -----------------------------
fit_clearance      = 0.6;   // mm clearance around rim outer size
wall_thickness     = 3.0;   // mm side wall thickness
top_thickness      = 3.0;   // mm top thickness
inside_height      = 35.0;  // mm inside height from bottom edge to underside of top

// Primary cable slit in long side wall
slot_side          = "front"; // "front" or "back"
slot_x             = (pcb_width + 2 * pcb_margin) * 0.50; // position relative to INNER rim size
slot_width         = 100.0;   // mm opening width along X
slot_top_z         = 10.0;   // slit is open from z=0 to this height

// Secondary slit on the OPPOSING long wall, centered in the housing
control_slot_enabled = true;
control_slot_width   = 12.0;  // mm opening width along X
control_slot_top_z   = 12.0;  // open from z=0 to this height

// Rain roof above both slits
roof_clearance       = 1.0;    // gap from slit top to roof underside
roof_projection      = 3.0;    // how far roof sticks out
roof_drop            = 6.0;    // how much outer edge drops
roof_thickness       = 2.0;    // roof material thickness
roof_side_margin     = 8;    // extra width beyond slit
roof_cheek_thickness = 1.8;    // side cheek thickness

// Optional small overhang lip near the bottom for a cleaner look
bottom_chamfer_h   = 1.2;   // mm
bottom_chamfer_in  = 0.8;   // mm

// -----------------------------
// Derived dimensions
// -----------------------------
rim_outer_width = pcb_width + 2 * pcb_margin;
rim_outer_depth = pcb_depth + 2 * pcb_margin;

inner_width  = rim_outer_width + 2 * fit_clearance;
inner_depth  = rim_outer_depth + 2 * fit_clearance;
outer_width  = inner_width + 2 * wall_thickness;
outer_depth  = inner_depth + 2 * wall_thickness;
outer_height = inside_height + top_thickness;

// Primary slot position measured from OUTER left edge
slot_center_x = wall_thickness + fit_clearance + slot_x;

// Secondary slot centered in the housing along X
control_slot_center_x = outer_width / 2;

// Roof footprint for primary slit
roof1_x0 = slot_center_x - slot_width / 2 - roof_side_margin;
roof1_x1 = slot_center_x + slot_width / 2 + roof_side_margin;
roof1_width = roof1_x1 - roof1_x0;

roof1_back_z  = slot_top_z + roof_clearance;
roof1_front_z = roof1_back_z - roof_drop;

// Roof footprint for control slit
roof2_x0 = control_slot_center_x - control_slot_width / 2 - roof_side_margin;
roof2_x1 = control_slot_center_x + control_slot_width / 2 + roof_side_margin;
roof2_width = roof2_x1 - roof2_x0;

roof2_back_z  = control_slot_top_z + roof_clearance;
roof2_front_z = roof2_back_z - roof_drop;

// -----------------------------
// Geometry
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

module cable_slot_cutout_front(center_x, width, top_z) {
    translate([center_x - width / 2, -1, 0])
        cube([width, wall_thickness + 2, top_z]);
}

module cable_slot_cutout_back(center_x, width, top_z) {
    translate([center_x - width / 2, outer_depth - wall_thickness - 1, 0])
        cube([width, wall_thickness + 2, top_z]);
}

// Generic roof on the front wall (y=0 side), sloping outward toward -Y
module rain_roof_front(x0, width, slot_top, back_z, front_z) {
    hull() {
        translate([x0, -roof_thickness / 2, back_z])
            cube([width, roof_thickness, roof_thickness]);

        translate([x0, -roof_projection - roof_thickness / 2, front_z])
            cube([width, roof_thickness, roof_thickness]);
    }
}

module rain_cheek_front_left(x0, slot_top, back_z, front_z) {
    hull() {
        translate([x0 - roof_cheek_thickness, -roof_thickness / 2, slot_top])
            cube([roof_cheek_thickness, roof_thickness, back_z + roof_thickness - slot_top]);

        translate([x0 - roof_cheek_thickness, -roof_projection - roof_thickness / 2, front_z])
            cube([roof_cheek_thickness, roof_thickness, roof_thickness]);
    }
}

module rain_cheek_front_right(x1, slot_top, back_z, front_z) {
    hull() {
        translate([x1, -roof_thickness / 2, slot_top])
            cube([roof_cheek_thickness, roof_thickness, back_z + roof_thickness - slot_top]);

        translate([x1, -roof_projection - roof_thickness / 2, front_z])
            cube([roof_cheek_thickness, roof_thickness, roof_thickness]);
    }
}

// Generic roof on the back wall (+Y side), sloping outward toward +Y
module rain_roof_back(x0, width, slot_top, back_z, front_z) {
    hull() {
        translate([x0, outer_depth - roof_thickness / 2, back_z])
            cube([width, roof_thickness, roof_thickness]);

        translate([x0, outer_depth + roof_projection - roof_thickness / 2, front_z])
            cube([width, roof_thickness, roof_thickness]);
    }
}

module rain_cheek_back_left(x0, slot_top, back_z, front_z) {
    hull() {
        translate([x0 - roof_cheek_thickness, outer_depth - roof_thickness / 2, slot_top])
            cube([roof_cheek_thickness, roof_thickness, back_z + roof_thickness - slot_top]);

        translate([x0 - roof_cheek_thickness, outer_depth + roof_projection - roof_thickness / 2, front_z])
            cube([roof_cheek_thickness, roof_thickness, roof_thickness]);
    }
}

module rain_cheek_back_right(x1, slot_top, back_z, front_z) {
    hull() {
        translate([x1, outer_depth - roof_thickness / 2, slot_top])
            cube([roof_cheek_thickness, roof_thickness, back_z + roof_thickness - slot_top]);

        translate([x1, outer_depth + roof_projection - roof_thickness / 2, front_z])
            cube([roof_cheek_thickness, roof_thickness, roof_thickness]);
    }
}

module primary_roof_and_cheeks() {
    if (slot_side == "front") {
        rain_roof_front(roof1_x0, roof1_width, slot_top_z, roof1_back_z, roof1_front_z);
        rain_cheek_front_left(roof1_x0, slot_top_z, roof1_back_z, roof1_front_z);
        rain_cheek_front_right(roof1_x1, slot_top_z, roof1_back_z, roof1_front_z);
    } else {
        rain_roof_back(roof1_x0, roof1_width, slot_top_z, roof1_back_z, roof1_front_z);
        rain_cheek_back_left(roof1_x0, slot_top_z, roof1_back_z, roof1_front_z);
        rain_cheek_back_right(roof1_x1, slot_top_z, roof1_back_z, roof1_front_z);
    }
}

module control_roof_and_cheeks() {
    if (control_slot_enabled) {
        if (slot_side == "front") {
            // opposing slit is on +Y / back wall
            rain_roof_back(roof2_x0, roof2_width, control_slot_top_z, roof2_back_z, roof2_front_z);
            rain_cheek_back_left(roof2_x0, control_slot_top_z, roof2_back_z, roof2_front_z);
            rain_cheek_back_right(roof2_x1, control_slot_top_z, roof2_back_z, roof2_front_z);
        } else {
            // opposing slit is on front wall
            rain_roof_front(roof2_x0, roof2_width, control_slot_top_z, roof2_back_z, roof2_front_z);
            rain_cheek_front_left(roof2_x0, control_slot_top_z, roof2_back_z, roof2_front_z);
            rain_cheek_front_right(roof2_x1, control_slot_top_z, roof2_back_z, roof2_front_z);
        }
    }
}

module primary_slot_cutout() {
    if (slot_side == "front")
        cable_slot_cutout_front(slot_center_x, slot_width, slot_top_z);
    else
        cable_slot_cutout_back(slot_center_x, slot_width, slot_top_z);
}

module opposing_control_slot_cutout() {
    if (control_slot_enabled) {
        if (slot_side == "front")
            cable_slot_cutout_back(control_slot_center_x, control_slot_width, control_slot_top_z);
        else
            cable_slot_cutout_front(control_slot_center_x, control_slot_width, control_slot_top_z);
    }
}

module relay_cover() {
    difference() {
        union() {
            outer_shell();
            primary_roof_and_cheeks();
            control_roof_and_cheeks();
        }
        inner_cavity();
        bottom_softening_cut();
        primary_slot_cutout();
        opposing_control_slot_cutout();
    }
}

relay_cover();
