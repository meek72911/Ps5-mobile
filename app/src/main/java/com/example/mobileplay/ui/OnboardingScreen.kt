package com.example.mobileplay.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.mobileplay.remoteplay.ConnectionState
import com.example.mobileplay.remoteplay.SonyAuthManager
import com.example.mobileplay.security.RegisteredConsole
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CharcoalSurfaceHigh
import com.example.ui.theme.MutedCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateMutedText
import com.example.ui.theme.SoftIceText
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.TacticalCoral

/**
 * 5-Screen PlayStation 5 Onboarding Wizard:
 * 1. Welcome  2. Sony Sign-In (Browser OAuth / Manual Account ID)  3. Find PS5  4. Pair Device  5. Connect
 */
@Composable
fun OnboardingScreen(
    uiState: MobilePlayUiState,
    onSetStep: (OnboardingStep) -> Unit,
    onExitToHome: () -> Unit,
    onOpenConsoles: () -> Unit,
    onOpenOfflineDemo: () -> Unit,
    onVerifyRedirectUrl: (String) -> Result<String>,
    onSaveManualAccountId: (String) -> Result<String>,
    onScanLocalWifi: () -> Unit,
    onPairConsole: (hostName: String, hostAddress: String, psnAccountId: String, pinCode: String) -> Unit,
    onConnectConsole: (RegisteredConsole) -> Unit
) {
    BackHandler {
        val prev = OnboardingStep.entries.getOrNull(uiState.onboardingStep.ordinal - 1)
        if (prev != null) onSetStep(prev) else onExitToHome()
    }

    val context = LocalContext.current
    var redirectUrlInput by rememberSaveable { mutableStateOf("") }
    var manualAccountInput by rememberSaveable { mutableStateOf(uiState.sonyAuthSession?.psnAccountIdBase64.orEmpty()) }
    var authFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    var hostName by rememberSaveable { mutableStateOf("") }
    var hostIp by rememberSaveable { mutableStateOf("") }
    var pinCode by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Stepper Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onExitToHome,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("onboarding_close_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SoftIceText)
                }
                Column {
                    Text(
                        text = "PS5 SETUP  •  STEP ${uiState.onboardingStep.stepNumber} OF 5",
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedCyan
                    )
                    Text(
                        text = uiState.onboardingStep.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = SoftIceText
                    )
                }
            }

            // Compact Step Indicator Pills
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OnboardingStep.entries.forEach { step ->
                    val current = step == uiState.onboardingStep
                    val completed = step.ordinal < uiState.onboardingStep.ordinal
                    Box(
                        modifier = Modifier
                            .size(if (current) 26.dp else 20.dp)
                            .background(
                                color = when {
                                    current -> MutedCyan
                                    completed -> StatusEmerald
                                    else -> CharcoalSurfaceHigh
                                },
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${step.stepNumber}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (current || completed) ObsidianBg else SlateMutedText
                        )
                    }
                }
            }
        }

        // Step Content Area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            when (uiState.onboardingStep) {
                OnboardingStep.WELCOME -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = CharcoalSurface,
                            border = BorderStroke(1.dp, MutedCyan.copy(alpha = 0.45f)),
                            modifier = Modifier.weight(1.2f)
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "PlayStation 5 Remote Play on Your Phone",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = SoftIceText
                                )
                                Text(
                                    text = "Connect over local Wi-Fi using legitimate Sony Remote Play registration and play with fully customizable PUBG/BGMI-style multi-touch controls.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SlateMutedText
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = { onSetStep(OnboardingStep.SIGN_IN) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MutedCyan, contentColor = ObsidianBg),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .testTag("onboarding_connect_ps5_button")
                                ) {
                                    Icon(Icons.Default.Router, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Connect PlayStation 5")
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = CharcoalSurface,
                                border = BorderStroke(1.dp, CharcoalBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Previously Connected Consoles",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = SoftIceText
                                    )
                                    Text(
                                        text = "${uiState.savedConsoles.size} console(s) encrypted in Android Keystore.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SlateMutedText
                                    )
                                    OutlinedButton(
                                        onClick = onOpenConsoles,
                                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                                    ) {
                                        Text("View Saved Consoles")
                                    }
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = CharcoalSurface,
                                border = BorderStroke(1.dp, CharcoalBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Offline Controls Demo",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = SoftIceText
                                    )
                                    Text(
                                        text = "Test multi-touch joystick, camera velocity curve, fire-drag aim, and Driving Mode immediately without a PS5.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SlateMutedText
                                    )
                                    OutlinedButton(
                                        onClick = onOpenOfflineDemo,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .minimumInteractiveComponentSize()
                                            .testTag("onboarding_offline_demo_button")
                                    ) {
                                        Icon(Icons.Default.Gamepad, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Launch Offline Demo")
                                    }
                                }
                            }
                        }
                    }
                }

                OnboardingStep.SIGN_IN -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Browser-based legitimate Sony OAuth Sign-In
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CharcoalSurface,
                            border = BorderStroke(1.dp, MutedCyan.copy(alpha = 0.4f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Security, contentDescription = null, tint = StatusEmerald, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = "Option 1: Sony Browser Sign-In",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = SoftIceText
                                    )
                                }
                                Text(
                                    text = "Opens Sony's official sign-in page in your Android browser (handles MFA and passkeys safely). MobilePlay never asks for or stores your PSN password.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SlateMutedText
                                )
                                Button(
                                    onClick = {
                                        runCatching {
                                            val intent = Intent(Intent.ACTION_VIEW, SonyAuthManager.buildBrowserSignInUri())
                                            context.startActivity(intent)
                                        }.onFailure { err ->
                                            authFeedback = "Could not launch browser: ${err.message}"
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MutedCyan, contentColor = ObsidianBg),
                                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Sign In via Sony Browser")
                                }
                                OutlinedTextField(
                                    value = redirectUrlInput,
                                    onValueChange = { redirectUrlInput = it },
                                    label = { Text("Paste Sony Redirect URL (optional)") },
                                    placeholder = { Text("https://remoteplay.dl.playstation.net/remoteplay/redirect?code=...") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("onboarding_redirect_url_input")
                                )
                                OutlinedButton(
                                    onClick = {
                                        val res = onVerifyRedirectUrl(redirectUrlInput)
                                        authFeedback = res.fold(
                                            onSuccess = { it },
                                            onFailure = { it.message ?: "Invalid redirect URL" }
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                                ) {
                                    Text("Verify Redirect URL")
                                }
                            }
                        }

                        // Manual Account ID (chiaki-ng compatible Base64 or 64-bit numeric)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CharcoalSurface,
                            border = BorderStroke(1.dp, CharcoalBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Key, contentDescription = null, tint = MutedCyan, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = "Option 2: Manual PSN Account ID",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = SoftIceText
                                    )
                                }
                                Text(
                                    text = "Enter your 8-byte Base64 Account ID (from chiaki-ng / PSN lookup) OR your 64-bit numeric PSN Account ID — MobilePlay converts numeric IDs to libchiaki's Base64 format locally.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SlateMutedText
                                )
                                OutlinedTextField(
                                    value = manualAccountInput,
                                    onValueChange = { manualAccountInput = it },
                                    label = { Text("PSN Account ID (Base64 or Numeric)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("onboarding_account_id_input")
                                )
                                authFeedback?.let { msg ->
                                    Text(text = msg, style = MaterialTheme.typography.bodySmall, color = StatusAmber)
                                }
                                uiState.sonyAuthSession?.let { session ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusEmerald, modifier = Modifier.size(16.dp))
                                        Text(
                                            text = session.methodLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StatusEmerald
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val res = onSaveManualAccountId(manualAccountInput)
                                            authFeedback = res.fold(
                                                onSuccess = { b64 ->
                                                    manualAccountInput = b64
                                                    "Validated libchiaki Account ID ($b64)."
                                                },
                                                onFailure = { it.message ?: "Invalid Account ID" }
                                            )
                                        },
                                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                                    ) {
                                        Text("Validate ID")
                                    }
                                    Button(
                                        onClick = {
                                            if (manualAccountInput.isNotBlank()) {
                                                onSaveManualAccountId(manualAccountInput)
                                            }
                                            onSetStep(OnboardingStep.FIND_PS5)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MutedCyan, contentColor = ObsidianBg),
                                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("onboarding_next_find_ps5")
                                    ) {
                                        Text("Next: Find PS5")
                                    }
                                }
                            }
                        }
                    }
                }

                OnboardingStep.FIND_PS5 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CharcoalSurface,
                            border = BorderStroke(1.dp, CharcoalBorder),
                            modifier = Modifier.weight(1.1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Local Network Discovery (UDP 9302)", style = MaterialTheme.typography.titleSmall, color = SoftIceText)
                                    Button(
                                        onClick = onScanLocalWifi,
                                        enabled = uiState.connectionState !is ConnectionState.Discovering,
                                        colors = ButtonDefaults.buttonColors(containerColor = MutedCyan, contentColor = ObsidianBg),
                                        modifier = Modifier.minimumInteractiveComponentSize().testTag("onboarding_scan_button")
                                    ) {
                                        if (uiState.connectionState is ConnectionState.Discovering) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ObsidianBg)
                                        } else {
                                            Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Scan Wi-Fi")
                                        }
                                    }
                                }
                                Text(text = uiState.lastScanMessage, style = MaterialTheme.typography.bodySmall, color = SlateMutedText)
                                uiState.discoveredConsoles.forEach { host ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = CharcoalSurfaceHigh,
                                        border = BorderStroke(1.dp, MutedCyan.copy(alpha = 0.5f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("${host.hostName} (${host.hostType})", style = MaterialTheme.typography.titleSmall, color = SoftIceText)
                                                Text("${host.hostAddress} • FW ${host.systemVersion}", style = MaterialTheme.typography.labelSmall, color = MutedCyan)
                                            }
                                            Button(
                                                onClick = {
                                                    hostName = host.hostName
                                                    hostIp = host.hostAddress
                                                    onSetStep(OnboardingStep.PAIR_DEVICE)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = MutedCyan, contentColor = ObsidianBg)
                                            ) {
                                                Text("Select")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CharcoalSurface,
                            border = BorderStroke(1.dp, CharcoalBorder),
                            modifier = Modifier.weight(0.9f)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("Manual Console IP", style = MaterialTheme.typography.titleSmall, color = SoftIceText)
                                Text(
                                    text = "If UDP broadcast is blocked by your router or AP isolation, enter your PS5's local IP address directly.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SlateMutedText
                                )
                                OutlinedTextField(
                                    value = hostName,
                                    onValueChange = { hostName = it },
                                    label = { Text("Console Name") },
                                    placeholder = { Text("Living Room PS5") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = hostIp,
                                    onValueChange = { hostIp = it },
                                    label = { Text("PS5 Local IP Address") },
                                    placeholder = { Text("192.168.1.50") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("onboarding_ip_input")
                                )
                                Button(
                                    onClick = { onSetStep(OnboardingStep.PAIR_DEVICE) },
                                    enabled = hostIp.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MutedCyan, contentColor = ObsidianBg),
                                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                                ) {
                                    Text("Next: Pair Device")
                                }
                            }
                        }
                    }
                }

                OnboardingStep.PAIR_DEVICE -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CharcoalSurface,
                        border = BorderStroke(1.dp, CharcoalBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Pair With PS5 (${hostIp.ifBlank { "No IP selected" }})",
                                style = MaterialTheme.typography.titleMedium,
                                color = SoftIceText
                            )
                            Text(
                                text = "1. On your PS5, open Settings → System → Remote Play → Enable Remote Play.\n" +
                                    "2. Select Link Device to display an 8-digit pairing PIN.\n" +
                                    "3. Enter the PIN below. Credentials are encrypted via Android Keystore AES-256-GCM.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateMutedText
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = manualAccountInput,
                                    onValueChange = { manualAccountInput = it },
                                    label = { Text("PSN Account ID (Base64/Numeric)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = pinCode,
                                    onValueChange = { input ->
                                        if (input.length <= 8 && input.all { it.isDigit() }) pinCode = input
                                    },
                                    label = { Text("8-Digit PS5 PIN") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).testTag("onboarding_pin_input")
                                )
                            }
                            uiState.pairStatusMessage?.let { msg ->
                                Text(text = msg, style = MaterialTheme.typography.bodySmall, color = StatusAmber)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { onPairConsole(hostName, hostIp, manualAccountInput, pinCode) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MutedCyan, contentColor = ObsidianBg),
                                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("onboarding_pair_submit")
                                ) {
                                    Text("Register & Save to Keystore")
                                }
                                OutlinedButton(
                                    onClick = { onSetStep(OnboardingStep.CONNECT) },
                                    modifier = Modifier.weight(0.7f).minimumInteractiveComponentSize()
                                ) {
                                    Text("Next: Connect")
                                }
                            }
                        }
                    }
                }

                OnboardingStep.CONNECT -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CharcoalSurface,
                        border = BorderStroke(1.dp, CharcoalBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Ready to Connect",
                                style = MaterialTheme.typography.titleMedium,
                                color = SoftIceText
                            )
                            Text(
                                text = "Stream Profile: ${uiState.streamQuality.summaryLabel()}  •  Engine: ${uiState.engineStatusLabel}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MutedCyan
                            )
                            val targetConsole = uiState.savedConsoles.firstOrNull()
                            if (targetConsole != null) {
                                Text(
                                    text = "Target Console: ${targetConsole.hostName} (${targetConsole.hostAddress}) — ${targetConsole.registrationNote}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SoftIceText
                                )
                            } else {
                                Text(
                                    text = "No paired console saved yet. Complete Step 4 or test the Offline Controls Demo.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SlateMutedText
                                )
                            }

                            when (val st = uiState.connectionState) {
                                is ConnectionState.NativeEngineRequired -> Text(
                                    text = st.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusAmber
                                )
                                is ConnectionState.Error -> Text(
                                    text = st.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TacticalCoral
                                )
                                is ConnectionState.Connected -> Text(
                                    text = "Connected to ${st.hostName}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusEmerald
                                )
                                else -> Unit
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { targetConsole?.let(onConnectConsole) },
                                    enabled = targetConsole != null,
                                    colors = ButtonDefaults.buttonColors(containerColor = MutedCyan, contentColor = ObsidianBg),
                                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Connect to PS5")
                                }
                                OutlinedButton(
                                    onClick = onOpenOfflineDemo,
                                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                                ) {
                                    Text("Enter Landscape Gameplay")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
