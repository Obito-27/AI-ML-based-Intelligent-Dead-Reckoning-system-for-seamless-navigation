# Dracarys IDR (SeamlessNav) — Comprehensive Technical Architecture & Engineering Guide (A to Z)

**Smart India Hackathon 2026 (SIH26168)**  
**Target Organization**: ISRO — Department of Space | **Track**: Software | **Theme**: Miscellaneous  
**Team**: Dracarys  

---

## Table of Contents
1. [Executive Summary & Problem Statement](#1-executive-summary--problem-statement)
2. [End-to-End System Architecture](#2-end-to-end-system-architecture)
3. [Sensors, Coordinate Frames & The Physics Problem](#3-sensors-coordinate-frames--the-physics-problem)
4. [Autonomous Mount Calibration & Gravity Leveling](#4-autonomous-mount-calibration--gravity-leveling)
5. [The AI Neural Motion Model (DracarysMotionNet)](#5-the-ai-neural-motion-model-dracarysmotionnet)
   - 5.1 Dataset & Ground Truth Extraction (IO-VNBD)
   - 5.2 Model Architecture: 1D-CNN + GRU
   - 5.3 Training Methodology, Loss Formulations & Multi-Task Heads
   - 5.4 Export Formats: ONNX, TFLite, and Custom Vectorized Binary (.bin)
6. [Multi-Rate Error-State Kalman Filter (ESKF) with Coriolis Dynamics](#6-multi-rate-error-state-kalman-filter-eskf-with-coriolis-dynamics)
   - 6.1 Strapdown Kinematics with Coriolis Cross-Coupling
   - 6.2 Non-Holonomic Constraints (NHC)
   - 6.3 Signal-Quality-Inflated Kalman Measurement Update
7. [Zero-Velocity Updates (ZUPT) & Strict Zero-Acceleration Gating](#7-zero-velocity-updates-zupt--strict-zero-acceleration-gating)
8. [Zero-Motion Detection & Strict Stationary Gating](#8-zero-motion-detection--strict-stationary-gating)
9. [Soft Multi-Candidate Map Matching Engine](#9-soft-multi-candidate-map-matching-engine)
10. [On-Device Android Application Engineering](#10-on-device-android-application-engineering)
    - 10.1 Why a Custom Pure-Kotlin Vectorized Engine instead of TF Lite Runtime?
    - 10.2 Jetpack Compose UI & Real-Time Dynamic Instrument Capsule
    - 10.3 Diagnostic Telemetry & Automated Outage Validation
11. [Empirical Benchmark Results & Ablation Studies](#11-empirical-benchmark-results--ablation-studies)
12. [Repository Layout, How to Train, Build & Run](#12-repository-layout-how-to-train-build--run)

---

## 1. Executive Summary & Problem Statement

### The Problem
Global Navigation Satellite System (GNSS / GPS) signals are inherently vulnerable to complete blackouts in tunnels, dense urban canyons, multi-level underground parking facilities, mountain passes, and dense tree canopies. When GPS cuts out:
- Conventional navigation applications freeze, jump erratically, or rely on linear extrapolation that diverges into buildings within seconds.
- Consumer smartphones only have noisy, low-cost micro-electro-mechanical systems (MEMS) accelerometers and gyroscopes.
- Direct mathematical double-integration of raw consumer accelerometer data causes position drift that explodes quadratically with time (drift proportional to 0.5 * a * t^2), yielding errors greater than 60% of total distance traveled within 30 to 60 seconds.
- Phones placed in cars are mounted arbitrarily: portrait in an air vent, tilted on a suction cup, laying on a passenger seat, or held in a walking pedestrian's hand. Without knowing how the phone is oriented relative to the vehicle, accelerometer readings mix forward thrust with gravitational force and lateral vehicle tilt.

### The Solution: Dracarys IDR (SeamlessNav)
Dracarys IDR solves this by fusing modern physics and deep learning:
1. **Physical Leveling & Calibration**: Real-time gravity-vector leveling and GNSS-course-coupled mount azimuth alignment determine the exact 3D orientation of the phone relative to the vehicle automatically—with zero user input or vehicle CAN-bus connection.
2. **AI Neural Motion Corrector (DracarysMotionNet)**: A lightweight 1D-CNN + GRU model running entirely on-device that extracts temporal motion signatures over trailing sensor windows to predict instantaneous forward speed, gyro bias correction, and epistemic confidence.
3. **Multi-Rate Error-State Kalman Filter (ESKF)**: Integrates body-frame kinematics with rotating Coriolis acceleration terms (dv_f/dt = a_f + omega_z * v_l, dv_l/dt = a_l - omega_z * v_f), Non-Holonomic Constraints (NHC, v_l ~ 0), and adaptive GNSS covariance inflation.
4. **Strict Zero-Acceleration Gating**: Prevents stationary phone drift by locking position when linear acceleration and gyro rates are at rest, clamping velocity and displacement increments to identically zero.
5. **Soft Multi-Candidate Map Matching**: An offline road network matching engine accelerated with a 2D KD-Tree that gently pulls dead-reckoned positions toward true street geometries without hard-clipping.

---

## 2. End-to-End System Architecture

```
+---------------------------------------------------------------------------------------------------+
|                                  HARDWARE SENSOR LAYER (10-100 Hz)                                |
|  [3D Accelerometer]   [3D Gyroscope]   [Gravity Vector]   [Rotation Vector]   [Multi-GNSS Fixes]  |
+---------------------------------------+-----------------------------------------------------------+
                                        |
                                        v
+---------------------------------------------------------------------------------------------------+
|                                 AUTONOMOUS CALIBRATION ENGINE                                     |
|  - Gravity-Vector Leveling: Align device Up vector with Earth Gravity via Rodrigues Rotation      |
|  - Mount Azimuth Alignment: Correlate horizontal accel with GNSS longitudinal acceleration        |
|  - Online Stationary Gyro Bias Learning during stationary intervals                               |
|  - Output: Vehicle Body-Frame Accelerations (a_forward, a_lateral, a_vertical) & Heading Rate     |
+---------------------------------------+-----------------------------------------------------------+
                                        |
                  +---------------------+---------------------+
                  |                                           |
                  v                                           v
+-----------------------------------+     +-------------------------------------------------------+
|  AI MOTION MODEL (MotionNet)      |     |  ZERO-MOTION & KINEMATICS ENGINE                      |
|  - Trailing 1.5s Rolling Buffer   |     |  - Physical force stop gate (||a_lin|| < 0.28 m/s^2)  |
|  - 1D-CNN Temporal Feature Extr.  |     |  - Sustained Forward Accel Accumulator                |
|  - Recurrent GRU State Cell       |     |  - Non-Holonomic Constraint (v_l ~ 0)                 |
|  - Output: v_f prior, d_omega,    |     |  - Gyroscope Zero-Bias Learning at Rest               |
|            Epistemic Confidence   |     +---------------------------+---------------------------+
+-----------------+-----------------+                                 |
                  |                                                   |
                  +---------------------+-----------------------------+
                                        |
                                        v
+---------------------------------------------------------------------------------------------------+
|                        MULTI-RATE ERROR-STATE KALMAN FILTER (ESKF)                                |
|  - High-Rate (10 Hz) Prediction: Coriolis body-frame strapdown integration                        |
|  - Low-Rate (~1 Hz) GNSS Measurement: Satellite-quality inflated covariance (HDOP, Sats, Age)     |
|  - Outage Protection: Seamless transition to Dead Reckoning without coordinate jumps              |
|  - Output: Filtered Local ENU Coordinates (x, y), Speed v_f, Heading psi                          |
+---------------------------------------+-----------------------------------------------------------+
                                        |
                                        v
+---------------------------------------------------------------------------------------------------+
|                       SOFT MULTI-CANDIDATE ROAD MAP MATCHER                                       |
|  - 2D KD-Tree Spatial Candidate Query within 35m Search Corridor                                  |
|  - Probabilistic weighting: Perpendicular Distance Gaussian x Heading Alignment Cosine            |
|  - Soft Pull: Gently nudges dead reckoning trace toward roadway centerline                         |
+---------------------------------------+-----------------------------------------------------------+
                                        |
                                        v
+---------------------------------------------------------------------------------------------------+
|                               USER INTERFACE & DIAGNOSTICS LAYER                                  |
|  - MapView: Offline Vector Street Grid + Vehicle Arrow + Uncertainty Corridor                      |
|  - Interactive Instrument Capsule: Live GNSS / Fused / DR Badge + Debug Outage Toggle              |
|  - 10 Hz Real-Time Telemetry Logging & Automatic Outage Error Validator Sheet                     |
+---------------------------------------------------------------------------------------------------+
```

---

## 3. Sensors, Coordinate Frames & The Physics Problem

### Coordinate Frames
1. **Device Sensor Frame (S)**: Defined by the physical body of the phone:
   - X_s: Across the phone screen (rightward).
   - Y_s: Along the length of the phone screen (upward).
   - Z_s: Perpendicular through the screen (pointing out toward user).
2. **Vehicle Body Frame (B)**: Defined by vehicle kinematics:
   - X_b (Lateral / a_l): Perpendicular to travel direction (pointing right).
   - Y_b (Forward / a_f): Parallel to vehicle direction of forward travel.
   - Z_b (Vertical / a_v): Pointing vertically Upward through vehicle roof.
3. **Local Navigation Frame (East-North-Up / ENU)**:
   - Tangent plane to Earth at reference GNSS coordinate (lat0, lon0).
   - X_enu: Points East.
   - Y_enu: Points North.
   - Heading angle psi: Measured clockwise from North (0 deg = North, 90 deg = East).

### The Sensor Drift Dilemma
If a smartphone is mounted tilted at 30 degrees pitch and acceleration is double-integrated naively:
a_measured = a_vehicle + g * sin(30 deg)
Earth's gravity is g = 9.80665 m/s^2. An error of just 0.1 m/s^2 integrated over 30 seconds yields:
Delta x = 0.5 * 0.1 * (30)^2 = 45 meters of error.
Over 60 seconds, that single offset creates 180 meters of artificial displacement. This is why standard phone dead reckoning fails within seconds without rigorous calibration and AI-assisted velocity aiding.

---

## 4. Autonomous Mount Calibration & Gravity Leveling

Our system implements a 4-step autonomous calibration pipeline in `DeviceOrientationCalibrator.kt` and `calibration.py`:

### 1. Gravity Vector Leveling (Pitch & Roll Resolution)
Under normal driving, the long-term low-pass average of acceleration is Earth's gravity vector g_dev. In Android Sensor.TYPE_GRAVITY, the sensor outputs the upward reaction force (+9.81 m/s^2 on Z when flat).
The unit vertical vector in device coordinates is:
u_up = g_dev / ||g_dev||
Using Rodrigues' rotation formula, we compute a 3x3 rotation matrix R_level that rotates any vector in device frame into a leveled horizontal plane where u_up maps exactly to [0, 0, 1]^T:
v = u_up x [0, 0, 1]^T,  c = u_up . [0, 0, 1]^T,  s = ||v||
R_level = I + [v]_x + [v]_x^2 * ((1 - c) / s^2)
Applying R_level strips out pitch and roll tilt completely.

### 2. Vehicle Heading Rate Extraction
The vehicle's true horizontal turning rate (yaw rate around vehicle vertical axis) is obtained by taking the dot product of the 3D gyroscope vector omega with the unit gravity vector:
omega_yaw = omega . u_up = (omega_x * g_x + omega_y * g_y + omega_z * g_z) / ||g||
This scalar is invariant to whether the phone is mounted upright, sideways, upside-down, or lying flat.

### 3. GNSS Course-Coupled Mount Azimuth Alignment (Yaw Angle of Phone in Car)
After leveling, the phone may still be rotated at an arbitrary azimuth psi_mount around the vertical axis (e.g., pointed 25 deg toward the driver):
[a_f; a_l] = [cos(psi_mount) sin(psi_mount); -sin(psi_mount) cos(psi_mount)] * [a_x_leveled; a_y_leveled]
When GNSS is active and the vehicle is driving (v > 3.0 m/s), longitudinal acceleration is observable from GPS speed differences: a_ref = Delta v_GNSS / Delta t.
We formulate a closed-form least-squares estimator:
psi_mount = atan2(sum(a_y_leveled * a_ref), sum(a_x_leveled * a_ref))
As soon as the car accelerates forward, the system locks psi_mount within 2 to 3 seconds.

> [!NOTE]
> **Testing Boundary & Physical Drive Limitation**:
> Streaming mount-azimuth convergence has been rigorously verified against synthetic/injected dynamic motion profiles and unit tests under compound 3D tilts, but has not yet been validated on a physical vehicle driving on real open roads. This is an inherent limitation of emulator-based sensor HAL testing. Demonstrating mount-azimuth convergence on an actual physical drive is flagged as the #1 priority verification task for upcoming real-world field trials.

### 4. Adaptive Stationary Gyro Bias Tracking
Whenever the vehicle stops at a traffic light or is stationary, the physical rotation rate is known to be identically zero:
b_gyro[k] = 0.95 * b_gyro[k-1] + 0.05 * omega_raw[k]
This eliminates the constant sensor thermal drift that causes dead reckoning to rotate in circles.

---

## 5. The AI Neural Motion Model (DracarysMotionNet)

### 5.1 Dataset & Ground Truth Extraction
We trained exclusively on the **IO-VNBD** (Input-Output Vehicle Navigation Benchmark Dataset; Onyekpe et al., 2021) featuring real smartphone sensor logs paired with vehicle onboard CAN-bus diagnostics and high-precision reference GNSS across dozens of real highway and urban driving sessions:
- **Training Drives**: S1, S2, M (~35,000 real 10 Hz samples).
- **Validation / Held-Out Drive**: S4 (completely unseen during training).
- **Ground Truth**:
  - Longitudinal Vehicle Speed: v_f recorded directly from wheel-speed encoders via OBD-II / CAN-bus (v_gt).
  - True Heading Rate: Differential rear-wheel speed yaw rate (omega_gt = (v_rr - v_rl) / track_width).
  - Target Gyroscope Error: Delta omega_z = omega_sensor - omega_gt.

### 5.2 Model Architecture: 1D-CNN + GRU
The network architecture is defined in `models.py`:

```
Input Tensor: (Batch, Window=15, Features=8)
Features: [af, al, az, gx, gy, gz, ||a_horiz||, jerk_proxy]
       |
       v
Conv1D (in=8, out=32, kernel=3, padding=1) -> BatchNorm1D -> ReLU
       |
       v
Conv1D (in=32, out=48, kernel=3, padding=1) -> BatchNorm1D -> ReLU
       |
       v
GRU Cell (input_size=48, hidden_size=48, batch_first=True, num_layers=1)
       |
       +-----------------------------------+-----------------------------------+
       | (Last step hidden state: 48)      |                                   |
       v                                   v                                   v
Forward Velocity Head               Heading Rate Head                   Confidence Head
Linear(48 -> 24) -> ReLU            Linear(48 -> 24) -> ReLU            Linear(48 -> 16) -> ReLU
Linear(24 -> 1)                     Linear(24 -> 1)                     Linear(16 -> 1) -> Sigmoid
       |                                   |                                   |
       v                                   v                                   v
v_f prior (m/s)                     delta_omega correction (rad/s)      sigma in [0.10, 0.95]
```

#### Strict Causal Rolling Window Guarantee
The temporal window size is W = 15 samples (1.5 seconds at 10 Hz).
At time step k, the window consists strictly of trailing samples:
X[k] = {s[k-14], s[k-13], ..., s[k]}
The model **never peeks forward into future samples** (k+1, k+2). This mathematical property was verified with automated unit tests to guarantee identical behavior between offline Python evaluation and real-time Android streaming.

### 5.3 Training Methodology & Multi-Task Loss
Trained in PyTorch using AdamW optimizer (lr = 1e-3, weight decay = 1e-4):
Loss_total = SmoothL1(v_f_pred, v_f_gt) + 15.0 * MSE(delta_omega_pred, delta_omega_gt)
- Forward speed achieved a Mean Absolute Error (MAE) of **1.18 m/s** on the completely unseen S4 drive.
- Heading rate correction suppresses high-frequency vibration spikes without lagging vehicle turns.

### 5.4 Export Formats
1. **PyTorch State Dict** (`dracarys_motion_net.pt`): Native training checkpoints.
2. **ONNX Model** (`dracarys_motion_net.onnx`, **0.043 MB**): 0.42 ms CPU execution time on desktop/edge.
3. **Custom Vectorized Binary Weights** (`dracarys_motion_net.bin`, **0.095 MB**): A high-performance, self-contained binary weight container structured with a `TFL3` magic header, JSON tensor descriptor block, and 16-byte aligned float arrays.

---

## 6. Multi-Rate Error-State Kalman Filter (ESKF) with Coriolis Dynamics

The core fusion filter is implemented in `ESKFFusion.kt` and `fusion.py`.

### 6.1 Strapdown Kinematics with Coriolis Terms
In a rotating vehicle coordinate system, linear accelerations in body frame are coupled with angular rate:
dv_f / dt = a_f + omega_z * v_l
dv_l / dt = a_l - omega_z * v_f
At 10 Hz step interval dt = 0.10 s, heading and velocities are propagated:
omega_corrected = omega_z - delta_omega_AI - b_gyro_hat
psi[k] = psi[k-1] + omega_corrected * dt
v_f_raw[k] = v_f[k-1] + (a_f + omega_corrected * v_l[k-1]) * dt

### 6.2 Non-Holonomic Constraints (NHC)
Cars, buses, and bikes drive where their wheels point; they cannot slip sideways through asphalt. Therefore, true lateral velocity v_l ~ 0.
We enforce this constraint using a soft Kalman gain K_nhc = 0.50:
v_l[k] = v_l_pred + K_nhc * (0 - v_l_pred)
This prevents centripetal acceleration during sharp turns from corrupting position.

### 6.3 AI Pseudo-Measurement Fusion
Rather than blindly trusting neural network outputs, the AI forward velocity v_f_AI enters as an adaptive prior weighted by the network's confidence sigma in [0.10, 0.95]:
v_f[k] = (1 - sigma) * v_f_raw[k] + sigma * v_f_AI[k]
Local position integration:
Delta x = (v_f * cos(psi) - v_l * sin(psi)) * dt
Delta y = (v_f * sin(psi) + v_l * cos(psi)) * dt
x[k] = x[k-1] + Delta x,  y[k] = y[k-1] + Delta y

### 6.4 Continuous Signal-Quality Covariance Inflation
When GNSS updates arrive (~1 Hz), the measurement covariance R_GNSS is scaled continuously based on live satellite health:
R_GNSS = R_0 * max(1.0, (accuracy / 3.0)^2) * S_sat * (1.0 + 1.5 * max(0, age - 1.2))
Where S_sat = 1.0 for >= 12 satellites and escalates to 20.0 for < 4 satellites. This ensures smooth handoffs without position jumps when entering or exiting tunnels.

---

## 7. Zero-Velocity Updates (ZUPT) & Strict Zero-Acceleration Gating

### The "Drifting at Rest" Bug & Resolution
In initial test builds, if a user sat stationary in their room, the neural net prior (trained on moving vehicle logs) could suggest a nominal speed (~1.5 m/s), causing the filter to drift across the room.

### Physical Force Stop Gate
We instituted a strict physical force stop gate in `LiveNavigationRepository.kt`:
1. **Total Linear Acceleration Magnitude**: ||a_lin|| = sqrt(a_x^2 + a_y^2 + a_z^2)
2. **Body Longitudinal/Lateral Accelerations**: |a_f|, |a_l|
3. **Total Gyroscope Rotation**: ||omega|| = sqrt(omega_x^2 + omega_y^2 + omega_z^2)

Condition for Physical Rest:
isPhysicallyResting = (||a_lin|| < 0.28 m/s^2) AND (|a_f| < 0.22 m/s^2) AND (|a_l| < 0.22 m/s^2) AND (||omega|| < 0.08 rad/s)

When `isStationary` evaluates to `true`:
- Velocity is clamped: v_f = 0.0, v_l = 0.0, v_x = 0.0, v_y = 0.0.
- Neural network speed priors are bypassed.
- Gyroscope zero-bias update is triggered.
- **Result**: The map position marker completely freezes with zero drift and zero rotation.

---

## 8. Zero-Motion Detection & Strict Stationary Gating

This project implements **Vehicle Dead Reckoning (VDR) only**, per the SIH26168 problem statement. The system uses a multi-layered stationary detection pipeline to prevent phantom drift when the vehicle is parked or stopped at a traffic light:

| Condition | Threshold | Effect |
| :--- | :---: | :--- |
| **Near-zero linear acceleration** | ‖a_lin‖ < 0.28 m/s², \|a_f\| < 0.22, \|a_l\| < 0.22 | Sets `isNearZeroAccel = true` |
| **Near-zero gyroscope rate** | ‖ω‖ < 0.08 rad/s | Sets `isNearZeroGyro = true` |
| **Physical rest** | Both of the above | `isPhysicallyResting = true` — forces `isVehicleMoving = false` |
| **Forward acceleration accumulator decay** | `forwardAccelAccum < 0.15` | Confirms no recent sustained forward thrust |
| **Low fusion speed** | v_f < 0.50 m/s with low accel and gyro | Additional stationary confirmation |

When stationary: velocity (v_f, v_l) and displacement increments (vx, vy) are clamped to identically **0.0**. Gyroscope zero-rate bias is adaptively learned during rest periods.

---

## 9. Soft Multi-Candidate Map Matching Engine

Implemented in `SoftMapMatcher.kt` and `mapmatch.py`:

1. **Road Network Indexing**: Road polyline segments are stored in a 2D KD-Tree indexed by segment midpoints.
2. **Spatial Candidate Query**: At each step, road segments within a perpendicular corridor d_corridor = 35 meters are retrieved in logarithmic time O(log N).
3. **Probabilistic Weighting**: For each candidate segment i, we compute the closest point p_cand_i, perpendicular distance d_i, and heading alignment cosine:
   w_i = exp(-d_i^2 / (2 * sigma_dist^2)) * max(0.1, |cos(psi_veh - theta_road_i)|)
4. **Soft Nudge vs. Hard Snapping**:
   p_target = sum(w_i * p_cand_i) / sum(w_i)
   p_final = (1 - alpha_pull) * p_ESKF + alpha_pull * p_target  (alpha_pull = 0.45)
   This prevents the "train-on-rails" glitch of hard-snapping if a car turns into an unmapped driveway or parking lot.

---

## 10. On-Device Android Application Engineering

### 10.1 Why a Custom Vectorized Pure-Kotlin Inference Engine?
Standard mobile ML deployments bundle `org.tensorflow:tensorflow-lite` or ONNX Runtime mobile libraries. For Dracarys IDR, we made an intentional design decision to write our own forward pass (`MotionNetInference.kt`):
- **APK Footprint**: Standard TF Lite adds 15 to 25 MB of compiled `.so` C++ libraries for multiple CPU architectures. Our pure-Kotlin engine adds **0 MB of native overhead**, keeping the total APK size to 18.7 MB (including full offline vector map tiles).
- **JNI Latency**: Crossing Java-to-C++ boundaries 10 times a second creates garbage collector pressure and thread synchronization overhead. In-memory Kotlin arrays execute in < 0.25 ms.
- **Zero-Dependency Testing**: The entire inference engine runs natively in JVM unit tests on Windows, Linux, and CI/CD pipelines without needing Android hardware or emulators.

### 10.2 Jetpack Compose UI Architecture
- **MapViewComposable**: Renders offline vector OpenStreetMap street geometry, vehicle navigation triangle, heading frustum, breadcrumb trajectory, and expanding uncertainty ellipse.
- **Dynamic Instrument Capsule**:
  - **Collapsed State**: Visible during healthy GNSS lock; displays current positioning mode badge (GNSS / Fused / Dead Reckoning) and vehicle status icon.
  - **Expanded State**: Activates smoothly upon GNSS outage; reveals live model confidence meter, accumulated drift percentage bar (green < 10%, red > 10%), elapsed outage chronometer, and distance-to-last-fix metric.
- **Sensor Diagnostics Modal**: Real-time HUD showing calibrated 3D accelerometer readings, vehicle yaw rate, mount azimuth angle, AI speed inference, satellite count, and GPS accuracy radius.

---

## 11. Empirical Benchmark Results & Ablation Studies

Evaluated on the **IO-VNBD** real driving dataset across **158 genuine GNSS blackout scenarios** across durations {15s, 30s, 45s, 60s} using Leave-One-Drive-Out Cross-Validation (LODO-CV):

| System Configuration | Median Drift % | P90 Drift % | Worst-Case Drift % | Pass Rate (<10% Drift) |
| :--- | :---: | :---: | :---: | :---: |
| **1. Raw IMU Double Integration (Baseline)** | 62.42% | 157.31% | 694.76% | 6.3% |
| **2. ESKF + Non-Holonomic Constraints (NHC)** | 25.42% | 65.34% | 154.60% | 17.1% |
| **3. ESKF + NHC + AI MotionNet Velocity** | 15.43% | 45.79% | 135.53% | 25.3% |
| **4. Full Dracarys IDR (+ Soft Map-Match)** | **11.22%** | **43.94%** | **90.82%** | **46.2%** |

On the highway/suburban transition drive (`S4` held-out fold):
- **Median Drift %**: **8.06%**
- **Pass Rate (<10% Drift)**: **61.0%**

---

## 12. Repository Layout, How to Train, Build & Run

### Directory Structure
```
dracarys_idr/
├── README.md                           # Quickstart summary
├── PROJECT_GUIDE_A_TO_Z.md             # This comprehensive technical guide
├── data/                               # Real IO-VNBD dataset logs (S1, S2, S4, M)
├── training/
│   ├── dracarys_idr/
│   │   ├── calibration.py              # Python device orientation & mount leveling
│   │   ├── fusion.py                   # Python ESKF + NHC + Coriolis implementation
│   │   ├── models.py                   # PyTorch 1D-CNN + GRU model definition
│   │   ├── train.py                    # Multi-drive training pipeline
│   │   ├── mapmatch.py                 # Offline KD-Tree road matching
│   │   ├── export_onnx.py              # ONNX model exporter
│   │   ├── export_tflite.py            # TFL3 binary weight format packer
│   │   └── stress_test.py              # 158-outage LODO-CV benchmark engine
│   └── tests/                          # Pytest suite for physics & causal invariance
└── app/
    ├── app/src/main/
    │   ├── AndroidManifest.xml         # Sensor & Activity permissions
    │   ├── assets/
    │   │   └── dracarys_motion_net.bin # Production neural network binary weights (95 KB)
    │   └── java/com/dracarys/idr/
    │       ├── MainActivity.kt         # Edge-to-edge window & runtime permission handler
    │       ├── calibration/            # DeviceOrientationCalibrator.kt
    │       ├── fusion/                 # ESKFFusion.kt (Strapdown mechanization)
    │       ├── ml/                     # MotionNetInference.kt (Pure-Kotlin forward pass)
    │       ├── mapmatch/               # SoftMapMatcher.kt & KdTree2D.kt
    │       ├── sensors/                # SensorCollector.kt & LiveNavigationRepository.kt
    │       └── ui/                     # Jetpack Compose UI (Capsule, Map, Badges)
    └── gradlew.bat                     # Gradle wrapper build tool
```

### Build & Execution Instructions

#### 1. Train or Benchmark Model (Python)
```bash
# Run 158-scenario stress test benchmark
python -m training.dracarys_idr.stress_test

# Train model from scratch on drives S1, S2, M and validate on S4
python -m training.dracarys_idr.train
```

#### 2. Run JVM Unit Tests
```bash
cd app
./gradlew testDebugUnitTest
```

#### 3. Build & Install Android APK
```bash
cd app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
