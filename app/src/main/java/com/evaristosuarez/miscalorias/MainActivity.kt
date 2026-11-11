package com.evaristosuarez.miscalorias

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evaristosuarez.miscalorias.data.Food
import com.evaristosuarez.miscalorias.data.FoodDatabase
import com.evaristosuarez.miscalorias.data.MealEntry
import com.evaristosuarez.miscalorias.data.Nutrition
import com.evaristosuarez.miscalorias.data.NutritionEstimator
import com.evaristosuarez.miscalorias.recognition.FoodImageLabeler
import com.google.mlkit.vision.label.ImageLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF20352A)
private val Leaf = Color(0xFF28573F)
private val Paper = Color(0xFFF5F3EA)
private val Clay = Color(0xFFD47C55)
private val Muted = Color(0xFF6D776F)
private val PaleGreen = Color(0xFFE6EDE4)
private val SpanishLocale = Locale.forLanguageTag("es-MX")

class MainActivity : ComponentActivity() {
    private lateinit var database: FoodDatabase
    private lateinit var imageLabeler: FoodImageLabeler

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        database = FoodDatabase(this)
        imageLabeler = FoodImageLabeler(this)

        setContent {
            MisCaloriasTheme {
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    if (uri != null) {
                        analyzeImage { complete ->
                            imageLabeler.analyze(uri, complete)
                        }
                    }
                }
                val camera = rememberLauncherForActivityResult(
                    ActivityResultContracts.TakePicturePreview(),
                ) { bitmap ->
                    if (bitmap != null) {
                        analyzeImage { complete -> imageLabeler.analyze(bitmap, complete) }
                    }
                }
                FoodDiaryApp(
                    database = database,
                    onPickPhoto = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onTakePhoto = { camera.launch(null) },
                )
            }
        }
    }

    private fun analyzeImage(process: ((Result<List<ImageLabel>>) -> Unit) -> Unit) {
        // The labels and the source image stay on-device and are discarded after the suggestion.
        val activity = this
        process { result ->
            activity.runOnUiThread {
                result.fold(
                    onSuccess = { labels ->
                        val suggestions = labels
                            .filter { it.confidence >= 0.25f }
                            .take(6)
                            .map { displayLabel(it.text) }
                        onRecognitionResult?.invoke(
                            suggestions,
                            if (suggestions.isEmpty()) "No se obtuvieron etiquetas claras. Puedes buscar o escribir el alimento." else null,
                        )
                    },
                    onFailure = { error ->
                        onRecognitionResult?.invoke(emptyList(), "No se pudo analizar la imagen: ${error.localizedMessage ?: "intenta otra vez"}")
                    },
                )
            }
        }
    }

    internal var onRecognitionResult: ((List<String>, String?) -> Unit)? = null

    override fun onDestroy() {
        if (::imageLabeler.isInitialized) imageLabeler.close()
        if (::database.isInitialized) database.close()
        super.onDestroy()
    }
}

@Composable
private fun MisCaloriasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Leaf,
            onPrimary = Color.White,
            secondary = Clay,
            background = Paper,
            surface = Color.White,
            onSurface = Ink,
            onBackground = Ink,
        ),
        content = content,
    )
}

private enum class Section(val title: String) {
    TODAY("Hoy"),
    HISTORY("Historial"),
    FOODS("Alimentos"),
}

@Composable
private fun FoodDiaryApp(
    database: FoodDatabase,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(Section.TODAY) }
    var selectedDay by rememberSaveable { mutableStateOf(LocalDate.now()) }
    var refresh by rememberSaveable { mutableIntStateOf(0) }
    var entries by remember(selectedDay, refresh) { mutableStateOf(emptyList<MealEntry>()) }
    var totalCalories by remember(selectedDay, refresh) { mutableIntStateOf(0) }
    var targetCalories by rememberSaveable {
        mutableIntStateOf(context.getSharedPreferences("settings", 0).getInt("target_calories", 0))
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showFoodDialog by rememberSaveable { mutableStateOf(false) }
    var recognizedLabels by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var recognitionError by rememberSaveable { mutableStateOf<String?>(null) }
    var toast by rememberSaveable { mutableStateOf<String?>(null) }
    val activity = context as? MainActivity

    LaunchedEffect(selectedDay, refresh) {
        val result = withContext(Dispatchers.IO) {
            database.mealsFor(selectedDay) to database.dailyCalories(selectedDay)
        }
        entries = result.first
        totalCalories = result.second
    }
    LaunchedEffect(showFoodDialog) {
        if (!showFoodDialog) {
            recognizedLabels = emptyList()
            recognitionError = null
        }
    }
    if (activity != null) {
        activity.onRecognitionResult = { labels, error ->
            recognizedLabels = labels
            recognitionError = error
            showFoodDialog = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            color = Paper,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("MISCALORIAS", color = Leaf, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 2.sp)
                        Text("Por Evaristo Suarez Eichelmann", color = Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { showSettings = true }) { Text("Ajustes") }
                }

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (section) {
                        Section.TODAY -> TodayScreen(
                            day = selectedDay,
                            entries = entries,
                            calories = totalCalories,
                            target = targetCalories,
                            onDayChange = { selectedDay = it },
                            onDelete = { id ->
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { database.deleteMeal(id) }
                                        refresh++
                                    } catch (error: Exception) {
                                        toast = "No se pudo quitar el registro: ${error.localizedMessage ?: "error de almacenamiento"}"
                                    }
                                }
                            },
                        )
                        Section.HISTORY -> HistoryScreen(
                            selectedDay = selectedDay,
                            database = database,
                            onSelectDay = { selectedDay = it; section = Section.TODAY },
                        )
                        Section.FOODS -> FoodLibraryScreen(database)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Section.entries.forEach { item ->
                        TextButton(onClick = { section = item }) {
                            Text(
                                item.title,
                                color = if (section == item) Leaf else Muted,
                                fontWeight = if (section == item) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                    Button(onClick = { showFoodDialog = true }) { Text("+ Comida") }
                }
            }
        }
    if (showFoodDialog) {
        FoodEntryDialog(
            database = database,
            recognizedLabels = recognizedLabels,
            recognitionError = recognitionError,
            onPickPhoto = onPickPhoto,
            onTakePhoto = onTakePhoto,
            onDismiss = { showFoodDialog = false },
            onAdd = { food, grams, meal ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { database.addMeal(selectedDay, meal, food, grams) }
                        refresh++
                        showFoodDialog = false
                        toast = "Comida agregada a tu registro"
                    } catch (error: Exception) {
                        toast = "No se pudo guardar la comida: ${error.localizedMessage ?: "error de almacenamiento"}"
                    }
                }
            },
            onFoodCreated = { refresh++ },
        )
    }

    if (showSettings) {
        SettingsDialog(
            initialTarget = targetCalories,
            onDismiss = { showSettings = false },
            onSave = { value ->
                targetCalories = value
                context.getSharedPreferences("settings", 0).edit { putInt("target_calories", value) }
                showSettings = false
            },
            onClear = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { database.clearMeals() }
                        refresh++
                        showSettings = false
                        toast = "Se borraron tus registros de comidas"
                    } catch (error: Exception) {
                        toast = "No se pudieron borrar los registros: ${error.localizedMessage ?: "error de almacenamiento"}"
                    }
                }
            },
        )
    }

    if (toast != null) {
        LaunchedEffect(toast) {
            kotlinx.coroutines.delay(2200)
            toast = null
        }
        androidx.compose.material3.Snackbar(
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            action = { TextButton(onClick = { toast = null }) { Text("Cerrar") } },
        ) { Text(toast.orEmpty()) }
    }
    }
}

@Composable
private fun TodayScreen(
    day: LocalDate,
    entries: List<MealEntry>,
    calories: Int,
    target: Int,
    onDayChange: (LocalDate) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(if (day == LocalDate.now()) "TU RESUMEN" else "REGISTRO DIARIO", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
            Text(if (day == LocalDate.now()) "Tu día, a tu manera." else day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", SpanishLocale)).replaceFirstChar { it.titlecase(SpanishLocale) }, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onDayChange(day.minusDays(1)) }) { Text("‹") }
            TextButton(onClick = { onDayChange(LocalDate.now()) }) { Text("Hoy") }
            TextButton(onClick = { if (day.isBefore(LocalDate.now())) onDayChange(day.plusDays(1)) }) { Text("›") }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = Leaf), shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ENERGÍA REGISTRADA", color = Color(0xFFD8E5D8), fontSize = 11.sp, letterSpacing = 1.4.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(calories.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 46.sp)
                Spacer(Modifier.width(8.dp))
                Text("kcal aprox.", color = Color.White.copy(alpha = 0.82f), modifier = Modifier.padding(bottom = 7.dp))
            }
            if (target > 0) {
                Text("Referencia personal: $target kcal · no es una recomendación médica", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                LinearProgressIndicator(
                    progress = { (calories.toFloat() / target).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = Color(0xFFE8B08F),
                    trackColor = Color.White.copy(alpha = 0.22f),
                )
            } else {
                Text("Sin meta configurada · puedes usarlo solo como registro", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
            Text("Las cifras son orientativas y dependen de la porción y preparación.", color = Color.White.copy(alpha = 0.76f), fontSize = 11.sp)
        }
    }

    SectionTitle("Nutrientes", "Suma aproximada del día")
    val total = entries.fold(Nutrition(0, 0, 0, 0)) { sum, entry ->
        Nutrition(sum.calories + entry.nutrition.calories, sum.protein + entry.nutrition.protein, sum.carbs + entry.nutrition.carbs, sum.fat + entry.nutrition.fat)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        MacroCard("Proteína", total.protein, Color(0xFF4F7A5A), Modifier.weight(1f))
        MacroCard("Carbohidratos", total.carbs, Color(0xFFD18C58), Modifier.weight(1f))
        MacroCard("Grasas", total.fat, Color(0xFF9A715E), Modifier.weight(1f))
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Tus comidas", null)
        Text("${entries.size} registros", color = Muted, fontSize = 12.sp)
    }
    if (entries.isEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Tu registro empieza aquí", color = Ink, fontWeight = FontWeight.SemiBold)
                Text("Agrega una comida o una porción. No necesitas fijarte una meta.", color = Muted, fontSize = 13.sp)
            }
        }
    } else {
        entries.forEach { entry ->
            MealRow(entry, onDelete = { onDelete(entry.id) })
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String?) {
    Column {
        Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun MacroCard(title: String, amount: Int, tint: Color, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Muted, fontSize = 10.sp, maxLines = 1)
            Text("${amount}g", color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(1.dp))
            Spacer(Modifier.height(4.dp).fillMaxWidth().clip(CircleShape).background(tint))
        }
    }
}

@Composable
private fun MealRow(entry: MealEntry, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.foodName, color = Ink, fontWeight = FontWeight.SemiBold)
                Text("${entry.meal} · ${entry.grams} g · ${entry.nutrition.protein}g proteína", color = Muted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("~${entry.nutrition.calories} kcal", color = Leaf, fontWeight = FontWeight.Bold)
                TextButton(onClick = onDelete) { Text("Quitar", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun HistoryScreen(selectedDay: LocalDate, database: FoodDatabase, onSelectDay: (LocalDate) -> Unit) {
    val days = remember(selectedDay) { (0L..6L).map { LocalDate.now().minusDays(it) } }
    var totals by remember { mutableStateOf<Map<LocalDate, Int>>(emptyMap()) }
    LaunchedEffect(days) {
        totals = withContext(Dispatchers.IO) { days.associateWith(database::dailyCalories) }
    }
    SectionTitle("Tu historial", "Registros en este dispositivo")
    days.forEach { day ->
        val total = totals[day] ?: 0
        Card(
            onClick = { onSelectDay(day) },
            colors = CardDefaults.cardColors(containerColor = if (day == selectedDay) PaleGreen else Color.White),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(17.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(dayLabel(day), color = Ink, fontWeight = FontWeight.SemiBold)
                    Text(if (total == 0) "Sin registros" else "$total kcal aprox.", color = Muted, fontSize = 12.sp)
                }
                Text("Ver día →", color = Leaf, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FoodLibraryScreen(database: FoodDatabase) {
    var query by rememberSaveable { mutableStateOf("") }
    val foods = remember(query) { database.searchFoods(query) }
    SectionTitle("Alimentos", "Referencias genéricas por cada 100 g")
    TextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Buscar alimento") },
        singleLine = true,
    )
    Text("Los productos, recetas y métodos de cocción varían. Contrasta con la etiqueta del alimento.", color = Muted, fontSize = 12.sp)
    foods.forEach { food ->
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(food.name, color = Ink, fontWeight = FontWeight.SemiBold)
                    Text("P ${food.proteinPer100g.toInt()}g · C ${food.carbsPer100g.toInt()}g · G ${food.fatPer100g.toInt()}g", color = Muted, fontSize = 12.sp)
                }
                Text("~${food.caloriesPer100g.toInt()} kcal", color = Leaf, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FoodEntryDialog(
    database: FoodDatabase,
    recognizedLabels: List<String>,
    recognitionError: String?,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onDismiss: () -> Unit,
    onAdd: (Food, Int, String) -> Unit,
    onFoodCreated: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedFoodId by rememberSaveable { mutableStateOf(0L) }
    var grams by rememberSaveable { mutableStateOf("100") }
    var meal by rememberSaveable { mutableStateOf("Comida") }
    var showCreateFood by rememberSaveable { mutableStateOf(false) }
    val foods = remember(query) { database.searchFoods(query) }
    val selectedFood = foods.firstOrNull { it.id == selectedFoodId }
    val portion = grams.toIntOrNull()?.takeIf { it in 1..5000 }
    val estimate = if (selectedFood != null && portion != null) NutritionEstimator.forPortion(selectedFood!!, portion) else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agregar comida") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTakePhoto) { Text("Tomar foto") }
                    OutlinedButton(onClick = onPickPhoto) { Text("Elegir foto") }
                }
                Text("La foto genera etiquetas visuales, no una medición nutricional. Confirma alimento, ingredientes y porción.", color = Muted, fontSize = 11.sp)
                if (recognitionError != null) Text(recognitionError, color = Color(0xFF9E392E), fontSize = 12.sp)
                if (recognizedLabels.isNotEmpty()) {
                    Text("Etiquetas sugeridas (no confirmadas):", color = Leaf, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text(recognizedLabels.joinToString(" · "), color = Ink, fontSize = 12.sp)
                    Text("Escribe abajo lo que reconociste; el modelo no identifica cantidades ni ingredientes ocultos.", color = Muted, fontSize = 11.sp)
                    recognizedLabels.take(4).forEach { label ->
                        TextButton(
                            onClick = {
                                query = label
                                selectedFoodId = 0L
                            },
                        ) { Text("Buscar “$label”") }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; selectedFoodId = 0L },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Buscar alimento o ingrediente") },
                    singleLine = true,
                )
                foods.take(8).forEach { food ->
                    TextButton(
                        onClick = { selectedFoodId = food.id; query = food.name },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${food.name} · ~${food.caloriesPer100g.toInt()} kcal/100 g", modifier = Modifier.fillMaxWidth(), color = if (selectedFood?.id == food.id) Leaf else Ink)
                    }
                }
                if (query.isNotBlank() && foods.none { it.name.equals(query, ignoreCase = true) }) {
                    TextButton(onClick = { showCreateFood = true }) { Text("Crear “$query” con tus valores") }
                }
                if (selectedFood != null) {
                    HorizontalDivider()
                    Text("Porción y comida", color = Ink, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = grams,
                        onValueChange = { grams = it.filter(Char::isDigit).take(4) },
                        label = { Text("Cantidad en gramos") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("Desayuno", "Comida", "Cena", "Colación").forEach { option ->
                            TextButton(onClick = { meal = option }) { Text(option, color = if (meal == option) Leaf else Muted, fontSize = 10.sp) }
                        }
                    }
                    if (estimate != null) {
                        Text("Estimación para ${portion} g: ~${estimate.calories} kcal · P ${estimate.protein} g · C ${estimate.carbs} g · G ${estimate.fat} g", color = Leaf, fontSize = 12.sp)
                    } else {
                        Text("Escribe una porción entre 1 y 5000 g.", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedFood != null && estimate != null,
                onClick = { selectedFood?.let { food -> portion?.let { onAdd(food, it, meal) } } },
            ) { Text("Guardar registro") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
    if (showCreateFood) {
        CreateFoodDialog(
            suggestedName = query,
            database = database,
            onDismiss = { showCreateFood = false },
            onCreated = { food ->
                selectedFoodId = food.id
                query = food.name
                showCreateFood = false
                onFoodCreated()
            },
        )
    }
}

@Composable
private fun CreateFoodDialog(
    suggestedName: String,
    database: FoodDatabase,
    onDismiss: () -> Unit,
    onCreated: (Food) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    var calories by rememberSaveable { mutableStateOf("") }
    var protein by rememberSaveable { mutableStateOf("") }
    var carbs by rememberSaveable { mutableStateOf("") }
    var fat by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crear referencia") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Ingresa valores por 100 g desde una etiqueta o una fuente que elijas.", color = Muted, fontSize = 12.sp)
                OutlinedTextField(name, { name = it }, label = { Text("Alimento") }, singleLine = true)
                NumericField("Calorías (kcal)", calories) { calories = it }
                NumericField("Proteína (g)", protein) { protein = it }
                NumericField("Carbohidratos (g)", carbs) { carbs = it }
                NumericField("Grasas (g)", fat) { fat = it }
                if (error != null) Text(error.orEmpty(), color = Color(0xFF9E392E), fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                val values = listOf(calories, protein, carbs, fat).map { it.toDoubleOrNull() }
                if (name.isBlank() || values.any { it == null }) {
                    error = "Completa el nombre y los cuatro valores."
                } else {
                    try {
                        val food = database.addCustomFood(
                            Food(
                                name = name,
                                caloriesPer100g = values[0]!!,
                                proteinPer100g = values[1]!!,
                                carbsPer100g = values[2]!!,
                                fatPer100g = values[3]!!,
                                custom = true,
                            ),
                        )
                        onCreated(food)
                    } catch (exception: Exception) {
                        error = exception.message ?: "No se pudo guardar; revisa si el alimento ya existe."
                    }
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun NumericField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onChange(new.filter { it.isDigit() || it == '.' }.take(7)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}

@Composable
private fun SettingsDialog(
    initialTarget: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    onClear: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(if (initialTarget > 0) initialTarget.toString() else "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Preferencias y privacidad") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Meta opcional", color = Ink, fontWeight = FontWeight.SemiBold)
                Text("Es una referencia que tú eliges, no una recomendación, y puede no ser adecuada para todas las personas. Puedes dejarla vacía; no se aceptan metas inferiores a 1200 kcal.", color = Muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit).take(5) },
                    label = { Text("Referencia kcal/día (opcional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                if (error != null) Text(error.orEmpty(), color = Color(0xFF9E392E), fontSize = 12.sp)
                Text("Tus registros permanecen en el dispositivo. Las fotos se analizan localmente, se descartan y no se envían a un servidor.", color = Muted, fontSize = 12.sp)
                TextButton(onClick = { showPrivacy = true }) { Text("Leer aviso de privacidad") }
                TextButton(onClick = { confirmClear = true }) { Text("Borrar todos mis registros", color = Color(0xFF9E392E)) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = value.toIntOrNull()
                when {
                    value.isBlank() -> onSave(0)
                    parsed == null || parsed !in 1200..10000 -> error = "Usa un valor entre 1200 y 10000, o deja el campo vacío."
                    else -> onSave(parsed)
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("¿Borrar todos los registros?") },
            text = { Text("Esta acción elimina el historial de comidas de este dispositivo y no se puede deshacer.") },
            confirmButton = {
                Button(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("Borrar registros") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("Aviso de privacidad") },
            text = {
                Text(
                    "Tus alimentos y preferencias se guardan en la base local privada de la app. Las fotos se analizan en el dispositivo y se descartan; MISCALORIAS no tiene cuenta ni permiso de Internet. Puedes borrar el historial en Ajustes o al desinstalar. La cámara y el selector de fotos dependen de las apps del sistema.",
                    color = Muted,
                    fontSize = 13.sp,
                )
            },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("Entendido") } },
        )
    }
}

private fun dayLabel(day: LocalDate): String = when (day) {
    LocalDate.now() -> "Hoy"
    LocalDate.now().minusDays(1) -> "Ayer"
    else -> day.format(DateTimeFormatter.ofPattern("EEEE d MMM", SpanishLocale))
        .replaceFirstChar { it.titlecase(SpanishLocale) }
}

private fun displayLabel(label: String): String {
    val translations = mapOf(
        "apple" to "Manzana",
        "banana" to "Plátano",
        "bread" to "Pan",
        "chicken" to "Pollo",
        "egg" to "Huevo",
        "fish" to "Pescado",
        "food" to "Comida",
        "fruit" to "Fruta",
        "meat" to "Carne",
        "orange" to "Naranja",
        "pasta" to "Pasta",
        "pizza" to "Pizza",
        "rice" to "Arroz",
        "salad" to "Ensalada",
        "sandwich" to "Sándwich",
        "vegetable" to "Verdura",
    )
    return translations[label.lowercase(Locale.ROOT)] ?: label
}
