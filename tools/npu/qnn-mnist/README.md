# SM8635 / HTP v73 / 4 MiB VTCM 的 U8 QNN 模型实测

这是在 vivo V2352A（SM8635）上成功运行的完整量化模型，不是单算子探针。
源模型是 [ONNX Model Zoo 的 MNIST-12](https://huggingface.co/onnxmodelzoo/mnist-12)，
原始 ONNX 约 26 KiB。模型包含两层卷积、ReLU、MaxPool 和末端全连接；
使用 QAIRT 2.47.0.260601 将权重和激活量化为 8 bit，在 mini 的 Linux VM 内
以 `--htp_socs=sm8635 --vtcm_override=4` 编译。

[mnist_sm8635_u8.bin](./mnist_sm8635_u8.bin) 是已在手机执行的 64 KiB QNN HTP
context binary，SHA-256 为
`a2fc347b90da368a9854c82636b3f3b6120f70b5f6bdb61b7efed20dd4fccb82`。
它依赖同版本 QAIRT 运行库，不能当作通用 GGUF 或语言模型使用。

## 实测结果

测试图来自 [MNIST 测试集](https://storage.googleapis.com/cvdf-datasets/mnist/t10k-images-idx3-ubyte.gz)。
前 100 张用于量化校准，同时做一次回归验证；第 101–200 张完全未参与校准。
CPU 基线是原始 ONNX 模型，经 ONNX Runtime 运行。输入为 `uint8` 灰度像素，
NPU context 的输入量化参数为 `scale=1/255, offset=0`；输出为 10 个 U8 logit，
`scale=0.21635721623897553, offset=-115`。

| 样本 | NPU 与 CPU 预测一致 | CPU 对标签 | NPU 对标签 | logit 平均绝对差 |
| --- | ---: | ---: | ---: | ---: |
| 校准集 0–99 | 100/100 | 98/100 | 98/100 | 0.0839 |
| 留出集 100–199 | 100/100 | 100/100 | 100/100 | 0.0827 |

输出覆盖全部 10 类，不是固定类别。手机 `logcat` 确认 `qnn-net-run` 通过
FastRPC 在 CDSP 打开 `libQnnHtpV73Skel.so`；context 的图元数据显示
`vtcmSize=4`。这验证了该模型在此机的 QNN HTP 路径上可以正确推理。
它没有验证 U8 语言模型、KV cache、长上下文或推理速度。

## 在手机复现

模型、`qnn-net-run`、同版本的 QAIRT HTP 库及 V73 skel 已在手机：

```text
/data/local/tmp/qnn-mnist/mnist_sm8635_u8.bin
/data/local/tmp/qnn-mnist/qnn-net-run
/data/local/tmp/qnn-htp-matmul/libQnnHtp.so
/data/local/tmp/qnn-htp-matmul/adsp/libQnnHtpV73Skel.so
```

从 mini 执行，`input_list.txt` 指向手机上的 100 个原生 U8 图像文件：

```sh
ssh mini 'adb -s 10AE8C33T5002ZD shell '\''
cd /data/local/tmp/qnn-mnist
LD_LIBRARY_PATH=/data/local/tmp/qnn-htp-matmul:. \
ADSP_LIBRARY_PATH=/data/local/tmp/qnn-htp-matmul/adsp \
./qnn-net-run \
  --backend=/data/local/tmp/qnn-htp-matmul/libQnnHtp.so \
  --retrieve_context=mnist_sm8635_u8.bin \
  --input_list=heldout_input_list.txt \
  --output_dir=heldout_output \
  --use_native_input_files --use_native_output_files --log_level=error
'\'''
```

输出文件是 `heldout_output/Result_N/Plus214_Output_0_native.raw`，每个文件
恰好 10 字节。`python verify.py --workdir /private/tmp/qnn-mnist --start 100`
可在 mini 上重新与原始 ONNX 的 CPU 结果对照。

## 转换记录

mini 上的源模型与校准输入保存在 `/private/tmp/qnn-mnist/`；QAIRT 的
Linux 工具运行在 mini 的 Lima VM。源 ONNX 的 SHA-256 为
`5c688690f8bacf667d4c2074af5ad0646ca328d7ab03eccf944a65b320171bdd`。
校准 `input_list.txt` 包含 100 行 `Input3:=calibration/sample-NNN.raw`，
每个 `.raw` 是 `(1,1,28,28)` 的 FP32 像素值除以 255。主要转换参数：

```sh
qnn-onnx-converter \
  --input_network mnist-12.onnx --input_list input_list.txt \
  --act_bitwidth 8 --weights_bitwidth 8 --bias_bitwidth 32 \
  --output_path mnist-u8.cpp

qnn-model-lib-generator \
  -c mnist-u8.cpp -b mnist-u8.bin \
  -t x86_64-linux-clang -l mnist_u8 -o lib

qnn-context-binary-generator \
  --model=lib/x86_64-linux-clang/libmnist_u8.so \
  --backend="$QAIRT_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so" \
  --binary_file=mnist_sm8635_u8 --output_dir=context \
  --htp_socs=sm8635 --vtcm_override=4
```

生成器输出 `context/mnist_sm8635_u8.SM8635.bin`，即本目录提供的文件。
转换器缺少可选的 ONNX Runtime/onnxsim 优化依赖时发出警告，但转换、
模型库编译、context 生成和手机执行全部成功。
