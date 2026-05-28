// OpenSCAD 2021.01 compatible
// Lightweight 13 mm garden-hose to 8x M5 irrigation manifold
//
// Source of truth for v5:
// - values were taken from the user's pasted parameter block
// - second garden hose connector is configurable by boolean
//
// Purpose:
// - accepts a regular 13 mm garden tube on a printed hose barb
// - optionally accepts a second regular 13 mm garden tube on the opposite side
// - distributes water to 8 M5 brass fitting sockets for 4/6 mm irrigation pipe
// - reduces material by replacing the rectangular block with a tube-like manifold
// - models an approximate internal M5x0.8 thread in each outlet socket
// - engraves outlet numbers into the body

$fn = 72;

// -----------------------------
// Main layout
// -----------------------------
outlet_count        = 8;
outlet_spacing      = 15.0;
outlet_x_margin_left  = 12.0;  // keep inlet/first-outlet side unchanged
outlet_x_margin_right = 12.0;  // v3: trimmed closed right end, still enough wall
body_length         = outlet_x_margin_left
                    + (outlet_count - 1) * outlet_spacing
                    + outlet_x_margin_right;

// Lighter tube-like body instead of large rectangular block
manifold_outer_d    = 18.0;   // outer water-gallery body diameter
main_bore_d         = 9.5;    // internal horizontal water gallery
end_wall_thickness  = 8.0;    // closed wall at far end of gallery

// Outlet bosses around M5 fittings
outlet_boss_d       = 11.0;
outlet_boss_h       = 6.0;
outlet_seat_d       = 7.5;
outlet_seat_depth   = 1.0;

// M5 threaded socket geometry
m5_major_d          = 5.05;   // slight clearance over nominal 5.0 mm
m5_tap_drill_d      = 4.90;   // minor/core hole for M5 thread
m5_pitch            = 0.80;
m5_thread_depth     = 5.5;    // threaded depth before meeting the water gallery
m5_socket_depth     = 12.0;   // total vertical drilling depth into gallery
thread_slices_per_turn = 18;

// 13 mm garden hose barb inlet
hose_inlet_len      = 38.0;
hose_barb_base_d    = 13.2;
hose_barb_peak_d    = 14.8;
hose_barb_root_d    = 13.0;
hose_barb_count     = 4;
hose_barb_pitch     = 7.0;
hose_barb_width     = 4.0;
inlet_boss_d        = 19.0;
inlet_boss_len      = 8.0;

// Optional second garden hose barb on the opposite/right side.
// true  = garden tube connector on both sides; main bore passes through both.
// false = one left garden tube connector only; right side stays closed.
enable_second_garden_connector = true;

// Optional engraved numbering
show_numbers        = true;
number_size         = 1.6;
number_engrave_depth = 0.55;
number_y_offset     = 4.62;   // engraved into each outlet boss, not floating

// Optional reference/debug
show_threaded_cutaway = false; // set true to preview one socket cut open

// -----------------------------
// Derived coordinates
// -----------------------------
water_axis_y = 0;
water_axis_z = 0;
body_top_z   = manifold_outer_d / 2;
body_bottom_z = -manifold_outer_d / 2;

function outlet_x(i) = outlet_x_margin_left + i * outlet_spacing;

// -----------------------------
// Basic geometry helpers
// -----------------------------
module x_cylinder(x0, len, d) {
    translate([x0, water_axis_y, water_axis_z])
        rotate([0, 90, 0])
            cylinder(h = len, d = d);
}

module x_frustum(x0, len, d1, d2) {
    translate([x0, water_axis_y, water_axis_z])
        rotate([0, 90, 0])
            cylinder(h = len, d1 = d1, d2 = d2);
}

module hose_barb_outer() {
    // Left/original hose connector.
    // Smooth root cylinder
    x_cylinder(-hose_inlet_len, hose_inlet_len, hose_barb_root_d);

    // Stronger boss where the hose connector enters the manifold body
    x_cylinder(-inlet_boss_len, inlet_boss_len, inlet_boss_d);

    // Printable hose barb ramps
    for (i = [0 : hose_barb_count - 1]) {
        x0 = -hose_inlet_len + 5 + i * hose_barb_pitch;
        x_frustum(x0, hose_barb_width, hose_barb_base_d, hose_barb_peak_d);
        x_cylinder(x0 + hose_barb_width, 1.2, hose_barb_peak_d);
    }
}

module hose_barb_outer_right() {
    // Mirrored copy of the left connector, placed on the opposite/right side.
    // Reuses the exact same hose-barb dimensions above.
    translate([body_length, water_axis_y, water_axis_z])
        mirror([1, 0, 0])
            hose_barb_outer();
}

module outlet_boss(x) {
    // A small vertical boss is enough material for the brass M5 fitting.
    translate([x, water_axis_y, body_top_z - 1.0])
        cylinder(h = outlet_boss_h + 1.0, d = outlet_boss_d);
}

module outlet_seat_cut(x) {
    translate([x, water_axis_y, body_top_z + outlet_boss_h - outlet_seat_depth])
        cylinder(h = outlet_seat_depth + 0.3, d = outlet_seat_d);
}

// Approximate male M5x0.8 thread cutter. Subtracting this from the boss creates
// an internal pre-threaded socket. This is intentionally conservative and may
// still be cleaned with a real M5 tap after printing.
module m5_thread_cutter(depth = m5_thread_depth) {
    turns = depth / m5_pitch;
    slices = ceil(turns * thread_slices_per_turn);
    thread_radial_depth = (m5_major_d - m5_tap_drill_d) / 2;
    tangential_w = m5_pitch * 0.58;

    union() {
        // Core tap-drill channel
        cylinder(h = depth + 0.4, d = m5_tap_drill_d);

        // Helical thread tooth volume
        linear_extrude(height = depth + 0.2,
                       twist = 360 * turns,
                       slices = slices,
                       convexity = 10)
            translate([m5_tap_drill_d / 2, -tangential_w / 2])
                polygon(points = [
                    [0, 0],
                    [thread_radial_depth, tangential_w / 2],
                    [0, tangential_w]
                ]);
    }
}

module outlet_threaded_socket_cut(x) {
    // Full hole continues slightly farther down to meet the main gallery.
    translate([x, water_axis_y, body_top_z + outlet_boss_h - m5_socket_depth])
        cylinder(h = m5_socket_depth + 0.6, d = m5_tap_drill_d);

    // Threaded top section.
    translate([x, water_axis_y, body_top_z + outlet_boss_h - m5_thread_depth])
        m5_thread_cutter(m5_thread_depth + 0.2);

    outlet_seat_cut(x);
}

module number_engrave_cut(i) {
    if (show_numbers) {
        // Engrave into the top edge of each outlet boss so numbers are part of
        // the body and cannot float as separate geometry. The offset keeps the
        // digits mostly outside the M5 fitting seat.
        translate([outlet_x(i),
                   water_axis_y + number_y_offset,
                   body_top_z + outlet_boss_h - number_engrave_depth])
            linear_extrude(height = number_engrave_depth + 0.20)
                text(str(i + 1), size = number_size, halign = "center", valign = "center");
    }
}

// -----------------------------
// Main part
// -----------------------------
module manifold_solid() {
    union() {
        // Main pressure body: much less material than a rectangular block.
        x_cylinder(0, body_length, manifold_outer_d);

        // End caps keep the body rounded/strong.
        translate([0, water_axis_y, water_axis_z])
            sphere(d = manifold_outer_d);
        translate([body_length, water_axis_y, water_axis_z])
            sphere(d = manifold_outer_d);

        hose_barb_outer();

        if (enable_second_garden_connector)
            hose_barb_outer_right();

        for (i = [0 : outlet_count - 1])
            outlet_boss(outlet_x(i));
    }
}

module manifold_cutouts() {
    // Main gallery.
    // With the second connector enabled, the bore opens through both garden hose barbs.
    // With it disabled, the original one-sided closed-right behavior is preserved.
    x_cylinder(-hose_inlet_len - 1,
               enable_second_garden_connector
                   ? body_length + 2 * hose_inlet_len + 2
                   : hose_inlet_len + body_length - end_wall_thickness + 1,
               main_bore_d);

    for (i = [0 : outlet_count - 1])
        outlet_threaded_socket_cut(outlet_x(i));

    for (i = [0 : outlet_count - 1])
        number_engrave_cut(i);

    if (show_threaded_cutaway) {
        // Debug cut through outlet 1 to inspect the thread profile.
        translate([outlet_x(0) - 5.7, -12, body_bottom_z - 2])
            cube([5.7, 24, manifold_outer_d + outlet_boss_h + 6]);
    }
}

module irrigation_distributor_light_threaded() {
    difference() {
        manifold_solid();
        manifold_cutouts();
    }
}

// Default output uses enable_second_garden_connector above.
irrigation_distributor_light_threaded();
