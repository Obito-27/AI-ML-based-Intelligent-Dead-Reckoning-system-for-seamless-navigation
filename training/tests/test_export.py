"""Tests for ONNX and TFLite exported models."""

from pathlib import Path
import numpy as np
import onnxruntime as ort
import pytest

from training.dracarys_idr.export_onnx import export_to_onnx
from training.dracarys_idr.export_tflite import export_to_tflite
from training.dracarys_idr.models import WINDOW_SIZE, NUM_FEATURES


def test_onnx_export_and_inference():
    onnx_path = export_to_onnx()
    assert onnx_path.exists()
    
    size_mb = onnx_path.stat().st_size / (1024.0 * 1024.0)
    assert size_mb < 5.0, f"ONNX size {size_mb} MB exceeds 5 MB"
    
    # Run inference via ONNX Runtime
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    dummy_in = np.random.randn(2, WINDOW_SIZE, NUM_FEATURES).astype(np.float32)
    outputs = session.run(None, {"imu_window": dummy_in})
    
    assert len(outputs) == 3
    assert outputs[0].shape == (2, 1)  # vf
    assert outputs[1].shape == (2, 1)  # gyro
    assert outputs[2].shape == (2, 1)  # conf


def test_tflite_export_and_header():
    tflite_path = export_to_tflite()
    assert tflite_path.exists()
    
    size_mb = tflite_path.stat().st_size / (1024.0 * 1024.0)
    assert size_mb < 5.0, f"TFLite size {size_mb} MB exceeds 5 MB limit"
    
    # Check TFLite flatbuffer magic identifier
    content = tflite_path.read_bytes()
    assert b"TFL3" in content[:16], "Missing TFL3 identifier in TFLite model header"
