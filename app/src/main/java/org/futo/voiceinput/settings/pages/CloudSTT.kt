package org.futo.voiceinput.settings.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.futo.voiceinput.R
import org.futo.voiceinput.settings.*

@Composable
fun CloudSTTScreen(viewModel: SettingsViewModel = viewModel()) {
    val useCloudSTT by viewModel.useDataStore(USE_CLOUD_STT)
    val endpoint by viewModel.useDataStore(CLOUD_STT_ENDPOINT)
    val apiKey by viewModel.useDataStore(CLOUD_STT_API_KEY)
    val provider by viewModel.useDataStore(CLOUD_STT_PROVIDER)
    
    var showApiKey by remember { mutableStateOf(false) }
    
    ScrollableList {
        ScreenTitle("Cloud Speech Recognition")
        
        // Toggle for cloud vs local
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Use Cloud STT",
                style = MaterialTheme.typography.bodyLarge
            )
            Switch(
                checked = useCloudSTT.value,
                onCheckedChange = { viewModel.setValue(USE_CLOUD_STT, it) }
            )
        }
        
        if (useCloudSTT.value) {
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Provider selection
            Text(
                "Provider",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            val providers = listOf(
                "openai" to "OpenAI Whisper API",
                "google" to "Google Cloud Speech",
                "azure" to "Azure Speech Services",
                "aws" to "AWS Transcribe",
                "custom" to "Custom Endpoint"
            )
            
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                providers.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = provider.value == value,
                            onClick = { 
                                viewModel.setValue(CLOUD_STT_PROVIDER, value)
                                // Set default endpoint based on provider
                                when(value) {
                                    "openai" -> viewModel.setValue(
                                        CLOUD_STT_ENDPOINT, 
                                        "https://api.openai.com/v1/audio/transcriptions"
                                    )
                                    "google" -> viewModel.setValue(
                                        CLOUD_STT_ENDPOINT,
                                        "https://speech.googleapis.com/v1/speech:recognize"
                                    )
                                    "azure" -> viewModel.setValue(
                                        CLOUD_STT_ENDPOINT,
                                        "https://YOUR-REGION.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1"
                                    )
                                    "aws" -> viewModel.setValue(
                                        CLOUD_STT_ENDPOINT,
                                        "wss://transcribe-streaming.YOUR-REGION.amazonaws.com/stream-transcription-websocket"
                                    )
                                }
                            }
                        )
                        Text(
                            label,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Endpoint configuration
            OutlinedTextField(
                value = endpoint.value,
                onValueChange = { viewModel.setValue(CLOUD_STT_ENDPOINT, it) },
                label = { Text("API Endpoint") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = { Text("https://api.example.com/v1/transcribe") }
            )
            
            // API Key configuration
            OutlinedTextField(
                value = apiKey.value,
                onValueChange = { viewModel.setValue(CLOUD_STT_API_KEY, it) },
                label = { Text("API Key") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                visualTransformation = if (showApiKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Text(if (showApiKey) "Hide" else "Show")
                    }
                },
                placeholder = { Text("sk-...") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            
            // Info/warning about cloud usage
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Cloud STT Information",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "• Audio will be sent to the cloud provider\n" +
                        "• Requires internet connection\n" +
                        "• May incur API usage costs\n" +
                        "• Provider's privacy policy applies",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            
            // Test connection button
            if (endpoint.value.isNotBlank() && apiKey.value.isNotBlank()) {
                Button(
                    onClick = { 
                        // TODO: Implement connection test
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Test Connection")
                }
            }
        } else {
            // Show local model info when cloud is disabled
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Local Processing",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "• All processing happens on-device\n" +
                        "• No internet required\n" +
                        "• Your audio never leaves your device\n" +
                        "• Using OpenAI Whisper models",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}
