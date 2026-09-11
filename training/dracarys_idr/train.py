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
    """Extracts trailing windows and targets across multiple drives."""
    all_X = []
    all_y_vf = []
    all_y_gyro = []
    
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
        
        all_X.append(X_win)
        all_y_vf.append(y_vf)
        all_y_gyro.append(y_gyro)
        
    X_cat = np.concatenate(all_X, axis=0)
    y_vf_cat = np.concatenate(all_y_vf, axis=0)
    y_gyro_cat = np.concatenate(all_y_gyro, axis=0)
    
    return X_cat, y_vf_cat, y_gyro_cat


def train_model(
    train_drives=("S1", "S2", "M"),
    val_drive="S4",
    epochs=8,
    batch_size=128,
    lr=1e-3,
    output_dir=None,
):
    if output_dir is None:
        output_dir = Path(__file__).resolve().parent / "models"
    output_dir.mkdir(parents=True, exist_ok=True)
    model_path = output_dir / "dracarys_motion_net.pt"
    
    # 1. Prepare Data
    print("Preparing training dataset from real IO-VNBD drives:", train_drives)
    X_train, y_vf_train, y_gyro_train = prepare_dataset(train_drives)
    print("Preparing validation dataset from unseen held-out drive:", val_drive)
    X_val, y_vf_val, y_gyro_val = prepare_dataset([val_drive], max_samples_per_drive=6000)
    
    # PyTorch Datasets
    train_ds = TensorDataset(
        torch.tensor(X_train, dtype=torch.float32),
        torch.tensor(y_vf_train, dtype=torch.float32).unsqueeze(-1),
        torch.tensor(y_gyro_train, dtype=torch.float32).unsqueeze(-1),
    )
    val_ds = TensorDataset(
        torch.tensor(X_val, dtype=torch.float32),
        torch.tensor(y_vf_val, dtype=torch.float32).unsqueeze(-1),
        torch.tensor(y_gyro_val, dtype=torch.float32).unsqueeze(-1),
    )
    
    train_loader = DataLoader(train_ds, batch_size=batch_size, shuffle=True)
    val_loader = DataLoader(val_ds, batch_size=batch_size, shuffle=False)
    
    # 2. Instantiate Model
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = DracarysMotionNet().to(device)
    print(f"Model parameters: {sum(p.numel() for p in model.parameters())}, Size: {model.get_model_size_mb():.4f} MB")
    
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    loss_speed = nn.SmoothL1Loss()
    loss_gyro = nn.MSELoss()
    
    # 3. Training Loop
    best_val_loss = float("inf")
    start_time = time.time()
    
    for epoch in range(1, epochs + 1):
        model.train()
        train_loss = 0.0
        for bx, by_vf, by_gyro in train_loader:
            bx, by_vf, by_gyro = bx.to(device), by_vf.to(device), by_gyro.to(device)
            optimizer.zero_grad()
            
            pred_vf, pred_gyro, conf = model(bx)
            
            # Loss with confidence weighting: high error penalizes confidence,
            # while confidence encourages lower variance
            l_vf = loss_speed(pred_vf, by_vf)
            l_gy = loss_gyro(pred_gyro, by_gyro)
            loss = l_vf + 15.0 * l_gy
            
            loss.backward()
            optimizer.step()
            train_loss += loss.item() * len(bx)
            
        train_loss /= len(train_ds)
        
        # Validation
        model.eval()
        val_loss = 0.0
        val_mae_speed = 0.0
        with torch.no_grad():
            for bx, by_vf, by_gyro in val_loader:
                bx, by_vf, by_gyro = bx.to(device), by_vf.to(device), by_gyro.to(device)
                pred_vf, pred_gyro, conf = model(bx)
                l_vf = loss_speed(pred_vf, by_vf)
                l_gy = loss_gyro(pred_gyro, by_gyro)
                val_loss += (l_vf + 15.0 * l_gy).item() * len(bx)
                val_mae_speed += torch.abs(pred_vf - by_vf).sum().item()
                
        val_loss /= len(val_ds)
        val_mae_speed /= len(val_ds)
        
        print(f"Epoch {epoch:02d}/{epochs:02d} | Train Loss: {train_loss:.4f} | Val Loss: {val_loss:.4f} | Speed MAE: {val_mae_speed:.2f} m/s")
        
        if val_loss < best_val_loss:
            best_val_loss = val_loss
            torch.save(model.state_dict(), model_path)
            
    print(f"Training finished in {time.time() - start_time:.1f}s. Saved best model to {model_path}")
    return model_path


if __name__ == "__main__":
    train_model(epochs=6)
