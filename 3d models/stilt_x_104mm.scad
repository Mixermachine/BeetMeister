// OpenSCAD 2021.01 compatible
// Separate X-shaped stilt for the controller module side with only two lugs.
//
// Source for lug spacing: controller_module.scad
// - right_corner_lug_y1 = lug_pad_diameter / 2 = 5.0
// - body_depth = max(80, 80) + lug_spacing + 2*pcb_margin = 117.313
// - right_corner_lug_y2 = body_depth - lug_pad_diameter / 2 = 112.313
// - center-to-center distance = 112.313 - 5.0 = 107.313 mm
//
// Geometry:
// - X-shaped rectangular brace, not V-shaped
// - two top screw-catching pilot holes for the controller module lugs
// - two lower screw-catching pilot holes for later attaching something there
// - top and bottom endpoint stamps are no larger than the rectangular stilt leg itself
// - beam endpoints overlap into the stamps to avoid gaps

$fn = 72;

// -----------------------------
// Main requested values
// -----------------------------
controller_two_lug_spacing = 107.313;  // calculated from controller_module.scad right-side corner lugs
stilt_length               = 104.0;    // requested stilt length / vertical height

// The controller carrier lug holes are clearance holes. This separate stilt must catch screws,
// so use the printed-plastic pilot diameter used by the other standoff features.
screw_clearance_ref_d      = 2.3;      // reference only: clearance through lugs
screw_pilot_d              = 1.9;      // pilot for 2 mm screws to bite into printed plastic

// -----------------------------
// Rectangular X-stilt geometry
// -----------------------------
leg_xz_thickness           = 7.0;      // rectangular leg thickness in X/Z side-view profile
leg_y_width                = 8.0;      // front/back width of the stilt

// Endpoint stamps are intentionally no larger than the leg cross-section.
stamp_x                    = leg_xz_thickness;
stamp_y                    = leg_y_width;
stamp_z                    = leg_xz_thickness;

// Optional screw-head reliefs. Keep disabled for flat screw-contact surfaces.
top_head_relief_enabled    = false;
bottom_head_relief_enabled = false;
head_relief_d              = 4.2;
head_relief_depth          = 1.4;

// Bottom pilot holes can be disabled if you only want the two top holes.
bottom_holes_enabled       = true;

// -----------------------------
// Derived values
// -----------------------------
left_x                     = -controller_two_lug_spacing / 2;
right_x                    =  controller_two_lug_spacing / 2;

bottom_z                   = 0;
top_z                      = stilt_length;

bottom_stamp_bottom_z      = bottom_z;
bottom_stamp_center_z      = bottom_stamp_bottom_z + stamp_z / 2;

top_stamp_bottom_z         = top_z - stamp_z;
top_stamp_center_z         = top_stamp_bottom_z + stamp_z / 2;

// Beam centerlines run into stamp centers, giving positive overlap and no gaps.
// The X consists of two diagonals: left-top to right-bottom, and right-top to left-bottom.

// -----------------------------
// Primitive helpers
// -----------------------------
module box_centered(size_vec) {
    cube(size_vec, center = true);
}

module box_from_bottom(center_x, center_y, bottom_z_pos, size_vec) {
    translate([center_x, center_y, bottom_z_pos + size_vec[2] / 2])
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
// Endpoint stamps and holes
// -----------------------------
module end_stamp(xc, bottom_z_pos) {
    // Same X/Y/Z envelope as the rectangular leg section.
    box_from_bottom(xc, 0, bottom_z_pos, [stamp_x, stamp_y, stamp_z]);
}

module top_pilot_hole(xc) {
    translate([xc, 0, top_stamp_bottom_z - 1])
        cylinder(h = stamp_z + 2, d = screw_pilot_d);

    if (top_head_relief_enabled)
        translate([xc, 0, top_z - head_relief_depth])
            cylinder(h = head_relief_depth + 0.02, d = head_relief_d);
}

module bottom_pilot_hole(xc) {
    if (bottom_holes_enabled) {
        translate([xc, 0, bottom_stamp_bottom_z - 0.5])
            cylinder(h = stamp_z + 1.0, d = screw_pilot_d);

        if (bottom_head_relief_enabled)
            translate([xc, 0, bottom_stamp_bottom_z - 0.01])
                cylinder(h = head_relief_depth + 0.02, d = head_relief_d);
    }
}

// -----------------------------
// X brace geometry
// -----------------------------
module diagonal_beam(p1, p2) {
    xz_profile_extruded_y(
        beam_poly_2d(p1, p2, leg_xz_thickness),
        leg_y_width
    );
}

module x_brace() {
    diagonal_beam([left_x,  top_stamp_center_z],    [right_x, bottom_stamp_center_z]);
    diagonal_beam([right_x, top_stamp_center_z],    [left_x,  bottom_stamp_center_z]);
}

module x_stilt_body_raw() {
    union() {
        // Top stamps: screw-catching connection to the two-lug side of the controller module.
        end_stamp(left_x,  top_stamp_bottom_z);
        end_stamp(right_x, top_stamp_bottom_z);

        // Bottom stamps: same size as the leg, with pilot holes for later attachments.
        end_stamp(left_x,  bottom_stamp_bottom_z);
        end_stamp(right_x, bottom_stamp_bottom_z);

        x_brace();
    }
}

module screw_cutouts() {
    top_pilot_hole(left_x);
    top_pilot_hole(right_x);
    bottom_pilot_hole(left_x);
    bottom_pilot_hole(right_x);
}

module x_stilt() {
    difference() {
        x_stilt_body_raw();
        screw_cutouts();
    }
}

x_stilt();
