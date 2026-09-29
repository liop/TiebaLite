"""Check that Gemma 4's exported bundle can run LiteRT PTQ calibration.

This is a pipeline compatibility check. A two-prompt, one-layer profile is not
representative enough to quantize a release model.
"""

from litert_torch.generative.export_hf.experimental.calib.calibrate import calibrate


calibrate(
    input_litertlm="/work/one-full-layer-export/model.litertlm",
    calibration_dataset_dir="/work/calibration-smoke",
    calibration_result_save_dir="/work/one-full-layer-calibration",
    calibration_dataset_format="jsonl",
    max_calibration_decode_steps=2,
    max_examples=2,
    kv_cache_max_len=256,
)
