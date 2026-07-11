package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.CallLinkDatabase
import com.example.data.entity.CallRecord
import com.example.data.entity.Contact
import com.example.data.entity.IceServer
import com.example.data.repository.CallLinkRepository
import com.example.data.api.FirebaseSyncManager
import com.example.data.api.FirebaseUserSession
import com.example.data.api.FirestoreCallSession
import com.example.webrtc.CallSession
import com.example.webrtc.CallState
import com.example.webrtc.WebRtcCallManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.absoluteValue

class CallLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CallLinkRepository
    private val callManager: WebRtcCallManager
    val firebaseSyncManager = FirebaseSyncManager(application)

    val allContacts: StateFlow<List<Contact>>
    val favoriteContacts: StateFlow<List<Contact>>
    val allCallRecords: StateFlow<List<CallRecord>>
    val allIceServers: StateFlow<List<IceServer>>

    val currentSession: StateFlow<CallSession?>
    val connectionLogs: StateFlow<List<String>>
    val isFirebaseAvailable: StateFlow<Boolean> = firebaseSyncManager.isFirebaseAvailable
    val syncStatus: StateFlow<String> = firebaseSyncManager.syncStatus
    val currentUserState: StateFlow<FirebaseUserSession?> = firebaseSyncManager.currentUserState

    private val _currentRoomId = MutableStateFlow<String?>(null)
    val currentRoomId: StateFlow<String?> = _currentRoomId.asStateFlow()

    private val _activeFirestoreSession = MutableStateFlow<FirestoreCallSession?>(null)
    val activeFirestoreSession: StateFlow<FirestoreCallSession?> = _activeFirestoreSession.asStateFlow()

    private val _activeSessions = MutableStateFlow<List<FirestoreCallSession>>(emptyList())
    val activeSessions: StateFlow<List<FirestoreCallSession>> = _activeSessions.asStateFlow()
    private var allActiveSessionsListener: com.google.firebase.firestore.ListenerRegistration? = null

    fun startListeningToAllActiveSessions() {
        val db = firebaseSyncManager.firestore
        if (db != null && firebaseSyncManager.isFirebaseAvailable.value) {
            allActiveSessionsListener?.remove()
            allActiveSessionsListener = db.collection("call_sessions")
                .whereIn("status", listOf("ringing", "connecting", "connected"))
                .addSnapshotListener { snapshot, e ->
                    if (e != null || snapshot == null) {
                        Log.e("CallLinkViewModel", "Error listening to all active sessions: ${e?.message}")
                        return@addSnapshotListener
                    }
                    val sessions = snapshot.toObjects(FirestoreCallSession::class.java)
                    _activeSessions.value = sessions
                }
        } else {
            _activeSessions.value = firebaseSyncManager.fetchLocalActiveCallSessionsMock()
        }
    }

    fun stopListeningToAllActiveSessions() {
        allActiveSessionsListener?.remove()
        allActiveSessionsListener = null
    }

    private var currentUserPhone: String = ""

    init {
        val database = CallLinkDatabase.getDatabase(application)
        repository = CallLinkRepository(
            contactDao = database.contactDao(),
            callRecordDao = database.callRecordDao(),
            iceServerDao = database.iceServerDao()
        )
        callManager = WebRtcCallManager(repository)
        callManager.onCallRecordCreated = { record ->
            viewModelScope.launch(Dispatchers.IO) {
                firebaseSyncManager.logCallRecordCloud(record)
            }
        }

        allContacts = repository.allContacts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        favoriteContacts = repository.favoriteContacts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        allCallRecords = repository.allCallRecords.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        allIceServers = repository.allIceServers.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        currentSession = callManager.currentSession
        connectionLogs = callManager.connectionLogs

        // Listen for live database peer presence updates and apply them in real-time to the UI
        firebaseSyncManager.startRealtimeStatusListener { phone, status ->
            viewModelScope.launch(Dispatchers.IO) {
                val existing = repository.getContactByPhone(phone)
                if (existing != null) {
                    repository.updateContactStatus(phone, status)
                }
            }
        }

        viewModelScope.launch {
            currentUserState.collect { user ->
                if (user != null) {
                    currentUserPhone = user.phoneNumber
                    firebaseSyncManager.startIncomingCallListener(user.phoneNumber) { incomingSession ->
                        viewModelScope.launch(Dispatchers.Main) {
                            // Block functionality check
                            val existingContact = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                repository.getContactByPhone(incomingSession.callerPhone)
                            }
                            if (existingContact?.isBlocked == true) {
                                Log.d("CallLinkViewModel", "Blocked incoming call from ${incomingSession.callerPhone}")
                                firebaseSyncManager.updateActiveCallSessionStatus(incomingSession.roomId, "ended")
                                firebaseSyncManager.removeActiveCallSession(incomingSession.roomId)
                                return@launch
                            }

                            // Incoming call notification sound effect
                            try {
                                val notificationUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                                val r = android.media.RingtoneManager.getRingtone(application, notificationUri)
                                r.play()
                            } catch (e: Exception) {
                                Log.e("CallLinkViewModel", "Error playing notification sound: ${e.message}")
                            }

                            if (callManager.currentSession.value == null) {
                                _currentRoomId.value = incomingSession.roomId
                                _activeFirestoreSession.value = incomingSession
                                val callerContact = Contact(
                                    phone = incomingSession.callerPhone,
                                    name = incomingSession.callerName,
                                    status = "Ringing"
                                )
                                callManager.receiveIncomingCall(callerContact, incomingSession.isVoiceOnly, false)
                                startListeningToActiveSession(incomingSession.roomId)
                            }
                        }
                    }
                    startListeningToAllActiveSessions()
                    syncCallHistoryFromCloud()
                } else {
                    firebaseSyncManager.stopIncomingCallListener()
                    stopListeningToAllActiveSessions()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        firebaseSyncManager.stopRealtimeStatusListener()
        firebaseSyncManager.stopIncomingCallListener()
        stopListeningToAllActiveSessions()
    }

    // Authentication Services
    fun signInWithGoogle(idToken: String, onResult: (Boolean, String?) -> Unit) {
        firebaseSyncManager.signInWithGoogleToken(idToken) { success, error ->
            if (success) {
                val generatedPhone = firebaseSyncManager.currentUserState.value?.phoneNumber ?: ""
                currentUserPhone = generatedPhone
                val name = firebaseSyncManager.currentUserState.value?.name ?: "Google User"
                syncProfileState(name)
            }
            onResult(success, error)
        }
    }

    fun signUp(email: String, password: String, name: String, onResult: (Boolean, String?) -> Unit) {
        firebaseSyncManager.signUpWithEmail(email, password, name) { success, error ->
            if (success) {
                val generatedPhone = firebaseSyncManager.currentUserState.value?.phoneNumber ?: ""
                currentUserPhone = generatedPhone
                syncProfileState(name)
            }
            onResult(success, error)
        }
    }

    fun login(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        firebaseSyncManager.loginWithEmail(email, password) { success, error ->
            if (success) {
                val generatedPhone = firebaseSyncManager.currentUserState.value?.phoneNumber ?: ""
                currentUserPhone = generatedPhone
                val name = firebaseSyncManager.currentUserState.value?.name ?: "User"
                syncProfileState(name)
            }
            onResult(success, error)
        }
    }

    fun logout() {
        firebaseSyncManager.signOut()
        currentUserPhone = ""
    }

    // Search cloud directory for other users by phone
    fun searchCloudPeer(phone: String, onResult: (Contact?) -> Unit) {
        firebaseSyncManager.searchContactByPhoneNumber(phone, onResult)
    }

    // Search cloud directory globally for multiple users (prefix and exact match)
    fun searchCloudPeers(query: String, onResult: (List<Contact>) -> Unit) {
        firebaseSyncManager.searchUsersCloud(query, onResult)
    }

    // Password reset helper
    fun sendPasswordReset(email: String, onResult: (Boolean, String?) -> Unit) {
        firebaseSyncManager.sendPasswordResetEmail(email, onResult)
    }

    // Cloud Call History flow
    private val _cloudCallRecords = MutableStateFlow<List<CallRecord>>(emptyList())
    val cloudCallRecords: StateFlow<List<CallRecord>> = _cloudCallRecords.asStateFlow()

    fun syncCallHistoryFromCloud() {
        val phone = firebaseSyncManager.currentUserState.value?.phoneNumber ?: currentUserPhone
        if (phone.isNotEmpty()) {
            firebaseSyncManager.fetchCallHistoryCloud(phone) { cloudRecords ->
                _cloudCallRecords.value = cloudRecords
                viewModelScope.launch(Dispatchers.IO) {
                    cloudRecords.forEach { record ->
                        if (!repository.hasCallRecordWithTimestamp(record.timestamp)) {
                            repository.insertCallRecord(record)
                        }
                    }
                }
            }
        }
    }

    // Sync active profile with real-time firestore
    fun syncProfileState(username: String) {
        val userPhone = firebaseSyncManager.currentUserState.value?.phoneNumber
        val derivedPhone = userPhone ?: ("+1555" + username.hashCode().absoluteValue.toString().take(6))
        currentUserPhone = derivedPhone
        viewModelScope.launch(Dispatchers.IO) {
            // Write status to firestore database
            firebaseSyncManager.updateOnlineStatus(derivedPhone, "Online")
            firebaseSyncManager.registerDevice(derivedPhone, android.os.Build.MODEL)
            
            // Also ensure this user exists in our local contact repository so they can call themselves or be recognized
            if (repository.getContactByPhone(derivedPhone) == null) {
                repository.insertContact(Contact(phone = derivedPhone, name = username, status = "Online"))
            }
            syncContactGroupsFromCloud()
        }
    }

    fun syncContactGroupsFromCloud() {
        val phone = firebaseSyncManager.currentUserState.value?.phoneNumber ?: currentUserPhone
        if (phone.isNotEmpty()) {
            firebaseSyncManager.fetchContactGroupsCloud(phone) { cloudGroups ->
                viewModelScope.launch(Dispatchers.IO) {
                    cloudGroups.forEach { (contactPhone, group) ->
                        val existing = repository.getContactByPhone(contactPhone)
                        if (existing != null) {
                            if (existing.group != group) {
                                repository.insertContact(existing.copy(group = group))
                            }
                        } else {
                            repository.insertContact(Contact(phone = contactPhone, name = "Cloud Peer " + contactPhone.takeLast(4), group = group))
                        }
                    }
                }
            }
        }
    }

    fun startListeningToActiveSession(roomId: String) {
        val db = firebaseSyncManager.firestore
        if (db != null && firebaseSyncManager.isFirebaseAvailable.value) {
            firebaseSyncManager.activeSessionsListener?.remove()
            firebaseSyncManager.activeSessionsListener = db.collection("call_sessions").document(roomId)
                .addSnapshotListener { snapshot, e ->
                    if (e != null || snapshot == null || !snapshot.exists()) {
                        viewModelScope.launch(Dispatchers.Main) {
                            if (callManager.currentSession.value != null && _currentRoomId.value == roomId) {
                                Log.d("CallLinkViewModel", "Active session document removed. Ending call.")
                                callManager.endCall()
                                _currentRoomId.value = null
                                _activeFirestoreSession.value = null
                            }
                        }
                        return@addSnapshotListener
                    }
                    val session = snapshot.toObject(com.example.data.api.FirestoreCallSession::class.java)
                    if (session != null) {
                        viewModelScope.launch(Dispatchers.Main) {
                            _activeFirestoreSession.value = session
                            if (session.status == "ended") {
                                Log.d("CallLinkViewModel", "Active session status ended. Ending call.")
                                callManager.endCall()
                                _currentRoomId.value = null
                                _activeFirestoreSession.value = null
                            }
                        }
                    }
                }
        }
    }

    // Call Actions
    fun startCall(contact: Contact, isVoiceOnly: Boolean, isGroup: Boolean = false) {
        val roomId = "room_" + UUID.randomUUID().toString().take(8)
        _currentRoomId.value = roomId

        val callerPhone = firebaseSyncManager.currentUserState.value?.phoneNumber ?: currentUserPhone
        val callerName = firebaseSyncManager.currentUserState.value?.name ?: "Me"

        if (callerPhone.isNotEmpty()) {
            firebaseSyncManager.updateOnlineStatus(callerPhone, "Busy")
        }
        
        val useFirebase = firebaseSyncManager.isFirebaseAvailable.value
        callManager.startCall(contact, isVoiceOnly, isGroup, autoAcceptSimulation = !useFirebase)

        // Store active calling session on firestore
        firebaseSyncManager.storeActiveCallSession(
            roomId = roomId,
            callerPhone = callerPhone,
            callerName = callerName,
            calleePhone = contact.phone,
            calleeName = contact.name,
            isVoiceOnly = isVoiceOnly
        ) { updatedSession ->
            _activeFirestoreSession.value = updatedSession
            if (useFirebase) {
                val currentRtcSession = callManager.currentSession.value
                if (currentRtcSession != null) {
                    if (updatedSession.status == "connected" && currentRtcSession.state == CallState.OUTGOING_RINGING) {
                        callManager.transitionToConnecting()
                    } else if (updatedSession.status == "ended") {
                        callManager.endCall()
                        _currentRoomId.value = null
                        _activeFirestoreSession.value = null
                    }
                }
            }
        }
        
        // Push the call record to Firestore as a live outgoing event
        viewModelScope.launch(Dispatchers.IO) {
            val record = CallRecord(
                contactName = contact.name,
                contactPhone = contact.phone,
                isGroup = isGroup,
                isVoice = isVoiceOnly,
                callType = "Outgoing",
                durationSeconds = 0
            )
            firebaseSyncManager.logCallRecordCloud(record)
        }
    }

    fun receiveIncomingCall(contact: Contact, isVoiceOnly: Boolean, isGroup: Boolean = false) {
        callManager.receiveIncomingCall(contact, isVoiceOnly, isGroup)
    }

    fun answerCall() {
        val callerPhone = firebaseSyncManager.currentUserState.value?.phoneNumber ?: currentUserPhone
        if (callerPhone.isNotEmpty()) {
            firebaseSyncManager.updateOnlineStatus(callerPhone, "In Call")
        }
        
        val roomId = _currentRoomId.value
        if (roomId != null) {
            firebaseSyncManager.joinActiveCallSession(roomId, callerPhone) { updatedSession ->
                _activeFirestoreSession.value = updatedSession
                if (updatedSession.status == "ended") {
                    callManager.endCall()
                    _currentRoomId.value = null
                    _activeFirestoreSession.value = null
                }
            }
        }
        
        callManager.answerCall()
    }

    fun rejectCall() {
        val callerPhone = firebaseSyncManager.currentUserState.value?.phoneNumber ?: currentUserPhone
        if (callerPhone.isNotEmpty()) {
            firebaseSyncManager.updateOnlineStatus(callerPhone, "Online")
        }
        
        val roomId = _currentRoomId.value
        if (roomId != null) {
            firebaseSyncManager.updateActiveCallSessionStatus(roomId, "ended")
            firebaseSyncManager.removeActiveCallSession(roomId)
            _currentRoomId.value = null
            _activeFirestoreSession.value = null
        }
        
        callManager.rejectCall()
    }

    fun cancelCall() {
        val callerPhone = firebaseSyncManager.currentUserState.value?.phoneNumber ?: currentUserPhone
        if (callerPhone.isNotEmpty()) {
            firebaseSyncManager.updateOnlineStatus(callerPhone, "Online")
        }
        
        val roomId = _currentRoomId.value
        if (roomId != null) {
            firebaseSyncManager.updateActiveCallSessionStatus(roomId, "ended")
            firebaseSyncManager.removeActiveCallSession(roomId)
            _currentRoomId.value = null
            _activeFirestoreSession.value = null
        }
        
        callManager.cancelCall()
    }

    fun endCall() {
        val callerPhone = firebaseSyncManager.currentUserState.value?.phoneNumber ?: currentUserPhone
        if (callerPhone.isNotEmpty()) {
            firebaseSyncManager.updateOnlineStatus(callerPhone, "Online")
        }
        
        val roomId = _currentRoomId.value
        if (roomId != null) {
            firebaseSyncManager.updateActiveCallSessionStatus(roomId, "ended")
            firebaseSyncManager.removeActiveCallSession(roomId)
            _currentRoomId.value = null
            _activeFirestoreSession.value = null
        }
        
        callManager.endCall()
    }

    // Join room from a shareable link ID
    fun joinRoomById(roomId: String) {
        _currentRoomId.value = roomId
        val callerPhone = firebaseSyncManager.currentUserState.value?.phoneNumber ?: currentUserPhone.ifEmpty { "+1 (609) 222-1111" }
        val callerName = firebaseSyncManager.currentUserState.value?.name ?: "Guest Joiner"

        firebaseSyncManager.joinActiveCallSession(roomId, callerPhone) { updatedSession ->
            _activeFirestoreSession.value = updatedSession
            
            // Re-route local session into connected call
            val peerPhone = if (callerPhone == updatedSession.callerPhone) updatedSession.calleePhone else updatedSession.callerPhone
            val peerName = if (callerPhone == updatedSession.callerPhone) updatedSession.calleeName else updatedSession.callerName
            
            viewModelScope.launch(Dispatchers.Main) {
                if (callManager.currentSession.value == null) {
                    val virtualContact = Contact(phone = peerPhone, name = peerName, status = "In Call")
                    callManager.receiveIncomingCall(virtualContact, updatedSession.isVoiceOnly, false)
                    callManager.answerCall()
                }
            }
        }
    }

    fun toggleMute() {
        callManager.toggleMute()
    }

    fun toggleRecording() {
        callManager.toggleRecording()
    }

    fun toggleCamera() {
        callManager.toggleCamera()
    }

    fun toggleSpeaker() {
        callManager.toggleSpeaker()
    }

    fun switchCamera() {
        callManager.switchCamera()
    }

    fun triggerReconnect() {
        callManager.triggerReconnect()
    }

    fun sendSpeechOrText(message: String) {
        callManager.sendSpeechOrText(message)
    }

    fun clearLogs() {
        callManager.clearLogs()
    }

    // Contact Actions
    fun addContact(name: String, phone: String, group: String = "None", isFavorite: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertContact(Contact(phone = phone, name = name, group = group, isFavorite = isFavorite, status = "Offline"))
            firebaseSyncManager.saveContactGroupToCloud(phone, group)
        }
    }

    fun updateContactGroup(phone: String, group: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val contact = repository.getContactByPhone(phone)
            if (contact != null) {
                repository.insertContact(contact.copy(group = group))
                firebaseSyncManager.saveContactGroupToCloud(phone, group)
            }
        }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteContact(contact)
        }
    }

    fun toggleContactFavorite(phone: String, currentFavorite: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setContactFavorite(phone, !currentFavorite)
        }
    }

    fun toggleContactBlocked(phone: String, currentBlocked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setContactBlocked(phone, !currentBlocked)
        }
    }

    // History Actions
    fun deleteCallRecord(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteCallRecordById(id)
        }
    }

    fun clearCallHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearCallHistory()
        }
    }

    // ICE Config Actions
    fun addIceServer(label: String, url: String, username: String? = null, credential: String? = null, isTurn: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertIceServer(IceServer(label = label, url = url, username = username, credential = credential, isTurn = isTurn))
        }
    }

    fun deleteIceServer(server: IceServer) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteIceServer(server)
        }
    }

    fun clearIceServers() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearIceServers()
        }
    }

    // Custom ViewModel Factory
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CallLinkViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return CallLinkViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
