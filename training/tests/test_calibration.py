"""Unit Tests for Live Device Orientation & Mount Calibration Engine.

Verifies the live, ground-truth-free calibration engine against exact synthetic
ground-truth scenarios:
1. Device Flat (standard horizontal placement)
2. Device Upright Portrait (windshield cradle, pitch = -80° to -90°)
3. Device at Arbitrary 3D Compound Angles (arbitrary yaw, pitch, roll)
4. Mount Azimuth Offset Recovery from GNSS acceleration
5. Directional Sign Invariance across left and right turns
"""

import pytest
import numpy as np
from training.dracarys_idr.calibration import DeviceOrientationCalibrator


def test_device_flat_orientation_yaw_extraction():
    """Device lying flat on horizontal dashboard (gravity on +Z in Android Sensor.TYPE_GRAVITY)."""
    g_dev = np.array([0.0, 0.0, 9.80665])
    
    # Vehicle turns left (CCW from above): +0.35 rad/s
    omega_dev_left = np.array([0.0, 0.0, 0.35])
    yaw_rate = DeviceOrientationCalibrator.extract_vehicle_yaw_rate(omega_dev_left, g_dev)
    assert pytest.approx(0.35, abs=1e-5) == yaw_rate
    
    # Vehicle turns right (CW from above): -0.35 rad/s
    omega_dev_right = np.array([0.0, 0.0, -0.35])
    yaw_rate_r = DeviceOrientationCalibrator.extract_vehicle_yaw_rate(omega_dev_right, g_dev)
    assert pytest.approx(-0.35, abs=1e-5) == yaw_rate_r


def test_device_upright_portrait_cradle_yaw_extraction():
    """Device mounted upright in portrait mode (screen facing driver, long edge vertical).
    In this upright pose, top of phone points UP, so gravity sensor reads +9.81 along phone +Y.
    Turning the car rotates the phone around its vertical long axis (+Y).
    """
    g_dev = np.array([0.0, 9.80665, 0.0])
    
    omega_vehicle_turn = np.array([0.0, 0.42, 0.0])
    yaw_rate = DeviceOrientationCalibrator.extract_vehicle_yaw_rate(omega_vehicle_turn, g_dev)
    assert pytest.approx(0.42, abs=1e-5) == yaw_rate


def test_device_arbitrary_compound_3d_tilt():
    """Device tilted at arbitrary Euler angles (compound pitch + roll).
    Tests that rotation about Earth vertical is cleanly recovered regardless of device pose.
    """
    pitch = np.deg2rad(35.0)
    roll = np.deg2rad(-22.0)
    
    Rx = np.array([
        [1.0, 0.0, 0.0],
        [0.0, np.cos(pitch), np.sin(pitch)],
        [0.0, -np.sin(pitch), np.cos(pitch)],
    ])
    Ry = np.array([
        [np.cos(roll), 0.0, -np.sin(roll)],
        [0.0, 1.0, 0.0],
        [np.sin(roll), 0.0, np.cos(roll)],
    ])
    R_v2d = Rx @ Ry
    
    # In vehicle frame, gravity vector points UP (+Z) in Android normal-force convention
    g_vehicle = np.array([0.0, 0.0, 9.80665])
    g_dev = R_v2d @ g_vehicle
    
    # True vehicle rotation: pure yaw of +0.28 rad/s (left turn)
    omega_vehicle_true = np.array([0.0, 0.0, 0.28])
    # Add horizontal roll/pitch perturbations (e.g. road camber, suspension rocking)
    omega_vehicle_cam = omega_vehicle_true + np.array([0.15, -0.10, 0.0])
    
    omega_dev = R_v2d @ omega_vehicle_cam
    
    recovered_yaw = DeviceOrientationCalibrator.extract_vehicle_yaw_rate(omega_dev, g_dev)
    assert pytest.approx(0.28, abs=1e-5) == recovered_yaw


def test_mount_azimuth_alignment_against_known_ground_truth():
    """Simulates known forward vehicle acceleration and verifies that the calibrator
    recovers the exact mount azimuth offset and projects accelerations into vehicle body frame.
    """
    calibrator = DeviceOrientationCalibrator()
    
    t = np.linspace(0, 50, 500)
    a_f_true = 2.0 * np.sin(0.2 * t) + 0.5 * np.cos(0.05 * t)
    a_l_true = 0.0
    
    # Known mount azimuth: phone is twisted 42.0 degrees clockwise relative to vehicle forward
    psi_mount_known_deg = 42.0
    psi_rad = np.deg2rad(psi_mount_known_deg)
    
    # Measured horizontal acceleration on device:
    # [ax] = [ cos(psi) -sin(psi)] [af]
    # [ay] = [ sin(psi)  cos(psi)] [al]
    ax_dev = np.cos(psi_rad) * a_f_true - np.sin(psi_rad) * a_l_true
    ay_dev = np.sin(psi_rad) * a_f_true + np.cos(psi_rad) * a_l_true
    az_dev = np.zeros_like(ax_dev)
    
    linear_acc_dev = np.stack([ax_dev, ay_dev, az_dev], axis=0)
    g_dev = np.array([0.0, 0.0, 9.80665])
    
    gnss_forward_acc = a_f_true + np.random.normal(0, 0.02, size=len(t))
    
    recovered_azimuth_deg = calibrator.calibrate_mount_azimuth_from_gnss(
        linear_acc_dev=linear_acc_dev,
        gravity_vec=g_dev,
        gnss_forward_acc=gnss_forward_acc,
    )
    
    assert calibrator.is_azimuth_calibrated
    assert pytest.approx(psi_mount_known_deg, abs=0.5) == recovered_azimuth_deg
    
    # Test projection of sample acceleration
    sample_acc = np.array([ax_dev[50], ay_dev[50], 0.0])
    a_f_est, a_l_est, _ = calibrator.project_vehicle_body_accel(sample_acc, g_dev)
    
    assert pytest.approx(a_f_true[50], abs=0.01) == a_f_est
    assert pytest.approx(0.0, abs=0.01) == a_l_est


def test_directional_heading_integration_sign():
    """Verifies sign convention: CCW rotation (turning left) increases Cartesian heading,
    while CW rotation (turning right) decreases Cartesian heading.
    """
    g_dev = np.array([0.0, 0.0, 9.80665])
    psi = np.pi / 2
    dt = 0.10
    
    # Vehicle turns left at 0.5 rad/s for 1 second (10 ticks)
    omega_turn_left = np.array([0.0, 0.0, 0.5])
    for _ in range(10):
        w = DeviceOrientationCalibrator.extract_vehicle_yaw_rate(omega_turn_left, g_dev)
        psi += w * dt
        
    assert psi > (np.pi / 2)
    assert pytest.approx(np.pi / 2 + 0.5, abs=1e-5) == psi
