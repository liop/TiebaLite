# 独立 HMX v73 CVT/MMA 最小样例

这是独立的 FastRPC DSP 库和 Android host 程序，不调用 GenieX/GGML 的
MatMul、tile 搬运或输出转换。手机是 vivo V2352A（SM8635），DSP 目标架构
为 v73。DSP 在运行时查询 VTCM 容量，在 2 KiB 对齐的 VTCM 中写入两个
32×32 的全 1 FP16 tile，输出先填 `0x7bff` 哨兵值。

偏置区为 256 字节、256 字节对齐。按照 [Qualcomm HMX 手册的 bias 布局](https://docs.qualcomm.com/doc/80-N2040-62/80-N2040-62_REV_AA_Qualcomm_Hexagon_V81_HMX_Programmers_Reference_Manual.pdf)，
前 128 字节依次保存 32 个输出通道的低 32 位（低 16 位 scale，高 16 位
output bias），后 128 字节为各通道高 32 位。本样例默认 scale=1、output
bias=1、高位=0。该手册描述 v81；这里使用其共同的 HMX 指令和控制位作
v73 探针，不能据此保证两个架构的所有行为相同。

| mode | 测试 | 预期首元素 | 2026-09-23 实测 |
| --- | --- | --- | --- |
| 0 | 清 ACC；CVT；空间掩码 0 写回 | 探索性 | `0x0000`，1024/1024 为零 |
| 1 | 清 ACC；全 1 tile MMA；CVT；掩码 `0x700` 写回 | `0x5020`（33） | `0x0000`，1024/1024 为零 |
| 2 | 清 ACC；CVT；掩码 `0x700` 写回 | `0x3c00`（1） | `0x0000`，1024/1024 为零 |
| 3 | 同 mode 1，改用 `:after.hf` 写回 | `0x5020`（33） | `0x0000`，1024/1024 为零 |
| 18 | 清 ACC；scale=0、output bias=1；掩码 `0x700` 写回 | `0x3c00`（1） | `0x0000`，1024/1024 为零 |

mode 10–17 逐步检查 FastRPC、VTCM 查询与分配、输入回读、HMX 上电/锁、
ACC 清零和 bias 寄存器回读。全部成功：VTCM 查询值为 4 MiB，HMX 锁
返回 0，输入位型为 `0x3c00`，bias 寄存器低 32 位回读为 `0x3c003c00`、
高 32 位为 0。mode 0–3、18 的 `out1_or_nonzero` 字段表示输出 tile 中
非零 FP16 元素数量；实测均为 0，说明 1024 个哨兵值都被覆盖为零。
mode 19 查询 HMX 资源抢占能力，运行时返回“不支持查询” (`0x80000404`)，
因此它不能判断 FP16 HMX 算术可用性。

这复现了 GenieX 的全零 HMX 输出，但尚不能把错误归因于 ACC 或 MMA：
即使不做 MMA、只让 output bias=1，CVT/写回仍全零。应先用其它可独立
写入 CVT 状态的已知样例与这个原生 HMX 探针交叉验证，然后再判断 ACC/MMA。
独立 [QNN HTP 对照](../qnn-htp-matmul/README.md)已确认 FP32、U8
MatMul 正确，而 FP16 Add/MatMul 全零。公开 HMX 指令路径通过 CVT 输出 ACC；这里没有
原始 ACC 直接存储的证据，因此本样例的输出不是 ACC dump。

构建使用 mini 的 Snapdragon `arm64-android:v0.7` 容器（Hexagon SDK
6.6.0.0、Tools 19.0.07、Android NDK r29）。`hmx_probe.idl` 由 SDK
`build_idl()` 生成 stub/skel。DSP 库与 host 可执行文件部署在手机独立目录
`/data/local/tmp/hmx-v73-minimal/`，没有覆盖 GenieX 库。手机上单模式运行：

```sh
cd /data/local/tmp/hmx-v73-minimal
ADSP_LIBRARY_PATH=. LD_LIBRARY_PATH=/vendor/lib64:. ./hmx-probe-host 2
```

首次完整调用曾卡住 CDSP，重启手机后改为逐阶段运行；单阶段版本均能返回。
尝试开启 `HAP_compute_res_attr_set_cache_mode` 后，在 VTCM 读取阶段返回
FastRPC `0x8000040d`，因此最终样例未开启该模式。测试结束后 mode 13
再次成功，手机 `sys.boot_completed=1`。
