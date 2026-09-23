# 独立 QNN HTP 精度对照

`qnn_matmul.c` 直接用 QNN C API 在线建图，不调用 GenieX/GGML。测试设备是
vivo V2352A（SM8635、HTP v73、4 MiB VTCM）。Android host 在 mini 的
Lima 构建容器中用 NDK r29 编译；运行库来自已有的 QAIRT 2.47
LiteRT-LM 部署包，手机上位于 `/data/local/tmp/qnn-htp-matmul/`。
程序成功加载 QNN HTP provider（backend 6，core API 2.36，HTP API 5.47），
CDSP 侧成功打开 `libQnnHtpV73Skel.so`。所有图的 finalize 和 execute 都返回成功。

输入 A 和静态权重 B 均为 32×32 的全 1 矩阵。U8 模式采用 scale=1、
offset=0 的量化编码。Cast 只使用 A。输出先填哨兵值，
执行后比较全部 1024 个元素：

| 模式 | 预期每项 | 实测首项 | 结果 |
| --- | ---: | ---: | --- |
| `add-fp32` | 2 | 2 | 1024/1024 正确 |
| `matmul-fp32` | 32 | 32 | 1024/1024 正确 |
| `cast-to-fp16` | FP16 1 (`0x3c00`) | `0x3c00` | 1024/1024 正确 |
| `cast-from-fp16` | FP32 1 | 1.00000012 | 1024/1024 在 `1e-4` 内；最大误差 `1.19e-7` |
| `add-u8` | U8 2 | 2 | 1024/1024 正确 |
| `matmul-u8` | U8 32 | 32 | 1024/1024 正确 |
| `add`（FP16） | FP16 2 (`0x4000`) | `0x0000` | 1024/1024 为零 |
| `matmul`（FP16） | FP16 32 (`0x5000`) | `0x0000` | 1024/1024 为零 |

另外用 `-DQNN_TEST_N=128` 编译同一程序，独立执行 128×128 测试，逐项检查
16,384 个输出：

| 模式 | 预期每项 | 实测首项 | 结果 |
| --- | ---: | ---: | --- |
| `add-fp32` | 2 | 2 | 16,384/16,384 正确 |
| `matmul-fp32` | 128 | 128 | 16,384/16,384 正确 |
| `add-u8` | 2 | 2 | 16,384/16,384 正确 |
| `matmul-u8` | 128 | 128 | 16,384/16,384 正确 |
| `add`（FP16） | FP16 2 (`0x4000`) | `0x0000` | 16,384/16,384 为零 |
| `matmul`（FP16） | FP16 128 (`0x5800`) | `0x0000` | 16,384/16,384 为零 |

为防全 1 输入掩盖错误，又用确定性的非均匀 0/1 矩阵运行
`add-u8-pattern` 和 `matmul-u8-pattern`，逐元素与 CPU 整数参考值比较。
32×32 和 128×128 的四组测试全部通过（错误数均为 0）。
128×128 MatMul 的首项为 9，输出不是常数 128；128×128 Add 中有
8,738 个合法的零输出，哨兵残留数为 0。

这个对照表明该 QAIRT HTP 路径中的通用图执行、FP32 算术、FP16 输入读取和
FP16 输出写回及 U8 量化矩阵计算都能工作；FP16 Add 与 MatMul 算术却稳定
输出全零。它与
[原生 HMX CVT 探针](../hmx-v73-minimal/README.md)相互印证，降低了“仅是
GenieX tile 排布或输出搬运错误”的可能性。量化 NPU 路线仍值得验证模型级
正确性与性能。QNN 图的内部指令选择未被测量，
因此不能据此断言 FP16 Add 也使用 HMX，或断言 HMX 硬件本身损坏。

另一套 GenieX 附带的 QNN HTP 库（core API 2.34、HTP API 5.45）在同机
`deviceCreate` 返回 `1008`，未运行到图执行，不能用作版本正确性对照。

手机上单模式复现：

```sh
cd /data/local/tmp/qnn-htp-matmul
LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH=/data/local/tmp/qnn-htp-matmul/adsp ./qnn-matmul matmul
```

128×128 版本是同目录的 `./qnn-matmul-128`。可将末尾的 `matmul` 替换为表中
其它模式或 `add-u8-pattern`、`matmul-u8-pattern`。运行需要同版本的
`libQnnHtp.so`、`libQnnHtpPrepare.so`、`libQnnHtpV73Stub.so`，以及
`adsp/libQnnHtpV73Skel.so`。构建需要 QAIRT 的 `QnnInterface.h`、
`QnnOpDef.h` 等头文件和 Android NDK；二进制不链接 GenieX。
