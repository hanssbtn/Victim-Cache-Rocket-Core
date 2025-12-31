#!/usr/bin/bash
echo "Making buildfile for SmallL1RocketConfig..."
make -C vlsi buildfile CONFIG=SmallL1RocketConfig TECH_CONF=tech-conf.yml tech_name=sky130 TOOLS_CONF=tool-conf.yml DESIGN_CONF=example-designs/design-conf.yml ENABLE_YOSYS_FLOW=1
echo "Synthesizing SmallL1RocketConfig..."
make -C vlsi syn CONFIG=SmallL1RocketConfig TECH_CONF=tech-conf.yml tech_name=sky130 TOOLS_CONF=tool-conf.yml DESIGN_CONF=example-designs/design-conf.yml ENABLE_YOSYS_FLOW=1 
echo "Making buildfile for VictimCacheConfig..."
make -C vlsi buildfile CONFIG=VictimCacheConfig TECH_CONF=tech-conf.yml tech_name=sky130 TOOLS_CONF=tool-conf.yml DESIGN_CONF=example-designs/design-conf.yml ENABLE_YOSYS_FLOW=1
echo "Synthesizing VictimCacheConfig..."
make -C vlsi syn CONFIG=VictimCacheConfig TECH_CONF=tech-conf.yml tech_name=sky130 TOOLS_CONF=tool-conf.yml DESIGN_CONF=example-designs/design-conf.yml ENABLE_YOSYS_FLOW=1 
python /home/$USER/chipyard/generators/chipyard/src/main/scala/victimcache/get_area_estimate.py