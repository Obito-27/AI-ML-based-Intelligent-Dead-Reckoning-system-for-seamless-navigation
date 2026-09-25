"""ONNX Model Exporter for Dracarys IDR Edge Engine.

Exports the trained DracarysMotionNet model to ONNX format with dynamic batch support.
Validates the exported artifact using ONNX Runtime and benchmarks inference latency.
"""

import sys
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

from pathlib import Path
import time
import numpy as np
import onnxruntime as ort
import torch

from training.dracarys_idr.models import DracarysMotionNet, WINDOW_SIZE, NUM_FEATURES


def export_to_onnx(
    weights_path: Path = None,
    output_path: Path = None,
) -> Path:
    base_dir = Path(__file__).resolve().parent / "models"
    if weights_path is None:
        weights_path = base_dir / "dracarys_motion_net.pt"
    if output_path is None:
        output_path = base_dir / "dracarys_motion_net.onnx"
        
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    # 1. Instantiate model and load weights
    model = DracarysMotionNet()
    if weights_path.exists():
        model.load_state_dict(torch.load(weights_path, map_location="cpu"))
        print(f"Loaded trained model weights from {weights_path}")
    else:
        print("Warning: Model weights not found, exporting initialized model architecture")
        
    model.eval()
    
    # 2. Export via torch.onnx with dynamic batch dimension
    dummy_input = torch.randn(1, WINDOW_SIZE, NUM_FEATURES, dtype=torch.float32)
    batch_dim = torch.export.Dim("batch", min=1, max=256)
    
    torch.onnx.export(
        model,
        dummy_input,
        str(output_path),
        export_params=True,
        opset_version=18,
        dynamic_shapes={"x": {0: batch_dim}},
        input_names=["imu_window"],
        output_names=["denoise_imu", "motion_state", "uncertainty_covar"],
    )
    
    size_mb = output_path.stat().st_size / (1024.0 * 1024.0)
    print(f"Exported ONNX model to {output_path} (Size: {size_mb:.3f} MB, Target < 5 MB)")
    
    # 3. Validate with ONNX Runtime
    session = ort.InferenceSession(str(output_path), providers=["CPUExecutionProvider"])
    
    # Benchmark 100 iterations
    test_input = np.random.randn(1, WINDOW_SIZE, NUM_FEATURES).astype(np.float32)
    start = time.perf_counter()
    for _ in range(100):
        outputs = session.run(None, {"imu_window": test_input})
    avg_latency_ms = (time.perf_counter() - start) * 10.0
    
    print(f"ONNX Runtime validation passed.")
    print(f"Average CPU inference latency: {avg_latency_ms:.3f} ms (Target < 5 ms for 200 Hz edge engine)")
    print(f"Outputs: denoise={outputs[0][0]}, motion={outputs[1][0]}, covar={outputs[2][0]}")
    
    return output_path


if __name__ == "__main__":
    export_to_onnx()
