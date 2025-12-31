#!/usr/bin/bash
# Link the test files to the $CHIPYARD_DIR/tests for compilation
# Assuming $CHIPYARD_DIR is /home/<USER>/chipyard and tests directory is $CHIPYARD_DIR/tests
SCRIPT_DIR="$(dirname "$(realpath "$0")")"
CHIPYARD_DIR=/home/$USER/chipyard
cp "$SCRIPT_DIR"/stubs.v "$CHIPYARD_DIR"/vlsi
cp "$SCRIPT_DIR"/tests/cache_test_*.c "$CHIPYARD_DIR"/tests
cp "$SCRIPT_DIR"/vlsi/tool-conf.yml "$CHIPYARD_DIR"/vlsi
cp "$SCRIPT_DIR"/vlsi/tech-conf.yml "$CHIPYARD_DIR"/vlsi
cp "$SCRIPT_DIR"/vlsi/design-conf.yml vlsi/example-designs/
cp "$SCRIPT_DIR"/vlsi/map_cells_*.ys "$CHIPYARD_DIR"/vlsi
cp "$SCRIPT_DIR"/vlsi/calculate_power_*.tcl "$CHIPYARD_DIR"/vlsi
echo "Don't forget to add the targets in ./tests directory to CMakeLists.txt"
