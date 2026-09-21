# LiteRT-LM Android OpenCL runtime

`litertlm-android-opencl-0.17.1.aar` is based on the official
`com.google.ai.edge.litertlm:litertlm-android:0.17.1` AAR. Its Android arm64
JNI library was rebuilt from the `v0.17.1` LiteRT-LM tag with dynamic LiteRT
linking enabled:

```shell
bazelisk build \
  --config=android_arm64 \
  --enable_platform_specific_config \
  --define=litert_runtime_link_mode=dynamic \
  //kotlin/java/com/google/ai/edge/litertlm/jni:litertlm_jni
```

The arm64 AAR contains:

- `liblitertlm_jni.so`
- `libLiteRt.so`
- `libGemmaModelConstraintProvider.so`
- `libLiteRtOpenClAccelerator.so`
- `libLiteRtTopKOpenClSampler.so`

The generic and WebGPU accelerator libraries are intentionally omitted. On
Android, LiteRT tries those before OpenCL; including them would select the
Vulkan/WebGPU path that cannot allocate Gemma 4 E4B's 160 MiB tensor on the
tested Adreno 735 driver (128 MiB maximum storage buffer binding).

The x86_64 JNI entry is unchanged from the official AAR and remains available
for emulator development. The custom OpenCL runtime is arm64-only.

LiteRT-LM 0.17.1 publishes its JVM API as Java 21 class files, so a clean build
must run Gradle with JDK 21. TiebaLite's source and target compatibility remain
Java 11.

Source: <https://github.com/google-ai-edge/LiteRT-LM/tree/v0.17.1>

Original Maven AAR SHA-256:

```text
a8aeaa6b128e9b0f1c7fe5e620932a7030cfff1ac9c0dc133d8c79711de5cf4d
```

OpenCL AAR SHA-256:

```text
7221ace77379b6dc463f79e678378bcb74c2a1894ba99dd650fcaccbcfdaac93
```
