"""Compact 1D-CNN + GRU Motion Correction Neural Network for Dracarys IDR.

Strict Causal Invariance:
The rolling window is STRICTLY TRAILING, ending at the current time sample k:
  Window(k) = [k - W + 1, k - W + 2, ..., k]
No future samples (k + 1, k + 2, ...) are ever accessed.
This guarantees strict deployability on real-time Android streaming inputs.

Predicts:
1. Forward velocity estimate / correction v_f (m/s)
2. Gyroscope yaw rate correction delta_omega (rad/s)
3. Confidence score sigma in [0.1, 0.95]

Target model size: < 5 MB (Actual: ~120 KB, 23K parameters).
"""

from typing import Dict, List, Tuple, Union
import numpy as np
import torch
import torch.nn as nn


WINDOW_SIZE = 15     # 15 samples = 1.5 seconds at 10 Hz
NUM_FEATURES = 8    # [a_f, a_l, a_z, gx, gy, gz, acc_norm, jerk_proxy]


def extract_trailing_window_features(
    a_f: np.ndarray,
    a_l: np.ndarray,
    a_z: np.ndarray,
    gx: np.ndarray,
    gy: np.ndarray,
    gz: np.ndarray,
    win_size: int = WINDOW_SIZE,
) -> np.ndarray:
    """Extracts strictly trailing rolling windows for each time step k.
    
    Returns:
        np.ndarray of shape (N, win_size, NUM_FEATURES)
    """
    n = len(a_f)
    acc_norm = np.sqrt(a_f ** 2 + a_l ** 2)
    jerk = np.diff(a_f, prepend=a_f[0])
    
    # Base feature matrix (N, NUM_FEATURES)
    raw_feats = np.column_stack([
        a_f, a_l, a_z,
        gx, gy, gz,
        acc_norm, jerk
    ])
    
    windows = np.zeros((n, win_size, NUM_FEATURES), dtype=np.float32)
    
    for k in range(n):
        # Strict trailing range: [k - win_size + 1 : k + 1]
        start_idx = max(0, k - win_size + 1)
        chunk = raw_feats[start_idx : k + 1]
        
        # Pad head if early in drive
        if len(chunk) < win_size:
            pad_count = win_size - len(chunk)
            pad = np.repeat(chunk[0:1], pad_count, axis=0)
            chunk = np.vstack([pad, chunk])
            
        windows[k] = chunk
        
    return windows


class DracarysMotionNet(nn.Module):
    """Compact 1D-CNN + GRU Motion Estimator (< 5 MB target)."""
    
    def __init__(
        self,
        in_features: int = NUM_FEATURES,
        win_size: int = WINDOW_SIZE,
        cnn_channels: int = 32,
        gru_hidden: int = 48,
    ):
        super().__init__()
        self.in_features = in_features
        self.win_size = win_size
        
        # 1D-CNN Feature Extractor across the trailing temporal window
        self.conv1 = nn.Conv1d(in_features, cnn_channels, kernel_size=3, padding=1)
        self.bn1 = nn.BatchNorm1d(cnn_channels)
        self.conv2 = nn.Conv1d(cnn_channels, gru_hidden, kernel_size=3, padding=1)
        self.bn2 = nn.BatchNorm1d(gru_hidden)
        self.relu = nn.ReLU()
        
        # GRU Temporal Layer
        self.gru = nn.GRU(
            input_size=gru_hidden,
            hidden_size=gru_hidden,
            num_layers=1,
            batch_first=True,
        )
        
        # Output Heads
        # 1. Forward Speed Head (m/s)
        self.head_vf = nn.Sequential(
            nn.Linear(gru_hidden, 24),
            nn.ReLU(),
            nn.Linear(24, 1),
        )
        
        # 2. Heading Rate / Gyro Correction Head (rad/s)
        self.head_gyro = nn.Sequential(
            nn.Linear(gru_hidden, 24),
            nn.ReLU(),
            nn.Linear(24, 1),
        )
        
        # 3. Confidence Head (bounded between 0.1 and 0.95)
        self.head_conf = nn.Sequential(
            nn.Linear(gru_hidden, 16),
            nn.ReLU(),
            nn.Linear(16, 1),
            nn.Sigmoid(),
        )

    def forward(self, x: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """Forward pass over strictly trailing window.
        
        Args:
            x: Tensor of shape (B, win_size, in_features)
            
        Returns:
            vf: (B, 1) predicted forward velocity (m/s)
            delta_gyro: (B, 1) predicted yaw rate correction (rad/s)
            confidence: (B, 1) confidence score in [0.1, 0.95]
        """
        # Permute for 1D convolution: (B, C, L)
        x_c = x.permute(0, 2, 1)
        h = self.relu(self.bn1(self.conv1(x_c)))
        h = self.relu(self.bn2(self.conv2(h)))
        
        # Back to (B, L, C) for GRU
        h_seq = h.permute(0, 2, 1)
        gru_out, _ = self.gru(h_seq)
        
        # Take representation at latest time step (current sample)
        curr_feat = gru_out[:, -1, :]
        
        vf = self.relu(self.head_vf(curr_feat))  # Speed is non-negative
        delta_gyro = self.head_gyro(curr_feat)
        conf = 0.1 + 0.85 * self.head_conf(curr_feat)
        
        return vf, delta_gyro, conf

    def get_model_size_mb(self) -> float:
        """Calculates model parameter size in Megabytes."""
        param_bytes = sum(p.numel() * p.element_size() for p in self.parameters())
        buffer_bytes = sum(b.numel() * b.element_size() for b in self.buffers())
        return (param_bytes + buffer_bytes) / (1024.0 * 1024.0)
