# sherpa-onnx AAR — how to get it

Upstream ships sherpa-onnx for Android only as a GitHub release asset.
There is no Maven coordinate. The build expects this file here:

    app/libs/sherpa-onnx-static-link-onnxruntime-1.13.3.aar

Download it with:

    curl -L -o app/libs/sherpa-onnx-static-link-onnxruntime-1.13.3.aar \
      https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.3/sherpa-onnx-static-link-onnxruntime-1.13.3.aar

Expected: 38,398,784 bytes. Verify the size after downloading; the AAR is
gitignored and never committed. CI performs this download step before
building (see .github/workflows/android.yml).

Why this variant: "static-link-onnxruntime" bundles ONNX Runtime inside the
AAR, so no separate native dependency is needed. It ships .so files for
arm64-v8a, armeabi-v7a, x86 and x86_64. Slice 1 ships a universal APK;
ABI splits are a later packaging decision (see docs/risks.md).
