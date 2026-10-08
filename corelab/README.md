# CoreLab

CoreLab is a standalone, deterministic certification harness for a small
Libretro-compatible core boundary. It intentionally contains no ROMs, BIOS
files, copyrighted assets, network fetches, or Android-module dependencies.

The checked-in fixture is generated in memory from a seed in
`manifest/dummy.profile`. The dummy shared library is a test double, not an
emulator core.

## Build and run with CMake

```sh
cmake -S corelab -B corelab/build
cmake --build corelab/build
ctest --test-dir corelab/build --output-on-failure
```

The certification report is written to `corelab/build/report/` by the test.
The report is intentionally split into bounded artifacts:
`capability-report.json`, `capabilities.json`, `run.json`, `events.jsonl`,
`video-checkpoints.json`, `audio-checkpoints.json`, `persistence.json`,
`crash.json`, and `environment.json`.

## Minimal host build without CMake

This is useful on a machine that only has a C++17 compiler:

```sh
mkdir -p corelab/build
c++ -std=c++17 -fPIC -shared -Icorelab/include \
  corelab/src/dummy_core.cpp -o corelab/build/libcorelab_dummy.dylib
c++ -std=c++17 -Icorelab/include corelab/src/corelab_runner.cpp -ldl \
  -o corelab/build/corelab_runner
corelab/build/corelab_runner \
  --profile corelab/manifest/dummy.profile \
  --core corelab/build/libcorelab_dummy.dylib \
  --report-dir corelab/build/report
```

On Linux, use `libcorelab_dummy.so` instead of the `.dylib` suffix.

## Profile and report contract

Profiles are deterministic `key=value` manifests. The fixture is synthesized
from `content.seed` and `content.bytes`; it is never read from the repository.
The profile pins the input trace, frame count, persistence requirements, and
golden SHA-256 digests. `capability-report.schema.json` is the machine-readable
report schema for downstream validation.

The runner checks:

- dynamic symbol/API/lifecycle order;
- single-thread entry and callback confinement;
- environment negotiation and explicit unsupported commands;
- software video geometry/pixel format/pitch and deterministic frame digest;
- deterministic input poll/state traces;
- batch audio structure and deterministic audio digest;
- native save RAM round-trip through a private temp-file/replace sequence;
- save-state serialize/restore replay;
- not-applicable persistence when a core reports zero-size regions;
- bounded, redacted evidence artifacts.

## CI wiring

The coordinator can add this repository-local job after checkout:

```yaml
corelab:
  script:
    - cmake -S corelab -B corelab/build -DCMAKE_BUILD_TYPE=RelWithDebInfo
    - cmake --build corelab/build --parallel
    - ctest --test-dir corelab/build --output-on-failure
  artifacts:
    when: always
    paths:
      - corelab/build/report/
```

For a matrix of exact core artifacts, invoke `corelab_runner` once per
`(artifact, ABI, profile)` and retain each report under a unique directory.
The runner exits nonzero for any failed required capability or deterministic
oracle. A production core must provide its own adapter/manifest and must be
approved for licensing and distribution separately; this dummy fixture does
not certify any third-party core.
