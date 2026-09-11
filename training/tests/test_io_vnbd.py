"""Tests for IO-VNBD dataset loader and schema verification."""

import pytest
from pathlib import Path
import numpy as np

from training.dracarys_idr.data.io_vnbd import (
    load_drive,
    list_available_drives,
    geodetic_to_enu,
    find_drive_files,
)


def test_list_available_drives():
    drives = list_available_drives()
    assert len(drives) > 0, "No IO-VNBD drives found!"
    assert "S1" in drives
    assert "S2" in drives
    assert "S4" in drives


def test_missing_drive_raises_filenotfound():
    with pytest.raises(FileNotFoundError) as exc_info:
        load_drive("NON_EXISTENT_DRIVE_999")
    assert "IO-VNBD real drive files not found" in str(exc_info.value)


def test_geodetic_to_enu_accuracy():
    lat0, lon0 = 52.40166, -1.50529
    # Point at origin
    x0, y0 = geodetic_to_enu(np.array([lat0]), np.array([lon0]), lat0, lon0)
    assert abs(x0[0]) < 1e-6
    assert abs(y0[0]) < 1e-6
    
    # 0.01 deg North shift (~1.11 km)
    x1, y1 = geodetic_to_enu(np.array([lat0 + 0.01]), np.array([lon0]), lat0, lon0)
    assert abs(x1[0]) < 1e-3
    assert 1100.0 < y1[0] < 1120.0


def test_real_drive_loading_and_properties():
    drive = load_drive("S1", max_samples=500)
    assert len(drive.t) == 500
    assert drive.dt == 0.10
    assert len(drive.a_f) == 500
    assert len(drive.a_l) == 500
    assert len(drive.gyro_z) == 500
    assert len(drive.v_gt) == 500
    assert len(drive.x_gt) == 500
    
    # Verify 1 Hz GPS update behavior in 10 Hz stream
    # Fresh fixes should occur approximately every 10 samples (not every single sample)
    fresh_count = drive.gps_fresh.sum()
    assert 1 <= fresh_count <= 60, f"Expected sparse GPS updates, got {fresh_count}"
    assert drive.fix_age.max() >= 0.8
