"""Error-State Kalman Filter (ESKF) + Non-Holonomic Constraint (NHC) Fusion Engine.

Port of eskf_nhc from idr_baseline.py with:
- Rotating body-frame Coriolis coupling
- Multi-rate 10 Hz IMU / 1 Hz GPS fusion
- Continuous signal quality covariance scaling (HDOP, sats, fix age)
- NHC pseudo-measurements (vl ~ 0) and online gyro bias estimation
"""

from typing import Dict, Optional, Tuple
import numpy as np

# Named, documented tuning constants for AI-assisted ESKF updates
DEFAULT_AI_GYRO_SCALE: float = 0.10
"""Scale factor applied to AI neural network gyro residual predictions to prevent high-frequency noise from over-correcting the calibrated physical gyro."""

DEFAULT_AI_CONF_MIN: float = 0.80
"""Lower clipping bound for neural network velocity confidence weighting in ESKF measurement update."""

DEFAULT_AI_CONF_MAX: float = 0.95
"""Upper clipping bound for neural network velocity confidence weighting in ESKF measurement update."""


class ESKFFusion:
    """Confidence-Aware ESKF with multi-rate GNSS/IMU fusion and NHC."""
    
    def __init__(
        self,
        dt: float = 0.10,
        k_nhc: float = 0.50,
        k_gyrobias: float = 0.008,
        max_gyro_bias: float = 0.02,
    ):
        self.dt = dt
        self.k_nhc = k_nhc
        self.k_gyrobias = k_gyrobias
        self.max_gyro_bias = max_gyro_bias

    def compute_gnss_covariance(
        self,
        accuracy_m: float,
        sats: int,
        fix_age_s: float,
        base_var: float = 9.0,
    ) -> float:
        acc_scale = max(1.0, (accuracy_m / 3.0) ** 2)
        if sats >= 12:
            sat_scale = 1.0
        elif sats > 4:
            sat_scale = 1.0 + 0.35 * (12 - sats)
        else:
            sat_scale = 20.0
            
        age_scale = 1.0 + 1.5 * max(0.0, fix_age_s - 1.2)
        var = base_var * acc_scale * sat_scale * age_scale
        return float(min(var, 1e6))

    @staticmethod
    def is_stationary(
        lin_acc_mag: float,
        gyro_mag: float,
        current_vf: float,
        gps_speed: Optional[float] = None,
        accel_thresh: float = 0.35,
        gyro_thresh: float = 0.05,
        vel_thresh: float = 0.50,
        gps_speed_thresh: float = 0.25,
    ) -> bool:
        """Evaluates Zero-Velocity Update (ZUPT) stationary condition.
        
        Device is stationary when:
        1. GPS reports low speed (< 0.25 m/s) and linear acceleration is near-zero (< 0.35 m/s^2), OR
        2. Prior filter velocity is low (< 0.50 m/s) with near-zero linear acc (< 0.35 m/s^2) and gyro (< 0.05 rad/s)
        """
        if gps_speed is not None and gps_speed < gps_speed_thresh and lin_acc_mag < accel_thresh:
            return True
        return current_vf < vel_thresh and lin_acc_mag < accel_thresh and gyro_mag < gyro_thresh

    def filter(
        self,
        t: np.ndarray,
        a_f: np.ndarray,
        a_l: np.ndarray,
        gyro_z: np.ndarray,
        gps_x: np.ndarray,
        gps_y: np.ndarray,
        gps_speed: np.ndarray,
        gps_accuracy: np.ndarray,
        gps_sats: np.ndarray,
        gps_fresh: np.ndarray,
        fix_age: np.ndarray,
        v0: float,
        psi0: float,
        x0: float = 0.0,
        y0: float = 0.0,
        gnss_outage_mask: Optional[np.ndarray] = None,
        vf_measurement: Optional[np.ndarray] = None,
        gyro_correction: Optional[np.ndarray] = None,
        ai_confidence: Optional[np.ndarray] = None,
    ) -> Dict[str, np.ndarray]:
        n = len(t)
        dt = self.dt
        
        x = np.zeros(n)
        y = np.zeros(n)
        vf = np.zeros(n)
        vl = np.zeros(n)
        psi = np.zeros(n)
        vx = np.zeros(n)
        vy = np.zeros(n)
        gyro_bias = np.zeros(n)
        pos_var = np.zeros(n)
        
        x[0], y[0] = x0, y0
        vf[0] = v0
        vl[0] = 0.0
        psi[0] = psi0
        vx[0] = v0 * np.cos(psi0)
        vy[0] = v0 * np.sin(psi0)
        pos_var[0] = 4.0
        
        gyro_bias_hat = 0.0
        p_pos = 4.0
        q_pos = 0.05
        
        for k in range(1, n):
            is_outage = False if gnss_outage_mask is None else bool(gnss_outage_mask[k])
            has_fresh_gnss = bool(gps_fresh[k]) and not is_outage

            raw_omega = gyro_z[k]
            if gyro_correction is not None:
                raw_omega = raw_omega - gyro_correction[k]
                
            omega_corrected = raw_omega - gyro_bias_hat
            psi[k] = psi[k - 1] + omega_corrected * dt

            # Zero-Velocity Detection (ZUPT)
            lin_acc_mag = float(np.hypot(a_f[k], a_l[k]))
            gyro_mag = float(abs(omega_corrected))
            has_speed = gps_speed is not None and len(gps_speed) > k
            g_spd = float(gps_speed[k]) if (has_speed and has_fresh_gnss) else None
            is_stat = self.is_stationary(lin_acc_mag, gyro_mag, vf[k - 1], g_spd)

            if is_stat:
                vf[k] = 0.0
                vl[k] = 0.0
            else:
                if vf_measurement is None:
                    vf_pred = vf[k - 1] + (a_f[k] + omega_corrected * vl[k - 1]) * dt
                else:
                    conf = 0.85 if ai_confidence is None else np.clip(ai_confidence[k], 0.1, 0.95)
                    vf_raw = vf[k - 1] + (a_f[k] + omega_corrected * vl[k - 1]) * dt
                    vf_pred = (1.0 - conf) * vf_raw + conf * vf_measurement[k]
                    
                vl_pred = vl[k - 1] + (a_l[k] - omega_corrected * vf[k - 1]) * dt
                
                # NHC constraint: vehicle lateral velocity should be ~0
                res_lat = 0.0 - vl_pred
                vl[k] = vl_pred + self.k_nhc * res_lat
                vf[k] = max(0.0, vf_pred)  # vehicle can't go backwards in normal driving
                
                # Conservative gyro bias estimation with dead zone
                # Only update when driving fast enough AND lateral residual is significant
                if abs(vf[k]) > 3.0 and abs(res_lat) > 0.05:
                    # Scale update by how much lateral residual could be explained by heading error
                    bias_update = self.k_gyrobias * (res_lat / vf[k]) * dt
                    gyro_bias_hat += bias_update
                    gyro_bias_hat = float(np.clip(gyro_bias_hat, -self.max_gyro_bias, self.max_gyro_bias))

            gyro_bias[k] = gyro_bias_hat
            
            cos_p = np.cos(psi[k])
            sin_p = np.sin(psi[k])
            vx[k] = vf[k] * cos_p - vl[k] * sin_p
            vy[k] = vf[k] * sin_p + vl[k] * cos_p
            
            x_pred = x[k - 1] + vx[k] * dt
            y_pred = y[k - 1] + vy[k] * dt
            p_pos += q_pos
            
            is_outage = False if gnss_outage_mask is None else bool(gnss_outage_mask[k])
            has_fresh_gnss = bool(gps_fresh[k]) and not is_outage
            
            if has_fresh_gnss:
                r_k = self.compute_gnss_covariance(
                    accuracy_m=gps_accuracy[k],
                    sats=int(gps_sats[k]),
                    fix_age_s=fix_age[k],
                )
                k_gain = p_pos / (p_pos + r_k)
                x[k] = x_pred + k_gain * (gps_x[k] - x_pred)
                y[k] = y_pred + k_gain * (gps_y[k] - y_pred)
                p_pos = (1.0 - k_gain) * p_pos
            else:
                x[k] = x_pred
                y[k] = y_pred
                
            pos_var[k] = p_pos
            
        return {
            "x": x,
            "y": y,
            "vf": vf,
            "vl": vl,
            "psi": psi,
            "vx": vx,
            "vy": vy,
            "gyro_bias": gyro_bias,
            "pos_var": pos_var,
        }


def eskf_nhc(
    sc,
    vf_measurement: Optional[np.ndarray] = None,
    gyro_correction: Optional[np.ndarray] = None,
    ai_confidence: Optional[np.ndarray] = None,
    gnss_outage_mask: Optional[np.ndarray] = None,
    k_nhc: float = 0.35,
    k_gyrobias: float = 0.02,
    ai_gyro_scale: float = DEFAULT_AI_GYRO_SCALE,
    ai_conf_min: float = DEFAULT_AI_CONF_MIN,
    ai_conf_max: float = DEFAULT_AI_CONF_MAX,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    engine = ESKFFusion(k_nhc=k_nhc, k_gyrobias=k_gyrobias)
    if hasattr(sc, "t"):
        t = sc.t
        a_f = sc.a_f
        a_l = sc.a_l
        gyro_z = sc.gyro_z
        gps_x = sc.gps_x
        gps_y = sc.gps_y
        gps_speed = sc.gps_speed
        gps_accuracy = sc.gps_accuracy
        gps_sats = sc.gps_sats
        gps_fresh = sc.gps_fresh
        fix_age = sc.fix_age
        v0 = float(sc.v_gt[0]) if hasattr(sc, "v_gt") else 0.0
        psi0 = float(sc.psi_gt[0]) if hasattr(sc, "psi_gt") else 0.0
        x0 = float(sc.x_gt[0]) if hasattr(sc, "x_gt") else 0.0
        y0 = float(sc.y_gt[0]) if hasattr(sc, "y_gt") else 0.0
    else:
        t = sc["t"]
        a_f = sc["ax"]
        a_l = sc["ay"]
        gyro_z = sc["gyro"]
        n = len(t)
        gps_x = sc.get("gps_x", np.zeros(n))
        gps_y = sc.get("gps_y", np.zeros(n))
        gps_speed = sc.get("gps_speed", np.zeros(n))
        gps_accuracy = sc.get("gps_accuracy", np.ones(n) * 4.0)
        gps_sats = sc.get("gps_sats", np.ones(n, dtype=int) * 12)
        gps_fresh = sc.get("gps_fresh", np.zeros(n, dtype=bool))
        fix_age = sc.get("fix_age", np.zeros(n))
        v0 = float(sc.get("v_true", [0.0])[0])
        psi0 = float(sc.get("psi_true", [0.0])[0])
        x0 = float(sc.get("x_true", [0.0])[0])
        y0 = float(sc.get("y_true", [0.0])[0])
        
    scaled_gyro_corr = None
    if gyro_correction is not None:
        scaled_gyro_corr = gyro_correction * ai_gyro_scale
        
    clipped_conf = None
    if ai_confidence is not None:
        clipped_conf = np.clip(ai_confidence, ai_conf_min, ai_conf_max)
        
    res = engine.filter(
        t=t,
        a_f=a_f,
        a_l=a_l,
        gyro_z=gyro_z,
        gps_x=gps_x,
        gps_y=gps_y,
        gps_speed=gps_speed,
        gps_accuracy=gps_accuracy,
        gps_sats=gps_sats,
        gps_fresh=gps_fresh,
        fix_age=fix_age,
        v0=v0,
        psi0=psi0,
        x0=x0,
        y0=y0,
        gnss_outage_mask=gnss_outage_mask,
        vf_measurement=vf_measurement,
        gyro_correction=scaled_gyro_corr,
        ai_confidence=clipped_conf,
    )
    return res["x"], res["y"], res["vf"]
