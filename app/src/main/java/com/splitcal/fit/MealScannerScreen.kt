package com.splitcal.fit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private val MealBg = Color(0xFF0B1120)
private val MealCard = Color(0xFF151E2E)
private val MealGreen = Color(0xFF10B981)
private val MealCyan = Color(0xFF06B6D4)
private val MealMuted = Color(0xFF94A3B8)
private val MealWhite = Color(0xFFF8FAFC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MealScannerScreen(dashboard: SplitCalUiState, vm: MealViewModel, onToast: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("Grilled chicken bowl with greens, rice, beans and sauce") }
    var title by remember(state.title) { mutableStateOf(state.title) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var slot by remember { mutableStateOf("Dinner") }
    LaunchedEffect(state.editingMealId, state.editingSlot) { if (state.editingMealId != null) slot = state.editingSlot ?: slot }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.onSuccess { bitmap = it }.onFailure { onToast("Could not open that photo") }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap = it; if (it == null) onToast("No photo captured") }
    val totalRows = state.ingredients.filter { it.included }
    val total = IngredientTotals(totalRows.sumOf { (it.kcal * it.multiplier).toInt() }, totalRows.sumOf { (it.p * it.multiplier).toInt() }, totalRows.sumOf { (it.c * it.multiplier).toInt() }, totalRows.sumOf { (it.f * it.multiplier).toInt() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI MEAL SCANNER", color = MealGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text("Scan, customize, and log", color = MealWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.PhotoLibrary, null); Text("Photo") }
            OutlinedButton(onClick = { camera.launch(null) }) { Icon(Icons.Default.CameraAlt, null); Text("Camera") }
        }
        if (bitmap != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MealCard), shape = RoundedCornerShape(20.dp)) {
                Box(Modifier.fillMaxWidth().height(220.dp)) {
                    Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
                    if (state.analyzing) ScanLaser(Modifier.fillMaxSize())
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MealCard), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("Meal and customizations") })
                OutlinedTextField(title, { title = it; vm.updateTitle(it) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Meal title") })
                Button(enabled = !state.analyzing, onClick = { scope.launch {
                    vm.setAnalyzing(true)
                    runCatching { vm.analyzeMeal(context, prompt, bitmap) }
                        .onSuccess { result ->
                            title = result.mealTitle
                            onToast(if (result.usedGemini) "Gemini 2.5 Flash analysis complete" else result.fallbackReason ?: "Offline estimate ready")
                        }
                        .onFailure { error -> vm.fail(error.message ?: "Meal analysis failed"); onToast("Meal analysis failed: ${error.message ?: "unknown error"}") }
                } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MealGreen, contentColor = MealBg)) {
                    Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text(if (state.analyzing) "Analyzing…" else "Analyze meal")
                }
                state.analysisStatus?.let { Text(it, color = if (it.startsWith("Analyzed with Gemini")) MealGreen else MealCyan, fontSize = 10.sp) }
                Text("Gemini uses the saved key for online analysis. If the request fails, SplitCal shows the reason and uses the offline estimate.", color = MealMuted, fontSize = 10.sp)
            }
        }
        Text("RESTAURANT & TAKEOUT PRESETS", color = MealMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MealPresets.all) { preset -> AssistChip(onClick = { vm.loadPreset(preset); title = preset.title; onToast("Loaded ${preset.title}") }, label = { Text(preset.title, fontSize = 10.sp) }) }
        }
        Text("ONE-TAP MODIFIERS", color = MealMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf("Remove Pita" to "−240 kcal", "Extra Chicken" to "+190 kcal / +36g P", "Sub Greens for Rice" to "−185 kcal", "Extra Hummus Scoop" to "+70 kcal", "No Heavy Sauce" to "−120 kcal")) { (name, detail) ->
                FilterChip(selected = false, onClick = { vm.applyModification(name); onToast("Applied $name") }, label = { Text("$name $detail", fontSize = 9.sp) })
            }
        }
        if (state.savings > 0) AssistChip(onClick = {}, label = { Text("Calorie savings · ${state.savings} kcal") }, colors = AssistChipDefaults.assistChipColors(labelColor = MealGreen))
        Card(colors = CardDefaults.cardColors(containerColor = MealCard), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("INGREDIENT BREAKDOWN", color = MealWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("${total.kcal} kcal", color = MealGreen, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                }
                state.ingredients.forEachIndexed { index, item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(item.included, { vm.updateIngredient(index, item.copy(included = it)) }, colors = CheckboxDefaults.colors(checkedColor = MealGreen))
                            Column(Modifier.weight(1f)) { Text(item.name, color = MealWhite, fontSize = 12.sp); Text("${item.portion} · ${(item.kcal * item.multiplier).toInt()} kcal · ${(item.p * item.multiplier).toInt()}P / ${(item.c * item.multiplier).toInt()}C / ${(item.f * item.multiplier).toInt()}F", color = MealMuted, fontSize = 9.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(start = 46.dp)) {
                            listOf(.5f, 1f, 1.5f, 2f).forEach { m -> FilterChip(item.multiplier == m, { vm.updateIngredient(index, item.copy(multiplier = m)) }, label = { Text("${m}x", fontSize = 9.sp) }) }
                        }
                    }
                }
                Text("${total.protein}g protein · ${total.carbs}g carbs · ${total.fat}g fat", color = MealCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("Breakfast", "Lunch", "Dinner", "Snack").forEach { FilterChip(slot == it, { slot = it }, label = { Text(it, fontSize = 9.sp) }) } }
                Button(onClick = { vm.logMeal(slot) { status -> onToast("$title saved · ${total.kcal} kcal · $status") } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MealGreen, contentColor = MealBg)) { Text(if (state.editingMealId != null) "Save meal edits · ${total.kcal} kcal" else "Log meal · ${total.kcal} kcal") }
                state.syncStatus?.let { Text(it, color = if (it.startsWith("Synced")) MealGreen else MealMuted, fontSize = 10.sp) }
            }
        }
        Text("MEALS · ${dashboard.date}", color = MealMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        dashboard.meals.forEach { meal -> Card(colors = CardDefaults.cardColors(containerColor = MealCard)) { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("${meal.slot} · ${meal.name}", color = MealWhite, fontWeight = FontWeight.SemiBold, fontSize = 12.sp); Text("${meal.protein}P · ${meal.carbs}C · ${meal.fat}F", color = MealMuted, fontSize = 10.sp) }; Text("${meal.kcal} kcal", color = MealGreen, fontSize = 10.sp); IconButton(onClick = { vm.loadMealForEdit(meal.id); onToast("Loaded meal for editing") }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Edit, "Edit meal", tint = MealCyan, modifier = Modifier.size(17.dp)) }; IconButton(onClick = { vm.deleteMeal(meal.id); onToast("Meal deleted") }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, "Delete meal", tint = MealMuted, modifier = Modifier.size(17.dp)) } } } }
    }
}

@Composable private fun ScanLaser(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "scan-line")
    Canvas(modifier) {
        val y = size.height * progress
        drawRect(Color(0x3310B981), topLeft = androidx.compose.ui.geometry.Offset(0f, y - 10.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 20.dp.toPx()))
        drawLine(MealGreen, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 2.dp.toPx())
    }
}
