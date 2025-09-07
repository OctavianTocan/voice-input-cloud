package org.futo.voiceinput.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
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
 * OpenAI Whisper API implementation.
 * 
 * Uses the OpenAI Audio API to transcribe audio using Whisper in the cloud.
 * See: https://platform.openai.com/docs/api-reference/audio/create
 */
class OpenAIWhisperEngine(
    private val context: Context,
    private val apiKey: String
) : SpeechRecognizerEngine {
    
    companion object {
        private const val API_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        private const val MODEL = "whisper-1"
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)  // Longer timeout for audio processing
        .build()
    
    private var partialCallback: ((String) -> Unit)? = null
    private var statusCallback: ((RunState) -> Unit)? = null
    
    override suspend fun transcribe(
        samples: FloatArray,
        glossary: String,
        forceLanguage: String?,
        decodingMode: DecodingMode
    ): String = withContext(Dispatchers.IO) {
        statusCallback?.invoke(RunState.ProcessingEncoder)
        
        // Convert float samples to WAV format
        val wavData = createWavFile(floatArrayToPcm16(samples), 16000)
        
        // Build multipart request
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart(
                "file", 
                "audio.wav",
                wavData.toRequestBody("audio/wav".toMediaType())
            )
            .apply {
                // Add language hint if specified
                if (forceLanguage != null) {
                    addFormDataPart("language", forceLanguage)
                }
                
                // Add prompt for glossary/vocabulary biasing
                if (glossary.isNotBlank()) {
                    // OpenAI Whisper API uses "prompt" parameter for context
                    // Including domain-specific terms helps with accuracy
                    addFormDataPart("prompt", glossary)
                }
                
                // Response format - we want verbose JSON for timestamps if needed
                addFormDataPart("response_format", "json")
                
                // Temperature for sampling (0 = deterministic, like greedy decoding)
                val temperature = if (decodingMode == DecodingMode.Greedy) "0" else "0.2"
                addFormDataPart("temperature", temperature)
            }
            .build()
        
        val request = Request.Builder()
            .url(API_ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
        
        statusCallback?.invoke(RunState.StartedDecoding)
        
        return@withContext try {
            val response = httpClient.newCall(request).execute()
            
            when (response.code) {
                200 -> {
                    val responseBody = response.body?.string() ?: ""
                    parseOpenAIResponse(responseBody)
                }
                401 -> throw IOException("Invalid API key")
                429 -> throw IOException("Rate limit exceeded")
                413 -> throw IOException("Audio file too large (max 25MB)")
                else -> {
                    val errorBody = response.body?.string() ?: ""
                    throw IOException("OpenAI API error ${response.code}: $errorBody")
                }
            }
        } catch (e: Exception) {
            throw IOException("Failed to transcribe with OpenAI: ${e.message}", e)
        }
    }
    
    /**
     * Parse OpenAI's response format
     */
    private fun parseOpenAIResponse(response: String): String {
        return try {
            val json = JSONObject(response)
            
            // Get the transcribed text
            val text = json.optString("text", "")
            
            // Optional: parse segments for word-level timestamps if needed
            if (json.has("segments")) {
                // Could emit partial results based on segments
                // but OpenAI doesn't stream, so we just return the full text
            }
            
            text.trim()
        } catch (e: Exception) {
            throw IOException("Failed to parse OpenAI response: ${e.message}")
        }
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
     * Create a WAV file with proper headers
     */
    private fun createWavFile(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2 // 16-bit mono
        
        // RIFF header
        outputStream.write("RIFF".toByteArray())
        outputStream.write(intToByteArray(totalDataLen))
        outputStream.write("WAVE".toByteArray())
        
        // fmt chunk
        outputStream.write("fmt ".toByteArray())
        outputStream.write(intToByteArray(16)) // Sub chunk size
        outputStream.write(shortToByteArray(1)) // Audio format (PCM)
        outputStream.write(shortToByteArray(1)) // Channels (mono)
        outputStream.write(intToByteArray(sampleRate))
        outputStream.write(intToByteArray(byteRate))
        outputStream.write(shortToByteArray(2)) // Block align
        outputStream.write(shortToByteArray(16)) // Bits per sample
        
        // data chunk
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
    
    override suspend fun close() {
        // Nothing to clean up for REST API
    }
    
    override fun setPartialResultCallback(callback: (String) -> Unit) {
        partialCallback = callback
        // Note: OpenAI API doesn't support streaming, so partials aren't available
    }
    
    override fun setStatusCallback(callback: (RunState) -> Unit) {
        statusCallback = callback
    }
}
