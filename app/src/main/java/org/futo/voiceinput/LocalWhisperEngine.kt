package org.futo.voiceinput

import android.content.Context
import org.futo.voiceinput.ggml.DecodingMode
import org.futo.voiceinput.ml.RunState
import org.futo.voiceinput.ml.WhisperModelWrapper
import org.futo.voiceinput.settings.DISALLOW_SYMBOLS
import org.futo.voiceinput.settings.LANGUAGE_TOGGLES
import org.futo.voiceinput.settings.USE_LANGUAGE_SPECIFIC_MODELS
import org.futo.voiceinput.settings.getSetting

/**
 * Local Whisper implementation of SpeechRecognizerEngine.
 * This wraps the existing WhisperModelWrapper to maintain compatibility.
 */
class LocalWhisperEngine(
    private val context: Context,
    private val primaryModel: ModelData,
    private val fallbackModel: ModelData?
) : SpeechRecognizerEngine {
    
    private var model: WhisperModelWrapper? = null
    private var partialCallback: ((String) -> Unit)? = null
    private var statusCallback: ((RunState) -> Unit)? = null
    
    /**
     * Initialize the model lazily when first transcription is requested
     */
    private suspend fun ensureModelLoaded() {
        if (model == null) {
            val secondaryModel = if(context.getSetting(USE_LANGUAGE_SPECIFIC_MODELS)) { 
                fallbackModel 
            } else { 
                null 
            }
            
            model = WhisperModelWrapper(
                context = context,
                primaryModel = primaryModel,
                fallbackEnglishModel = secondaryModel,
                suppressNonSpeech = context.getSetting(DISALLOW_SYMBOLS),
                languages = context.getSetting(LANGUAGE_TOGGLES),
                onStatusUpdate = { status ->
                    statusCallback?.invoke(status)
                },
                onPartialDecode = { partial ->
                    partialCallback?.invoke(partial)
                }
            )
        }
    }
    
    override suspend fun transcribe(
        samples: FloatArray,
        glossary: String,
        forceLanguage: String?,
        decodingMode: DecodingMode
    ): String {
        ensureModelLoaded()
        
        // Clean up glossary formatting as in original
        val glossaryCleaned = glossary.trim().replace("\n", ", ").replace("  ", " ")
        val prompt = if(glossary.isBlank()) "" else "(Glossary: ${glossaryCleaned})"
        
        return model!!.run(samples, prompt, forceLanguage, decodingMode)
    }
    
    override suspend fun close() {
        model?.close()
        model = null
    }
    
    override fun setPartialResultCallback(callback: (String) -> Unit) {
        partialCallback = callback
    }
    
    override fun setStatusCallback(callback: (RunState) -> Unit) {
        statusCallback = callback
    }
}
