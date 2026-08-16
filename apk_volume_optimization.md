# TiebaLite 打包体积优化总结

TiebaLite 项目能将打包体积做到极其精简，主要得益于在 Android 构建、架构设计以及底层依赖上的极致优化。以下是分析总结出的核心优化点：

## 1. 原生代码与资源的极致“瘦身” (R8/ProGuard)
在发布版本的构建流 (`release` build type) 中，项目充分利用了构建工具的压缩能力：
*   **代码混淆与压缩 (`isMinifyEnabled = true`)**: 启用了 R8，深度排查并移除了所有未被实际调用的代码（包括第三方库中的无用类和方法）。
*   **资源压缩 (`isShrinkResources = true`)**: 配合代码压缩，所有在代码中失去引用的 XML、PNG 等静态资源文件，均会在最终打包前被硬移除，最大程度缩减了无用资源占用的体积。

## 2. 拥抱现代声明式 UI 与架构设计
项目全面转向了 **Jetpack Compose** 声明式 UI，并抛弃了传统视图体系的大量历史包袱：
*   **零 XML 布局负担**: 传统 Android 开发中需要编写繁杂的 XML 布局文件，并在代码中生成大量的 `ViewBinding` 或 `DataBinding` 模板类。Compose 的全 Kotlin 的描述方式消除了这些海量文件的生成。
*   **组件极致复用**: Compose 其自身的底层引擎组合机制大大减少了自定义 `Fragment`/`Activity` 的数量，也降低了代码库整体的臃肿程度。

## 3. 高效的数据模型代码生成工具
在网络通信的数据交换方面（尤其是处理百度贴吧的 API），项目进行了特殊优化：
*   **Square Wire 替代官方 Protobuf 编译器**: 传统官方的 `protoc` (即使是 lite 版本) 生成的 Kotlin/Java 类会包含极其庞大的模板代码。而项目采用了 Square 专门为 Android & Kotlin 平台优化的 **Wire** (`com.squareup.wire`)，大幅缩减了 Proto 数据模型生成的代码量，通常能节省数 MB 的应用包体积。
*   **轻量级序列化**: 在需要 JSON 的地方启用了 `kotlinx.serialization`，比传统的反射序列化库更轻量级。

## 4. 全面采用矢量图 (Vector/WebP)
*   **动态矢量图支持 (`vectorDrawables.useSupportLibrary = true`)**: 放弃了为各种屏幕分辨率（hdpi, xhdpi, xxhdpi 等）分别提供不同尺寸的位图（如 PNG 格式图片）。全面转向使用基于 XML 路径绘制的矢量图标不仅保证了所有分辨率下的清晰度，更成百倍地节省了存储空间。现代图片加载库 **Sketch** 也非常契合这种模式下对其他图片格式（如 WebP）的高效加载与缓存处理。

## 5. 零“原生包袱”(No NDK/JNI Payload)
*   **纯粹的 DEX 打包**: 相比于许多如今集成了大量音视频、加密或跨平台底层库的大型 App，本项目依赖中没有包含庞大的 NDK C/C++ 共享对象库（`.so` 文件）。通常一个 App 为了适配不同 CPU 架构需要包入至少两套（如 `armeabi-v7a` 和 `arm64-v8a`，即便是 App Bundle 时代也相当可观）。纯 Kotlin 软实现意味着只有一个平台无关的 DEX 文件。

## 6. 精简打包最终产物 (Packaging Options)
*   **排除冗余文件**: 在 `build.gradle.kts` 的打包环节，项目明确排除了不需要发布给任何用户的附属文件：
    ```kotlin
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    packaging.resources.excludes += "DebugProbesKt.bin"
    ```
    去除了各类开源协议声明文件以及不需要的 Kotlin 调试探针二进制文件，确保发布的版本是干干净净的纯执行体。

---
**总结概括：**
TiebaLite 做到体积极致小巧的秘诀在于：借助现代工具链剥离 XML 布局历史包袱、放弃庞大的原生 C/C++ 库与臃肿的官方数据序列化生成库，并配合深度启用的代码/资源压缩以及全矢量图架构，将冗余做到了最低。
