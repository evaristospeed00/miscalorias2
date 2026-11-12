package com.evaristosuarez.miscalorias

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.evaristosuarez.miscalorias.data.Food
import com.evaristosuarez.miscalorias.data.FoodDatabase
import com.evaristosuarez.miscalorias.data.MealEntry
import com.evaristosuarez.miscalorias.data.Nutrition
import com.evaristosuarez.miscalorias.data.NutritionEstimator
import com.evaristosuarez.miscalorias.recognition.FoodImageLabeler
import com.evaristosuarez.miscalorias.ui.components.AppCard
import com.evaristosuarez.miscalorias.ui.components.AppTopBar
import com.evaristosuarez.miscalorias.ui.components.BottomNavBar
import com.evaristosuarez.miscalorias.ui.components.CalorieHeroCard
import com.evaristosuarez.miscalorias.ui.components.Chip
import com.evaristosuarez.miscalorias.ui.components.EmptyStateCard
import com.evaristosuarez.miscalorias.ui.components.Eyebrow
import com.evaristosuarez.miscalorias.ui.components.MacroStat
import com.evaristosuarez.miscalorias.ui.components.PrimaryButton
import com.evaristosuarez.miscalorias.ui.components.QuietTextButton
import com.evaristosuarez.miscalorias.ui.components.SecondaryButton
import com.evaristosuarez.miscalorias.ui.components.SectionHeader
import com.evaristosuarez.miscalorias.ui.components.SuggestionBadge
import com.evaristosuarez.miscalorias.ui.theme.CarbsAccent
import com.evaristosuarez.miscalorias.ui.theme.FatAccent
import com.evaristosuarez.miscalorias.ui.theme.MisCaloriasTheme
import com.evaristosuarez.miscalorias.ui.theme.NumericTitle
import com.evaristosuarez.miscalorias.ui.theme.ProteinAccent
import com.evaristosuarez.miscalorias.ui.theme.Spacing
import com.evaristosuarez.miscalorias.vision.ArDepthCaptureActivity
import com.evaristosuarez.miscalorias.vision.DepthCapturePayload
import com.google.mlkit.vision.label.ImageLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                        analyzeImage(process = { complete ->
                            imageLabeler.analyze(uri, complete)
                        })
                    }
                }
                val camera = rememberLauncherForActivityResult(
                    ActivityResultContracts.TakePicturePreview(),
                ) { bitmap ->
                    if (bitmap != null) {
                        analyzeImage(process = { complete -> imageLabeler.analyze(bitmap, complete) })
                    }
                }
                val depthCamera = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode == RESULT_OK && result.data != null) {
                        val payload = DepthCapturePayload.fromIntent(result.data!!)
                        val bitmap = payload?.rgbFile?.takeIf { it.isFile }?.let {
                            BitmapFactory.decodeFile(it.absolutePath)
                        }
                        if (payload == null || bitmap == null) {
                            payload?.deleteFiles()
                            onRecognitionResult?.invoke(
                                emptyList(),
                                "No se pudo leer la foto capturada. Intenta de nuevo.",
                            )
                        } else {
                            analyzeImage(
                                process = { complete -> imageLabeler.analyze(bitmap, complete) },
                                onFinished = {
                                    bitmap.recycle()
                                    payload.deleteFiles()
                                },
                            )
                        }
                    } else if (
                        result.data?.getBooleanExtra(
                            DepthCapturePayload.EXTRA_FALLBACK_TO_SYSTEM_CAMERA,
                            false,
                        ) == true
                    ) {
                        camera.launch(null)
                    }
                }
                FoodDiaryApp(
                    database = database,
                    onPickPhoto = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onTakePhoto = {
                        depthCamera.launch(Intent(this@MainActivity, ArDepthCaptureActivity::class.java))
                    },
                )
            }
        }
    }

    private fun analyzeImage(
        process: ((Result<List<ImageLabel>>) -> Unit) -> Unit,
        onFinished: () -> Unit = {},
    ) {
        // The labels and the source image stay on-device and are discarded after the suggestion.
        val activity = this
        process { result ->
            activity.runOnUiThread {
                try {
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
                } finally {
                    onFinished()
                }
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
    val context = LocalContext.current
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
    DisposableEffect(activity) {
        if (activity != null) {
            activity.onRecognitionResult = { labels, error ->
                recognizedLabels = labels
                recognitionError = error
                showFoodDialog = true
            }
        }
        onDispose {
            if (activity != null) {
                activity.onRecognitionResult = null
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AppTopBar(
                    brand = "miscalorIAs",
                    subtitle = "DIARIO DE ALIMENTACIÓN",
                    actionLabel = "Ajustes",
                    onAction = { showSettings = true },
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
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
                            refresh = refresh,
                            onSelectDay = { selectedDay = it; section = Section.TODAY },
                        )
                        Section.FOODS -> FoodLibraryScreen(database = database, refresh = refresh)
                    }
                    Spacer(Modifier.height(Spacing.xs))
                }

                BottomNavBar(
                    items = Section.entries.map { it.title to (section == it) },
                    onSelect = { index -> section = Section.entries[index] },
                    actionLabel = "+ Comida",
                    onAction = { showFoodDialog = true },
                )
            }
        }

        if (showFoodDialog) {
            FoodEntryDialog(
                database = database,
                refresh = refresh,
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

        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (toast != null) {
                LaunchedEffect(toast) {
                    delay(2200)
                    toast = null
                }
                Snackbar(
                    modifier = Modifier.padding(Spacing.md),
                    action = {
                        QuietTextButton(text = "Cerrar", onClick = { toast = null })
                    },
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    Text(toast.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
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
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Eyebrow(if (day == LocalDate.now()) "Tu resumen" else "Registro diario")
            Text(
                if (day == LocalDate.now()) {
                    "Tu día, a tu manera."
                } else {
                    day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", SpanishLocale))
                        .replaceFirstChar { it.titlecase(SpanishLocale) }
                },
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietTextButton(text = "‹", onClick = { onDayChange(day.minusDays(1)) })
            QuietTextButton(
                text = "Hoy",
                onClick = { onDayChange(LocalDate.now()) },
                color = if (day == LocalDate.now()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            val canGoForward = day.isBefore(LocalDate.now())
            QuietTextButton(
                text = "›",
                enabled = canGoForward,
                onClick = { if (canGoForward) onDayChange(day.plusDays(1)) },
            )
        }
    }

    CalorieHeroCard(calories = calories, target = target)

    SectionHeader(title = "Nutrientes", subtitle = "Suma aproximada del día")
    val total = entries.fold(Nutrition(0, 0, 0, 0)) { sum, entry ->
        Nutrition(
            sum.calories + entry.nutrition.calories,
            sum.protein + entry.nutrition.protein,
            sum.carbs + entry.nutrition.carbs,
            sum.fat + entry.nutrition.fat,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        MacroStat("Proteína", total.protein, ProteinAccent, Modifier.weight(1f))
        MacroStat("Carbohidratos", total.carbs, CarbsAccent, Modifier.weight(1f))
        MacroStat("Grasas", total.fat, FatAccent, Modifier.weight(1f))
    }

    SectionHeader(
        title = "Tus comidas",
        trailing = {
            Text(
                "${entries.size} registros",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
    if (entries.isEmpty()) {
        EmptyStateCard(
            title = "Tu registro empieza aquí",
            body = "Agrega una comida o una porción. No necesitas fijarte una meta.",
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            entries.forEach { entry ->
                MealRow(entry, onDelete = { onDelete(entry.id) })
            }
        }
    }
}

@Composable
private fun MealRow(entry: MealEntry, onDelete: () -> Unit) {
    AppCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    entry.foodName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${entry.meal} · ${entry.grams} g · ${entry.nutrition.protein}g proteína",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "~${entry.nutrition.calories}",
                    style = NumericTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "kcal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QuietTextButton(
                    text = "Quitar",
                    onClick = onDelete,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    selectedDay: LocalDate,
    database: FoodDatabase,
    refresh: Int,
    onSelectDay: (LocalDate) -> Unit,
) {
    val days = remember { (0L..6L).map { LocalDate.now().minusDays(it) } }
    var totals by remember { mutableStateOf<Map<LocalDate, Int>>(emptyMap()) }
    LaunchedEffect(days, refresh) {
        totals = withContext(Dispatchers.IO) { days.associateWith(database::dailyCalories) }
    }
    SectionHeader(title = "Tu historial", subtitle = "Registros en este dispositivo")
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        days.forEach { day ->
            val total = totals[day] ?: 0
            AppCard(
                onClick = { onSelectDay(day) },
                highlighted = day == selectedDay,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            dayLabel(day),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (total == 0) "Sin registros" else "$total kcal aprox.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Ver día",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FoodLibraryScreen(database: FoodDatabase, refresh: Int) {
    var query by rememberSaveable { mutableStateOf("") }
    var foods by remember { mutableStateOf(emptyList<Food>()) }
    LaunchedEffect(query, refresh) {
        foods = withContext(Dispatchers.IO) { database.searchFoods(query) }
    }
    SectionHeader(title = "Alimentos", subtitle = "Referencias genéricas por cada 100 g")
    SoftTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = "Buscar alimento",
    )
    Text(
        "Los productos, recetas y métodos de cocción varían. Contrasta con la etiqueta del alimento.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        foods.forEach { food ->
            AppCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            food.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "P ${food.proteinPer100g.toInt()}g · C ${food.carbsPer100g.toInt()}g · G ${food.fatPer100g.toInt()}g",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "~${food.caloriesPer100g.toInt()}",
                            style = NumericTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "kcal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoodEntryDialog(
    database: FoodDatabase,
    refresh: Int,
    recognizedLabels: List<String>,
    recognitionError: String?,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onDismiss: () -> Unit,
    onAdd: (Food, Int, String) -> Unit,
    onFoodCreated: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedFood by remember { mutableStateOf<Food?>(null) }
    var grams by rememberSaveable { mutableStateOf("100") }
    var meal by rememberSaveable { mutableStateOf("Comida") }
    var showCreateFood by rememberSaveable { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var foods by remember { mutableStateOf(emptyList<Food>()) }

    LaunchedEffect(query, refresh) {
        foods = withContext(Dispatchers.IO) { database.searchFoods(query) }
    }

    val portion = grams.toIntOrNull()?.takeIf { it in 1..5000 }
    val estimate = if (selectedFood != null && portion != null) {
        NutritionEstimator.forPortion(selectedFood!!, portion)
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Eyebrow("Registro")
                Text("Agregar comida", style = MaterialTheme.typography.headlineSmall)
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // Camera / gallery — visual chrome only; capture pipeline unchanged.
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SecondaryButton(
                        text = "Tomar foto",
                        onClick = onTakePhoto,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Elegir foto",
                        onClick = onPickPhoto,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "La foto genera etiquetas visuales, no una medición nutricional. Confirma alimento, ingredientes y porción.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (recognitionError != null) {
                    Text(
                        recognitionError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (recognizedLabels.isNotEmpty()) {
                    Text(
                        "Etiquetas sugeridas (no confirmadas)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        recognizedLabels.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Escribe abajo lo que reconociste; el modelo no identifica cantidades ni ingredientes ocultos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        recognizedLabels.take(4).forEach { label ->
                            SuggestionBadge(
                                text = label,
                                onClick = {
                                    query = label
                                    selectedFood = null
                                },
                            )
                        }
                    }
                }
                SoftOutlinedField(
                    value = query,
                    onValueChange = { query = it; selectedFood = null },
                    label = "Buscar alimento o ingrediente",
                )
                foods.take(8).forEach { food ->
                    val selected = selectedFood?.id == food.id
                    AppCard(
                        onClick = { selectedFood = food; query = food.name },
                        highlighted = selected,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = Spacing.sm,
                            vertical = Spacing.sm,
                        ),
                    ) {
                        Text(
                            "${food.name} · ~${food.caloriesPer100g.toInt()} kcal/100 g",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                if (query.isNotBlank() && foods.none { it.name.equals(query, ignoreCase = true) }) {
                    QuietTextButton(
                        text = "Crear “$query” con tus valores",
                        onClick = { showCreateFood = true },
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (selectedFood != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text(
                        "Porción y comida",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    SoftOutlinedField(
                        value = grams,
                        onValueChange = { grams = it.filter(Char::isDigit).take(4) },
                        label = "Cantidad en gramos",
                        keyboardType = KeyboardType.Number,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        listOf("Desayuno", "Comida", "Cena", "Colación").forEach { option ->
                            Chip(
                                label = option,
                                selected = meal == option,
                                onClick = { meal = option },
                            )
                        }
                    }
                    if (estimate != null) {
                        AppCard {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Eyebrow("Estimación nutricional")
                                Text(
                                    "~${estimate.calories} kcal",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "Para ${portion} g · P ${estimate.protein} g · C ${estimate.carbs} g · G ${estimate.fat} g",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Text(
                            "Escribe una porción entre 1 y 5000 g.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                text = "Guardar registro",
                enabled = selectedFood != null && estimate != null && !isSaving,
                onClick = {
                    val foodToSave = selectedFood
                    val portionToSave = portion
                    if (foodToSave != null && portionToSave != null && !isSaving) {
                        isSaving = true
                        onAdd(foodToSave, portionToSave, meal)
                    }
                },
            )
        },
        dismissButton = {
            QuietTextButton(text = "Cerrar", onClick = onDismiss)
        },
    )
    if (showCreateFood) {
        CreateFoodDialog(
            suggestedName = query,
            database = database,
            onDismiss = { showCreateFood = false },
            onCreated = { food ->
                selectedFood = food
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
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Eyebrow("Referencia")
                Text("Crear alimento", style = MaterialTheme.typography.headlineSmall)
            }
        },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    "Ingresa valores por 100 g desde una etiqueta o una fuente que elijas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SoftOutlinedField(value = name, onValueChange = { name = it }, label = "Alimento")
                SoftOutlinedField(
                    value = calories,
                    onValueChange = { calories = it.filter { ch -> ch.isDigit() || ch == '.' }.take(7) },
                    label = "Calorías (kcal)",
                    keyboardType = KeyboardType.Decimal,
                )
                SoftOutlinedField(
                    value = protein,
                    onValueChange = { protein = it.filter { ch -> ch.isDigit() || ch == '.' }.take(7) },
                    label = "Proteína (g)",
                    keyboardType = KeyboardType.Decimal,
                )
                SoftOutlinedField(
                    value = carbs,
                    onValueChange = { carbs = it.filter { ch -> ch.isDigit() || ch == '.' }.take(7) },
                    label = "Carbohidratos (g)",
                    keyboardType = KeyboardType.Decimal,
                )
                SoftOutlinedField(
                    value = fat,
                    onValueChange = { fat = it.filter { ch -> ch.isDigit() || ch == '.' }.take(7) },
                    label = "Grasas (g)",
                    keyboardType = KeyboardType.Decimal,
                )
                if (error != null) {
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                text = "Guardar",
                onClick = {
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
                        } catch (exception: android.database.sqlite.SQLiteConstraintException) {
                            error = "Ya existe un alimento con el nombre “${name.trim()}”."
                        } catch (exception: Exception) {
                            error = exception.message ?: "No se pudo guardar el alimento."
                        }
                    }
                },
            )
        },
        dismissButton = {
            QuietTextButton(text = "Cancelar", onClick = onDismiss)
        },
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
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Eyebrow("Preferencias")
                Text("Ajustes y privacidad", style = MaterialTheme.typography.headlineSmall)
            }
        },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    "Meta opcional",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Es una referencia que tú eliges, no una recomendación, y puede no ser adecuada para todas las personas. Puedes dejarla vacía; no se aceptan metas inferiores a 1200 kcal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SoftOutlinedField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit).take(5) },
                    label = "Referencia kcal/día (opcional)",
                    keyboardType = KeyboardType.Number,
                )
                if (error != null) {
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text(
                    "Tus registros permanecen en el dispositivo. Las fotos se analizan localmente, se descartan y no se envían a un servidor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QuietTextButton(
                    text = "Leer aviso de privacidad",
                    onClick = { showPrivacy = true },
                    color = MaterialTheme.colorScheme.onSurface,
                )
                QuietTextButton(
                    text = "Borrar todos mis registros",
                    onClick = { confirmClear = true },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                text = "Guardar",
                onClick = {
                    val parsed = value.toIntOrNull()
                    when {
                        value.isBlank() -> onSave(0)
                        parsed == null || parsed !in 1200..10000 ->
                            error = "Usa un valor entre 1200 y 10000, o deja el campo vacío."
                        else -> onSave(parsed)
                    }
                },
            )
        },
        dismissButton = {
            QuietTextButton(text = "Cerrar", onClick = onDismiss)
        },
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text("¿Borrar todos los registros?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Esta acción elimina el historial de comidas de este dispositivo y no se puede deshacer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                PrimaryButton(
                    text = "Borrar registros",
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                )
            },
            dismissButton = {
                QuietTextButton(text = "Cancelar", onClick = { confirmClear = false })
            },
        )
    }
    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text("Aviso de privacidad", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Tus alimentos y preferencias se guardan en la base local privada de la app. Las fotos se analizan en el dispositivo y se descartan; MISCALORIAS no tiene cuenta ni permiso de Internet. Puedes borrar el historial en Ajustes o al desinstalar. La cámara y el selector de fotos dependen de las apps del sistema.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                QuietTextButton(text = "Entendido", onClick = { showPrivacy = false })
            },
        )
    }
}

@Composable
private fun SoftTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = MaterialTheme.shapes.medium,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun SoftOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
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
