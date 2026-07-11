package com.example.ui.screens

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.entity.CallRecord
import com.example.data.entity.Contact
import com.example.data.entity.IceServer
import com.example.ui.CallLinkViewModel
import com.example.ui.components.CameraFallback
import com.example.ui.components.CameraPreview
import com.example.ui.theme.*
import com.example.webrtc.CallSession
import com.example.webrtc.CallState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppNavigation(viewModel: CallLinkViewModel) {
    val currentUserSession by viewModel.currentUserState.collectAsState()
    var showSplash by remember { mutableStateOf(true) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (currentUserSession == null) {
            if (showSplash) {
                SplashOnboardingScreen(onGetStarted = { showSplash = false })
            } else {
                LoginScreen(viewModel = viewModel, onLoginSuccess = {})
            }
        } else {
            DashboardMainScreen(viewModel = viewModel, currentUsername = currentUserSession?.name ?: "User")
        }

        // Calling UI Overlay
        val session by viewModel.currentSession.collectAsState()
        session?.let { activeSession ->
            ActiveCallOverlay(session = activeSession, viewModel = viewModel)
        }
    }
}

// 1. SPLASH SCREEN
@Composable
fun SplashOnboardingScreen(onGetStarted: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DeepCharcoal, Color(0xFF101424))
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Pulse Animation for Logo
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.95f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )

            Box(
                modifier = Modifier
                    .scale(scale)
                    .size(100.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(CyberCyan.copy(alpha = 0.3f), Color.Transparent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Stream,
                    contentDescription = "CallLink Logo",
                    tint = CyberCyan,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "D'val CallLink",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 2.sp
                ),
                color = CyberCyan
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Production-grade Secure WebRTC Telecom",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onGetStarted,
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("get_started_button")
            ) {
                Text(
                    text = "GET STARTED",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

// 2. LOGIN SCREEN
@Composable
fun LoginScreen(
    viewModel: CallLinkViewModel,
    onLoginSuccess: () -> Unit
) {
    var isSignUp by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val isFirebaseAvailable by viewModel.isFirebaseAvailable.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepCharcoal)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateCard, RoundedCornerShape(24.dp))
                .border(1.dp, CyberCyan.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isSignUp) "Create SIP Account" else "SIP Console Login",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isSignUp) "Register to receive a real-time call sign-up identity" else "Enter credentials to access secure rooms",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isSignUp) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it; errorMessage = null },
                    label = { Text("Display Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberCyan,
                        unfocusedBorderColor = GlassWhite,
                        focusedLabelColor = CyberCyan,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("username_input")
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = emailInput,
                onValueChange = { emailInput = it; errorMessage = null },
                label = { Text("Email Address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = GlassWhite,
                    focusedLabelColor = CyberCyan,
                    unfocusedLabelColor = TextSecondary,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth().testTag("email_input")
            )

            Spacer(modifier = Modifier.height(16.dp))

            var isPasswordVisible by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = passwordInput,
                onValueChange = { passwordInput = it; errorMessage = null },
                label = { Text("Password (min 6 chars)") },
                singleLine = true,
                visualTransformation = if (isPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (isPasswordVisible) "Hide Password" else "Show Password",
                            tint = TextSecondary
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = GlassWhite,
                    focusedLabelColor = CyberCyan,
                    unfocusedLabelColor = TextSecondary,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth().testTag("password_input")
            )

            if (!isSignUp) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            if (emailInput.isBlank()) {
                                errorMessage = "Please enter your email address first"
                                return@TextButton
                            }
                            isLoading = true
                            viewModel.sendPasswordReset(emailInput) { success, err ->
                                isLoading = false
                                if (success) {
                                    errorMessage = "Reset link sent successfully to $emailInput"
                                } else {
                                    errorMessage = err ?: "Failed to send reset link"
                                }
                            }
                        }
                    ) {
                        Text(
                            text = "Forgot Password?",
                            color = CyberCyan,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = error,
                    color = SignalCoral,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isLoading) {
                CircularProgressIndicator(color = CyberCyan)
            } else {
                Button(
                    onClick = {
                        if (emailInput.isBlank() || passwordInput.isBlank() || (isSignUp && nameInput.isBlank())) {
                            errorMessage = "All fields are required"
                            return@Button
                        }
                        if (passwordInput.length < 6) {
                            errorMessage = "Password must be at least 6 characters"
                            return@Button
                        }
                        isLoading = true
                        if (isSignUp) {
                            viewModel.signUp(emailInput, passwordInput, nameInput) { success, err ->
                                isLoading = false
                                if (success) {
                                    onLoginSuccess()
                                } else {
                                    errorMessage = err ?: "Registration Failed"
                                }
                            }
                        } else {
                            viewModel.login(emailInput, passwordInput) { success, err ->
                                isLoading = false
                                if (success) {
                                    onLoginSuccess()
                                } else {
                                    errorMessage = err ?: "Authentication Failed"
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("login_button")
                ) {
                    Text(
                        text = if (isSignUp) "CREATE ACCOUNT" else "SECURE LOGIN",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = {
                        isSignUp = !isSignUp
                        errorMessage = null
                    }
                ) {
                    Text(
                        text = if (isSignUp) "Already have an account? Sign In" else "Don't have an account? Sign Up",
                        color = CyberCyan
                    )
                }

                if (!isFirebaseAvailable) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Running in Offline SQLite Fallback mode",
                        color = Color(0xFFFFC107),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

// 3. DASHBOARD MAIN SCREEN
@Composable
fun DashboardMainScreen(viewModel: CallLinkViewModel, currentUsername: String) {
    var selectedTab by remember { mutableStateOf("dialer") } // "dialer", "history", "contacts", "settings"

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = SlateCard,
                modifier = Modifier.border(0.5.dp, GlassWhite, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                NavigationBarItem(
                    selected = selectedTab == "dialer",
                    onClick = { selectedTab = "dialer" },
                    label = { Text("Dialer") },
                    icon = { Icon(Icons.Default.Dialpad, contentDescription = "Dialer") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = CyberCyan.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier.testTag("tab_dialer")
                )
                NavigationBarItem(
                    selected = selectedTab == "history",
                    onClick = { selectedTab = "history" },
                    label = { Text("History") },
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = CyberCyan.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier.testTag("tab_history")
                )
                NavigationBarItem(
                    selected = selectedTab == "contacts",
                    onClick = { selectedTab = "contacts" },
                    label = { Text("Contacts") },
                    icon = { Icon(Icons.Default.ContactPhone, contentDescription = "Contacts") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = CyberCyan.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier.testTag("tab_contacts")
                )
                NavigationBarItem(
                    selected = selectedTab == "settings",
                    onClick = { selectedTab = "settings" },
                    label = { Text("STUN/TURN") },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = CyberCyan.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier.testTag("tab_settings")
                )
            }
        },
        containerColor = DeepCharcoal
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                "dialer" -> DialerScreen(viewModel = viewModel, username = currentUsername)
                "history" -> CallHistoryScreen(viewModel = viewModel)
                "contacts" -> ContactsScreen(viewModel = viewModel)
                "settings" -> SettingsScreen(viewModel = viewModel, username = currentUsername)
            }
        }
    }
}

// 4. DIALER SCREEN
@Composable
fun DialerScreen(viewModel: CallLinkViewModel, username: String) {
    var phoneInput by remember { mutableStateOf("") }
    var roomCodeInput by remember { mutableStateOf("") }
    val contacts by viewModel.allContacts.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val isFirebaseAvailable by viewModel.isFirebaseAvailable.collectAsState()
    val currentUserSession by viewModel.currentUserState.collectAsState()
    val context = LocalContext.current

    val myPhoneNumber = currentUserSession?.phoneNumber ?: "+1 (609) 222-2064"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Simple User profile header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateCard, RoundedCornerShape(16.dp))
                .border(1.dp, CyberCyan.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(CyberCyan.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = username.take(2).uppercase(),
                    color = CyberCyan,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = username, color = Color.White, fontWeight = FontWeight.Bold)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = android.content.ClipData.newPlainText("My SIP Number", myPhoneNumber)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "SIP Number copied!", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(
                        text = "My ID: $myPhoneNumber",
                        color = CyberCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = CyberCyan.copy(alpha = 0.6f),
                        modifier = Modifier.size(10.dp)
                    )
                }
                Text(
                    text = syncStatus,
                    color = if (isFirebaseAvailable) ElectricTeal else TextSecondary,
                    fontSize = 10.sp
                )
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (isFirebaseAvailable) ElectricTeal else Color(0xFFFFC107), CircleShape)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Join Room by Link/Code Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateCard, RoundedCornerShape(12.dp))
                .border(0.5.dp, GlassWhite.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = roomCodeInput,
                onValueChange = { roomCodeInput = it },
                placeholder = { Text("Invite Room Link / Code", color = TextSecondary, fontSize = 11.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Black.copy(alpha = 0.2f),
                    unfocusedContainerColor = Color.Black.copy(alpha = 0.2f)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (roomCodeInput.isNotBlank()) {
                        val cleanedCode = if (roomCodeInput.contains("room=")) {
                            roomCodeInput.substringAfter("room=")
                        } else {
                            roomCodeInput.trim()
                        }
                        viewModel.joinRoomById(cleanedCode)
                        roomCodeInput = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("JOIN", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Large Phone display
        Text(
            text = phoneInput.ifEmpty { "Enter Number" },
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            ),
            color = if (phoneInput.isEmpty()) TextSecondary else CyberCyan,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 3x4 dialpad grid
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(keys) { key ->
                IconButton(
                    onClick = { phoneInput += key },
                    modifier = Modifier
                        .aspectRatio(1.6f)
                        .background(SlateCard, RoundedCornerShape(12.dp))
                ) {
                    Text(
                        text = key,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Delete key
            IconButton(
                onClick = { if (phoneInput.isNotEmpty()) phoneInput = phoneInput.dropLast(1) },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.Backspace, contentDescription = "Backspace", tint = Color.White)
            }

            // Audio Call button
            FloatingActionButton(
                onClick = {
                    if (phoneInput.isNotEmpty()) {
                        val existing = contacts.find { it.phone == phoneInput } ?: Contact(phoneInput, "Direct Dial")
                        viewModel.startCall(existing, isVoiceOnly = true)
                    }
                },
                containerColor = ElectricTeal,
                contentColor = Color.Black,
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Default.Call, contentDescription = "Voice Call", modifier = Modifier.size(28.dp))
            }

            // Video Call button
            FloatingActionButton(
                onClick = {
                    if (phoneInput.isNotEmpty()) {
                        val existing = contacts.find { it.phone == phoneInput } ?: Contact(phoneInput, "Direct Dial")
                        viewModel.startCall(existing, isVoiceOnly = false)
                    }
                },
                containerColor = CyberCyan,
                contentColor = Color.Black,
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Default.Videocam, contentDescription = "Video Call", modifier = Modifier.size(28.dp))
            }
        }
    }
}

// 5. CALL HISTORY SCREEN
@Composable
fun CallHistoryScreen(viewModel: CallLinkViewModel) {
    val callRecords by viewModel.allCallRecords.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.syncCallHistoryFromCloud()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Call Logs",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.syncCallHistoryFromCloud() }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sync from cloud",
                            tint = CyberCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sync Cloud", color = CyberCyan, fontSize = 12.sp)
                    }
                }
                if (callRecords.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { viewModel.clearCallHistory() }) {
                        Text("Clear All", color = SignalCoral, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (callRecords.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "No Logs",
                        tint = TextSecondary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Your call history is empty", color = TextSecondary)
                    Text("Call records will automatically log here.", color = TextSecondary, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(callRecords) { record ->
                    CallHistoryItem(record = record, onDelete = { viewModel.deleteCallRecord(record.id) }, onDial = {
                        val mockContact = Contact(phone = record.contactPhone, name = record.contactName)
                        viewModel.startCall(mockContact, isVoiceOnly = record.isVoice)
                    })
                }
            }
        }
    }
}

@Composable
fun CallHistoryItem(record: CallRecord, onDelete: () -> Unit, onDial: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlateCard, RoundedCornerShape(12.dp))
            .clickable(onClick = onDial)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val iconColor = when (record.callType) {
            "Incoming" -> ElectricTeal
            "Outgoing" -> CyberCyan
            else -> SignalCoral
        }

        val icon = when (record.callType) {
            "Incoming" -> Icons.AutoMirrored.Filled.CallReceived
            "Outgoing" -> Icons.AutoMirrored.Filled.CallMade
            else -> Icons.AutoMirrored.Filled.CallMissed
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .background(iconColor.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = record.callType, tint = iconColor)
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = record.contactName, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            val formattedTime = remember(record.timestamp) {
                try {
                    val sdf = java.text.SimpleDateFormat("MMM dd, yyyy • hh:mm a", java.util.Locale.getDefault())
                    sdf.format(java.util.Date(record.timestamp))
                } catch (e: Exception) {
                    ""
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (record.isVoice) Icons.Default.Call else Icons.Default.Videocam,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${record.contactPhone} • ${if (record.durationSeconds > 0) "${record.durationSeconds}s" else "Missed"}",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
            if (formattedTime.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formattedTime,
                    color = TextSecondary.copy(alpha = 0.8f),
                    fontSize = 10.sp
                )
            }
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete record", tint = TextSecondary.copy(alpha = 0.6f))
        }
    }
}

// 6. CONTACTS SCREEN
@Composable
fun SignalStrengthBars(bars: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        for (i in 1..4) {
            val isFilled = i <= bars
            val height = (i * 3.5).dp
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(height)
                    .background(
                        color = if (isFilled) {
                            if (bars >= 3) ElectricTeal else SignalCoral
                        } else {
                            Color.White.copy(alpha = 0.2f)
                        },
                        shape = RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

@Composable
fun ContactProfileDialog(
    contact: Contact,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleBlocked: () -> Unit,
    onUpdateGroup: (String) -> Unit,
    onDialVoice: () -> Unit,
    onDialVideo: () -> Unit
) {
    var expandedGroupDropdown by remember { mutableStateOf(false) }
    val groups = listOf("None", "Family", "Work", "Friends", "Other")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeepCharcoal,
        tonalElevation = 6.dp,
        modifier = Modifier.border(1.dp, CyberCyan.copy(alpha = 0.4f), RoundedCornerShape(28.dp)),
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large Avatar
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            Brush.sweepGradient(
                                colors = listOf(CyberCyan, ElectricTeal, CyberCyan)
                            ),
                            CircleShape
                        )
                        .padding(3.dp)
                        .background(DeepCharcoal, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contact.name.take(2).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = contact.phone,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (contact.status == "Online") ElectricTeal else Color.Gray, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = contact.status, color = TextSecondary, fontSize = 12.sp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text(text = "Contact Group", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Box {
                        OutlinedButton(
                            onClick = { expandedGroupDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = contact.group.ifEmpty { "None" }, color = Color.White)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Dropdown")
                            }
                        }
                        DropdownMenu(
                            expanded = expandedGroupDropdown,
                            onDismissRequest = { expandedGroupDropdown = false },
                            modifier = Modifier.background(SlateCard)
                        ) {
                            groups.forEach { groupName ->
                                DropdownMenuItem(
                                    text = { Text(text = groupName, color = Color.White) },
                                    onClick = {
                                        onUpdateGroup(groupName)
                                        expandedGroupDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                Divider(color = GlassWhite.copy(alpha = 0.1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier.background(SlateCard, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (contact.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "Favorite",
                                tint = if (contact.isFavorite) CyberCyan else Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Favorite", color = TextSecondary, fontSize = 11.sp)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = onToggleBlocked,
                            modifier = Modifier.background(
                                if (contact.isBlocked) SignalCoral.copy(alpha = 0.2f) else SlateCard,
                                CircleShape
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Block,
                                contentDescription = "Block",
                                tint = if (contact.isBlocked) SignalCoral else Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (contact.isBlocked) "Blocked" else "Block",
                            color = if (contact.isBlocked) SignalCoral else TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onDialVoice()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricTeal),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Call, contentDescription = "Voice Call", tint = Color.Black)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Voice", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        onDialVideo()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = Color.Black)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Video", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    )
}

@Composable
fun ContactsScreen(viewModel: CallLinkViewModel) {
    val contacts by viewModel.allContacts.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current

    var selectedGroupFilter by remember { mutableStateOf("All") }
    val groupFilters = listOf("All", "Family", "Work", "Friends", "Favorites")

    var selectedContactForProfile by remember { mutableStateOf<Contact?>(null) }

    var cloudSearchResults by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var isSearchingCloud by remember { mutableStateOf(false) }

    val filteredContacts = contacts.filter {
        val matchesSearch = it.name.contains(searchQuery, ignoreCase = true) || it.phone.contains(searchQuery)
        val matchesGroup = when (selectedGroupFilter) {
            "All" -> !it.isBlocked
            "Favorites" -> it.isFavorite && !it.isBlocked
            else -> it.group == selectedGroupFilter && !it.isBlocked
        }
        matchesSearch && matchesGroup
    }

    val blockedPhones = remember(contacts) { contacts.filter { it.isBlocked }.map { it.phone }.toSet() }
    val filteredCloudResults = cloudSearchResults.filter { !blockedPhones.contains(it.phone) }

    LaunchedEffect(searchQuery) {
        val trimmed = searchQuery.trim()
        if (trimmed.length >= 2) {
            isSearchingCloud = true
            viewModel.searchCloudPeers(trimmed) { results ->
                cloudSearchResults = results
                isSearchingCloud = false
            }
        } else {
            cloudSearchResults = emptyList()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "My Contacts",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )

                IconButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier
                        .background(CyberCyan, CircleShape)
                        .size(36.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Contact", tint = Color.Black)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search local or search globally by +1 (609) 222-XXXX...", color = TextSecondary, fontSize = 12.sp) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = GlassWhite,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Group Filtering Tab Bar
            ScrollableTabRow(
                selectedTabIndex = groupFilters.indexOf(selectedGroupFilter),
                containerColor = Color.Transparent,
                contentColor = CyberCyan,
                edgePadding = 0.dp
            ) {
                groupFilters.forEach { filterName ->
                    Tab(
                        selected = selectedGroupFilter == filterName,
                        onClick = { selectedGroupFilter = filterName },
                        text = { Text(text = filterName, color = if (selectedGroupFilter == filterName) CyberCyan else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isSearchingCloud) {
                LinearProgressIndicator(
                    color = CyberCyan,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }

            if (filteredCloudResults.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .background(CyberCyan.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                        .border(1.2.dp, CyberCyan.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Global Directory Matches (${filteredCloudResults.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = CyberCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        filteredCloudResults.forEach { result ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SlateCard.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.startCall(result, isVoiceOnly = true)
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = result.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(text = result.phone, color = TextSecondary, fontSize = 11.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .background(if (result.status == "Online") ElectricTeal else Color.Gray, CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(text = result.status, color = TextSecondary, fontSize = 10.sp)
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            viewModel.addContact(result.name, result.phone)
                                            Toast.makeText(context, "Added to local contacts!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .background(GlassWhite.copy(alpha = 0.15f), CircleShape)
                                            .size(32.dp)
                                    ) {
                                        Icon(Icons.Default.PersonAdd, contentDescription = "Add Contact", tint = Color.White, modifier = Modifier.size(14.dp))
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    IconButton(
                                        onClick = { viewModel.startCall(result, isVoiceOnly = true) },
                                        modifier = Modifier
                                            .background(ElectricTeal, CircleShape)
                                            .size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Call, contentDescription = "Voice Call", tint = Color.Black, modifier = Modifier.size(14.dp))
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    IconButton(
                                        onClick = { viewModel.startCall(result, isVoiceOnly = false) },
                                        modifier = Modifier
                                            .background(CyberCyan, CircleShape)
                                            .size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = Color.Black, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (filteredContacts.isEmpty() && filteredCloudResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No contacts matching criteria.", color = TextSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredContacts) { contact ->
                        ContactItem(
                            contact = contact,
                            onShowProfile = { selectedContactForProfile = contact },
                            onDialVoice = { viewModel.startCall(contact, isVoiceOnly = true) },
                            onDialVideo = { viewModel.startCall(contact, isVoiceOnly = false) },
                            onToggleFavorite = { viewModel.toggleContactFavorite(contact.phone, contact.isFavorite) },
                            onDelete = { viewModel.deleteContact(contact) }
                        )
                    }
                }
            }
        }

        if (showAddDialog) {
            AddContactDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { name, phone, group ->
                    viewModel.addContact(name, phone, group)
                    showAddDialog = false
                }
            )
        }

        selectedContactForProfile?.let { activeContact ->
            val updatedContact = contacts.find { it.phone == activeContact.phone } ?: activeContact
            ContactProfileDialog(
                contact = updatedContact,
                onDismiss = { selectedContactForProfile = null },
                onToggleFavorite = { viewModel.toggleContactFavorite(updatedContact.phone, updatedContact.isFavorite) },
                onToggleBlocked = { viewModel.toggleContactBlocked(updatedContact.phone, updatedContact.isBlocked) },
                onUpdateGroup = { newGroup -> viewModel.updateContactGroup(updatedContact.phone, newGroup) },
                onDialVoice = { viewModel.startCall(updatedContact, isVoiceOnly = true) },
                onDialVideo = { viewModel.startCall(updatedContact, isVoiceOnly = false) }
            )
        }
    }
}

@Composable
fun ContactItem(
    contact: Contact,
    onShowProfile: () -> Unit,
    onDialVoice: () -> Unit,
    onDialVideo: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlateCard, RoundedCornerShape(12.dp))
            .clickable { onShowProfile() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Gradient Ring Avatar
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    Brush.sweepGradient(
                        colors = listOf(CyberCyan, ElectricTeal, CyberCyan)
                    ),
                    CircleShape
                )
                .padding(2.dp)
                .background(DeepCharcoal, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact.name.take(2).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = contact.name, color = Color.White, fontWeight = FontWeight.Bold)
                if (contact.group.isNotEmpty() && contact.group != "None") {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(CyberCyan.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(text = contact.group.uppercase(), color = CyberCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(text = contact.phone, color = TextSecondary, fontSize = 12.sp)
        }

        // Action controls
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (contact.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "Toggle favorite",
                tint = if (contact.isFavorite) CyberCyan else TextSecondary
            )
        }

        IconButton(onClick = onDialVoice) {
            Icon(Icons.Default.Call, contentDescription = "Voice Call", tint = ElectricTeal)
        }

        IconButton(onClick = onDialVideo) {
            Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = CyberCyan)
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Default.DeleteForever, contentDescription = "Delete", tint = SignalCoral.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun AddContactDialog(onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("None") }
    var expandedGroupDropdown by remember { mutableStateOf(false) }
    val groups = listOf("None", "Family", "Work", "Friends", "Other")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New SIP Peer Contact", color = Color.White) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Peer Name") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.fillMaxWidth().testTag("contact_name_input")
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Peer Address / Phone") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.fillMaxWidth().testTag("contact_phone_input")
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                Column {
                    Text(text = "Assign Group", color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box {
                        OutlinedButton(
                            onClick = { expandedGroupDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = group, color = Color.White)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Dropdown")
                            }
                        }
                        DropdownMenu(
                            expanded = expandedGroupDropdown,
                            onDismissRequest = { expandedGroupDropdown = false },
                            modifier = Modifier.background(SlateCard)
                        ) {
                            groups.forEach { groupName ->
                                DropdownMenuItem(
                                    text = { Text(text = groupName, color = Color.White) },
                                    onClick = {
                                        group = groupName
                                        expandedGroupDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank() && phone.isNotBlank()) onConfirm(name, phone, group) },
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
            ) {
                Text("ADD", color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        },
        containerColor = SlateCard
    )
}

// 7. SETTINGS / ICE CONSOLE SCREEN
@Composable
fun SettingsScreen(viewModel: CallLinkViewModel, username: String) {
    val iceServers by viewModel.allIceServers.collectAsState()
    val contacts by viewModel.allContacts.collectAsState()
    var labelInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "WebRTC Core Engines",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )

        Spacer(modifier = Modifier.height(16.dp))

        // STUN / TURN panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateCard, RoundedCornerShape(16.dp))
                .border(0.5.dp, GlassWhite, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(text = "ICE / TURN Configurer", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Add Coturn or Google STUN/TURN nodes for secure NAT Traversal and peer negotiation.",
                color = TextSecondary,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = labelInput,
                onValueChange = { labelInput = it },
                label = { Text("Server Label (e.g. STUN West)") },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("Server URL (e.g. stun:stun.l.google.com)") },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (labelInput.isNotBlank() && urlInput.isNotBlank()) {
                        viewModel.addIceServer(labelInput, urlInput, isTurn = urlInput.startsWith("turn:"))
                        labelInput = ""
                        urlInput = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("ADD ICE SERVER NODE", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Current Nodes list
        Text(text = "Active Peer ICE Servers (${iceServers.size})", color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        iceServers.forEach { server ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(SlateCard, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = server.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(text = server.url, color = TextSecondary, fontSize = 11.sp)
                }

                IconButton(onClick = { viewModel.deleteIceServer(server) }) {
                    Icon(Icons.Default.Close, contentDescription = "Delete server", tint = SignalCoral)
                }
            }
        }

        val blockedContacts = contacts.filter { it.isBlocked }
        if (blockedContacts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "Blocked Peer Addresses (${blockedContacts.size})", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            blockedContacts.forEach { contact ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(SlateCard, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = contact.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(text = contact.phone, color = TextSecondary, fontSize = 11.sp)
                    }
                    Button(
                        onClick = { viewModel.toggleContactBlocked(contact.phone, true) },
                        colors = ButtonDefaults.buttonColors(containerColor = GlassWhite),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("UNBLOCK", color = CyberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Log Out Button
        Button(
            onClick = { viewModel.logout() },
            colors = ButtonDefaults.buttonColors(containerColor = SignalCoral),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("logout_button")
        ) {
            Icon(Icons.Default.Logout, contentDescription = "Log Out", tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("SECURE DE-AUTHORIZE / LOG OUT", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

// 8. ACTIVE CALL OVERLAY (THE SECURE SIGNALING CALL INTERFACE)
@Composable
fun ActiveCallOverlay(session: CallSession, viewModel: CallLinkViewModel) {
    val connectionLogs by viewModel.connectionLogs.collectAsState()
    var showTechConsole by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepCharcoal)
    ) {
        when (session.state) {
            CallState.OUTGOING_RINGING -> OutgoingRingingView(session = session, onCancel = { viewModel.cancelCall() })
            CallState.INCOMING_RINGING -> IncomingRingingView(
                session = session,
                onAnswer = { viewModel.answerCall() },
                onReject = { viewModel.rejectCall() }
            )
            CallState.CONNECTING -> ConnectingView(session = session, logs = connectionLogs)
            CallState.CONNECTED, CallState.RECONNECTING -> ConnectedActiveCallView(
                session = session,
                viewModel = viewModel,
                showTechConsole = showTechConsole,
                onToggleTechConsole = { showTechConsole = !showTechConsole }
            )
            else -> {}
        }
    }
}

@Composable
fun OutgoingRingingView(session: CallSession, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Elegant Pulsing animation for calling status
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val radius by infiniteTransition.animateFloat(
                initialValue = 40.dp.value,
                targetValue = 90.dp.value,
                animationSpec = infiniteRepeatable(
                    animation = tween(1800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "radius"
            )

            Box(
                modifier = Modifier.size(180.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = CyberCyan.copy(alpha = 0.15f),
                        radius = radius * density,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(CyberCyan.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (session.isVoiceOnly) Icons.Default.Call else Icons.Default.Videocam,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = session.contactName,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "WebRTC calling peer...",
                style = MaterialTheme.typography.bodyLarge,
                color = CyberCyan
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FloatingActionButton(
                onClick = onCancel,
                containerColor = SignalCoral,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "End call", modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("SECURED CONNECTION", color = TextSecondary, fontSize = 12.sp, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
fun IncomingRingingView(session: CallSession, onAnswer: () -> Unit, onReject: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(ElectricTeal.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = session.contactName.take(2).uppercase(),
                    color = ElectricTeal,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = session.contactName,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Incoming ${if (session.isVoiceOnly) "Audio" else "Video"} Session Request",
                style = MaterialTheme.typography.bodyLarge,
                color = ElectricTeal
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Reject Button
            FloatingActionButton(
                onClick = onReject,
                containerColor = SignalCoral,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "Decline call")
            }

            // Accept Button
            FloatingActionButton(
                onClick = onAnswer,
                containerColor = ElectricTeal,
                contentColor = Color.Black,
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = if (session.isVoiceOnly) Icons.Default.Call else Icons.Default.Videocam,
                    contentDescription = "Accept call"
                )
            }
        }
    }
}

@Composable
fun ConnectingView(session: CallSession, logs: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(top = 48.dp)
        ) {
            CircularProgressIndicator(color = CyberCyan)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Negotiating WebRTC Connection...",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                text = "STUN/TURN SDP Handshake",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        // Live signaling log frame
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(Color.Black, RoundedCornerShape(12.dp))
                .border(0.5.dp, GlassWhite, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(
                text = "LOGS (WebRTC Signaling Tunnel)",
                color = CyberCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true
            ) {
                items(logs.reversed()) { log ->
                    Text(
                        text = log,
                        color = Color.Green,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun ConnectedActiveCallView(
    session: CallSession,
    viewModel: CallLinkViewModel,
    showTechConsole: Boolean,
    onToggleTechConsole: () -> Unit
) {
    var callTextPrompt by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    // Request permissions for camera
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            viewModel.toggleCamera() // revert state if denied
        }
    }

    LaunchedEffect(session.isCameraEnabled, session.isVoiceOnly) {
        if (!session.isVoiceOnly && session.isCameraEnabled) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Main frame - Video feed or Voice wave visualization
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.3f)
                    .background(Color.Black)
            ) {
                if (session.isVoiceOnly) {
                    // Voice Call Waveform Visualizer
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Pulsing circular wave
                            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                            val radius by infiniteTransition.animateFloat(
                                initialValue = 50.dp.value,
                                targetValue = 100.dp.value,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1200, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "radius"
                            )

                            Box(modifier = Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawCircle(
                                        color = CyberCyan.copy(alpha = 0.2f),
                                        radius = radius * density
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(100.dp)
                                        .background(CyberCyan.copy(alpha = 0.1f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = null,
                                        tint = CyberCyan,
                                        modifier = Modifier.size(44.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Secure Voice Stream Active",
                                color = CyberCyan,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    // Video Call layout (Local Camera Preview + Remote Simulated Frame)
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Remote frame (Simulated active stream)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF131722)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Stream,
                                    contentDescription = null,
                                    tint = CyberCyan,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Remote Video Stream: 1080p @60fps",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "Codec: VP9 Profile 0",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        // Local Camera View (PIP)
                        if (session.isCameraEnabled) {
                            CameraPreview(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .size(width = 110.dp, height = 160.dp)
                                    .align(Alignment.TopEnd)
                                    .border(1.5.dp, CyberCyan, RoundedCornerShape(16.dp))
                            )
                        } else {
                            CameraFallback(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .size(width = 110.dp, height = 160.dp)
                                    .align(Alignment.TopEnd)
                                    .border(1.5.dp, GlassWhite, RoundedCornerShape(16.dp))
                            )
                        }
                    }
                }

                // Header status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .padding(top = 24.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = session.contactName, color = Color.White, fontWeight = FontWeight.ExtraBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "SECURE • ${String.format("%02d:%02d", session.durationSeconds / 60, session.durationSeconds % 60)}",
                                color = CyberCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (session.isRecording) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(SignalCoral.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                        .border(0.5.dp, SignalCoral.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(SignalCoral, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "REC",
                                        color = SignalCoral,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Tech log trigger
                    Button(
                        onClick = onToggleTechConsole,
                        colors = ButtonDefaults.buttonColors(containerColor = GlassWhite),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = "STUN/SDP LOGS", fontSize = 11.sp, color = CyberCyan)
                    }
                }
            }

            // Lower half: Transcript chat & call controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(DeepCharcoal)
                    .padding(16.dp)
            ) {
                // Fetch real-time member data from Firestore session
                val firestoreSession by viewModel.activeFirestoreSession.collectAsState()
                val participants = firestoreSession?.participants ?: listOf(session.contactPhone)

                Text(
                    text = "ACTIVE PARTICIPANTS (${participants.size})",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    participants.forEach { phone ->
                        val isMe = phone == viewModel.currentUserState.value?.phoneNumber
                        val displayName = if (isMe) "You (Me)" else (if (phone == session.contactPhone) session.contactName else phone.takeLast(9))
                        
                        var latency by remember(phone) { mutableStateOf((20..60).random()) }
                        var signalBars by remember(phone) { mutableStateOf((3..4).random()) }
                        
                        LaunchedEffect(phone) {
                            while (true) {
                                delay(3000)
                                latency = (latency + (-5..5).random()).coerceIn(15, 120)
                                if (latency > 90) {
                                    signalBars = (2..3).random()
                                } else {
                                    signalBars = (3..4).random()
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .background(CyberCyan.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                .border(1.dp, CyberCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(if (signalBars >= 3) ElectricTeal else SignalCoral, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = displayName,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            SignalStrengthBars(bars = signalBars)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${latency}ms",
                                color = if (signalBars >= 3) ElectricTeal.copy(alpha = 0.8f) else SignalCoral.copy(alpha = 0.8f),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Interactive Dialog Box for Gemini call simulator
                Text(
                    text = "LIVE SPEECH TRANSCRIPTS",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(SlateCard, RoundedCornerShape(12.dp))
                        .border(0.5.dp, GlassWhite, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    items(session.conversationTranscript) { entry ->
                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = "${entry.first}: ",
                                color = if (entry.first == "Me") CyberCyan else ElectricTeal,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = entry.second,
                                color = Color.White,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Shareable Link Generator component
                val currentRoomId by viewModel.currentRoomId.collectAsState()
                currentRoomId?.let { rId ->
                    ShareRoomLinkCard(roomId = rId)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Speech Input Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = callTextPrompt,
                        onValueChange = { callTextPrompt = it },
                        placeholder = { Text("Speak / Send response...", color = TextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = GlassWhite
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (callTextPrompt.isNotBlank()) {
                                viewModel.sendSpeechOrText(callTextPrompt)
                                callTextPrompt = ""
                            }
                        }),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("speech_input")
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (callTextPrompt.isNotBlank()) {
                                viewModel.sendSpeechOrText(callTextPrompt)
                                callTextPrompt = ""
                            }
                        },
                        modifier = Modifier
                            .background(CyberCyan, CircleShape)
                            .size(48.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.Black)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                CallControlToolbar(session = session, viewModel = viewModel)
            }
        }

        // TECH CONSOLE - COLLAPSIBLE SLIDE-UP SHEET
        if (showTechConsole) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(onClick = onToggleTechConsole)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(SlateCard, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .border(1.dp, GlassWhite, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .clickable(enabled = false) {}
                        .padding(24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "WebRTC Diagnostics Console",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyberCyan,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onToggleTechConsole) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .background(Color.Black, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        item {
                            Text("== SDP OFFER ==", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text(session.sdpOffer.ifEmpty { "Pending generating..." }, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("== SDP ANSWER ==", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text(session.sdpAnswer.ifEmpty { "Pending answer..." }, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("== LOCAL ICE CANDIDATES ==", color = ElectricTeal, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            session.localIceCandidates.forEach { cand ->
                                Text(cand, color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("== REMOTE ICE CANDIDATES ==", color = ElectricTeal, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            session.remoteIceCandidates.forEach { cand ->
                                Text(cand, color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

// 9. RESPONSIVE CALL CONTROL TOOLBAR
@Composable
fun CallControlToolbar(
    session: CallSession,
    viewModel: CallLinkViewModel,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SlateCard.copy(alpha = 0.95f), RoundedCornerShape(20.dp))
            .border(1.dp, GlassWhite.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
            .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mute Microphone
        IconButton(
            onClick = { viewModel.toggleMute() },
            modifier = Modifier
                .background(if (session.isMuted) SignalCoral else GlassWhite.copy(alpha = 0.15f), CircleShape)
                .size(48.dp)
        ) {
            Icon(
                imageVector = if (session.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = "Mute",
                tint = Color.White
            )
        }

        // Toggle Video / Camera (Only for Video calls)
        if (!session.isVoiceOnly) {
            IconButton(
                onClick = { viewModel.toggleCamera() },
                modifier = Modifier
                    .background(if (!session.isCameraEnabled) SignalCoral else GlassWhite.copy(alpha = 0.15f), CircleShape)
                    .size(48.dp)
            ) {
                Icon(
                    imageVector = if (session.isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    contentDescription = "Camera",
                    tint = Color.White
                )
            }

            if (session.isCameraEnabled) {
                IconButton(
                    onClick = { viewModel.switchCamera() },
                    modifier = Modifier
                        .background(GlassWhite.copy(alpha = 0.15f), CircleShape)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FlipCameraAndroid,
                        contentDescription = "Switch Camera",
                        tint = Color.White
                    )
                }
            }
        }

        // Speakerphone Toggle
        IconButton(
            onClick = { viewModel.toggleSpeaker() },
            modifier = Modifier
                .background(if (session.isSpeakerOn) ElectricTeal else GlassWhite.copy(alpha = 0.15f), CircleShape)
                .size(48.dp)
        ) {
            Icon(
                imageVector = if (session.isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                contentDescription = "Speaker",
                tint = Color.White
            )
        }

        // Simulate Reconnection / Network Drop
        IconButton(
            onClick = { viewModel.triggerReconnect() },
            modifier = Modifier
                .background(GlassWhite.copy(alpha = 0.15f), CircleShape)
                .size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.NetworkCheck,
                contentDescription = "Test Reconnection",
                tint = CyberCyan
            )
        }

        // Record Call Toggle
        IconButton(
            onClick = { viewModel.toggleRecording() },
            modifier = Modifier
                .background(if (session.isRecording) SignalCoral.copy(alpha = 0.8f) else GlassWhite.copy(alpha = 0.15f), CircleShape)
                .size(48.dp)
                .testTag("record_call_toggle")
        ) {
            Icon(
                imageVector = if (session.isRecording) Icons.Default.Stop else Icons.Default.Circle,
                contentDescription = "Record Call",
                tint = if (session.isRecording) Color.White else SignalCoral
            )
        }

        // End call
        FloatingActionButton(
            onClick = { viewModel.endCall() },
            containerColor = SignalCoral,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier.size(52.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CallEnd,
                contentDescription = "End Call"
            )
        }
    }
}

// 10. SHAREABLE ROOM LINK CARD
@Composable
fun ShareRoomLinkCard(
    roomId: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val liveUrl = "https://ais-pre-jg7yzjuh7pfnlwwgjudiio-619586630283.europe-west2.run.app"
    val shareableLink = "$liveUrl/join?room=$roomId"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlateCard, RoundedCornerShape(16.dp))
            .border(1.dp, CyberCyan.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share",
                tint = CyberCyan,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Share Call Invitation Link",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Invite friends or team members to join this secure room in real-time.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = shareableLink,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = CyberCyan,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )

            Row {
                // Copy Button
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Shareable D'val CallLink Room", shareableLink)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Link copied to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy Link",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Native Share Button
                IconButton(
                    onClick = {
                        val sendIntent: Intent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, "Join my secure video room on D'val CallLink: $shareableLink")
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, "Share Call Link")
                        context.startActivity(sendIntent)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share Link",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
