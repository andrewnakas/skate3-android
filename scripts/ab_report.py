#!/usr/bin/env python3
"""Turn one ab.sh capture into a summary.

The frame timestamps are ACCUMULATED from repeated --latency polls, so they
arrive with heavy duplication (each poll re-reports up to 128 frames, most of
which the previous poll already saw). Dedupe, then the gaps between successive
unique present times are the intervals a player actually saw.
"""
import sys, pathlib

out = pathlib.Path(sys.argv[1]); secs = float(sys.argv[2])
c0, c1, ticks = sys.argv[3], sys.argv[4], float(sys.argv[5])

SENTINEL = (1 << 63) - 1
ts = set()
for line in (out / "frames.raw").read_text().splitlines():
    line = line.strip()
    if not line.isdigit():
        continue
    v = int(line)
    if 0 < v < SENTINEL:
        ts.add(v)
present = sorted(ts)

print(f"   presented frames  {len(present)}  (unique, accumulated over the run)")
if len(present) > 2:
    span = (present[-1] - present[0]) / 1e9
    # A gap far larger than any real frame means the polls missed frames in
    # between (SurfaceFlinger only keeps 128). Those are not stalls and must
    # not be counted as such, or a sampling hole reads as a hitch.
    raw = [(b - a) / 1e6 for a, b in zip(present, present[1:])]
    gaps = [g for g in raw if 0 < g < 200]
    dropped = len(raw) - len(gaps)
    if gaps:
        g = sorted(gaps); n = len(g)
        q = lambda x: g[min(n - 1, int(n * x))]
        print(f"   coverage          {span:.0f}s of {secs:.0f}s"
              + (f"   ({dropped} sampling holes excluded)" if dropped else ""))
        print(f"   fps (presented)   {len(gaps)/sum(gaps)*1000:6.1f}")
        print(f"   frame interval    p50 {q(.50):5.2f}  p95 {q(.95):5.2f}  "
              f"p99 {q(.99):5.2f}  max {g[-1]:6.2f} ms")
        print(f"   over 20 ms        {100*sum(1 for x in g if x > 20)/n:5.1f} % of frames")
else:
    print("   no frame intervals captured - was the game in the world?")

# The primary metric. Average fps has agreed with the wrong conclusion before;
# whether the governor took the clock away has not.
rows = []
busy = []
for line in (out / "gpu.txt").read_text().splitlines():
    f = line.split()
    if len(f) >= 5:
        rows.append((int(f[0]), int(f[1]), int(f[2]), int(f[4])))
    if len(f) >= 6 and f[5].isdigit():
        busy.append((int(f[3]), int(f[5])))   # (clock MHz, busy %)
if rows:
    t0 = rows[0][0]
    worst = max(r[1] for r in rows)
    first, last = rows[0], rows[-1]
    print(f"   GPU ceiling       {first[2]} -> {last[2]} MHz   "
          f"pwrlevel {first[1]} -> {last[1]} (worst {worst})")
    print(f"   GPU junction      {first[3]} -> {last[3]} C")
    clamped = next((r for r in rows if r[1] != 0), None)
    if clamped:
        print(f"   ** CLAMPED after {clamped[0]-t0}s ** -> pwrlevel {clamped[1]}, ceiling {clamped[2]} MHz")
    else:
        print(f"   PASS: pwrlevel 0 held for the whole {rows[-1][0]-t0}s")

# Energy. mJ per presented frame is the number that makes "this lowers
# sustained power" falsifiable: a change that trades fps for efficiency shows
# up here and nowhere else.
pw = []
pf = out / "power.txt"
if pf.exists():
    for line in pf.read_text().splitlines():
        f = line.split()
        if len(f) >= 4:
            try:
                pw.append((int(f[0]), int(f[1]), int(f[2]), int(f[3])))
            except ValueError:
                pass
if len(pw) > 2:
    dt = pw[-1][0] - pw[0][0]
    d_uah = pw[-1][1] - pw[0][1]           # +ve while charging
    mv = sum(r[2] for r in pw) / len(pw)
    ma = sum(abs(r[3]) for r in pw) / len(pw)
    # Was `d_uah > 0`. Wrong: on USB the counter can sit flat while the phone
    # trickle-charges, which produced a confident "0.00 mJ per presented frame"
    # for a run that plainly used energy. Read the charger flags instead.
    cfg = {}
    for line in (out / "config.txt").read_text().splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            cfg[k] = v
    powered = "true" in cfg.get("charging", "").lower()
    charging = powered or d_uah > 0
    watts = ma * mv / 1e6
    print(f"   power             {watts:6.2f} W  ({ma:.0f} mA @ {mv:.0f} mV)"
          + ("   <- CHARGING: this is net, not draw" if charging else ""))
    if dt > 0 and not charging:
        # uAh -> J:  uAh * 3.6 mC/uAh * mV / 1e6
        joules = abs(d_uah) * 3.6e-3 * mv / 1e3
        print(f"   energy            {joules:6.1f} J over {dt}s  ({joules/dt:.2f} W sustained)")
        if len(present) > 2:
            print(f"   **  {joules/len(present)*1000:6.2f} mJ per presented frame  **")
    elif charging:
        print(f"   energy            NOT MEASURABLE - charger attached ({cfg.get('charging','?')}); "
              f"counter moved {d_uah} uAh. Unplug for a real number.")

# With the frame rate PINNED at 60, fps is a constant and says nothing about
# whether a change made the phone cooler. GPU work does. busy% alone is not
# enough either -- the governor trades clock against occupancy, so 90% at
# 350 MHz and 40% at 818 MHz are different amounts of work. busy% x clock is
# the work-rate proxy that is comparable across runs at a fixed frame rate.
if busy:
    mhz = sum(b[0] for b in busy) / len(busy)
    pct = sum(b[1] for b in busy) / len(busy)
    print(f"   GPU work          busy {pct:4.1f} %  @ {mhz:5.0f} MHz avg"
          f"   -> work-rate index {pct*mhz/100:6.0f}  (lower is cooler)")

try:
    print(f"   cpu               {100*((int(c1)-int(c0))/ticks)/secs:6.1f} % of one core (pid total)")
except Exception:
    pass
mem = (out / "meminfo.txt").read_text().strip()
if mem:
    print(f"   {mem}")
