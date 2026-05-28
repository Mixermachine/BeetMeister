// OpenSCAD 2021.01 compatible
// Single-piece sleeve case for one 33140 LiFePO4 battery
//
// Design intent:
// - one-piece case, closed at +X end
// - open insertion mouth at -X end
// - battery slides in lengthwise
// - already-soldered wire runs parallel to the battery
// - cable channel is directly beside the battery cavity and slightly overlaps it,
//   forming a connected side groove so the wire can slide in with the cell.
// - cable channel is rotated toward a corner of the battery cavity, so it uses
//   existing corner material instead of forcing the sleeve wider on one side.
//
// Configured cell size: 33 mm diameter x 152 mm length.

$fn = 72;

// -----------------------------
// Battery and cable parameters
// -----------------------------
battery_diameter       = 33.0;
battery_length         = 152.0;
diameter_clearance     = 0.8;
length_clearance       = 3.0;

cable_channel_d        = 6;      // adjust to your insulated wire diameter
cable_channel_overlap  = 1.9;    // overlap with battery cavity, makes connected groove

// Cable channel angular placement around the battery cavity cross-section.
// 0   = old position on the +Y side, which made the case wider.
// +45 = upper +Y/+Z corner, using existing corner material.
// -45 = lower +Y/-Z corner, using existing corner material.
// +90 / -90 are possible true vertical positions, but they usually require
// extra height instead of saving material.
cable_channel_angle_deg = 45;

// -----------------------------
// Case parameters
// -----------------------------
wall                   = 3.0;
closed_end_wall        = 4.0;
corner_radius          = 4.0;

mouth_relief_len       = 7.0;
mouth_extra_d          = 1.2;

// Output selection:
// "sleeve"   = printable battery sleeve only
// "cap"      = printable open-end cap only
// "both"     = sleeve and cap separated for visual checking / combined export
// "assembly" = cap shown in its assembled position on the sleeve
output_part            = "cap";

// Sleeve view modes used when output_part is "sleeve", "both", or "assembly":
// "print"         = printable sleeve only
// "cutaway"       = cutaway preview with optional reference cell/cable
// "solid_preview" = whole sleeve with optional references
view_mode              = "print";
show_reference_cell    = false;
show_reference_cable   = false;

// -----------------------------
// Open-end cap parameters
// -----------------------------
// The cap is an external cup/collar that slides over the open end.
// It does not need a long plug inside the battery cavity, so it does not
// consume the 3 mm length clearance reserved for the cell.
cap_front_thickness    = 3.0;
cap_collar_len         = 15.0;
cap_wall               = 2.4;
cap_fit_clearance      = 0.35;   // radial/YZ clearance around outside of sleeve

// Small internal battery stop. Keep this below length_clearance.
// With length_clearance = 3.0, 2.0 leaves about 1 mm free.
cap_battery_stop_len   = 2.0;
cap_battery_stop_d_clearance = 0.45;

// Cable exit through the cap. The side slot lets you install the cap around
// an already-soldered cable without threading the complete cable through a hole.
cap_cable_clearance    = 0.45;
cap_open_side_slot     = true;

// -----------------------------
// Derived geometry
// -----------------------------
cavity_d               = battery_diameter + diameter_clearance;
cell_r                 = cavity_d / 2;
cable_r                = cable_channel_d / 2;

case_len               = battery_length + length_clearance + closed_end_wall;
open_x                 = -case_len / 2;
closed_x               =  case_len / 2;
cavity_x0              = open_x - 1.0;                 // cuts through open mouth
cavity_x1              = closed_x - closed_end_wall;   // leaves closed end wall

cell_center_y          = 0;
cell_center_z          = wall + cell_r;

// Directly adjacent groove, rotated around the battery cavity. The overlap means
// the battery cavity and cable channel are one connected interior shape instead
// of two separated holes. OpenSCAD trigonometric functions use degrees.
cable_center_offset    = cell_r + cable_r - cable_channel_overlap;
cable_center_y         = cell_center_y + cable_center_offset * cos(cable_channel_angle_deg);
cable_center_z         = cell_center_z + cable_center_offset * sin(cable_channel_angle_deg);

// Outer sleeve bounds are now calculated from both the battery cavity and cable
// channel. At +/-45 degrees this keeps the old battery-only envelope and avoids
// the former one-sided +Y material extension.
case_y_min             = min(cell_center_y - cell_r, cable_center_y - cable_r) - wall;
case_y_max             = max(cell_center_y + cell_r, cable_center_y + cable_r) + wall;
case_z_min             = min(cell_center_z - cell_r - wall, cable_center_z - cable_r - wall);
case_z_max             = max(cell_center_z + cell_r + wall, cable_center_z + cable_r + wall);

label_z                = case_z_max + 0.02;
label_engrave_depth    = 0.55;

// -----------------------------
// Helpers
// -----------------------------
module x_cylinder_between(x0, x1, y, z, d) {
    translate([x0, y, z])
        rotate([0, 90, 0])
            cylinder(h = x1 - x0, d = d);
}

module rounded_box_bounds(xmin, xmax, ymin, ymax, zmin, zmax, r) {
    h = zmax - zmin;
    translate([0, 0, zmin])
        hull() {
            for (x = [xmin + r, xmax - r])
                for (y = [ymin + r, ymax - r])
                    translate([x, y, 0])
                        cylinder(h = h, r = r);
        }
}

module outer_sleeve() {
    rounded_box_bounds(
        open_x, closed_x,
        case_y_min, case_y_max,
        case_z_min, case_z_max,
        corner_radius
    );
}

module internal_voids() {
    // Main battery cavity: open at -X, blind at +X.
    x_cylinder_between(cavity_x0, cavity_x1, cell_center_y, cell_center_z, cavity_d);

    // Parallel cable channel directly next to the battery cavity.
    // Because it overlaps the battery cavity, it creates a side groove for the wire.
    x_cylinder_between(cavity_x0, cavity_x1, cable_center_y, cable_center_z, cable_channel_d);

    // Open-mouth relief so cell + soldered wire start more easily.
    x_cylinder_between(open_x - 1.2, open_x + mouth_relief_len,
                       cell_center_y, cell_center_z, cavity_d + mouth_extra_d);

    x_cylinder_between(open_x - 1.2, open_x + mouth_relief_len,
                       cable_center_y, cable_center_z, cable_channel_d + mouth_extra_d);
}

module polarity_mark_cutouts() {
    // Recessed marks cut inward into the top surface.
    // This avoids raised outward-facing text that can be damaged or interfere
    // when the sleeve is printed standing on the closed end.
    translate([open_x + 13, (case_y_min + case_y_max) / 2, case_z_max - label_engrave_depth + 0.01])
        linear_extrude(height = label_engrave_depth + 0.04)
            text("-", size = 4.8, halign = "center", valign = "center");

    translate([closed_x - 12, (case_y_min + case_y_max) / 2, case_z_max - label_engrave_depth + 0.01])
        linear_extrude(height = label_engrave_depth + 0.04)
            text("+", size = 4.8, halign = "center", valign = "center");
}

module sleeve_case() {
    difference() {
        outer_sleeve();
        internal_voids();
        polarity_mark_cutouts();
    }
}


// -----------------------------
// Open-end cap
// -----------------------------
module cap_shell_only() {
    // External cup/collar around the outside of the sleeve.
    // Front plate is on the open end; rear collar slides over the sleeve body.
    difference() {
        rounded_box_bounds(
            open_x - cap_front_thickness,
            open_x + cap_collar_len,
            case_y_min - cap_wall,
            case_y_max + cap_wall,
            case_z_min - cap_wall,
            case_z_max + cap_wall,
            corner_radius + cap_wall
        );

        // Clearance pocket for the sleeve exterior. This starts at the sleeve
        // mouth, preserving the front plate while clearing the collar volume.
        rounded_box_bounds(
            open_x - 0.01,
            open_x + cap_collar_len + 0.50,
            case_y_min - cap_fit_clearance,
            case_y_max + cap_fit_clearance,
            case_z_min - cap_fit_clearance,
            case_z_max + cap_fit_clearance,
            corner_radius + cap_fit_clearance
        );
    }
}

module cap_battery_stop() {
    // Shallow stop/centering boss. It projects into the battery cavity only a
    // little, so the existing length clearance is not consumed aggressively.
    if (cap_battery_stop_len > 0)
        x_cylinder_between(
            open_x - cap_front_thickness + 0.05,
            open_x + cap_battery_stop_len,
            cell_center_y,
            cell_center_z,
            cavity_d - 2 * cap_battery_stop_d_clearance
        );
}

module cap_cable_cutout() {
    cut_x0 = open_x - cap_front_thickness - 0.40;
    cut_x1 = open_x + cap_collar_len + 0.60;
    cable_exit_d = cable_channel_d + 2 * cap_cable_clearance;

    // Round through-passage aligned with the sleeve's cable channel.
    x_cylinder_between(
        cut_x0,
        cut_x1,
        cable_center_y,
        cable_center_z,
        cable_exit_d
    );

    // Optional side slot from the cable passage to the +Y edge of the cap.
    // This is useful when the cable is already soldered and cannot be threaded.
    if (cap_open_side_slot)
        translate([
            cut_x0,
            cable_center_y,
            cable_center_z - cable_exit_d / 2
        ])
            cube([
                cut_x1 - cut_x0,
                (case_y_max + cap_wall + 2.0) - cable_center_y,
                cable_exit_d
            ]);
}

module open_end_cap_assembled() {
    difference() {
        union() {
            cap_shell_only();
            cap_battery_stop();
        }
        cap_cable_cutout();
    }
}

module open_end_cap_print() {
    // Move standalone cap to positive X/Z for convenient export/printing.
    translate([
        -(open_x - cap_front_thickness),
        0,
        -(case_z_min - cap_wall)
    ])
        open_end_cap_assembled();
}

module reference_cell_and_wire() {
    if (show_reference_cell) {
        color([0.15, 0.15, 0.15, 0.35])
            x_cylinder_between(open_x + 2, open_x + 2 + battery_length,
                               cell_center_y, cell_center_z, battery_diameter);
    }

    if (show_reference_cable) {
        color([0.05, 0.1, 1.0, 0.55])
            x_cylinder_between(open_x + 1, cavity_x1 - 1,
                               cable_center_y, cable_center_z, cable_channel_d * 0.65);
    }
}

module solid_preview() {
    sleeve_case();
    reference_cell_and_wire();
}

module cutaway_view() {
    difference() {
        union() {
            sleeve_case();
            reference_cell_and_wire();
        }

        // Remove a front/top quadrant to expose the battery cavity and corner cable groove.
        translate([open_x - 5, case_y_min - 20, (case_z_min + case_z_max) / 2])
            cube([case_len + 10, (case_y_max - case_y_min) + 40,
                  (case_z_max - case_z_min) / 2 + 20]);

        translate([open_x - 5, case_y_min - 20, case_z_min - 2])
            cube([case_len + 10, (case_y_max - case_y_min) / 2 + 20,
                  (case_z_max - case_z_min) + 4]);
    }
}

// -----------------------------
// Output
// -----------------------------
module selected_sleeve_view() {
    if (view_mode == "cutaway")
        cutaway_view();
    else if (view_mode == "solid_preview")
        solid_preview();
    else
        sleeve_case();
}

if (output_part == "cap") {
    open_end_cap_print();
} else if (output_part == "both") {
    selected_sleeve_view();
    translate([0, (case_y_max - case_y_min) + 2 * cap_wall + 12, 0])
        open_end_cap_print();
} else if (output_part == "assembly") {
    selected_sleeve_view();
    open_end_cap_assembled();
} else {
    selected_sleeve_view();
}
