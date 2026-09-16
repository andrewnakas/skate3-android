# Bundled GPU driver

`turnip-t30.zip` is MrPurple's Turnip build for Adreno, shipped unmodified.

    release   https://github.com/MrPurple666/purple-turnip/releases/tag/vturnip_mrpurple_T30-toasted.adpkg
    file      turnip_mrpurple_T30-toasted.adpkg.zip
    bytes     3713730
    sha256    f65b2d3353fd4aa7190bb5426b94468e99ffea7a58a830bc0c4651db89353227

Its two members, which `DriverBridge.prepareBundle` checks by size and hash
before it will use either of them:

    346       meta.json         bf6c432ffd05a254a9531920f6ec826abb2bcf91c52a0c5467167a0ee1940f73
    18869912  vulkan.purple.so  1d80dfa019659b008e4669311db5b1e4a02af59ff1d5458a98e2f5fe18ed013b

`meta.json` declares Mesa 26.3.0-T30-1.4.359 and `minApi` 30, which is why T30 is
refused below Android 11.

The blob is committed rather than fetched at build time. `libmain.so` is already
in this repository at 86 MB, so 3.5 MB of driver is the lesser complication, and
a build that needs no network is worth more here than a smaller checkout.

Turnip is part of Mesa (MIT). The licence text ships in the APK at
`assets/third-party/NOTICE.txt` and is reachable from the driver screen.
