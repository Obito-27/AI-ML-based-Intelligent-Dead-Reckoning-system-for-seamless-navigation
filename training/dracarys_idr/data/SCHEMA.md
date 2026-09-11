# IO-VNBD Dataset Schema & Specification
 
 This document details the inferred and verified schema of the **Inertial and Odometry Benchmark Dataset for Ground Vehicle Positioning (IO-VNBD)** (Onyekpe et al., 2021, *Data in Brief*).
 
 The dataset provides paired recordings from:
 1. **Smartphone (S-*.csv)**: An Android smartphone recording IMU, Magnetometer, Gravity, and GPS
 2. **Vehicle CAN / Ground Truth (V-*.csv)**: Vehicle CAN bus sensors, reference GPS, wheel speeds, and steering metrics.
 
 ---
 
 ## 1. Smartphone Schema (S-*.csv)
 
 - `GPS LATITUDE (degrees)`: WSS84 Latitude (~ 1 Hz updates in 10 Hz stream)
 - `GPS LONGITUDE (degrees)`: WSS84 Longitude
 - `GPS ALTITUDE (m)`: Altitude above mean sea level
 - `GPS SPEED (Kmh)`: GPS Doppler speed
 - `GPS ACCURACY (m)`: 68% horizontal accuracy bound
 - `GPS ORIENTATION (\276\60)` : Course over ground heading
 - `GPS SATELLITES IN RANGE`: \"used / in_view\"
 - `TIME SINCE START (ms)`: Relative timebase at 10 Hz
 - `DATE (YYYY-MO-DD HH-MI-SS_SSS)`: UTC stamp
 - `ACCELEROMETER X/Y/Z (m/s?)`: Accelerometer specific force
 - `GRAVITY X/Y/Z (m/s?)`: Sampled gravity vector
 - `GYROSCOPE Yaw/Pitch/Roll (rad/s)`: Angular rates (rad/s)
 - `MAGNETIC FIELD X/Y/Z (\524\276T)`: Magnetometer
 - `ORIENTATION (Yaw/Pitch/Roll) (\276\60)`: Android fused orientation
 
 ---
 
 ## 2. Vehicle Reference Schema (V-*.csv)
 
 - `No of GPS Satellites Available`: Satellite count
 - `Time Since STart of Day (seconds)`: 10 Hz reference clock
 - `Latitude (degrees)`, `Longitude (degrees)`: Ground truth WGS84
 - `Velocity (km/hr)`: Ground truth forward velocity
 - `Heading (degrees)`: True vehicle heading
 - `Yaw Rate (deg/sec)`: Reference yawrate
 - `Wheel Speed Front/Rear Left/Right (rad/sec)`: Wheel speeds
 - `Indicated Longitudinal/Lateral Acceleration (g)`: Vehicle accelerometers