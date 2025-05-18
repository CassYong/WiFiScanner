package com.example.wifiscanner

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.wifiscanner.ui.theme.WiFiScannerTheme

class MainActivity : ComponentActivity() {
    private lateinit var wifiManager: WifiManager
    private var allScanResults by mutableStateOf<List<AccessPoint>>(emptyList()) // All scan results (Task 1)
    private var filteredScanResults by mutableStateOf<List<AccessPoint>>(emptyList()) // Filtered results (Task 2)
    private var isScanning by mutableStateOf(false)
    private var showAllAPs by mutableStateOf(false) // Toggle for Task 1 / Task 2 display

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            startWifiScan()
        } else {
            Toast.makeText(this, "Permissions denied. Cannot scan WiFi networks.", Toast.LENGTH_LONG).show()
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.System.canWrite(this)) {
            Toast.makeText(this, "Write settings permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Write settings permission denied", Toast.LENGTH_LONG).show()
        }
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                scanSuccess()
            } else {
                scanFailure()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        setContent {
            WiFiScannerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WiFiScannerApp(
                        allScanResults = allScanResults,
                        filteredScanResults = filteredScanResults,
                        isScanning = isScanning,
                        showAllAPs = showAllAPs,
                        onScanClick = { checkPermissionsAndScan() },
                        onToggleTask = { showAllAPs = !showAllAPs }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            startWifiScan()
        } else {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    private fun startWifiScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "Location permission required for scanning", Toast.LENGTH_SHORT).show()
                return
            }
        }

        isScanning = true
        try {
            val success = wifiManager.startScan()
            if (!success) {
                scanFailure()
            }
        } catch (e: SecurityException) {
            isScanning = false
            Toast.makeText(this, "Permission denied: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scanSuccess() {
        isScanning = false
        try {
            val results = wifiManager.scanResults

            val accessPoints = results.map { it.toAccessPoint() }
                .sortedByDescending { it.signalStrength }

            // Store all scan results for Task 1
            allScanResults = accessPoints

            // Store filtered results for Task 2
            filteredScanResults = getDistinctStrongestAccessPoints(accessPoints)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission denied: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scanFailure() {
        isScanning = false
        Toast.makeText(this, "Scan failed. Please try again.", Toast.LENGTH_SHORT).show()
    }

    private fun ScanResult.toAccessPoint(): AccessPoint {
        val isEncrypted = when {
            capabilities.contains("WEP") ||
                    capabilities.contains("PSK") ||
                    capabilities.contains("EAP") -> true
            else -> false
        }

        return AccessPoint(
            ssid = SSID.ifEmpty { "<Hidden SSID>" },
            bssid = BSSID,
            signalStrength = level,
            isEncrypted = isEncrypted,
            capabilities = capabilities
        )
    }

    private fun getDistinctStrongestAccessPoints(accessPoints: List<AccessPoint>): List<AccessPoint> {
        return accessPoints
            .groupBy { it.ssid }
            .mapValues { entry ->
                entry.value.maxByOrNull { it.signalStrength }!!
            }
            .values
            .sortedByDescending { it.signalStrength }
            .take(4)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WiFiScannerApp(
    allScanResults: List<AccessPoint>,
    filteredScanResults: List<AccessPoint>,
    isScanning: Boolean,
    showAllAPs: Boolean,
    onScanClick: () -> Unit,
    onToggleTask: () -> Unit
) {
    val context = LocalContext.current

    var selectedAP by remember { mutableStateOf<AccessPoint?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showConnectionDialog by remember { mutableStateOf(false) }
    var connectionInfo by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = { Text(text = stringResource(id = R.string.app_name)) }
        )

        // Toggle button to switch between Task 1 and Task 2
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(onClick = onToggleTask) {
                Text(if (showAllAPs) "Show Strongest 4 APs (Task 2)" else "Show All APs (Task 1)")
            }
        }

        // Display current mode
        Text(
            text = if (showAllAPs) "Showing All APs (Task 1)" else "Showing Strongest 4 APs (Task 2)",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyMedium
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                val displayResults = if (showAllAPs) allScanResults else filteredScanResults

                items(displayResults) { accessPoint ->
                    AccessPointItem(accessPoint = accessPoint, onClick = {
                        selectedAP = accessPoint
                        showDialog = true
                    })
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        Button(
            onClick = onScanClick,
            enabled = !isScanning,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(text = stringResource(id = R.string.scan))
        }

        if (showDialog && selectedAP != null) {
            PasswordDialog(
                accessPoint = selectedAP!!,
                onDismiss = { showDialog = false },
                onConnect = { ssid, username, password ->
                    connectToWifi(context, ssid, username, password) { info ->
                        connectionInfo = info
                        showConnectionDialog = true
                    }
                }
            )
        }

        if (showConnectionDialog) {
            AlertDialog(
                onDismissRequest = { showConnectionDialog = false },
                confirmButton = {
                    TextButton(onClick = { showConnectionDialog = false }) {
                        Text("OK")
                    }
                },
                title = { Text("Connection Info") },
                text = { Text(connectionInfo) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessPointItem(accessPoint: AccessPoint, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = accessPoint.ssid,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "${accessPoint.signalStrength} dBm",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = accessPoint.bssid,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (accessPoint.isEncrypted)
                    stringResource(id = R.string.encrypted)
                else
                    stringResource(id = R.string.open),
                fontSize = 14.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordDialog(
    accessPoint: AccessPoint,
    onDismiss: () -> Unit,
    onConnect: (String, String, String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    val isUthm = accessPoint.ssid.equals("UTHM", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to ${accessPoint.ssid}") },
        text = {
            Column {
                Text("Enter credentials:")
                if (isUthm) {
                    Text("For UTHM, use matric number (e.g., AI220258)", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(if (isUthm) "Matric Number (e.g., AI220258)" else "Username") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (username.isNotEmpty() && password.isNotEmpty()) {
                    if (isUthm && !username.matches(Regex("[A-Z]{2}\\d{6}"))) {
                        Toast.makeText(context, "Please enter a valid matric number (e.g., AI220258)", Toast.LENGTH_LONG).show()
                    } else {
                        onConnect(accessPoint.ssid, username, password)
                        onDismiss()
                    }
                } else {
                    Toast.makeText(context, "Please enter matric number and password", Toast.LENGTH_SHORT).show()
                }
            }) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

fun connectToWifi(context: Context, ssid: String, username: String, userPassword: String, onConnected: (String) -> Unit) {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val TAG = "WiFiScanner"
    val isUthm = ssid.equals("UTHM", ignoreCase = true)

    // Check WRITE_SETTINGS permission
    if (!Settings.System.canWrite(context)) {
        Log.e(TAG, "WRITE_SETTINGS permission not granted")
        onConnected("Failed to connect to $ssid: Please grant Write Settings permission in Settings > Apps > WiFiScanner > Modify system settings")
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        context.startActivity(intent)
        return
    }

    // Check CHANGE_NETWORK_STATE permission
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CHANGE_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
        Log.e(TAG, "CHANGE_NETWORK_STATE permission not granted")
        onConnected("Failed to connect to $ssid: CHANGE_NETWORK_STATE permission not granted")
        return
    }

    fun getIpAddress(): String {
        val wifiInfo = wifiManager.connectionInfo
        Log.d(TAG, "Current SSID: ${wifiInfo.ssid}, Target SSID: $ssid")
        if (wifiInfo.ssid == "\"$ssid\"" || wifiInfo.ssid == ssid) {
            val ip = wifiManager.dhcpInfo.ipAddress
            if (ip != 0) {
                val ipAddress = String.format(
                    "%d.%d.%d.%d",
                    (ip and 0xff), (ip shr 8 and 0xff), (ip shr 16 and 0xff), (ip shr 24 and 0xff)
                )
                Log.d(TAG, "IP assigned: $ipAddress")
                return ipAddress
            } else {
                Log.e(TAG, "No IP assigned")
                return "Not assigned"
            }
        }
        Log.e(TAG, "Not connected to target SSID")
        return "Not assigned"
    }

    // Special handling for UTHM network
    if (isUthm) {
        connectToUthmNetwork(context, ssid, username, userPassword, onConnected)
        return
    }

    // Standard network connection for non-UTHM networks
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            val enterpriseConfig = WifiEnterpriseConfig().apply {
                setIdentity(username)
                setPassword(userPassword)
                setEapMethod(WifiEnterpriseConfig.Eap.PEAP)
                setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAPV2)
            }

            val networkSpecifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2EnterpriseConfig(enterpriseConfig)
                .build()

            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(networkSpecifier)
                .build()

            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connectivityManager.bindProcessToNetwork(network)
                    val ipAddress = getIpAddress()
                    onConnected("Successfully connected to \"$ssid\"\nIP Address: $ipAddress")
                }

                override fun onUnavailable() {
                    onConnected("Failed to connect to $ssid: Network unavailable")
                }
            }

            connectivityManager.requestNetwork(networkRequest, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Connection error for $ssid: ${e.message}")
            onConnected("Failed to connect to $ssid: ${e.message}")
        }
    } else {
        try {
            val wifiConfig = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP)
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X)
                enterpriseConfig.apply {
                    setIdentity(username)
                    setPassword(userPassword)
                    setEapMethod(WifiEnterpriseConfig.Eap.PEAP)
                    setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAPV2)
                }
            }

            val netId = wifiManager.addNetwork(wifiConfig)
            if (netId != -1) {
                wifiManager.disconnect()
                wifiManager.enableNetwork(netId, true)
                wifiManager.reconnect()
                Handler(Looper.getMainLooper()).postDelayed({
                    val ipAddress = getIpAddress()
                    if (ipAddress != "Not assigned") {
                        onConnected("Successfully connected to \"$ssid\"\nIP Address: $ipAddress")
                    } else {
                        onConnected("Failed to connect to $ssid: No IP assigned")
                    }
                }, 3000)
            } else {
                onConnected("Failed to connect to $ssid: Could not add network")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pre-Android 10 connection error for $ssid: ${e.message}")
            onConnected("Failed to connect to $ssid: ${e.message}")
        }
    }
}

private fun connectToUthmNetwork(
    context: Context,
    ssid: String,
    username: String,
    password: String,
    onConnected: (String) -> Unit
) {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val TAG = "UTHMConnection"

    try {
        // Try with TTLS/PAP first (common for Eduroam-like networks)
        val enterpriseConfig = WifiEnterpriseConfig().apply {
            setIdentity(username)
            setPassword(password)
            setEapMethod(WifiEnterpriseConfig.Eap.TTLS)
            setPhase2Method(WifiEnterpriseConfig.Phase2.PAP)
            setAnonymousIdentity("anonymous")
            // setDomainSuffixMatch("uthm.edu.my") // Uncomment if required by UTHM IT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2EnterpriseConfig(enterpriseConfig)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connectivityManager.bindProcessToNetwork(network)
                    val ipAddress = getIpAddress(wifiManager, ssid)
                    onConnected("Successfully connected to UTHM\nIP Address: $ipAddress")
                }

                override fun onUnavailable() {
                    Log.e(TAG, "TTLS/PAP connection unavailable")
                    tryPeapConnection(context, ssid, username, password, onConnected)
                }
            }

            connectivityManager.requestNetwork(request, callback)
        } else {
            // Pre-Android 10 implementation
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP)
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X)
                enterpriseConfig.apply {
                    setIdentity(username)
                    setPassword(password)
                    setEapMethod(WifiEnterpriseConfig.Eap.TTLS)
                    setPhase2Method(WifiEnterpriseConfig.Phase2.PAP)
                    setAnonymousIdentity("anonymous")
                    // setDomainSuffixMatch("uthm.edu.my") // Uncomment if required
                }
            }

            val netId = wifiManager.addNetwork(config)
            if (netId != -1) {
                wifiManager.disconnect()
                wifiManager.enableNetwork(netId, true)
                wifiManager.reconnect()
                Handler(Looper.getMainLooper()).postDelayed({
                    val ipAddress = getIpAddress(wifiManager, ssid)
                    if (ipAddress != "Not assigned") {
                        onConnected("Successfully connected to UTHM\nIP Address: $ipAddress")
                    } else {
                        Log.e(TAG, "TTLS/PAP: No IP assigned")
                        tryPeapConnection(context, ssid, username, password, onConnected)
                    }
                }, 5000)
            } else {
                Log.e(TAG, "Failed to add TTLS/PAP network")
                tryPeapConnection(context, ssid, username, password, onConnected)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "UTHM TTLS connection failed: ${e.message}")
        tryPeapConnection(context, ssid, username, password, onConnected)
    }
}

private fun tryPeapConnection(
    context: Context,
    ssid: String,
    username: String,
    password: String,
    onConnected: (String) -> Unit
) {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val TAG = "UTHMPeapConnection"

    try {
        val enterpriseConfig = WifiEnterpriseConfig().apply {
            setIdentity(username)
            setPassword(password)
            setEapMethod(WifiEnterpriseConfig.Eap.PEAP)
            setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAPV2)
            setAnonymousIdentity("anonymous")
            // setDomainSuffixMatch("uthm.edu.my") // Uncomment if required
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2EnterpriseConfig(enterpriseConfig)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connectivityManager.bindProcessToNetwork(network)
                    val ipAddress = getIpAddress(wifiManager, ssid)
                    onConnected("Successfully connected to UTHM\nIP Address: $ipAddress")
                }

                override fun onUnavailable() {
                    Log.e(TAG, "PEAP/MSCHAPv2 connection unavailable")
                    onConnected("""
                        UTHM connection failed. Please verify:
                        1. Your matric number (e.g., AI220258)
                        2. Correct password
                        3. Contact UTHM IT if issue persists
                        Error: PEAP/MSCHAPv2 authentication failed
                    """.trimIndent())
                }
            }

            connectivityManager.requestNetwork(request, callback)
        } else {
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP)
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X)
                enterpriseConfig.apply {
                    setIdentity(username)
                    setPassword(password)
                    setEapMethod(WifiEnterpriseConfig.Eap.PEAP)
                    setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAPV2)
                    setAnonymousIdentity("anonymous")
                    // setDomainSuffixMatch("uthm.edu.my") // Uncomment if required
                }
            }

            val netId = wifiManager.addNetwork(config)
            if (netId != -1) {
                wifiManager.disconnect()
                wifiManager.enableNetwork(netId, true)
                wifiManager.reconnect()
                Handler(Looper.getMainLooper()).postDelayed({
                    val ipAddress = getIpAddress(wifiManager, ssid)
                    if (ipAddress != "Not assigned") {
                        onConnected("Successfully connected to UTHM\nIP Address: $ipAddress")
                    } else {
                        Log.e(TAG, "PEAP/MSCHAPv2: No IP assigned")
                        onConnected("""
                            UTHM connection failed. Please verify:
                            1. Your matric number (e.g., AI220258)
                            2. Correct password
                            3. Contact UTHM IT if issue persists
                            Error: No IP assigned after connection
                        """.trimIndent())
                    }
                }, 5000)
            } else {
                Log.e(TAG, "Failed to add PEAP/MSCHAPv2 network")
                onConnected("Failed to add UTHM network configuration")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "UTHM PEAP connection error: ${e.message}")
        onConnected("UTHM connection error: ${e.message}")
    }
}

private fun getIpAddress(wifiManager: WifiManager, targetSsid: String): String {
    val wifiInfo = wifiManager.connectionInfo
    Log.d("UTHMConnection", "Current SSID: ${wifiInfo.ssid}, Target SSID: $targetSsid")
    if (wifiInfo.ssid == "\"$targetSsid\"" || wifiInfo.ssid == targetSsid) {
        val ip = wifiManager.dhcpInfo.ipAddress
        if (ip != 0) {
            val ipAddress = String.format(
                "%d.%d.%d.%d",
                (ip and 0xff), (ip shr 8 and 0xff), (ip shr 16 and 0xff), (ip shr 24 and 0xff)
            )
            Log.d("UTHMConnection", "IP assigned: $ipAddress")
            return ipAddress
        } else {
            Log.e("UTHMConnection", "No IP assigned")
        }
    } else {
        Log.e("UTHMConnection", "Not connected to target SSID")
    }
    return "Not assigned"
}

data class AccessPoint(
    val ssid: String,
    val bssid: String,
    val signalStrength: Int,
    val isEncrypted: Boolean,
    val capabilities: String
)