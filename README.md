# Wi-Fi Scanner Android App

An Android application built using Kotlin that scans for nearby Wi-Fi access points (APs), displays essential network information, and allows users to connect to a selected AP.

## 📱 Features

### 🔍 Wi-Fi Scanning
- Scans for all nearby Wi-Fi networks using `WifiManager`.
- Displays:
  - **SSID (Network Name)**
  - **BSSID (MAC Address)**
  - **Signal Strength** in dBm
  - **Encryption Type** (Open or Secured)

### 📶 Sort by Signal Strength
- Filters and displays the **top 4 access points** with the strongest signal.
- Groups networks by SSID to avoid duplicates.

### 🔐 Connect to Wi-Fi
- Allows users to select a Wi-Fi network.
- Prompts for username/password via a secure `AlertDialog`.
- Connects to the network and shows:
  - **SSID**
  - **IP Address**

## 🚀 Getting Started

### Prerequisites
- Android Studio
- A real Android device with Wi-Fi and Location enabled

### Permissions Required
Add these permissions in `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
