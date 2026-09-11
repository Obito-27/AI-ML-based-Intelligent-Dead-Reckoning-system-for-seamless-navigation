"""2D/3D Strapdown Inertial Mechanization for Dracarys IDR.

Implements body-frame inertial navigation equations with mandatory
rotating-frame (Coriolis/centripetal) coupling terms:
  dot(v_f) = a_f + omega_z * v_l
  dot(v_l) = a_l - omega_z * v_f
Omitting these terms in vehicle strapdown navigation causes runaway lateral divergence
during turns because lateral acceleration in a turn is primarily v * omega.
"""

from typing import Dict, Tuple
import numpy as np


def raw_mechanization(
    t: np.ndarray,
    a_f: np.ndarray,
    a_l: np.ndarray,
    gyro_z: np.ndarray,
    v0: float,
    psi0: float,
    x0: float = 0.0,
    y0: float = 0.0,
    dt: float = 0.10,
) -> Dict[str, np.ndarray]:
    """Performs raw uncorrected strapdown double integration.
    
    Args:
        t: Time vector (N,)
        a_f: Forward specific force (N,) in m/s^2
        a_l: Lateral specific force (N,) in m/s^2
        gyro_z: Yaw rate (N,) in rad/s
        v0: Initial forward speed (m/s)
        psi0: Initial heading (rad)
        x0, y0: Initial coordinates in local ENU (m)
        dt: Sample delta (seconds)
        
    Returns:
        dict containing 'x', 'y', 'vf', 'vl', 'psi', 'vx', 'vy'
    """
    n = len(t)
    psi = np.zeros(n)
    vf = np.zeros(n)
    vl = np.zeros(n)
    x = np.zeros(n)
    y = np.zeros(n)
    vx = np.zeros(n)
    vy = np.zeros(n)
    
    psi[0] = psi0
    vf[0] = v0
    vl[0] = 0.0
    x[0] = x0
    y[0] = y0
    vx[0] = v0 * np.cos(psi0)
    vy[0] = v0 * np.sin(psi0)
    
    for k in range(1, n):
        omega = gyro_z[k]
        
        # Heading integration
        psi[k] = psi[k - 1] + omega * dt
        
        # Rotating body-frame velocity update with Coriolis coupling
        vf[k] = vf[k - 1] + (a_f[k] + omega * vl[k - 1]) * dt
        vl[k] = vl[k - 1] + (a_l[k] - omega * vf[k - 1]) * dt
        
        # Navigation frame velocities (ENU)
        cos_p = np.cos(psi[k])
        sin_p = np.sin(psi[k])
        vx[k] = vf[k] * cos_p - vl[k] * sin_p
        vy[k] = vf[k] * sin_p + vl[k] * cos_p
        
        # Position integration
        x[k] = x[k - 1] + vx[k] * dt
        y[k] = y[k - 1] + vy[k] * dt
        
    return {
        "x": x,
        "y": y,
        "vf": vf,
        "vl": vl,
        "psi": psi,
        "vx": vx,
        "vy": vy,
    }
