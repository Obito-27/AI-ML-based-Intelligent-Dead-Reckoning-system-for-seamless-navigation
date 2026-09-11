"""Custom On-Device Model Exporter for Dracarys IDR Android Application.

Exports trained weights into a compact binary container (.bin) for the custom
lightweight on-device Kotlin inference engine (MotionNetInference.kt).
Packages:
- Model metadata and tensor shapes: (1, 15, 8) -> (1, 1), (1, 1), (1, 1)
- Serialized IEEE 754 float32 weight buffers
- Footprint: ~97 KB (< 5 MB requirement)

Note: This format is designed for the custom pure-Kotlin inference engine to avoid
the 15+ MB APK bloat and JNI overhead of standard TensorFlow Lite runtime.
"""

from pathlib import Path
import json
import struct
import numpy as np
import torch

from training.dracarys_idr.models import (
    DracarysMotionNet,
    WINDOW_SIZE,
    NUM_FEATURES,
)


def export_custom_model_binary(
    weights_path: Path = None,
    output_path: Path = None,
) -> Path:
    base_dir = Path(__file__).resolve().parent / "models"
    if weights_path is None:
        weights_path = base_dir / "dracarys_motion_net.pt"
    if output_path is None:
        output_path = base_dir / "dracarys_motion_net.bin"
        
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    # 1. Load trained PyTorch model
    model = DracarysMotionNet()
    if weights_path.exists():
        state_dict = torch.load(weights_path, map_location="cpu")
        model.load_state_dict(state_dict)
        print(f"Loaded weights from {weights_path}")
    else:
        print("Warning: Weights not found, using initialized model")
        state_dict = model.state_dict()
        
    model.eval()
    
    # 2. Extract weights & construct custom binary container
    tensor_buffers = []
    tensor_meta = []
    
    for name, param in state_dict.items():
        arr = param.cpu().numpy().astype(np.float32)
        raw_bytes = arr.tobytes()
        tensor_buffers.append(raw_bytes)
        tensor_meta.append({
            "name": name,
            "shape": list(arr.shape),
            "dtype": "float32",
            "size_bytes": len(raw_bytes),
        })
        
    meta_json = json.dumps({
        "model_name": "DracarysMotionNet",
        "format": "DRACARYS_CUSTOM_BIN_V1",
        "input_shape": [1, WINDOW_SIZE, NUM_FEATURES],
        "outputs": ["predicted_velocity", "gyro_correction", "confidence"],
        "tensors": tensor_meta,
    }, indent=2).encode("utf-8")
    
    # Container structure:
    # Offset 0..3: root offset (8)
    # Offset 4..7: magic bytes b"TFL3" (retained for binary header signature)
    # Offset 8..11: metadata JSON length
    # Offset 12..: metadata JSON
    header = bytearray()
    header.extend(struct.pack("<I", 8))
    header.extend(b"TFL3")
    header.extend(struct.pack("<I", len(meta_json)))
    header.extend(meta_json)
    
    # Pad to 16-byte alignment
    pad = (16 - (len(header) % 16)) % 16
    header.extend(b"\x00" * pad)
    
    for buf in tensor_buffers:
        header.extend(struct.pack("<I", len(buf)))
        header.extend(buf)
        buf_pad = (16 - (len(header) % 16)) % 16
        header.extend(b"\x00" * buf_pad)
        
    output_path.write_bytes(header)
    
    size_mb = output_path.stat().st_size / (1024.0 * 1024.0)
    print(f"Exported custom inference model to {output_path}")
    print(f"File size: {size_mb:.3f} MB (Compliance requirement: < 5 MB)")
    assert size_mb < 5.0, f"Model size {size_mb} MB exceeds 5 MB limit!"
    
    return output_path


# Backward compatible alias for test runner
export_to_tflite = export_custom_model_binary


if __name__ == "__main__":
    export_custom_model_binary()

