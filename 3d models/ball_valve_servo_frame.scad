// OpenSCAD 2021.01 compatible
// DS3218 direct-drive adapter frame for small garden ball valve - v9 orange-saddle ledge removal
//
// Purpose:
// - holds a DS3218 / 25 kg class servo upside down above a ball valve
// - aligns the servo shaft coaxially with the valve's square stem
// - grips the 30.5 mm valve body with an open-bottom cradle
// - has a side-entry slot so the raised orange valve actuator can slide into the frame during assembly
// - uses the servo mounting tabs, with printed pilot holes for screws to bite
// - v4: moves the vertical support posts onto solid cradle landings so the top frame is connected
// - v5: shortens the valve clamp to the measured 33 mm body length,
//       and cuts a true 40 mm servo-body opening through the top frame
// - v6: restores the side-entry slot length so it does not overcut past the center,
//       removes the residual central blocking material by lowering the orange round clearance,
//       and adds round screw bosses so the servo tab pilot holes remain fully surrounded
// - v7: adds a small relief groove between each pair of servo tab holes for the
//       1.4 x 3 x 7 mm underside rib/nodge on the DS3218 mounting tabs
// - v8: moves servo tab pilot holes 2 mm outward, shifts servo alignment +1 mm in Y,
//       tightens the valve-body clamp by 0.5 mm, lowers the servo holder by 4 mm,
//       removes the remaining orange actuator ledge, and makes the upper holder
//       side-print surfaces flush with the 33 mm valve-clamp width
// - v9: removes the saddle/ledge where the orange actuator clearance merges
//       into the horizontal valve-body opening, without lengthening the side slot
//
// Coordinate convention:
// - X = valve hose / pipe axis
// - Y = servo length / mounting-tab axis
// - Z = vertical, ground/table at Z = 0
// - valve stem center and servo shaft center are at X=0, Y=0
//
// Print/use note:
// - This frame is intentionally parametric. Dry-fit without the servo powered.
// - Servo travel should be limited in software because the original 90 degree
//   mechanical stop is in the removed valve knob, not in this adapter.

$fn = 72;

// -----------------------------
// Output / preview
// -----------------------------
show_references = false;  // true shows translucent valve/servo reference volumes in OpenSCAD preview

// -----------------------------
// Valve measurements from user
// -----------------------------
valve_body_d                 = 30.5;
valve_body_len               = 33.0;   // measured total thick valve body length along X; clamp must not overstep this
valve_body_fit_clearance     = 0.05;   // v8: 0.5 mm tighter clamp diameter than v7 (was 0.55)

orange_lip_top_from_ground   = 31.5;
orange_recess_d              = 9.5;
orange_recess_depth          = 4.0;
orange_lip_h                 = 1.3;

// Updated orange actuator geometry from user/photos.
// The orange top is not just the small 9.5 mm recess: the raised orange part is wider.
orange_part_d                = 22.9;
orange_side_rise_above_gray  = 7.3;    // highest raised orange side areas above gray housing
orange_center_rise_above_gray= 1.3;    // middle orange area above gray housing
orange_part_radial_clearance = 0.8;
orange_part_z_clearance      = 0.8;
gray_housing_top_from_ground = orange_lip_top_from_ground - orange_center_rise_above_gray;
orange_part_highest_z        = gray_housing_top_from_ground + orange_side_rise_above_gray;
orange_part_clearance_d      = orange_part_d + 2 * orange_part_radial_clearance;

// Assembly slot for the raised orange actuator and square stem.
// The earlier round vertical clearance was not enough because the valve is slid
// into the frame from one side; the raised orange part needs a path from the
// outside edge to the center.
// "right" = +X hose side, "left" = -X hose side, "both" = through-slot.
// "front"/-Y and "back"/+Y are also available if you want side insertion
// perpendicular to the hose axis.
orange_entry_slot_enabled    = true;
orange_entry_slot_side       = "right";
orange_entry_slot_w          = orange_part_clearance_d;
orange_entry_slot_extra      = 1.5;
orange_entry_slot_center_overlap = 0.0; // v6: keep slot ending at center; do not weaken structure by extending past center
orange_entry_slot_z_clearance= 0.6;

valve_stem_top_from_ground   = 30.4;   // estimated by user: 31.5 - 1.1
valve_stem_w                 = 4.7;
valve_stem_d                 = 4.7;
valve_stem_h                 = 3.7;

// Clearance hole in the cradle around the valve stem / coupler nose.
// The printed coupler nose should be under this diameter.
stem_access_d                = 16.0;

// v9 saddle/ledge relief:
// The raised orange part slides in from the side, but the round orange clearance
// must also merge cleanly into the horizontal valve-body bore. If the vertical
// orange clearance starts too high, a small crescent ledge remains at the
// intersection of those two cuts. This value is derived from the cylinder
// intersection height and lowers ONLY the central round clearance, not the
// side-entry slot length.
orange_saddle_relief_enabled = true;
orange_saddle_relief_extra_z = 0.7;

// -----------------------------
// Servo measurements from user
// -----------------------------
servo_body_l                 = 40.0;   // body length along Y, without mounting tabs
servo_body_d                 = 20.5;   // body depth along X
servo_body_h                 = 41.2;
servo_total_l_tabs           = 54.5;
// Updated tab geometry: there are two screw positions at each end of the servo.
servo_tab_hole_from_outer_end= 3.2;    // v8: holes moved 2 mm outward from servo body; was 5.2
servo_tab_pair_hole_cc_x     = 10.0;   // two holes per tab end, center-to-center across X
servo_tab_hole_cc_reference  = 48.0;   // previous measured center distance, kept as reference only
servo_tab_clearance_hole_d   = 4.0;    // hole already present in the servo tab
servo_tab_thickness          = 2.3;

// Servo shaft offset in the 40 mm body length direction.
// Shaft is 11 mm from one body end and 29 mm from the other.
servo_shaft_from_near_edge   = 11.0;
servo_shaft_from_far_edge    = 29.0;

// If the cable/shaft side should face the other Y direction, set this to -1.
servo_y_sign                 = 1;
servo_alignment_shift_y       = 1.0;    // v8: moves complete servo mount toward +Y / top direction by 1 mm

// Printed holes in this adapter. These are pilots so the screws bite into plastic.
// The servo tab's 4 mm holes remain clearance holes in the servo itself.
servo_mount_pilot_d          = 1.9;
servo_mount_pilot_depth      = 9.0;

// -----------------------------
// Horn / coupler vertical stack
// -----------------------------
// Coupler dimensions must match the printed horn coupler version in use.
// Increased because the narrow drive nose must rise above the 22.9 mm orange raised part
// before the wider horn flange starts.
coupler_total_h              = 14.5;
coupler_bottom_clearance     = 0.30;   // bottom of narrow coupler nose above orange recess floor
vertical_fit_clearance       = 0.50;   // extra assembly clearance
servo_holder_z_lowering       = 4.0;    // v8: shorten support posts / lower servo holder by 4 mm

// User measurement: from bottom of servo mounting tab to the horn/shaft stack.
// This is the key tunable value if the horn/coupler height needs adjustment.
servo_tab_bottom_to_horn_bottom = 21.0;

// -----------------------------
// Frame dimensions
// -----------------------------
cradle_wall                  = 4.0;
cradle_extra_x               = 0.0;    // v5: do not extend beyond the measured 33 mm valve body
cradle_outer_len             = valve_body_len; // hard maximum along X for the lower clamp
cradle_outer_w               = valve_body_d + valve_body_fit_clearance + 2 * cradle_wall;
cradle_outer_h               = max(valve_body_d + cradle_wall + 1.0,
                                     orange_part_highest_z + orange_part_z_clearance + 1.0);

// The servo body is 40 x 20.5 mm. The upper frame must leave a true opening
// for this body; the previous design left only about 30 mm in the long direction.
servo_body_clearance_x       = 1.2;
servo_body_clearance_y       = 0.3;   // v6: still clears 40 mm servo body, but keeps screw holes inside solid tab material
servo_body_opening_x         = servo_body_d + 2 * servo_body_clearance_x;
servo_body_opening_y         = servo_body_l + 2 * servo_body_clearance_y;

servo_mount_pad_x            = cradle_outer_len; // v8: top holder reaches same X-side plane as 33 mm clamp for side printing
servo_mount_pad_y            = 14.0;                // along Y at each tab; body opening cut trims the inner overhang
servo_mount_pad_h            = 7.0;                 // enough bite length for pilot screws
servo_screw_boss_d          = 7.0;                 // v6: round material around each 1.9 mm pilot hole
servo_screw_boss_h          = servo_mount_pad_h;   // bosses are flush with the tab pads

// Small underside rib/nodge relief on the servo mounting tabs.
// The servo has a small protrusion between each pair of tab holes.
// The cut is only a narrow groove in the top of the printed pad, so the two
// screw bosses remain solid and connected.
servo_tab_center_relief_enabled = true;
servo_tab_center_relief_w_x     = 1.8;  // measured 1.4 mm + print/fit clearance
servo_tab_center_relief_l_y     = 7.8;  // measured 7.0 mm + print/fit clearance
servo_tab_center_relief_depth   = 3.3;  // measured 3.0 mm + print/fit clearance

side_rail_w                  = 5.1;    // v8: side rails reach the clamp side plane for stable side printing
side_rail_h                  = 5.0;
servo_side_clearance         = servo_body_clearance_x;
vertical_post_w              = 4.5;

// v4 connection fix:
// The servo tab screw pads sit farther apart in Y than the valve cradle.
// In v3 the vertical posts were placed directly below those tab positions;
// the rear/right posts could therefore hang outside the clamp or above the
// orange side-entry cutout.
// These post landings move the posts inward to positions that are inside the
// valve cradle footprint and outside the orange entry slot, while the side
// rails still carry the load out to the screw pads.
post_landing_margin_y        = 1.2;
post_landing_slot_margin_y   = 1.2;
post_landing_y_abs           = min(
                                     cradle_outer_w / 2 - vertical_post_w / 2 - post_landing_margin_y,
                                     orange_entry_slot_w / 2 + vertical_post_w / 2 + post_landing_slot_margin_y
                                   );

// v8 relief retained as an upper-only safety clearance. The real v9 fix is
// lower in the derived orange_round_clearance_z0 below.
orange_upper_ledge_relief_enabled = true;
orange_upper_ledge_relief_extra_d = 1.2;
orange_upper_ledge_relief_z0      = gray_housing_top_from_ground - 0.5;

// -----------------------------
// Derived geometry
// -----------------------------
valve_center_z = valve_body_d / 2;
orange_recess_floor_z = orange_lip_top_from_ground - orange_recess_depth;
valve_body_clearance_d = valve_body_d + valve_body_fit_clearance;
valve_body_clearance_r = valve_body_clearance_d / 2;
orange_part_clearance_r = orange_part_clearance_d / 2;

// Height at which the vertical orange clearance cylinder must start to
// geometrically meet the horizontal valve-body cylinder at the orange diameter.
// This removes the ledge seen where the round valve bore transitions upward.
orange_saddle_merge_z = valve_center_z +
    sqrt(valve_body_clearance_r * valve_body_clearance_r -
         orange_part_clearance_r * orange_part_clearance_r);
orange_round_clearance_z0 = orange_saddle_relief_enabled
    ? (orange_saddle_merge_z - orange_saddle_relief_extra_z)
    : (orange_recess_floor_z - orange_entry_slot_z_clearance);

coupler_bottom_z = orange_recess_floor_z + coupler_bottom_clearance;
horn_bottom_z = coupler_bottom_z + coupler_total_h;
servo_tab_support_top_z = horn_bottom_z + servo_tab_bottom_to_horn_bottom + vertical_fit_clearance - servo_holder_z_lowering;
servo_tab_support_bottom_z = servo_tab_support_top_z - servo_mount_pad_h;

// Servo body placement in Y, relative to shaft at Y=0.
servo_body_y_min = ((servo_y_sign > 0) ? -servo_shaft_from_near_edge : -servo_shaft_from_far_edge) + servo_alignment_shift_y;
servo_body_y_max = ((servo_y_sign > 0) ?  servo_shaft_from_far_edge  :  servo_shaft_from_near_edge) + servo_alignment_shift_y;
servo_body_center_y = (servo_body_y_min + servo_body_y_max) / 2;
servo_body_opening_y_min = servo_body_y_min - servo_body_clearance_y;
servo_body_opening_y_max = servo_body_y_max + servo_body_clearance_y;

servo_tab_y1 = servo_body_center_y - servo_total_l_tabs / 2 + servo_tab_hole_from_outer_end;
servo_tab_y2 = servo_body_center_y + servo_total_l_tabs / 2 - servo_tab_hole_from_outer_end;
servo_tab_x_positions = [-servo_tab_pair_hole_cc_x / 2, servo_tab_pair_hole_cc_x / 2];

rail_x_abs_nominal = servo_body_d / 2 + servo_side_clearance + side_rail_w / 2;
rail_x_abs_max = cradle_outer_len / 2 - side_rail_w / 2; // v8: rail outer face is flush with clamp side face
rail_x_abs = min(rail_x_abs_nominal, rail_x_abs_max);
rail_y_min = servo_tab_y1 - servo_mount_pad_y / 2;
rail_y_max = servo_tab_y2 + servo_mount_pad_y / 2;
rail_len_y = rail_y_max - rail_y_min;

post_y_positions = [-post_landing_y_abs, post_landing_y_abs];

post_z0 = cradle_outer_h - 0.2; // slight overlap into cradle top for CSG union reliability
post_h = servo_tab_support_bottom_z - post_z0 + 0.2;

// -----------------------------
// Helpers
// -----------------------------
module cyl_x(h, d) {
    rotate([0, 90, 0])
        cylinder(h = h, d = d, center = true);
}

module pad(center_x, center_y, z_top, sx, sy, sz) {
    translate([center_x - sx / 2, center_y - sy / 2, z_top - sz])
        cube([sx, sy, sz]);
}

module vertical_pilot_cut(x, y, z_top, depth, d) {
    translate([x, y, z_top - depth])
        cylinder(h = depth + 1.0, d = d);
}

// -----------------------------
// Main frame solids
// -----------------------------
module valve_cradle_solid() {
    translate([-cradle_outer_len / 2, -cradle_outer_w / 2, 0])
        cube([cradle_outer_len, cradle_outer_w, cradle_outer_h]);
}

module orange_entry_slot_cut() {
    // Starts below the orange recess floor so both the square stem and the
    // elevated orange actuator can slide in from the chosen side.
    // v6: the side slot again stops at the center line. The residual material
    // that blocked insertion is removed by lowering the central round orange
    // clearance, not by making the slot longer.
    z0 = orange_recess_floor_z - orange_entry_slot_z_clearance;
    h  = cradle_outer_h - z0 + 2.0;
    w  = orange_entry_slot_w;
    ex = orange_entry_slot_extra;

    if (orange_entry_slot_side == "right") {
        translate([0, -w / 2, z0])
            cube([cradle_outer_len / 2 + ex, w, h]);
    } else if (orange_entry_slot_side == "left") {
        translate([-cradle_outer_len / 2 - ex, -w / 2, z0])
            cube([cradle_outer_len / 2 + ex, w, h]);
    } else if (orange_entry_slot_side == "both") {
        translate([-cradle_outer_len / 2 - ex, -w / 2, z0])
            cube([cradle_outer_len + 2 * ex, w, h]);
    } else if (orange_entry_slot_side == "front") {
        translate([-w / 2, -cradle_outer_w / 2 - ex, z0])
            cube([w, cradle_outer_w / 2 + ex, h]);
    } else if (orange_entry_slot_side == "back") {
        translate([-w / 2, 0, z0])
            cube([w, cradle_outer_w / 2 + ex, h]);
    }
}

module valve_cradle_cuts() {
    // Horizontal valve body clearance, open at the bottom because the valve sits on the table.
    translate([0, 0, valve_center_z])
        cyl_x(cradle_outer_len + 2, valve_body_clearance_d);

    // Lower central clearance for the small orange recess, square stem, and coupler nose.
    translate([0, 0, orange_recess_floor_z - 1.0])
        cylinder(h = cradle_outer_h - orange_recess_floor_z + 3.0, d = stem_access_d);

    // Wide clearance for the raised 22.9 mm orange actuator area visible in the photos.
    // v9: this starts at the mathematically-derived saddle merge height, so the
    // vertical orange clearance and horizontal valve-body bore overlap cleanly.
    // The side-entry slot still stops at the center line; only the internal
    // ledge/saddle at the transition is removed.
    translate([0, 0, orange_round_clearance_z0])
        cylinder(h = cradle_outer_h - orange_round_clearance_z0 + 2.0, d = orange_part_clearance_d);

    // v8: remove the remaining small ledge at the merge from the orange
    // actuator clearance into the upper clamp opening. This is a central
    // round relief only; the side-entry slot length is unchanged.
    if (orange_upper_ledge_relief_enabled)
        translate([0, 0, orange_upper_ledge_relief_z0])
            cylinder(h = cradle_outer_h - orange_upper_ledge_relief_z0 + 2.0,
                     d = orange_part_clearance_d + orange_upper_ledge_relief_extra_d);

    // Side-loading path for assembly. Without this slot, the elevated orange part
    // can not move from the outer edge into the central round clearance.
    if (orange_entry_slot_enabled)
        orange_entry_slot_cut();
}

module valve_cradle() {
    difference() {
        valve_cradle_solid();
        valve_cradle_cuts();
    }
}

module servo_tab_pads_solid() {
    pad(0, servo_tab_y1, servo_tab_support_top_z, servo_mount_pad_x, servo_mount_pad_y, servo_mount_pad_h);
    pad(0, servo_tab_y2, servo_tab_support_top_z, servo_mount_pad_x, servo_mount_pad_y, servo_mount_pad_h);
}

module servo_screw_bosses_solid() {
    // Round material around each screw pilot. These bosses are added before
    // the servo body opening is cut, so they are trimmed only where needed and
    // still keep the 1.9 mm holes fully surrounded by plastic.
    for (yy = [servo_tab_y1, servo_tab_y2]) {
        for (xx = servo_tab_x_positions) {
            translate([xx, yy, servo_tab_support_top_z - servo_screw_boss_h])
                cylinder(h = servo_screw_boss_h, d = servo_screw_boss_d);
        }
    }
}

module side_rails() {
    for (sx = [-1, 1]) {
        translate([
            sx * rail_x_abs - side_rail_w / 2,
            rail_y_min,
            servo_tab_support_bottom_z
        ])
            cube([side_rail_w, rail_len_y, side_rail_h]);
    }
}

module vertical_posts() {
    // Posts are intentionally NOT located directly under the servo screw pads.
    // They are moved inward onto solid cradle material so every post overlaps
    // the valve clamp below and the side rail above. This prevents floating
    // posts after the large orange side-entry assembly slot is cut.
    for (sx = [-1, 1]) {
        for (yy = post_y_positions) {
            translate([
                sx * rail_x_abs - vertical_post_w / 2,
                yy - vertical_post_w / 2,
                post_z0
            ])
                cube([vertical_post_w, vertical_post_w, post_h]);
        }
    }
}

module frame_solid() {
    union() {
        valve_cradle();
        servo_tab_pads_solid();
        servo_screw_bosses_solid();
        side_rails();
        vertical_posts();
    }
}

module servo_body_opening_cut() {
    // Clear through the upper frame for the actual 40 x 20.5 mm servo body.
    // This is deliberately separate from the screw pads: the pads support the
    // mounting tabs, while this cut guarantees the body itself can drop in.
    translate([
        -servo_body_opening_x / 2,
        servo_body_opening_y_min,
        servo_tab_support_bottom_z - 0.5
    ])
        cube([
            servo_body_opening_x,
            servo_body_opening_y_max - servo_body_opening_y_min,
            servo_mount_pad_h + side_rail_h + 3.0
        ]);
}

module servo_tab_center_relief_cuts() {
    // Remove a small rectangular pocket between each left/right screw-hole pair.
    // The pocket starts at the servo-tab support top surface and goes downward,
    // clearing the small stabilizing rib on the underside of the servo tab.
    if (servo_tab_center_relief_enabled) {
        for (yy = [servo_tab_y1, servo_tab_y2]) {
            translate([
                -servo_tab_center_relief_w_x / 2,
                yy - servo_tab_center_relief_l_y / 2,
                servo_tab_support_top_z - servo_tab_center_relief_depth
            ])
                cube([
                    servo_tab_center_relief_w_x,
                    servo_tab_center_relief_l_y,
                    servo_tab_center_relief_depth + 0.6
                ]);
        }
    }
}

module frame_cuts() {
    // 40 mm servo-body opening. Previous top frame left only roughly 30 mm.
    servo_body_opening_cut();

    // Relief for the small servo-tab underside rib between the two tab holes.
    servo_tab_center_relief_cuts();

    // Tight pilot holes for the screws that pass through the servo tabs and bite into the printed pads.
    // There are two screw positions at each servo tab end, four holes total.
    for (yy = [servo_tab_y1, servo_tab_y2]) {
        for (xx = servo_tab_x_positions) {
            vertical_pilot_cut(xx, yy, servo_tab_support_top_z + 0.2, servo_mount_pilot_depth, servo_mount_pilot_d);
        }
    }
}

module adapter_frame() {
    difference() {
        frame_solid();
        frame_cuts();
    }
}

// -----------------------------
// Optional reference geometry for preview only
// -----------------------------
module valve_reference() {
    %translate([0, 0, valve_center_z])
        cyl_x(valve_body_len, valve_body_d);

    // Small central recess previously measured.
    %translate([0, 0, orange_recess_floor_z])
        cylinder(h = orange_recess_depth, d = orange_recess_d);

    // Conservative reference for the raised orange actuator area. It is shown at the
    // maximum side height so any possible collision is visible in preview.
    %translate([0, 0, gray_housing_top_from_ground])
        cylinder(h = orange_side_rise_above_gray, d = orange_part_d);

    %translate([-valve_stem_w / 2, -valve_stem_d / 2, valve_stem_top_from_ground - valve_stem_h])
        cube([valve_stem_w, valve_stem_d, valve_stem_h]);
}

module servo_reference() {
    // Body reference only, not printed.
    %translate([
        -servo_body_d / 2,
        servo_body_y_min,
        servo_tab_support_top_z
    ])
        cube([servo_body_d, servo_body_l, servo_body_h]);

    // Horn/coupler vertical reference.
    %translate([0, 0, horn_bottom_z])
        cylinder(h = 2.0, d = 20.6);
}

// -----------------------------
// Output
// -----------------------------
adapter_frame();

if (show_references) {
    valve_reference();
    servo_reference();
}


echo(str("v9 orange_saddle_merge_z=", orange_saddle_merge_z));
echo(str("v9 orange_round_clearance_z0=", orange_round_clearance_z0));
echo(str("v9 slot_side=", orange_entry_slot_side, ", center_overlap=", orange_entry_slot_center_overlap));
