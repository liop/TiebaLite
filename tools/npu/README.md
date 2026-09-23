# SM8635 NPU 测试

已跑通的完整 U8 QNN 模型见 [MNIST-12 SM8635 context 与实测](qnn-mnist/README.md)：
在 4 MiB VTCM 目标下编译，手机 HTP/CDSP 上 200 张测试图与原始 ONNX CPU
预测全部一致。以下是此前的 GenieX/GGML 算子测试记录。

`ggml-op-test.cpp` 使用确定性的输入，在同一台 Android 设备上分别通过 GGML CPU 与
`HTP0` 执行 Add、Mul、ReLU、FP32/FP16/Q4_0 MatMul、RMSNorm 和 Softmax。它逐元素报告最大/平均绝对误差、
最大相对误差、非有限值数和超过各类型误差阈值的元素数。进程在有失败或没有任何 HTP 算子运行时
返回非零状态。这个程序测试 GenieX 随附的 ggml Hexagon 后端，不是独立的 QNN 或手写
HVX/VTCM kernel 测试。

## 本次环境

- 手机：vivo V2352A，SM8635，Android 16；通过 `ssh mini` 上的 USB ADB 访问。
- 运行时：GenieX v0.7.0 Android 包；`libggml-hexagon.so` SHA-256 为
  `3d9116bd02132fba3d302ee6c245b5ca103e294a25e732b31f443ae652107f8c`。
- NDK：mini 上的 `/Users/pk/Library/Android/sdk/ndk/27.1.12297006`。
- 头文件：GenieX v0.7.0 子模块固定的 llama.cpp
  `4ff829ec2e2f526aa6afba529eebbfb3ef1f95ec`；请使用此版本与包内 GGML 库编译。
- 设备运行时目录：`/data/local/tmp/geniex-v0.7.0`。

源码传到 mini 后，在 mini 上编译并通过其 ADB 推送：

```sh
ssh mini '
  mkdir -p /private/tmp/npu-op-test/llama.cpp
  cd /private/tmp/npu-op-test/llama.cpp
  git init
  git remote add origin https://github.com/ggml-org/llama.cpp.git
  git fetch --depth=1 origin 4ff829ec2e2f526aa6afba529eebbfb3ef1f95ec
  git checkout FETCH_HEAD
'
scp tools/npu/ggml-op-test.cpp mini:/private/tmp/npu-op-test/
ssh mini '
  cd /private/tmp/npu-op-test
  NDK=/Users/pk/Library/Android/sdk/ndk/27.1.12297006
  LIB=/private/tmp/npu-model-test/geniex-bench-android-arm64-v0.7.0/lib/llama_cpp
  "$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang++" \
    -std=c++17 -O2 -I llama.cpp/ggml/include ggml-op-test.cpp \
    -L "$LIB" -lggml -lggml-base -o ggml-op-test
  adb -s 10AE8C33T5002ZD push ggml-op-test /data/local/tmp/ggml-op-test
'
```

手机上执行（首次不设置 `GGML_HEXAGON_NHMX`；随后设置为 0 对照）：

```sh
ssh mini 'adb -s 10AE8C33T5002ZD shell '\''
  cd /data/local/tmp/geniex-v0.7.0
  export LD_LIBRARY_PATH=$PWD/lib/llama_cpp:$PWD/lib
  export ADSP_LIBRARY_PATH=$PWD/lib
  /data/local/tmp/ggml-op-test "$PWD/lib/llama_cpp"
'\'''
```

用 `export GGML_HEXAGON_NHMX=0` 放在程序调用前，再跑一次即可验证关闭 HMX 的结果。
同样的开关可用于现有 GenieX 全层 HTP：

```sh
ssh mini 'adb -s 10AE8C33T5002ZD shell '\''
  cd /data/local/tmp/geniex-v0.7.0
  export LD_LIBRARY_PATH=$PWD/lib:$PWD/lib/llama_cpp:$PWD/lib/qairt
  export GGML_HEXAGON_NHMX=0
  ./bin/geniex-bench --plugin llama_cpp --device npu --device-id HTP0 \
    -m /data/local/tmp/qwen35-4b/Qwen3.5-4B-Q4_K_M.gguf -c 256 \
    --prompt-file /data/local/tmp/qwen35-08b/prompts/tieba.txt \
    --accuracy --no-think -n 32 --power-mode burst
'\'''
```

## 2026-09-23 结果

默认配置日志确认 `HTP0 hwinfo: threads 4, hvx 4, hmx 1, vtcm 4 MB`，并成功创建
v73 HTP session。Add、Mul、ReLU、RMSNorm、Softmax 和非 32 倍数的 MatMul 与 CPU
一致。方阵边长为 32、64、96、128、192 时，MatMul 的 HTP 输出大量变为零，
`graph_compute` 仍返回成功。例如 32×32 有 1021/1024 个元素超出容差，最大绝对误差
5.3006992；31×31 和 33×33 均通过。

此版二进制识别的关闭开关是 `GGML_HEXAGON_NHMX=0`（可在库中查到该字符串）。加上该
环境变量后，日志确认 `hmx 0`，上述所有算子和尺寸都通过；192×192 MatMul 的最大绝对
误差为 `4.2915344e-6`。`GGML_HEXAGON_USE_HMX=0` 不被此版库识别，不能用于对照。

同一设备上，Qwen3.5-4B Q4_K_M 的 GenieX 全层 HTP 在默认 HMX 下对“用一句话介绍百度
贴吧。”输出无关外文；设置 `GGML_HEXAGON_NHMX=0` 后，日志显示 33/33 层卸载到所选
`HTP0`，输出合理中文。额外两题输出 `42`（17+25）和 `北京`（中国首都）。Qwen3.5-0.8B
Q4_0 在关闭 HMX 后也能正常回答贴吧提示。4B 贴吧提示单次性能为 TTFT 1539.7 ms、
预填充 11.7 tok/s、解码 4.0 tok/s；这不是正式性能基准。

手机上的原始日志：

- `/data/local/tmp/ggml-op-test-boundary.log`：默认 HMX 算子测试。
- `/data/local/tmp/ggml-op-test-no-hmx.log`：关闭 HMX 算子测试。
- `/data/local/tmp/qwen35-4b/default-hmx-tieba.log`：4B 默认 HMX 输出。
- `/data/local/tmp/qwen35-4b/no-hmx.log`、`no-hmx-math.log`、`no-hmx-capital.log`：4B
  关闭 HMX 后的三个提示。

结果将异常缩小到当前 ggml-hexagon/GenieX 包的 HMX 矩阵乘法路径；不能仅凭这些测试
判定 Hexagon 硬件、Qualcomm QNN runtime 或 VTCM 自身有故障。后续已在 mini
安装 Hexagon SDK 并重新编译 DSP 库，见下节。

## HMX MatMul 形状复现（2026-09-23）

使用 GenieX v0.7.0 所固定的 GGML 头文件重新编译上述程序，错误保持不变，排除了测试程序
与新版 GGML 头文件混用造成的直接 ABI 问题。FP16 和 Q4_0 权重的 32×32、64×64
矩阵乘法也输出几乎全零；`GGML_HEXAGON_VERBOSE=1` 将这些操作标记为 `hmx-tiled`，
31×31、33×33 则标记为 `hvx-tiled`。`GGML_HEXAGON_PROFILE=3` 的 trace 出现
`HMX_COMP` 事件，但仅能证明 HMX worker 被执行，不能证明 tile 输入、硬件计算或写回正确。

非方阵扫测按 GGML 的 `weights[K,N] × activations[K,M] → output[N,M]` 记为
`[M,K,N]`。`[1,32,32]`、`[2,32,32]`、`[4,32,32]` 通过；从
`[8,32,32]` 到 `[33,32,32]` 输出几乎全零。`[32,31,32]`、
`[32,33,32]`、`[32,32,31]`、`[32,32,33]` 都通过。
`[1,32,32]` 的 Q4_0 测试也通过。因此异常仅在 K、N 均为 32 倍数且
M 达到 HMX 选择阈值时出现。

改变 `GGML_HEXAGON_NHVX=1/2/3` 或 `GGML_HEXAGON_HOSTBUF=0` 均未修复
`[32,32,32]`。在这台手机上不要把 `graph_compute` 成功或 HMX trace 事件当成
计算正确的证据。

## GenieX v0.7.0 HMX DSP 侧排查（2026-09-23）

在 mini 的 Lima VM 内，使用 Snapdragon 官方 `arm64-android:v0.7` 镜像所含
Hexagon SDK 6.6.0.0 / Tools 19.0.07，按 GenieX v0.7.0 固定源码
`4ff829ec2e2f526aa6afba529eebbfb3ef1f95ec` 重新编译 v73 DSP 库。
将新库单独放在手机 `/data/local/tmp/geniex-hmx-baseline`，通过
`ADSP_LIBRARY_PATH` 只加载该库；32×32 F32/F16/Q4_0 仍几乎全零。
新版上游 llama.cpp `e6ab7c1a41054a888ada952eab4c886444c2f5ad`
的 v73 DSP 库也复现相同问题，因此直接升级源码不足以修复。

DSP 侧隔离实验：

- HMX 锁返回 0，`HMX_COMP` worker 实际进入。
- 在 HMX 前后读取 VTCM，激活与反量化权重首元素均为非零；把它们经现有输出
  转换路径送回 CPU，也能读到正确的首元素。人为在 HMX 后写入 FP16 1，CPU
  正确读到 1；在 HMX 前写入则被 HMX 覆盖为 0。
- 在同一 HMX worker 内把激活和权重 32×32 tile 全写为 1，乘法输出仍为 0。
  HMX 的 bias load/store 能把 FP16 1 写回，因此并非整个 HMX 指令集不可用。
- 分离 HMX accumulator 转换与输出存储、修正激活指令参数、去掉 `:deep`、
  改用官方示例的独立 HMX 指令、将 DSP 编译改为 `-O0 -fno-lto`，均未使
  MatMul 得到非零结果。v73 上尝试 HMX v2 电源请求导致会话启动失败。
- 去掉 HMX 队列等待时的 `qurt_hvx_unlock()` 会使会话无法启动，不是可用修复。

目前没有经过数值测试的 HMX 修复。该机继续使用
`GGML_HEXAGON_NHMX=0` 保证模型正确；它仍使用 HTP/HVX，但之前的性能测试
低于 GPU。上述现象只能定位到此设备上的 GenieX 原生 FP16 HMX MatMul
执行链，尚不能断言硬件、固件或 SDK 的单独责任。下一步需要用独立于
ggml-hexagon 的 Qualcomm HMX/QAIRT 算例在同一手机交叉验证。

### ACC / CVT 分段探针

应把“输出全零”与“ACC 全零”分开。Qualcomm 公开的 HMX ISA 将 ACC 经
`cvt.hf = acc(...)` 转入 CVT，再由 `mxmem(...)=cvt` 写到 VTCM；没有找到
可不经过 CVT 而把原始 ACC 直接存入内存的指令。因而现有输出不是原始 ACC dump。

在 mini 上使用干净重编的隔离 DSP 库，对 `[8,32,32]`、`[32,32,32]`
执行以下探针：

| 探针 | 首元素读回 | 说明 |
| --- | ---: | --- |
| HMX 后用 HVX 将 VTCM scale 拷到输出 | 1 | scale/bias 缓冲中的预期 FP16 数据可读 |
| `bias=mxmem2` 后以 `mxmem2=bias` 回写，再读 bias 字段 | 1 | HMX bias load/store 生效 |
| 输出 bias 设为 1，使用显式 `cvt.hf=acc(0)` + `mxmem=cvt` | 0 | 转换/写回链没有反映 bias |
| `mxclracc.hf` 后**跳过乘法**，保持输出 bias=1，再 CVT/写回 | 0 | 即使不执行 MMA，CVT/写回仍异常；不能由此推断 ACC 原值 |
| 同上，改用保留 ACC 控制位或输出 spatial mask `0x700` | 0 | 两种参数变化均无效 |

这些探针把当前可证实的异常推进到 `ACC → CVT → mxmem` 链路；原始 ACC
究竟为零还是非零仍未知。实验库都位于手机 `/data/local/tmp/geniex-hmx-*`，
原始 GenieX 库未覆盖。

随后构建了[独立 v73 最小样例](hmx-v73-minimal/README.md)，不经过
GenieX/GGML。4 MiB VTCM、HMX 锁和 bias load/store 均验证成功；清 ACC
后设置 output bias=1 并执行 CVT/写回，整个 32×32 输出 tile 仍为零。
加入全 1 tile MMA 或改用 `:after.hf` 也全零。故当前应先交叉验证
CVT/写回路径，再判定 ACC/MMA；这些输出不能当作原始 ACC dump。

独立 [QNN HTP FP16/FP32 对照](qnn-htp-matmul/README.md)进一步发现：
FP32 Add/MatMul、U8 Add/MatMul 和双向 FP16 Cast 通过，FP16 Add/MatMul
均 1024/1024 输出为零。这把调查范围扩大到设备上的 FP16 HTP 算术路径，不能仅按
GenieX 的 HMX kernel 代码缺陷处理；QNN 的内部 HMX/HVX 选择仍未确认。

关闭 HMX 后与 CPU/OpenCL 的多模型速度对照见
[`docs/htp-v73-model-evaluation.md` 第 15 节](../../docs/htp-v73-model-evaluation.md)。
