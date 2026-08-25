package com.aiqyn.safestudio

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import java.util.Locale
import com.aiqyn.safestudio.service.ContextRelevanceEngine
import com.aiqyn.safestudio.data.ContextReminder
import com.aiqyn.safestudio.data.ContextMatch
import com.aiqyn.safestudio.data.RelevanceLevel
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aiqyn.safestudio.data.Language
import com.aiqyn.safestudio.data.Mode
import com.aiqyn.safestudio.data.UserPreferencesRepository
import com.aiqyn.safestudio.service.AudioMonitoringService
import com.aiqyn.safestudio.service.NotificationHelper
import com.aiqyn.safestudio.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIqynTheme {
                AIqynApp()
            }
        }
    }
}

private enum class Screen {
    REGISTRATION, MAIN, SETTINGS, SOUND_LIBRARY, CONTEXT_REMINDERS, DEBUG_CONTEXT_TEST
}

private data class SettingsSession(
    val name: String = "",
    val mode: Mode = Mode.EVERYDAY,
    val language: Language = Language.ENGLISH,
    val selectedSounds: Set<String> = emptySet(),
    val hasCustomizedSounds: Boolean = false,
    val nameRecognitionEnabled: Boolean = false,
    val namePhoneticVariants: Set<String> = emptySet(),
    val nameEnvelope: List<Float> = emptyList()
)

@Composable
private fun AIqynApp() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val prefs = remember { UserPreferencesRepository(context) }
    NotificationHelper.createChannels(context)

    val currentLocale = configuration.locales[0]

    var userName by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(Mode.EVERYDAY) }
    var language by remember { mutableStateOf(Language.ENGLISH) }
    var screen by remember { mutableStateOf(Screen.REGISTRATION) }
    var showTtsDialog by remember { mutableStateOf(false) }
    val isMonitoring by AudioMonitoringService.isRunning.collectAsState()

    var session by remember { mutableStateOf<SettingsSession?>(null) }

    val activeLanguage = session?.language ?: language
    val uiStrings = remember(activeLanguage) { getUiStrings(activeLanguage) }
    val layoutDirection = if (activeLanguage == Language.ARABIC) LayoutDirection.Rtl else LayoutDirection.Ltr
    
    LaunchedEffect(Unit) {
        val state = prefs.getUserState()
        userName = state.name
        mode = state.mode
        
        if (state.registered) {
            language = state.language
            screen = Screen.MAIN
        } else {
            val detectedLang = Language.fromSystem(currentLocale.language)
            language = detectedLang
            session = SettingsSession(
                language = detectedLang,
                mode = Mode.EVERYDAY,
                selectedSounds = prefs.getDefaultSoundsForMode(Mode.EVERYDAY),
                nameRecognitionEnabled = state.nameRecognitionEnabled,
                namePhoneticVariants = state.namePhoneticVariants
            )
            screen = Screen.REGISTRATION
        }
    }

    if (showTtsDialog) {
        val ttsStrings = getTtsStrings(activeLanguage)
        AlertDialog(
            onDismissRequest = { showTtsDialog = false; prefs.setTtsCheckDone() },
            containerColor = SpaceNavy,
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.8f),
            title = { Text(ttsStrings.title) },
            text = { Text(ttsStrings.text) },
            confirmButton = {
                Button(
                    onClick = {
                        showTtsDialog = false
                        prefs.setTtsCheckDone()
                        try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) {}
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CosmicPurple)
                ) { Text(ttsStrings.btnSettings) }
            },
            dismissButton = {
                TextButton(onClick = { showTtsDialog = false; prefs.setTtsCheckDone() }) {
                    Text(ttsStrings.btnAnyway, color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> 
        if (granted) {
            // Permission granted, trigger training dialog if it was intented
            // Note: We don't have a direct flag here but the user can click again.
            // For monitoring:
            AudioMonitoringService.start(context)
        }
    }
    
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) AudioMonitoringService.start(context)
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(DeepSpaceBackground, SpaceNavy, DeepSpaceBackground)))) {
            Scaffold(modifier = Modifier.fillMaxSize(), containerColor = Color.Transparent) { innerPadding ->
                val userState = prefs.getUserState()
                val currentSession = session ?: SettingsSession(
                    name = userName, 
                    mode = mode, 
                    language = language, 
                    selectedSounds = userState.selectedSounds, 
                    hasCustomizedSounds = true,
                    nameRecognitionEnabled = userState.nameRecognitionEnabled,
                    namePhoneticVariants = userState.namePhoneticVariants
                )
                
                when (screen) {
                    Screen.REGISTRATION -> RegistrationScreen(
                        modifier = Modifier.padding(innerPadding),
                        session = currentSession,
                        onSessionUpdate = { session = it },
                        onSave = { name, selectedMode, selectedLanguage ->
                            prefs.saveRegistration(
                                name = name, 
                                mode = selectedMode, 
                                language = selectedLanguage, 
                                sounds = currentSession.selectedSounds, 
                                nameRecognition = currentSession.nameRecognitionEnabled,
                                variants = currentSession.namePhoneticVariants
                            )
                            userName = name; mode = selectedMode; language = selectedLanguage; session = null; screen = Screen.MAIN
                        },
                        onOpenLibrary = { screen = Screen.SOUND_LIBRARY },
                        prefs = prefs
                    )

                    Screen.MAIN -> MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        userName = userName,
                        mode = mode,
                        isMonitoring = isMonitoring,
                        onStartMonitoring = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@MainScreen
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                return@MainScreen
                            }
                            AudioMonitoringService.start(context)
                        },
                        onStopMonitoring = { AudioMonitoringService.stop(context) },
                        onSettings = { 
                            session = SettingsSession(
                                name = userName, 
                                mode = mode, 
                                language = language, 
                                selectedSounds = userState.selectedSounds, 
                                hasCustomizedSounds = true, 
                                nameRecognitionEnabled = userState.nameRecognitionEnabled,
                                namePhoneticVariants = userState.namePhoneticVariants
                            )
                            screen = Screen.SETTINGS 
                        },
                        uiStrings = uiStrings
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        modifier = Modifier.padding(innerPadding),
                        session = currentSession,
                        onSessionUpdate = { session = it },
                        onSave = { newName, newMode, newLanguage ->
                            prefs.saveRegistration(
                                name = newName, 
                                mode = newMode, 
                                language = newLanguage, 
                                sounds = currentSession.selectedSounds, 
                                nameRecognition = currentSession.nameRecognitionEnabled,
                                variants = currentSession.namePhoneticVariants
                            )
                            userName = newName; mode = newMode; language = newLanguage; session = null
                            if (isMonitoring) { AudioMonitoringService.stop(context); AudioMonitoringService.start(context) }
                            screen = Screen.MAIN
                        },
                        onCancel = { session = null; screen = Screen.MAIN },
                        onReset = {
                            AudioMonitoringService.stop(context); prefs.clearProfile(); userName = ""; mode = Mode.EVERYDAY
                            val detectedLang = Language.fromSystem(currentLocale.language)
                            language = detectedLang; session = SettingsSession(language = detectedLang, selectedSounds = prefs.getDefaultSoundsForMode(Mode.EVERYDAY))
                            screen = Screen.REGISTRATION
                        },
                        onOpenLibrary = { screen = Screen.SOUND_LIBRARY },
                        onOpenContextReminders = { screen = Screen.CONTEXT_REMINDERS },
                        onOpenDebugTest = if (isDebugMode(context)) { { screen = Screen.DEBUG_CONTEXT_TEST } } else null,
                        prefs = prefs
                    )

                    Screen.CONTEXT_REMINDERS -> ContextRemindersScreen(
                        modifier = Modifier.padding(innerPadding),
                        onBack = { screen = Screen.SETTINGS },
                        prefs = prefs,
                        language = currentSession.language,
                        uiStrings = uiStrings
                    )

                    Screen.DEBUG_CONTEXT_TEST -> if (isDebugMode(context)) {
                        DebugContextTestScreen(
                            modifier = Modifier.padding(innerPadding),
                            onBack = { screen = Screen.SETTINGS },
                            prefs = prefs,
                            language = currentSession.language,
                            uiStrings = uiStrings
                        )
                    } else {
                        screen = Screen.SETTINGS
                    }

                    Screen.SOUND_LIBRARY -> SoundLibraryScreen(
                        modifier = Modifier.padding(innerPadding),
                        selectedSounds = currentSession.selectedSounds,
                        onSave = { sounds ->
                            session = currentSession.copy(selectedSounds = sounds, hasCustomizedSounds = true)
                            screen = if (!userState.registered) Screen.REGISTRATION else Screen.SETTINGS
                        },
                        onBack = { screen = if (!userState.registered) Screen.REGISTRATION else Screen.SETTINGS },
                        uiStrings = getUiStrings(currentSession.language), 
                        language = currentSession.language,
                        onResetToDefaults = {
                            session = currentSession.copy(selectedSounds = prefs.getDefaultSoundsForMode(currentSession.mode), hasCustomizedSounds = false)
                        }
                    )
                }
            }
        }
    }
}

private data class TtsStrings(val title: String, val text: String, val btnSettings: String, val btnAnyway: String)

private fun getTtsStrings(language: Language): TtsStrings {
    return when(language) {
        Language.RUSSIAN -> TtsStrings("Рекомендация речевого движка", "Для лучших многоязычных голосовых оповещений AIqyn рекомендует Google Speech Services.", "Открыть настройки голоса", "Продолжить в любом случае")
        Language.KAZAKH -> TtsStrings("Сөйлеу жүйесі бойынша ұсыныс", "Үздік көптілді дауыстық ескертулер үшін AIqyn Google Speech Services қызметін ұсынады.", "Дауыс баптауларын ашу", "Бәрібір жалғастыру")
        Language.TURKISH -> TtsStrings("Konuşma Motoru Öнерisi", "En iyi çok dilli sesli uyarılar için AIqyn, Google Konuşma Hizmetlerini önerir.", "Ses Ayarlarını Aç", "Yine de Devam Et")
        Language.SPANISH -> TtsStrings("Recomendación del motor de voz", "Para obtener las mejores alertas de voz multilingües, AIqyn recomienda Google Speech Services.", "Abrir ajustes de voz", "Continuar de todos modos")
        Language.ARABIC -> TtsStrings("توصية محرك الكلام", "للحصول على أفضل تنبيهات صوتية متعددة اللغات، يوصي AIqyn بـ Google Speech Services.", "فتح إعدادات الصوت", "المتابعة على أي حال")
        Language.CHINESE -> TtsStrings("语音引擎建议", "为了获得最佳的多语言语音警报，AIqyn 建议使用 Google 语音服务。", "打开语音设置", "仍然继续")
        Language.GERMAN -> TtsStrings("Empfehlung für das Sprachmodul", "Für die besten mehrsprachigen Sprachwarnungen empfiehlt AIqyn Google Speech Services.", "Spracheinstellungen öffnen", "Trotzdem fortfahren")
        else -> TtsStrings("Speech Engine Recommendation", "For best multilingual voice alerts, AIqyn recommends Google Speech Services.", "Open Voice Settings", "Continue Anyway")
    }
}

private data class UiStrings(
    val registerTitle: String, val settingsTitle: String, val onboardingSub: String,
    val userNameLabel: String, val chooseMode: String, val chooseLanguage: String,
    val saveAction: String, val saveChangesAction: String, val cancelAction: String,
    val hello: String, val currentMode: String, val monitoringActive: String,
    val monitoringStopped: String, val activeSub: String, val startMonitoring: String,
    val stopMonitoring: String, val settings: String, val resetAction: String,
    val modeEveryday: String, val modeEverydaySub: String, val modeParent: String,
    val modeParentSub: String, val modeDeaf: String, val modeDeafSub: String,
    val soundLibrary: String, val catTransport: String, val catHome: String,
    val catEmergency: String, val catAccessibility: String, val beta: String,
    val resetDefaults: String, val headphoneNote: String, val nameRecognitionTitle: String,
    val nameRecognitionDesc: String, val recordAction: String, val reRecordAction: String,
    val deleteAction: String, val trainingSuccess: String, val listeningAction: String,
    val trainingInstruction: String, val startTrainingAction: String, val voiceSavedSuccess: String,
    // Context Reminders strings
    val contextReminders: String, val contextRemindersDesc: String, val addReminder: String,
    val editReminder: String, val event: String, val time: String, val location: String,
    val destination: String, val keywords: String, val description: String, val save: String,
    val cancel: String, val enabled: String, val disabled: String, val noReminders: String,
    val addFirstReminder: String, val relevanceLow: String, val relevanceMedium: String,
    val relevanceHigh: String, val contextImportant: String, val contextPossibly: String,
    val contextMayBe: String
)

private fun getUiStrings(language: Language): UiStrings {
    return when (language) {
        Language.RUSSIAN -> UiStrings(
            registerTitle = "Регистрация", settingsTitle = "Настройки", onboardingSub = "Интеллектуальная защита звука",
            userNameLabel = "Имя пользователя", chooseMode = "Выберите режим", chooseLanguage = "Выберите язык",
            saveAction = "Продолжить", saveChangesAction = "Сохранить изменения", cancelAction = "Назад",
            hello = "Привет", currentMode = "Режим", monitoringActive = "Мониторинг активен",
            monitoringStopped = "Мониторинг остановлен", activeSub = "ИИ защита звука активна",
            startMonitoring = "Начать мониторинг", stopMonitoring = "Остановить мониторинг", settings = "Настройки",
            resetAction = "Сбросить профиль", modeEveryday = "ЕЖЕДНЕВНЫЙ", modeEverydaySub = "Для повседневной безопасности",
            modeParent = "РОДИТЕЛЬ", modeParentSub = "Защита с фокусом на ребенке", modeDeaf = "СЛАБОСЛЫШАЩИЙ",
            modeDeafSub = "Бесшумная защита и вибрация", soundLibrary = "Библиотека опасных звуков",
            catTransport = "ТРАНСПОРТ И УЛИЦА", catHome = "ДОМ И БЕЗОПАСНОСТЬ", catEmergency = "ЗДОРОВЬЕ И ЧС",
            catAccessibility = "ДОСТУПНОСТЬ", beta = "Бета", resetDefaults = "Сбросить к настройкам режима",
            headphoneNote = "AIqyn работает лучше всего с наушниками для точного мониторинга звуков и голосовых оповещений.",
            nameRecognitionTitle = "Распознавание имени",
            nameRecognitionDesc = "Alqyn распознаёт это слово независимо от голоса человека.",
            recordAction = "Записать", reRecordAction = "Перезаписать", deleteAction = "Удалить",
            trainingSuccess = "Имя успешно обучено!", listeningAction = "Слушаю...",
            trainingInstruction = "Чтобы функция работала правильно, произнесите своё имя чётко.",
            startTrainingAction = "Начать запись", voiceSavedSuccess = "Имя успешно обучено!",
            // Context Reminders
            contextReminders = "Контекстные напоминания", contextRemindersDesc = "Сообщите AIqyn, что важно в вашем дне, для получения более умных уведомлений.",
            addReminder = "Добавить напоминание", editReminder = "Редактировать напоминание", event = "Событие",
            time = "Время", location = "Местоположение", destination = "Назначение", keywords = "Ключевые слова",
            description = "Описание", save = "Сохранить", cancel = "Отмена", enabled = "Включено",
            disabled = "Отключено", noReminders = "Напоминаний пока нет", addFirstReminder = "Добавьте первое напоминание",
            relevanceLow = "Низкий", relevanceMedium = "Средний", relevanceHigh = "Высокий",
            contextImportant = "Важно", contextPossibly = "Возможно связано", contextMayBe = "Может быть связано"
        )
        Language.KAZAKH -> UiStrings(
            registerTitle = "Тіркелу", settingsTitle = "Баптаулар", onboardingSub = "Зияткерлік дыбыстық қорғау",
            userNameLabel = "Пайдаланушы аты", chooseMode = "Режимді таңдаңыз", chooseLanguage = "Тілді таңдаңыз",
            saveAction = "Жалғастыру", saveChangesAction = "Өзгерістерді сақтау", cancelAction = "Артқа",
            hello = "Сәлем", currentMode = "Режим", monitoringActive = "Бақылау белсенді",
            monitoringStopped = "Бақылау тоқтатылды", activeSub = "ЖИ дыбыстық қорғау белсенді",
            startMonitoring = "Бақылауды бастау", stopMonitoring = "Бақылауды тоқтату", settings = "Баптаулар",
            resetAction = "Профильді тастау", modeEveryday = "КҮНДЕЛІКТІ", modeEverydaySub = "Күнделікті қауіпсіздік үшін",
            modeParent = "АТА-АНА", modeParentSub = "Балаға бағытталған қорғау", modeDeaf = "ЕСТУІ ШЕКТЕЛГЕН",
            modeDeafSub = "Үнсіз қорғау және діріл", soundLibrary = "Қауіпті дыбыстар кітапханасы",
            catTransport = "КӨЛІК ЖӘНЕ КӨШЕ", catHome = "ҮЙ ЖӘНЕ ҚАУІПСІЗДІК", catEmergency = "ДЕНСАУЛЫҚ ЖӘНЕ ТЖ",
            catAccessibility = "ҚОЛЖЕТІМДІЛІК", beta = "Бета", resetDefaults = "Режим бойынша тастау",
            headphoneNote = "AIqyn дыбыстарды дәл бақылау және дауыстық ескертулер үшін құлаққаптармен жақсы жұмыс істейді.",
            nameRecognitionTitle = "Есімді тану",
            nameRecognitionDesc = "Alqyn бұл сөзді кез келген адамның дауысымен тани алады.",
            recordAction = "Жазу", reRecordAction = "Қайта жазу", deleteAction = "Өшіру",
            trainingSuccess = "Есім сәтті танылды!", listeningAction = "Тыңдап тұрмын...",
            trainingInstruction = "Функция дұрыс жұмыс істеуі үшін өз есіміңізді анық айтыңыз.",
            startTrainingAction = "Жазуды бастау", voiceSavedSuccess = "Есім сәтті танылды!",
            // Context Reminders
            contextReminders = "Контекст еске салулар", contextRemindersDesc = "AIqyn-ға күніңізде не маңызды екенін айтыңыз, ақылды ескертулер алыңыз.",
            addReminder = "Еске салу қосу", editReminder = "Еске салуды өңдеу", event = "Оқиға",
            time = "Уақыт", location = "Орналасу", destination = "Мақсат", keywords = "Кілт сөздер",
            description = "Сипаттама", save = "Сақтау", cancel = "Болдырмау", enabled = "Қосулы",
            disabled = "Өшірулі", noReminders = "Еске салулар жоқ", addFirstReminder = "Бірінші еске салуды қосыңыз",
            relevanceLow = "Төмен", relevanceMedium = "Орташа", relevanceHigh = "Жоғары",
            contextImportant = "Маңызды", contextPossibly = "Байланысты болуы мүмкін", contextMayBe = "Байланысты болуы мүмкін"
        )
        Language.GERMAN -> UiStrings(
            registerTitle = "Registrierung", settingsTitle = "Einstellungen", onboardingSub = "Intelligenter Schallschutz",
            userNameLabel = "Benutzername", chooseMode = "Modus wählen", chooseLanguage = "Sprache wählen",
            saveAction = "Fortfahren", saveChangesAction = "Änderungen speichern", cancelAction = "Abbrechen",
            hello = "Hallo", currentMode = "Modus", monitoringActive = "Überwachung aktiv",
            monitoringStopped = "Überwachung gestoppt", activeSub = "KI-Schallschutz ist aktiv",
            startMonitoring = "Überwachung starten", stopMonitoring = "Überwachung stoppen", settings = "Einstellungen",
            resetAction = "Profil zurücksetzen", modeEveryday = "ALLTAG", modeEverydaySub = "Für tägliche Sicherheit",
            modeParent = "ELTERN", modeParentSub = "Schutz mit Fokus auf das Kind", modeDeaf = "GEHÖRLOS",
            modeDeafSub = "Geräuschloser Schutz & Vibration", soundLibrary = "Gefahren-Soundbibliothek",
            catTransport = "STRASSENVERKEHR", catHome = "HEIM & SICHERHEIT", catEmergency = "GESUNDHEIT & NOTFALL",
            catAccessibility = "ZUGÄNGLICHKEIT", beta = "Beta", resetDefaults = "Auf Standard zurücksetzen",
            headphoneNote = "AIqyn funktioniert am besten mit Kopfhörern für eine präzise Schallüberwachung und Sprachalarme.",
            nameRecognitionTitle = "Namenserkennung",
            nameRecognitionDesc = "Alqyn erkennt dieses Wort unabhängig von der Stimme der Person.",
            recordAction = "Aufnehmen", reRecordAction = "Erneut aufnehmen", deleteAction = "Löschen",
            trainingSuccess = "Name erfolgreich trainiert!", listeningAction = "Ich höre zu...",
            trainingInstruction = "Damit die Funktion richtig funktioniert, sprechen Sie Ihren Namen deutlich aus.",
            startTrainingAction = "Aufnahme starten", voiceSavedSuccess = "Name erfolgreich trainiert!",
            // Context Reminders
            contextReminders = "Kontext-Erinnerungen", contextRemindersDesc = "Teilen Sie AIqyn mit, was in Ihrem Tag wichtig ist, für intelligentere Benachrichtigungen.",
            addReminder = "Erinnerung hinzufügen", editReminder = "Erinnerung bearbeiten", event = "Ereignis",
            time = "Zeit", location = "Standort", destination = "Ziel", keywords = "Schlüsselwörter",
            description = "Beschreibung", save = "Speichern", cancel = "Abbrechen", enabled = "Aktiviert",
            disabled = "Deaktiviert", noReminders = "Keine Erinnerungen", addFirstReminder = "Erste Erinnerung hinzufügen",
            relevanceLow = "Niedrig", relevanceMedium = "Mittel", relevanceHigh = "Hoch",
            contextImportant = "Wichtig", contextPossibly = "Möglicherweise verbunden", contextMayBe = "Könnte verbunden sein"
        )
        Language.SPANISH -> UiStrings(
            registerTitle = "Registro", settingsTitle = "Ajustes", onboardingSub = "Protección de sonido inteligente",
            userNameLabel = "Nombre de usuario", chooseMode = "Elegir modo", chooseLanguage = "Elegir idioma",
            saveAction = "Continuar", saveChangesAction = "Guardar cambios", cancelAction = "Cancelar",
            hello = "Hola", currentMode = "Modo", monitoringActive = "Monitoreo activo",
            monitoringStopped = "Monitoreo detenido", activeSub = "Protección de sonido IA activa",
            startMonitoring = "Iniciar monitoreo", stopMonitoring = "Detener monitoreo", settings = "Ajustes",
            resetAction = "Restablecer perfil", modeEveryday = "DIARIO", modeEverydaySub = "Para la seguridad diaria",
            modeParent = "PADRE", modeParentSub = "Protección enfocada en el niño", modeDeaf = "SORDO",
            modeDeafSub = "Protección silenciosa y vibración", soundLibrary = "Biblioteca de sonidos peligrosos",
            catTransport = "TRANSPORTE Y CALLE", catHome = "HOGAR Y SEGURIDAD", catEmergency = "SALUD Y EMERGENCIA",
            catAccessibility = "ACCESIBILIDAD", beta = "Beta", resetDefaults = "Restablecer a valores predeterminados",
            headphoneNote = "AIqyn funciona mejor con auriculares para un monitoreo de sonido preciso y alertas de voz.",
            nameRecognitionTitle = "Reconocimiento de nombre",
            nameRecognitionDesc = "Alqyn reconoce esta palabra independientemente de la voz de la persona.",
            recordAction = "Grabar", reRecordAction = "Volver a grabar", deleteAction = "Eliminar",
            trainingSuccess = "¡Nombre entrenado con éxito!", listeningAction = "Escuchando...",
            trainingInstruction = "Para que la función funcione correctamente, diga su nombre claramente.",
            startTrainingAction = "Iniciar grabación", voiceSavedSuccess = "¡Nombre entrenado con éxito!",
            // Context Reminders
            contextReminders = "Recordatorios de contexto", contextRemindersDesc = "Dile a AIqyn qué es importante en tu día para obtener notificaciones más inteligentes.",
            addReminder = "Agregar recordatorio", editReminder = "Editar recordatorio", event = "Evento",
            time = "Hora", location = "Ubicación", destination = "Destino", keywords = "Palabras clave",
            description = "Descripción", save = "Guardar", cancel = "Cancelar", enabled = "Habilitado",
            disabled = "Deshabilitado", noReminders = "Sin recordatorios", addFirstReminder = "Agrega tu primer recordatorio",
            relevanceLow = "Bajo", relevanceMedium = "Medio", relevanceHigh = "Alto",
            contextImportant = "Importante", contextPossibly = "Posiblemente relacionado", contextMayBe = "Puede estar relacionado"
        )
        Language.ARABIC -> UiStrings(
            registerTitle = "تسجيل", settingsTitle = "الإعدادات", onboardingSub = "حماية صوتية ذكية",
            userNameLabel = "اسم المستخدم", chooseMode = "اختر الوضع", chooseLanguage = "اختر اللغة",
            saveAction = "متابعة", saveChangesAction = "حفظ التغييرات", cancelAction = "إلغاء",
            hello = "مرحباً", currentMode = "الوضع", monitoringActive = "المراقبة نشطة",
            monitoringStopped = "المراقبة متوقفة", activeSub = "حماية الصوت بالذكاء الاصطناعي نشطة",
            startMonitoring = "بدء المراقبة", stopMonitoring = "إيقاف المراقبة", settings = "الإعدادات",
            resetAction = "إعادة تعيين الملف الشخصي", modeEveryday = "يومي", modeEverydaySub = "للأمان اليومي",
            modeParent = "الوالد", modeParentSub = "حماية تركز على الطفل", modeDeaf = "أصم",
            modeDeafSub = "حماية صامتة واهتزاز", soundLibrary = "مكتبة أصوات الخطر",
            catTransport = "المرور", catHome = "المنزل والأمان", catEmergency = "الصحة والطوارئ",
            catAccessibility = "سهولة الوصول", beta = "بيتا", resetDefaults = "إعادة التعيين للافتراضيات",
            headphoneNote = "يعمل AIqyn بشكل أفضل مع سماعات الرأس لمراقبة الصوت وتنبيهات الصوت بدقة.",
            nameRecognitionTitle = "التعرف على الاسم",
            nameRecognitionDesc = "يتعرف Alqyn على هذه الكلمة بغض النظر عن صوت الشخص.",
            recordAction = "تسجيل", reRecordAction = "إعادة تسجيل", deleteAction = "حذف",
            trainingSuccess = "تم تدريب الاسم بنجاح!", listeningAction = "جاري الاستماع...",
            trainingInstruction = "لكي تعمل الوظيفة بشكل صحيح، انطق اسمك بوضوح.",
            startTrainingAction = "بدء التسجيل", voiceSavedSuccess = "تم تدريب الاسم بنجاح!",
            // Context Reminders
            contextReminders = "تذكيرات السياق", contextRemindersDesc = "أخبر AIqyn بما هو مهم في يومك للحصول على تنبيهات أكثر ذكاءً.",
            addReminder = "إضافة تذكير", editReminder = "تعديل التذكير", event = "الحدث",
            time = "الوقت", location = "الموقع", destination = "الوجهة", keywords = "الكلمات المفتاحية",
            description = "الوصف", save = "حفظ", cancel = "إلغاء", enabled = "مفعّل",
            disabled = "معطل", noReminders = "لا توجد تذكيرات", addFirstReminder = "أضف أول تذكير",
            relevanceLow = "منخفض", relevanceMedium = "متوسط", relevanceHigh = "عالي",
            contextImportant = "مهم", contextPossibly = "قد يكون مرتبطًا", contextMayBe = "قد يكون مرتبطًا"
        )
        Language.CHINESE -> UiStrings(
            registerTitle = "注册", settingsTitle = "设置", onboardingSub = "智能声音保护",
            userNameLabel = "用户名", chooseMode = "选择模式", chooseLanguage = "选择语言",
            saveAction = "继续", saveChangesAction = "保存更改", cancelAction = "取消",
            hello = "你好", currentMode = "模式", monitoringActive = "监控已启动",
            monitoringStopped = "监控已停止", activeSub = "AI声音保护已开启",
            startMonitoring = "开始监控", stopMonitoring = "停止监控", settings = "设置",
            resetAction = "重置个人资料", modeEveryday = "日常", modeEverydaySub = "用于日常安全",
            modeParent = "父母", modeParentSub = "专注于儿童保护", modeDeaf = "听障",
            modeDeafSub = "静音保护和振动", soundLibrary = "危险声音库",
            catTransport = "交通", catHome = "家庭与安全", catEmergency = "健康与紧急",
            catAccessibility = "无障碍", beta = "Beta", resetDefaults = "重置为默认值",
            headphoneNote = "AIqyn 配合耳机使用效果最佳，可实现精准的声音监控和语音警报。",
            nameRecognitionTitle = "姓名识别",
            nameRecognitionDesc = "Alqyn 可以独立于人的声音识别这个词。",
            recordAction = "录制", reRecordAction = "重新录制", deleteAction = "删除",
            trainingSuccess = "姓名训练成功！", listeningAction = "正在聆听...",
            trainingInstruction = "为了使功能正常工作，请清晰地说出您的姓名。",
            startTrainingAction = "开始录制", voiceSavedSuccess = "姓名训练成功！",
            // Context Reminders
            contextReminders = "上下文提醒", contextRemindersDesc = "告诉AIqyn您一天中重要的事情，以获得更智能的通知。",
            addReminder = "添加提醒", editReminder = "编辑提醒", event = "事件",
            time = "时间", location = "位置", destination = "目的地", keywords = "关键词",
            description = "描述", save = "保存", cancel = "取消", enabled = "已启用",
            disabled = "已禁用", noReminders = "没有提醒", addFirstReminder = "添加第一个提醒",
            relevanceLow = "低", relevanceMedium = "中", relevanceHigh = "高",
            contextImportant = "重要", contextPossibly = "可能相关", contextMayBe = "可能相关"
        )
        else -> UiStrings(
            registerTitle = "Register", settingsTitle = "Settings", onboardingSub = "Intelligent sound protection",
            userNameLabel = "User Name", chooseMode = "Choose Mode", chooseLanguage = "Select Language",
            saveAction = "Continue", saveChangesAction = "Save Changes", cancelAction = "Back",
            hello = "Hello", currentMode = "Mode", monitoringActive = "Monitoring Active",
            monitoringStopped = "Monitoring Stopped", activeSub = "AI sound protection is active",
            startMonitoring = "Start Monitoring", stopMonitoring = "Stop Monitoring", settings = "Settings",
            resetAction = "Reset Profile", modeEveryday = "EVERYDAY", modeEverydaySub = "For daily safety",
            modeParent = "PARENT", modeParentSub = "Child-focused protection", modeDeaf = "DEAF",
            modeDeafSub = "Silent protection & vibration", soundLibrary = "Danger Sound Library",
            catTransport = "TRANSPORT & STREET", catHome = "HOME & SAFETY", catEmergency = "HEALTH & EMERGENCY",
            catAccessibility = "DEAF ACCESSIBILITY", beta = "Beta", resetDefaults = "Reset to Mode Defaults",
            headphoneNote = "AIqyn works best with headphones for accurate real-world sound monitoring and voice alerts.",
            nameRecognitionTitle = "Name Recognition",
            nameRecognitionDesc = "Alqyn detects this word regardless of the speaker's voice.",
            recordAction = "Test", reRecordAction = "Re-record", deleteAction = "Delete",
            trainingSuccess = "Name successfully recognized!", listeningAction = "Listening...",
            trainingInstruction = "To make the feature work correctly, pronounce your name clearly.",
            startTrainingAction = "Start Recording", voiceSavedSuccess = "Voice saved successfully",
            // Context Reminders
            contextReminders = "Context Reminders", contextRemindersDesc = "Tell AIqyn what's important in your day for smarter notifications.",
            addReminder = "Add Reminder", editReminder = "Edit Reminder", event = "Event",
            time = "Time", location = "Location", destination = "Destination", keywords = "Keywords",
            description = "Description", save = "Save", cancel = "Cancel", enabled = "Enabled",
            disabled = "Disabled", noReminders = "No reminders yet", addFirstReminder = "Add your first reminder",
            relevanceLow = "Low", relevanceMedium = "Medium", relevanceHigh = "High",
            contextImportant = "Important", contextPossibly = "Possibly related", contextMayBe = "May be related"
        )
    }
}

private fun getContextString(uiStrings: UiStrings, key: String): String {
    return when (key) {
        "contextReminders" -> uiStrings.contextReminders
        "addReminder" -> uiStrings.addReminder
        "editReminder" -> uiStrings.editReminder
        "event" -> uiStrings.event
        "time" -> uiStrings.time
        "location" -> uiStrings.location
        "destination" -> uiStrings.destination
        "keywords" -> uiStrings.keywords
        "description" -> uiStrings.description
        "save" -> uiStrings.save
        "cancel" -> uiStrings.cancel
        "enabled" -> uiStrings.enabled
        "disabled" -> uiStrings.disabled
        "noReminders" -> uiStrings.noReminders
        "addFirstReminder" -> uiStrings.addFirstReminder
        else -> key
    }
}

@Composable
fun UniversalNameField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    isError: Boolean,
    errorText: String?,
    modifier: Modifier = Modifier,
    onMicClick: (() -> Unit)? = null,
    isListening: Boolean = false
) {
    Column(modifier = modifier) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(
                value = value,
                onValueChange = { newValue ->
                    val sanitizedText = newValue.text.replace(Regex("[\\u202A-\\u202E\\u2066-\\u2069]"), "")
                    val diff = newValue.text.length - sanitizedText.length
                    val newSelection = if (diff > 0) TextRange(sanitizedText.length) else newValue.selection
                    onValueChange(TextFieldValue(sanitizedText, newSelection))
                },
                label = { Text(text = label, style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)) },
                trailingIcon = {
                    if (onMicClick != null) {
                        IconButton(onClick = onMicClick) {
                            Icon(painter = painterResource(id = android.R.drawable.ic_btn_speak_now), contentDescription = null, tint = if (isListening) CosmicPurple else Color.White.copy(alpha = 0.6f))
                        }
                    }
                },
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Start),
                visualTransformation = VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = isError,
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CosmicPurple, unfocusedBorderColor = GlassWhiteHigh, focusedLabelColor = CosmicPurple, errorBorderColor = Color(0xFFFF5252))
            )
        }
        if (errorText != null) {
            Text(text = errorText, color = Color(0xFFFF5252), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        }
    }
}

@Composable
private fun RegistrationScreen(modifier: Modifier = Modifier, session: SettingsSession, onSessionUpdate: (SettingsSession) -> Unit, onSave: (String, Mode, Language) -> Unit, onOpenLibrary: () -> Unit, prefs: UserPreferencesRepository) {
    UserEditor(modifier = modifier, initialName = session.name, initialMode = session.mode, initialLanguage = session.language, sessionSounds = session.selectedSounds, initialNameRecognitionEnabled = session.nameRecognitionEnabled, initialNamePhoneticVariants = session.namePhoneticVariants, isRegistration = true, onSessionUpdate = onSessionUpdate, onSubmit = onSave, onCancel = null, onReset = null, onOpenLibrary = onOpenLibrary, uiStrings = getUiStrings(session.language), prefs = prefs)
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier, session: SettingsSession, onSessionUpdate: (SettingsSession) -> Unit, onSave: (String, Mode, Language) -> Unit, onCancel: () -> Unit, onReset: () -> Unit, onOpenLibrary: () -> Unit, onOpenContextReminders: () -> Unit, onOpenDebugTest: (() -> Unit)? = null, prefs: UserPreferencesRepository) {
    UserEditor(modifier = modifier, initialName = session.name, initialMode = session.mode, initialLanguage = session.language, sessionSounds = session.selectedSounds, initialNameRecognitionEnabled = session.nameRecognitionEnabled, initialNamePhoneticVariants = session.namePhoneticVariants, isRegistration = false, onSessionUpdate = onSessionUpdate, onSubmit = onSave, onCancel = onCancel, onReset = onReset, onOpenLibrary = onOpenLibrary, onOpenContextReminders = onOpenContextReminders, onOpenDebugTest = onOpenDebugTest, uiStrings = getUiStrings(session.language), prefs = prefs)
}

@Composable
private fun UserEditor(
    modifier: Modifier = Modifier, initialName: String, initialMode: Mode, initialLanguage: Language, sessionSounds: Set<String>, initialNameRecognitionEnabled: Boolean, initialNamePhoneticVariants: Set<String>, isRegistration: Boolean, onSessionUpdate: ((SettingsSession) -> Unit)? = null, onSubmit: (String, Mode, Language) -> Unit, onCancel: (() -> Unit)? = null, onReset: (() -> Unit)? = null, onOpenLibrary: (() -> Unit)? = null, onOpenContextReminders: (() -> Unit)? = null, onOpenDebugTest: (() -> Unit)? = null, uiStrings: UiStrings, prefs: UserPreferencesRepository
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(TextFieldValue(initialName, selection = TextRange(initialName.length))) }
    var selectedMode by remember(initialMode) { mutableStateOf(initialMode) }
    var selectedLanguage by remember(initialLanguage) { mutableStateOf(initialLanguage) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var nameRecognitionEnabled by remember { mutableStateOf(initialNameRecognitionEnabled) }
    var namePhoneticVariants by remember { mutableStateOf(initialNamePhoneticVariants) }
    var isListening by remember { mutableStateOf(false) }
    var showTrainingDialog by remember { mutableStateOf(false) }

    val trainingMicPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> 
        if (granted) {
            showTrainingDialog = true
        }
    }

    LaunchedEffect(name.text, selectedMode, selectedLanguage, nameRecognitionEnabled, namePhoneticVariants) {
        onSessionUpdate?.let { update ->
            update(SettingsSession(name.text, selectedMode, selectedLanguage, sessionSounds, true, nameRecognitionEnabled, namePhoneticVariants))
        }
    }
    
    LaunchedEffect(initialName) { if (name.text != initialName) name = TextFieldValue(initialName, selection = TextRange(initialName.length)) }

    if (showTrainingDialog) {
        AlertDialog(
            onDismissRequest = { showTrainingDialog = false },
            containerColor = SpaceNavy,
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.8f),
            title = { Text(uiStrings.nameRecognitionTitle) },
            text = { Text(uiStrings.trainingInstruction) },
            confirmButton = {
                Button(
                    onClick = {
                        showTrainingDialog = false
                        AudioMonitoringService.stop(context)
                        
                        // Give visual feedback immediately
                        isListening = true
                        
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                                Toast.makeText(context, "Speech recognition not available", Toast.LENGTH_SHORT).show()
                                isListening = false
                                return@postDelayed
                            }

                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLanguage.code)
                                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            }
                            
                            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                            recognizer.setRecognitionListener(object : RecognitionListener {
                                override fun onReadyForSpeech(params: Bundle?) { 
                                    Log.d("AIqyn", "Speech Recognizer Ready")
                                    isListening = true 
                                }
                                override fun onBeginningOfSpeech() {
                                    Log.d("AIqyn", "User started speaking")
                                }
                                override fun onRmsChanged(rmsdB: Float) {}
                                override fun onBufferReceived(buffer: ByteArray?) {}
                                override fun onEndOfSpeech() { 
                                    Log.d("AIqyn", "User stopped speaking")
                                    isListening = false 
                                }
                                override fun onError(error: Int) { 
                                    Log.e("AIqyn", "Speech Recognizer Error: $error")
                                    isListening = false
                                    recognizer.destroy() 
                                    // If we were monitoring, maybe we should restart? 
                                    // But for now just notify
                                    Toast.makeText(context, "Try again", Toast.LENGTH_SHORT).show()
                                }
                                override fun onResults(results: Bundle?) {
                                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    Log.d("AIqyn", "Speech Recognizer Results: $matches")
                                    if (!matches.isNullOrEmpty()) {
                                        val detectedName = matches[0]
                                        namePhoneticVariants = matches.take(5).toSet()
                                        
                                        // Update the name field if it's empty, otherwise just variants
                                        if (name.text.isBlank()) {
                                            name = TextFieldValue(detectedName, selection = TextRange(detectedName.length))
                                        }
                                        
                                        nameRecognitionEnabled = true
                                        Toast.makeText(context, uiStrings.trainingSuccess, Toast.LENGTH_SHORT).show()
                                    }
                                    isListening = false
                                    recognizer.destroy()
                                }
                                override fun onPartialResults(partialResults: Bundle?) {
                                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    if (!matches.isNullOrEmpty() && name.text.isBlank()) {
                                         // Optionally show partial results in UI
                                         Log.d("AIqyn", "Partial results: $matches")
                                    }
                                }
                                override fun onEvent(eventType: Int, params: Bundle?) { }
                            })
                            
                            try {
                                recognizer.startListening(intent)
                            } catch (e: Exception) {
                                Log.e("AIqyn", "Failed to start listening: ${e.message}")
                                isListening = false
                                recognizer.destroy()
                            }
                        }, 500) // Increased delay to ensure mic is released
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CosmicPurple)
                ) { Text(uiStrings.startTrainingAction) }
            },
            dismissButton = {
                TextButton(onClick = { showTrainingDialog = false }) {
                    Text(uiStrings.cancelAction, color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (isRegistration) {
            Text("AIqyn", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, color = CosmicPurple)
            Text(uiStrings.onboardingSub, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 32.dp))
        } else {
            Text(uiStrings.settingsTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = CosmicPurple, modifier = Modifier.padding(bottom = 24.dp))
        }

        UniversalNameField(
            value = name,
            onValueChange = { name = it; nameError = null },
            label = uiStrings.userNameLabel,
            isError = nameError != null,
            errorText = nameError,
            modifier = Modifier.fillMaxWidth(),
            onMicClick = { 
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    showTrainingDialog = true 
                } else {
                    trainingMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            isListening = isListening
        )

        AnimatedVisibility(visible = name.text.length > 1, enter = expandVertically(), exit = shrinkVertically()) {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), colors = CardDefaults.cardColors(containerColor = GlassWhite), border = BorderStroke(1.dp, if (nameRecognitionEnabled) CosmicPurple else GlassWhiteHigh), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = nameRecognitionEnabled, onCheckedChange = { nameRecognitionEnabled = it }, colors = CheckboxDefaults.colors(checkedColor = GlowCyan))
                        Text(uiStrings.nameRecognitionTitle, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    }
                    Text(uiStrings.nameRecognitionDesc, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(start = 32.dp))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(uiStrings.chooseMode, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        ModeSelector(selectedMode = selectedMode, onModeSelected = { 
            selectedMode = it
            onSessionUpdate?.let { update ->
                update(SettingsSession(name.text, it, selectedLanguage, prefs.getDefaultSoundsForMode(it), false, nameRecognitionEnabled))
            }
        }, uiStrings = uiStrings)

        Spacer(Modifier.height(24.dp))
        Text(uiStrings.chooseLanguage, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        LanguageSelector(selectedLanguage = selectedLanguage, onLanguageSelected = { selectedLanguage = it })

        HeadphoneNote(uiStrings)

        if (onOpenLibrary != null) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, CosmicPurple), colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowCyan), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(painter = painterResource(id = android.R.drawable.ic_menu_agenda), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(text = uiStrings.soundLibrary, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }

        if (onOpenContextReminders != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenContextReminders, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, GlassWhiteHigh), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(painter = painterResource(id = android.R.drawable.ic_menu_my_calendar), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(text = getContextString(uiStrings, "contextReminders"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }

        // Debug-only button for Context Test
        if (onOpenDebugTest != null && isDebugMode(context)) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenDebugTest, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, Color(0xFFFF5252)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(painter = painterResource(id = android.R.drawable.ic_menu_edit), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(text = "DEBUG: Context Test", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }

        Spacer(Modifier.height(40.dp))
        Button(onClick = {
            if (name.text.trim().isNotEmpty()) onSubmit(name.text.trim(), selectedMode, selectedLanguage)
            else nameError = if (selectedLanguage == Language.RUSSIAN) "Введите имя" else "Name is required"
        }, modifier = Modifier.fillMaxWidth().height(56.dp).shadow(12.dp, RoundedCornerShape(28.dp), ambientColor = CosmicPurple, spotColor = CosmicPurple), shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = CosmicPurple, contentColor = Color.White)) {
            Text(if (isRegistration) uiStrings.saveAction else uiStrings.saveChangesAction, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (onCancel != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, GlassWhiteHigh), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                Text(uiStrings.cancelAction, style = MaterialTheme.typography.titleMedium)
            }
        }

        if (onReset != null) {
            Spacer(Modifier.height(32.dp))
            Text(uiStrings.resetAction, color = Color(0xFFFF5252), style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { onReset() }.padding(8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HeadphoneNote(uiStrings: UiStrings) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = CardDefaults.cardColors(containerColor = GlowCyan.copy(alpha = 0.05f)), border = BorderStroke(1.dp, GlowCyan.copy(alpha = 0.2f)), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(id = android.R.drawable.ic_dialog_info), contentDescription = null, tint = GlowCyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(uiStrings.headphoneNote, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun SoundLibraryScreen(modifier: Modifier = Modifier, selectedSounds: Set<String>, onSave: (Set<String>) -> Unit, onBack: () -> Unit, uiStrings: UiStrings, language: Language, onResetToDefaults: () -> Unit) {
    var sounds by remember(selectedSounds) { mutableStateOf(selectedSounds) }
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(uiStrings.soundLibrary, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = CosmicPurple, modifier = Modifier.padding(bottom = 24.dp), textAlign = TextAlign.Center)
        LibraryCategory(title = uiStrings.catTransport, options = listOf("SIREN", "VEHICLE_HORN", "TRAIN_HORN", "MOTORCYCLE_HORN", "BICYCLE_BELL", "REVERSE_BEEP", "TIRE_SCREECH", "CRASH", "EMERGENCY_ALARM"), selected = sounds, onToggle = { id -> sounds = if (sounds.contains(id)) sounds - id else sounds + id }, language = language)
        LibraryCategory(title = uiStrings.catHome, options = listOf("DOG_BARK", "FIRE_ALARM", "SMOKE_DETECTOR", "GLASS_BREAKING", "DOORBELL", "DOOR_KNOCK", "DOOR_BANGING", "INTRUDER_ALARM"), selected = sounds, onToggle = { id -> sounds = if (sounds.contains(id)) sounds - id else sounds + id }, language = language)
        LibraryCategory(title = uiStrings.catEmergency, options = listOf("BABY_CRY", "CHILD_CRYING", "HELP_SCREAM", "PANIC_SCREAM", "FIRE_SHOUT", "STOP_SHOUT", "AGGRESSIVE_SCREAM"), selected = sounds, onToggle = { id -> sounds = if (sounds.contains(id)) sounds - id else sounds + id }, language = language, uiStrings = uiStrings)
        LibraryCategory(title = uiStrings.catAccessibility, options = listOf("PHONE_RING", "ALARM_CLOCK", "MICROWAVE_TIMER", "SCHOOL_BELL"), selected = sounds, onToggle = { id -> sounds = if (sounds.contains(id)) sounds - id else sounds + id }, language = language)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onResetToDefaults) { Text(uiStrings.resetDefaults, color = GlowCyan) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSave(sounds) }, modifier = Modifier.fillMaxWidth().height(56.dp).shadow(12.dp, RoundedCornerShape(28.dp), ambientColor = CosmicPurple, spotColor = CosmicPurple), shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = CosmicPurple)) {
            Text(uiStrings.saveChangesAction, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, GlassWhiteHigh), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
            Text(uiStrings.cancelAction, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun LibraryCategory(title: String, options: List<String>, selected: Set<String>, onToggle: (String) -> Unit, language: Language, uiStrings: UiStrings? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = GlowCyan, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        options.forEach { id ->
            var label = getSoundName(id, language)
            if (uiStrings != null && (id == "HELP_SCREAM" || id == "PANIC_SCREAM" || id == "FIRE_SHOUT" || id == "STOP_SHOUT" || id == "AGGRESSIVE_SCREAM")) label += " (${uiStrings.beta})"
            LibraryItem(label = label, isSelected = selected.contains(id), onToggle = { onToggle(id) })
        }
    }
}

private fun getSoundName(id: String, language: Language): String {
    return when(language) {
        Language.RUSSIAN -> when(id) {
            "SIREN" -> "Сирена"; "VEHICLE_HORN" -> "Автомобильный сигнал"; "TRAIN_HORN" -> "Поезд"; "MOTORCYCLE_HORN" -> "Мотоцикл"; "BICYCLE_BELL" -> "Велосипед"; "REVERSE_BEEP" -> "Сигнал заднего хода"; "TIRE_SCREECH" -> "Визг шин"; "CRASH" -> "Удар/Столкновение"; "EMERGENCY_ALARM" -> "Аварийная сигнализация"; "DOG_BARK" -> "Лай собаки"; "FIRE_ALARM" -> "Пожарная тревога"; "SMOKE_DETECTOR" -> "Детектор дыма"; "GLASS_BREAKING" -> "Разбитое стекло"; "DOORBELL" -> "Дверной звонок"; "DOOR_KNOCK" -> "Стук в дверь"; "DOOR_BANGING" -> "Стук дверью"; "INTRUDER_ALARM" -> "Сигнализация"; "BABY_CRY" -> "Плач младенца"; "CHILD_CRYING" -> "Плач ребенка"; "HELP_SCREAM" -> "Крик о помощи"; "PANIC_SCREAM" -> "Панический крик"; "FIRE_SHOUT" -> "Крик 'Пожар!'"; "STOP_SHOUT" -> "Крик 'Стоп!'"; "AGGRESSIVE_SCREAM" -> "Агрессивный крик"; "PHONE_RING" -> "Звонок телефона"; "ALARM_CLOCK" -> "Будильник"; "MICROWAVE_TIMER" -> "Таймер микроволновки"; "SCHOOL_BELL" -> "Школьный звонок"
            else -> id
        }
        Language.KAZAKH -> when(id) {
            "SIREN" -> "Сирена"; "VEHICLE_HORN" -> "Көлік сигналы"; "TRAIN_HORN" -> "Пойыз"; "MOTORCYCLE_HORN" -> "Мотоцикл"; "BICYCLE_BELL" -> "Велосипед"; "REVERSE_BEEP" -> "Артқа жүру сигналы"; "TIRE_SCREECH" -> "Шиналардың сықыры"; "CRASH" -> "Соқтығысу"; "EMERGENCY_ALARM" -> "Апаттық дабыл"; "DOG_BARK" -> "Иттің үргені"; "FIRE_ALARM" -> "Өрт дабылы"; "SMOKE_DETECTOR" -> "Түтін детекторы"; "GLASS_BREAKING" -> "Шыны сынуы"; "DOORBELL" -> "Есік қоңырауы"; "DOOR_KNOCK" -> "Есік қағу"; "DOOR_BANGING" -> "Есіктің тарс етуі"; "INTRUDER_ALARM" -> "Күзет дабылы"; "BABY_CRY" -> "Сәбидің жылағаны"; "CHILD_CRYING" -> "Баланың жылағаны"; "HELP_SCREAM" -> "Көмек сұраған айқай"; "PANIC_SCREAM" -> "Үрейлі айқай"; "FIRE_SHOUT" -> "'Өрт!' деген айқай"; "STOP_SHOUT" -> "'Тоқта!' деген айқай"; "AGGRESSIVE_SCREAM" -> "Агрессивті айқай"; "PHONE_RING" -> "Телефон қоңырауы"; "ALARM_CLOCK" -> "Оятқыш"; "MICROWAVE_TIMER" -> "Таймер"; "SCHOOL_BELL" -> "Мектеп қоңырауы"
            else -> id
        }
        else -> when(id) {
            "SIREN" -> "Siren"; "VEHICLE_HORN" -> "Vehicle Horn"; "TRAIN_HORN" -> "Train Horn"; "MOTORCYCLE_HORN" -> "Motorcycle Horn"; "BICYCLE_BELL" -> "Bicycle Bell"; "REVERSE_BEEP" -> "Reverse Truck Beep"; "TIRE_SCREECH" -> "Tire Screech"; "CRASH" -> "Crash / Collision"; "EMERGENCY_ALARM" -> "Emergency Alarm"; "DOG_BARK" -> "Dog Bark"; "FIRE_ALARM" -> "Fire Alarm"; "SMOKE_DETECTOR" -> "Smoke Detector"; "GLASS_BREAKING" -> "Glass Breaking"; "DOORBELL" -> "Doorbell"; "DOOR_KNOCK" -> "Door Knock"; "DOOR_BANGING" -> "Door Banging"; "INTRUDER_ALARM" -> "Intruder Alarm"; "BABY_CRY" -> "Baby Cry"; "CHILD_CRYING" -> "Child Cry"; "HELP_SCREAM" -> "Help Scream"; "PANIC_SCREAM" -> "Panic Scream"; "FIRE_SHOUT" -> "'Fire!' shout"; "STOP_SHOUT" -> "'Stop!' shout"; "AGGRESSIVE_SCREAM" -> "Aggressive scream"; "PHONE_RING" -> "Phone Ring"; "ALARM_CLOCK" -> "Alarm Clock"; "MICROWAVE_TIMER" -> "Microwave / Timer"; "SCHOOL_BELL" -> "School Bell"
            else -> id
        }
    }
}

@Composable
private fun LibraryItem(label: String, isSelected: Boolean, onToggle: () -> Unit) {
    Card(onClick = onToggle, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = if (isSelected) CosmicPurple.copy(alpha = 0.2f) else GlassWhite), border = BorderStroke(1.dp, if (isSelected) CosmicPurple else GlassWhiteHigh), shape = RoundedCornerShape(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Checkbox(checked = isSelected, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = GlowCyan, uncheckedColor = GlassWhiteHigh, checkmarkColor = SpaceNavy))
            Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(selectedLanguage: Language, onLanguageSelected: (Language) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(value = selectedLanguage.displayName, onValueChange = {}, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CosmicPurple, unfocusedBorderColor = GlassWhiteHigh), modifier = Modifier.menuAnchor().fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(SpaceNavy)) {
            Language.entries.forEach { lang -> DropdownMenuItem(text = { Text(lang.displayName, color = Color.White) }, onClick = { onLanguageSelected(lang); expanded = false }) }
        }
    }
}

@Composable
private fun ModeSelector(selectedMode: Mode, onModeSelected: (Mode) -> Unit, uiStrings: UiStrings) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Mode.entries.forEach { mode ->
            val title = when(mode) { Mode.EVERYDAY -> uiStrings.modeEveryday; Mode.PARENT -> uiStrings.modeParent; Mode.DEAF -> uiStrings.modeDeaf }
            val sub = when(mode) { Mode.EVERYDAY -> uiStrings.modeEverydaySub; Mode.PARENT -> uiStrings.modeParentSub; Mode.DEAF -> uiStrings.modeDeafSub }
            SelectableCard(title = title, subtitle = sub, selected = selectedMode == mode, onClick = { onModeSelected(mode) })
        }
    }
}

@Composable
private fun SelectableCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().shadow(if (selected) 16.dp else 0.dp, RoundedCornerShape(16.dp), ambientColor = CosmicPurple, spotColor = GlowCyan), colors = CardDefaults.cardColors(containerColor = if (selected) CosmicPurple.copy(alpha = 0.3f) else GlassWhite, contentColor = Color.White), border = BorderStroke(width = if (selected) 2.dp else 1.dp, color = if (selected) GlowCyan else GlassWhiteHigh), shape = RoundedCornerShape(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp)) {
            RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = GlowCyan, unselectedColor = GlassWhiteHigh))
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Color.White.copy(alpha = 0.9f))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(modifier: Modifier = Modifier, userName: String, mode: Mode, isMonitoring: Boolean, onStartMonitoring: () -> Unit, onStopMonitoring: () -> Unit, onSettings: () -> Unit, uiStrings: UiStrings) {
    Scaffold(modifier = Modifier.fillMaxSize(), containerColor = Color.Transparent, topBar = {
        TopAppBar(title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "${uiStrings.hello}, ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(text = userName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                Text("${uiStrings.currentMode}: ${when(mode) { Mode.EVERYDAY -> uiStrings.modeEveryday; Mode.PARENT -> uiStrings.modeParent; Mode.DEAF -> uiStrings.modeDeaf }}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f))
            }
        }, actions = { IconButton(onClick = onSettings) { Icon(painter = painterResource(id = android.R.drawable.ic_menu_manage), contentDescription = uiStrings.settings, tint = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
    }) { padding ->
        Column(modifier = modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Card(modifier = Modifier.fillMaxWidth().shadow(24.dp, RoundedCornerShape(32.dp), ambientColor = if (isMonitoring) CosmicPurple else Color.Black), shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = if (isMonitoring) CosmicPurple.copy(alpha = 0.2f) else GlassWhite), border = BorderStroke(1.dp, if (isMonitoring) CosmicPurple else GlassWhiteHigh)) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (isMonitoring) uiStrings.monitoringActive else uiStrings.monitoringStopped, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (isMonitoring) GlowCyan else Color.White)
                    if (isMonitoring) Text(uiStrings.activeSub, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(80.dp))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp).shadow(32.dp, CircleShape, ambientColor = if (isMonitoring) CosmicPurple else GlowCyan, spotColor = if (isMonitoring) CosmicPurple else GlowCyan).clip(CircleShape).background(Brush.radialGradient(if (isMonitoring) listOf(CosmicPurple, SpaceNavy) else listOf(GlowCyan, SpaceNavy))).clickable(onClick = if (isMonitoring) onStopMonitoring else onStartMonitoring)) {
                Text(if (isMonitoring) uiStrings.stopMonitoring else uiStrings.startMonitoring, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

/**
 * Context Reminders Screen
 * Allows users to manage context reminders for smarter notifications
 */
@Composable
private fun ContextRemindersScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    prefs: UserPreferencesRepository,
    language: Language,
    uiStrings: UiStrings
) {
    val context = LocalContext.current
    var reminders by remember { mutableStateOf(prefs.getContextReminders()) }
    var showDialog by remember { mutableStateOf(false) }
    var editingReminder by remember { mutableStateOf<ContextReminder?>(null) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(getContextString(uiStrings, "contextReminders"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = CosmicPurple, modifier = Modifier.padding(bottom = 8.dp))
        Text(uiStrings.contextRemindersDesc, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 24.dp))

        if (reminders.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = GlassWhite),
                border = BorderStroke(1.dp, GlassWhiteHigh)
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(uiStrings.noReminders, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.6f))
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { showDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = CosmicPurple)) {
                        Text(uiStrings.addFirstReminder)
                    }
                }
            }
        } else {
            reminders.forEach { reminder ->
                ReminderCard(
                    reminder = reminder,
                    onToggle = { prefs.toggleContextReminder(reminder.id); reminders = prefs.getContextReminders() },
                    onEdit = { editingReminder = reminder; showDialog = true },
                    onDelete = { prefs.deleteContextReminder(reminder.id); reminders = prefs.getContextReminders() },
                    uiStrings = uiStrings
                )
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CosmicPurple)
            ) {
                Text(uiStrings.addReminder, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(40.dp))
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) {
            Text(uiStrings.cancelAction, style = MaterialTheme.typography.titleMedium)
        }
    }

    if (showDialog) {
        AddReminderDialog(
            reminder = editingReminder,
            onDismiss = { showDialog = false; editingReminder = null },
            onSave = { newReminder ->
                prefs.saveContextReminder(newReminder)
                reminders = prefs.getContextReminders()
                showDialog = false
                editingReminder = null
            },
            uiStrings = uiStrings
        )
    }
}

@Composable
private fun ReminderCard(
    reminder: ContextReminder,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    uiStrings: UiStrings
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (reminder.enabled) CosmicPurple.copy(alpha = 0.2f) else GlassWhite),
        border = BorderStroke(1.dp, if (reminder.enabled) CosmicPurple else GlassWhiteHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(reminder.event, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                Switch(
                    checked = reminder.enabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(checkedThumbColor = GlowCyan, uncheckedThumbColor = GlassWhiteHigh, checkedTrackColor = CosmicPurple.copy(alpha = 0.5f), uncheckedTrackColor = GlassWhiteHigh)
                )
            }
            if (reminder.description.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(reminder.description, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
            }
            if (reminder.keywords.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Keywords: ${reminder.keywords.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                    Text(uiStrings.editReminder)
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))) {
                    Text(uiStrings.deleteAction)
                }
            }
        }
    }
}

@Composable
private fun AddReminderDialog(
    reminder: ContextReminder?,
    onDismiss: () -> Unit,
    onSave: (ContextReminder) -> Unit,
    uiStrings: UiStrings
) {
    var event by remember { mutableStateOf(reminder?.event ?: "") }
    var time by remember { mutableStateOf(reminder?.time ?: "") }
    var location by remember { mutableStateOf(reminder?.location ?: "") }
    var destination by remember { mutableStateOf(reminder?.destination ?: "") }
    var keywords by remember { mutableStateOf(reminder?.keywords?.joinToString(", ") ?: "") }
    var description by remember { mutableStateOf(reminder?.description ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SpaceNavy,
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.8f),
        title = { Text(reminder?.let { uiStrings.editReminder } ?: uiStrings.addReminder) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = event,
                    onValueChange = { event = it },
                    label = { Text(getContextString(uiStrings, "event")) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CosmicPurple, unfocusedBorderColor = GlassWhiteHigh)
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text(getContextString(uiStrings, "time")) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CosmicPurple, unfocusedBorderColor = GlassWhiteHigh)
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text(getContextString(uiStrings, "location")) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CosmicPurple, unfocusedBorderColor = GlassWhiteHigh)
                )
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    label = { Text(getContextString(uiStrings, "destination")) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CosmicPurple, unfocusedBorderColor = GlassWhiteHigh)
                )
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text(getContextString(uiStrings, "keywords")) },
                    placeholder = { Text("e.g., flight, boarding, gate") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CosmicPurple, unfocusedBorderColor = GlassWhiteHigh)
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(getContextString(uiStrings, "description")) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CosmicPurple, unfocusedBorderColor = GlassWhiteHigh)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val keywordSet = keywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    val newReminder = ContextReminder(
                        id = reminder?.id ?: generateId(),
                        event = event,
                        time = time,
                        location = location,
                        destination = destination,
                        keywords = keywordSet,
                        description = description,
                        enabled = reminder?.enabled ?: true
                    )
                    onSave(newReminder)
                },
                colors = ButtonDefaults.buttonColors(containerColor = CosmicPurple)
            ) {
                Text(getContextString(uiStrings, "save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(getContextString(uiStrings, "cancel"), color = Color.White.copy(alpha = 0.6f))
            }
        }
    )
}

private fun generateId(): String {
    return System.currentTimeMillis().toString()
}

/**
 * Debug-only function to check if the app is running in debug mode
 */
private fun isDebugMode(context: Context): Boolean {
    return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

/**
 * Debug Context Test Screen - Only visible in debug builds
 * Allows testing the ContextRelevanceEngine with simulated speech
 */
@Composable
private fun DebugContextTestScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    prefs: UserPreferencesRepository,
    language: Language,
    uiStrings: UiStrings
) {
    val context = LocalContext.current
    var testText by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var matchedReminders by remember { mutableStateOf<List<ContextMatch>>(emptyList()) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("DEBUG: Context Test", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = CosmicPurple, modifier = Modifier.padding(bottom = 16.dp))
        Text("Test ContextRelevanceEngine with simulated speech", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 24.dp))

        // Test input
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            label = { Text("Simulated Speech") },
            placeholder = { Text("e.g., 'Flight to Almaty is now boarding'") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicPurple, unfocusedBorderColor = GlassWhiteHigh, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
        )

        Spacer(Modifier.height(16.dp))

        // Quick test buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    testText = "Flight to Almaty is now boarding"
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = CosmicPurple)
            ) {
                Text("Flight Test")
            }
            Button(
                onClick = {
                    testText = "Can you bring me some water?"
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = GlassWhiteHigh, contentColor = Color.White)
            ) {
                Text("No Match Test")
            }
        }

        Spacer(Modifier.height(24.dp))

        // Run test button
        Button(
            onClick = {
                if (testText.isNotEmpty()) {
                    val reminders = prefs.getContextReminders()
                    val matches = ContextRelevanceEngine.analyzeRelevance(testText, reminders)
                    matchedReminders = matches

                    if (matches.isNotEmpty()) {
                        val highest = ContextRelevanceEngine.getHighestRelevance(matches)
                        testResult = "Detected text: \"$testText\"\nRelevance: ${highest?.relevance}\nMatched: ${highest?.reminder?.event}"
                    } else {
                        testResult = "Detected text: \"$testText\"\nRelevance: NO MATCH"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GlowCyan)
        ) {
            Text("Run Test", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(24.dp))

        // Display results
        if (testResult != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (matchedReminders.isNotEmpty()) CosmicPurple.copy(alpha = 0.2f) else GlassWhite),
                border = BorderStroke(1.dp, if (matchedReminders.isNotEmpty()) CosmicPurple else GlassWhiteHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(testResult!!, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    if (matchedReminders.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        matchedReminders.forEach { match ->
                            Text("• ${match.reminder.event}: ${match.relevance} (${String.format("%.2f", match.confidence)})", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) {
            Text("Back", style = MaterialTheme.typography.titleMedium)
        }
    }
}
