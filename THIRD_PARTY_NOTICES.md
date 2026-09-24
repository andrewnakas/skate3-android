# Third-party notices — Skate 3 for Android

`LICENSE` covers the Android application and launcher in this repository. It does
not cover the game (not distributed here — players supply their own copy), and it
does not cover the work below, which is redistributed inside the APK or compiled
into it under its authors' own licences.

The APK ships its own copy of these notices at
`app/src/main/assets/third-party/NOTICE.txt`, so they travel with the binary as
well as with the source. That file is the fuller list — it also carries Mesa /
Turnip, libadrenotools and liblinkernsbypass in full.

---

## darchap — Skate3-Port

<https://github.com/darchap/Skate3-Port>

```
================================================================================
Skate3-Port - darchap
================================================================================

Six of this app's settings were darchap's first, from
https://github.com/darchap/Skate3-Port. Both trees were compared against their
common upstream and every shared line dated; all six landed in ours on
2026-09-22, the day after the two ports were compared:

  - Vegetation cut ........................ his 2026-09-13
  - Pedestrians & Traffic, Movable Props .. his 2026-09-13
       (the spawn-source version, 2026-09-16)
  - Hair Detail ........................... his 2026-09-14
  - Water Effects ......................... his 2026-09-14
  - FPS Percentiles, the 1% low and p95/p99 his 2026-09-15

Only the crowd cuts were credited at the time. The percentile code is his line
for line - same four-second window, same max(1, n/100) slowest set, same
mean-of-the-slowest-1% as FPS; ours adds comments and nothing else.

The crowd cuts are the good idea and worth stating: rather than hiding the
meshes at draw time, the three LivingWorld census managers are hooked and take
the game's own "spawned nothing" exit, so an entity that is never created costs
no collision, no voice, no engine noise and no update slot. The "Other Skaters"
cut is our own extension of that technique.

Also his: the foreground keep-alive service that stops Android taking the
process while a session is backgrounded. The 8 GiB guest mapping makes a cached
process the first thing the low-memory killer picks.

Copyright (c) 2026 darchap

BSD 3-Clause License

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

Per clause 3: darchap has not endorsed this build.
```

---

## The recompilation this is built on

- **Alex McHugh** — [`mchughalex/skate3recomp`](https://github.com/mchughalex/skate3recomp)
  and [`mchughalex/rexglue-skate3`](https://github.com/mchughalex/rexglue-skate3).
  The Skate 3 recompilation itself.
- **portingpete** — [`portingpete/skate3-recomp`](https://github.com/portingpete/skate3-recomp).
  Early Skate 3 bring-up on ReXGlue.
- **Buku313** — [`Buku313/Skate3-Mobile`](https://github.com/Buku313/Skate3-Mobile).
  A parallel ARM64 port; the 16 KB page-alignment fix came from there.
- **ReXGlue SDK** — [`rexglue/rexglue-sdk`](https://github.com/rexglue/rexglue-sdk),
  and **Xenia** (Ben Vanik and contributors) underneath it.

Neither upstream Skate 3 repository carries an explicit licence file. Where code
is inherited from them it remains under its authors' terms.

---

## Also in the APK

See `app/src/main/assets/third-party/NOTICE.txt` for the full texts:

- **Billy Laws** — libadrenotools and liblinkernsbypass (BSD 2-Clause)
- **Alan Constantino** — [`AlanConstantino/skate3-pocket`](https://github.com/AlanConstantino/skate3-pocket),
  the GPU driver proxy, importer and selection UI. See `native/PROVENANCE.md`.
- **Mesa / Turnip**, as published by **MrPurple666** — the bundled driver
