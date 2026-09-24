#!/usr/bin/env python3
"""Compare two ab.sh runs, and say plainly when they are not comparable.

The whole point: a frame-rate difference between two runs whose frame cap,
present mode, settings or starting temperature differ tells you nothing, and
this project has twice spent a session on exactly that mistake.
"""
import sys, pathlib, re

def load(d):
    d = pathlib.Path(d)
    cfg = {}
    for line in (d / "config.txt").read_text().splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            cfg[k] = v
    def read(n):
        p = d / n
        return p.read_text().strip() if p.exists() else ""
    return d, cfg, read("android_args.txt"), read("settings.toml"), \
           read("pace_and_present.txt"), read("summary.txt")

a, ca, arga, seta, pacea, suma = load(sys.argv[1])
b, cb, argb, setb, paceb, sumb = load(sys.argv[2])

def cap_of(pace):
    m = re.findall(r"guest frame cap is now (\S+)", pace)
    return m[-1] if m else "?"
def mode_of(pace):
    m = re.findall(r"presentation mode \d+ \((\w+)\)", pace)
    return m[-1] if m else "?"

print(f"A  {a.name}")
print(f"B  {b.name}")
print()

blocking = []
# Things that must match or the comparison is meaningless.
for key, label in [("app_version", "app version"), ("so_build_id", "libmain.so")]:
    if ca.get(key) != cb.get(key):
        print(f"   {label:18} A={ca.get(key)}  B={cb.get(key)}   <- differs (expected, if this IS the change)")
if cap_of(pacea) != cap_of(paceb):
    blocking.append(f"frame cap differs: A={cap_of(pacea)} B={cap_of(paceb)}")
if mode_of(pacea) != mode_of(paceb):
    blocking.append(f"present mode differs: A={mode_of(pacea)} B={mode_of(paceb)}")
if seta != setb:
    blocking.append("settings.toml differs")
if arga != argb:
    # args differing is normal when the args file IS the variable; say what.
    da = set(arga.splitlines()) - set(argb.splitlines())
    db = set(argb.splitlines()) - set(arga.splitlines())
    da = {x for x in da if x.strip() and not x.strip().startswith("#")}
    db = {x for x in db if x.strip() and not x.strip().startswith("#")}
    if da or db:
        print("   args changed:")
        for x in sorted(da): print(f"     A only: {x}")
        for x in sorted(db): print(f"     B only: {x}")
        print()

# The GPU's STARTING power level is the confound that matters most and the one
# this comparison originally missed: a run that begins unclamped at 818 MHz and
# one that begins already clamped at 421 MHz are not the same experiment, even
# at the same battery temperature. Caught in the merge-hud-pass A/B, where the
# tool said "comparable" for a pair whose starting ceilings differed 2x.
pa, pb = ca.get("gpu_pwrlevel_start"), cb.get("gpu_pwrlevel_start")
ka, kb = ca.get("gpu_ceiling_start_mhz"), cb.get("gpu_ceiling_start_mhz")
if pa != pb or ka != kb:
    blocking.append(f"started in different GPU power states: "
                    f"A=pwrlevel {pa} @ {ka} MHz, B=pwrlevel {pb} @ {kb} MHz")

ta, tb = float(ca.get("battery_temp_c", 0)), float(cb.get("battery_temp_c", 0))
if abs(ta - tb) > 3:
    blocking.append(f"started at different temperatures: A={ta}C B={tb}C")
if ca.get("charging") != cb.get("charging"):
    blocking.append(f"charging state differs: A={ca.get('charging')} B={cb.get('charging')}")
if ca.get("seconds") != cb.get("seconds"):
    blocking.append(f"different run lengths: A={ca.get('seconds')}s B={cb.get('seconds')}s")

print("--- A ---"); print(suma)
print("--- B ---"); print(sumb)
print()
if blocking:
    print("NOT COMPARABLE:")
    for x in blocking:
        print(f"  - {x}")
    print("\nFix the configuration and re-run. A number from these two runs is not evidence.")
    sys.exit(1)
print("Comparable: configuration matches apart from the change under test.")
