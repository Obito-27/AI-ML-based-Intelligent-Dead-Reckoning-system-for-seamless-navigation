"""Tests for DracarysMotionNet: causal trailing window verification and size compliance."""

import numpy as np
import pytest
import torch

from training.dracarys_idr.models import (
    DracarysMotionNet,
    extract_trailing_window_features,
    WINDOW_SIZE,
    NUM_FEATURES,
)


def test_strictly_trailing_window_causality():
    """Explicitly verifies that the rolling window is trailing and causal.
    
    Mutating any future sample at index > k MUST NOT alter the feature window
    or the model prediction at step k.
    """
    n = 60
    np.random.seed(42)
    a_f = np.random.randn(n)
    a_l = np.random.randn(n)
    a_z = np.random.randn(n)
    gx = np.random.randn(n)
    gy = np.random.randn(n)
    gz = np.random.randn(n)
    
    # 1. Base trailing features
    base_windows = extract_trailing_window_features(a_f, a_l, a_z, gx, gy, gz, win_size=WINDOW_SIZE)
    
    # Choose test step k
    k = 25
    window_k_orig = base_windows[k].copy()
    
    # 2. Corrupt/mutate all FUTURE samples (k+1 to n-1)
    a_f_mutated = a_f.copy()
    a_l_mutated = a_l.copy()
    gx_mutated = gx.copy()
    
    a_f_mutated[k + 1 :] += 1000.0  # Huge future spike
    a_l_mutated[k + 1 :] -= 500.0
    gx_mutated[k + 1 :] *= 50.0
    
    mutated_windows = extract_trailing_window_features(
        a_f_mutated, a_l_mutated, a_z, gx_mutated, gy, gz, win_size=WINDOW_SIZE
    )
    window_k_after = mutated_windows[k]
    
    # Strict causal assertion: window at k must be 100% invariant to future samples
    np.testing.assert_array_almost_equal(
        window_k_orig,
        window_k_after,
        err_msg="Causal violation: Window at step k was altered by future sample mutation!",
    )
    
    # Also verify model forward pass invariance
    model = DracarysMotionNet()
    model.eval()
    with torch.no_grad():
        t1 = torch.tensor(window_k_orig, dtype=torch.float32).unsqueeze(0)
        t2 = torch.tensor(window_k_after, dtype=torch.float32).unsqueeze(0)
        out1 = model(t1)
        out2 = model(t2)
        
    for o1, o2 in zip(out1, out2):
        assert torch.allclose(o1, o2, atol=1e-6), "Model prediction at k depended on future values!"


def test_model_size_compliance():
    """Asserts that model parameter size is strictly under the 5 MB platform limit."""
    model = DracarysMotionNet()
    size_mb = model.get_model_size_mb()
    assert size_mb < 5.0, f"Model size {size_mb} MB exceeds 5 MB ceiling!"
    assert size_mb < 0.5, f"Model size {size_mb} MB is unexpectedly large (> 500 KB)!"


def test_model_output_ranges():
    model = DracarysMotionNet()
    model.eval()
    x = torch.randn(8, WINDOW_SIZE, NUM_FEATURES)
    vf, gyro, conf = model(x)
    
    assert vf.shape == (8, 1)
    assert gyro.shape == (8, 1)
    assert conf.shape == (8, 1)
    
    # Velocity must be non-negative
    assert (vf >= 0.0).all()
    # Confidence must be bounded in [0.1, 0.95]
    assert (conf >= 0.1).all() and (conf <= 0.95).all()
