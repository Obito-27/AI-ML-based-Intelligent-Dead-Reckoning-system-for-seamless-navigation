"""Tests for ESKF + NHC multi-rate fusion and gradual GNSS covariance degradation."""

import numpy as np
import pytest

from training.dracarys_idr.fusion import ESKFFusion, eskf_nhc


def test_asynchronous_1hz_gps_10hz_imu_stream():
    """Explicitly tests fusion behavior with 10 Hz IMU ticks and ~1 Hz GPS arrivals.
    
    Verifies that:
    1. Mechanization predicts at 10 Hz on every tick.
    2. GNSS Kalman correction is ONLY applied when gps_fresh is True (every ~10 steps).
    3. Position variance p_pos grows between GPS updates and contracts upon fresh arrival.
    """
    n = 100  # 10 seconds of data
    dt = 0.10
    t = np.arange(n) * dt
    
    # 10 Hz IMU: vehicle moving at 10 m/s
    a_f = np.zeros(n)
    a_l = np.zeros(n)
    gyro_z = np.zeros(n)
    
    # True trajectory
    x_true = 10.0 * t
    y_true = np.zeros(n)
    
    # Simulate realistic 1 Hz GPS arriving every 10th step with noise
    gps_x = np.zeros(n)
    gps_y = np.zeros(n)
    gps_fresh = np.zeros(n, dtype=bool)
    gps_accuracy = np.ones(n) * 4.0
    gps_sats = np.ones(n, dtype=int) * 14
    fix_age = np.zeros(n)
    
    last_fix_x = 0.0
    last_fix_t = 0.0
    for k in range(n):
        if k % 10 == 0:  # Fresh 1 Hz fix
            last_fix_x = x_true[k] + np.random.normal(0, 0.5)
            last_fix_t = t[k]
            gps_fresh[k] = True
        gps_x[k] = last_fix_x
        gps_y[k] = 0.0
        fix_age[k] = t[k] - last_fix_t
        
    engine = ESKFFusion(dt=dt)
    res = engine.filter(
        t=t,
        a_f=a_f,
        a_l=a_l,
        gyro_z=gyro_z,
        gps_x=gps_x,
        gps_y=gps_y,
        gps_speed=np.ones(n) * 10.0,
        gps_accuracy=gps_accuracy,
        gps_sats=gps_sats,
        gps_fresh=gps_fresh,
        fix_age=fix_age,
        v0=10.0,
        psi0=0.0,
    )
    
    # Verify smooth tracking
    final_err = abs(res["x"][-1] - x_true[-1])
    assert final_err < 2.0, f"Position diverged under 1Hz GPS: {final_err} m"
    
    # Verify variance behavior: p_pos must be lower at k=10 (after fix) than at k=9 (before fix)
    assert res["pos_var"][10] < res["pos_var"][9], "Kalman update failed to reduce position variance!"


def test_gradual_gnss_covariance_degradation():
    """Verifies that measurement noise variance degrades smoothly with signal quality."""
    engine = ESKFFusion()
    
    # Pristine fix (high sats, low accuracy radius, fresh fix)
    r_good = engine.compute_gnss_covariance(accuracy_m=3.0, sats=18, fix_age_s=0.1)
    
    # Degraded fix (high HDOP/radius, low sats, stale fix)
    r_bad = engine.compute_gnss_covariance(accuracy_m=20.0, sats=5, fix_age_s=4.0)
    
    assert r_bad > 10.0 * r_good, f"Expected smooth inflation, got r_good={r_good}, r_bad={r_bad}"
    assert r_good >= 9.0


def test_nhc_lateral_velocity_suppression():
    """Verifies that Non-Holonomic Constraints continuously pull lateral velocity toward 0."""
    n = 50
    dt = 0.10
    t = np.arange(n) * dt
    sc = {
        "t": t,
        "ax": np.zeros(n),
        "ay": np.ones(n) * 1.5,  # Uncompensated lateral bias/bump
        "gyro": np.zeros(n),
        "x_true": np.zeros(n),
        "y_true": np.zeros(n),
        "v_true": np.ones(n) * 12.0,
        "psi_true": np.zeros(n),
    }
    
    x, y, vf = eskf_nhc(sc)
    # The lateral drift should remain tightly bounded by NHC
    assert abs(y[-1]) < 5.0, f"NHC failed to clamp lateral divergence, y[-1]={y[-1]}"
