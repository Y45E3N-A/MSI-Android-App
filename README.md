# 🌿 MSI Android App – Multispectral Imaging System Controller

This Android application is designed to wirelessly interface with a custom-built Raspberry Pi-based multispectral imaging system (MSI v2). The system is developed to assess the fluorescence response of plant leaves to multispectral LED illumination, aiding early detection of plant viruses for sustainable agriculture.

## 📱 Features

- Acts as a Wi-Fi hotspot for the Raspberry Pi Zero 2W.
- Receives and stores high-resolution image sets (16 images per session).
- Captures metadata:
  - GPS coordinates (via Android location services)
  - Temperature and humidity (via onboard Android sensors)
- Displays image previews and organizes them in a session-based gallery.
- Controls the Raspberry Pi remotely via real-time Socket.IO communication:
  - Live camera preview
  - Start/stop imaging
  - Toggle LED cycling
  - Initiate safe system shutdown
- Background file upload server for receiving image ZIP files from the Pi.

## 🧠 System Architecture

- **Hardware:**
  - Raspberry Pi Zero 2W
  - Multispectral LED array (16 bands, 395–880nm)
  - Temperature & humidity sensor (HS3001)
  - GNSS module (Quectel L86-M33)
  - Battery system with smart charging and safety detection (BQ25172, RT9426)
  
- **Communication:**
  - Socket.IO for real-time events (preview, button presses, status)
  - HTTP POST file upload from Pi to Android
  - Android acts as hotspot for local connection

- **Android Tech Stack:**
  - Kotlin
  - OkHttp / Retrofit
  - kotlinx.serialization
  - Room for local database
  - Foreground service for image uploads

## 🧩 Folder Structure (Key Modules)

```
app/
├── ui/
│   ├── control/       # Live control and preview interface
│   └── gallery/       # Session-based image viewer
├── data/
│   ├── AppDatabase.kt # Room database setup
│   ├── Session.kt     # Session metadata entity
├── network/
│   ├── PiApiService.kt     # Retrofit/OkHttp API interface
│   └── PiSocketManager.kt  # Socket.IO communication
├── service/
│   └── UploadServer.kt     # Embedded HTTP server for receiving Pi images
```

## 📷 Image Workflow

1. Raspberry Pi captures 16-channel multispectral images using controlled LED cycling.
2. Images are zipped and sent via HTTP to Android app's foreground upload server.
3. Android app:
   - Extracts images to local storage
   - Stores metadata in Room DB
   - Updates session gallery with preview and GPS/ENV info

## ⚙️ Getting Started

### Prerequisites

- Android Studio (latest stable version)
- Android device (API 26+ recommended)
- Kotlin 1.9+
- Raspberry Pi Zero 2W with configured Flask server & hardware drivers

### Building the App

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/msi-android-app.git
   cd msi-android-app
   ```

2. Open in Android Studio.

3. Add the following permissions to your `AndroidManifest.xml`:
   ```xml
   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
   <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
   <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
   <uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
   ```

4. Connect a real Android device and run the app.

## 🔌 Raspberry Pi Server

- Flask server with SocketIO enabled (`server.py`)
- Compatible with hardware via `MSI2_HW` abstraction
- Key features:
  - Controls LED PWM, imaging sequence, live preview
  - Exposes endpoints for Android interaction
  - Automatically cleans up old camera processes
  - Uploads ZIP of images after session

## 📡 Hotspot Communication

- Android acts as Wi-Fi AP (hotspot)
- Pi connects to it and uploads images to the embedded HTTP server
- App listens on port `8080` for image uploads

## 🗃️ Data Storage

Each session stores:
- 16 multispectral images
- GPS coordinates
- Humidity + temperature
- Timestamp
- Device session ID

Images and metadata are stored in:
```
/storage/emulated/0/Android/data/com.example.msiandroidapp/files/Sessions/
```

## 🚧 Known Issues

- GPS data may be imprecise indoors.
- Android 10+ restricts background service/network access; ensure appropriate permissions are granted.
- Some LED leakage can still occur due to circuit limitations (see MSI v2 circuit report).

## 🔐 License

MIT License – free to use, modify, and distribute.

## 🙋‍♂️ Acknowledgements

- MSI v2 Circuit & Hardware Design: [Matei Rosca]
- Android App Development: Yaseen Ahmed
- Raspberry Pi integration and firmware: [Collaborators TBD]

For access to hardware schematics and board files, email **matei.rosca@manchester.ac.uk**.