"""Tests for 2D/3D strapdown mechanization and Coriolis coupling verification."""

import numpy as np
import pytest

from training.dracarys_idr.mechanization import raw_mechanization


def test_straight_line_mechanization():
    n = 100
    dt = 0.10
    t = np.arange(n) * dt
    a_f = np.zeros(n)
    a_l = np.zeros(n)
    gyro_z = np.zeros(n)
    v0 = 10.0  # 10 m/s straight line
    psi0 = 0.0 # Eastbound
    
    res = raw_mechanization(t, a_f, a_l, gyro_z, v0=v0, psi0=psi0, dt=dt)
    expected_dist = v0 * t[-1]
    assert abs(res["x"][-1] - expected_dist) < 0.1
    assert abs(res["y"][-1]) < 1e-6
    assert abs(res["vl"][-1]) < 1e-6


def test_coriolis_coupling_term_significance():
    """Confirms that in a constant turn (v*omega), lateral force equals centripetal force.
    
    Without the -omega*vf term in dot(vl), lateral velocity explodes.
    """
    n = 200
    dt = 0.10
    t = np.arange(n) * dt
    v0 = 15.0
    omega = 0.10  # constant yaw rate (rad/s)
    
    # In a coordinated turn, accelerometer measures centripetal acceleration: a_l = v0 * omega
    a_f = np.zeros(n)
    a_l = np.ones(n) * (v0 * omega)
    gyro_z = np.ones(n) * omega
    
    res = raw_mechanization(t, a_f, a_l, gyro_z, v0=v0, psi0=0.0, dt=dt)
    
    # Because a_l - omega * vf = (v0*omega) - omega*v0 = 0, vl must remain bounded near 0!
    assert np.max(np.abs(res["vl"])) < 0.05, "Coriolis term failed to balance centripetal acceleration!"
