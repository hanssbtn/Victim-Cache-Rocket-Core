# Load LEFs
read_lef /home/user/chipyard/vlsi/build/chipyard.harness.TestHarness.VictimCacheConfig-ChipTop/tech-sky130-cache/sky130_fd_sc_hd__nom.tlef
read_lef /home/user/.conda-sky130/share/pdk/sky130A/libs.ref/sky130_fd_sc_hd/lef/sky130_fd_sc_hd.lef

# Load library
read_liberty /home/user/.conda-sky130/share/pdk/sky130A/libs.ref/sky130_fd_sc_hd/lib/sky130_fd_sc_hd__tt_025C_1v80.lib

# Load netlist
read_verilog vlsi/ChipTop.power_ready_VictimCacheConfig.v

# Link design
link_design ChipTop

# Define clock
# Note: 'clock_uncore' is standard, but if it fails, check the port list
create_clock -name core_clock -period 20 [get_ports clock_uncore]

# Report Power
set_power_activity -global -activity 0.2
report_power