"""Performance & Drift Metrics for Dracarys IDR Navigation Evaluation.

Computes:
- Final drift (m) and Drift percentage of distance travelled (%)
- Root Mean Squared Error (RMSE in meters)
- Circular Error Probable (CEP50 and CEP95 in meters)
- Hard requirement validation: Pass if Drift % < 10.0%
"""

from typing import Dict
import numpy as np


def compute_metrics(
    x_est: np.ndarray,
    y_est: np.ndarray,
    x_gt: np.ndarray,
    y_gt: np.ndarray,
    dist_traveled: float,
) -> Dict[str, float]:
    """Calculates all key navigation error metrics over an outage segment.
    
    Args:
        x_est, y_est: Estimated trajectory in local ENU (m)
        x_gt, y_gt: Reference ground truth trajectory in local ENU (m)
        dist_traveled: Total ground distance traversed during outage (m)
        
    Returns:
        Dictionary with final_drift_m, drift_pct, rmse_m, cep50_m, cep95_m, passed
    """
    err = np.hypot(x_est - x_gt, y_est - y_gt)
    final_drift = float(err[-1])
    dist = max(1.0, float(dist_traveled))
    drift_pct = float(100.0 * final_drift / dist)
    rmse = float(np.sqrt(np.mean(err ** 2)))
    cep50 = float(np.median(err))
    cep95 = float(np.percentile(err, 95))
    passed = bool(drift_pct < 10.0)
    
    return {
        "final_drift_m": final_drift,
        "drift_pct": drift_pct,
        "rmse_m": rmse,
        "cep50_m": cep50,
        "cep95_m": cep95,
        "dist_m": dist,
        "passed": passed,
    }


def compute_drift_curve(
    x_est: np.ndarray,
    y_est: np.ndarray,
    x_gt: np.ndarray,
    y_gt: np.ndarray,
    dist_cum: np.ndarray,
) -> Tuple[np.ndarray, np.ndarray]:
    """Computes point-by-point drift percentage as distance accumulates."""
    err = np.hypot(x_est - x_gt, y_est - y_gt)
    drift_pct = 100.0 * err / np.maximum(dist_cum, 1.0)
    return dist_cum, drift_pct
