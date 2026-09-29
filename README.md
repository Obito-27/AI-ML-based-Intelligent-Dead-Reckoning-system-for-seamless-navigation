# Dracarys IDR — AI-ML Based Intelligent Dead Reckoning System

**Smart India Hackathon 2026 (SIH26168)**
**Organization**: ISRO — Department of Space | **Track**: Software | **Theme**: Miscellaneous
**Team**: Dracarys
**Team Members: 1. Salil Sampat
                2. Parth Kanade
                3. Viraj Chodhary
                4. Anushka Patil
                5. Yogeswari Bhoi
                6. Neha Chhipa

---

## 1. System Overview

Dracarys IDR (SeamlessNav) is a high-rate, multi-tier Intelligent Dead Reckoning system designed to maintain route-consistent vehicle positioning through complete GNSS blackouts (tunnels, dense foliage, urban canyons) using only smartphone IMU sensors, augmented by an on-device neural motion correction network, and transitioning seamlessly back to GNSS upon reacquisition.

### Core Architecture
1. **Multi-Rate Fusion Core**:
   - 10 Hz strapdown mechanization carrying rotating body-frame Coriolis coupling terms ($\dot{v}_f = a_f + \omega_z v_l$, $\dot{v}_l = a_l - \omega_z v_f$).
   - Asynchronous ~1 Hz GNSS Kalman measurement update triggered only on fresh fix arrival.
   - Continuous signal-quality covariance inflation ($R_{\text{GNSS}}$ scaling continuously with satellite count, HDOP/accuracy radius, and fix age), producing a seamless handoff without mode-switch jumps.
   - Non-Holonomic Constraints (NHC) enforcing $v_l \approx 0$ with online gyro bias adaptation.
2. **On-Device Neural Motion Corrector (`DracarysMotionNet`)**:
   - Compact 1D-CNN + GRU model (< 150 KB, 23,000 parameters).
   - Strictly **trailing (causal)** rolling feature windows ($15$ steps = $1.5$s at 10 Hz). Never peeks into future samples.
   - Predicts forward speed $v_f$ (m/s), yaw rate correction $\Delta \omega_z$ (rad/s), and confidence $\sigma \in [0.1, 0.95]$.
   - AI outputs act strictly as pseudo-measurements inside the classical ESKF filter—never direct black-box positions.
3. **Soft Multi-Candidate Map Matching**:
   - Dynamically derives geographic bounding box per drive from its own GPS trace (works across UK, Nigeria, France, India).
   - Evaluates road candidate likelihood via distance and heading consistency within a bounded corridor (35 m).
   - Gently pulls position estimate toward the road polyline without hard-snapping.
4. **Dual Deployment Targets**:
   - **Android App**: 10 Hz smartphone IMU + GNSS with custom on-device binary weights container (`dracarys_motion_net.bin`, ~0.095 MB) and a hand-written pure-Kotlin vectorized forward pass (`MotionNetInference.kt`). This architecture was selected deliberately over standard `org.tensorflow:tensorflow-lite` to eliminate 15+ MB native C++ runtime bloat, eliminate JNI boundary crossing overhead on 10 Hz sensor threads, and enable pure JVM unit testing.
   - **Edge Engine**: Sensor-agnostic C++/Python engine running up to 200 Hz for external IMU/FOG sensors via ONNX Runtime (`dracarys_motion_net.onnx`, 0.043 MB, 0.42 ms CPU latency).

---

## 2. Real-Data Benchmark Deliverables (IO-VNBD Dataset)

All figures, plots, and models are trained and benchmarked strictly on **real** IO-VNBD paired drives (`S-*.csv` and `V-*.csv`, Onyekpe et al., 2021). Zero synthetic data was used in evaluation reports.

### Primary Benchmark: Full Leave-One-Drive-Out Cross-Validation (LODO-CV)
Evaluated across **158 real outage scenarios** on 4 drives (`S4`, `S2`, `M`, `S1`) across durations $\{15\text{s}, 30\text{s}, 45\text{s}, 60\text{s}\}$. Every outage is evaluated strictly by an out-of-fold model trained on the other 3 drives (100% genuine drive-level holdout):

| Configuration | Median Drift % | P90 Drift % | Worst-Case Drift % | Pass Rate (<10%) |
|---|---|---|---|---|
| **1. Raw IMU mechanization (baseline)** | 62.42% | 157.31% | 694.76% | 6.3% |
| **2. ESKF + NHC (no AI, no map)** | 25.42% | 65.34% | 154.60% | 17.1% |
| **3. ESKF + NHC + AI velocity** | 15.43% | 45.79% | 135.53% | 25.3% |
| **4. Full IDR (+ Soft Map-Match)** | **11.22%** | **43.94%** | **90.82%** | **46.2%** |

### Single-Fold Held-Out Benchmark (Drive S4 Only, 41 Outages)
Trained on `S1, S2, M` and evaluated strictly on unseen Drive `S4` (representative of highway/urban transition):

| Configuration | Median Drift % | P90 Drift % | Worst-Case Drift % | Pass Rate (<10%) |
|---|---|---|---|---|
| **Raw IMU mechanization** | 60.58% | 130.08% | 251.66% | 9.8% |
| **ESKF + NHC** | 26.08% | 52.80% | 83.25% | 12.2% |
| **ESKF + NHC + AI velocity** | 8.94% | 21.86% | 33.30% | 53.7% |
| **Full IDR (+ Soft Map-Match)** | **8.06%** | **22.47%** | **78.54%** | **61.0%** |

### Side-by-Side Comparison against Competing Baseline

| Metric | Competing Published Repo (443 Scenarios, 7 Drives) | Dracarys IDR (Primary LODO-CV, 158 Outages) | Dracarys IDR (Single-Fold S4 Holdout, 41 Outages) |
|---|---|---|---|
| **Median Drift %** | **1.16%** | **11.22%** | **8.06%** |
| **P90 Drift %** | **29.93%** | **43.94%** | **22.47%** |
| **Worst-Case Drift %** | **63.23%** | **90.82%** | **78.54%** |
| **Pass Rate (<10%)** | **73.1%** | **46.2%** | **61.0%** |

*Open Questions on Gap Analysis*:
- The competing repo reported 1.16% median drift / 73.1% pass rate on a 443-scenario, 7-drive IO-VNBD test. The gap between their 1.16% and our 11.22% LODO (or 8.06% S4-holdout) is not yet fully explained.
- We observe that in their published table, adding OSM snapping collapsed median drift from 9.18% to 1.16%, yet their P90 (29.93%) and worst-case (63.23%) were byte-identical before and after OSM snapping, indicating hard centerline snapping on typical paths with full disengagement on the tail.
- Their raw IMU baseline reported 9.07% median drift, vs our 60–62%. A 9% uncorrected raw drift indicates their evaluation may have had a high proportion of straight motorway segments (where they reported a 95.9% pass rate), shorter outage windows, or CAN speed aiding. Without their exact outage timestamps and source scripts, this remains an open question.

*Generated Reports & Visualizations*:
- Numerical metrics: `training/reports/results.json`
- Trajectory ablation: `training/reports/trajectory_ablation_REAL.png`
- Drift vs distance curve: `training/reports/drift_vs_distance_REAL.png`

---

## 3. Verification vs Road-Testing Matrix

Plain-language status of verification:

| Capability / Component | Verification Status | Verification Method |
|---|---|---|
| **Python Training Pipeline** | Verified | End-to-end training loop on real IO-VNBD files (`S1`, `S2`, `M`), validated on held-out `S4`. |
| **Mechanization & Coriolis Terms** | Verified | Unit tests in `test_mechanization.py` numerically verify centripetal force balance ($a_l = v \cdot \omega$). |
| **Multi-Rate Fusion (10Hz / 1Hz)** | Verified | Unit test in `test_fusion.py` verifies state propagation at 10 Hz and Kalman update strictly on 1 Hz GPS arrivals. |
| **Causal Rolling Window Invariance** | Verified | Assertion in `test_models.py` proves future sample corruption produces zero change in window features and predictions. |
| **Android App (JVM Tests)** | Verified | 41/41 unit tests passing (`DeviceOrientationCalibratorTest`, `SoftMapMatcherTest`, `MotionNetInferenceTest`, `NumericalCrossCheckTest`, `WcagContrastTest`, `NavigationModeColorTest`, `FakeRepositoryTest`). |
| **Android App (APK Build)** | Verified | Debug APK assembled via `./gradlew assembleDebug` (`app-debug.apk`, 18.72 MB). |
| **Android UI Instrumented Tests** | Authored (Pending Device) | 15 Compose UI tests authored (`ConfidenceBarTest`, `DriftBarTest`, `CapsuleAnimationTest`). Unexecuted due to no attached emulator/device. |
| **Model Footprint (< 5 MB)** | Verified | ONNX model is **0.043 MB**; custom binary container is **0.095 MB** (well under 5 MB limit). |
| **Inference Latency (Edge Engine)** | Verified | Benchmarked at **0.42 ms** on CPU (over 2,300 Hz capability, exceeding 200 Hz requirement). |
| **Dynamic Per-Drive OSM Bbox** | Verified | Successfully computed bounding boxes and extracted road graphs across all drives. |
| **Realistic Indian Road Conditions** | **Pending Field Test** | Potholes, sudden speed-breakers, extreme two-wheeler lean angles, and NavIC multi-GNSS chip reception require real-device on-road data collection. |
| **Phone Mount Vibration & Shifting** | **Pending Field Test** | Handlebar phone mount vibrations in traffic requires physical on-vehicle verification. |
