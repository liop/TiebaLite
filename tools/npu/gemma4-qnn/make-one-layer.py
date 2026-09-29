"""Create a one-layer Gemma 4 checkpoint for QNN compatibility experiments.

This deliberately changes the architecture and is never a release model.
"""

import argparse
import json
import re
import shutil
from pathlib import Path

from safetensors import safe_open
from safetensors.torch import save_file


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--layer-index", type=int, default=5)
    args = parser.parse_args()

    source = args.source
    output = args.output
    output.mkdir(parents=True, exist_ok=True)

    config = json.loads((source / "config.json").read_text())
    text = config["text_config"]
    index = args.layer_index
    if not 0 <= index < text["num_hidden_layers"]:
        raise ValueError("Layer index is outside source model")
    if text["layer_types"][index] != "full_attention":
        raise ValueError("Select a full_attention layer for the one-layer model")
    width = text["hidden_size_per_layer_input"]
    text["num_hidden_layers"] = 1
    text["layer_types"] = [text["layer_types"][index]]
    if "num_kv_shared_layers" in text:
        text["num_kv_shared_layers"] = 0
    (output / "config.json").write_text(json.dumps(config, indent=2) + "\n")

    tensors = {}
    with safe_open(source / "model.safetensors", framework="pt", device="cpu") as checkpoint:
        for key in checkpoint.keys():
            layer = re.search(r"\.layers\.(\d+)\.", key)
            if key.startswith("model.language_model.") and layer:
                if int(layer.group(1)) != index:
                    continue
                destination_key = key.replace(f".layers.{index}.", ".layers.0.")
            else:
                destination_key = key
            if key == "model.language_model.embed_tokens_per_layer.weight":
                start = index * width
                tensors[destination_key] = checkpoint.get_slice(key)[:, start : start + width].contiguous()
            elif key == "model.language_model.per_layer_model_projection.weight":
                start = index * width
                tensors[destination_key] = checkpoint.get_slice(key)[start : start + width, :].contiguous()
            else:
                tensors[destination_key] = checkpoint.get_tensor(key)
        save_file(tensors, output / "model.safetensors", metadata=checkpoint.metadata())

    for name in (
        "tokenizer.json",
        "tokenizer_config.json",
        "generation_config.json",
        "chat_template.jinja",
        "processor_config.json",
    ):
        path = source / name
        if path.exists():
            shutil.copy2(path, output / name)
    print(f"Wrote {len(tensors)} tensors to {output}")


if __name__ == "__main__":
    main()
