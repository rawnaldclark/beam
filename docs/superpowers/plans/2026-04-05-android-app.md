# Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the ZapTransfer Android app — Kotlin/Compose standalone file transfer hub with E2E encryption, foreground service, and resilient network handling.

**Architecture:** Single Activity + Compose Navigation. MVVM with ViewModels + StateFlow. Room for persistence. Foreground service for background transfers. lazysodium-android for crypto (wire-compatible with Chrome's libsodium.js).

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), stream-webrtc-android, OkHttp, lazysodium-android, ML Kit Barcode, Room, Hilt, Min SDK 26

**Spec Reference:** docs/superpowers/specs/2026-04-04-zaptransfer-design.md (Sections 4, 5, 8)

**Dependency:** Relay server must be deployed first (Plan 1: `2026-04-05-relay-server.md`)

---

## Project Structure

```
android/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── java/com/zaptransfer/android/
│       │       ├── ZapTransferApplication.kt
│       │       ├── MainActivity.kt
│       │       ├── navigation/NavGraph.kt
│       │       ├── ui/
│       │       │   ├── devicehub/ (DeviceHubScreen, ViewModel, DeviceCard, EmptyState)
│       │       │   ├── pairing/ (QrScanner, PinEntry, SasVerification, DeviceNaming, PairingViewModel)
│       │       │   ├── transfer/ (ProgressScreen, CompleteSheet, TransferViewModel)
│       │       │   ├── clipboard/ (HistoryScreen, ViewModel)
│       │       │   ├── settings/ (SettingsScreen, ViewModel)
│       │       │   └── components/ (ConnectionTypeIndicator, DeviceIconPicker, EmojiSasDisplay)
│       │       ├── service/
│       │       │   ├── TransferForegroundService.kt
│       │       │   ├── NetworkMonitor.kt
│       │       │   └── WakeLockManager.kt
│       │       ├── webrtc/
│       │       │   ├── PeerConnectionManager.kt
│       │       │   ├── SignalingClient.kt
│       │       │   └── IceRestartPolicy.kt
│       │       ├── crypto/
│       │       │   ├── KeyManager.kt
│       │       │   ├── SessionCipher.kt
│       │       │   └── HashAccumulator.kt
│       │       ├── data/
│       │       │   ├── db/
│       │       │   │   ├── ZapDatabase.kt
│       │       │   │   ├── entity/ (PairedDevice, TransferHistory, ChunkProgress, ClipboardEntry, OfflineQueue)
│       │       │   │   └── dao/ (PairedDeviceDao, TransferHistoryDao, ChunkProgressDao, ClipboardDao, OfflineQueueDao)
│       │       │   ├── repository/ (DeviceRepo, TransferRepo, ClipboardRepo, OfflineQueueRepo)
│       │       │   └── preferences/UserPreferences.kt
│       │       └── util/ (FileUtil, NotificationChannels, PermissionHelper, MimeTypeUtil)
│       ├── test/ (unit tests)
│       └── androidTest/ (instrumented tests)
├── build.gradle.kts (root)
├── settings.gradle.kts
└── gradle.properties
```

---

## Phase A: Project Setup

### Task 1: Gradle Scaffold

**Files:**
- Create: `android/build.gradle.kts` (root)
- Create: `android/settings.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/app/build.gradle.kts`

- [ ] **Step 1: Create root build.gradle.kts**

```kotlin
plugins {
    id("com.android.application") version "8.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("com.google.dagger.hilt.android") version "2.51" apply false
    id("com.google.devtools.ksp") version "2.0.0-1.0.21" apply false
}
```

- [ ] **Step 2: Create app/build.gradle.kts with ALL dependencies**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.zaptransfer.android"
    compileSdk = 34
    defaultConfig { minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "0.1.0" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51")
    ksp("com.google.dagger:hilt-compiler:2.51")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Crypto
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // WebRTC
    implementation("io.getstream:stream-webrtc-android:1.1.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ML Kit Barcode
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // CameraX
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.mockk:mockk:1.13.9")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
```

- [ ] **Step 3: Create settings.gradle.kts and gradle.properties**

- [ ] **Step 4: Commit** — `git commit -m "feat(android): Gradle scaffold with all dependencies"`

---

### Task 2: Room Database + Entities

**Files:**
- Create: `data/db/entity/PairedDeviceEntity.kt`
- Create: `data/db/entity/TransferHistoryEntity.kt`
- Create: `data/db/entity/ChunkProgressEntity.kt`
- Create: `data/db/entity/ClipboardEntryEntity.kt`
- Create: `data/db/entity/OfflineQueueEntity.kt`
- Create: `data/db/dao/` (all 5 DAOs)
- Create: `data/db/ZapDatabase.kt`

- [ ] **Step 1: Create all entities**

```kotlin
@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val icon: String,        // LAPTOP, DESKTOP, PHONE, TABLET
    val platform: String,    // chrome_extension, android
    val x25519PublicKey: ByteArray,
    val ed25519PublicKey: ByteArray,
    val pairedAt: Long,
    val lastSeenAt: Long? = null
)

@Entity(tableName = "transfer_history")
data class TransferHistoryEntity(
    @PrimaryKey val transferId: String,
    val deviceId: String,
    val direction: String,   // SENT, RECEIVED
    val fileName: String,
    val fileSizeBytes: Long,
    val mimeType: String?,
    val status: String,      // COMPLETED, FAILED, CANCELLED
    val sha256Hash: String?,
    val localUri: String?,
    val startedAt: Long,
    val completedAt: Long? = null
)

@Entity(tableName = "chunk_progress")
data class ChunkProgressEntity(
    @PrimaryKey val transferId: String,
    val totalChunks: Int,
    val lastAckedChunk: Int,
    val tempFilePath: String,
    val sha256State: ByteArray?,
    val updatedAt: Long
)

@Entity(tableName = "clipboard_entries")
data class ClipboardEntryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val deviceId: String,
    val content: String,
    val isUrl: Boolean,
    val receivedAt: Long
)

@Entity(tableName = "offline_queue")
data class OfflineQueueEntity(
    @PrimaryKey(autoGenerate = true) val queueId: Long = 0,
    val targetDeviceId: String,
    val type: String,        // FILE, TEXT
    val contentOrUri: String,
    val fileName: String?,
    val fileSizeBytes: Long?,
    val enqueuedAt: Long,
    val expiresAt: Long,
    val status: String       // PENDING, SENDING, EXPIRED, COMPLETED, FAILED_FILE_MISSING
)
```

- [ ] **Step 2: Create all DAOs** with standard CRUD + specific queries (e.g., `ClipboardDao.deleteOldest()` to cap at 20)

- [ ] **Step 3: Create ZapDatabase**

```kotlin
@Database(entities = [...all 5...], version = 1)
abstract class ZapDatabase : RoomDatabase() {
    abstract fun pairedDeviceDao(): PairedDeviceDao
    abstract fun transferHistoryDao(): TransferHistoryDao
    abstract fun chunkProgressDao(): ChunkProgressDao
    abstract fun clipboardDao(): ClipboardDao
    abstract fun offlineQueueDao(): OfflineQueueDao
}
```

- [ ] **Step 4: Commit** — `git commit -m "feat(android): Room database with all entities and DAOs"`

---

### Task 3: Theme + Application + Activity

**Files:**
- Create: `ui/theme/Theme.kt`, `Color.kt`, `Type.kt`
- Create: `ZapTransferApplication.kt`
- Create: `MainActivity.kt`
- Create: `navigation/NavGraph.kt`
- Create: `AndroidManifest.xml`
- Create: `util/NotificationChannels.kt`

- [ ] **Step 1: Material 3 theme** (indigo primary, dark/light support)

- [ ] **Step 2: Hilt Application**

```kotlin
@HiltAndroidApp
class ZapTransferApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
    }
}
```

- [ ] **Step 3: MainActivity with NavHost**

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZapTransferTheme { ZapNavGraph() } }
    }
}
```

- [ ] **Step 4: NavGraph with all routes** (deviceHub, pairing/*, transfer/*, settings, clipboard)

- [ ] **Step 5: AndroidManifest with all permissions**

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

- [ ] **Step 6: Commit** — `git commit -m "feat(android): theme, application, activity, navigation, manifest"`

---

## Phase B: Crypto Layer

### Task 4: KeyManager

**Files:**
- Create: `crypto/KeyManager.kt`
- Create: `test/.../crypto/KeyManagerTest.kt`

- [ ] **Step 1: Write test** — keygen produces valid lengths, device ID derivation matches expected format, sign/verify round-trip

- [ ] **Step 2: Implement KeyManager**

```kotlin
@Singleton
class KeyManager @Inject constructor(context: Application) {
    private val sodium = LazySodiumAndroid(SodiumAndroid())
    private val encPrefs = EncryptedSharedPreferences.create(...)

    fun generateKeyPairs(): KeyPairs { /* X25519 + Ed25519 */ }
    fun getOrCreateKeys(): KeyPairs { /* read from storage or generate */ }
    fun deriveDeviceId(ed25519Pk: ByteArray): String { /* Base64url(SHA256(pk)[0:16]) */ }
    fun sign(message: ByteArray): ByteArray { /* Ed25519 detached */ }
    fun verify(message: ByteArray, sig: ByteArray, pk: ByteArray): Boolean
    fun deriveSharedSecret(ourSk: ByteArray, peerPk: ByteArray): ByteArray { /* X25519 */ }
}
```

- [ ] **Step 3: Run test** — `./gradlew testDebugUnitTest --tests "*KeyManagerTest"`

- [ ] **Step 4: Commit** — `git commit -m "feat(android): KeyManager with lazysodium"`

---

### Task 5: SessionCipher

**Files:**
- Create: `crypto/SessionCipher.kt`
- Create: `test/.../crypto/SessionCipherTest.kt`

- [ ] **Step 1: Write test** — encrypt/decrypt round-trip, deterministic nonce, padding to correct bucket, triple-DH key derivation

- [ ] **Step 2: Implement SessionCipher**

```kotlin
class SessionCipher(private val sodium: LazySodiumAndroid) {
    fun deriveSessionKey(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray, salt: ByteArray): ByteArray
    fun deriveChunkKey(sessionKey: ByteArray): ByteArray
    fun deriveMetadataKey(sessionKey: ByteArray): ByteArray
    fun deriveChunkNonce(chunkKey: ByteArray, chunkIndex: Long): ByteArray // 24 bytes
    fun padChunk(plaintext: ByteArray): ByteArray // power-of-2 bucket + random padding
    fun unpadChunk(padded: ByteArray): ByteArray
    fun encryptChunk(plaintext: ByteArray, chunkKey: ByteArray, chunkIndex: Long, aad: ByteArray): ByteArray
    fun decryptChunk(ciphertext: ByteArray, chunkKey: ByteArray, chunkIndex: Long, aad: ByteArray): ByteArray
    fun encryptMetadata(json: ByteArray, metadataKey: ByteArray): ByteArray // nonce || ciphertext
    fun decryptMetadata(envelope: ByteArray, metadataKey: ByteArray): ByteArray
}
```

- [ ] **Step 3: Run test — expect PASS**

- [ ] **Step 4: Commit** — `git commit -m "feat(android): SessionCipher with XChaCha20-Poly1305"`

---

### Task 6: HashAccumulator

**Files:**
- Create: `crypto/HashAccumulator.kt`
- Create: `test/.../crypto/HashAccumulatorTest.kt`

- [ ] **Step 1: Write test** — incremental hash matches single-pass, reorder buffer works, state serialization round-trip

- [ ] **Step 2: Implement HashAccumulator**

```kotlin
class HashAccumulator {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var nextExpectedIndex = 0
    private val reorderBuffer = TreeMap<Int, ByteArray>() // max 32 entries

    fun feedChunk(index: Int, data: ByteArray) {
        if (index == nextExpectedIndex) {
            digest.update(data)
            nextExpectedIndex++
            // Drain reorder buffer for consecutive chunks
            while (reorderBuffer.containsKey(nextExpectedIndex)) {
                digest.update(reorderBuffer.remove(nextExpectedIndex)!!)
                nextExpectedIndex++
            }
        } else if (index > nextExpectedIndex) {
            reorderBuffer[index] = data
        }
        // index < nextExpectedIndex = duplicate, ignore
    }

    fun finalize(): ByteArray = digest.digest()
    fun serializeState(): ByteArray { /* clone digest + buffer */ }
    fun restoreState(state: ByteArray) { /* restore */ }
}
```

- [ ] **Step 3: Run test — expect PASS**

- [ ] **Step 4: Commit** — `git commit -m "feat(android): HashAccumulator with reorder buffer"`

---

### Task 7: Cross-Platform Crypto Test Vectors

**Files:**
- Create: `test/.../crypto/CrossPlatformTest.kt`

- [ ] **Step 1: Test known vectors**

Hard-code test vectors that both Android (lazysodium) and Chrome (libsodium.js) must produce:
- Given key + nonce + plaintext → expected ciphertext (XChaCha20-Poly1305)
- Given seed → expected X25519 keypair
- Given shared secret + salt → expected HKDF output
- Given message + key → expected Ed25519 signature

- [ ] **Step 2: Run tests — expect PASS** (proving wire compatibility with Chrome)

- [ ] **Step 3: Commit** — `git commit -m "test(android): cross-platform crypto test vectors"`

---

## Phase C: Networking

### Task 8: SignalingClient

**Files:**
- Create: `webrtc/SignalingClient.kt`
- Create: `test/.../webrtc/SignalingClientTest.kt`

- [ ] **Step 1: Implement SignalingClient**

```kotlin
class SignalingClient(private val keyManager: KeyManager) {
    private var ws: WebSocket? = null
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state
    private val backoff = listOf(0L, 500, 1000, 2000, 4000, 8000, 16000, 30000)
    private var attempt = 0

    fun connect(relayUrl: String) { /* OkHttp WebSocket, handle auth challenge-response */ }
    fun send(message: JsonObject) { /* serialize + send text frame */ }
    fun sendBinary(data: ByteArray) { /* send binary frame */ }
    fun onMessage(handler: (JsonObject) -> Unit) { /* register */ }
    fun disconnect() { /* clean close */ }

    // Auth: receive challenge → sign(challenge || timestamp) → send auth-response → wait auth-ok
    // Reconnect: exponential backoff, re-auth on reconnect
    // Heartbeat: ping every 30s
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Authenticating : ConnectionState()
    data class Connected(val sessionTTL: Long) : ConnectionState()
    data class Error(val reason: String) : ConnectionState()
}
```

- [ ] **Step 2: Commit** — `git commit -m "feat(android): SignalingClient with OkHttp WebSocket"`

---

### Task 9: NetworkMonitor

**Files:**
- Create: `service/NetworkMonitor.kt`

- [ ] **Step 1: Implement NetworkMonitor**

```kotlin
@Singleton
class NetworkMonitor @Inject constructor(context: Application) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Disconnected)
    val state: StateFlow<NetworkState> = _state

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { updateState() }
            override fun onLost(network: Network) { _state.value = NetworkState.Disconnected }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { updateState() }
        })
    }

    private fun updateState() { /* determine WIFI/CELLULAR/ETHERNET from capabilities */ }
}

sealed class NetworkState {
    object Disconnected : NetworkState()
    data class Connected(val transport: Transport, val isMetered: Boolean) : NetworkState()
}
enum class Transport { WIFI, CELLULAR, ETHERNET }
```

- [ ] **Step 2: Commit** — `git commit -m "feat(android): NetworkMonitor with ConnectivityManager"`

---

### Task 10: Presence Tracking

**Files:**
- Create: `data/repository/DeviceRepository.kt`

- [ ] **Step 1: DeviceRepository with presence**

```kotlin
@Singleton
class DeviceRepository @Inject constructor(
    private val dao: PairedDeviceDao,
    private val signalingClient: SignalingClient
) {
    private val _onlineDevices = MutableStateFlow<Set<String>>(emptySet())
    val onlineDevices: StateFlow<Set<String>> = _onlineDevices

    fun observePairedDevices(): Flow<List<PairedDeviceEntity>> = dao.getAll()

    fun handlePresence(deviceId: String, status: String) {
        _onlineDevices.update { current ->
            if (status == "online") current + deviceId else current - deviceId
        }
    }
}
```

- [ ] **Step 2: Commit** — `git commit -m "feat(android): DeviceRepository with presence tracking"`

---

## Phase D: Pairing

### Task 11: QR Scanner Screen

**Files:**
- Create: `ui/pairing/QrScannerScreen.kt`

- [ ] **Step 1: Implement CameraX + ML Kit scanner**

Compose screen with CameraX preview, ML Kit BarcodeScanner (FORMAT_QR_CODE), viewfinder overlay. On decode: parse JSON payload → navigate to SAS verification. Camera permission request with rationale.

- [ ] **Step 2: Commit** — `git commit -m "feat(android): QR scanner with ML Kit + CameraX"`

---

### Task 12: PIN Entry Screen

**Files:**
- Create: `ui/pairing/PinEntryScreen.kt`

- [ ] **Step 1: 8-digit numeric input**

Row of 8 `OutlinedTextField` boxes, numeric keyboard. Auto-submit on 8th digit. Error text on invalid PIN.

- [ ] **Step 2: Commit** — `git commit -m "feat(android): PIN entry screen"`

---

### Task 13: Key Exchange

**Files:**
- Create: `ui/pairing/PairingViewModel.kt`

- [ ] **Step 1: Implement key exchange in PairingViewModel**

On QR decode or PIN submit: connect to relay → receive peer's public keys → compute shared_secret = X25519(ourSk, peerPk) → derive SAS → navigate to verification.

- [ ] **Step 2: Commit** — `git commit -m "feat(android): X25519 key exchange in PairingViewModel"`

---

### Task 14: SAS Verification Screen

**Files:**
- Create: `ui/pairing/SasVerificationScreen.kt`
- Create: `ui/components/EmojiSasDisplay.kt`

- [ ] **Step 1: 4 emoji display with labels**

Row of 4 large emoji with text labels below. "They Match" (filled button) + "No Match" (outlined). Same 256-emoji table as Chrome extension.

- [ ] **Step 2: Commit** — `git commit -m "feat(android): SAS verification with 4 emoji"`

---

### Task 15: Device Naming + Persistence

**Files:**
- Create: `ui/pairing/DeviceNamingScreen.kt`

- [ ] **Step 1: Name field + icon picker**

`OutlinedTextField` pre-filled with peer's self-name. Grid of device icons (laptop, desktop, phone, tablet). "Done" button disabled when empty. On done: save PairedDeviceEntity to Room + keys to EncryptedSharedPreferences.

- [ ] **Step 2: Commit** — `git commit -m "feat(android): device naming and paired device persistence"`

---

## Phase E: Device Hub UI

### Task 16: DeviceHubScreen

**Files:**
- Create: `ui/devicehub/DeviceHubScreen.kt`
- Create: `ui/devicehub/DeviceCard.kt`
- Create: `ui/devicehub/DeviceHubViewModel.kt`

- [ ] **Step 1: Device cards with status**

```kotlin
@Composable
fun DeviceCard(device: PairedDeviceUi, onSendFile: () -> Unit, onSendText: () -> Unit) {
    Card { Row { Icon(device.icon); Column { Text(device.name); StatusBadge(device); }; if (device.isOnline) { SendButtons() } } }
}
```

- [ ] **Step 2: ViewModel**

```kotlin
@HiltViewModel
class DeviceHubViewModel @Inject constructor(
    private val deviceRepo: DeviceRepository,
    private val transferRepo: TransferRepository
) : ViewModel() {
    val uiState: StateFlow<DeviceHubUiState> = combine(
        deviceRepo.observePairedDevices(),
        deviceRepo.onlineDevices,
        transferRepo.observeRecent(20)
    ) { devices, online, transfers -> DeviceHubUiState(devices.map { it.toUi(online) }, transfers) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DeviceHubUiState())
}
```

- [ ] **Step 3: Commit** — `git commit -m "feat(android): DeviceHubScreen with device cards and ViewModel"`

---

### Task 17: Empty State Onboarding

**Files:**
- Create: `ui/devicehub/EmptyStateOnboarding.kt`

- [ ] **Step 1: Full-screen pairing CTA**

"Pair Your First Device" with illustration, "Scan QR Code" primary button, "Enter PIN instead" text button. Shown when `pairedDevices.isEmpty()`.

- [ ] **Step 2: Commit** — `git commit -m "feat(android): empty state onboarding"`

---

### Task 18: Recent Transfers List

**Files:**
- Modify: `ui/devicehub/DeviceHubScreen.kt`
- Create: `ui/devicehub/TransferHistoryItem.kt`

- [ ] **Step 1: Transfer history list**

Direction arrow (sent/received), file name, peer name, relative time, file size. Tap to open file.

- [ ] **Step 2: Commit** — `git commit -m "feat(android): recent transfers list on device hub"`

---

## Phase F: Transfer Engine (WebSocket Relay Only)

### Task 19: Transfer State Machine

**Files:**
- Create: `service/TransferStateMachine.kt`
- Create: `test/.../service/TransferStateMachineTest.kt`

- [ ] **Step 1: Write state machine tests** — all valid transitions, invalid transitions throw, timeout handling

- [ ] **Step 2: Implement state machine** — enum states, transition rules, event-driven

- [ ] **Step 3: Commit** — `git commit -m "feat(android): transfer state machine"`

---

### Task 20: Chunked Encryption + Adaptive Sizing

**Files:**
- Create: `service/ChunkSizer.kt`
- Create: `service/TransferEngine.kt`

- [ ] **Step 1: ChunkSizer** — tiers 8KB-512KB, throughput measurement, adapt up/down

- [ ] **Step 2: TransferEngine send pipeline** — read file → chunk → pad → encrypt → send via WS

- [ ] **Step 3: Commit** — `git commit -m "feat(android): chunked encryption with adaptive sizing"`

---

### Task 21: AIMD Flow Control

**Files:**
- Create: `service/FlowController.kt`

- [ ] **Step 1: AIMD window** — initial=4, min=2, max=64/8, additive increase, multiplicative decrease

- [ ] **Step 2: Commit** — `git commit -m "feat(android): AIMD flow control"`

---

### Task 22: Send + Receive Flow

**Files:**
- Modify: `service/TransferEngine.kt`

- [ ] **Step 1: Complete send flow** — session key exchange → metadata → chunks → verify
- [ ] **Step 2: Complete receive flow** — accept → decrypt → reassemble → hash verify → save
- [ ] **Step 3: Clipboard fast path** — single message, auto-copy, notification

- [ ] **Step 4: Commit** — `git commit -m "feat(android): complete send/receive/clipboard transfer flows"`

---

## Phase G: Foreground Service

### Task 23: TransferForegroundService

**Files:**
- Create: `service/TransferForegroundService.kt`

- [ ] **Step 1: Foreground service with notification**

```kotlin
class TransferForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildProgressNotification(0, "Starting...")
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // Launch transfer coroutine
        return START_STICKY
    }
}
```

- [ ] **Step 2: Commit** — `git commit -m "feat(android): foreground service with progress notification"`

---

### Task 24: WakeLockManager

**Files:**
- Create: `service/WakeLockManager.kt`

- [ ] **Step 1: Wake lock + WiFi lock**

```kotlin
class WakeLockManager(context: Context) {
    private val pm = context.getSystemService(PowerManager::class.java)
    private val wm = context.getSystemService(WifiManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquire() { wakeLock = pm.newWakeLock(PARTIAL_WAKE_LOCK, "ZapTransfer:transfer").apply { acquire() }; /* + wifi lock */ }
    fun release() { wakeLock?.release(); wifiLock?.release() }
}
```

- [ ] **Step 2: Commit** — `git commit -m "feat(android): WakeLockManager"`

---

### Task 25: Chunk Progress Persistence + Recovery

**Files:**
- Modify: `service/TransferEngine.kt`
- Modify: `ZapTransferApplication.kt`

- [ ] **Step 1: Persist chunk progress to Room** every 64 chunks or 10 seconds

- [ ] **Step 2: Process death recovery** — on Application.onCreate, query incomplete transfers, start service, resume

- [ ] **Step 3: Commit** — `git commit -m "feat(android): chunk progress persistence and process death recovery"`

---

## Phase H: Transfer UI

### Task 26: Transfer Progress Screen

**Files:**
- Create: `ui/transfer/TransferProgressScreen.kt`
- Create: `ui/transfer/TransferViewModel.kt`

- [ ] **Step 1: Progress UI** — file name, size, progress bar, speed, ETA, connection type, cancel

- [ ] **Step 2: Commit** — `git commit -m "feat(android): transfer progress screen"`

---

### Task 27: Transfer Complete Sheet

**Files:**
- Create: `ui/transfer/TransferCompleteSheet.kt`

- [ ] **Step 1: Bottom sheet** — Open File / Save to Downloads / Save to Custom / Dismiss

- [ ] **Step 2: Commit** — `git commit -m "feat(android): transfer complete bottom sheet"`

---

### Task 28: Settings Screen

**Files:**
- Create: `ui/settings/SettingsScreen.kt`
- Create: `ui/settings/SettingsViewModel.kt`
- Create: `data/preferences/UserPreferences.kt`

- [ ] **Step 1: Exactly 4 settings** — Save location, auto-accept toggle, device name, paired devices list

- [ ] **Step 2: Commit** — `git commit -m "feat(android): settings screen with 4 settings"`

---

### Task 29: Clipboard History Screen

**Files:**
- Create: `ui/clipboard/ClipboardHistoryScreen.kt`
- Create: `ui/clipboard/ClipboardHistoryViewModel.kt`

- [ ] **Step 1: Last 20 items** — content preview, copy button, "Open in Browser" for URLs

- [ ] **Step 2: Commit** — `git commit -m "feat(android): clipboard history screen"`

---

## Phase I: Resilience

### Task 30: Network Change Handling

**Files:**
- Modify: `service/TransferEngine.kt`
- Create: `webrtc/IceRestartPolicy.kt`

- [ ] **Step 1: On NetworkMonitor state change** — if transfer active + WiFi→cellular: trigger ICE restart, bridge via relay

- [ ] **Step 2: Commit** — `git commit -m "feat(android): network change handling with ICE restart"`

---

### Task 31: Doze Survival

**Files:**
- Create: `util/PermissionHelper.kt`

- [ ] **Step 1: Battery optimization request** — after first pairing, check `isIgnoringBatteryOptimizations`, if not → show dialog → fire `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

- [ ] **Step 2: Commit** — `git commit -m "feat(android): battery optimization exemption request"`

---

### Task 32: Partial File Cleanup

**Files:**
- Create: `util/FileUtil.kt`

- [ ] **Step 1: On app start** — query chunk_progress for updatedAt < 24h ago → delete temp files → delete rows → update transfer_history status to FAILED

- [ ] **Step 2: Commit** — `git commit -m "feat(android): partial file cleanup on app start"`

---

## Phase J: WebRTC P2P (Optimization)

### Task 33: PeerConnectionManager

**Files:**
- Create: `webrtc/PeerConnectionManager.kt`

- [ ] **Step 1: Implement WebRTC manager**

```kotlin
class PeerConnectionManager(private val signalingClient: SignalingClient) {
    fun createConnection(peerId: String): PeerConnection { /* stream-webrtc-android, STUN config, data channels */ }
    fun createDataChannel(pc: PeerConnection, transferId: String): DataChannel { /* unordered, reliable */ }
    fun handleOffer(peerId: String, sdp: SessionDescription) { /* create answer */ }
    fun handleAnswer(peerId: String, sdp: SessionDescription)
    fun handleIceCandidate(peerId: String, candidate: IceCandidate)
    fun restartIce(peerId: String) { /* iceRestart: true */ }
}
```

- [ ] **Step 2: Commit** — `git commit -m "feat(android): PeerConnectionManager with stream-webrtc"`

---

### Task 34: Parallel Path Racing

**Files:**
- Modify: `service/TransferEngine.kt`

- [ ] **Step 1: Start relay + ICE simultaneously** — first ready wins, mid-transfer upgrade if P2P comes later

- [ ] **Step 2: Commit** — `git commit -m "feat(android): parallel path racing relay + P2P"`

---

### Task 35: Mid-Transfer Path Upgrade + ICE Restart

**Files:**
- Modify: `service/TransferEngine.kt`
- Modify: `webrtc/PeerConnectionManager.kt`

- [ ] **Step 1: Path upgrade** — DataChannel opens → send path-upgrade → switch sending → release relay

- [ ] **Step 2: ICE restart on network change** — ConnectivityManager callback → restartIce() → relay bridges during restart

- [ ] **Step 3: Commit** — `git commit -m "feat(android): mid-transfer path upgrade and ICE restart"`

---

## Completion Checklist

- [ ] All phases A-J implemented
- [ ] Crypto unit tests passing (`./gradlew testDebugUnitTest`)
- [ ] Cross-platform test vectors matching Chrome extension
- [ ] Manual test: pair with Chrome extension via QR
- [ ] Manual test: receive text clipboard from Chrome
- [ ] Manual test: receive file from Chrome over relay
- [ ] Manual test: send file to Chrome over relay
- [ ] Manual test: P2P transfer on same WiFi network
- [ ] Manual test: WiFi → cellular mid-transfer (ICE restart)
- [ ] Manual test: app backgrounded during transfer (foreground service keeps going)
- [ ] Manual test: kill app during transfer, reopen, transfer resumes
- [ ] Manual test: battery optimization dialog appears after first pairing
- [ ] APK size check: ~17MB with ABI split
