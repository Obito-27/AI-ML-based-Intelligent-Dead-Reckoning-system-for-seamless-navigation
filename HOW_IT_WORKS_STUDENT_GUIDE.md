# Dracarys IDR (SeamlessNav) — The High Schooler's Complete Guide to Navigation Without GPS

> **Who is this guide for?**  
> Anyone in 10th to 12th grade who knows basic physics (speed, acceleration, gravity) and is curious about how AI, math, and smartphones work together to solve real-world engineering problems!

---

## 📚 Table of Contents
1. [The Big Problem: What Happens When GPS Dies?](#1-the-big-problem-what-happens-when-gps-dies)
2. [What are GNSS and IMU Sensors? (And How Do They Work?)](#2-what-are-gnss-and-imu-sensors-and-how-do-they-work)
3. [The Core Nightmare: Why Can't We Just "Add Up" Accelerometer Data?](#3-the-core-nightmare-why-cant-we-just-add-up-accelerometer-data)
4. [Calibration: How Does the Phone Know Which Way the Car is Pointing?](#4-calibration-how-does-the-phone-know-which-way-the-car-is-pointing)
5. [The AI & Machine Learning: What Does It Do and How Was It Trained?](#5-the-ai--machine-learning-what-does-it-do-and-how-was-it-trained)
6. [The "Brain": What is a Kalman Filter (ESKF)?](#6-the-brain-what-is-a-kalman-filter-eskf)
7. [What Happens When GPS is ON vs. When GPS is OFF?](#7-what-happens-when-gps-is-on-vs-when-gps-is-off)
8. [The "Why Is My Phone Moving When I'm Sitting Still?" Problem (Zero-Acceleration Stop)](#8-the-why-is-my-phone-moving-when-im-sitting-still-problem-zero-acceleration-stop)
9. [Vehicle Dead Reckoning: How the Car Tracks Itself Without GPS](#9-vehicle-dead-reckoning-how-the-car-tracks-itself-without-gps)
10. [Map Matching: Putting the Car on the Road Gently](#10-map-matching-putting-the-car-on-the-road-gently)
11. [How We Built the Android App Without Making It Huge and Slow](#11-how-we-built-the-android-app-without-making-it-huge-and-slow)
12. [Summary: Everything from A to Z in One Simple Picture](#12-summary-everything-from-a-to-z-in-one-simple-picture)

---

## 1. The Big Problem: What Happens When GPS Dies?

Imagine you are in a car using Google Maps or Apple Maps. You drive into a long tunnel under a mountain, or into an underground multi-level parking garage, or between giant skyscrapers in Mumbai or New York.

Suddenly, your navigation app gets confused:
- The blue location dot freezes.
- Or it starts drifting through buildings and lakes.
- Or it spins in circles.

Why? Because **GPS requires line-of-sight to satellites in space**. Concrete, soil, tunnels, and dense foliage block satellite radio signals completely.

This is where **Dead Reckoning (DR)** comes in.  
*Dead Reckoning* is an old sailor's term: If you know where you started, and you know how fast you are moving and in which direction, you can figure out where you are right now without looking at the sky!

Our project, **Dracarys IDR (SeamlessNav)**, lets a regular smartphone track a car (or someone walking) through complete GPS blackouts using the phone's internal sensors and an on-device AI.

---

## 2. What are GNSS and IMU Sensors? (And How Do They Work?)

Every smartphone in your pocket has two main positioning systems:

### 1. GNSS (Global Navigation Satellite System)
*Often casually called "GPS", but GNSS also includes India's NavIC, Europe's Galileo, and Russia's GLONASS.*
- **How it works:** Tiny antennas in your phone receive microwave time-stamped signals sent by satellites orbiting ~20,000 km above Earth.
- **Accuracy:** Very accurate outdoors (usually within 3 to 5 meters).
- **The Catch:** It updates slowly (only once per second, or 1 Hz), drains battery, and dies completely the moment you lose line-of-sight to the sky.

### 2. IMU (Inertial Measurement Unit)
Inside your phone is a microscopic silicon chip called an **IMU**. It contains:
- **Accelerometer:** Measures forces and acceleration ($a$) in 3D: Left-Right ($X$), Forward-Backward ($Y$), and Up-Down ($Z$). It measures how hard the phone is being pushed or shaken.
- **Gyroscope:** Measures angular speed (rotation rate, $\omega$) in degrees or radians per second. When you turn a corner, the gyroscope feels the spin.
- **Magnetometer (Compass):** Detects Earth’s magnetic field to know which way is North.

**The Superpower of the IMU:** It does not need satellites or internet! It works underground, underwater, and in outer space. It also updates super fast—**100 times every second (100 Hz)**.

---

## 3. The Core Nightmare: Why Can't We Just "Add Up" Accelerometer Data?

In 9th and 10th-grade physics, you learned the kinematic equations:
$$\text{Velocity} = v = u + at$$
$$\text{Position} = s = ut + \frac{1}{2}at^2$$

So why can’t programmers just take the phone's acceleration $a$ from the accelerometer, multiply by time $t$ to get speed, and multiply by time again to get position?

### Reason 1: The Quadratic Error Explosion ($t^2$)
Smartphone accelerometers are cheap chips costing \$1 to \$2. They have tiny sensor noise—say, an error of just $0.1\text{ m/s}^2$ (which feels like almost nothing).

Let's do the 10th-grade math over 60 seconds of a tunnel:
$$\text{Error} = \frac{1}{2} \cdot a_{\text{error}} \cdot t^2 = \frac{1}{2} \cdot 0.1 \cdot (60)^2 = 180\text{ meters!}$$
In just one minute, a tiny $0.1\text{ m/s}^2$ mistake puts your car **180 meters off course**—inside someone's living room!

### Reason 2: Earth's Monster Gravity ($9.8\text{ m/s}^2$)
Gravity is constantly pulling down at $9.8\text{ m/s}^2$.  
If your phone is tilted in a car mount by just **5 degrees**, part of that gravity bleeds into the forward direction:
$$9.8 \cdot \sin(5^\circ) \approx 0.85\text{ m/s}^2$$
If the phone thinks that $0.85\text{ m/s}^2$ is the car accelerating forward, within 30 seconds it will calculate that you are flying down the road at over **$90\text{ km/h}$** while the car is actually stopped!

---

## 4. Calibration: How Does the Phone Know Which Way the Car is Pointing?

When you get in a car, you might clip your phone into an air-vent mount, put it in a cup holder, or tilt it at an angle to see the screen.  
The phone has its own coordinate system ($X_{\text{phone}}, Y_{\text{phone}}, Z_{\text{phone}}$), while the car has its own ($X_{\text{car}}, Y_{\text{car}}, Z_{\text{car}}$).

How does our app figure out how the phone is tilted without asking the driver?

### Step 1: Finding "Up" Using Gravity Leveling
Even when a car is driving, the strongest constant force acting on the phone is Earth's gravity pointing straight down toward the center of the Earth.  
Android has a special sensor called `Sensor.TYPE_GRAVITY`. We use 3D vector geometry (called Rodrigues' rotation formula) to rotate the phone's 3D axes so that the gravity vector points straight down.  
*Result:* We have eliminated all pitch (tilt up/down) and roll (tilt side-to-side)!

### Step 2: Finding Turning Rate (Yaw Rate)
A car turns left and right around a vertical axis. By taking the dot product between the 3D gyroscope vector and the gravity vector:
$$\text{Turning Rate} = \text{Gyroscope Vector} \cdot \text{Gravity Vector}$$
We get the exact rate at which the car is turning, **no matter how the phone is tilted**!

### Step 3: Finding Forward (Mount Azimuth Alignment)
Now the phone is leveled, but is it pointing straight ahead, or turned 20 degrees towards the driver?
When GPS is working and the car speeds up, the GPS reports the car's forward acceleration:
$$a_{\text{car}} = \frac{\Delta v_{\text{GPS}}}{\Delta t}$$
We look at the horizontal accelerometer readings and compare them with the GPS speed increase. Using simple trigonometry ($\tan^{-1}(y/x)$), the phone calculates:  
*"Aha! When the car accelerates forward, my sensor sees a push at an angle of 18 degrees. That means I am mounted 18 degrees to the right!"*  
Within 2 seconds of driving forward, the phone locks in this angle automatically.

### Step 4: Calibrating Gyroscope Drift at Red Lights
All gyroscopes drift slightly over time due to heat. Whenever the car stops at a red light, we know the real rotation rate must be exactly $0$. We measure whatever tiny signal the gyro produces and subtract it as bias.

---

## 5. The AI & Machine Learning: What Does It Do and How Was It Trained?

### Why Do We Need AI Here?
Remember how we said double-integrating accelerometers causes huge errors?  
Instead of trying to calculate speed by integrating acceleration ($v = \int a \, dt$), **what if an AI could look at the vibration patterns and motion signatures of the car and directly tell us how fast the car is moving?**

When a car moves at 40 km/h vs. 80 km/h vs. sitting still, the road vibrations, engine rumble, slight steering adjustments, and suspension movements produce unique temporal patterns on the IMU. Humans can't write a math formula for those vibrations, but a neural network can learn them!

### How Was the Model Trained?
1. **The Training Data:** We used real-world driving datasets called **IO-VNBD**. Researchers put smartphones in cars and recorded the phone's sensors while simultaneously plugging a computer into the car's OBD-II engine computer (CAN-bus).
   - This gave us the **True Speed** from the car's wheels.
   - It gave us the **True Turning Rate** from the wheel-speed sensors.
2. **The "Student" (The Neural Network):**
   We created a network called **`DracarysMotionNet`**.
   - **1D Convolutional Layers (1D-CNN):** These act like little pattern scanners. They slide over the last 1.5 seconds of sensor vibrations (accelerometer and gyroscope) to detect bumps, engine rumbles, and acceleration trends.
   - **Gated Recurrent Unit (GRU):** This is a memory layer. It remembers what happened a second ago (e.g., "The car was braking 0.5s ago, so it's likely slowing down now").
3. **The Multi-Task Heads (The Output):**
   The network outputs three things at 10 Hz (10 times every second):
   - **Predicted Speed ($v_f$):** e.g., "The car is traveling forward at $14.2\text{ m/s}$."
   - **Gyro Correction ($\Delta\omega$):** e.g., "Adjust the turning rate by $-0.002\text{ rad/s}$."
   - **Confidence Score ($\sigma$):** e.g., "I am 92% confident in this speed estimate."
4. **The Loss Function (How the AI Learned):**
   During training, whenever the AI guessed wrong, we penalized it using math:
   $$\text{Loss} = |\text{Predicted Speed} - \text{True Wheel Speed}| + 15 \times (\text{Predicted Gyro} - \text{True Gyro})^2$$
   Using gradient descent (AdamW optimizer), the network practiced across tens of thousands of samples until its speed error dropped to just **$1.18\text{ m/s}$** on new roads it had never seen before!

---

## 6. The "Brain": What is a Kalman Filter (ESKF)?

We have:
- Raw physics (accelerometer and gyroscope).
- An AI model predicting speed and confidence.
- GPS fixes (when available).

Who decides whom to trust? That is the job of the **Error-State Kalman Filter (ESKF)**.

Think of the Kalman Filter as a wise judge in a courtroom:
- **When GPS is available and strong:** The judge says: *"GPS has 16 satellites and high accuracy. I trust GPS 95%, and I use it to keep the sensors calibrated."*
- **When GPS gets noisy (near tall buildings):** The judge says: *"GPS signal is getting weaker. I will trust the IMU physics and the AI model more."*
- **When entering a tunnel (GPS dead):** The judge says: *"GPS is gone. I am switching to pure Dead Reckoning. I will propagate motion using the car's body physics and the AI's predicted speed."*

### Physics Rules Built Into the Filter:
1. **Coriolis Coupling:** When a car is turning and moving forward, there is a sideways centrifugal acceleration ($a = \omega \cdot v$). The filter accounts for this so turns don't fool the speedometer.
2. **Non-Holonomic Constraints (NHC):** A car has wheels. It can drive forward and backward, but it **cannot slide sideways like a crab**. The filter enforces that sideways velocity is close to zero ($v_{\text{lateral}} \approx 0$).

---

## 7. What Happens When GPS is ON vs. When GPS is OFF?

| System State | What the App Does | What the User Sees |
| :--- | :--- | :--- |
| **GPS is ON (Normal Navigation)** | • Uses true GPS coordinates.<br>• Calculates phone mount angle.<br>• Keeps dead reckoning anchor aligned to current position.<br>• Calibrates gyro bias. | • Teal **GNSS** badge.<br>• Blue vehicle arrow tracks true satellite location.<br>• Instrument capsule stays compact (collapsed). |
| **GPS Goes OFF (Tunnel / Outage)** | • Immediately transitions without jumping or stuttering.<br>• AI predicts forward speed from IMU vibrations.<br>• Gyroscope tracks vehicle heading.<br>• ESKF calculates $(X, Y)$ position.<br>• Covariance inflates smoothly. | • Violet **Dead Reckoning** badge.<br>• Capsule smoothly expands.<br>• Shows live **Confidence Bar**.<br>• Shows **Drift %** counter.<br>• Chronometer counts outage duration (e.g., `00:35`). |
| **GPS Restores (Exiting Tunnel)** | • Compares where Dead Reckoning ended up vs. where the new GPS fix arrived.<br>• Logs the outage error to validate accuracy.<br>• Smoothly pulls the vehicle marker back to GPS. | • Badge turns back to Teal **GNSS**.<br>• Capsule collapses.<br>• Record saved in "Outages" report button for verification. |

---

## 8. The "Why Is My Phone Moving When I'm Sitting Still?" Problem (Zero-Acceleration Stop)

During our early testing, we noticed an annoying bug:  
*If you were sitting in your room testing the app on a desk, the map marker would slowly start crawling across the room!*

**Why did this happen?**  
The AI model was trained on cars driving on roads. When the phone was still, tiny table vibrations made the AI guess: *"Maybe the car is rolling at 1.5 km/h?"* Because the filter integrated that speed, the dot crept forward!

**How We Fixed It (Strict Physical Force Stop Gate):**  
We added a fundamental law of physics into [`LiveNavigationRepository.kt`](app/app/src/main/java/com/dracarys/idr/sensors/LiveNavigationRepository.kt):
1. We check the magnitude of total linear acceleration: $\|\mathbf{a}_{\text{lin}}\| = \sqrt{a_x^2 + a_y^2 + a_z^2}$.
2. We check the gyroscope rotation rate: $\|\boldsymbol{\omega}\|$.
3. **The Rule:** If acceleration is less than $0.28\text{ m/s}^2$ and rotation is less than $0.08\text{ rad/s}$, the phone is **physically stationary**.
4. When stationary, we forcefully clamp:
   $$\text{Speed} = 0.0, \quad \text{Displacement} = 0.0$$
   Now, when the phone is on a table or held still, it is **100% frozen** with zero jitter, zero drift, and zero phantom motion.

---

## 9. Vehicle Dead Reckoning: How the Car Tracks Itself Without GPS

This system is a **Vehicle Dead Reckoning (VDR)** system, designed for the SIH26168 problem statement — tracking a car through GPS blackout zones like tunnels, underground parking, and dense urban canyons.

```
           +-----------------------+
           |   VEHICLE DEAD        |
           |   RECKONING (VDR)     |
           +-----------+-----------+
                       |
         +-------------+-------------+
         |                           |
         v                           v
   [Forward Accel]           [Heading Rate]
   a_f from leveled          omega_z from gyro
   accelerometer             (mount-aligned)
         |                           |
         +-------------+-------------+
                       |
                       v
            ESKF Integration
            + AI MotionNet speed prior
            + Non-Holonomic Constraint (v_l ~ 0)
```

**How VDR works in practice:**
1. The phone's accelerometer measures forward and lateral forces on the car.
2. The gyroscope tracks how fast the car is turning.
3. The AI model (DracarysMotionNet) predicts forward speed from the sensor patterns.
4. The Kalman Filter (ESKF) integrates all of these to estimate position, heading, and velocity.
5. When the car stops (no acceleration, no rotation), the system locks the position and learns the gyroscope's zero-rate bias.

---

## 10. Map Matching: Putting the Car on the Road Gently

Even the best dead reckoning system will drift by a few meters over a 1-kilometer tunnel. But cars drive on roads—they don't drive through riverbeds or office buildings.

We built an offline **Soft Map Matcher** using a fast data structure called a **2D KD-Tree**:
1. It looks around the car in a **35-meter radius** to find nearby road segments.
2. It scores each road segment:
   - Is it close to the car? (Distance probability)
   - Is the road pointing in the same direction the car is heading? (Heading alignment)
3. **Soft Pull vs. Hard Snap:**
   - Inferior navigation systems "hard snap" your car directly onto the center of the nearest line. If you pull into a driveway or parking lot that isn't on the map, the app goes crazy!
   - Our system uses a **Soft Nudge ($\alpha = 0.45$)**: It gently pulls the estimate toward the road like an elastic band, but allows you to drive off-road if you really are off-road.

---

## 11. How We Built the Android App Without Making It Huge and Slow

Most AI apps on Android download giant Google TensorFlow Lite or ONNX C++ library files ($15\text{ to }25\text{ Megabytes}$), which slows down the phone and makes the download huge.

We did something unique:
- We exported the trained neural network weights into a super-compact binary file: **`dracarys_motion_net.bin` (only 95 Kilobytes!)**.
- We wrote the entire neural network math (1D convolutions, matrix multiplication, batch normalization, and GRU equations) **by hand in pure Kotlin** ([`MotionNetInference.kt`](app/app/src/main/java/com/dracarys/idr/ml/MotionNetInference.kt)).
- **Result:**
  - The app runs in **$0.25\text{ milliseconds}$** per step.
  - Total APK download size is only **18.7 MB** (which already includes full offline street map tiles for testing!).
  - Uses very little battery and doesn't heat up the phone.

---

## 12. Summary: Everything from A to Z in One Simple Picture

Here is how the entire system works together every $100\text{ milliseconds}$ (10 times a second):

```
                        [SMARTPHONE HARDWARE]
             Accelerometer, Gyroscope, Compass, Gravity, GPS
                                  │
                                  ▼
                     [STEP 1: CALIBRATION ENGINE]
           - Gravity vector levels pitch & roll to Earth Up
           - Course correlation aligns phone angle with car forward axis
           - Stationary intervals zero-out gyroscope drift
                                  │
                                  ▼
           [STEP 2: MOTION CLASSIFICATION & STOP DETECTION]
         Is phone resting? (Linear accel < 0.28, Gyro < 0.08)
                 ├── YES ──► FREEZE! (Velocity = 0, Position = 0)
                 └── NO  ──► Continue to Step 3
                                  │
                                  ▼
                     [STEP 3: VEHICLE MOTION ESTIMATION]
          1D-CNN + GRU AI Model predicts speed from vibration patterns
          Sustained forward acceleration accumulator tracks driving intent
                                  │
                                  ▼
                   [STEP 4: ERROR-STATE KALMAN FILTER]
           - Fuses body acceleration + Coriolis terms + AI speed prior
           - Enforces zero sideways wheel slippage (v_lateral = 0)
           - Smoothly blends in GPS when available (1 Hz)
           - Seamlessly carries over position when GPS dies (10 Hz)
                                  │
                                  ▼
                     [STEP 5: SOFT MAP MATCHING]
           - KD-Tree queries nearby roads within 35m corridor
           - Gently nudges car toward road centerline
                                  │
                                  ▼
                   [STEP 6: USER INTERFACE & HUD]
           - Renders car on offline vector map
           - Capsule shows GNSS / Dead Reckoning state & drift %
           - User can tap badge to toggle AUTO / PDR / VDR
```

### In One Sentence:
**Dracarys IDR turns a common smartphone into a satellite-independent navigation computer by combining the physics of gravity and motion with a lightweight neural network that translates phone vibrations into accurate forward speed.**
