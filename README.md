# Prerequisites
Before running the simulation, add this to line 138 in the `vlsi/` Makefile:
```
    for x in $$(cat $(VLSI_RTL)); do \
		echo '    - "'$$x'"' >> $@; \
	done
	echo '    - "$(vlsi_dir)/stubs.v"' >> $@ # Add this line
```

After that, add these compile targets to `tests/CMakeLists.txt` file:
```
#################################
# Build
#################################
# Other tests...
add_executable(cache_test_1 cache_test_1.c) 
add_executable(cache_test_2 cache_test_2.c) 
add_executable(cache_test_3 cache_test_3.c) 
add_executable(cache_test_4 cache_test_4.c) 

# Optionally add dump targets for the tests above

#################################
# Disassembly
#################################
# Other disassemblies...
add_dump_target(cache_test_1 cache_test_1.c) 
add_dump_target(cache_test_2 cache_test_2.c) 
add_dump_target(cache_test_3 cache_test_3.c) 
add_dump_target(cache_test_4 cache_test_4.c) 

# Other sections...
```

Lastly, if your Chipyard/OpenROAD/Yosys directory differs from the default location (e.g. `/home/$USER/chipyard` for Chipyard on Linux), edit the `.sh`, `.yml`, `.ys`, `.tcl` and `.py` files to match your installation directories.

# Running the simulation
To run the simulation, copy and paste these command: 
```
cd chipyard # Modify according to your chipyard directory
source env.sh
./generators/chipyard/src/main/scala/victimcache/copy-files.sh // Copy the necessary files to the correct directories
./generators/chipyard/src/main/scala/victimcache/synthesize-chip.sh // Synthesize the configs
./generators/chipyard/src/main/scala/victimcache/benchmark.py // Run the benchmark
./chipyard/generators/chipyard/src/main/scala/victimcache/estimate_power.sh // Get power estimate
```