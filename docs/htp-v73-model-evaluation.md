# HTP v73 端侧模型调研与实测记录

本文记录 TiebaLite 在 vivo V2352A 上测试 LiteRT-LM、QNN/HTP、GenieX 和 GGUF
模型的全部方向、命令、结果与当前结论。文档中的“可运行”不仅要求进程返回成功，
还要求模型输出正确；仅能建立 HTP session、但输出乱码或重复 token，不视为可用。

**2026-09-23 更新：**第 14 节的算子级测试定位到此版 GenieX 随附 ggml-hexagon 的
HMX MatMul 路径异常。设置 `GGML_HEXAGON_NHMX=0` 后，Qwen3.5-4B 全层 HTP 在三类
提示上均得到正确输出。前文关于“全层 HTP 不可用”的结论仅适用于默认 HMX 配置。

## 1. 测试环境

| 项目 | 值 |
| --- | --- |
| 手机 | vivo V2352A / PD2352 |
| Android | 16 |
| SoC | Qualcomm SM8635，QNN SoC model 68 |
| HTP | v73，4 HVX、1 HMX、4 MiB VTCM |
| adb 主机 | `ssh mini` |
| adb 路径 | `/Users/pk/Library/Android/sdk/platform-tools/adb` |
| QAIRT | 2.47 |
| GenieX | v0.7.0 Android benchmark archive |
| 模型下载 | 优先 `https://hf-mirror.com/` |

mini 的 `~/.zshenv` 已加入：

```sh
export PATH="/Users/pk/Library/Android/sdk/platform-tools:$PATH"
```

确认手机连接：

```sh
ssh mini 'adb devices -l'
```

所有手机测试都经 mini 的 USB adb 执行，不使用本机 adb。

## 2. LiteRT-LM 0.17.1 与 GPU 路线

已在 mini 按官方源码方式构建 `litert_lm_main` 和 Android arm64 动态 LiteRT
运行库，并验证 OpenCL GPU 路线。项目集成使用的自定义 AAR、构建参数和库清单见
[`localai/libs/README.md`](../localai/libs/README.md)。

测试过 WebGPU/Vulkan 路线。被测 Adreno 735 驱动的 storage buffer 单绑定上限为
128 MiB，而 Gemma 4 E4B 需要分配约 160 MiB tensor，因此该路径不能完成模型加载。
当前 Android AAR 有意只保留 OpenCL accelerator，避免运行时优先选中不可用的
WebGPU/Vulkan accelerator。

模型路径：

```text
# TiebaLite
/sdcard/Android/data/xyz.liop.tieba.pos/files/models/gemma-4-E4B-it.litertlm

# AI Edge Gallery 原始下载
/sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_4_E4B_it/28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm
```

## 3. 公开 NPU 制品调研

### 3.1 Qwen3 / Qwen3.5

- Qwen3.5-4B 是 1B–4B 范围内优先测试的能力候选，但目前没有找到可直接下载、
  明确针对 SM8635/HTP v73/4 MiB VTCM 的 `.litertlm`、QNN context binary 或
  AI Hub AOT 制品。
- Qwen3-4B-Instruct-2507 有公开 QNN HTP AOT，但目标是 v75/v79，不是 v73。
- Qwen3.5-2B 在 Qualcomm AI Hub 有模型条目，但没有发现公开的 v73 即用制品。
- Qwen3-1.7B 有标注 v73 的 ExecuTorch 仓库，但仓库说明 PTE 文件未上传，无法直接
  用 adb 复现。

### 3.2 Llama

- 找到名为 `Llama-3.2-3B-Instruct-QNN-HTP-Z4` 的仓库；实际下载文件头为 `GGUF`，
  不是 QNN context binary。
- QAIRT 2.47 源码/配置显示该包使用的 `QnnGenAiTransformer` 映射到 `qnn-cpu`。
  实测成功的是 CPU，不是 HTP NPU：预填充 47.27 tok/s，解码 14.89 tok/s，
  TTFT 0.677 s。
- 另有 Llama 3.2 3B v75/v79 AOT 制品，但不能作为 v73 即用制品。

### 3.3 Gemma 3 / Gemma 4

- 公开的 Gemma 3 NPU `.litertlm` 主要面向 SM8750/HTP v79。
- 通用 `.litertlm` 可用于 CPU/GPU，但不等同于针对本机 v73 编译好的 NPU AOT。
- Gemma 4 E4B 已走通 LiteRT-LM CPU/OpenCL 方向；WebGPU/Vulkan 受上述 128 MiB
  buffer 限制。

### 3.4 Qwen2.5-1.5B v73 QNN AOT

下载并测试了公开的 `Qwen2.5-1.5B-Instruct-qnn`，其中 4 个 context binary 总计约
1.4 GiB，元数据明确为 `soc_model:60, dsp_arch:v73`。

补齐 `libQnnHtpNetRunExtensions.so` 后，FastRPC 与 v73 skel 均能成功打开，但
`QnnContext_createFromBinary` 失败：

```text
Request feature vtcm size with value 8388608 unsupported
err 0x138d / 5005
```

该 AOT 固化要求 8 MiB VTCM，而本机只有 4 MiB。修改 JSON 不能改变 context binary
内已编译的资源要求。这说明“同为 v73”仍不足以保证 AOT 可移植，SoC model、VTCM
和编译参数也必须匹配。

## 4. GenieX v0.7.0 通用 GGUF HTP 路线

### 4.1 运行环境

手机目录：

```text
/data/local/tmp/geniex-v0.7.0
```

关键文件：

```text
bin/geniex-bench
lib/libgeniex.so
lib/llama_cpp/libgeniex_plugin.so
lib/llama_cpp/libggml-hexagon.so
lib/llama_cpp/libggml-htp-v73.so
```

由于插件把 `ADSP_LIBRARY_PATH` 指向 `lib` 根目录，另复制了：

```sh
cp lib/llama_cpp/libggml-htp-v73.so lib/libggml-htp-v73.so
```

基础命令：

```sh
ssh mini 'adb shell '\''
cd /data/local/tmp/geniex-v0.7.0
export LD_LIBRARY_PATH=$PWD/lib:$PWD/lib/llama_cpp:$PWD/lib/qairt
./bin/geniex-bench \
  --plugin llama_cpp \
  --device npu \
  --device-id HTP0 \
  -m /data/local/tmp/MODEL.gguf \
  -c 256 \
  --prompt-file /data/local/tmp/prompt.txt \
  --accuracy \
  --no-think \
  -n 64 \
  --power-mode burst
'\'''
```

正确性提示词：

```text
用一句话介绍百度贴吧。
```

### 4.2 Qwen3.5-4B Q4_K_M

模型：`Qwen3.5-4B-Q4_K_M.gguf`，2,740,937,888 bytes。

纯 HTP 能真实建立 session，日志确认：

```text
HTP0 hwinfo: threads 4, hvx 4, hmx 1, vtcm 4 MB
HTP0 new session
```

32 prompt / 16 generation 的性能：

| 路径 | TTFT | 预填充 | 解码 | 正确性 |
| --- | ---: | ---: | ---: | --- |
| HTP | 615 ms | 52.35 tok/s | 5.71 tok/s | 失败，输出乱码 |
| CPU | 710.6 ms | 25.4 tok/s | 6.2 tok/s | 通过 |

CPU 正确输出说明模型文件和提示词本身有效。CPU+HTP hybrid 进程被系统以 exit 137
终止，判断为内存压力/OOM。

### 4.3 Qwen3-4B Q4_K_M

模型：`Qwen3-4B-Q4_K_M.gguf`，2,497,281,312 bytes。

| 配置 | TTFT | 预填充 | 解码 | 结果 |
| --- | ---: | ---: | ---: | --- |
| 全层 HTP，ctx 256 | 208.2 ms | 87.4 tok/s | 11.35 tok/s | 输出无关数学文本 |
| hybrid，ctx 1024 | - | - | - | exit 137 / OOM |
| hybrid，ctx 256 | - | - | - | exit 137 / OOM |
| 仅 4 层 HTP，ctx 256 | 494.8 ms | 36.5 tok/s | 3.34 tok/s | 重复“百度”token |

只要 Q4_K 层进入 HTP 就会破坏 logits，因此继续用 Q4_0 排除 K-quant 特有问题。

### 4.4 Qwen3-4B Q4_0

下载入口：

```text
https://hf-mirror.com/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_0.gguf
```

文件大小：2,375,773,472 bytes。手机路径：

```text
/data/local/tmp/qwen3-4b/Qwen3-4B-Q4_0.gguf
```

实测结果：

| 路径 | TTFT | 预填充 | 解码 | 正确性 |
| --- | ---: | ---: | ---: | --- |
| 全层 HTP，ctx 256 | 175.8 ms | 103.8 tok/s | 14.3 tok/s | 失败，重复标点 |
| CPU，ctx 256 | 377.7 ms | 47.7 tok/s | 8.7 tok/s | 通过 |

CPU 输出：

```text
百度贴吧是百度旗下的互动社区平台，用户可以在这里创建和参与各类兴趣主题的讨论论坛，进行交流与分享。
```

Q4_0 仍失败，说明问题不局限于 Q4_K kernel；当前证据更指向 GenieX v0.7.0
v73 HTP 后端对 Qwen3/Qwen3.5 架构或相关算子的数值兼容性问题。

## 5. 默认 HMX 配置下的阶段性判断（见第 14 节更新）

1. 本机 HTP v73 调用链已经走通：FastRPC、v73 skel、HTP session、HVX/HMX 和
   VTCM 信息均由设备日志确认，并非 CPU 冒充 NPU。
2. 默认 HMX 配置下，Qwen3.5-4B、Qwen3-4B 和 Llama 3.2 3B 的 GGUF 全层 HTP
   均能完成推理流程，但输出不正确。Qwen3/Qwen3.5 在限制为 3 层 HTP offload
   时通过了当时的正确性测试。关闭 HMX 后的更新结果见第 14 节。
3. 公开 Qwen2.5-1.5B v73 QNN AOT 因要求 8 MiB VTCM，不能在本机 4 MiB VTCM
   上创建 context。
4. 目前没有确认到可直接下载且与 SM8635/v73/4 MiB VTCM 匹配的 1B–4B QNN
   context binary、AI Hub AOT 或 NPU `.litertlm`。
5. 要获得可靠 NPU，需要使用 Qualcomm AI Hub/QAIRT 针对 SM8635 与 4 MiB VTCM
   重新编译模型，或等待厂商发布完全匹配的制品。

## 6. 后续测试队列

- [x] Llama 3.2 3B Instruct Q4_0：验证 GenieX v73 HTP 后端是否只对 Qwen 架构异常。
- [x] Llama 仍错误，说明问题不是 Qwen 架构独有。
- [ ] 测试 HTP 支持矩阵中的更小官方参考模型，确认后端整体正确性。
- [ ] 搜索或生成针对 SM8635、HTP v73、4 MiB VTCM 的 AI Hub/QAIRT AOT。
- [ ] 将最终可用模型接入 TiebaLite，并与 OpenCL GPU 路线做速度、内存、功耗对比。

## 7. 参考资料与制品

- [Qualcomm GenieX](https://github.com/qualcomm/GenieX)
- [GenieX run notes](https://github.com/qualcomm/GenieX/blob/main/notes/run.md)
- [Qwen3-4B GGUF](https://huggingface.co/Qwen/Qwen3-4B-GGUF)
- [Qwen3.5-4B](https://huggingface.co/Qwen/Qwen3.5-4B)
- [Qwen3-4B v75/v79 QNN HTP AOT](https://huggingface.co/huluhuluu/qwen3-4b-instruct-2507-mllm-qnn-htp)
- [Qwen3-1.7B v73 ExecuTorch 仓库](https://huggingface.co/anan19990108/qwen3-1.7b-executorch-w4a16-ctx4096)
- [Qwen2.5-1.5B v73 QNN 制品](https://huggingface.co/keitokei1994/Qwen2.5-1.5B-Instruct-qnn)
- [Llama 3.2 3B v75/v79 QNN HTP AOT](https://huggingface.co/huluhuluu/llama-3.2-3b-instruct-mllm-qnn-htp)
- [名为 QNN HTP、实际为 GGUF 的 Llama 仓库](https://huggingface.co/zededa/Llama-3.2-3B-Instruct-QNN-HTP-Z4)
- [Gemma/LiteRT-LM NPU 相关讨论](https://github.com/google-ai-edge/LiteRT-LM/issues/3508)

## 8. Llama 3.2 3B Q4_0 实测

模型：`unsloth/Llama-3.2-3B-Instruct-GGUF` 的
`Llama-3.2-3B-Instruct-Q4_0.gguf`，1,921,909,184 bytes。

下载过程中 hf-mirror 跳转后的直连 TLS 被 mini 上的 Clash TUN 中断。显式使用
`http://127.0.0.1:20700` 后可以下载，但单连接较慢，因此使用 8 路 HTTP Range。
第一次合并虽然总长度正确，但首段在旧 curl 断流后形成稀疏零数据，CPU 加载时报：

```text
gguf_init_from_reader: key 25 is empty
gguf_init_from_reader: failed to read key-value pairs
```

重新下载首段后，从 Hugging Face API 取得 LFS SHA-256 并完成校验：

```text
18eafaccbd0a63d9f2f5bd9d76e718ff57d8fc6e147d1f753f3975ac4a8938f0
```

其中下载响应的 `376b51e2...b814` 是 Xet hash，不应当作 SHA-256 使用。

正确文件的测试结果：

| 路径 | TTFT | 预填充 | 解码 | 正确性 |
| --- | ---: | ---: | ---: | --- |
| CPU，ctx 256 | 569.7 ms | 77.3 tok/s | 10.9 tok/s | 通过 |
| 全层 HTP，ctx 256 | 132.4 ms | 333.9 tok/s | 18.4 tok/s | 失败，输出无关英文 |

CPU 输出：

```text
百度贴吧是一种在线社区，用户可以通过点赞和回复等方式参与讨论，分享信息和互相交流。
```

HTP 日志再次确认不是 CPU 回退：

```text
HTP0 hwinfo: threads 4, hvx 4, hmx 1, vtcm 4 MB
HTP0 new session
```

这次测试把问题范围从 Qwen 架构扩大到 Llama：GenieX v0.7.0 的 GGUF HTP v73
路径在本机能够运行且性能明显提升，但当前数值结果不可靠。

## 9. 官方参考模型 Qwen3.5-0.8B Q4_0

Qualcomm GenieX 的发布检查清单指定 Qwen3-0.6B 和 Qwen3.5-0.8B 做
CPU/GPU/NPU 质量验证；官方模型文档也推荐 Q4_0 以获得最佳 Hexagon NPU 支持。
因此下载 `unsloth/Qwen3.5-0.8B-GGUF` 的 Q4_0 做参考测试。

```text
文件大小：507154688 bytes
SHA-256：444406ddd926550c724ec18d5120a9d40ded44908a063b0e66e9a7e5464c652c
手机路径：/data/local/tmp/qwen35-08b/Qwen3.5-0.8B-Q4_0.gguf
```

全层 HTP 仍产生无关西班牙语：

| 路径 | TTFT | 预填充 | 解码 | 正确性 |
| --- | ---: | ---: | ---: | --- |
| CPU | 279.1 ms | 64.7 tok/s | 16.3 tok/s | 通过 |
| 全层 HTP | 131.8 ms | 138.3 tok/s | 26.3 tok/s | 失败 |
| hybrid | 2175.4 ms | 8.3 tok/s | 25.7 tok/s | 失败 |

随后测试部分 offload。只使用贴吧提示时，1–7 层看似都能产生合理文本，8 层首次
明显失败；但加入算术、首都和贴吧三类提示交叉验证后，7 层在算术题输出
`composite`，在首都题输出 `之巅`。因此不能用单条自然语言输出来认定稳定阈值。

`-ngl 1` 的三题输出与 CPU 完全一致：

| 提示 | CPU | 1 层 HTP | 判断 |
| --- | --- | --- | --- |
| 17 + 25 | 52 | 52 | 一致；错误来自 0.8B 模型能力，而非 HTP 漂移 |
| 中国首都 | 北京 | 北京 | 一致 |
| 介绍贴吧 | 合理中文描述 | 合理中文描述 | 一致 |

这证明部分 HTP 可以保持数值行为，但较多层 offload 会跨过错误阈值。0.8B 不在本次
1B–4B 的目标范围内，只作为后端诊断参考。

## 10. Qwen3-4B Q4_0 部分 HTP 可用配置

使用算术、事实、中文描述三类提示对 CPU 与 1–4 层 HTP 进行对照：

| HTP offload | 算术 17+25 | 中国首都 | 贴吧描述 | 结论 |
| ---: | --- | --- | --- | --- |
| 0（CPU） | 42 | 北京 | 正确 | 基线通过 |
| 1 | 42 | 北京 | 正确，且与 CPU 逐字一致 | 通过 |
| 2 | 42 | 北京 | 正确 | 通过 |
| 3 | 42 | 北京。 | 正确 | 通过，推荐上限 |
| 4 | 42 | 替换字符乱码 | 无限重复“百度” | 失败 |

推荐 adb CLI 配置：

```sh
ssh mini 'adb shell '\''
cd /data/local/tmp/geniex-v0.7.0
export LD_LIBRARY_PATH=$PWD/lib:$PWD/lib/llama_cpp:$PWD/lib/qairt
./bin/geniex-bench \
  --plugin llama_cpp \
  --device npu \
  --device-id HTP0 \
  -m /data/local/tmp/qwen3-4b/Qwen3-4B-Q4_0.gguf \
  -c 256 \
  -ngl 3 \
  --prompt-file /data/local/tmp/qwen35-08b/prompts/tieba.txt \
  --accuracy \
  --no-think \
  -n 48 \
  --power-mode burst
'\'''
```

官方 benchmark 方式（1 次 warmup + 3 次测量，32 prompt + 32 generation）的中位数：

| 路径 | TTFT | 预填充 | 解码 |
| --- | ---: | ---: | ---: |
| CPU | 708.95 ms | 45.15 tok/s | 7.17 tok/s |
| HTP `-ngl 3` | 770.95 ms | 41.52 tok/s | 8.34 tok/s |

部分 HTP 让解码提升约 16.4%，但 TTFT 和预填充分别变慢约 8.7% 和 8.0%。因此它
证明了可用的 HTP v73 推理，却不是全面性能优化；是否用于产品应根据生成长度权衡。

## 11. Qwen3.5-4B Q4_K_M 部分 HTP

Qwen3.5-4B 是本次范围内模型能力最强的候选。全层 HTP 输出错误，hybrid 因内存压力
被系统以 exit 137 终止；改用有限层 offload 后得到可用结果。

| HTP offload | 算术 17+25 | 中国首都 | 贴吧描述 | 结论 |
| ---: | --- | --- | --- | --- |
| 0（CPU） | 42 | 北京 | 正确 | 基线通过 |
| 1 | 42 | 北京 | 正确 | 通过 |
| 2 | 42 | 北京 | 正确 | 通过 |
| 3 | 42 | 北京 | 正确 | 通过，当前推荐上限 |
| 4 | - | - | 重复 token | 失败（此前测试） |

在默认 HMX 配置下，当时验证的可用方案是：

```text
模型：Qwen3.5-4B Q4_K_M
运行时：GenieX llama_cpp
设备：HTP0
上下文：256（已验证值）
最大已验证正确 offload：-ngl 3
```

注意这不是全 NPU：绝大多数层仍在 CPU，3 层在 HTP v73。它满足 adb CLI 下实际
调用 NPU 且输出正确，但不能替代针对 SM8635/4 MiB VTCM 编译的完整 QNN AOT。

官方 benchmark 方式（1 次 warmup + 3 次测量，32 prompt + 32 generation）的中位数：

| 路径 | TTFT | 预填充 | 解码 |
| --- | ---: | ---: | ---: |
| CPU | 1805.17 ms | 17.74 tok/s | 2.22 tok/s |
| HTP `-ngl 3` | 1593.44 ms | 20.10 tok/s | 5.59 tok/s |

按中位数计算，3 层 HTP 的 TTFT 降低约 11.7%，预填充提升约 13.3%，解码提升约
151.6%。CPU 三轮解码在 2.15–4.59 tok/s 间波动，表明手机连续负载下温控影响明显；
HTP 三轮解码为 5.15–5.86 tok/s，波动更小。以上数据适合证明当前配置的方向性收益，
产品性能结论还应在冷机、固定电量与固定温度下重复采样。

## 12. 运行时与 AOT 工具限制

- mini 上的 GenieX Android tar 与 Qualcomm 官方 `latest stable` 资产大小和 ETag
  一致，大小为 91,575,933 bytes，服务器更新时间为 2026-09-18，已排除“本地包落后”。
- benchmark JSON 报告 QAIRT 2.45、llama.cpp `4ff829e`。
- GenieX 当前公开 Android 重点支持 v79/v81；公开 v73 硬件主要是 Snapdragon X 和
  QCS9075。SM8635 Android v73 不在相同的官方验证组合中。
- 当时 mini 未安装 Docker/Podman，未配置 Qualcomm AI Hub CLI，也没有
  `~/.qai_hub/client.ini`；后来已配置 Lima Linux VM 并完成第 17 节的 U8 模型编译。
- QAIRT 2.47 中 `qnn-genai-transformer-composer` 只有 x86_64 Linux/Windows 可执行
  文件，不能直接在 macOS mini 上运行。`qnn-context-binary-generator` 本身不能把
  已编译为 8 MiB VTCM 的公开 context binary 改造成 4 MiB。

生成完整、可靠的 NPU AOT 仍需要下面任一条件：

1. 为 mini 配置 Qualcomm AI Hub 凭据，并让 AI Hub 以 SM8635/SoC model 68 为目标
   编译；或
2. 在 mini 上安装 Linux 虚拟化运行 composer，并取得完整转换配置、权重与 QNN
   编译链，明确限制 VTCM 为 4 MiB。

## 13. adb 连接记录

mini 的 adb 已加入 PATH。长时间连续测试后 adb server 曾丢失设备；重启 server 后
手机重新枚举为 `unauthorized`；重新确认 USB 调试授权后恢复为 `device`，并完成了
Qwen3.5-4B 正式性能基准。该事件未影响已经落盘的正确性日志。

## 14. HMX MatMul 定位与全层 HTP 修复验证（2026-09-23）

使用仓库中的 [`tools/npu/ggml-op-test.cpp`](../tools/npu/ggml-op-test.cpp) 在同一台手机上
对照 GGML CPU 与 HTP0。默认配置下，日志报告 `hvx 4, hmx 1, vtcm 4 MB`，并成功
建立 v73 HTP session。FP32 Add、Mul、ReLU、RMSNorm、Softmax 都通过 `1e-3` 的绝对
误差阈值；31×31、33×33 MatMul 通过，但 32×32、64×64、96×96、128×128、192×192
MatMul 大量输出零值。例如 32×32 的最大绝对误差为 5.3006992，1021/1024 个元素
超出阈值。计算接口仍返回成功。

此版库支持的开关是 `GGML_HEXAGON_NHMX=0`。设置后，日志报告 `hmx 0`，所有上述算子
与尺寸通过，192×192 MatMul 最大绝对误差 `4.2915344e-6`。先前尝试的
`GGML_HEXAGON_USE_HMX=0` 不被此版库识别，不能作为关闭 HMX 的证据。

在 GenieX 全层 HTP（`ngl=-1`）中使用同一开关，Qwen3.5-4B Q4_K_M 显示 33/33 层
卸载到所选 HTP0 设备，对贴吧、17+25、中国首都三类提示分别输出合理中文描述、`42`、
`北京`。Qwen3.5-0.8B Q4_0 也能正常生成贴吧描述。4B 贴吧提示单次测得 TTFT
1539.7 ms、预填充 11.7 tok/s、解码 4.0 tok/s；默认 HMX 同提示仍输出无关外文。
这些是正确性验证，性能还需稳定温度与多轮采样。

当时的证据将异常定位到这份 ggml-hexagon/GenieX 包的 HMX 矩阵乘法路径，
不能仅据此断定 Qualcomm QNN runtime、Hexagon 硬件或 VTCM 本身异常。
随后独立的 [QNN HTP 精度对照](../tools/npu/qnn-htp-matmul/README.md)
发现 FP32、U8 Add/MatMul 与 FP16 Cast 通过，但 FP16 Add/MatMul 全零，
32×32 和 128×128 均如此。非均匀 0/1 输入的 U8 Add/MatMul 也逐项通过 CPU
参考值，提示本机 FP16 HTP 算术路径另有问题；仍未确认 QNN 算子内部是否走 HMX。
编译命令、参数和手机日志位置见 [`tools/npu/README.md`](../tools/npu/README.md)。

## 15. 关闭 HMX 后的 GenieX 性能对照（2026-09-23）

通过 `ssh mini` 的 USB ADB 在同一台 V2352A 上运行已有 GGUF 模型。使用 GenieX
v0.7.0 `llama_cpp` 插件、`-c 256 -p 32 -n 32 --warmup 1 -r 3 --power-mode burst`
和默认 seed 42；`-p` 产生固定数量的随机 token ID，以下数据是速度基准，不用于判断
回答质量。每个设备单独加载模型、单独运行；HTP 使用 `--device npu --device-id HTP0`
和 `GGML_HEXAGON_NHMX=0`，GPU 使用 `--device gpu --device-id GPUOpenCL`，CPU 使用
`--device cpu`。表中均为三轮测量的中位数，TTFT 越低越好，其余越高越好。

| 模型 | 后端 | TTFT ms | 预填充 tok/s | 解码 tok/s |
| --- | --- | ---: | ---: | ---: |
| Qwen3.5-4B Q4_K_M | CPU | 1801.6 | 17.78 | 4.12 |
| Qwen3.5-4B Q4_K_M | HTP0，HMX 关闭 | 1370.8 | 23.38 | 3.53 |
| Qwen3.5-4B Q4_K_M | OpenCL GPU | 首次生成时 exit 137，未完成基准 | — | — |
| Qwen3-4B Q4_0 | CPU | 756.4 | 42.32 | 8.47 |
| Qwen3-4B Q4_0 | HTP0，HMX 关闭 | 920.4 | 34.78 | 4.72 |
| Qwen3-4B Q4_0 | OpenCL GPU | 699.4 | 45.80 | 8.91 |
| Llama 3.2 3B Q4_0 | CPU | 535.5 | 59.78 | 10.04 |
| Llama 3.2 3B Q4_0 | HTP0，HMX 关闭 | 758.2 | 42.22 | 6.93 |
| Llama 3.2 3B Q4_0 | OpenCL GPU | 615.5 | 52.04 | 9.77 |
| Qwen3.5-0.8B Q4_0 | CPU | 445.1 | 72.06 | 29.27 |
| Qwen3.5-0.8B Q4_0 | HTP0，HMX 关闭 | 281.1 | 114.34 | 20.31 |
| Qwen3.5-0.8B Q4_0 | OpenCL GPU | 264.0 | 123.07 | 27.50 |

Qwen3.5-4B 的 HTP 预填充中位数比 CPU 高约 31%，但解码低约 15%。其 CPU 三轮解码
为 3.17–6.56 tok/s，HTP 为 2.38–4.12 tok/s，波动明显；不能据单次结果断言持续
收益。Qwen3-4B 和 Llama 3.2 3B 上，关闭 HMX 的 HTP 在预填充和解码均慢于 CPU
与可运行的 OpenCL GPU。0.8B 上 HTP 预填充快于 CPU，解码仍慢于 CPU/GPU。

另对 Qwen3.5-4B 做了 `-p 128 -n 32` 对照：HTP 预填充中位数 22.54 tok/s、
TTFT 5681.4 ms；CPU 为 24.62 tok/s、5199.7 ms。该组随机输入中 HTP 三轮均在
14 token 处遇到 EOS，CPU 三轮均生成 32 token，因此解码值不作为同长度速度对照。
随机输入可能让两条路径产生不同的停止位置；自然语言正确性仍以第 14 节的提示词测试
为准。

手机在测试过程中多个 thermal zone 的最高读数约 44–52.5 °C，运行顺序和温控会影响
小样本基准。这轮没有测功耗。原始 JSON 与日志保留在每个模型的手机目录下：
`perf-htp-hvx.json`、`perf-cpu.json`、`perf-gpu.json`（对应 `.log`）；Qwen3.5-4B
另有 `perf-htp-hvx-p128.json` 和 `perf-cpu-p128.json`。Qwen3.5-4B GPU 尝试仅有
`perf-gpu.log`，未生成有效 JSON。

目前 `GGML_HEXAGON_NHMX=0` 是正确性 workaround，但这些速度数据不足以把该 HTP
路径作为 CPU/GPU 的通用性能替代。要获得更高 NPU 速度，需要修复 ggml-hexagon 的
HMX MatMul 或使用针对本机编译且经正确性验证的 QNN AOT。

## 16. LiteRT-LM + Qualcomm delegate 试跑（2026-09-23）

在 mini 上使用已有的 LiteRT-LM v0.17.1 Android 构建和 QAIRT 2.47 Qualcomm
compiler/dispatch 插件，通过 `adb -s 10AE8C33T5002ZD` 部署到 V2352A。
运行目录为 `/data/local/tmp/litertlm-qualcomm-v0.17.1/`；测试时设置
`LD_LIBRARY_PATH` 为该目录、`ADSP_LIBRARY_PATH` 为其 `adsp/` 子目录。
这里的 `--backend=npu` 走 LiteRT/QNN，与 ggml-hexagon 的
`GGML_HEXAGON_NHMX=0` 是不同后端；后者不能控制 QNN HMX。

先用手机已有的通用 Gemma 4 E4B `.litertlm` 尝试 on-device JIT。
Qualcomm compiler plugin 和 QNN 2.47 成功加载，并识别 SoC model 68；随后出现
大量 QNN 算子校验失败（包括 FP32 Multiply），日志超过 25 MB 且未进入生成，故终止。
日志：`gemma-e4b-npu.log`。

再在 mini 下载并校验公开的 Gemma 3 270M Q8 Qualcomm SM8550 AOT 包
（SHA256 `3de5b6e6affcabae4ba0ee6b8cd0de88e12b54dc2f210102ba57ffb09c093ff1`，
461,750,272 bytes）。SM8550 与 SM8635 都在 LiteRT 的 V73 支持表中，但 SoC model
分别为 43 和 68。该 AOT 文件的 QNN context 由 2.40 编译，当前运行时为 2.47；
`--backend=npu` 在 `QnnContext_createFromBinary` 返回 5005，未进入推理。
手机 logcat 给出具体原因：`Request feature vtcm size with value 8388608 unsupported`，
即 AOT 包请求 8 MiB VTCM，而本机为 4 MiB。日志：`gemma270-sm8550-npu.log`。
同为 V73 不等于 AOT context 可跨 SoC/VTCM 复用。

最后用同仓库的通用 Gemma 3 270M Q8 `.litertlm`（SHA256
`757e9119fa5bd667a2774fb470ac4afcd3190a21c677f8e69a5d6bc908abdd63`，
304,005,120 bytes）尝试 SM8635 本机 JIT，命令核心为
`litert_lm_main --backend=npu --model_path=gemma3-270m-it-q8.litertlm
--input_prompt=Hello --max_output_tokens=16`。QNN 编译在 TCM migration 阶段报
`InputSlice ... not sufficiently tiled to fit in TCM. Requires 16777216 bytes`。
进程虽然退出码为 0 并生成 `Hello! How can I help you today?`，但日志随后明确显示
`TfLiteXNNPackDelegate` 接管 decode 1482/1537 个节点，以及各 prefill 1611/1667
个节点；**这是 CPU 回退，不是成功的 NPU 推理**。把 `--max_num_tokens` 设为 1024
后仍报同一个 16 MiB TCM 错误。1024 上下文时该回退路径预填充 56.16 tok/s、
解码 41.25 tok/s；直接 `--backend=cpu` 对照为 69.87 和 40.95 tok/s。
每次提示仅处理 10 个预填充 token 和 11 个解码 token，这些数字只用于识别回退，
不能作为稳定吞吐对比。日志：`gemma270-q8-jit-npu.log`、
`gemma270-q8-jit-ctx1024.log`、`gemma270-q8-cpu.log`。

因此这一轮**没有获得 LiteRT-LM 在 SM8635 上的有效 HTP tok/s**。
下一步需要为 SoC model 68、4 MiB VTCM 编译的模型，且图分块必须适合本机 TCM；
直接调整 LiteRT-LM 输出 token 数或复用 8 MiB AOT 均不能解决已观测的失败。
LiteRT 的 [支持芯片表](https://github.com/google-ai-edge/LiteRT/blob/main/litert/vendors/qualcomm/supported_soc.csv)
列有 SM8635，官方 [Qualcomm AOT/JIT 说明](https://github.com/google-ai-edge/LiteRT/blob/main/litert/vendors/qualcomm/doc/HTP_INSTRUCTIONS.md)
说明 AOT 要在 x86 Linux host 编译；[Qualcomm 选项说明](https://github.com/google-ai-edge/LiteRT/blob/main/litert/vendors/qualcomm/doc/OPTIONS_REFERENCE.md)
提供编译期的 `vtcm_size` 参数。该 Gemma 270M 图仍未完成适配本机的 AOT 编译。

## 17. SM8635 专用 U8 QNN 小模型成功（2026-09-23）

使用 [ONNX Model Zoo 的 MNIST-12](https://huggingface.co/onnxmodelzoo/mnist-12)
作为真实完整模型（约 26 KiB）。在 mini 的 Lima Linux VM 内用 QAIRT 2.47，
以 100 张 MNIST 测试图校准为 U8 权重/激活，并通过
`--htp_socs=sm8635 --vtcm_override=4` 生成 64 KiB QNN HTP context。
该 context 在本机 QNN HTP 后端正常加载与执行，`logcat` 确认 CDSP 打开
`libQnnHtpV73Skel.so`。

前 100 张校准图和另外 100 张未参与校准的图，NPU 与原始 ONNX CPU 的
预测类别均为 100/100 一致；后者两条路径都识别正确 100/100。
context 元数据报告 `vtcmSize=4`。模型文件、转换命令、手机复现步骤和
逐样本验证脚本见 [MNIST-12 QNN 记录](../tools/npu/qnn-mnist/README.md)。
这证明本机能够运行经适配的完整 U8 QNN 模型，但还不能推断 U8 LLM
或 LiteRT-LM 的模型级正确性与速度。

## 18. Gemma 4 E4B Abliterated QNN 发布前检查（2026-09-23）

TiebaLite 当前下载的是
[`olekk/gemma-4-E4B-it-abliterated-litert-lm`](https://huggingface.co/olekk/gemma-4-E4B-it-abliterated-litert-lm)
的 3,659,530,240-byte `.litertlm`。该仓库说明它在官方通用 Gemma 4 E4B
LiteRT-LM 包中仅修改了 `o_proj`、`down_proj` 权重，并未重新构造推理图。
手机已安装该文件；mini 上的检查副本为
`/Users/pk/gemma4-aot/gemma-4-E4B-it-abliterated.litertlm`。

使用官方 `litert-lm-peek` 查看包结构：共有 12 个 section，主
`tf_lite_prefill_decode` section 为 2,260,043,376 bytes，其元数据明确记录
`prefer_activation_type=fp16`。提取主图后发现 1,340 个 TFLite subgraph；
`decode` 子图中有 2,344 个 FLOAT32、868 个 INT8、258 个 INT4 tensor，
并包含 410 个 `STABLEHLO_COMPOSITE` 算子。量化权重和部分 INT8 激活不代表
整图为 U8，也不等于已有可在 v73 执行的 QNN context。

第 16 节的同源官方 Gemma 4 E4B 图在 SM8635 QNN 2.47 JIT 尝试中留下
15,104 次 `The SocModel doesn't support FP16`，并有 `RmsNorm`、
`ElementWiseBinary` 算子校验失败。另在当前 abliterated 文件上用
`litert_lm_main --backend=npu` 试跑，日志出现
`NPU accelerator could not be loaded and registered`，随后由 XNNPACK 接管并生成
文本；这次成功输出是 CPU 回退，不能当作 NPU 成功。日志为手机上的
`/data/local/tmp/litertlm-qualcomm-v0.17.1/abliterated-npu-dispatch.log`。

检索到其他 abliterated 发布版，包括 BF16 Safetensors、GGUF，以及
[`vokash3/Huihui-gemma-4-E4B-it-abliterated-LiteRT-LM`](https://huggingface.co/vokash3/Huihui-gemma-4-E4B-it-abliterated-LiteRT-LM)。
后者公开的转换参数是 `dynamic_wi8_afp32`，为 7.8 GiB 通用 LiteRT-LM 包，
并非 SM8635 的 QNN AOT。Google 的
[LiteRT-LM Qualcomm NPU 型号表](https://developers.google.cn/edge/litert/next/litert_lm_npu)
目前只列出 Gemma 3 1B 的 SM8750、SM8650、SM8550 包；未列出 Gemma 4 E4B
或 SM8635 制品。

mini 当前可用空间约 8.7 GiB；查到的 Gemma 4 E4B Abliterated
Safetensors 源权重约 16 GB，尚不具备在 mini 本地完整下载并重新导出的空间。

因此尚未获得可正确运行的 Gemma 4 E4B Abliterated / SM8635 / v73 /
4 MiB VTCM QNN 模型。当前 Android 代码的
`GemmaLocalInference` 也只初始化 GPU，失败后回退 CPU。发布当前 APK
会把未验证的 GPU/CPU 路线冒充为 NPU 版本，故本轮没有上传蒲公英。
若继续走 QNN，需从 abliterated 源权重重新导出目标支持的量化图，解决
FP16/复合算子与 4 MiB VTCM 分块，再编译、真机逐 token 对照、接入 NPU
runtime 后打包。
