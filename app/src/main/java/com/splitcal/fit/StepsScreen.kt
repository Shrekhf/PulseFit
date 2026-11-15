package com.splitcal.fit

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val StepBg = Color(0xFF0B1120)
private val StepCard = Color(0xFF151E2E)
private val StepGreen = Color(0xFF10B981)
private val StepCyan = Color(0xFF06B6D4)
private val StepMuted = Color(0xFF94A3B8)
private val StepWhite = Color(0xFFF8FAFC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StepsScreen(
    dashboard: SplitCalUiState,
    vm: StepsViewModel,
    onConnectHealthConnect: () -> Unit,
    onEnableSensor: () -> Unit,
    onSamsungHealthSync: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var manual by remember(dashboard.steps) { mutableFloatStateOf(dashboard.steps.toFloat()) }
    var guideOpen by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(vm::importExport) }
    LaunchedEffect(Unit) { vm.refresh() }
    val steps = state.snapshot?.steps ?: dashboard.steps
    val mode = state.calibrationMode.ifBlank { dashboard.settings.calibrationMode }
    val actualActive = state.snapshot?.activeCalories
    val selectedBurn = StepBurnCalculator.calibrated(steps, mode, actualActive)
    val personalBurn = StepBurnCalculator.personal(steps)
    val rawBurn = actualActive ?: StepBurnCalculator.rawReference(steps)
    val delta = (rawBurn - personalBurn).coerceAtLeast(0)
    LazyColumn(Modifier.fillMaxSize().background(StepBg).testTag("steps_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Column(Modifier.testTag("steps_heading")) { Text("HEALTH STEPS", color = StepCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp); Text("Samsung Health & daily movement", color = StepWhite, fontSize = 21.sp, fontWeight = FontWeight.Bold) } }
        item {
            StepCard {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.DirectionsWalk, null, tint = StepCyan); Spacer(Modifier.width(8.dp)); Text("${steps.prettySteps()} / ${dashboard.settings.stepGoal.prettySteps()} steps", color = StepWhite, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold) }
                LinearProgressIndicator(progress = { (steps.toFloat() / dashboard.settings.stepGoal.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = StepGreen, trackColor = Color(0xFF263346))
                Text("${state.status} · ${if (state.isLoading) "Refreshing…" else "${selectedBurn} kcal active burn"}", color = StepMuted, fontSize = 10.sp)
                Button(onClick = onConnectHealthConnect, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = StepGreen, contentColor = StepBg)) { Icon(Icons.Default.HealthAndSafety, null); Spacer(Modifier.width(6.dp)); Text("Connect / permissions") }
                if (state.sdkAvailable) OutlinedButton(onClick = { (context as? android.app.Activity)?.let(vm::requestSamsungPermissions) }, modifier = Modifier.fillMaxWidth()) { Text("Connect Samsung Health Data SDK", fontSize = 10.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedButton(onClick = onSamsungHealthSync, modifier = Modifier.weight(1f).testTag("sync_samsung_health")) { Icon(Icons.Default.Sync, null); Text("Sync Samsung Health") }
                    OutlinedButton(onClick = { context.packageManager.getLaunchIntentForPackage(SamsungHealthRepository.SAMSUNG_HEALTH_PACKAGE)?.let(context::startActivity) ?: (context as? android.app.Activity)?.let { guideOpen = true } }, modifier = Modifier.weight(1f)) { Text("Open Samsung Health", fontSize = 10.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedButton(onClick = onEnableSensor, modifier = Modifier.weight(1f)) { Text("Enable phone sensor", fontSize = 10.sp) }
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/*")) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FileOpen, null); Text("Import CSV / JSON", fontSize = 10.sp) }
                }
                TextButton(onClick = { guideOpen = true }, modifier = Modifier.align(Alignment.Start)) { Text("Samsung Health setup guide", color = StepCyan, fontSize = 10.sp) }
            }
        }
        item {
            StepCard {
                Text("SAMSUNG HEALTH VS. CALIBRATED BURN", color = StepWhite, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                DataLine("Synced Samsung Health steps", "${steps.prettySteps()} / 15,000")
                DataLine("Calibrated active burn · 500 / 15k", "$personalBurn kcal", modifier = Modifier.testTag("calibrated_burn"))
                DataLine("Raw wearable reported burn", "$rawBurn kcal${if (actualActive == null) " · estimate" else ""}")
                Text("+$delta kcal discrepancy prevented from inflating your daily calorie budget", color = StepGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                state.snapshot?.totalCalories?.let { Text("Health Connect total calories (includes resting energy): $it kcal", color = StepMuted, fontSize = 9.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(StepBurnCalculator.PERSONAL_MODE to "Personal 500", StepBurnCalculator.WEARABLE_MODE to "Wearable raw", StepBurnCalculator.CONSERVATIVE_MODE to "Conservative 420").forEach { (id, label) ->
                        FilterChip(selected = mode == id, onClick = { vm.setCalibrationMode(id) }, label = { Text(label, fontSize = 9.sp) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Include all Health Connect origins", color = StepWhite, fontSize = 10.sp); Text("Off prioritizes Samsung Health records", color = StepMuted, fontSize = 9.sp) }
                    Switch(checked = state.includeAllOrigins, onCheckedChange = vm::setIncludeAllOrigins)
                }
            }
        }
        item {
            StepCard {
                Text("EMULATOR & MANUAL FALLBACK", color = StepWhite, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text("${manual.toInt().prettySteps()} steps", color = StepCyan, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Slider(value = manual, onValueChange = { manual = it }, valueRange = 0f..30_000f, steps = 299, colors = SliderDefaults.colors(thumbColor = StepGreen, activeTrackColor = StepGreen))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { vm.addSteps(1800, steps) }, modifier = Modifier.weight(1f)) { Text("+1,800 steps", fontSize = 10.sp) }
                    OutlinedButton(onClick = vm::setBenchmark, modifier = Modifier.weight(1f).testTag("set_15000_benchmark")) { Text("Set 15,000 Benchmark", fontSize = 9.sp) }
                }
                Button(onClick = { vm.setSteps(manual.toInt()) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = StepGreen, contentColor = StepBg)) { Text("Save manual step count") }
            }
        }
        item {
            StepCard {
                Text("SAMSUNG HEALTH RECOVERY & READINESS", color = StepWhite, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                DataLine("Last-night sleep", state.recovery?.sleepMinutes?.let { "${it / 60}h ${it % 60}m" } ?: "No permitted sleep record")
                DataLine("Average heart rate", state.recovery?.restingHeartRate?.let { "$it bpm" } ?: "No permitted heart-rate record")
                state.recovery?.let { recovery ->
                    Text(recovery.autoRegulationTip ?: "Recovery context is ready for the gym planner.", color = StepGreen, fontSize = 11.sp)
                }
                Text("Samsung SDK adapter: ${if (state.sdkAvailable) "available" else "unavailable on this device; Health Connect is active primary"}", color = StepMuted, fontSize = 9.sp)
                state.snapshot?.stepGoal?.let { DataLine("Samsung Health step goal", "$it steps") }
                state.snapshot?.activeCaloriesGoal?.let { DataLine("Samsung Health active calorie goal", "$it kcal") }
                state.snapshot?.energyScore?.let { DataLine("Samsung Health Energy Score", "$it") }
            }
        }
    }
    if (guideOpen) ModalBottomSheet(onDismissRequest = { guideOpen = false }, containerColor = StepCard) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Samsung Health Setup Guide", color = StepWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Recommended · enable Health Connect sync", color = StepGreen, fontWeight = FontWeight.Bold)
            Text("1. Open Samsung Health.\n2. Go to Settings > Health Connect.\n3. Enable data sync for Steps, Sleep, Heart rate, Exercise, and Calories.\n4. Return here and tap Connect / permissions.", color = StepWhite, fontSize = 12.sp)
            Text("Direct SDK development mode (optional)", color = StepCyan, fontWeight = FontWeight.Bold)
            Text("In Samsung Health: Settings > About Samsung Health > tap the version 10 times to enable Developer Mode. The direct Samsung Data SDK adapter requires Samsung's separate SDK AAR and its own user permissions.", color = StepMuted, fontSize = 11.sp)
            Button(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${SamsungHealthRepository.SAMSUNG_HEALTH_PACKAGE}"))) }; guideOpen = false }, modifier = Modifier.fillMaxWidth()) { Text("Open Samsung Health app settings") }
        }
    }
}

@Composable private fun StepCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(StepCard, RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
}
@Composable private fun DataLine(label: String, value: String, modifier: Modifier = Modifier) { Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = StepMuted, fontSize = 10.sp); Text(value, color = StepWhite, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) } }
private fun Int.prettySteps() = toString().reversed().chunked(3).joinToString(",").reversed()
