#!/usr/bin/bash
/home/user/.conda-yosys/bin/yosys vlsi/map_cells_vc.ys
/home/user/.conda-yosys/bin/yosys vlsi/map_cells_small.ys
/home/user/.conda-openroad/bin/openroad -exit vlsi/calculate_power_vc.tcl
/home/user/.conda-openroad/bin/openroad -exit vlsi/calculate_power_small.tcl