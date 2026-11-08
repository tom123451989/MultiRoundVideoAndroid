package com.example.multiroundvideo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal val stopRequested = AtomicBoolean(false)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppScreen(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(context: Context) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("No video selected") }
    var speedText by remember { mutableStateOf("1000") }
    var joinsText by remember { mutableStateOf("2") }
    var repeatsText by remember { mutableStateOf("2") }
    var audioEnabled by remember { mutableStateOf(true) }
    var boostText by remember { mutableStateOf("150") }
    var intelliEnabled by remember { mutableStateOf(false) }
    var finalMatchEnabled by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val isRunning by VideoProcessingService.isRunning.collectAsState()
    val status by VideoProcessingService.status.collectAsState()
    val details by VideoProcessingService.details.collectAsState()
    val progress by VideoProcessingService.progress.collectAsState()
    val etaText by VideoProcessingService.etaText.collectAsState()
    val generatedFile by VideoProcessingService.generatedFile.collectAsState()
    val activeEncoder by VideoProcessingService.activeEncoder.collectAsState()

    val preferences = remember(context) { context.getSharedPreferences("multi_round_video", Context.MODE_PRIVATE) }
    val pendingOutputOnLaunch = remember(preferences) {
        preferences.getString("pending_output", null)?.let { path -> File(path).takeIf { it.exists() } }
    }

    DisposableEffect(Unit) {
        if (pendingOutputOnLaunch != null && generatedFile == null) {
            VideoProcessingService.setGeneratedFile(pendingOutputOnLaunch)
        }
        onDispose {}
    }

    val scope = rememberCoroutineScope()
    val activity = context as? Activity

    DisposableEffect(isRunning) {
        if (isRunning) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val speedValue = speedText.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    val joinValue = joinsText.toIntOrNull()?.takeIf { it >= 0 }
    val repeatValue = repeatsText.toIntOrNull()?.takeIf { it >= 0 }
    val joinConfigurationValid = joinValue != null && (joinValue >= 1 || repeatValue == 0)
    val boostValue = boostText.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    val effective = if (joinValue != null && repeatValue != null) {
        formatRepetitionMultiplier(joinValue, repeatValue)
    } else "—"
    val approximateCopies = if (joinValue != null && repeatValue != null) {
        formatApproximateTotalCopies(joinValue, repeatValue)
    } else "—"

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedName = queryDisplayName(context, uri) ?: "Selected video"
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
    }

    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { destination ->
        val sourceFile = generatedFile
        if (destination != null && sourceFile != null) {
            scope.launch {
                isSaving = true
                try {
                    withContext(Dispatchers.IO) {
                        val outputStream = context.contentResolver.openOutputStream(destination, "w")
                            ?: throw IOException("Android could not open the selected save location.")
                        outputStream.use { out -> sourceFile.inputStream().use { input -> input.copyTo(out) } }
                    }
                    sourceFile.delete()
                    VideoProcessingService.setGeneratedFile(null)
                    preferences.edit().remove("pending_output").apply()
                } catch (_: Exception) {
                } finally {
                    isSaving = false
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF9B7BFF),
            secondary = Color(0xFF51D8E8),
            background = Color(0xFF080C1B),
            surface = Color(0xFF151B2D),
            onSurface = Color(0xFFF2F3FF)
        )
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("MULTI-ROUND VIDEO V5", fontWeight = FontWeight.Bold)
                        Text("FAST STREAM COPY  •  INTELLI MODE  •  EXACT MATCH", style = MaterialTheme.typography.labelSmall)
                    }
                })
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(Icons.Default.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Source video", fontWeight = FontWeight.SemiBold)
                                Text(selectedName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(onClick = { videoPicker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !isRunning && !isSaving) {
                            Text("Choose video")
                        }
                    }
                }

                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Processing settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        NumberField("Speed-up factor (base)", speedText, { speedText = it }, "e.g. 1000", KeyboardType.Decimal, enabled = !isRunning && !isSaving && !intelliEnabled)
                        NumberField("Join count per round (untouched)", joinsText, { joinsText = it }, "e.g. 2", KeyboardType.Number, enabled = !isRunning && !isSaving)
                        NumberField("Full repeat rounds", repeatsText, { repeatsText = it }, "e.g. 2", KeyboardType.Number, enabled = !isRunning && !isSaving)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Intelli Mode", fontWeight = FontWeight.Medium)
                                Text("Auto-adjusts speed per round to match duration & size (joins untouched)", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = intelliEnabled, onCheckedChange = { intelliEnabled = it }, enabled = !isRunning && !isSaving)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Final Exact Match", fontWeight = FontWeight.Medium)
                                Text("Speeds up final output after all repeats to match original 30s duration", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = finalMatchEnabled, onCheckedChange = { finalMatchEnabled = it }, enabled = !isRunning && !isSaving)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Include audio (Safeguarded)", fontWeight = FontWeight.Medium)
                                Text("Guarantees hearable, synced audio after every full repeat", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = audioEnabled, onCheckedChange = { audioEnabled = it }, enabled = !isRunning && !isSaving)
                        }
                        if (audioEnabled) {
                            NumberField("Audio boost (%)", boostText, { boostText = it }, "e.g. 150", KeyboardType.Decimal, enabled = !isRunning && !isSaving)
                        }
                    }
                }

                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Repetition summary", fontWeight = FontWeight.Bold)
                        Text(effective, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.secondary)
                        Text("Effective multiplier = join count ^ full repeat rounds", style = MaterialTheme.typography.bodySmall)
                        Text("Approximate total copies: $approximateCopies", style = MaterialTheme.typography.bodySmall)
                        Text("Current encoder: $activeEncoder", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(status, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            if (isRunning) {
                                Text(etaText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(details, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                enabled = !isRunning && !isSaving && generatedFile == null && selectedUri != null && speedValue != null && joinConfigurationValid && repeatValue != null && (!audioEnabled || boostValue != null),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val uri = selectedUri ?: return@Button
                                    val speed = speedValue ?: return@Button
                                    val joinCount = joinValue ?: return@Button
                                    val roundCount = repeatValue ?: return@Button
                                    val audioBoost = if (audioEnabled) (boostValue ?: return@Button) else 100.0

                                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }

                                    VideoProcessingService.startService(
                                        context = context,
                                        uri = uri,
                                        displayName = selectedName,
                                        speed = speed,
                                        joins = joinCount,
                                        repeats = roundCount,
                                        includeAudio = audioEnabled,
                                        audioBoost = audioBoost,
                                        intelliEnabled = intelliEnabled,
                                        finalMatchEnabled = finalMatchEnabled
                                    )
                                }
                            ) {
                                androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Start V5")
                            }
                            OutlinedButton(
                                enabled = isRunning,
                                onClick = {
                                    VideoProcessingService.stopService(context)
                                }
                            ) {
                                androidx.compose.material3.Icon(Icons.Default.Stop, contentDescription = null)
                                Text("Stop")
                            }
                        }
                        val fileToSave = generatedFile
                        if (fileToSave != null) {
                            Button(
                                onClick = { savePicker.launch(fileToSave.name) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isRunning && !isSaving
                            ) {
                                androidx.compose.material3.Icon(Icons.Default.SaveAlt, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (isSaving) "Saving…" else "Save output")
                            }
                            Text("Output is retained in app storage until saved or discarded.", style = MaterialTheme.typography.bodySmall)
                            TextButton(
                                onClick = {
                                    val deleted = fileToSave.delete() || !fileToSave.exists()
                                    if (deleted) {
                                        VideoProcessingService.setGeneratedFile(null)
                                        preferences.edit().remove("pending_output").apply()
                                    }
                                },
                                enabled = !isSaving
                            ) {
                                Text("Discard output")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
    )
}

internal data class EncoderPlan(
    val preferred: String,
    val fallbacks: List<String>
)

private fun queryDisplayName(context: Context, uri: Uri): String? {
    var result: String? = null
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) result = cursor.getString(0)
    }
    return result
}

internal suspend fun processVideo(
    context: Context,
    uri: Uri,
    displayName: String,
    speed: Double,
    joins: Int,
    includeAudio: Boolean,
    audioBoost: Double,
    repeats: Int,
    intelliEnabled: Boolean = false,
    finalMatchEnabled: Boolean = false,
    update: (Float, String, String) -> Unit
): File = withContext(Dispatchers.IO) {
    val outputDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        ?: File(context.filesDir, "Movies")
    if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
        throw IOException("Cannot create the app's Movies output directory.")
    }
    recoverInterruptedWork(outputDirectory)
    if (outputDirectory.usableSpace < 32L * 1024L * 1024L) {
        throw IOException("Less than 32 MB of free storage is available. Free space and try again.")
    }

    val nameCandidate = displayName.ifBlank { "input_video.mp4" }
        .replace('\\', '/')
        .substringAfterLast('/')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    val safeDisplayName = nameCandidate.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "input_video.mp4"
    val baseName = safeDisplayName.substringBeforeLast('.', safeDisplayName).ifBlank { "video" }
    val workDirectory = File(outputDirectory, ".processing_${System.currentTimeMillis()}")
    if (!workDirectory.mkdirs()) throw IOException("Cannot create temporary processing folder.")
    val inputFile = File(workDirectory, safeDisplayName)
    val finalFile = File(outputDirectory, "${baseName}_final_${repeats}x.mp4")
    var complete = false
    var backupFile: File? = null
    var newFinalInstalled = false

    try {
        update(0.01f, "Preparing source", "Copying selected video into workspace…")
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Android could not read the selected video.")
        inputStream.use { source -> inputFile.outputStream().use { target -> source.copyTo(target) } }
        if (!inputFile.exists() || inputFile.length() == 0L) throw IOException("Selected video is empty or could not be copied.")
        if (stopRequested.get()) throw CancellationException("Processing stopped by user.")

        // Probe original video duration and file size once
        val retriever = MediaMetadataRetriever()
        var originalDurationSec = 0.0
        val originalSizeBytes = inputFile.length()
        try {
            retriever.setDataSource(inputFile.absolutePath)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (durStr != null) {
                originalDurationSec = (durStr.toDoubleOrNull() ?: 0.0) / 1000.0
            }
        } catch (_: Throwable) {
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
        if (originalDurationSec <= 0.0) {
            originalDurationSec = 30.0
        }

        val intelliTargetBitrate = if (intelliEnabled) calculateTargetBitrate(originalSizeBytes, originalDurationSec) else null

        var currentInput = inputFile
        var activeEncoder = "copy"

        if (repeats > 0) {
            val encoderPlan = detectEncoderPlan()
            activeEncoder = encoderPlan.preferred
            update(0.02f, "Encoder selected", "Preferred encoder: $activeEncoder.")

            for (round in 1..repeats) {
                if (stopRequested.get()) throw CancellationException("Processing stopped by user.")
                val roundStartProgress = (round - 1).toFloat() / repeats
                val roundMidProgress = (round - 0.5f).toFloat() / repeats
                val roundEndProgress = round.toFloat() / repeats

                var currentInputDurationSec = originalDurationSec
                val roundRetriever = MediaMetadataRetriever()
                try {
                    roundRetriever.setDataSource(currentInput.absolutePath)
                    val dStr = roundRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    if (dStr != null) {
                        val dSec = (dStr.toDoubleOrNull() ?: 0.0) / 1000.0
                        if (dSec > 0.0) currentInputDurationSec = dSec
                    }
                } catch (_: Throwable) {
                } finally {
                    try { roundRetriever.release() } catch (_: Throwable) {}
                }

                val effectiveRoundSpeed = if (intelliEnabled) {
                    calculateIntelliSpeed(currentInputDurationSec, joins, originalDurationSec)
                } else {
                    speed
                }

                // Process audio on the final round to avoid accumulating AAC header corruptions in intermediate steps
                val isFinalRound = (round == repeats)
                val shouldProcessAudio = includeAudio && isFinalRound
                val audioFilter = if (shouldProcessAudio) buildAtempoFilter(effectiveRoundSpeed, audioBoost) else null

                val roundSpeedLabel = if (intelliEnabled) {
                    "Intelli speed ${String.format(Locale.US, "%.1fx", effectiveRoundSpeed)} (joins: $joins untouched)…"
                } else {
                    "Applying video speed factor $speed…"
                }

                update(roundStartProgress, "Round $round/$repeats — speed-up", roundSpeedLabel)
                val processedFile = File(workDirectory, "processed_round$round.mp4")

                activeEncoder = executeEncodeWithFallback(
                    plan = encoderPlan,
                    currentEncoder = activeEncoder,
                    commandFactory = { encoder -> buildSpeedCommand(currentInput, processedFile, effectiveRoundSpeed, audioFilter, shouldProcessAudio, encoder, intelliTargetBitrate) },
                    onFallback = { fallbackMessage -> update(roundStartProgress, "Switching encoder", fallbackMessage) },
                    onStatistics = { stats ->
                        val localProg = (stats.time / 1000.0).toFloat().coerceIn(0f, 1f) * 0.5f
                        val currentGlobalProg = (roundStartProgress + (localProg / repeats)).coerceIn(0f, 0.99f)
                        update(currentGlobalProg, "Round $round/$repeats — speed-up", roundSpeedLabel)
                    }
                )

                if (!processedFile.exists() || processedFile.length() == 0L) {
                    throw IOException("Round $round speed-up produced an empty output file.")
                }

                // Immediate cleanup of source input after speed-up
                if (currentInput != inputFile) {
                    currentInput.delete()
                }

                if (stopRequested.get()) throw CancellationException("Processing stopped by user.")
                ensureFreeSpaceForConcat(outputDirectory, processedFile, joins)
                update(roundMidProgress, "Round $round/$repeats — joining", "Stream-copy joining $joins copies instantly…")
                val concatList = File(workDirectory, "concat_list_round$round.txt")
                val concatEntry = "file '${processedFile.absolutePath.replace("'", "'\\''")}'"
                concatList.bufferedWriter().use { writer ->
                    repeat(joins) {
                        if (stopRequested.get()) throw CancellationException("Processing stopped by user.")
                        writer.append(concatEntry)
                        writer.newLine()
                    }
                }
                val joinedFile = File(workDirectory, "joined_round$round.mp4")
                activeEncoder = executeEncodeWithFallback(
                    plan = encoderPlan,
                    currentEncoder = activeEncoder,
                    commandFactory = { encoder -> buildConcatCommand(concatList, joinedFile, shouldProcessAudio, encoder, intelliTargetBitrate) },
                    onFallback = { fallbackMessage -> update(roundMidProgress, "Switching encoder", fallbackMessage) },
                    onStatistics = { stats ->
                        val localProg = (stats.time / 1000.0).toFloat().coerceIn(0f, 1f) * 0.5f
                        val currentGlobalProg = (roundMidProgress + (localProg / repeats)).coerceIn(0f, 0.99f)
                        update(currentGlobalProg, "Round $round/$repeats — joining", "Joining $joins copies…")
                    }
                )

                if (!joinedFile.exists() || joinedFile.length() == 0L) {
                    throw IOException("Round $round join produced an empty output file.")
                }

                // Immediate per-round intermediate file cleanup to free disk space & RAM
                processedFile.delete()
                concatList.delete()

                currentInput = joinedFile
                update(roundEndProgress, "Round $round/$repeats complete", "Current encoder: $activeEncoder")

                // Remove any old intermediate files left over from prior rounds
                workDirectory.listFiles()?.forEach { file ->
                    if (file != currentInput && file != inputFile && file.exists()) {
                        file.delete()
                    }
                }
            }

            // Final Post-Processing Exact Duration Match Pass (if enabled)
            if (finalMatchEnabled) {
                if (stopRequested.get()) throw CancellationException("Processing stopped by user.")
                var finalDurationSec = 0.0
                val finalRetriever = MediaMetadataRetriever()
                try {
                    finalRetriever.setDataSource(currentInput.absolutePath)
                    val dStr = finalRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    if (dStr != null) {
                        finalDurationSec = (dStr.toDoubleOrNull() ?: 0.0) / 1000.0
                    }
                } catch (_: Throwable) {
                } finally {
                    try { finalRetriever.release() } catch (_: Throwable) {}
                }

                if (finalDurationSec > 0.0 && originalDurationSec > 0.0 && Math.abs(finalDurationSec - originalDurationSec) > 0.5) {
                    val finalRequiredSpeed = finalDurationSec / originalDurationSec
                    update(0.95f, "Final Exact Match", "Matching duration ${String.format(Locale.US, "%.1fs", finalDurationSec)} -> ${String.format(Locale.US, "%.1fs", originalDurationSec)} (${String.format(Locale.US, "%.1fx", finalRequiredSpeed)} speed-up)…")

                    val finalMatchedFile = File(workDirectory, "final_matched.mp4")
                    val finalAudioFilter = if (includeAudio) buildAtempoFilter(finalRequiredSpeed, audioBoost) else null

                    activeEncoder = executeEncodeWithFallback(
                        plan = encoderPlan,
                        currentEncoder = activeEncoder,
                        commandFactory = { encoder -> buildSpeedCommand(currentInput, finalMatchedFile, finalRequiredSpeed, finalAudioFilter, includeAudio, encoder, intelliTargetBitrate) },
                        onFallback = { fallbackMessage -> update(0.95f, "Switching encoder", fallbackMessage) }
                    )

                    if (finalMatchedFile.exists() && finalMatchedFile.length() > 0L) {
                        currentInput.delete()
                        currentInput = finalMatchedFile
                    }
                }
            }

        } else {
            update(0.5f, "No processing rounds", "Full repeat rounds is 0; original video copied unchanged.")
        }

        if (stopRequested.get()) throw CancellationException("Processing stopped by user.")
        update(0.98f, "Finalizing output", "Preparing final MP4 in storage…")
        val stagedFinal = File(workDirectory, "${baseName}_final_${repeats}x.mp4")
        if (!currentInput.renameTo(stagedFinal)) {
            currentInput.copyTo(stagedFinal, overwrite = true)
        }
        if (!stagedFinal.exists() || stagedFinal.length() == 0L) {
            throw IOException("Final output file was not created correctly.")
        }
        if (stopRequested.get()) throw CancellationException("Processing stopped by user.")

        if (finalFile.exists()) {
            backupFile = File(outputDirectory, ".backup_${System.currentTimeMillis()}_${finalFile.name}")
            if (!finalFile.renameTo(backupFile!!)) {
                backupFile = null
                throw IOException("Could not safely replace previous output file.")
            }
        }
        if (!stagedFinal.renameTo(finalFile)) {
            try {
                stagedFinal.copyTo(finalFile, overwrite = true)
                stagedFinal.delete()
            } catch (copyError: Exception) {
                finalFile.delete()
                backupFile?.let { backup ->
                    if (backup.renameTo(finalFile)) backupFile = null
                }
                throw IOException("Could not move output into Movies folder.", copyError)
            }
        }
        newFinalInstalled = true
        if (!finalFile.exists() || finalFile.length() == 0L) {
            throw IOException("Final output file validation failed.")
        }
        complete = true
        backupFile?.delete()
        update(1f, "Complete", "Output: ${finalFile.name} • Encoder: $activeEncoder")
        finalFile
    } finally {
        workDirectory.deleteRecursively()
        if (!complete) {
            if (newFinalInstalled) finalFile.delete()
            backupFile?.let { backup -> if (backup.exists()) backup.renameTo(finalFile) }
        }
    }
}

private fun ensureFreeSpaceForConcat(outputDirectory: File, processedFile: File, joins: Int) {
    if (joins < 1) throw IOException("Join count must be at least 1 when processing rounds are enabled.")
    val processedBytes = processedFile.length()
    if (processedBytes <= 0L) throw IOException("Cannot estimate join storage: processed video is empty.")
    val estimatedBytes = if (processedBytes > Long.MAX_VALUE / joins.toLong()) {
        Long.MAX_VALUE
    } else {
        processedBytes * joins.toLong()
    }
    val safetyReserve = 16L * 1024L * 1024L
    val requiredBytes = if (estimatedBytes > Long.MAX_VALUE - safetyReserve) Long.MAX_VALUE else estimatedBytes + safetyReserve
    if (outputDirectory.usableSpace < requiredBytes) {
        throw IOException("Not enough free storage for this join count.")
    }
}

private fun recoverInterruptedWork(outputDirectory: File) {
    val entries = outputDirectory.listFiles() ?: return
    val backupPattern = Regex("^\\.backup_\\d+_(.+)$")
    for (entry in entries) {
        if (entry.name.startsWith(".processing_")) {
            entry.deleteRecursively()
        } else {
            val match = backupPattern.matchEntire(entry.name)
            if (match != null && entry.isFile) {
                val original = File(outputDirectory, match.groupValues[1])
                if (!original.exists()) entry.renameTo(original) else entry.delete()
            }
        }
    }
}

private suspend fun detectEncoderPlan(): EncoderPlan {
    val encoderText = executeFFmpeg(listOf("-hide_banner", "-encoders")).lowercase(Locale.US)
    val hasHardwareH264 = Regex("\\bh264_mediacodec\\b").containsMatchIn(encoderText)
    val hasMpeg4 = Regex("\\bmpeg4\\b").containsMatchIn(encoderText)
    val hasOpenH264 = Regex("\\blibopenh264\\b").containsMatchIn(encoderText)
    val softwareFallbacks = buildList {
        if (hasMpeg4) add("mpeg4")
        if (hasOpenH264) add("libopenh264")
    }
    return when {
        hasMpeg4 -> EncoderPlan("mpeg4", buildList {
            if (hasHardwareH264) add("h264_mediacodec")
            if (hasOpenH264) add("libopenh264")
        })
        hasHardwareH264 -> EncoderPlan("h264_mediacodec", buildList {
            if (hasOpenH264) add("libopenh264")
        })
        hasOpenH264 -> EncoderPlan("libopenh264", emptyList())
        else -> throw IOException("This FFmpeg build exposes no supported MP4 video encoder.")
    }
}

private suspend fun executeEncodeWithFallback(
    plan: EncoderPlan,
    currentEncoder: String,
    commandFactory: (String) -> List<String>,
    onFallback: (String) -> Unit,
    onStatistics: ((Statistics) -> Unit)? = null
): String {
    val encoderChain = buildList {
        add(currentEncoder)
        for (fallback in listOf(plan.preferred) + plan.fallbacks) {
            if (!contains(fallback)) add(fallback)
        }
    }
    var lastError: Throwable? = null
    for (i in encoderChain.indices) {
        if (stopRequested.get()) throw CancellationException("Processing stopped by user.")
        val encoder = encoderChain[i]
        try {
            executeFFmpeg(commandFactory(encoder), onStatistics)
            return encoder
        } catch (error: Throwable) {
            if (error is CancellationException || stopRequested.get()) {
                throw CancellationException("Processing stopped by user.")
            }
            lastError = error
            val nextEncoder = encoderChain.getOrNull(i + 1)
            if (nextEncoder != null) {
                onFallback("$encoder failed; retrying with $nextEncoder. ${error.message ?: ""}")
            }
        }
    }
    throw lastError ?: IOException("All video encoders failed.")
}

private fun buildSpeedCommand(
    input: File,
    output: File,
    speed: Double,
    audioFilter: String?,
    includeAudio: Boolean,
    encoder: String,
    intelliTargetBitrate: String? = null
): List<String> {
    val vf = if (encoder == "h264_mediacodec") "fps=30,setpts=N/(30*TB),format=nv12" else "fps=30,setpts=N/(30*TB)"
    val args = mutableListOf(
        "-y",
        "-fflags", "+genpts+discardcorrupt",
        "-i", input.absolutePath,
        "-filter:v", vf,
        "-avoid_negative_ts", "make_zero"
    )
    if (includeAudio && audioFilter != null) {
        args.addAll(listOf("-filter:a", audioFilter, "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-async", "1", "-shortest"))
    } else {
        args.add("-an")
    }
    args.addAll(videoEncoderArguments(encoder, intelliTargetBitrate))
    args.addAll(listOf("-movflags", "+faststart", output.absolutePath))
    return args
}

private fun buildConcatCommand(
    listFile: File,
    output: File,
    includeAudio: Boolean,
    encoder: String,
    intelliTargetBitrate: String? = null
): List<String> {
    val args = mutableListOf(
        "-y",
        "-fflags", "+genpts+discardcorrupt",
        "-f", "concat",
        "-safe", "0",
        "-i", listFile.absolutePath,
        "-c:v", "copy",
        "-avoid_negative_ts", "make_zero"
    )
    if (includeAudio) {
        args.addAll(listOf("-c:a", "copy"))
    } else {
        args.add("-an")
    }
    args.addAll(listOf("-movflags", "+faststart", output.absolutePath))
    return args
}

private fun videoEncoderArguments(encoder: String, intelliTargetBitrate: String? = null): List<String> {
    val bitrate = intelliTargetBitrate ?: "1500k"
    return when (encoder) {
        "h264_mediacodec" -> listOf(
            "-c:v", encoder,
            "-b:v", bitrate,
            "-pix_fmt", "nv12"
        )
        "mpeg4" -> if (intelliTargetBitrate != null) listOf(
            "-c:v", "mpeg4",
            "-b:v", bitrate,
            "-threads", "0"
        ) else listOf(
            "-c:v", "mpeg4",
            "-q:v", "5",
            "-threads", "0"
        )
        "libopenh264" -> listOf(
            "-c:v", "libopenh264",
            "-b:v", bitrate,
            "-threads", "0"
        )
        else -> listOf("-c:v", encoder, "-b:v", bitrate, "-threads", "0")
    }
}

private suspend fun executeFFmpeg(
    arguments: List<String>,
    onStatistics: ((Statistics) -> Unit)? = null
): String = suspendCancellableCoroutine { continuation ->
    try {
        val session = FFmpegKit.executeWithArgumentsAsync(
            arguments.toTypedArray(),
            { session ->
                if (!continuation.isActive) return@executeWithArgumentsAsync
                val output = buildString {
                    append(session.output.orEmpty())
                    append('\n')
                    append(session.allLogsAsString.orEmpty())
                }
                if (ReturnCode.isSuccess(session.returnCode)) {
                    runCatching { continuation.resume(output) }
                } else if (ReturnCode.isCancel(session.returnCode) || stopRequested.get()) {
                    runCatching { continuation.resumeWith(Result.failure(CancellationException("FFmpeg session cancelled by user."))) }
                } else {
                    val details = output.takeLast(3500).ifBlank { session.failStackTrace ?: "No FFmpeg diagnostics were returned." }
                    runCatching { continuation.resumeWith(Result.failure(IOException("FFmpeg failed (${session.returnCode}): $details"))) }
                }
            },
            null,
            { statistics ->
                onStatistics?.invoke(statistics)
            }
        )
        continuation.invokeOnCancellation {
            FFmpegKit.cancel(session.sessionId)
        }
    } catch (error: Throwable) {
        if (continuation.isActive) runCatching { continuation.resumeWith(Result.failure(error)) }
    }
}
