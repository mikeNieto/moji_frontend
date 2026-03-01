package com.mhm.moji_frontend

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.sqrt

class AudioRecorder(
    private val onAudioCaptured: (ByteArray) -> Unit,
    private val continuousListeningManager: ContinuousListeningManager? = null
) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var stateObserverJob: Job? = null

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val RMS_THRESHOLD = 800.0  // Raised: better separation from ambient noise
        private const val SILENCE_DURATION_MS = 1500L // 1.5 seconds of silence AFTER speech to stop
        private const val MAX_DURATION_MS = 30000L // 30 seconds max timeout
        private const val INITIAL_GRACE_PERIOD_MS = 2000L // 2 seconds to start speaking, then discard
    }

    private var previousState: RobotState = RobotState.IDLE

    init {
        stateObserverJob = coroutineScope.launch {
            StateManager.currentState.collect { state ->
                if (state == RobotState.LISTENING && previousState != RobotState.IDLE) {
                    // Only start recording when entering LISTENING from a non-IDLE state
                    // (i.e., after face search completes: SEARCHING/GREETING/REGISTERING → LISTENING)
                    // Don't record during the brief IDLE→LISTENING→SEARCHING transition
                    startRecording()
                } else if (state != RobotState.THINKING &&
                           state != RobotState.LISTENING &&
                           state != RobotState.GREETING &&
                           state != RobotState.REGISTERING) {
                    // Stop recording on terminal/idle states, but not on transitional states
                    // (GREETING/REGISTERING are brief transitions before LISTENING)
                    stopRecording()
                }
                previousState = state
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (recordingJob?.isActive == true) {
            return
        }

        recordingJob = coroutineScope.launch {
            // Give the OS time to fully release the microphone from the wake word detector
            // and for any residual TTS echo to decay
            kotlinx.coroutines.delay(500)

            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size")
                launch(Dispatchers.Main) {
                    StateManager.updateState(RobotState.ERROR)
                }
                return@launch
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed. State: ${audioRecord?.state}")
                launch(Dispatchers.Main) {
                    StateManager.updateState(RobotState.ERROR)
                }
                audioRecord?.release()
                audioRecord = null
                return@launch
            }

            try {
                audioRecord?.startRecording()
                
                // Add a small delay to ensure recording actually started
                kotlinx.coroutines.delay(100)
                
                if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.e(TAG, "AudioRecord failed to start recording.")
                    launch(Dispatchers.Main) {
                        StateManager.updateState(RobotState.ERROR)
                    }
                    audioRecord?.release()
                    audioRecord = null
                    return@launch
                }

                Log.d(TAG, "Recording started")

                val buffer = ShortArray(1024)
                val pcmDataStream = ByteArrayOutputStream()

                var silenceStartTime = -1L
                val recordingStartTime = System.currentTimeMillis()
                var logCounter = 0
                var hasDetectedSpeech = false // Track if the user has started speaking

                while (isActive) {
                    val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readResult > 0) {
                        // Calculate RMS
                        var sumSq = 0.0
                        for (i in 0 until readResult) {
                            val sample = buffer[i].toDouble()
                            sumSq += sample * sample
                            
                            // Write little-endian PCM
                            pcmDataStream.write(buffer[i].toInt() and 0xFF)
                            pcmDataStream.write((buffer[i].toInt() shr 8) and 0xFF)
                        }
                        val rms = sqrt(sumSq / readResult)

                        if (logCounter++ % 10 == 0) {
                            Log.d(TAG, "Current RMS: $rms, speechDetected: $hasDetectedSpeech")
                        }

                        val currentTime = System.currentTimeMillis()
                        val elapsedTime = currentTime - recordingStartTime

                        // VAD logic: only start silence detection AFTER speech has been detected
                        if (rms >= RMS_THRESHOLD) {
                            // User is speaking
                            hasDetectedSpeech = true
                            silenceStartTime = -1L // Reset silence timer
                        } else {
                            // Silence detected
                            if (hasDetectedSpeech) {
                                // User was speaking but now is silent — start/continue silence timer
                                if (silenceStartTime == -1L) {
                                    silenceStartTime = currentTime
                                } else if (currentTime - silenceStartTime >= SILENCE_DURATION_MS) {
                                    Log.d(TAG, "User stopped speaking (silence for ${SILENCE_DURATION_MS}ms). Stopping recording.")
                                    break
                                }
                            } else {
                                // User hasn't started speaking yet — check grace period
                                if (elapsedTime >= INITIAL_GRACE_PERIOD_MS) {
                                    Log.d(TAG, "No speech detected within grace period (${INITIAL_GRACE_PERIOD_MS}ms). Stopping recording.")
                                    break
                                }
                            }
                        }

                        // Timeout logic
                        if (elapsedTime >= MAX_DURATION_MS) {
                            Log.d(TAG, "Max recording duration reached (${MAX_DURATION_MS / 1000}s). Stopping recording.")
                            break
                        }
                    } else if (readResult < 0) {
                        Log.e(TAG, "AudioRecord read error: $readResult")
                        break
                    }
                }

                // If recording job was cancelled, don't emit
                if (!isActive) {
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioRecord = null
                    return@launch
                }

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                // If no speech was ever detected, discard the audio entirely
                // and return to IDLE — don't waste a server round-trip on silence
                if (!hasDetectedSpeech) {
                    Log.d(TAG, "No speech detected — discarding audio and returning to IDLE")
                    launch(Dispatchers.Main) {
                        // Stop continuous listening so WakeWordDetector can restart Porcupine
                        continuousListeningManager?.stop()
                        StateManager.updateState(RobotState.IDLE)
                    }
                    return@launch
                }

                val pcmData = pcmDataStream.toByteArray()
                Log.d(TAG, "Audio capturado: ${pcmData.size} bytes")

                // Transition to THINKING state IN THE MAIN THREAD!
                launch(Dispatchers.Main) {
                   StateManager.updateState(RobotState.THINKING)
                }

                // Compress PCM to AAC
                val aacData = encodePcmToAac(pcmData)
                if (aacData.isNotEmpty()) {
                    Log.d(TAG, "Audio compressed to AAC: ${aacData.size} bytes")
                    onAudioCaptured(aacData)
                    // State stays in THINKING — InteractionOrchestrator handles
                    // the response via WebSocket and transitions to RESPONDING/LISTENING
                } else {
                    Log.e(TAG, "Failed to compress audio to AAC")
                    launch(Dispatchers.Main) {
                        StateManager.updateState(RobotState.ERROR)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Exception during recording", e)
                    launch(Dispatchers.Main) {
                        StateManager.updateState(RobotState.ERROR)
                    }
                }
                audioRecord?.release()
                audioRecord = null
            }
        }
    }

    fun stop() {
        stateObserverJob?.cancel()
        stopRecording()
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord.stop() failed (was it ever started?): ${e.message}")
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.release() failed: ${e.message}")
        }
        audioRecord = null
    }

    private fun encodePcmToAac(pcmData: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        try {
            val mime = MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            }

            val codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputOffset = 0
            var isEOS = false
            var inputEOS = false

            while (!isEOS) {
                // Feed input
                if (!inputEOS) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val remaining = pcmData.size - inputOffset
                            if (remaining <= 0) {
                                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEOS = true
                            } else {
                                val chunkSize = min(inputBuffer.capacity(), remaining)
                                inputBuffer.put(pcmData, inputOffset, chunkSize)
                                codec.queueInputBuffer(inputBufferIndex, 0, chunkSize, System.nanoTime() / 1000, 0)
                                inputOffset += chunkSize
                            }
                        }
                    }
                }

                // Consume output
                var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                while (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        // ADTS Header is typically needed for streaming raw AAC
                        val chunkAudio = ByteArray(bufferInfo.size + 7)
                        val outLen = bufferInfo.size + 7
                        
                        // ADTS Header
                        val profile = 2 // AAC LC
                        val freqIdx = 8 // 16kHz
                        val chanCfg = 1 // Mono

                        chunkAudio[0] = 0xFF.toByte()
                        chunkAudio[1] = 0xF9.toByte()
                        chunkAudio[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
                        chunkAudio[3] = (((chanCfg and 3) shl 6) + (outLen shr 11)).toByte()
                        chunkAudio[4] = ((outLen and 0x7FF) shr 3).toByte()
                        chunkAudio[5] = (((outLen and 7) shl 5) + 0x1F).toByte()
                        chunkAudio[6] = 0xFC.toByte()

                        outputBuffer.get(chunkAudio, 7, bufferInfo.size)
                        
                        outputStream.write(chunkAudio)
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEOS = true
                        break
                    }
                    outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                }
            }
            codec.stop()
            codec.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding to AAC", e)
        }
        return outputStream.toByteArray()
    }
}
