package org.futo.voiceinput

import org.futo.voiceinput.ggml.DecodingMode
import org.futo.voiceinput.ml.RunState

/**
 * Common interface for speech recognition engines.
 * Implementations can be local (Whisper) or cloud-based.
 */
interface SpeechRecognizerEngine {
    /**
     * Process audio samples and return transcribed text.
     * 
     * @param samples Float array of audio samples, normalized to [-1, 1], 16kHz mono
     * @param glossary User-specific vocabulary/prompts for biasing
     * @param forceLanguage If set, force recognition in this language (ISO code like "en")
     * @param decodingMode Greedy vs beam search (may be ignored by cloud implementations)
     * @return Transcribed text
     */
    suspend fun transcribe(
        samples: FloatArray,
        glossary: String,
        forceLanguage: String?,
        decodingMode: DecodingMode
    ): String

    /**
     * Release any resources held by this engine
     */
    suspend fun close()

    /**
     * Set callback for partial recognition results during processing
     */
    fun setPartialResultCallback(callback: (String) -> Unit)

    /**
     * Set callback for status updates during processing
     */
    fun setStatusCallback(callback: (RunState) -> Unit)
}
