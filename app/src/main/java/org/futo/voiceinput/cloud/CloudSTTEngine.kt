package org.futo.voiceinput.cloud

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.futo.voiceinput.SpeechRecognizerEngine
import org.futo.voiceinput.ggml.DecodingMode
import org.futo.voiceinput.ml.RunState
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Cloud-based speech recognition engine.
 * 
 * This implementation can be adapted for various cloud providers:
 * - Google Cloud Speech-to-Text
 * - Azure Speech Services
 * - AWS Transcribe
 * - OpenAI Whisper API
 * - Custom STT servers
 * 
 * Current implementation shows a generic HTTP/WebSocket pattern.
 */
class CloudSTTEngine(
    private val context: Context,
    private val apiEndpoint: String,
    private val apiKey: String,
    private val streamingEnabled: Boolean = true
) : SpeechRecognizerEngine {
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private var partialCallback: ((String) -> Unit)? = null
    private var statusCallback: ((RunState) -> Unit)? = null
    private var webSocket: WebSocket? = null
    
    override suspend fun transcribe(
        samples: FloatArray,
        glossary: String,
        forceLanguage: String?,
        decodingMode: DecodingMode
    ): String = withContext(Dispatchers.IO) {
        statusCallback?.invoke(RunState.ProcessingEncoder)
        
        if (streamingEnabled) {
            transcribeStreaming(samples, glossary, forceLanguage)
        } else {
            transcribeBatch(samples, glossary, forceLanguage)
        }
    }
    
    /**
     * Batch transcription - sends entire audio at once
     */
    private suspend fun transcribeBatch(
        samples: FloatArray,
        glossary: String,
        forceLanguage: String?
    ): String {
        // Convert float samples to 16-bit PCM
        val pcmData = floatArrayToPcm16(samples)
        
        // Create WAV file in memory
        val wavData = createWavFile(pcmData, 16000)
        
        // Build request based on your cloud provider's API
        // Example for a generic API that accepts WAV files:
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio", 
                "audio.wav",
                wavData.toRequestBody("audio/wav".toMediaType())
            )
            .apply {
                if (forceLanguage != null) {
                    addFormDataPart("language", forceLanguage)
                }
                if (glossary.isNotBlank()) {
                    addFormDataPart("hints", glossary)
                }
            }
            .build()
        
        val request = Request.Builder()
            .url(apiEndpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
        
        return try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                parseTranscriptionResponse(responseBody)
            } else {
                throw IOException("Cloud STT failed: ${response.code} ${response.message}")
            }
        } catch (e: Exception) {
            throw IOException("Cloud STT error: ${e.message}", e)
        }
    }
    
    /**
     * Streaming transcription using WebSocket
     */
    private suspend fun transcribeStreaming(
        samples: FloatArray,
        glossary: String,
        forceLanguage: String?
    ): String {
        var finalTranscript = ""
        val pcmData = floatArrayToPcm16(samples)
        
        // Build WebSocket request
        val request = Request.Builder()
            .url(apiEndpoint.replace("http://", "ws://").replace("https://", "wss://"))
            .header("Authorization", "Bearer $apiKey")
            .build()
        
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Send configuration
                val config = JSONObject().apply {
                    put("config", JSONObject().apply {
                        put("encoding", "LINEAR16")
                        put("sampleRateHertz", 16000)
                        put("languageCode", forceLanguage ?: "en-US")
                        if (glossary.isNotBlank()) {
                            put("speechContexts", org.json.JSONArray().apply {
                                put(JSONObject().apply {
                                    put("phrases", glossary.split(",").map { it.trim() })
                                })
                            })
                        }
                    })
                }
                webSocket.send(config.toString())
                
                // Stream audio in chunks
                val chunkSize = 16000 // 1 second chunks
                var offset = 0
                while (offset < pcmData.size) {
                    val end = minOf(offset + chunkSize * 2, pcmData.size)
                    val chunk = pcmData.sliceArray(offset until end)
                    
                    // Send audio chunk
                    val audioMessage = JSONObject().apply {
                        put("audio", Base64.encodeToString(chunk, Base64.NO_WRAP))
                    }
                    webSocket.send(audioMessage.toString())
                    
                    offset = end
                    // Small delay to avoid overwhelming the server
                    Thread.sleep(50)
                }
                
                // Signal end of audio
                webSocket.send(JSONObject().apply { put("endOfAudio", true) }.toString())
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                val response = JSONObject(text)
                
                // Parse interim/partial results
                if (response.has("partial")) {
                    val partial = response.getString("partial")
                    partialCallback?.invoke(partial)
                }
                
                // Parse final result
                if (response.has("transcript")) {
                    finalTranscript = response.getString("transcript")
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                t.printStackTrace()
            }
        }
        
        webSocket = httpClient.newWebSocket(request, listener)
        
        // Wait for completion (with timeout)
        var waitTime = 0
        while (finalTranscript.isEmpty() && waitTime < 60000) {
            delay(100)
            waitTime += 100
        }
        
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        
        return finalTranscript
    }
    
    /**
     * Convert float array [-1, 1] to 16-bit PCM
     */
    private fun floatArrayToPcm16(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        for (sample in samples) {
            val pcmValue = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            buffer.putShort(pcmValue)
        }
        
        return buffer.array()
    }
    
    /**
     * Create a WAV file header and combine with PCM data
     */
    private fun createWavFile(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2 // 16-bit mono
        
        // WAV header
        outputStream.write("RIFF".toByteArray())
        outputStream.write(intToByteArray(totalDataLen))
        outputStream.write("WAVE".toByteArray())
        outputStream.write("fmt ".toByteArray())
        outputStream.write(intToByteArray(16)) // Sub chunk size
        outputStream.write(shortToByteArray(1)) // Audio format (PCM)
        outputStream.write(shortToByteArray(1)) // Channels (mono)
        outputStream.write(intToByteArray(sampleRate))
        outputStream.write(intToByteArray(byteRate))
        outputStream.write(shortToByteArray(2)) // Block align
        outputStream.write(shortToByteArray(16)) // Bits per sample
        outputStream.write("data".toByteArray())
        outputStream.write(intToByteArray(pcmData.size))
        outputStream.write(pcmData)
        
        return outputStream.toByteArray()
    }
    
    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )
    }
    
    private fun shortToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte()
        )
    }
    
    /**
     * Parse the transcription response from your cloud provider
     */
    private fun parseTranscriptionResponse(response: String): String {
        // This depends on your cloud provider's response format
        // Example for a JSON response:
        return try {
            val json = JSONObject(response)
            json.optString("transcript", "")
        } catch (e: Exception) {
            ""
        }
    }
    
    override suspend fun close() {
        webSocket?.close(1000, "Client closing")
        webSocket = null
    }
    
    override fun setPartialResultCallback(callback: (String) -> Unit) {
        partialCallback = callback
    }
    
    override fun setStatusCallback(callback: (RunState) -> Unit) {
        statusCallback = callback
    }
}
