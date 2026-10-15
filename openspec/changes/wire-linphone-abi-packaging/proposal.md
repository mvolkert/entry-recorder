# Proposal

## Why

Only `jni/arm64-v8a/liblinphone.so` is committed and `jni/` is not wired into `sourceSets`/`jniLibs.srcDir`,
so native packaging is unclear and blind `abiFilters` could exclude devices. Build config change, no
behaviour delta.

## What Changes

- Determine the real ABI packaging story from a release bundle, then wire `jniLibs` correctly (or document
  that only arm64-v8a is supported and gate the app accordingly).

## Capabilities

`skip_specs: true`.

## Impact

- Code: `app/build.gradle.kts`, bundled `liblinphone.so` ABIs.
- Prerequisite: inspect a release bundle's native libs before touching `abiFilters`.
