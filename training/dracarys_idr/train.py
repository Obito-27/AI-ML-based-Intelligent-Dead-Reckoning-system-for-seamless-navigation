"""Training Pipeline for DracarysMotionNet on Real IO-VNBD Drives.

Trains the 1D-CNN + GRU model exclusively on real paired smartphone/CAN logs:
- Training Drives: S1, S2, M
- Validation / Held-Out Drive: S4 (completely unseen during training)
- Saves model weights to training/dracarys_idr/models/dracarys_motion_net.pt
"""

from pathlib import Path
import time
import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import DataLoader, TensorDataset

from training.dracarys_idr.data.io_vnbd import load_drive, list_available_drives
from training.dracarys_idr.models import (
    DracarysMotionNet,
    extract_trailing_window_features,
    WINDOW_SIZE,
    NUM_FEATURES,
)


def prepare_dataset(drive_ids, max_samples_per_drive=12000):
    """Extracts trailing windows and targets across multiple drives for the 3 heads."""
    all_X = []
    all_y_vf = []
    all_y_gyro = []
    all_y_denoise = []
    all_y_q = []
    
    for d_id in drive_ids:
        print(f"Loading drive {d_id} for dataset extraction...")
        drive = load_drive(d_id, max_samples=max_samples_per_drive)
        
        # Strictly trailing window features
        X_win = extract_trailing_window_features(
            drive.a_f, drive.a_l, drive.az_raw,
            drive.gx_raw, drive.gy_raw, drive.gyro_z,
            win_size=WINDOW_SIZE,
        )
        
        # Targets: true speed and residual gyro bias/error
        y_vf = drive.v_gt.astype(np.float32)
        y_gyro = (drive.gyro_z - drive.yaw_rate_gt).astype(np.float32)
        
        # Head 1 targets: IMU high-frequency vibration denoising residuals
        # Rolling filter (window=5) isolates high-frequency chassis/engine vibration from gross kinematics
        from scipy.ndimage import uniform_filter1d
        smooth_af = uniform_filter1d(drive.a_f, size=5, mode="nearest")
        smooth_gz = uniform_filter1d(drive.gyro_z, size=5, mode="nearest")
        delta_af = (drive.a_f - smooth_af).astype(np.float32)
        delta_gz = (drive.gyro_z - smooth_gz).astype(np.float32)
        y_denoise = np.column_stack([delta_af, delta_gz]).astype(np.float32)
        
        # Head 3 target for log(sigma_Q^2):
        # Explicit ground-truth supervision derived from the empirical variance of the strapdown
        # kinematic process innovation error over the 15-frame temporal window:
        # eta[t] = a_f[t] - (v_gt[t] - v_gt[t-1]) / dt
        # sigma_proc^2 = Var_win(eta)
        # target_log_q = log(clamp(sigma_proc^2 / Q_0, 0.1, 10.0)) with nominal Q_0 = 2.0 m^2/s^2
        acc_gt = np.diff(drive.v_gt, prepend=drive.v_gt[0]) / drive.dt
        proc_err = drive.a_f - acc_gt
        proc_mean = uniform_filter1d(proc_err, size=WINDOW_SIZE, mode="nearest")
        proc_var = uniform_filter1d((proc_err - proc_mean)**2, size=WINDOW_SIZE, mode="nearest")
        y_q = np.log(np.clip(proc_var / 2.0, 0.1, 10.0)).astype(np.float32)
        
        all_X.append(X_win)
        all_y_vf.append(y_vf)
        all_y_gyro.append(y_gyro)
        all_y_denoise.append(y_denoise)
        all_y_q.append(y_q)
        
    X_cat = np.concatenate(all_X, axis=0)
    y_vf_cat = np.concatenate(all_y_vf, axis=0)
    y_gyro_cat = np.concatenate(all_y_gyro, axis=0)
    y_denoise_cat = np.concatenate(all_y_denoise, axis=0)
    y_q_cat = np.concatenate(all_y_q, axis=0)
    
    return X_cat, y_vf_cat, y_gyro_cat, y_denoise_cat, y_q_cat


def train_model(
    train_drives=("S1", "S2", "M"),
    val_drive="S4",
    epochs=8,
    batch_size=128,
    lr=1e-3,
    output_dir=None,
    model_filename="dracarys_motion_net.pt",
):
    if output_dir is None:
        output_dir = Path(__file__).resolve().parent / "models"
    output_dir.mkdir(parents=True, exist_ok=True)
    model_path = output_dir / model_filename
    
    # 1. Prepare Data
    print("Preparing training dataset from real IO-VNBD drives:", train_drives)
    X_train, y_vf_train, y_gyro_train, y_den_train, y_q_train = prepare_dataset(train_drives)
    print("Preparing validation dataset from unseen held-out drive:", val_drive)
    X_val, y_vf_val, y_gyro_val, y_den_val, y_q_val = prepare_dataset([val_drive], max_samples_per_drive=6000)
    
    # PyTorch Datasets
    train_ds = TensorDataset(
        torch.tensor(X_train, dtype=torch.float32),
        torch.tensor(y_vf_train, dtype=torch.float32).unsqueeze(-1),
        torch.tensor(y_gyro_train, dtype=torch.float32).unsqueeze(-1),
        torch.tensor(y_den_train, dtype=torch.float32),
        torch.tensor(y_q_train, dtype=torch.float32).unsqueeze(-1),
    )
    val_ds = TensorDataset(
        torch.tensor(X_val, dtype=torch.float32),
        torch.tensor(y_vf_val, dtype=torch.float32).unsqueeze(-1),
        torch.tensor(y_gyro_val, dtype=torch.float32).unsqueeze(-1),
        torch.tensor(y_den_val, dtype=torch.float32),
        torch.tensor(y_q_val, dtype=torch.float32).unsqueeze(-1),
    )
    
    train_loader = DataLoader(train_ds, batch_size=batch_size, shuffle=True)
    val_loader = DataLoader(val_ds, batch_size=batch_size, shuffle=False)
    
    # 2. Instantiate Model with deterministic seed
    torch.manual_seed(42 + abs(hash(val_drive)) % 1000)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = DracarysMotionNet().to(device)
    print(f"Model parameters: {sum(p.numel() for p in model.parameters())}, Size: {model.get_model_size_mb():.4f} MB")
    
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    loss_speed = nn.SmoothL1Loss()
    loss_mse = nn.MSELoss()
    
    # 3. Training Loop with Multi-Task Loss (detached residual for uncertainty head)
    best_val_loss = float("inf")
    start_time = time.time()
    
    for epoch in range(1, epochs + 1):
        model.train()
        train_loss = 0.0
        for bx, by_vf, by_gyro, by_den, by_q in train_loader:
            bx = bx.to(device)
            by_vf = by_vf.to(device)
            by_gyro = by_gyro.to(device)
            by_den = by_den.to(device)
            by_q = by_q.to(device)
            
            optimizer.zero_grad()
            
            pred_denoise, pred_motion, pred_covar = model(bx)
            pred_vf = pred_motion[:, 0:1]
            pred_gyro = pred_motion[:, 1:2]
            log_var_v = pred_covar[:, 0:1]
            log_var_q = pred_covar[:, 1:2]
            
            # Head 2: Unbiased velocity regression
            l_vf = loss_speed(pred_vf, by_vf)
            # Gyro correction loss
            l_gy = loss_mse(pred_gyro, by_gyro)
            
            # Head 3: Learned measurement variance matches empirical residual error
            vf_err_sq = (pred_vf.detach() - by_vf) ** 2
            target_log_var_v = torch.log(torch.clamp(vf_err_sq, min=0.04, max=25.0))
            l_unc = loss_mse(log_var_v, target_log_var_v)
            l_q = loss_mse(log_var_q, by_q)
            
            # Head 1: IMU high-frequency vibration Denoising loss
            l_den = loss_mse(pred_denoise, by_den)
            
            loss = l_vf + 15.0 * l_gy + 0.1 * l_unc + 0.1 * l_q + 0.1 * l_den
            
            loss.backward()
            optimizer.step()
            train_loss += loss.item() * len(bx)
            
        train_loss /= len(train_ds)
        
        # Validation
        model.eval()
        val_loss = 0.0
        val_mae_speed = 0.0
        with torch.no_grad():
            for bx, by_vf, by_gyro, by_den, by_q in val_loader:
                bx = bx.to(device)
                by_vf = by_vf.to(device)
                by_gyro = by_gyro.to(device)
                by_den = by_den.to(device)
                by_q = by_q.to(device)
                
                pred_denoise, pred_motion, pred_covar = model(bx)
                pred_vf = pred_motion[:, 0:1]
                pred_gyro = pred_motion[:, 1:2]
                log_var_v = pred_covar[:, 0:1]
                log_var_q = pred_covar[:, 1:2]
                
                l_vf = loss_speed(pred_vf, by_vf)
                l_gy = loss_mse(pred_gyro, by_gyro)
                vf_err_sq = (pred_vf - by_vf) ** 2
                target_log_var_v = torch.log(torch.clamp(vf_err_sq, min=0.04, max=25.0))
                l_unc = loss_mse(log_var_v, target_log_var_v)
                l_q = loss_mse(log_var_q, by_q)
                l_den = loss_mse(pred_denoise, by_den)
                
                v_loss = l_vf + 15.0 * l_gy + 0.1 * l_unc + 0.05 * l_q + 0.1 * l_den
                val_loss += v_loss.item() * len(bx)
                val_mae_speed += torch.abs(pred_vf - by_vf).sum().item()
                
        val_loss /= len(val_ds)
        val_mae_speed /= len(val_ds)
        
        print(f"Epoch {epoch:02d}/{epochs:02d} | Train Loss: {train_loss:.4f} | Val Loss: {val_loss:.4f} | Speed MAE: {val_mae_speed:.2f} m/s")
        
        if val_loss < best_val_loss:
            best_val_loss = val_loss
            torch.save(model.state_dict(), model_path)
            
    print(f"Training finished in {time.time() - start_time:.1f}s. Saved best model to {model_path}")
    return model_path


def train_all_lodo_models(epochs=6):
    """Trains 1 base model + 4 Leave-One-Drive-Out CV holdout models."""
    base_dir = Path(__file__).resolve().parent / "models"
    lodo_dir = base_dir / "lodo"
    lodo_dir.mkdir(parents=True, exist_ok=True)
    
    all_drives = ["S1", "S2", "M", "S4"]
    print("="*80)
    print("TRAINING BASE MODEL (train on S1, S2, M; val on S4)")
    print("="*80)
    train_model(
        train_drives=("S1", "S2", "M"),
        val_drive="S4",
        epochs=epochs,
        output_dir=base_dir,
        model_filename="dracarys_motion_net.pt",
    )
    
    for held_out in all_drives:
        train_drives = tuple(d for d in all_drives if d != held_out)
        print("\n" + "="*80)
        print(f"TRAINING LODO MODEL: HOLDOUT {held_out} (train on {train_drives})")
        print("="*80)
        train_model(
            train_drives=train_drives,
            val_drive=held_out,
            epochs=epochs,
            output_dir=lodo_dir,
            model_filename=f"model_holdout_{held_out}.pt",
        )


if __name__ == "__main__":
    train_all_lodo_models(epochs=6)

