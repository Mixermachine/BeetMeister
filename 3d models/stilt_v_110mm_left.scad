// OpenSCAD 2021.01 compatible
// Separate V-shaped stilt for carrier lugs.
//
// Revision:
// - Rectangular V legs.
// - Small top and foot stamps are restored, but each stamp is no larger than the leg cross-section.
// - Beam endpoints are placed inside the stamps, so the geometry has intentional overlap and no gaps.
// - Top screw areas remain flat.
// - No wide foot plate.
// - Added centered bottom screw hole from underside into the foot stamp.
// - Screw holes changed from clearance holes to tight pilot holes so 2 mm screws can bite into the printed plastic.

$fn = 72;

// -----------------------------
// Main requested values
// -----------------------------
top_hole_spacing      = 110.0;  // distance between the two lug screw points, center to center
stilt_height          = 100.0;  // ground to top mounting surface height
// Existing carrier lug holes are clearance holes. This stilt needs to catch the screws,
// so the holes in this part are pilot holes, matching the standoff style in the other files.
screw_clearance_ref_d  = 2.3;    // reference only: clearance through carrier lugs for 2 mm screws
screw_pilot_d          = 1.9;    // tight pilot for 2 mm screws to bite into printed plastic

// -----------------------------
// Rectangular stilt geometry
// -----------------------------
leg_xz_thickness      = 7.0;    // rectangular leg thickness in the X/Z side-view profile
leg_y_width           = 8.0;    // front/back width of the stilt

// The top and bottom stamps are intentionally not larger than the leg.
// They provide flat, gap-free endpoints without creating big print-unfriendly blocks.
stamp_x               = leg_xz_thickness;
stamp_y               = leg_y_width;
stamp_z               = leg_xz_thickness;

// Optional screw-head relief from the top side.
// Keep disabled when you want a completely flat screw-contact surface.
head_relief_enabled   = false;
head_relief_d         = 4.2;
head_relief_depth     = 1.4;

// Optional light rectangular crossbar near the top. Keep false for the clean V shape.
top_crossbar_enabled  = false;
top_crossbar_xz       = 5.0;
top_crossbar_y        = 6.0;

// Optional bottom screw hole for later attaching another part to the lower V point.
// This is centered in the foot stamp and also uses a tight pilot hole so a screw can bite later.
bottom_hole_enabled   = true;
bottom_hole_d         = screw_pilot_d;
bottom_hole_depth     = stamp_z + 1.0;  // cuts through the foot stamp with slight cleanup margin

// -----------------------------
// Derived values
// -----------------------------
left_x                = -top_hole_spacing / 2;
right_x               =  top_hole_spacing / 2;
foot_x                = 0;

// Top stamps sit directly below the lug, with the flat top surface at stilt_height.
top_stamp_bottom_z    = stilt_height - stamp_z;
top_stamp_center_z    = top_stamp_bottom_z + stamp_z / 2;

// Foot stamp sits on the ground. It is only the size of the leg section, not a plate.
foot_stamp_bottom_z   = 0;
foot_stamp_center_z   = stamp_z / 2;

// Put the beam centerline endpoints inside the stamps.
// This creates a true OpenSCAD union with positive overlap, preventing tiny gaps.
top_leg_anchor_z      = top_stamp_center_z;
bottom_leg_anchor_z   = foot_stamp_center_z;

// -----------------------------
// Primitive helpers
// -----------------------------
module box_centered(size_vec) {
    cube(size_vec, center = true);
}

module box_from_bottom(center_x, center_y, bottom_z, size_vec) {
    translate([center_x, center_y, bottom_z + size_vec[2] / 2])
        box_centered(size_vec);
}

function beam_poly_2d(p1, p2, t) =
    let(
        dx = p2[0] - p1[0],
        dz = p2[1] - p1[1],
        l  = sqrt(dx * dx + dz * dz),
        nx = -dz / l * t / 2,
        nz =  dx / l * t / 2
    )
    [
        [p1[0] + nx, p1[1] + nz],
        [p2[0] + nx, p2[1] + nz],
        [p2[0] - nx, p2[1] - nz],
        [p1[0] - nx, p1[1] - nz]
    ];

module xz_profile_extruded_y(profile_points, y_width) {
    // Builds an X/Z side-view polygon and extrudes it across Y.
    // rotate([90,0,0]) maps 2D Y to model Z and extrusion Z to model Y.
    rotate([90, 0, 0])
        linear_extrude(height = y_width, center = true, convexity = 4)
            polygon(points = profile_points);
}

// -----------------------------
// Main geometry modules
// -----------------------------
module end_stamp(xc, bottom_z) {
    // Same X/Y/Z envelope as the rectangular leg section.
    // This is the small, printable, fully-connected endpoint requested.
    box_from_bottom(xc, 0, bottom_z, [stamp_x, stamp_y, stamp_z]);
}

module top_mount_hole(xc) {
    translate([xc, 0, top_stamp_bottom_z - 1])
        cylinder(h = stamp_z + 2, d = screw_pilot_d);

    if (head_relief_enabled) {
        translate([xc, 0, stilt_height - head_relief_depth])
            cylinder(h = head_relief_depth + 0.02, d = head_relief_d);
    }
}

module bottom_mount_hole() {
    if (bottom_hole_enabled) {
        // Hole enters from the bottom side and is vertical in Z.
        // It is intentionally a pilot hole, not a clearance hole, so the screw can bite.
        translate([foot_x, 0, -0.5])
            cylinder(h = bottom_hole_depth + 0.5, d = bottom_hole_d);
    }
}

module rectangular_leg(x_top) {
    xz_profile_extruded_y(
        beam_poly_2d(
            [x_top, top_leg_anchor_z],
            [foot_x, bottom_leg_anchor_z],
            leg_xz_thickness
        ),
        leg_y_width
    );
}

module v_legs() {
    rectangular_leg(left_x);
    rectangular_leg(right_x);

    if (top_crossbar_enabled)
        xz_profile_extruded_y(
            beam_poly_2d(
                [left_x, top_leg_anchor_z],
                [right_x, top_leg_anchor_z],
                top_crossbar_xz
            ),
            top_crossbar_y
        );
}

module v_stilt_body_raw() {
    union() {
        // Stamps first, then legs. Endpoints overlap inside the stamps.
        end_stamp(left_x,  top_stamp_bottom_z);
        end_stamp(right_x, top_stamp_bottom_z);
        end_stamp(foot_x,  foot_stamp_bottom_z);
        v_legs();
    }
}

module screw_cutouts() {
    top_mount_hole(left_x);
    top_mount_hole(right_x);
    bottom_mount_hole();
}

module v_stilt() {
    difference() {
        v_stilt_body_raw();
        screw_cutouts();
    }
}

v_stilt();
