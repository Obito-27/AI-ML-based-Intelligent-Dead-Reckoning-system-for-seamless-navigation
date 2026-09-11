"""Live Device Orientation & Mount Calibration Engine for Dracarys IDR.

Implements autonomous, ground-truth-free sensor calibration using:
1. Gravity Vector Leveling (Pitch/Roll) via Android Sensor.TYPE_GRAVITY
2. Vertical Vehicle Yaw Rate Projection (invariant to phone tilt / cradle mount)
3. GNSS Course-Coupled Mount Azimuth Alignment (Forward / Lateral acceleration)
4. Zero-Velocity Detection (ZUPT) & Gyroscope Bias Estimation
"""

from typing import Tuple, Optional
import numpy as np


class DeviceOrientationCalibrator:
    """Calibrates smartphone IMU sensor frame to vehicle body frame without CAN bus."""

    def __init__(self, g_mag: float = 9.80665):
        self.g_mag = g_mag
        self.psi_mount: float = 0.0
        self.is_azimuth_calibrated: bool = False
        self.gyro_bias: np.ndarray = np.zeros(3, dtype=np.float64)

    @staticmethod
    def compute_gravity_unit_up(gravity_vec: np.ndarray) -> np.ndarray:
        """Extracts the vehicle Up unit vector from measured Earth gravity.
        
        In Android Sensor.TYPE_GRAVITY, the sensor measures upward normal force
        against gravity (+9.81 m/s^2 on Z when device is flat screen-up).
        Therefore, the measured gravity vector directly points in the vehicle UP direction:
            u_up = +g / ||g||
        """
        norm = np.linalg.norm(gravity_vec)
        if norm < 1e-4:
            return np.array([0.0, 0.0, 1.0], dtype=np.float64)
        return np.asarray(gravity_vec, dtype=np.float64) / norm

    @staticmethod
    def extract_vehicle_yaw_rate(gyro_meas: np.ndarray, gravity_vec: np.ndarray) -> float:
        """Extracts rotation around the vehicle vertical (Up) axis from 3D gyro.
        
        Vehicle yaw rate is the projection of 3D angular velocity onto vehicle Up:
            omega_yaw = omega_gyro . u_up = (omega_gyro . g) / ||g||
        Positive sign corresponds to CCW rotation (turning left).
        """
        u_up = DeviceOrientationCalibrator.compute_gravity_unit_up(gravity_vec)
        return float(np.dot(gyro_meas, u_up))

    @staticmethod
    def compute_leveling_matrix(gravity_vec: np.ndarray) -> np.ndarray:
        """Builds a rotation matrix R_dev_to_level that rotates device frame
        such that the measured gravity vector aligns with [0, 0, +g] (vehicle Up).
        """
        u_up = DeviceOrientationCalibrator.compute_gravity_unit_up(gravity_vec)
        target = np.array([0.0, 0.0, 1.0], dtype=np.float64)

        v = np.cross(u_up, target)
        s = np.linalg.norm(v)
        c = float(np.dot(u_up, target))

        if s < 1e-6:
            if c > 0:
                return np.eye(3, dtype=np.float64)
            else:
                return np.diag([1.0, -1.0, -1.0])

        vx = np.array([
            [0.0, -v[2], v[1]],
            [v[2], 0.0, -v[0]],
            [-v[1], v[0], 0.0],
        ], dtype=np.float64)

        R = np.eye(3, dtype=np.float64) + vx + (vx @ vx) * ((1.0 - c) / (s ** 2))
        return R

    def calibrate_mount_azimuth_from_gnss(
        self,
        linear_acc_dev: np.ndarray,
        gravity_vec: np.ndarray,
        gnss_forward_acc: np.ndarray,
        min_speed_mask: Optional[np.ndarray] = None,
    ) -> float:
        """Estimates horizontal mount azimuth offset psi_mount by solving the
        closed-form least-squares projection between leveled horizontal accelerometer
        readings and vehicle longitudinal acceleration derived from GNSS.
        """
        R_level = self.compute_leveling_matrix(gravity_vec)
        acc_leveled = R_level @ linear_acc_dev  # (3, N)
        ax_h = acc_leveled[0]
        ay_h = acc_leveled[1]

        if min_speed_mask is not None and min_speed_mask.sum() > 20:
            ax_sub = ax_h[min_speed_mask]
            ay_sub = ay_h[min_speed_mask]
            a_ref = gnss_forward_acc[min_speed_mask]
        else:
            ax_sub = ax_h
            ay_sub = ay_h
            a_ref = gnss_forward_acc

        # Optimal closed-form least-squares solution: psi = atan2(ay . a_ref, ax . a_ref)
        dot_x = float(np.dot(ax_sub, a_ref))
        dot_y = float(np.dot(ay_sub, a_ref))

        self.psi_mount = float(np.arctan2(dot_y, dot_x)) % (2.0 * np.pi)
        self.is_azimuth_calibrated = True
        return float(np.rad2deg(self.psi_mount))

    def project_vehicle_body_accel(
        self,
        linear_acc_dev: np.ndarray,
        gravity_vec: np.ndarray,
    ) -> Tuple[float, float, float]:
        """Projects raw linear acceleration into vehicle body axes: (a_forward, a_lateral, a_vertical)."""
        R_level = self.compute_leveling_matrix(gravity_vec)
        acc_leveled = R_level @ linear_acc_dev
        ax_h, ay_h, az_v = float(acc_leveled[0]), float(acc_leveled[1]), float(acc_leveled[2])

        cos_psi = np.cos(self.psi_mount)
        sin_psi = np.sin(self.psi_mount)

        a_forward = cos_psi * ax_h + sin_psi * ay_h
        a_lateral = -sin_psi * ax_h + cos_psi * ay_h
        return a_forward, a_lateral, az_v
