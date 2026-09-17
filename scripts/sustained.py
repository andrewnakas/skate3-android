#!/usr/bin/env python3
"""Does the frame rate decay over a session, and what grows as it does?

One row per `native-scene: alive` heartbeat, which the engine writes every 1800
frames at WARN (skate3_native_scene_gpu.cpp) - so it is in a shipping log, from
a tester, with no special build. The heartbeat carries a frame counter and the
line carries a timestamp, so 1800 frames divided by the gap between two of them
IS the window's frame rate. That is the column everything else is read against.

Beside it, from the same log when it was captured at log_level=info (see
android_args/sustained.txt), the things that could be growing: the texture and
mesh stores, the Vulkan allocator's live objects, and the retired-object
backlog. Each is carried forward from the most recent sample at or before the
window's end, so a row describes the state the window finished in.

The point of the layout is that a cause has to line up with the knee in the fps
column. A store that reaches its cap two minutes after the frame rate started
falling did not cause it.

  usage: scripts/sustained.py <log> [more logs...]

Logs are read in the order given; pass the rotated ones oldest-first
(skate3.4.log ... skate3.1.log skate3.log) to see a whole session. Runs are
split on the engine's own startup banner.
"""
import re
import sys
from datetime import datetime

TS = re.compile(r'^\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\]')

ALIVE = re.compile(
    r'native-scene: alive frame=(\d+) items=(\d+) draws=(\d+) '
    r'draws_2d=(\d+) draws_since_last=(\d+)')
STORE = re.compile(
    r'native-scene: store sizes tex=(\d+)MB/(\d+) mesh=(\d+)MB/(\d+)')
VMA = re.compile(
    r'nrhi-vulkan vma: blocks=(\d+) \((\d+)MB\) allocs=(\d+) \((\d+)MB\) '
    r'unused=(\d+)MB \| live tex=(\d+) buf=(\d+) view=(\d+)')
MEM = re.compile(r'nrhi-vulkan mem: .*\| retired=(\d+) \|')
LRU = re.compile(r'native-scene: (tex|mesh) store LRU (start|done) \((.*)\)')
PERF = re.compile(r'native-scene perf: guest_fps=(\d+) guest_dt_max=([\d.]+)ms')
PERFBIT = re.compile(r'\b(capture|build|render|items|shadow|tail|twod|commit)='
                     r'([\d.]+)/([\d.]+)ms')
BANNER = re.compile(r'skate3 starting \[(\S+)\]')

# The heartbeat's cadence is NOT assumed. It was every 1800 frames when this
# was written and the switch merge changed it to every ten seconds of wall
# clock, which would have silently rescaled every fps in the table had it been
# hardcoded. Each row divides the real frame delta by the real time delta
# between two consecutive beats, so either cadence - or a missed beat - gives
# the right answer.

# Below this many draws per frame the world is not on screen: a menu, a
# loading screen, or a pause. Gameplay in the logs this was written against
# never went under ~600.
kGameplayDrawsPerFrame = 400


def parse_ts(line):
    m = TS.match(line)
    if not m:
        return None
    return datetime.strptime(m.group(1), '%Y-%m-%d %H:%M:%S.%f')


class Run:
    def __init__(self, start, version):
        self.start = start
        self.version = version
        self.rows = []
        self.lru = []
        self.state = {}      # latest value of each carried-forward field
        self.prev = None     # (timestamp, frame) of the last heartbeat

    def beat(self, ts, frame, items, draws, draws_since_last):
        fps = None
        if self.prev is not None:
            pts, pframe = self.prev
            secs = (ts - pts).total_seconds()
            dframes = frame - pframe
            # A relaunch inside one file restarts the frame counter; a negative
            # or zero delta is a new run, not a window.
            if secs > 0 and dframes > 0:
                fps = dframes / secs
        self.prev = (ts, frame)
        if fps is not None:
            row = dict(self.state)
            row.update(ts=ts, frame=frame, items=items, draws=draws, fps=fps,
                       dpf=draws_since_last / dframes,
                       elapsed=(ts - self.start).total_seconds())
            self.rows.append(row)


def parse(paths):
    runs = []
    cur = None
    for path in paths:
        with open(path, errors='replace') as fh:
            for line in fh:
                ts = parse_ts(line)
                if ts is None:
                    continue

                m = BANNER.search(line)
                if m:
                    cur = Run(ts, m.group(1))
                    runs.append(cur)
                    continue

                m = ALIVE.search(line)
                if m:
                    frame = int(m.group(1))
                    # A rotated log can begin mid-run with no banner above it.
                    if cur is None or (cur.prev and frame < cur.prev[1]):
                        cur = Run(ts, cur.version if cur else '?')
                        runs.append(cur)
                    cur.beat(ts, frame, int(m.group(2)), int(m.group(3)),
                             int(m.group(5)))
                    continue

                if cur is None:
                    continue

                m = STORE.search(line)
                if m:
                    cur.state.update(tex_mb=int(m.group(1)), tex_n=int(m.group(2)),
                                     mesh_mb=int(m.group(3)), mesh_n=int(m.group(4)))
                    continue
                m = VMA.search(line)
                if m:
                    cur.state.update(vma_mb=int(m.group(2)), vma_live_mb=int(m.group(4)),
                                     vma_unused=int(m.group(5)), live_tex=int(m.group(6)),
                                     live_buf=int(m.group(7)), live_view=int(m.group(8)))
                    continue
                m = MEM.search(line)
                if m:
                    cur.state['retired'] = int(m.group(1))
                    continue
                m = LRU.search(line)
                if m:
                    store, event, detail = m.groups()
                    cur.lru.append((ts, store, event, detail))
                    cur.state[store + '_lru'] = (event == 'start')
                    continue
                m = PERF.search(line)
                if m:
                    for name, avg, _mx in PERFBIT.findall(line):
                        cur.state['p_' + name] = float(avg)
    return runs


COLUMNS = [
    # key, header, width, format
    ('elapsed',   'min',      5, lambda v: f'{v/60:5.1f}'),
    ('fps',       'fps',      5, lambda v: f'{v:5.1f}'),
    ('items',     'items',    5, lambda v: f'{v:5d}'),
    ('dpf',       'draw/f',   6, lambda v: f'{v:6.0f}'),
    ('tex_mb',    'texMB',    5, lambda v: f'{v:5d}'),
    ('tex_n',     'texN',     5, lambda v: f'{v:5d}'),
    ('mesh_mb',   'mshMB',    5, lambda v: f'{v:5d}'),
    ('mesh_n',    'mshN',     5, lambda v: f'{v:5d}'),
    ('retired',   'retird',   6, lambda v: f'{v:6d}'),
    ('live_view', 'views',    6, lambda v: f'{v:6d}'),
    ('vma_mb',    'vmaMB',    5, lambda v: f'{v:5d}'),
    ('p_build',   'build',    5, lambda v: f'{v:5.1f}'),
    ('p_render',  'rendr',    5, lambda v: f'{v:5.1f}'),
    ('p_items',   'items',    5, lambda v: f'{v:5.1f}'),
    ('p_commit',  'commt',    5, lambda v: f'{v:5.1f}'),
]


def render(run, index):
    print(f"\n=== run {index}  started {run.start:%H:%M:%S}  {run.version}")
    if not run.rows:
        print("  no alive heartbeats - the run never reached 1800 frames, or the "
              "log does not cover it")
        return

    # Only show columns that actually carry data, so a warn-only tester log
    # prints a narrow honest table instead of a wide empty one.
    cols = [c for c in COLUMNS if any(c[0] in r for r in run.rows)]
    print('  ' + ' '.join(f'{h:>{w}}' for _, h, w, _ in cols))
    for row in run.rows:
        cells = []
        for key, _h, w, fmt in cols:
            cells.append(fmt(row[key]) if key in row else ' ' * w)
        print('  ' + ' '.join(cells))

    # Summarise GAMEPLAY windows only. A run that ends in a menu ends at a
    # comfortable 60 fps drawing nothing, and comparing that against the first
    # window says the session got faster. Draws per frame separates them
    # cleanly - the menu windows in the logs this was written against ran ~135
    # while gameplay ran 850-1800 - where `items` does not, because `items` is
    # an instantaneous snapshot that legitimately dips mid-run.
    play = [r for r in run.rows if r.get('dpf', 0) >= kGameplayDrawsPerFrame]
    if not play:
        print(f"  no window drew more than {kGameplayDrawsPerFrame} draws/frame - "
              f"this run never reached gameplay")
        play = run.rows
    elif len(play) != len(run.rows):
        print(f"  ({len(run.rows) - len(play)} of {len(run.rows)} windows were "
              f"menu or loading and are left out of the summary below)")

    first, last = play[0], play[-1]
    worst = min(play, key=lambda r: r['fps'])
    print(f"  first {first['fps']:.1f} fps -> last {last['fps']:.1f} fps "
          f"({last['fps'] - first['fps']:+.1f}), "
          f"worst {worst['fps']:.1f} at {worst['elapsed']/60:.1f} min")
    # Frame time is the honest unit. The panel is pinned to 60 Hz and the
    # presenter runs FIFO, so 16.4ms -> 17.0ms is presented as 60 -> 30: the
    # cliff in the fps column can be a tenth of the change in this one.
    print(f"  frame time {1000/first['fps']:.1f}ms -> {1000/last['fps']:.1f}ms "
          f"({1000/last['fps'] - 1000/first['fps']:+.1f}ms), "
          f"worst {1000/worst['fps']:.1f}ms")

    for ts, store, event, detail in run.lru:
        mins = (ts - run.start).total_seconds() / 60
        print(f"  {mins:5.1f} min  {store} store LRU {event}: {detail}")
    if not run.lru:
        print("  no LRU latch seen (needs log_level=info; "
              "push android_args/sustained.txt)")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    runs = parse(sys.argv[1:])
    if not runs:
        sys.exit("no runs found - is this a skate3.log?")
    for i, run in enumerate(runs):
        render(run, i)
    print()


if __name__ == '__main__':
    main()
