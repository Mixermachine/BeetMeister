// OpenSCAD 2021.01 compatible
// Relay-board extension plate for the pump holder
//
// Based directly on the file content you pasted.
// Updated to mirror the rounded lugs onto the other side too.
//
// Changes:
// - keeps your current front lugs and values exactly
// - adds mirrored rear lugs and mirrored rear holes
// - changes plate_depth so there is the same lug inset on the rear side

$fn = 72;

// -----------------------------
// User parameters
// -----------------------------
pcb_width              = 135.5;   // X
pcb_depth              = 52.5;    // Y
pcb_hole_edge_offset   = 3.0;     // from each PCB edge
pcb_hole_d             = 1.9;     // M3-ish clearance

// Clearance for solder joints / underside components
standoff_height        = 4.0;     // gives extra bottom clearance
standoff_diameter      = 7.5;

// Plate / adapter
plate_thickness        = 4.0;
original_hole_spacing  = 27.313;
original_hole_count    = 6;
original_hole_d        = 1.9;

// Existing front mounting-lug geometry from your file
mount_hole_front_margin = 0.0;    // Y distance from front edge to original-hole center
mount_pad_diameter      = 10.0;   // rounded material around each original hole
mount_neck_diameter     = 10.0;   // narrow rounded neck connecting each lug to body
mount_body_start_y      = 22.0;   // where the full relay-board plate body begins

// Mirrored rear lugs
rear_lugs_enabled       = true;
rear_hole_back_margin   = mount_hole_front_margin;
rear_pad_diameter       = mount_pad_diameter;
rear_neck_diameter      = mount_neck_diameter;
rear_body_end_inset     = mount_body_start_y/2;
rear_hole_d             = original_hole_d;

// Around the relay board
pcb_margin             = 5.0;     // solid border around the PCB footprint
rim_height             = 5.0;     // protective seam/rim height
rim_thickness          = 2.0;     // wall thickness of the protective rim

// Optional extra plate margins if you want more overhang
extra_side_margin_x    = 3.0;
extra_outer_margin_y   = 0.0;

// -----------------------------
// Derived dimensions
// -----------------------------
hole_span      = (original_hole_count - 1) * original_hole_spacing;
min_plate_len1 = hole_span + mount_pad_diameter + 5;
min_plate_len2 = pcb_width + 0 * pcb_margin + 2 * extra_side_margin_x;
plate_length   = max(min_plate_len1, min_plate_len2);

pcb_zone_width = pcb_width + 2 * pcb_margin;
pcb_zone_depth = pcb_depth + 2 * pcb_margin;

// CHANGED: same lug inset added on the rear side too
plate_depth    = mount_body_start_y + pcb_zone_depth + extra_outer_margin_y + rear_body_end_inset;

// PCB zone placement
pcb_zone_x0    = (plate_length - pcb_zone_width) / 2;
pcb_zone_y0    = mount_body_start_y;

// Actual PCB footprint placement inside the zone
pcb_x0         = pcb_zone_x0 + pcb_margin;
pcb_y0         = pcb_zone_y0 + pcb_margin;

// Front mounting-hole row
orig_hole_x0   = (plate_length - hole_span) / 2;
orig_hole_y    = mount_hole_front_margin;

// Rear mounting-hole row
rear_hole_y    = plate_depth - rear_hole_back_margin;
rear_body_y    = plate_depth - rear_body_end_inset;

// PCB mounting-hole positions
pcb_hole_x1    = pcb_x0 + pcb_hole_edge_offset;
pcb_hole_x2    = pcb_x0 + pcb_width - pcb_hole_edge_offset;
pcb_hole_y1    = pcb_y0 + pcb_hole_edge_offset;
pcb_hole_y2    = pcb_y0 + pcb_depth - pcb_hole_edge_offset;

// -----------------------------
// Helpers
// -----------------------------
module rounded_lug_front(xc) {
    hull() {
        translate([xc, orig_hole_y, 0])
            cylinder(h = plate_thickness, d = mount_pad_diameter);

        translate([xc, mount_body_start_y, 0])
            cylinder(h = plate_thickness, d = mount_neck_diameter);
    }
}

module rounded_lug_back(xc) {
    hull() {
        translate([xc, rear_hole_y, 0])
            cylinder(h = plate_thickness, d = rear_pad_diameter);

        translate([xc, rear_body_y, 0])
            cylinder(h = plate_thickness, d = rear_neck_diameter);
    }
}

module adapter_body() {
    union() {
        // Main relay-board platform
        translate([0, mount_body_start_y, 0])
            cube([plate_length, rear_body_y - mount_body_start_y, plate_thickness]);

        // Front rounded lugs
        for (i = [0 : original_hole_count - 1]) {
            xpos = orig_hole_x0 + i * original_hole_spacing;
            rounded_lug_front(xpos);
        }

        // Rear rounded lugs
        if (rear_lugs_enabled) {
            for (i = [0 : original_hole_count - 1]) {
                xpos = orig_hole_x0 + i * original_hole_spacing;
                rounded_lug_back(xpos);
            }
        }
    }
}

module original_mount_holes() {
    for (i = [0 : original_hole_count - 1]) {
        xpos = orig_hole_x0 + i * original_hole_spacing;
        translate([xpos, orig_hole_y, -1])
            cylinder(h = plate_thickness + 2, d = original_hole_d);
    }
}

module rear_mount_holes() {
    if (rear_lugs_enabled) {
        for (i = [0 : original_hole_count - 1]) {
            xpos = orig_hole_x0 + i * original_hole_spacing;
            translate([xpos, rear_hole_y, -1])
                cylinder(h = plate_thickness + 2, d = rear_hole_d);
        }
    }
}

module pcb_mount_standoff(x, y) {
    difference() {
        translate([x, y, plate_thickness])
            cylinder(h = standoff_height, d = standoff_diameter);

        translate([x, y, -1])
            cylinder(h = plate_thickness + standoff_height + 2, d = pcb_hole_d);
    }
}

module pcb_rim() {
    difference() {
        // Outer rim body around the board zone
        translate([pcb_zone_x0, pcb_zone_y0, plate_thickness])
            cube([pcb_zone_width, pcb_zone_depth, rim_height]);

        // Hollow inner opening
        translate([pcb_zone_x0 + rim_thickness,
                   pcb_zone_y0 + rim_thickness,
                   plate_thickness - 1])
            cube([
                pcb_zone_width - 2 * rim_thickness,
                pcb_zone_depth - 2 * rim_thickness,
                rim_height + 2
            ]);
    }
}

module relay_adapter_plate() {
    difference() {
        adapter_body();
        original_mount_holes();
        rear_mount_holes();
    }

    // PCB standoffs
    pcb_mount_standoff(pcb_hole_x1, pcb_hole_y1);
    pcb_mount_standoff(pcb_hole_x2, pcb_hole_y1);
    pcb_mount_standoff(pcb_hole_x1, pcb_hole_y2);
    pcb_mount_standoff(pcb_hole_x2, pcb_hole_y2);

    // Protective rim / seam around the board area
    pcb_rim();
}

relay_adapter_plate();
