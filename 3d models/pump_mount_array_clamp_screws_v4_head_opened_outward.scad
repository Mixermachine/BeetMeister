// OpenSCAD 2021.01 compatible
// Based on v4, with only the screw-head recess opened further outward.
// Bore placement and bore angle are unchanged.

$fn = 96;

// -----------------------------
// User parameters
// -----------------------------
pump_diameter     = 24.5;   // mm
pump_count        = 8;
center_spacing    = 28;     // mm
plate_thickness   = 8;      // mm
plate_margin_x    = 4;      // mm from outermost hole to plate edge

// Mounting holes on the solid (non-slot) side
mount_hole_d      = 2.3;    // mm
mount_hole_edge_x = 5;     // mm in from plate ends
mount_hole_solid_margin = 5.0; // mm from solid outer edge to mounting-hole center

// Choose ONE of these:
// 1) automatic evenly-spaced holes:
mount_hole_count  = 9;      // set to 0 to disable automatic pattern
// 2) or manual X positions in mm from the left edge:
mount_hole_positions = [];  // example: [10, 45, 80, 115, 150, 185]

// Edge margins in Y
slot_side_margin  = 1.5;    // mm material on the slotted side (smaller)
solid_side_margin = 8;    // mm material on the opposite side (kept thick)

// Through-slot geometry
slot_width        = 1.5;    // mm
slot_angle        = -42;    // degrees, down-right from the hole toward the slot edge
slot_length       = 18;     // mm
slot_overlap      = 0.8;    // mm extra overlap into the circle so the slot fully opens into the hole

// Clamp screw geometry
enable_clamp_screws     = true;

// Same defaults as v4
clamp_screw_pilot_d     = 1.8;   // mm pilot into the solid body
clamp_screw_clear_d     = 2.2;   // mm clearance through the arm
clamp_screw_head_d      = 3.8;   // mm counterbore diameter at arm entry side
clamp_screw_head_depth  = 1.2;   // mm counterbore depth INSIDE the part
clamp_screw_length      = 15;    // mm total bore length from arm into body
clamp_screw_clear_len   = 5.0;   // mm clearance length from arm side before pilot starts
clamp_screw_face_inset  = 5.6;   // mm inward from the slot boundary on the arm face

// New: shift only the head recess outward along the same axis,
// so it breaks open at the face without changing the main bore placement.
clamp_screw_head_outward = 3.4;  // mm

// Keep the v4 bore angle exactly
clamp_screw_angle       = slot_angle + 90; // orthogonal to slot, pointing into body

// -----------------------------
// Derived dimensions
// -----------------------------
r = pump_diameter / 2;
plate_width = slot_side_margin + pump_diameter + solid_side_margin;
hole_center_y = slot_side_margin + r;
mount_hole_y = plate_width - mount_hole_solid_margin;

plate_length = (pump_count - 1) * center_spacing + 2 * (pump_diameter / 2 + plate_margin_x);
first_center_x = plate_margin_x + pump_diameter / 2;

// -----------------------------
// Helpers
// -----------------------------
module base_plate(pl, pw, pt) {
    cube([pl, pw, pt]);
}

module pump_hole(pd, pt) {
    translate([0, 0, -1])
        cylinder(h = pt + 2, d = pd);
}

module through_slot(pd, pt, ang, wid, len, overlap) {
    contact_x = (pd / 2 - overlap) * cos(ang);
    contact_y = (pd / 2 - overlap) * sin(ang);

    translate([contact_x, contact_y, -1])
        rotate([0, 0, ang])
            translate([0, -wid / 2, 0])
                cube([len, wid, pt + 2]);
}

module bore_from_point(start_x, start_y, bore_angle, bore_len, bore_d) {
    translate([start_x, start_y, plate_thickness / 2])
        rotate([0, 90, bore_angle])
            cylinder(h = bore_len, d = bore_d);
}

module clamp_screw_from_arm_face(pd, slot_ang, wid, overlap,
                                 face_inset, total_len, clear_len,
                                 pilot_d, clear_d, head_d, head_depth,
                                 head_outward) {
    edge_y = -hole_center_y;

    cx = (pd / 2 - overlap) * cos(slot_ang);
    cy = (pd / 2 - overlap) * sin(slot_ang);

    nx = -sin(slot_ang);
    ny =  cos(slot_ang);

    s1 = (edge_y - (cy + (wid/2) * ny)) / sin(slot_ang);
    x1 = cx + s1 * cos(slot_ang) + (wid/2) * nx;

    s2 = (edge_y - (cy - (wid/2) * ny)) / sin(slot_ang);
    x2 = cx + s2 * cos(slot_ang) - (wid/2) * nx;

    arm_boundary_x = min(x1, x2);

    entry_x = arm_boundary_x - face_inset;
    entry_y = edge_y;

    // Move ONLY the head recess outward along the same axis.
    head_start_x = entry_x - head_outward * cos(clamp_screw_angle);
    head_start_y = entry_y - head_outward * sin(clamp_screw_angle);

    union() {
        // Head recess opened further outward, but ending at the same place internally
        bore_from_point(head_start_x, head_start_y, clamp_screw_angle, head_depth + head_outward, head_d);

        // Clearance through the arm (unchanged)
        bore_from_point(entry_x, entry_y, clamp_screw_angle, clear_len, clear_d);

        // Pilot into the solid body (unchanged)
        pilot_start_x = entry_x + clear_len * cos(clamp_screw_angle);
        pilot_start_y = entry_y + clear_len * sin(clamp_screw_angle);
        bore_from_point(pilot_start_x, pilot_start_y, clamp_screw_angle, total_len - clear_len, pilot_d);
    }
}

module mounting_hole_at(xpos) {
    translate([xpos, mount_hole_y, -1])
        cylinder(h = plate_thickness + 2, d = mount_hole_d);
}

module automatic_mount_holes() {
    if (mount_hole_count == 1) {
        mounting_hole_at(plate_length / 2);
    } else if (mount_hole_count > 1) {
        for (i = [0 : mount_hole_count - 1]) {
            xpos = mount_hole_edge_x + i * ((plate_length - 2 * mount_hole_edge_x) / (mount_hole_count - 1));
            mounting_hole_at(xpos);
        }
    }
}

module manual_mount_holes() {
    for (xpos = mount_hole_positions)
        mounting_hole_at(xpos);
}

module pump_mount_array() {
    difference() {
        base_plate(plate_length, plate_width, plate_thickness);

        for (i = [0 : pump_count - 1]) {
            x = first_center_x + i * center_spacing;

            translate([x, hole_center_y, 0]) {
                pump_hole(pump_diameter, plate_thickness);
                through_slot(pump_diameter, plate_thickness, slot_angle, slot_width, slot_length, slot_overlap);

                if (enable_clamp_screws)
                    clamp_screw_from_arm_face(
                        pump_diameter,
                        slot_angle,
                        slot_width,
                        slot_overlap,
                        clamp_screw_face_inset,
                        clamp_screw_length,
                        clamp_screw_clear_len,
                        clamp_screw_pilot_d,
                        clamp_screw_clear_d,
                        clamp_screw_head_d,
                        clamp_screw_head_depth,
                        clamp_screw_head_outward
                    );
            }
        }

        if (len(mount_hole_positions) > 0)
            manual_mount_holes();
        else
            automatic_mount_holes();
    }
}

pump_mount_array();
