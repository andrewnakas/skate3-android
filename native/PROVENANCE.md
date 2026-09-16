# The Vulkan driver proxy

`driver_proxy.cpp`, `exports.map` and `CMakeLists.txt` are byte-identical to
`AlanConstantino/skate3-pocket` at `c9868d4`, deliberately, so that a future
change there reads as a diff rather than an archaeology exercise. Their SHA-256
sums are:

    2430eb8c499ccc684f8c44e7efea2b7526c76ed3ac4cd4cd0818470727a045cb  driver_proxy.cpp
    02d37a6ec9922049acb4d3e0967c956613d06d07be52e9e44596491a2982c34a  exports.map
    ae87f64e579da0afc9effade31b6f1caf795cb5bc80ace2af1b9ba9a9a2270cd  CMakeLists.txt

`vendor/libadrenotools` is <https://github.com/bylaws/libadrenotools> at
`8fae8ce254dfc1344527e05301e43f37dea2df80`, with its `lib/linkernsbypass`
submodule (<https://github.com/bylaws/liblinkernsbypass>) at
`aa3975893d83ef1bc84c321ec60c65fbf1287887`. Git metadata was dropped. The
upstream `tools/` directory is **not** vendored: it holds the qtimapper-shim and
the blob patcher, neither of which the top-level `CMakeLists.txt` references.

## How it takes over

`libmain.so` does not link Vulkan. The engine opens it by bare name at runtime -
`rex::platform::lib_names::kVulkanLoader` in
`third_party/rexglue-sdk/include/rex/platform/dynlib.h`, used from
`src/ui/vulkan/vulkan_instance.cpp`. So if the app calls `System.load` on its own
`libvulkan.so` out of `nativeLibraryDir` *before* SDL loads the engine, the
engine's later `dlopen("libvulkan.so")` hands back that same already-loaded
object. `nativeInit` checks exactly this with `RTLD_NOLOAD` and refuses to
proceed if something else got there first, rather than guessing.

Only `ADRENOTOOLS_DRIVER_CUSTOM` is used. The GPU turbo, guest-memory mapping and
BCn options are left off.

## What the build has to hold to

- Native libraries must be **extracted**: `useLegacyPackaging = true`. The
  adrenotools hook libraries have to be real files in `nativeLibraryDir`, which
  an APK-mapped build never creates. `nativeInit` reports this rather than
  failing obscurely.
- A custom driver must live in app-private `/data` storage.
- The selection is fixed for the life of the process; changing it means a
  restart.
- The custom path builds a surface-free instance first and refuses to continue
  unless every physical device reports `VK_DRIVER_ID_MESA_TURNIP`. A failure is
  reported as a failure - it never quietly falls back to the system driver.

Unlike the fork, this app defaults to the system driver and **does not load the
proxy at all** unless a custom driver is selected; see the comment on
`DriverBridge.initialize`. The proxy's own system-driver branch is therefore
unreachable here. It is kept so the file stays identical upstream.
