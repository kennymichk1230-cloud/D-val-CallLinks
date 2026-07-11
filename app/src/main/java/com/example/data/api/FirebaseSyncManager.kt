package com.example.data.api

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.example.data.entity.CallRecord
import com.example.data.entity.Contact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FirebaseSyncManager(private val context: Context) {

    private val _isFirebaseAvailable = MutableStateFlow(false)
    val isFirebaseAvailable: StateFlow<Boolean> = _isFirebaseAvailable.asStateFlow()

    private val _syncStatus = MutableStateFlow("SQLite Offline Mode")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    // Auth flows
    private val _currentUserState = MutableStateFlow<FirebaseUserSession?>(null)
    val currentUserState: StateFlow<FirebaseUserSession?> = _currentUserState.asStateFlow()

    var firestore: FirebaseFirestore? = null
    private var auth: FirebaseAuth? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var contactsListener: ListenerRegistration? = null
    var activeSessionsListener: ListenerRegistration? = null

    // Fallback directory for when Firebase isn't configured in Google services
    private val fallbackLocalDirectory = mutableMapOf<String, FirebaseUserSession>()
    private val fallbackActiveSessions = mutableMapOf<String, FirestoreCallSession>()

    init {
        try {
            var initialized = false
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                initialized = true
            } else {
                val apiKey = com.example.BuildConfig.FIREBASE_API_KEY
                val appId = com.example.BuildConfig.FIREBASE_APP_ID
                val projectId = com.example.BuildConfig.FIREBASE_PROJECT_ID
                
                if (apiKey.isNotEmpty() && !apiKey.contains("YOUR_") &&
                    appId.isNotEmpty() && !appId.contains("YOUR_") &&
                    projectId.isNotEmpty() && !projectId.contains("YOUR_")) {
                    
                    val options = com.google.firebase.FirebaseOptions.Builder()
                        .setApiKey(apiKey)
                        .setApplicationId(appId)
                        .setProjectId(projectId)
                        .build()
                    FirebaseApp.initializeApp(context, options)
                    initialized = true
                    Log.d("FirebaseSync", "Firebase initialized programmatically using options from .env file!")
                } else {
                    // Try default initialization if google-services.json happens to be present
                    try {
                        if (FirebaseApp.initializeApp(context) != null) {
                            initialized = true
                            Log.d("FirebaseSync", "Firebase initialized using google-services.json!")
                        }
                    } catch (e: Exception) {
                        Log.d("FirebaseSync", "Default initialization failed, no google-services.json found: ${e.message}")
                    }
                }
            }

            if (initialized) {
                firestore = FirebaseFirestore.getInstance().apply {
                    val settings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                        .setPersistenceEnabled(true)
                        .build()
                    firestoreSettings = settings
                }
                auth = FirebaseAuth.getInstance()
                _isFirebaseAvailable.value = true
                _syncStatus.value = "Cloud Sync Active (Firestore Online)"
                Log.d("FirebaseSync", "Firebase Auth & Firestore loaded successfully!")
                
                // Track auth state
                auth?.addAuthStateListener { firebaseAuth ->
                    val user = firebaseAuth.currentUser
                    if (user != null) {
                        scope.launch {
                            try {
                                // Try to fetch their profile details from Firestore
                                val phoneDoc = firestore?.collection("users_by_uid")?.document(user.uid)?.get()?.await()
                                if (phoneDoc != null && phoneDoc.exists()) {
                                    val phone = phoneDoc.getString("phone") ?: ""
                                    val name = phoneDoc.getString("name") ?: (user.displayName ?: "User")
                                    _currentUserState.value = FirebaseUserSession(
                                        uid = user.uid,
                                        email = user.email ?: "",
                                        name = name,
                                        phoneNumber = phone,
                                        status = "Online"
                                    )
                                    updateOnlineStatus(phone, "Online")
                                } else {
                                    // If not in firestore yet, set minimal session
                                    _currentUserState.value = FirebaseUserSession(
                                        uid = user.uid,
                                        email = user.email ?: "",
                                        name = user.displayName ?: "User",
                                        phoneNumber = "+1 (609) 222-0000",
                                        status = "Online"
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e("FirebaseSync", "Auth observer error: ${e.message}")
                            }
                        }
                    } else {
                        _currentUserState.value = null
                    }
                }
            } else {
                _isFirebaseAvailable.value = false
                _syncStatus.value = "Local SQLite Mode (Room Active)"
                Log.w("FirebaseSync", "Firebase credentials not configured. Falling back safely to local SQLite mode.")
            }
        } catch (e: Exception) {
            _isFirebaseAvailable.value = false
            _syncStatus.value = "Local SQLite Mode (Room Active)"
            Log.e("FirebaseSync", "Error during Firebase initialization: ${e.message}", e)
        }
    }

    // Helper to generate a realistic phone number in the requested format: +1 (609) 222-XXXX
    fun generateUserPhoneNumber(): String {
        val lastFour = (1000..9999).random()
        return "+1 (609) 222-$lastFour"
    }

    // Sign Up with Email & Password
    fun signUpWithEmail(email: String, password: String, name: String, onResult: (Boolean, String?) -> Unit) {
        val mAuth = auth
        val mFirestore = firestore

        if (mAuth != null && mFirestore != null && _isFirebaseAvailable.value) {
            scope.launch {
                try {
                    val authResult = mAuth.createUserWithEmailAndPassword(email, password).await()
                    val user = authResult.user
                    if (user != null) {
                        val generatedPhone = generateUserPhoneNumber()
                        
                        // Store detailed profile indexed by generated phone
                        val profileData = mapOf(
                            "uid" to user.uid,
                            "email" to email,
                            "name" to name,
                            "phone" to generatedPhone,
                            "status" to "Online",
                            "lastSeen" to System.currentTimeMillis()
                        )
                        
                        // Map UID to phone for rapid user details lookup
                        val uidMapping = mapOf(
                            "phone" to generatedPhone,
                            "name" to name,
                            "email" to email
                        )

                        mFirestore.collection("users").document(generatedPhone).set(profileData).await()
                        mFirestore.collection("users_by_uid").document(user.uid).set(uidMapping).await()

                        val session = FirebaseUserSession(
                            uid = user.uid,
                            email = email,
                            name = name,
                            phoneNumber = generatedPhone,
                            status = "Online"
                        )
                        _currentUserState.value = session
                        _syncStatus.value = "Registered: $generatedPhone"
                        onResult(true, null)
                    } else {
                        onResult(false, "User creation failed")
                    }
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Sign-up error: ${e.message}")
                    onResult(false, e.localizedMessage ?: "Unknown Firebase error")
                }
            }
        } else {
            // Simulated local memory fallback
            val mockUid = UUID.randomUUID().toString()
            val generatedPhone = generateUserPhoneNumber()
            val session = FirebaseUserSession(
                uid = mockUid,
                email = email,
                name = name,
                phoneNumber = generatedPhone,
                status = "Online"
            )
            fallbackLocalDirectory[generatedPhone] = session
            _currentUserState.value = session
            _syncStatus.value = "Local Account Created: $generatedPhone"
            onResult(true, null)
        }
    }

    // Login with Email & Password
    fun loginWithEmail(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        val mAuth = auth
        val mFirestore = firestore

        if (mAuth != null && mFirestore != null && _isFirebaseAvailable.value) {
            scope.launch {
                try {
                    val authResult = mAuth.signInWithEmailAndPassword(email, password).await()
                    val user = authResult.user
                    if (user != null) {
                        val phoneDoc = mFirestore.collection("users_by_uid").document(user.uid).get().await()
                        val phone = phoneDoc.getString("phone") ?: generateUserPhoneNumber()
                        val name = phoneDoc.getString("name") ?: (user.displayName ?: "User")
                        
                        val session = FirebaseUserSession(
                            uid = user.uid,
                            email = email,
                            name = name,
                            phoneNumber = phone,
                            status = "Online"
                        )
                        _currentUserState.value = session
                        updateOnlineStatus(phone, "Online")
                        _syncStatus.value = "Logged In: $phone"
                        onResult(true, null)
                    } else {
                        onResult(false, "Auth user empty")
                    }
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Login error: ${e.message}")
                    onResult(false, e.localizedMessage ?: "Unknown Firebase error")
                }
            }
        } else {
            // Local fallback login
            val found = fallbackLocalDirectory.values.find { it.email.equals(email, ignoreCase = true) }
            if (found != null) {
                _currentUserState.value = found
                _syncStatus.value = "Local Session: ${found.phoneNumber}"
                onResult(true, null)
            } else {
                // Auto create for ease of testing in fallback
                val generatedPhone = generateUserPhoneNumber()
                val session = FirebaseUserSession(
                    uid = UUID.randomUUID().toString(),
                    email = email,
                    name = email.substringBefore("@"),
                    phoneNumber = generatedPhone,
                    status = "Online"
                )
                fallbackLocalDirectory[generatedPhone] = session
                _currentUserState.value = session
                _syncStatus.value = "Local Session Auto-Created: $generatedPhone"
                onResult(true, null)
            }
        }
    }

    // Sign Out
    fun signOut() {
        val phone = _currentUserState.value?.phoneNumber
        if (phone != null) {
            updateOnlineStatus(phone, "Offline")
        }
        auth?.signOut()
        _currentUserState.value = null
        _syncStatus.value = if (_isFirebaseAvailable.value) "Cloud Sync Active (Firestore Online)" else "Local SQLite Mode (Room Active)"
    }

    // Search contact by phone number globally
    fun searchContactByPhoneNumber(phone: String, onResult: (Contact?) -> Unit) {
        val mFirestore = firestore
        val cleanedPhone = phone.trim()
        
        if (mFirestore != null && _isFirebaseAvailable.value) {
            scope.launch {
                try {
                    val doc = mFirestore.collection("users").document(cleanedPhone).get().await()
                    if (doc.exists()) {
                        val name = doc.getString("name") ?: "Unknown"
                        val status = doc.getString("status") ?: "Offline"
                        onResult(Contact(phone = cleanedPhone, name = name, status = status))
                    } else {
                        onResult(null)
                    }
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Search error: ${e.message}")
                    onResult(null)
                }
            }
        } else {
            // Local fallback search
            val found = fallbackLocalDirectory[cleanedPhone]
            if (found != null) {
                onResult(Contact(phone = cleanedPhone, name = found.name, status = found.status))
            } else {
                onResult(null)
            }
        }
    }

    // 1. Presence API - Update peer status in real-time
    fun updateOnlineStatus(phone: String, status: String) {
        // Update local session status
        val current = _currentUserState.value
        if (current != null && current.phoneNumber == phone) {
            _currentUserState.value = current.copy(status = status)
        }

        val db = firestore
        if (db != null && _isFirebaseAvailable.value) {
            scope.launch {
                try {
                    db.collection("users").document(phone).update("status", status).await()
                    Log.d("FirebaseSync", "Presence synced for $phone: $status")
                } catch (e: Exception) {
                    // Try setting in case document doesn't exist
                    try {
                        val data = mapOf(
                            "status" to status,
                            "lastSeen" to System.currentTimeMillis()
                        )
                        db.collection("users").document(phone).set(data).await()
                    } catch (ex: Exception) {
                        Log.e("FirebaseSync", "Failed to sync presence: ${ex.message}")
                    }
                }
            }
        } else {
            val local = fallbackLocalDirectory[phone]
            if (local != null) {
                fallbackLocalDirectory[phone] = local.copy(status = status)
            }
        }
    }

// 2. Devices API - Register active sessions
    fun registerDevice(phone: String, deviceModel: String, token: String? = null) {
        val db = firestore
        if (db != null && _isFirebaseAvailable.value) {
            scope.launch {
                try {
                    val fcmToken = token ?: try {
                        com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
                    } catch (e: Exception) {
                        Log.e("FirebaseSync", "Failed to retrieve real FCM token: ${e.message}")
                        "simulated_token_fcm_" + phone.hashCode()
                    }
                    val data = mapOf(
                        "deviceModel" to deviceModel,
                        "pushToken" to fcmToken,
                        "timestamp" to System.currentTimeMillis()
                    )
                    db.collection("users").document(phone).collection("devices").document(fcmToken).set(data).await()
                    Log.d("FirebaseSync", "Device registered in Firestore for user $phone with token: $fcmToken")
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Device registration failed: ${e.message}")
                }
            }
        }
    }

    // 3. Call History API - Log call records to cloud
    fun logCallRecordCloud(record: CallRecord) {
        val db = firestore
        if (db != null && _isFirebaseAvailable.value) {
            scope.launch {
                try {
                    val currentPhone = _currentUserState.value?.phoneNumber ?: ""
                    val data = mapOf(
                        "userPhone" to currentPhone,
                        "contactName" to record.contactName,
                        "contactPhone" to record.contactPhone,
                        "isGroup" to record.isGroup,
                        "isVoice" to record.isVoice,
                        "callType" to record.callType,
                        "timestamp" to record.timestamp,
                        "durationSeconds" to record.durationSeconds
                    )
                    db.collection("calls").add(data).await()
                    Log.d("FirebaseSync", "Call logged to Firestore successfully!")
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Cloud logging failed: ${e.message}")
                }
            }
        }
    }

    // 4. Contacts API - Listen to real-time status updates from remote Firestore users collection
    fun startRealtimeStatusListener(onStatusUpdate: (phone: String, status: String) -> Unit) {
        val db = firestore
        if (db != null && _isFirebaseAvailable.value) {
            try {
                contactsListener = db.collection("users").addSnapshotListener { snapshots, e ->
                    if (e != null) {
                        Log.w("FirebaseSync", "Listen failed.", e)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        for (doc in snapshots.documents) {
                            val phone = doc.id
                            val status = doc.getString("status") ?: "Offline"
                            onStatusUpdate(phone, status)
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e("FirebaseSync", "Error starting realtime snapshot listener: ${ex.message}")
            }
        }
    }

    fun stopRealtimeStatusListener() {
        contactsListener?.remove()
    }

    // 5. Firestore ACTIVE CALL SESSION MANAGEMENT API
    fun storeActiveCallSession(
        roomId: String,
        callerPhone: String,
        callerName: String,
        calleePhone: String,
        calleeName: String,
        isVoiceOnly: Boolean,
        onSessionUpdate: (FirestoreCallSession) -> Unit
    ) {
        val db = firestore
        val newSession = FirestoreCallSession(
            roomId = roomId,
            callerPhone = callerPhone,
            callerName = callerName,
            calleePhone = calleePhone,
            calleeName = calleeName,
            participants = listOf(callerPhone),
            status = "ringing",
            isVoiceOnly = isVoiceOnly,
            timestamp = System.currentTimeMillis()
        )

        if (db != null && _isFirebaseAvailable.value) {
            scope.launch {
                try {
                    db.collection("call_sessions").document(roomId).set(newSession).await()
                    Log.d("FirebaseSync", "Call session $roomId created in Firestore.")

                    // Retrieve Callee FCM Token to trigger FCM push notification
                    try {
                        val devicesSnapshot = db.collection("users").document(calleePhone).collection("devices").get().await()
                        for (doc in devicesSnapshot.documents) {
                            val pushToken = doc.getString("pushToken")
                            if (!pushToken.isNullOrEmpty()) {
                                val pushRequest = mapOf(
                                    "token" to pushToken,
                                    "title" to "Incoming ${if (isVoiceOnly) "Voice" else "Video"} Call",
                                    "body" to "$callerName is calling you...",
                                    "data" to mapOf(
                                        "roomId" to roomId,
                                        "callerName" to callerName,
                                        "isVoiceOnly" to isVoiceOnly.toString()
                                    ),
                                    "timestamp" to System.currentTimeMillis()
                                )
                                db.collection("push_requests").add(pushRequest).await()
                                Log.d("FirebaseSync", "Enqueued push request for callee $calleePhone (Token: $pushToken)")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FirebaseSync", "Failed to enqueue FCM push notification request: ${e.message}")
                    }
                    
                    // Listen to this specific call session in real-time
                    activeSessionsListener?.remove()
                    activeSessionsListener = db.collection("call_sessions").document(roomId)
                        .addSnapshotListener { snapshot, e ->
                            if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                            val session = snapshot.toObject(FirestoreCallSession::class.java)
                            if (session != null) {
                                onSessionUpdate(session)
                            }
                        }
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Failed to write call session: ${e.message}")
                }
            }
        } else {
            // Local fallback call session simulation
            fallbackActiveSessions[roomId] = newSession
            onSessionUpdate(newSession)
        }
    }

    fun joinActiveCallSession(roomId: String, participantPhone: String, onSessionUpdate: (FirestoreCallSession) -> Unit) {
        val db = firestore
        if (db != null && _isFirebaseAvailable.value) {
            scope.launch {
                try {
                    val docRef = db.collection("call_sessions").document(roomId)
                    db.runTransaction { transaction ->
                        val snapshot = transaction.get(docRef)
                        if (snapshot.exists()) {
                            val currentParticipants = snapshot.get("participants") as? List<String> ?: emptyList()
                            if (!currentParticipants.contains(participantPhone)) {
                                val updatedParticipants = currentParticipants + participantPhone
                                transaction.update(docRef, "participants", updatedParticipants)
                                transaction.update(docRef, "status", "connected")
                            }
                        }
                    }.await()

                    // Start listening for real-time changes
                    activeSessionsListener?.remove()
                    activeSessionsListener = docRef.addSnapshotListener { snapshot, e ->
                        if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                        val session = snapshot.toObject(FirestoreCallSession::class.java)
                        if (session != null) {
                            onSessionUpdate(session)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Failed to join active session: ${e.message}")
                }
            }
        } else {
            // Local fallback simulation
            val existing = fallbackActiveSessions[roomId]
            if (existing != null) {
                val updated = existing.copy(
                    participants = (existing.participants + participantPhone).distinct(),
                    status = "connected"
                )
                fallbackActiveSessions[roomId] = updated
                onSessionUpdate(updated)
            }
        }
    }

    fun updateActiveCallSessionStatus(roomId: String, status: String) {
        val db = firestore
        if (db != null && _isFirebaseAvailable.value) {
            scope.launch {
                try {
                    db.collection("call_sessions").document(roomId).update("status", status).await()
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Failed to update call session: ${e.message}")
                }
            }
        } else {
            val existing = fallbackActiveSessions[roomId]
            if (existing != null) {
                fallbackActiveSessions[roomId] = existing.copy(status = status)
            }
        }
    }

    fun removeActiveCallSession(roomId: String) {
        activeSessionsListener?.remove()
        activeSessionsListener = null
        
        val db = firestore
        if (db != null && _isFirebaseAvailable.value) {
            scope.launch {
                try {
                    db.collection("call_sessions").document(roomId).delete().await()
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Failed to delete call session: ${e.message}")
                }
            }
        } else {
            fallbackActiveSessions.remove(roomId)
        }
    }

    // Password privacy reset request via Firebase Auth
    fun sendPasswordResetEmail(email: String, onResult: (Boolean, String?) -> Unit) {
        val mAuth = auth
        if (mAuth != null && _isFirebaseAvailable.value) {
            scope.launch {
                try {
                    mAuth.sendPasswordResetEmail(email).await()
                    onResult(true, null)
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Password reset request failed: ${e.message}")
                    onResult(false, e.localizedMessage ?: "Failed to send reset email")
                }
            }
        } else {
            // Simulated local memory fallback
            onResult(true, null)
        }
    }

    // Real-time Incoming Call Listener
    private var incomingCallListener: ListenerRegistration? = null

    fun startIncomingCallListener(myPhone: String, onIncomingCall: (FirestoreCallSession) -> Unit) {
        val db = firestore
        if (db != null && _isFirebaseAvailable.value && myPhone.isNotEmpty()) {
            try {
                incomingCallListener?.remove()
                incomingCallListener = db.collection("call_sessions")
                    .whereEqualTo("calleePhone", myPhone)
                    .whereEqualTo("status", "ringing")
                    .addSnapshotListener { snapshots, e ->
                        if (e != null || snapshots == null) return@addSnapshotListener
                        for (change in snapshots.documentChanges) {
                            if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                                val session = change.document.toObject(FirestoreCallSession::class.java)
                                if (session != null) {
                                    onIncomingCall(session)
                                }
                            }
                        }
                    }
                Log.d("FirebaseSync", "Started real-time incoming call listener for $myPhone")
            } catch (ex: Exception) {
                Log.e("FirebaseSync", "Error starting incoming call listener: ${ex.message}")
            }
        }
    }

    fun stopIncomingCallListener() {
        incomingCallListener?.remove()
        incomingCallListener = null
    }

    // Global User Search: exact matching + name prefixes
    fun searchUsersCloud(query: String, onResult: (List<Contact>) -> Unit) {
        val db = firestore
        val cleanedQuery = query.trim()
        if (db != null && _isFirebaseAvailable.value && cleanedQuery.isNotEmpty()) {
            scope.launch {
                try {
                    val results = mutableListOf<Contact>()
                    
                    // 1. Search by exact phone
                    val doc = db.collection("users").document(cleanedQuery).get().await()
                    if (doc.exists()) {
                        val name = doc.getString("name") ?: "Unknown"
                        val status = doc.getString("status") ?: "Offline"
                        results.add(Contact(phone = cleanedQuery, name = name, status = status))
                    }
                    
                    // 2. Also search by name prefix
                    val nameQuery = db.collection("users")
                        .orderBy("name")
                        .startAt(cleanedQuery)
                        .endAt(cleanedQuery + "\uf8ff")
                        .limit(10)
                        .get()
                        .await()
                        
                    for (d in nameQuery.documents) {
                        val phone = d.id
                        if (phone != cleanedQuery) {
                            val name = d.getString("name") ?: "Unknown"
                            val status = d.getString("status") ?: "Offline"
                            results.add(Contact(phone = phone, name = name, status = status))
                        }
                    }
                    onResult(results.distinctBy { it.phone })
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Query search failed: ${e.message}")
                    onResult(emptyList())
                }
            }
        } else {
            // Local fallback prefix match
            val matched = fallbackLocalDirectory.filter { 
                it.key.contains(cleanedQuery) || it.value.name.contains(cleanedQuery, ignoreCase = true)
            }.map { 
                Contact(phone = it.key, name = it.value.name, status = it.value.status)
            }
            onResult(matched)
        }
    }

    // Cloud Call History Fetcher
    fun fetchCallHistoryCloud(myPhone: String, onResult: (List<CallRecord>) -> Unit) {
        val db = firestore
        if (db != null && _isFirebaseAvailable.value && myPhone.isNotEmpty()) {
            scope.launch {
                try {
                    val list = mutableListOf<CallRecord>()
                    
                    // Outgoing
                    val q1 = db.collection("calls")
                        .whereEqualTo("userPhone", myPhone)
                        .get()
                        .await()
                    
                    for (doc in q1.documents) {
                        val duration = doc.getLong("durationSeconds") ?: 0L
                        list.add(
                            CallRecord(
                                contactName = doc.getString("contactName") ?: "Unknown",
                                contactPhone = doc.getString("contactPhone") ?: "",
                                isGroup = doc.getBoolean("isGroup") ?: false,
                                isVoice = doc.getBoolean("isVoice") ?: true,
                                callType = doc.getString("callType") ?: "Outgoing",
                                timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                                durationSeconds = duration
                            )
                        )
                    }

                    // Incoming
                    val q2 = db.collection("calls")
                        .whereEqualTo("contactPhone", myPhone)
                        .get()
                        .await()
                        
                    for (doc in q2.documents) {
                        val duration = doc.getLong("durationSeconds") ?: 0L
                        val userPhone = doc.getString("userPhone") ?: ""
                        val callerName = doc.getString("contactName") ?: "Unknown"
                        val timestamp = doc.getLong("timestamp") ?: 0L
                        
                        if (list.none { it.timestamp == timestamp }) {
                            list.add(
                                CallRecord(
                                    contactName = callerName,
                                    contactPhone = userPhone.ifEmpty { myPhone },
                                    isGroup = doc.getBoolean("isGroup") ?: false,
                                    isVoice = doc.getBoolean("isVoice") ?: true,
                                    callType = "Incoming",
                                    timestamp = timestamp,
                                    durationSeconds = duration
                                )
                            )
                        }
                    }

                    list.sortByDescending { it.timestamp }
                    onResult(list)
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Failed to fetch cloud call records: ${e.message}")
                    onResult(emptyList())
                }
            }
        } else {
            onResult(emptyList())
        }
    }

    // Contact Group sync
    fun saveContactGroupToCloud(contactPhone: String, group: String) {
        val db = firestore
        val currentPhone = _currentUserState.value?.phoneNumber ?: ""
        if (db != null && _isFirebaseAvailable.value && currentPhone.isNotEmpty()) {
            scope.launch {
                try {
                    val data = mapOf(
                        "phone" to contactPhone,
                        "group" to group,
                        "timestamp" to System.currentTimeMillis()
                    )
                    db.collection("users").document(currentPhone)
                        .collection("contacts").document(contactPhone).set(data).await()
                    Log.d("FirebaseSync", "Contact group $group for $contactPhone saved to Firestore.")
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Failed to save contact group to cloud: ${e.message}")
                }
            }
        }
    }

    fun fetchContactGroupsCloud(currentPhone: String, onResult: (Map<String, String>) -> Unit) {
        val db = firestore
        if (db != null && _isFirebaseAvailable.value && currentPhone.isNotEmpty()) {
            scope.launch {
                try {
                    val snapshot = db.collection("users").document(currentPhone)
                        .collection("contacts").get().await()
                    val result = mutableMapOf<String, String>()
                    for (doc in snapshot.documents) {
                        val phone = doc.id
                        val group = doc.getString("group") ?: "None"
                        result[phone] = group
                    }
                    onResult(result)
                } catch (e: Exception) {
                    Log.e("FirebaseSync", "Failed to fetch contact groups: ${e.message}")
                    onResult(emptyMap())
                }
            }
        } else {
            onResult(emptyMap())
        }
    }
}

// Data Classes for Sync Operations
data class FirebaseUserSession(
    val uid: String = "",
    val email: String = "",
    val name: String = "",
    val phoneNumber: String = "",
    val status: String = "Offline"
)

data class FirestoreCallSession(
    val roomId: String = "",
    val callerPhone: String = "",
    val callerName: String = "",
    val calleePhone: String = "",
    val calleeName: String = "",
    val participants: List<String> = emptyList(),
    val status: String = "", // "ringing", "connected", "ended"
    val isVoiceOnly: Boolean = false,
    val timestamp: Long = 0L
)
