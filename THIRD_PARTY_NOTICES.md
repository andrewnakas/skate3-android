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

Two things in this app are darchap's work, from
https://github.com/darchap/Skate3-Port:

  - Stopping ambient crowds and movable props at the SPAWN rather than hiding
    them at the draw. The three LivingWorld census managers are hooked and take
    the game's own "spawned nothing" exit, so an entity that is never created
    costs no collision, no voice, no engine noise and no update slot. He did
    this first; our engine commit 46eb33c ports it and says so. The later
    "Other Skaters" cut is our own extension of the same technique.

  - The foreground keep-alive service that stops Android taking the process
    while a session is backgrounded. The 8 GiB guest mapping makes a cached
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
