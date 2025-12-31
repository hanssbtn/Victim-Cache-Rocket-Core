import subprocess
import re
import matplotlib.pyplot as plt
import numpy as np
import os
import sys
import glob

# ================= CONFIGURATION =================
CHIPYARD_DIR = os.getcwd() # Assumes you run this from chipyard root
VLSI_DIR = f"{CHIPYARD_DIR}/vlsi"
FIGURES_DIR = f"{os.path.dirname(__file__)}/figures"

# SIMULATOR PATHS (RTL - Fast)
SIM_VC = f"{CHIPYARD_DIR}/sims/verilator/simulator-chipyard.harness-VictimCacheConfig-debug"
SIM_DEFAULT = f"{CHIPYARD_DIR}/sims/verilator/simulator-chipyard.harness-SmallL1RocketConfig-debug"

# TESTS
TESTS = ["cache_test_1.riscv", "cache_test_2.riscv", "cache_test_3.riscv", "cache_test_4.riscv"] # Kept short for power demo
TEST_PATHS = [f"{CHIPYARD_DIR}/tests/{test}" for test in TESTS]
TEST_NAMES = ["Linear", "Thrashing", "Stride", "MatMul"]

# ================= METRICS STORAGE =================
results = {
    "VC_Config": {name: {"cycles": 0, "vc_hits": 0, "vc_misses": 0, "vc_allocs": 0, "power_mw": 0.0, "energy_pj_op": 0.0} for name in TEST_NAMES},
    "Default_Config": {name: {"cycles": 0, "vc_hits": 0, "vc_misses": 0, "vc_allocs": 0} for name in TEST_NAMES}
}

def run_command(command, env):
    print(f"\n[EXEC] {command}")
    try:
        subprocess.run(
            command, 
            shell=True, 
            env=env, 
            check=True, 
            executable="/bin/bash"
        )
    except subprocess.CalledProcessError:
        print(f"Command failed: {command}")
        sys.exit(1)

def get_env_from_script(script_path):
    if not os.path.exists(script_path): return os.environ.copy()
    command = f"source {script_path} && env"
    try:
        res = subprocess.run(command, shell=True, executable="/bin/bash", capture_output=True, text=True, check=True)
        env = {}
        for line in res.stdout.splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                env[k] = v
        return env
    except: return os.environ.copy()

def parse_line(line, current_data, config_key):
    if m := re.search(r"Cycles:\s+(\d+)", line): current_data["cycles"] = int(m.group(1))
    if m := re.search(r"Instructions:\s+(\d+)", line): current_data["instructions"] = int(m.group(1))
    if m := re.search(r"L1D misses\s+\(mhpmcounter3\)\s+=\s+([0-9a-fA-Fx]+)", line): current_data["l1d_misses"] = int(m.group(1), 16)
    if m := re.search(r"L1D misses\s+\(mhpmcounter4\)\s+=\s+([0-9a-fA-Fx]+)", line): current_data["l1i_misses"] = int(m.group(1), 16)
    if m := re.search(r"L1D accesses\s+\(mhpmcounter5\)\s+=\s+([0-9a-fA-Fx]+)", line): current_data["l1d_accesses"] = int(m.group(1), 16)
    if m := re.search(r"L1D hits\s+=\s+([0-9a-fA-Fx]+)", line): current_data["l1d_hits"] = int(m.group(1), 16)

    if config_key == "VC_Config":
        if "[VC-HIT]" in line: current_data["vc_hits"] += 1
        elif "[VC-MISS]" in line: current_data["vc_misses"] += 1
        elif "[VC-ALLOC]" in line: current_data["vc_allocs"] += 1

def run_simulation_and_metrics(sim_path, binary, test_name, config_key, env):
    print(f"Processing {test_name} [{config_key}]...")
    if not os.path.exists(sim_path):
        print(f"CRITICAL ERROR: Simulator not found at {sim_path}")
        return
    if not os.path.exists(binary):
        print(f"CRITICAL ERROR: Binary not found at {binary}")
        return
    
    log_file = f"temp_{config_key}_{test_name}.log"
    try:
        if os.path.exists(log_file):
            print(f"WARNING: Results for {test_name} already exists. skipping...")
        else:
            print(f"Running {test_name} on {config_key} -> Saving to {log_file}...")
            with open(log_file, "w") as f_out:
                subprocess.run([sim_path, binary, "+verbose", "ENABLE_YOSYS_FLOW=1"], stdout=f_out, stderr=subprocess.STDOUT, timeout=1800)
        print(f" ... Analyzing logs ...")
        with open(log_file, "r", errors='replace') as f_in:
            for line in f_in:
                parse_line(line, results[config_key][test_name], config_key)
        d = results[config_key][test_name]
        print(f" -> Cycles: {d['cycles']} | Instr: {d['instructions']}")
        print(f" -> IPC: {d['cycles'] / d['instructions']:.2f}")
        print(f" -> L1D Stats: {d['l1d_hits']} Hits / {d['l1d_misses']} Misses / {d['l1d_accesses']} Accesses")

        # Parse Verilator Log
        if config_key == "VC_Config":
            total_vc = d['vc_hits'] + d['vc_misses']
            rate = (d['vc_hits'] / total_vc * 100) if total_vc > 0 else 0
            print(f" -> VC Stats: {d['vc_hits']} Hits / {total_vc} Accesses ({rate:.2f}%)")
            
    except subprocess.TimeoutExpired:
        print(f"Error: {test_name} timed out!")
        return
    except Exception as e:
        print(f"Error: {e}")
        return
    
def plot_results():
    labels = TEST_NAMES
    x = np.arange(len(labels))
    width = 0.35
    # --- GRAPH 1: Execution Time ---
    fig1, ax1 = plt.subplots(figsize=(10, 6))
    def_cycles = [results["Default_Config"][t]["cycles"] for t in TEST_NAMES]
    vc_cycles = [results["VC_Config"][t]["cycles"] for t in TEST_NAMES]
    ax1.bar(x - width/2, def_cycles, width, label='Baseline Config')
    ax1.bar(x + width/2, vc_cycles, width, label='With Victim Cache')
    ax1.set_ylabel('Cycles')
    ax1.set_title('Execution Time Comparison')
    ax1.set_xticks(x)
    ax1.set_xticklabels(labels)
    ax1.legend()
    for i in range(len(vc_cycles)):
        if vc_cycles[i] > 0 and def_cycles[i] > 0:
            speedup = (def_cycles[i] - vc_cycles[i]) / def_cycles[i] * 100
            txt = f"{speedup:+.1f}%"
            height = max(vc_cycles[i], def_cycles[i])
            ax1.text(i + width/2, height + (max(def_cycles)*0.02), txt, ha='center', fontweight='bold', fontsize=9)
    plt.tight_layout()
    plt.savefig(f'{FIGURES_DIR}/graph_execution_time.png')
    print("Generated graph_execution_time.png")
    # --- GRAPH 2: L1D Cache Miss Rate ---
    fig2, ax2 = plt.subplots(figsize=(10, 6))
    def calc_miss_rate(d):
        if d["l1d_accesses"] == 0: 
            return 0.0
        return (d["l1d_misses"] / d["l1d_accesses"]) * 100
    def_miss = [calc_miss_rate(results["Default_Config"][t]) for t in TEST_NAMES]
    vc_miss = [calc_miss_rate(results["VC_Config"][t]) for t in TEST_NAMES]
    ax2.bar(x - width/2, def_miss, width, label='Default L1 Miss %')
    ax2.bar(x + width/2, vc_miss, width, label='VC Config L1 Miss %')
    ax2.set_ylabel('L1D Miss Rate (%)')
    ax2.set_title('L1 Data Cache Miss Rate')
    ax2.set_xticks(x)
    ax2.set_xticklabels(labels)
    ax2.legend()
    plt.tight_layout()
    plt.savefig(f'{FIGURES_DIR}/graph_l1_miss_rate.png')
    print("Generated graph_l1_miss_rate.png")

    # --- GRAPH 3: Victim Cache Hit Rate ---
    fig3, ax3 = plt.subplots(figsize=(10, 6))
    hits = [results["VC_Config"][t]["vc_hits"] for t in TEST_NAMES]
    misses = [results["VC_Config"][t]["vc_misses"] for t in TEST_NAMES]
    totals = [h + m for h, m in zip(hits, misses)]
    hit_rates = [(h/t*100) if t > 0 else 0 for h, t in zip(hits, totals)]
    colors = ['red' if r < 5 else 'orange' if r < 30 else 'green' for r in hit_rates]

    ax3.bar(labels, hit_rates, color=colors)
    ax3.set_ylabel('VC Hit Rate (%)')
    ax3.set_title('Victim Cache Efficiency (Local)')
    ax3.set_ylim(0, 100)
    for i, v in enumerate(hit_rates):
        ax3.text(i, v + 1, f"{v:.1f}%", ha='center', fontweight='bold')

    plt.tight_layout()
    plt.savefig(f'{FIGURES_DIR}/graph_vc_efficiency.png')
    print("Generated graph_vc_efficiency.png")

def plot_vc_detailed_stats(results, test_names):
    vc_data = results["VC_Config"]
    
    hits = [vc_data[t]["vc_hits"] for t in test_names]
    misses = [vc_data[t]["vc_misses"] for t in test_names]
    allocs = [vc_data[t]["vc_allocs"] for t in test_names]
    
    x = np.arange(len(test_names))
    width = 0.6

    # --- GRAPH 1: VC HITS ---
    fig1, ax1 = plt.subplots(figsize=(8, 6))
    bars1 = ax1.bar(x, hits, width, color='#4CAF50', edgecolor='black') # Green
    ax1.set_ylabel('Count')
    ax1.set_title('Victim Cache Hits by Test Type')
    ax1.set_xticks(x)
    ax1.set_xticklabels(test_names)
    ax1.grid(axis='y', linestyle='--', alpha=0.7)
    
    # Add labels on top of bars
    for bar in bars1:
        height = bar.get_height()
        ax1.text(bar.get_x() + bar.get_width()/2., height,
                f'{int(height)}', ha='center', va='bottom')
    
    plt.tight_layout()
    plt.savefig(f'{FIGURES_DIR}/graph_vc_metrics_hits.png')
    print("Generated graph_vc_metrics_hits.png")
    plt.close()

    # --- GRAPH 2: VC MISSES ---
    fig2, ax2 = plt.subplots(figsize=(8, 6))
    bars2 = ax2.bar(x, misses, width, color='#F44336', edgecolor='black') # Red
    ax2.set_ylabel('Count')
    ax2.set_title('Victim Cache Misses by Test Type')
    ax2.set_xticks(x)
    ax2.set_xticklabels(test_names)
    ax2.grid(axis='y', linestyle='--', alpha=0.7)

    for bar in bars2:
        height = bar.get_height()
        ax2.text(bar.get_x() + bar.get_width()/2., height,
                f'{int(height)}', ha='center', va='bottom')

    plt.tight_layout()
    plt.savefig(f'{FIGURES_DIR}/graph_vc_metrics_misses.png')
    print("Generated graph_vc_metrics_misses.png")
    plt.close()

    # --- GRAPH 3: VC ALLOCATIONS ---
    fig3, ax3 = plt.subplots(figsize=(8, 6))
    bars3 = ax3.bar(x, allocs, width, color='#2196F3', edgecolor='black') # Blue
    ax3.set_ylabel('Count')
    ax3.set_title('Victim Cache Allocations by Test Type')
    ax3.set_xticks(x)
    ax3.set_xticklabels(test_names)
    ax3.grid(axis='y', linestyle='--', alpha=0.7)

    for bar in bars3:
        height = bar.get_height()
        ax3.text(bar.get_x() + bar.get_width()/2., height,
                f'{int(height)}', ha='center', va='bottom')

    plt.tight_layout()
    plt.savefig(f'{FIGURES_DIR}/graph_vc_metrics_allocs.png')
    print("Generated graph_vc_metrics_allocs.png")
    plt.close()


if __name__ == "__main__":
    build_env = get_env_from_script("env.sh")
    print("=== Buiding VC config ===")
    run_command("make -C sims/verilator/ CONFIG=VictimCacheConfig debug", build_env)
    print("=== Buiding baseline config ===")
    run_command("make -C sims/verilator/ CONFIG=SmallL1RocketConfig debug", build_env)
    print("=== Buiding tests ===")
    run_command("cmake tests", build_env)
    run_command("make -C tests", build_env)
    
    print("\n=== Running Default Config ===")
    for i, test in enumerate(TEST_PATHS):
        run_simulation_and_metrics(SIM_DEFAULT, test, TEST_NAMES[i], "Default_Config", build_env)

    print("\n=== Running Victim Cache Config (+ Power) ===")
    for i, test in enumerate(TEST_PATHS):
        run_simulation_and_metrics(SIM_VC, test, TEST_NAMES[i], "VC_Config", build_env)

    plot_results()

    plot_vc_detailed_stats(results, TEST_NAMES)


