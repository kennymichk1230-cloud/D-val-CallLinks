package com.example.webrtc

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.entity.CallRecord
import com.example.data.entity.Contact
import com.example.data.repository.CallLinkRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import kotlin.random.Random

enum class CallState {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    BUSY,
    RECONNECTING
}

data class CallSession(
    val contactName: String,
    val contactPhone: String,
    val isVoiceOnly: Boolean,
    val isGroup: Boolean = false,
    val state: CallState = CallState.IDLE,
    val isMuted: Boolean = false,
    val isCameraEnabled: Boolean = true,
    val isSpeakerOn: Boolean = false,
    val durationSeconds: Long = 0,
    val sdpOffer: String = "",
    val sdpAnswer: String = "",
    val localIceCandidates: List<String> = emptyList(),
    val remoteIceCandidates: List<String> = emptyList(),
    val currentIceServer: String = "stun:stun.l.google.com:19302",
    val callQualityScore: Int = 100,
    val conversationTranscript: List<Pair<String, String>> = emptyList(), // Speaker name to message
    val isRecording: Boolean = false
)

class WebRtcCallManager(
    private val repository: CallLinkRepository
) {
    var onCallRecordCreated: ((CallRecord) -> Unit)? = null

    private val _currentSession = MutableStateFlow<CallSession?>(null)
    val currentSession: StateFlow<CallSession?> = _currentSession.asStateFlow()

    private val _connectionLogs = MutableStateFlow<List<String>>(emptyList())
    val connectionLogs: StateFlow<List<String>> = _connectionLogs.asStateFlow()

    private var timerJob: Job? = null
    private var simulationJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        _connectionLogs.value = _connectionLogs.value + "[$timestamp] $message"
        Log.d("WebRtcCall", message)
    }

    fun clearLogs() {
        _connectionLogs.value = emptyList()
    }

    fun startCall(contact: Contact, isVoiceOnly: Boolean, isGroup: Boolean = false, autoAcceptSimulation: Boolean = true) {
        if (_currentSession.value != null) {
            addLog("Error: Call already in progress")
            return
        }

        clearLogs()
        addLog("Initializing WebRTC peer connection for CallLink...")
        addLog("Protocol: STUN/TURN (relay-mode enabled)")

        _currentSession.value = CallSession(
            contactName = contact.name,
            contactPhone = contact.phone,
            isVoiceOnly = isVoiceOnly,
            isGroup = isGroup,
            state = CallState.OUTGOING_RINGING
        )

        addLog("Ringing remote peer: ${contact.name} (${contact.phone})")
        if (autoAcceptSimulation) {
            startOutgoingCallSimulation()
        }
    }

    fun receiveIncomingCall(contact: Contact, isVoiceOnly: Boolean, isGroup: Boolean = false) {
        if (_currentSession.value != null) {
            addLog("Busy signal sent to incoming peer: ${contact.name}")
            return
        }

        clearLogs()
        addLog("Incoming Call Signal received via FCM Push Notification")
        _currentSession.value = CallSession(
            contactName = contact.name,
            contactPhone = contact.phone,
            isVoiceOnly = isVoiceOnly,
            isGroup = isGroup,
            state = CallState.INCOMING_RINGING
        )
    }

    fun answerCall() {
        val session = _currentSession.value ?: return
        if (session.state != CallState.INCOMING_RINGING) return

        addLog("User accepted incoming call. Handshaking WebRTC signaling...")
        transitionToConnecting()
    }

    fun rejectCall() {
        val session = _currentSession.value ?: return
        addLog("Call rejected by user.")
        saveHistoryAndEnd(session, isMissed = true)
    }

    fun cancelCall() {
        val session = _currentSession.value ?: return
        addLog("Call cancelled by initiator.")
        saveHistoryAndEnd(session, isMissed = false)
    }

    fun endCall() {
        val session = _currentSession.value ?: return
        addLog("Call ended by user. Closing peer connection and releasing media streams.")
        saveHistoryAndEnd(session, isMissed = false)
    }

    fun toggleMute() {
        val session = _currentSession.value ?: return
        val newMute = !session.isMuted
        _currentSession.value = session.copy(isMuted = newMute)
        addLog("Microphone ${if (newMute) "muted" else "unmuted"}")
    }

    fun toggleCamera() {
        val session = _currentSession.value ?: return
        val newCamera = !session.isCameraEnabled
        _currentSession.value = session.copy(isCameraEnabled = newCamera)
        addLog("Camera ${if (newCamera) "disabled" else "enabled"}")
    }

    fun toggleSpeaker() {
        val session = _currentSession.value ?: return
        val newSpeaker = !session.isSpeakerOn
        _currentSession.value = session.copy(isSpeakerOn = newSpeaker)
        addLog("Audio routing updated: ${if (newSpeaker) "SPEAKERPHONE" else "EARPIECE/BLUETOOTH"}")
    }

    fun switchCamera() {
        val session = _currentSession.value ?: return
        addLog("Switched video stream source (Front -> Rear / Rear -> Front camera)")
    }

    fun triggerReconnect() {
        val session = _currentSession.value ?: return
        if (session.state != CallState.CONNECTED) return

        coroutineScope.launch {
            _currentSession.value = session.copy(state = CallState.RECONNECTING)
            addLog("WARNING: Network instability detected. Re-establishing WebRTC ICE state...")
            delay(2000)
            _currentSession.value = _currentSession.value?.copy(state = CallState.CONNECTED)
            addLog("INFO: Peer connection restored via ICE TURN relay server!")
        }
    }

    fun toggleRecording() {
        val session = _currentSession.value ?: return
        val nextState = !session.isRecording
        _currentSession.value = session.copy(isRecording = nextState)
        if (nextState) {
            addLog("Call recording STARTED (Local PCM loopback stream initiated)")
        } else {
            addLog("Call recording STOPPED (Saved to local memory storage: /sdcard/DvalCallLink/Recordings/call_rec_${System.currentTimeMillis()}.wav)")
        }
    }

    fun transitionToConnecting() {
        val session = _currentSession.value ?: return
        _currentSession.value = session.copy(state = CallState.CONNECTING)

        simulationJob = coroutineScope.launch {
            try {
                addLog("1. Creating local RTCPeerConnection...")
                delay(400)
                addLog("2. Fetching dynamic STUN/TURN servers from repository...")
                val iceServers = withContext(Dispatchers.IO) {
                    repository.allIceServers
                }
                delay(300)
                addLog("3. Gathering local ICE candidates...")
                val localIce = listOf(
                    "candidate:423492837 1 udp 2122260223 192.168.1.100 53421 typ host",
                    "candidate:283749283 1 udp 1686052863 74.125.143.12 3478 typ srflx raddr 192.168.1.100 rport 53421",
                    "candidate:110293847 1 tcp 2122260223 192.168.1.100 9 typ host"
                )
                _currentSession.value = _currentSession.value?.copy(localIceCandidates = localIce)
                delay(500)

                addLog("4. Creating local Session Description Protocol (SDP) Offer...")
                val offerSdp = """
                    v=0
                    o=- 463728192837 2 IN IP4 127.0.0.1
                    s=-
                    t=0 0
                    a=group:BUNDLE audio video
                    m=audio 9 UDP/TLS/RTP/SAVPF 111 103 104 9 0 8 106 105 13 110 112 113 126
                    c=IN IP4 0.0.0.0
                    a=rtpmap:111 opus/48000/2
                """.trimIndent()
                _currentSession.value = _currentSession.value?.copy(sdpOffer = offerSdp)
                addLog("SDP Offer Created successfully!")
                delay(600)

                addLog("5. Sending SDP Offer through Socket.io Signaling channel...")
                delay(500)
                addLog("6. Received Remote SDP Answer from remote peer...")
                val answerSdp = """
                    v=0
                    o=- 463728192837 3 IN IP4 127.0.0.1
                    s=-
                    t=0 0
                    a=group:BUNDLE audio video
                    m=audio 9 UDP/TLS/RTP/SAVPF 111
                    c=IN IP4 0.0.0.0
                    a=rtpmap:111 opus/48000/2
                """.trimIndent()
                _currentSession.value = _currentSession.value?.copy(sdpAnswer = answerSdp)
                delay(400)

                addLog("7. Performing WebRTC DTLS cryptography handshake...")
                delay(500)

                addLog("8. Exchanging ICE Candidates...")
                val remoteIce = listOf(
                    "candidate:982348123 1 udp 2122260223 10.0.0.2 60233 typ host",
                    "candidate:872349283 1 udp 4181923 172.56.21.9 3478 typ relay raddr 10.0.0.2 rport 60233"
                )
                _currentSession.value = _currentSession.value?.copy(remoteIceCandidates = remoteIce)
                delay(300)

                addLog("9. Connection established! Syncing MediaStreamTracks...")
                delay(200)

                _currentSession.value = _currentSession.value?.copy(
                    state = CallState.CONNECTED,
                    conversationTranscript = listOf("System" to "WebRTC secured call connected with ${session.contactName}")
                )
                addLog("Call Connected! Status: SECURE_WEBRTC")

                startTimer()

            } catch (e: Exception) {
                addLog("Error establishing WebRTC connection: ${e.message}")
                _currentSession.value = _currentSession.value?.copy(state = CallState.DISCONNECTED)
            }
        }
    }

    private fun startOutgoingCallSimulation() {
        simulationJob = coroutineScope.launch {
            delay(3000) // Ring for 3 seconds
            val session = _currentSession.value
            if (session != null && session.state == CallState.OUTGOING_RINGING) {
                addLog("Remote peer accepted the call signal!")
                transitionToConnecting()
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = coroutineScope.launch {
            while (isActive) {
                delay(1000)
                val session = _currentSession.value ?: break
                if (session.state == CallState.CONNECTED) {
                    val nextDur = session.durationSeconds + 1
                    // Randomly fluctuate call quality score slightly to represent actual dynamic WebRTC WebRTC bandwidth statistics
                    val fluctuation = Random.nextInt(-3, 3)
                    val nextQuality = (session.callQualityScore + fluctuation).coerceIn(80, 100)
                    _currentSession.value = session.copy(
                        durationSeconds = nextDur,
                        callQualityScore = nextQuality
                    )
                }
            }
        }
    }

    fun sendSpeechOrText(message: String) {
        val session = _currentSession.value ?: return
        if (session.state != CallState.CONNECTED) return

        _currentSession.value = session.copy(
            conversationTranscript = session.conversationTranscript + ("Me" to message)
        )
    }

    private fun saveHistoryAndEnd(session: CallSession, isMissed: Boolean) {
        timerJob?.cancel()
        simulationJob?.cancel()

        val duration = session.durationSeconds
        val callType = when {
            isMissed -> "Missed"
            session.state == CallState.INCOMING_RINGING -> "Incoming"
            else -> if (duration > 0) "Incoming" else "Outgoing"
        }

        coroutineScope.launch {
            val record = CallRecord(
                contactName = session.contactName,
                contactPhone = session.contactPhone,
                isGroup = session.isGroup,
                isVoice = session.isVoiceOnly,
                callType = callType,
                durationSeconds = duration
            )
            withContext(Dispatchers.IO) {
                repository.insertCallRecord(record)
            }
            onCallRecordCreated?.invoke(record)
            _currentSession.value = null
        }
    }
}
