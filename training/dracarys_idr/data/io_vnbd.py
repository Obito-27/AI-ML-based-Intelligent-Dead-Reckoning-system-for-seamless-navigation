"""IO-VNBD Dataset loader and preprocessor for Dracarys IDR.

Loads paired real smartphone logs (S-*.csv) and vehicle CAN ground truth (V-*.csv)
from the Inertial and Odometry Benchmark Dataset (IO-VNBD).
Handles:
- Latin1 / CP1252 character encodings
- Whitespace-padded column headers
- 10 Hz IMU vs ~1 Hz asynchronous GPS fix detection
- WGS84 to local East-North-Up (ENU) geodetic conversion
- Phone-to-vehicle mount calibration (gravity leveling + GNSS heading alignment)
"""

from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple
import numpy as np
import pandas as pd


@dataclass
class DriveData:
    drive_id: str
    t: np.ndarray             # (N,) seconds from start (10 Hz)
    dt: float                 # nominal sample period (0.10s)
    
    # Phone-to-vehicle calibrated motion inputs (10 Hz)
    a_f: np.ndarray           # (N,) forward specific force (m/s^2)
    a_l: np.ndarray           # (N,) lateral specific force (m/s^2)
    gyro_z: np.ndarray        # (N,) yaw rate around vehicle vertical axis (rad/s)
    
    # Raw phone IMU signals
    ax_raw: np.ndarray
    ay_raw: np.ndarray
    az_raw: np.ndarray
    grav_x: np.ndarray
    grav_y: np.ndarray
    grav_z: np.ndarray
    gx_raw: np.ndarray
    gy_raw: np.ndarray
    gz_raw: np.ndarray
    
    # Phone GPS observables (~1 Hz asynchronous in 10 Hz stream)
    gps_lat: np.ndarray
    gps_lon: np.ndarray
    gps_x: np.ndarray         # Local ENU East (m)
    gps_y: np.ndarray         # Local ENU North (m)
    gps_speed: np.ndarray     # (m/s)
    gps_accuracy: np.ndarray  # horizontal accuracy (m)
    gps_heading: np.ndarray   # course over ground (rad)
    gps_sats: np.ndarray      # satellites in range count
    gps_fresh: np.ndarray     # boolean mask: True only when fresh 1 Hz fix arrived
    fix_age: np.ndarray       # seconds since last fresh fix
    
    # Vehicle Ground Truth (CAN bus + high-grade reference GPS @ 10 Hz)
    x_gt: np.ndarray          # Local ENU East (m)
    y_gt: np.ndarray          # Local ENU North (m)
    v_gt: np.ndarray          # True forward velocity (m/s)
    psi_gt: np.ndarray        # True Cartesian heading (rad, [0, 2pi), 0=East, pi/2=North)
    yaw_rate_gt: np.ndarray   # True yaw rate (rad/s, CCW positive)
    dist_traveled: float      # Total cumulative distance (m)
    
    # Reference origin
    lat0: float
    lon0: float


def geodetic_to_enu(lat: np.ndarray, lon: np.ndarray, lat0: float, lon0: float) -> Tuple[np.ndarray, np.ndarray]:
    """Projects WGS84 lat/lon to local Cartesian East-North-Up (ENU) coordinates."""
    R_EARTH_A = 6378137.0
    F = 1.0 / 298.257223563
    E2 = 2 * F - F * F
    
    phi0 = np.radians(lat0)
    phi = np.radians(lat)
    dphi = phi - phi0
    dlam = np.radians(lon - lon0)
    
    phi_m = (phi + phi0) * 0.5
    sin_phi_m = np.sin(phi_m)
    
    den = np.sqrt(1.0 - E2 * sin_phi_m ** 2)
    R_N = R_EARTH_A / den
    R_M = R_EARTH_A * (1.0 - E2) / (den ** 3)
    
    x = dlam * R_N * np.cos(phi_m)
    y = dphi * R_M
    return x, y


def find_drive_files(drive_id: str, search_dir: Optional[Path] = None) -> Tuple[Path, Path]:
    if search_dir is None:
        candidates = [
            Path(__file__).resolve().parent.parent.parent.parent / "data" / "raw",
            Path("data/raw"),
            Path("../data/raw"),
        ]
    else:
        candidates = [Path(search_dir)]
        
    for base in candidates:
        if not base.exists():
            continue
        phone_matches = list(base.glob(f"**/*S-{drive_id}.csv")) + list(base.glob(f"**/*S_{drive_id}.csv"))
        veh_matches = list(base.glob(f"**/*V-{drive_id}.csv")) + list(base.glob(f"**/*V_{drive_id}.csv"))
        if phone_matches and veh_matches:
            return phone_matches[0], veh_matches[0]
            
    raise FileNotFoundError(
        f"IO-VNBD real drive files not found for drive '{drive_id}' in {[str(c) for c in candidates]}. "
        f"Expected paired S-{drive_id}.csv and V-{drive_id}.csv. "
        "Please ensure real IO-VNBD dataset files are downloaded into data/raw/."
    )


def list_available_drives(search_dir: Optional[Path] = None) -> List[str]:
    if search_dir is None:
        candidates = [
            Path(__file__).resolve().parent.parent.parent.parent / "data" / "raw",
            Path("data/raw"),
        ]
    else:
        candidates = [Path(search_dir)]
        
    drives = set()
    for base in candidates:
        if not base.exists():
            continue
        for s_file in base.glob("**/S-*.csv"):
            drive_id = s_file.stem.replace("S-", "")
            v_matches = list(s_file.parent.glob(f"V-{drive_id}.csv")) + list(base.glob(f"**/V-{drive_id}.csv"))
            if v_matches:
                drives.add(drive_id)
    return sorted(list(drives))


def load_drive(drive_id: str, data_dir: Optional[Path] = None, max_samples: Optional[int] = None) -> DriveData:
    s_path, v_path = find_drive_files(drive_id, data_dir)
    
    df_s = pd.read_csv(s_path, encoding="latin1")
    df_v = pd.read_csv(v_path, encoding="latin1")
    
    df_s.columns = [c.strip() for c in df_s.columns]
    df_v.columns = [c.strip() for c in df_v.columns]
    
    # ── Time Synchronization between Phone (S) and Vehicle CAN (V) ────
    # In IO-VNBD, S-*.csv records DATE in BST (UTC+1) and V-*.csv records
    # 'Time Since Start of Day' in seconds UTC. There is an initial time
    # offset (0.2s to 8.6s) between the two recording systems.
    date_col = [c for c in df_s.columns if "DATE" in c][0]
    time_col = [c for c in df_v.columns if "Time Since Start of Day" in c][0]
    
    s_date_str = df_s[date_col].iloc[0]
    parts = s_date_str.split(" ")
    t_parts = parts[1].split(":")
    # BST = UTC+1: subtract 1 hour to get UTC seconds from start of day
    s_sec = (int(t_parts[0]) - 1) * 3600.0 + int(t_parts[1]) * 60.0 + int(t_parts[2]) + int(t_parts[3]) / 1000.0
    v_sec = float(df_v[time_col].iloc[0])
    offset_samples = int(round((s_sec - v_sec) * 10.0))
    
    if offset_samples > 0:
        df_v = df_v.iloc[offset_samples:].reset_index(drop=True)
    elif offset_samples < 0:
        df_s = df_s.iloc[-offset_samples:].reset_index(drop=True)
        
    n = min(len(df_s), len(df_v))
    if max_samples is not None:
        n = min(n, max_samples)
    df_s = df_s.iloc[:n].reset_index(drop=True)
    df_v = df_v.iloc[:n].reset_index(drop=True)
    
    dt = 0.10
    t = np.arange(n) * dt
    
    # ── Vehicle Ground Truth ───────────────────────────────────────────
    v_gt = df_v["Velocity (km/hr)"].values.astype(float) / 3.6
    bearing_rad = np.deg2rad(df_v["Heading (degrees)"].values.astype(float))
    # Navigation bearing (deg from N clockwise) to Cartesian angle (rad from E counter-clockwise)
    psi_gt = (np.pi * 0.5 - bearing_rad) % (2.0 * np.pi)
    # Yaw rate in CSV is already CCW-positive (when car turns right/CW, value is negative = d(psi)/dt)
    yaw_rate_gt = np.deg2rad(df_v["Yaw Rate (deg/sec)"].values.astype(float))
    
    lat_gt = df_v["Latitude (degrees)"].values.astype(float)
    lon_gt = df_v["Longitude (degrees)"].values.astype(float)
    
    lat0, lon0 = lat_gt[0], lon_gt[0]
    x_gt, y_gt = geodetic_to_enu(lat_gt, lon_gt, lat0, lon0)
    dist_traveled = float(np.sum(v_gt) * dt)
    
    # ── Extract raw IMU & Gravity channels ──────────────────────────────
    col_ax = [c for c in df_s.columns if "ACCELEROMETER" in c and "X" in c][0]
    col_ay = [c for c in df_s.columns if "ACCELEROMETER" in c and "Y" in c][0]
    col_az = [c for c in df_s.columns if "ACCELEROMETER" in c and "Z" in c][0]
    
    col_grav_x = [c for c in df_s.columns if "GRAVITY" in c and "X" in c][0]
    col_grav_y = [c for c in df_s.columns if "GRAVITY" in c and "Y" in c][0]
    col_grav_z = [c for c in df_s.columns if "GRAVITY" in c and "Z" in c][0]
    
    col_gx = [c for c in df_s.columns if "GYROSCOPE" in c and ("Pitch" in c or "X" in c)][0]
    col_gy = [c for c in df_s.columns if "GYROSCOPE" in c and ("Roll" in c or "Y" in c)][0]
    col_gz = [c for c in df_s.columns if "GYROSCOPE" in c and ("Yaw" in c or "Z" in c)][0]
    
    ax_raw = df_s[col_ax].values.astype(float)
    ay_raw = df_s[col_ay].values.astype(float)
    az_raw = df_s[col_az].values.astype(float)
    
    grav_x = df_s[col_grav_x].values.astype(float)
    grav_y = df_s[col_grav_y].values.astype(float)
    grav_z = df_s[col_grav_z].values.astype(float)
    
    gx_raw = df_s[col_gx].values.astype(float)
    gy_raw = df_s[col_gy].values.astype(float)
    gz_raw = df_s[col_gz].values.astype(float)
    
    # ── Gravity Subtraction: ACCELEROMETER - GRAVITY = Linear Acc ─────
    lin_ax = ax_raw - grav_x
    lin_ay = ay_raw - grav_y
    lin_az = az_raw - grav_z
    
    # ── GPS observables ──────────────────────────────────────────────
    gps_lat = df_s["GPS LATITUDE (degrees)"].values.astype(float)
    gps_lon = df_s["GPS LONGITUDE (degrees)"].values.astype(float)
    gps_speed = df_s["GPS SPEED (Kmh)"].values.astype(float) / 3.6
    gps_acc = df_s["GPS ACCURACY (m)"].values.astype(float)
    
    col_gps_head = [c for c in df_s.columns if "GPS" in c and "ORIENTATION" in c]
    if not col_gps_head:
        col_gps_head = [c for c in df_s.columns if "ORIENTATION" in c and "GPS" in c]
    if col_gps_head:
        gps_heading = (np.pi * 0.5 - np.deg2rad(df_s[col_gps_head[0]].values.astype(float))) % (2.0 * np.pi)
    else:
        gps_heading = np.zeros(n)
        
    col_sats = [c for c in df_s.columns if "SATELLITES" in c][0]
    gps_sats = np.zeros(n, dtype=int)
    for i, s_val in enumerate(df_s[col_sats].values):
        try:
            if isinstance(s_val, str) and "/" in s_val:
                gps_sats[i] = int(s_val.split("/")[0].strip())
            else:
                gps_sats[i] = int(float(s_val))
        except (ValueError, TypeError):
            gps_sats[i] = 12
            
    gps_x, gps_y = geodetic_to_enu(gps_lat, gps_lon, lat0, lon0)
    
    gps_fresh = np.zeros(n, dtype=bool)
    gps_fresh[0] = True
    for i in range(1, n):
        if (gps_lat[i] != gps_lat[i - 1]) or (gps_lon[i] != gps_lon[i - 1]):
            gps_fresh[i] = True
            
    fix_age = np.zeros(n, dtype=float)
    last_fresh_t = t[0]
    for i in range(n):
        if gps_fresh[i]:
            last_fresh_t = t[i]
        fix_age[i] = t[i] - last_fresh_t
        
    # ── Phone-to-Vehicle Mount Calibration ────────────────────────────
    # 1. Vertical Gyroscope Channel Selection:
    # Android phones mounted portrait in car cradles rotate around the
    # pitch or roll axis during vehicle turns. Find the channel and sign
    # that best tracks vehicle yaw rate during turn events.
    gp = df_s[[c for c in df_s.columns if "GYROSCOPE" in c and "Pitch" in c][0]].values.astype(float)
    gr = df_s[[c for c in df_s.columns if "GYROSCOPE" in c and "Roll" in c][0]].values.astype(float)
    gy = df_s[[c for c in df_s.columns if "GYROSCOPE" in c and "Yaw" in c][0]].values.astype(float)
    
    turn_mask = np.abs(yaw_rate_gt) > 0.04
    if turn_mask.sum() > 30:
        best_gyro = None
        best_corr = -1.0
        for g_cand in [gp, gr, gy]:
            for sgn in [1.0, -1.0]:
                c = np.corrcoef(sgn * g_cand[turn_mask], yaw_rate_gt[turn_mask])[0, 1]
                if not np.isnan(c) and c > best_corr:
                    best_corr = c
                    best_gyro = sgn * g_cand
        gyro_z = best_gyro if best_gyro is not None else gp
    else:
        gyro_z = gp
        
    # 2. Horizontal Mount Angle for Forward/Lateral Acceleration:
    # Search for azimuth angle theta in [0, 360) that aligns horizontal
    # linear acceleration with forward acceleration (dv/dt)
    moving_mask = v_gt > 3.0
    if moving_mask.sum() > 50:
        a_long_ref = np.gradient(v_gt, dt)
        best_theta = 0
        best_a_corr = -1.0
        for theta_deg in range(0, 360, 5):
            rad = np.deg2rad(theta_deg)
            af_cand = np.cos(rad) * lin_ax + np.sin(rad) * lin_ay
            c = np.corrcoef(af_cand[moving_mask], a_long_ref[moving_mask])[0, 1]
            if not np.isnan(c) and c > best_a_corr:
                best_a_corr = c
                best_theta = theta_deg
                
        rad = np.deg2rad(best_theta)
        a_f = np.cos(rad) * lin_ax + np.sin(rad) * lin_ay
        a_l = -np.sin(rad) * lin_ax + np.cos(rad) * lin_ay
    else:
        a_f = lin_ax
        a_l = lin_ay
        
    return DriveData(
        drive_id=drive_id,
        t=t,
        dt=dt,
        a_f=a_f,
        a_l=a_l,
        gyro_z=gyro_z,
        ax_raw=ax_raw,
        ay_raw=ay_raw,
        az_raw=az_raw,
        grav_x=grav_x,
        grav_y=grav_y,
        grav_z=grav_z,
        gx_raw=gx_raw,
        gy_raw=gy_raw,
        gz_raw=gz_raw,
        gps_lat=gps_lat,
        gps_lon=gps_lon,
        gps_x=gps_x,
        gps_y=gps_y,
        gps_speed=gps_speed,
        gps_accuracy=gps_acc,
        gps_heading=gps_heading,
        gps_sats=gps_sats,
        gps_fresh=gps_fresh,
        fix_age=fix_age,
        x_gt=x_gt,
        y_gt=y_gt,
        v_gt=v_gt,
        psi_gt=psi_gt,
        yaw_rate_gt=yaw_rate_gt,
        dist_traveled=dist_traveled,
        lat0=lat0,
        lon0=lon0,
    )
