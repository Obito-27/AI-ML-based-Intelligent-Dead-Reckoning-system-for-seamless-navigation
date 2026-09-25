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
        
        # Output Heads (3 Explicit Heads per AI-IMU Brossard et al. Architecture)
        # Head 1: IMU Denoising Head [delta_af, delta_gz] (residuals to filter vibration & sensor noise)
        self.head_denoise = nn.Sequential(
            nn.Linear(gru_hidden, 24),
            nn.ReLU(),
            nn.Linear(24, 2),
        )
        
        # Head 2: Motion State Regression Head [vf, delta_gyro]
        self.head_motion = nn.Sequential(
            nn.Linear(gru_hidden, 24),
            nn.ReLU(),
            nn.Linear(24, 2),
        )
        
        # Head 3: Context-Aware Uncertainty / Covariance Head [log_var_v, log_var_q]
        self.head_uncertainty = nn.Sequential(
            nn.Linear(gru_hidden, 24),
            nn.ReLU(),
            nn.Linear(24, 2),
        )

    def forward(
        self, x: torch.Tensor
    ) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """Forward pass over strictly trailing window.
        
        Args:
            x: Tensor of shape (B, win_size, in_features)
            
        Returns:
            denoise: (B, 2) predicted [delta_af, delta_gz] IMU residuals
            motion: (B, 2) predicted [vf (m/s >= 0), delta_gyro (rad/s)]
            covar: (B, 2) predicted [log_var_v (Rv = exp(s_v)), log_var_q (Q_scale = exp(s_q))]
        """
        # Permute for 1D convolution: (B, C, L)
        x_c = x.permute(0, 2, 1)
        h = self.relu(self.bn1(self.conv1(x_c)))
        h = self.relu(self.bn2(self.conv2(h)))
        
        # Back to (B, L, C) for GRU
        h_seq = h.permute(0, 2, 1)
        gru_out, _ = self.gru(h_seq)
        
        # Take representation at latest time step (current sample k)
        curr_feat = gru_out[:, -1, :]
        
        # Head 1: Denoising residuals
        denoise = self.head_denoise(curr_feat)
        
        # Head 2: Motion state
        raw_motion = self.head_motion(curr_feat)
        vf = self.relu(raw_motion[:, 0:1])  # Forward speed is strictly non-negative
        delta_gyro = raw_motion[:, 1:2]
        motion = torch.cat([vf, delta_gyro], dim=-1)
        
        # Head 3: Learned covariance log-variances (clamped for numerical stability)
        raw_covar = self.head_uncertainty(curr_feat)
        log_var_v = torch.clamp(raw_covar[:, 0:1], min=-4.0, max=4.0)
        log_var_q = torch.clamp(raw_covar[:, 1:2], min=-4.0, max=4.0)
        covar = torch.cat([log_var_v, log_var_q], dim=-1)
        
        return denoise, motion, covar

    def predict_legacy(
        self, x: torch.Tensor
    ) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """Legacy-compatible tuple output: (vf, delta_gyro, confidence)."""
        denoise, motion, covar = self.forward(x)
        vf = motion[:, 0:1]
        delta_gyro = motion[:, 1:2]
        log_var_v = covar[:, 0:1]
        # Derived confidence score in [0.1, 0.95] from measurement variance
        conf = torch.clamp(1.0 / (1.0 + torch.exp(0.5 * log_var_v)), 0.1, 0.95)
        return vf, delta_gyro, conf

    def get_model_size_mb(self) -> float:
        """Calculates model parameter size in Megabytes."""
        param_bytes = sum(p.numel() * p.element_size() for p in self.parameters())
        buffer_bytes = sum(b.numel() * b.element_size() for b in self.buffers())
        return (param_bytes + buffer_bytes) / (1024.0 * 1024.0)
