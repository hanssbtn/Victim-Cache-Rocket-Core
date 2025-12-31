import re
import sys
import os
import pandas as pd

def parse_yosys_log(file_path):
    if not os.path.exists(file_path):
        print(f"Error: File '{file_path}' not found.")
        return {}

    modules = {}
    
    # Regex to find area lines. Matches:
    #   Chip area for module '\TLVictimCache': 207784.281600
    #   Chip area for top module '\ChipTop': 2375888.665600
    area_pattern = re.compile(r"^\s*Chip area for (?:top )?module ['\"](.+)['\"]:\s+([0-9\.]+)")

    try:
        with open(file_path, 'r') as f:
            for line in f:
                match = area_pattern.search(line)
                if match:
                    raw_name = match.group(1)
                    area = float(match.group(2))
                    
                    clean_name = raw_name.replace('\\', '')
                    
                    modules[clean_name] = area
    except Exception as e:
        print(f"Error reading {file_path}: {e}")
        return {}

    return modules

def generate_report(modules, title="Area Report"):
    if not modules:
        print(f"\n--- {title}: No Data Found ---")
        return pd.DataFrame()

    # Determine Total Area
    total_area = modules.get('ChipTop')
    if not total_area:
        if modules:
            total_area = max(modules.values())
        else:
            total_area = 1.0 # Prevent div by zero

    data_list = []
    for name, area_um in modules.items():
        data_list.append({
            "Module Name": name,
            "Area (um²)": area_um,
            "Area (mm²)": area_um / 1_000_000,
            "% Total": (area_um / total_area) * 100
        })

    df = pd.DataFrame(data_list)
    
    df['sort_key'] = df['Area (um²)']
    if 'ChipTop' in modules:
        # Give ChipTop a fake high sort key to ensure it is first
        df.loc[df['Module Name'] == 'ChipTop', 'sort_key'] = total_area * 10 
    
    df = df.sort_values(by='sort_key', ascending=False).drop(columns=['sort_key'])

    # --- Print Text Table ---
    print(f"\n{'='*114}")
    print(f" {title}")
    print(f"{'='*114}")
    print(f"{'Module Name':<67} | {'Area (um²)':>15} | {'Area (mm²)':>12} | {'% Total':>8}")
    print("-" * 114)

    for index, row in df.iterrows():
        name = row['Module Name']
        area_um = row['Area (um²)']
        area_mm = row['Area (mm²)']
        percent = row['% Total']
        
        prefix = "   "
        if name == "ChipTop":
            prefix = "** "
        elif "VictimCache" in name or name == "Rocket":
            prefix = "-> "

        print(f"{prefix}{name:<65} | {area_um:15,.2f} | {area_mm:12.4f} | {percent:8.4f}%")

    print("-" * 114)
    print(f"Total Logic Area: {total_area/1_000_000:.4f} mm²")
    
    return df

def export_to_excel(dfs, filename=f"{os.path.dirname(__file__)}/tables/chip_area_comparison.xlsx"):
    os.makedirs(f"{os.path.dirname(__file__)}/tables", exist_ok=True)
    if not dfs:
        return

    try:
        with pd.ExcelWriter(filename) as writer:
            for sheet_name, df in dfs.items():
                if not df.empty:
                    df.to_excel(writer, sheet_name=sheet_name[:31], index=False) # Excel limits sheet names to 31 chars
        print(f"\n[Success] Excel report saved to: {filename}")
    except Exception as e:
        print(f"\n[Error] Could not write Excel file: {e}")

if __name__ == "__main__":
    # 1. Define File Paths (Retained as requested)
    file_vc = sys.argv[1] if len(sys.argv) > 1 else "/home/user/chipyard/vlsi/build/chipyard.harness.TestHarness.VictimCacheConfig-ChipTop/syn-rundir/ChipTop.synth_stat.txt"
    file_small = sys.argv[2] if len(sys.argv) > 2 else "/home/user/chipyard/vlsi/build/chipyard.harness.TestHarness.SmallL1RocketConfig-ChipTop/syn-rundir/ChipTop.synth_stat.txt"

    # 2. Parse Logs
    print(f"Parsing: {file_vc}")
    data_vc = parse_yosys_log(file_vc)
    
    print(f"Parsing: {file_small}")
    data_small = parse_yosys_log(file_small)

    # 3. Generate Reports (Print to console & get DataFrames)
    excel_sheets = {}

    if data_vc:
        df_vc = generate_report(data_vc, title="VictimCacheConfig Area")
        excel_sheets["VictimCache"] = df_vc
    
    if data_small:
        df_small = generate_report(data_small, title="SmallL1RocketConfig Area")
        excel_sheets["SmallL1Rocket"] = df_small

    # 4. Export to Excel
    if excel_sheets:
        export_to_excel(excel_sheets)