package com.aiqyn.safestudio.service

import com.aiqyn.safestudio.data.Language
import com.aiqyn.safestudio.data.Mode
import org.tensorflow.lite.support.label.Category
import java.util.Locale

enum class DetectedSoundKind {
    SIREN,
    VEHICLE_HORN,
    DOG_BARK,
    CHILD_CRYING,
    EMERGENCY_ALARM,
    TRAIN_HORN,
    MOTORCYCLE_HORN,
    BICYCLE_BELL,
    REVERSE_BEEP,
    TIRE_SCREECH,
    CRASH,
    FIRE_ALARM,
    SMOKE_DETECTOR,
    GLASS_BREAKING,
    DOORBELL,
    DOOR_KNOCK,
    DOOR_BANGING,
    INTRUDER_ALARM,
    BABY_CRY,
    HELP_SCREAM,
    PANIC_SCREAM,
    FIRE_SHOUT,
    STOP_SHOUT,
    AGGRESSIVE_SCREAM,
    PHONE_RING,
    ALARM_CLOCK,
    MICROWAVE_TIMER,
    SCHOOL_BELL
}

data class SoundDetection(
    val kind: DetectedSoundKind,
    val score: Float,
    val matchedLabel: String,
)

/**
 * Maps YAMNet AudioSet labels to app alerts. Uses fuzzy keyword matching and multilingual support.
 */
object YamNetCategoryMatcher {

    const val CONFIDENCE_THRESHOLD = 0.35f // Slightly lowered for better coverage
    private const val DOMINANCE_MARGIN = 0.12f

    fun findDetection(categories: List<Category>, selectedSounds: Set<String>): SoundDetection? {
        if (categories.isEmpty()) return null
        val sorted = categories.sortedByDescending { it.score }
        val top = sorted.first()

        val candidates = sorted.mapNotNull { cat ->
            if (cat.score < CONFIDENCE_THRESHOLD) return@mapNotNull null
            mapLabelToKind(cat.label, selectedSounds)?.let { kind ->
                SoundDetection(kind, cat.score, cat.label)
            }
        }
        if (candidates.isEmpty()) return null

        val best = candidates.maxBy { it.score }

        if (isLikelySpeechOrMusic(top.label) && top.score >= best.score - DOMINANCE_MARGIN) {
            // Check if it's a specific shout we want to detect even over speech
            if (best.kind != DetectedSoundKind.HELP_SCREAM && 
                best.kind != DetectedSoundKind.FIRE_SHOUT && 
                best.kind != DetectedSoundKind.STOP_SHOUT) {
                return null
            }
        }

        return best
    }

    fun notificationMessage(userName: String, detection: SoundDetection, language: Language): String {
        val name = userName.ifBlank { "User" }
        return when (language) {
            Language.RUSSIAN -> {
                val action = when (detection.kind) {
                    DetectedSoundKind.SIREN -> "осторожно, обнаружена сирена"
                    DetectedSoundKind.VEHICLE_HORN -> "внимание, автомобильный сигнал"
                    DetectedSoundKind.DOG_BARK -> "будьте бдительны, лай собаки"
                    DetectedSoundKind.CHILD_CRYING -> "внимание, плач ребенка"
                    DetectedSoundKind.EMERGENCY_ALARM -> "внимание, аварийная сигнализация"
                    DetectedSoundKind.TRAIN_HORN -> "внимание, сигнал поезда"
                    DetectedSoundKind.MOTORCYCLE_HORN -> "внимание, сигнал мотоцикла"
                    DetectedSoundKind.BICYCLE_BELL -> "внимание, велосипедный звонок"
                    DetectedSoundKind.REVERSE_BEEP -> "внимание, звуковой сигнал заднего хода"
                    DetectedSoundKind.TIRE_SCREECH -> "внимание, визг шин"
                    DetectedSoundKind.CRASH -> "внимание, звук удара или столкновения"
                    DetectedSoundKind.FIRE_ALARM -> "внимание, пожарная сигнализация"
                    DetectedSoundKind.SMOKE_DETECTOR -> "внимание, детектор дыма"
                    DetectedSoundKind.GLASS_BREAKING -> "внимание, звук разбитого стекла"
                    DetectedSoundKind.DOORBELL -> "внимание, дверной звонок"
                    DetectedSoundKind.DOOR_KNOCK -> "внимание, стук в дверь"
                    DetectedSoundKind.DOOR_BANGING -> "внимание, стук дверью"
                    DetectedSoundKind.INTRUDER_ALARM -> "внимание, сигнализация вторжения"
                    DetectedSoundKind.BABY_CRY -> "внимание, плач младенца"
                    DetectedSoundKind.HELP_SCREAM -> "внимание, крик о помощи"
                    DetectedSoundKind.PANIC_SCREAM -> "внимание, панический крик"
                    DetectedSoundKind.FIRE_SHOUT -> "внимание, крик 'Пожар!'"
                    DetectedSoundKind.STOP_SHOUT -> "внимание, крик 'Стоп!'"
                    DetectedSoundKind.AGGRESSIVE_SCREAM -> "внимание, агрессивный крик"
                    DetectedSoundKind.PHONE_RING -> "внимание, звонок телефона"
                    DetectedSoundKind.ALARM_CLOCK -> "внимание, будильник"
                    DetectedSoundKind.MICROWAVE_TIMER -> "внимание, таймер микроволновки"
                    DetectedSoundKind.SCHOOL_BELL -> "внимание, школьный звонок"
                }
                "$name, $action."
            }
            Language.KAZAKH -> {
                val action = when (detection.kind) {
                    DetectedSoundKind.SIREN -> "абай болыңыз, сирена анықталды"
                    DetectedSoundKind.VEHICLE_HORN -> "сақ болыңыз, көлік сигналы анықталды"
                    DetectedSoundKind.DOG_BARK -> "абайлаңыз, иттің үргені анықталды"
                    DetectedSoundKind.CHILD_CRYING -> "назар аударыңыз, баланың жылағаны анықталды"
                    DetectedSoundKind.EMERGENCY_ALARM -> "назар аударыңыз, апаттық дабыл анықталды"
                    DetectedSoundKind.TRAIN_HORN -> "назар аударыңыз, пойыз сигналы анықталды"
                    DetectedSoundKind.MOTORCYCLE_HORN -> "назар аударыңыз, мотоцикл сигналы анықталды"
                    DetectedSoundKind.BICYCLE_BELL -> "назар аударыңыз, велосипед қоңырауы анықталды"
                    DetectedSoundKind.REVERSE_BEEP -> "назар аударыңыз, артқа жүру сигналы анықталды"
                    DetectedSoundKind.TIRE_SCREECH -> "назар аударыңыз, шиналардың сықыры анықталды"
                    DetectedSoundKind.CRASH -> "назар аударыңыз, соқтығысу дыбысы анықталды"
                    DetectedSoundKind.FIRE_ALARM -> "назар аударыңыз, өрт дабылы анықталды"
                    DetectedSoundKind.SMOKE_DETECTOR -> "назар аударыңыз, түтін детекторы анықталды"
                    DetectedSoundKind.GLASS_BREAKING -> "назар аударыңыз, шыны сынуы анықталды"
                    DetectedSoundKind.DOORBELL -> "назар аударыңыз, есік қоңырауы анықталды"
                    DetectedSoundKind.DOOR_KNOCK -> "назар аударыңыз, есік қағу анықталды"
                    DetectedSoundKind.DOOR_BANGING -> "назар аударыңыз, есіктің тарс еткені анықталды"
                    DetectedSoundKind.INTRUDER_ALARM -> "назар аударыңыз, күзет дабылы анықталды"
                    DetectedSoundKind.BABY_CRY -> "назар аударыңыз, сәбидің жылағаны анықталды"
                    DetectedSoundKind.HELP_SCREAM -> "назар аударыңыз, көмек сұраған айқай анықталды"
                    DetectedSoundKind.PANIC_SCREAM -> "назар аударыңыз, үрейлі айқай анықталды"
                    DetectedSoundKind.FIRE_SHOUT -> "назар аударыңыз, 'Өрт!' деген айқай анықталды"
                    DetectedSoundKind.STOP_SHOUT -> "назар аударыңыз, 'Тоқта!' деген айқай анықталды"
                    DetectedSoundKind.AGGRESSIVE_SCREAM -> "назар аударыңыз, агрессивті айқай анықталды"
                    DetectedSoundKind.PHONE_RING -> "назар аударыңыз, телефон қоңырауы анықталды"
                    DetectedSoundKind.ALARM_CLOCK -> "назар аударыңыз, оятқыш анықталды"
                    DetectedSoundKind.MICROWAVE_TIMER -> "назар аударыңыз, микротолқынды пеш таймері анықталды"
                    DetectedSoundKind.SCHOOL_BELL -> "назар аударыңыз, мектеп қоңырауы анықталды"
                }
                "$name, $action."
            }
            Language.TURKISH -> {
                val action = when (detection.kind) {
                    DetectedSoundKind.SIREN -> "dikkatli olun, siren tespit edildi"
                    DetectedSoundKind.VEHICLE_HORN -> "dikkat, araç kornası tespit edildi"
                    DetectedSoundKind.DOG_BARK -> "dikkatli olun, köpek havlaması tespit edildi"
                    DetectedSoundKind.CHILD_CRYING -> "dikkat, çocuk ağlaması tespit edildi"
                    DetectedSoundKind.EMERGENCY_ALARM -> "dikkat, acil durum alarmı tespit edildi"
                    DetectedSoundKind.TRAIN_HORN -> "dikkat, tren kornası tespit edildi"
                    DetectedSoundKind.MOTORCYCLE_HORN -> "dikkat, motosiklet kornası tespit edildi"
                    DetectedSoundKind.BICYCLE_BELL -> "dikkat, bisiklet zili tespit edildi"
                    DetectedSoundKind.REVERSE_BEEP -> "dikkat, geri vites ikazı tespit edildi"
                    DetectedSoundKind.TIRE_SCREECH -> "dikkat, fren sesi tespit edildi"
                    DetectedSoundKind.CRASH -> "dikkat, kaza sesi tespit edildi"
                    DetectedSoundKind.FIRE_ALARM -> "dikkat, yangın alarmı tespit edildi"
                    DetectedSoundKind.SMOKE_DETECTOR -> "dikkat, duman dedektörü tespit edildi"
                    DetectedSoundKind.GLASS_BREAKING -> "dikkat, cam kırılma sesi tespit edildi"
                    DetectedSoundKind.DOORBELL -> "dikkat, kapı zili tespit edildi"
                    DetectedSoundKind.DOOR_KNOCK -> "dikkat, kapı vurulması tespit edildi"
                    DetectedSoundKind.DOOR_BANGING -> "dikkat, kapı çarpması tespit edildi"
                    DetectedSoundKind.INTRUDER_ALARM -> "dikkat, hırsız alarmı tespit edildi"
                    DetectedSoundKind.BABY_CRY -> "dikkat, bebek ağlaması tespit edildi"
                    DetectedSoundKind.HELP_SCREAM -> "dikkat, yardım çığlığı tespit edildi"
                    DetectedSoundKind.PANIC_SCREAM -> "dikkat, panik çığlığı tespit edildi"
                    DetectedSoundKind.FIRE_SHOUT -> "dikkat, 'Yangın!' bağırması tespit edildi"
                    DetectedSoundKind.STOP_SHOUT -> "dikkat, 'Dur!' bağırması tespit edildi"
                    DetectedSoundKind.AGGRESSIVE_SCREAM -> "dikkat, agresif çığlık tespit edildi"
                    DetectedSoundKind.PHONE_RING -> "dikkat, telefon çalıyor"
                    DetectedSoundKind.ALARM_CLOCK -> "dikkat, çalar saat çalıyor"
                    DetectedSoundKind.MICROWAVE_TIMER -> "dikkat, mikrodalga zamanlayıcısı bitti"
                    DetectedSoundKind.SCHOOL_BELL -> "dikkat, okul zili çalıyor"
                }
                "$name, $action."
            }
            Language.SPANISH -> {
                val action = when (detection.kind) {
                    DetectedSoundKind.SIREN -> "tenga cuidado, sirena detectada"
                    DetectedSoundKind.VEHICLE_HORN -> "atención, bocina de vehículo detectada"
                    DetectedSoundKind.DOG_BARK -> "tenga cuidado, ladrido de perro detectado"
                    DetectedSoundKind.CHILD_CRYING -> "atención, llanto de niño detectado"
                    DetectedSoundKind.EMERGENCY_ALARM -> "atención, alarma de emergencia detectada"
                    DetectedSoundKind.TRAIN_HORN -> "atención, bocina de tren detectada"
                    DetectedSoundKind.MOTORCYCLE_HORN -> "atención, bocina de motocicleta detectada"
                    DetectedSoundKind.BICYCLE_BELL -> "atención, timbre de bicicleta detectado"
                    DetectedSoundKind.REVERSE_BEEP -> "atención, pitido de reversa detectado"
                    DetectedSoundKind.TIRE_SCREECH -> "atención, chirrido de llantas detectado"
                    DetectedSoundKind.CRASH -> "atención, sonido de choque detectado"
                    DetectedSoundKind.FIRE_ALARM -> "atención, alarma de incendio detectada"
                    DetectedSoundKind.SMOKE_DETECTOR -> "atención, detector de humo detectado"
                    DetectedSoundKind.GLASS_BREAKING -> "atención, sonido de cristales rotos"
                    DetectedSoundKind.DOORBELL -> "atención, timbre de la puerta detectado"
                    DetectedSoundKind.DOOR_KNOCK -> "atención, alguien llama a la puerta"
                    DetectedSoundKind.DOOR_BANGING -> "atención, portazo detectado"
                    DetectedSoundKind.INTRUDER_ALARM -> "atención, alarma de intruso detectada"
                    DetectedSoundKind.BABY_CRY -> "atención, llanto de bebé detectado"
                    DetectedSoundKind.HELP_SCREAM -> "atención, grito de auxilio detectado"
                    DetectedSoundKind.PANIC_SCREAM -> "atención, grito de pánico detectado"
                    DetectedSoundKind.FIRE_SHOUT -> "atención, grito de '¡Fuego!' detectado"
                    DetectedSoundKind.STOP_SHOUT -> "atención, grito de '¡Alto!' detectado"
                    DetectedSoundKind.AGGRESSIVE_SCREAM -> "atención, grito agresivo detectado"
                    DetectedSoundKind.PHONE_RING -> "atención, el teléfono está sonando"
                    DetectedSoundKind.ALARM_CLOCK -> "atención, el despertador está sonando"
                    DetectedSoundKind.MICROWAVE_TIMER -> "atención, temporizador de microondas terminado"
                    DetectedSoundKind.SCHOOL_BELL -> "atención, timbre escolar detectado"
                }
                "$name, $action."
            }
            Language.ARABIC -> {
                val action = when (detection.kind) {
                    DetectedSoundKind.SIREN -> "كن حذرًا، تم اكتشاف صافرة إنذار"
                    DetectedSoundKind.VEHICLE_HORN -> "انتباه، تم اكتشاف بوق سيارة"
                    DetectedSoundKind.DOG_BARK -> "كن حذرًا، تم اكتشاف نباح كلب"
                    DetectedSoundKind.CHILD_CRYING -> "انتباه، تم اكتشاف بكاء طفل"
                    DetectedSoundKind.EMERGENCY_ALARM -> "انتباه، تم اكتشاف إنذار طوارئ"
                    DetectedSoundKind.TRAIN_HORN -> "انتباه، تم اكتشاف بوق قطار"
                    DetectedSoundKind.MOTORCYCLE_HORN -> "انتباه، تم اكتشاف بوق دراجة نارية"
                    DetectedSoundKind.BICYCLE_BELL -> "انتباه، تم اكتشاف جرس دراجة"
                    DetectedSoundKind.REVERSE_BEEP -> "انتباه، تم اكتشاف صوت تنبيه للرجوع للخلف"
                    DetectedSoundKind.TIRE_SCREECH -> "انتباه، تم اكتشاف صرير إطارات"
                    DetectedSoundKind.CRASH -> "انتباه، تم اكتشاف صوت اصطدام"
                    DetectedSoundKind.FIRE_ALARM -> "انتباه، تم اكتشاف إنذار حريق"
                    DetectedSoundKind.SMOKE_DETECTOR -> "انتباه، تم اكتشاف كاشف دخان"
                    DetectedSoundKind.GLASS_BREAKING -> "انتباه، تم اكتشاف صوت كسر زجاج"
                    DetectedSoundKind.DOORBELL -> "انتباه، تم اكتشاف جرس الباب"
                    DetectedSoundKind.DOOR_KNOCK -> "انتباه، تم اكتشاف طرق على الباب"
                    DetectedSoundKind.DOOR_BANGING -> "انتباه، تم اكتشاف صفق باب"
                    DetectedSoundKind.INTRUDER_ALARM -> "انتباه، تم اكتشاف إنذار اقتحام"
                    DetectedSoundKind.BABY_CRY -> "انتباه، تم اكتشاف بكاء رضيع"
                    DetectedSoundKind.HELP_SCREAM -> "انتباه، تم اكتشاف صرخة طلب مساعدة"
                    DetectedSoundKind.PANIC_SCREAM -> "انتباه، تم اكتشاف صرخة ذعر"
                    DetectedSoundKind.FIRE_SHOUT -> "انتباه، تم اكتشاف صرخة 'حريق!'"
                    DetectedSoundKind.STOP_SHOUT -> "انتباه، تم اكتشاف صرخة 'توقف!'"
                    DetectedSoundKind.AGGRESSIVE_SCREAM -> "انتباه، تم اكتشاف صراخ عدواني"
                    DetectedSoundKind.PHONE_RING -> "انتباه، الهاتف يرن"
                    DetectedSoundKind.ALARM_CLOCK -> "انتباه، المنبه يرن"
                    DetectedSoundKind.MICROWAVE_TIMER -> "انتباه، انتهى مؤقت الميكروويف"
                    DetectedSoundKind.SCHOOL_BELL -> "انتباه، جرس المدرسة يرن"
                }
                "$name، $action."
            }
            Language.CHINESE -> {
                val action = when (detection.kind) {
                    DetectedSoundKind.SIREN -> "请小心，检测到警报声"
                    DetectedSoundKind.VEHICLE_HORN -> "注意，检测到车辆喇叭声"
                    DetectedSoundKind.DOG_BARK -> "请小心，检测到狗叫声"
                    DetectedSoundKind.CHILD_CRYING -> "注意，检测到孩子哭声"
                    DetectedSoundKind.EMERGENCY_ALARM -> "注意，检测到紧急警报"
                    DetectedSoundKind.TRAIN_HORN -> "注意，检测到火车喇叭"
                    DetectedSoundKind.MOTORCYCLE_HORN -> "注意，检测到摩托车喇叭"
                    DetectedSoundKind.BICYCLE_BELL -> "注意，检测到自行车铃声"
                    DetectedSoundKind.REVERSE_BEEP -> "注意，检测到倒车提示音"
                    DetectedSoundKind.TIRE_SCREECH -> "注意，检测到轮胎摩擦声"
                    DetectedSoundKind.CRASH -> "注意，检测到撞击声"
                    DetectedSoundKind.FIRE_ALARM -> "注意，检测到火警"
                    DetectedSoundKind.SMOKE_DETECTOR -> "注意，检测到烟雾报警器"
                    DetectedSoundKind.GLASS_BREAKING -> "注意，检测到玻璃破碎声"
                    DetectedSoundKind.DOORBELL -> "注意，检测到门铃声"
                    DetectedSoundKind.DOOR_KNOCK -> "注意，检测到敲门声"
                    DetectedSoundKind.DOOR_BANGING -> "注意，检测到摔门声"
                    DetectedSoundKind.INTRUDER_ALARM -> "注意，检测到入侵报警"
                    DetectedSoundKind.BABY_CRY -> "注意，检测到婴儿哭声"
                    DetectedSoundKind.HELP_SCREAM -> "注意，检测到求救尖叫声"
                    DetectedSoundKind.PANIC_SCREAM -> "注意，检测到惊恐尖叫声"
                    DetectedSoundKind.FIRE_SHOUT -> "注意，检测到喊'着火了'的声音"
                    DetectedSoundKind.STOP_SHOUT -> "注意，检测到喊'停'的声音"
                    DetectedSoundKind.AGGRESSIVE_SCREAM -> "注意，检测到攻击性尖叫声"
                    DetectedSoundKind.PHONE_RING -> "注意，电话正在响"
                    DetectedSoundKind.ALARM_CLOCK -> "注意，闹钟正在响"
                    DetectedSoundKind.MICROWAVE_TIMER -> "注意，微波炉定时器结束"
                    DetectedSoundKind.SCHOOL_BELL -> "注意，学校铃声响了"
                }
                "$name，$action。"
            }
            Language.GERMAN -> {
                val action = when (detection.kind) {
                    DetectedSoundKind.SIREN -> "bitte vorsichtig sein, Sirene erkannt"
                    DetectedSoundKind.VEHICLE_HORN -> "Achtung, Fahrzeughupe erkannt"
                    DetectedSoundKind.DOG_BARK -> "Vorsicht, Hundebellen erkannt"
                    DetectedSoundKind.CHILD_CRYING -> "Achtung, Kind weint erkannt"
                    DetectedSoundKind.EMERGENCY_ALARM -> "Achtung, Notfallalarm erkannt"
                    DetectedSoundKind.TRAIN_HORN -> "Achtung, Zugpfeife erkannt"
                    DetectedSoundKind.MOTORCYCLE_HORN -> "Achtung, Motorradhupe erkannt"
                    DetectedSoundKind.BICYCLE_BELL -> "Achtung, Fahrradklingel erkannt"
                    DetectedSoundKind.REVERSE_BEEP -> "Achtung, Rückfahrwarner erkannt"
                    DetectedSoundKind.TIRE_SCREECH -> "Achtung, Reifenquietschen erkannt"
                    DetectedSoundKind.CRASH -> "Achtung, Aufprallgeräusch erkannt"
                    DetectedSoundKind.FIRE_ALARM -> "Achtung, Feueralarm erkannt"
                    DetectedSoundKind.SMOKE_DETECTOR -> "Achtung, Rauchmelder erkannt"
                    DetectedSoundKind.GLASS_BREAKING -> "Achtung, Glasbruch erkannt"
                    DetectedSoundKind.DOORBELL -> "Achtung, Türklingel erkannt"
                    DetectedSoundKind.DOOR_KNOCK -> "Achtung, Klopfen an der Tür erkannt"
                    DetectedSoundKind.DOOR_BANGING -> "Achtung, Türknallen erkannt"
                    DetectedSoundKind.INTRUDER_ALARM -> "Achtung, Einbruchsalarm erkannt"
                    DetectedSoundKind.BABY_CRY -> "Achtung, Babyweinen erkannt"
                    DetectedSoundKind.HELP_SCREAM -> "Achtung, Hilferuf erkannt"
                    DetectedSoundKind.PANIC_SCREAM -> "Achtung, Panikschrei erkannt"
                    DetectedSoundKind.FIRE_SHOUT -> "Achtung, 'Feuer!'-Ruf erkannt"
                    DetectedSoundKind.STOP_SHOUT -> "Achtung, 'Stopp!'-Ruf erkannt"
                    DetectedSoundKind.AGGRESSIVE_SCREAM -> "Achtung, aggressiver Schrei erkannt"
                    DetectedSoundKind.PHONE_RING -> "Achtung, das Telefon klingelt"
                    DetectedSoundKind.ALARM_CLOCK -> "Achtung, der Wecker klingelt"
                    DetectedSoundKind.MICROWAVE_TIMER -> "Achtung, Mikrowellentimer abgelaufen"
                    DetectedSoundKind.SCHOOL_BELL -> "Achtung, Schulglocke erkannt"
                }
                "$name, $action."
            }
            Language.ENGLISH -> {
                val action = when (detection.kind) {
                    DetectedSoundKind.SIREN -> "be careful, siren detected"
                    DetectedSoundKind.VEHICLE_HORN -> "watch out, vehicle horn detected"
                    DetectedSoundKind.DOG_BARK -> "caution, dog bark detected"
                    DetectedSoundKind.CHILD_CRYING -> "attention, child crying detected"
                    DetectedSoundKind.EMERGENCY_ALARM -> "attention, emergency alarm detected"
                    DetectedSoundKind.TRAIN_HORN -> "attention, train horn detected"
                    DetectedSoundKind.MOTORCYCLE_HORN -> "attention, motorcycle horn detected"
                    DetectedSoundKind.BICYCLE_BELL -> "attention, bicycle bell detected"
                    DetectedSoundKind.REVERSE_BEEP -> "attention, reverse beep detected"
                    DetectedSoundKind.TIRE_SCREECH -> "attention, tire screech detected"
                    DetectedSoundKind.CRASH -> "attention, crash sound detected"
                    DetectedSoundKind.FIRE_ALARM -> "attention, fire alarm detected"
                    DetectedSoundKind.SMOKE_DETECTOR -> "attention, smoke detector detected"
                    DetectedSoundKind.GLASS_BREAKING -> "attention, glass breaking sound"
                    DetectedSoundKind.DOORBELL -> "attention, doorbell detected"
                    DetectedSoundKind.DOOR_KNOCK -> "attention, door knock detected"
                    DetectedSoundKind.DOOR_BANGING -> "attention, door banging detected"
                    DetectedSoundKind.INTRUDER_ALARM -> "attention, intruder alarm detected"
                    DetectedSoundKind.BABY_CRY -> "attention, baby cry detected"
                    DetectedSoundKind.HELP_SCREAM -> "attention, help scream detected"
                    DetectedSoundKind.PANIC_SCREAM -> "attention, panic scream detected"
                    DetectedSoundKind.FIRE_SHOUT -> "attention, 'Fire!' shout detected"
                    DetectedSoundKind.STOP_SHOUT -> "attention, 'Stop!' shout detected"
                    DetectedSoundKind.AGGRESSIVE_SCREAM -> "attention, aggressive scream detected"
                    DetectedSoundKind.PHONE_RING -> "attention, phone ring detected"
                    DetectedSoundKind.ALARM_CLOCK -> "attention, alarm clock detected"
                    DetectedSoundKind.MICROWAVE_TIMER -> "attention, microwave timer finished"
                    DetectedSoundKind.SCHOOL_BELL -> "attention, school bell detected"
                }
                "$name, $action."
            }
        }
    }

    fun transliteratedKazakhMessage(userName: String, detection: SoundDetection): String {
        val name = transliterate(userName.ifBlank { "User" })
        val action = when (detection.kind) {
            DetectedSoundKind.SIREN -> "abay bolynyz, sirena anyqtaldy"
            DetectedSoundKind.VEHICLE_HORN -> "saq bolynyz, kolik signaly anyqtaldy"
            DetectedSoundKind.DOG_BARK -> "abaylanyz, ittin urgeni anyqtaldy"
            DetectedSoundKind.CHILD_CRYING -> "nazar audarynyz, balanyn zhylagany anyqtaldy"
            DetectedSoundKind.EMERGENCY_ALARM -> "nazar audarynyz, apattyq dabyl anyqtaldy"
            DetectedSoundKind.TRAIN_HORN -> "nazar audarynyz, poiyz signaly anyqtaldy"
            DetectedSoundKind.MOTORCYCLE_HORN -> "nazar audarynyz, mototsikl signaly anyqtaldy"
            DetectedSoundKind.BICYCLE_BELL -> "nazar audarynyz, velosiped qonyrauy anyqtaldy"
            DetectedSoundKind.REVERSE_BEEP -> "nazar audarynyz, artqa zhuru signaly anyqtaldy"
            DetectedSoundKind.TIRE_SCREECH -> "nazar audarynyz, shinalardyn syqyry anyqtaldy"
            DetectedSoundKind.CRASH -> "nazar audarynyz, soqtyghysu dybysy anyqtaldy"
            DetectedSoundKind.FIRE_ALARM -> "nazar audarynyz, oert dabyl anyqtaldy"
            DetectedSoundKind.SMOKE_DETECTOR -> "nazar audarynyz, tutin detektory anyqtaldy"
            DetectedSoundKind.GLASS_BREAKING -> "nazar audarynyz, shyny synuy anyqtaldy"
            DetectedSoundKind.DOORBELL -> "nazar audarynyz, esik qonyrauy anyqtaldy"
            DetectedSoundKind.DOOR_KNOCK -> "nazar audarynyz, esik qaghu anyqtaldy"
            DetectedSoundKind.DOOR_BANGING -> "nazar audarynyz, esiktin tars etkent anyqtaldy"
            DetectedSoundKind.INTRUDER_ALARM -> "nazar audarynyz, kuzet dabyl anyqtaldy"
            DetectedSoundKind.BABY_CRY -> "nazar audarynyz, sabidyn zhylaghany anyqtaldy"
            DetectedSoundKind.HELP_SCREAM -> "nazar audarynyz, koemek suraghan aiqai anyqtaldy"
            DetectedSoundKind.PANIC_SCREAM -> "nazar audarynyz, ureili aiqai anyqtaldy"
            DetectedSoundKind.FIRE_SHOUT -> "nazar audarynyz, 'Oert!' degen aiqai anyqtaldy"
            DetectedSoundKind.STOP_SHOUT -> "nazar audarynyz, 'Toqta!' degen aiqai anyqtaldy"
            DetectedSoundKind.AGGRESSIVE_SCREAM -> "nazar audarynyz, agressivti aiqai anyqtaldy"
            DetectedSoundKind.PHONE_RING -> "nazar audarynyz, telefon qonyrauy anyqtaldy"
            DetectedSoundKind.ALARM_CLOCK -> "nazar audarynyz, oiatqysh anyqtaldy"
            DetectedSoundKind.MICROWAVE_TIMER -> "nazar audarynyz, mikrotolqyndy pesh taimeri anyqtaldy"
            DetectedSoundKind.SCHOOL_BELL -> "nazar audarynyz, mektep qonyrauy anyqtaldy"
        }
        return "$name, $action."
    }

    private fun transliterate(text: String): String {
        return text.map { char ->
            when (char.lowercaseChar()) {
                'ә' -> 'a'
                'ғ' -> 'g'
                'қ' -> 'k'
                'ң' -> 'n'
                'ө' -> 'o'
                'ұ', 'ү' -> 'u'
                'і' -> 'i'
                'һ' -> 'h'
                'ж' -> "zh" // Added for common Kazakh sounds
                else -> char
            }
        }.joinToString("")
    }

    private fun mapLabelToKind(label: String, selectedSounds: Set<String>): DetectedSoundKind? {
        val l = label.lowercase(Locale.ROOT)

        // HELP
        if (selectedSounds.contains("HELP_SCREAM")) {
            val helpKeywords = listOf(
                "scream", "shout", "yell", "help", "help me", "save me",
                "помогите", "помощь", "спасите",
                "көмектес", "көмек", "құтқарыңдар",
                "ayuda", "auxilio",
                "ساعدني", "النجدة",
                "救命", "帮我",
                "hilfe"
            )
            if (helpKeywords.any { l.contains(it) }) return DetectedSoundKind.HELP_SCREAM
        }

        // FIRE
        if (selectedSounds.contains("FIRE_SHOUT")) {
            val fireKeywords = listOf(
                "fire", "пожар", "өрт", "fuego", "حريق", "着火", "feuer"
            )
            if (fireKeywords.any { l.contains(it) }) return DetectedSoundKind.FIRE_SHOUT
        }

        // STOP
        if (selectedSounds.contains("STOP_SHOUT")) {
            val stopKeywords = listOf(
                "stop", "стой", "тоқта", "alto", "قف", "停下", "stopp"
            )
            if (stopKeywords.any { l.contains(it) }) return DetectedSoundKind.STOP_SHOUT
        }

        // GLASS BREAKING
        if (selectedSounds.contains("GLASS_BREAKING")) {
            if (l.contains("glass") && (l.contains("break") || l.contains("shatter") || l.contains("broken") || l.contains("window"))) {
                return DetectedSoundKind.GLASS_BREAKING
            }
        }

        // MOTORCYCLE
        if (selectedSounds.contains("MOTORCYCLE_HORN")) {
            if (l.contains("motorcycle") || l.contains("motorbike") || l.contains("bike exhaust")) {
                return DetectedSoundKind.MOTORCYCLE_HORN
            }
        }

        // CRASH
        if (selectedSounds.contains("CRASH")) {
            if (l.contains("crash") || l.contains("collision") || l.contains("impact") || l.contains("accident")) {
                return DetectedSoundKind.CRASH
            }
        }

        // PANIC SCREAM
        if (selectedSounds.contains("PANIC_SCREAM")) {
            if (l.contains("panic") && (l.contains("scream") || l.contains("shout"))) return DetectedSoundKind.PANIC_SCREAM
        }

        // AGGRESSIVE SCREAM
        if (selectedSounds.contains("AGGRESSIVE_SCREAM")) {
            if (l.contains("aggressive") || l.contains("angry") || l.contains("fighting")) return DetectedSoundKind.AGGRESSIVE_SCREAM
        }

        // Strict mappings for others
        if (selectedSounds.contains("SIREN") && (l.contains("siren") || l.contains("emergency vehicle"))) return DetectedSoundKind.SIREN
        if (selectedSounds.contains("VEHICLE_HORN") && (l.contains("vehicle horn") || l.contains("car horn") || l.contains("honk") || l.contains("klaxon"))) return DetectedSoundKind.VEHICLE_HORN
        if (selectedSounds.contains("DOG_BARK") && (l.contains("bark") || l.contains("bow-wow") || (l.contains("dog") && l.contains("howl")))) return DetectedSoundKind.DOG_BARK
        if (selectedSounds.contains("CHILD_CRYING") && l.contains("child") && (l.contains("cry") || l.contains("crying"))) return DetectedSoundKind.CHILD_CRYING
        if (selectedSounds.contains("EMERGENCY_ALARM") && (l.contains("emergency alarm") || l.contains("alert"))) return DetectedSoundKind.EMERGENCY_ALARM
        if (selectedSounds.contains("TRAIN_HORN") && (l.contains("train horn") || l.contains("locomotive"))) return DetectedSoundKind.TRAIN_HORN
        if (selectedSounds.contains("BICYCLE_BELL") && l.contains("bicycle bell")) return DetectedSoundKind.BICYCLE_BELL
        if (selectedSounds.contains("REVERSE_BEEP") && (l.contains("reverse beep") || l.contains("backing up"))) return DetectedSoundKind.REVERSE_BEEP
        if (selectedSounds.contains("TIRE_SCREECH") && (l.contains("tire screech") || l.contains("skidding"))) return DetectedSoundKind.TIRE_SCREECH
        if (selectedSounds.contains("FIRE_ALARM") && (l.contains("fire alarm") || l.contains("smoke alarm"))) return DetectedSoundKind.FIRE_ALARM
        if (selectedSounds.contains("SMOKE_DETECTOR") && (l.contains("smoke detector") || l.contains("smoke alarm"))) return DetectedSoundKind.SMOKE_DETECTOR
        if (selectedSounds.contains("DOORBELL") && (l.contains("doorbell") || l.contains("chime"))) return DetectedSoundKind.DOORBELL
        if (selectedSounds.contains("DOOR_KNOCK") && l.contains("knock")) return DetectedSoundKind.DOOR_KNOCK
        if (selectedSounds.contains("DOOR_BANGING") && (l.contains("door bang") || l.contains("slam"))) return DetectedSoundKind.DOOR_BANGING
        if (selectedSounds.contains("INTRUDER_ALARM") && (l.contains("intruder") || l.contains("burglar"))) return DetectedSoundKind.INTRUDER_ALARM
        if (selectedSounds.contains("BABY_CRY") && (l.contains("baby") && (l.contains("cry") || l.contains("crying")) || l.contains("infant cry"))) return DetectedSoundKind.BABY_CRY
        if (selectedSounds.contains("PHONE_RING") && (l.contains("telephone") || l.contains("ringtone"))) return DetectedSoundKind.PHONE_RING
        if (selectedSounds.contains("ALARM_CLOCK") && l.contains("alarm clock")) return DetectedSoundKind.ALARM_CLOCK
        if (selectedSounds.contains("MICROWAVE_TIMER") && (l.contains("microwave") || l.contains("timer"))) return DetectedSoundKind.MICROWAVE_TIMER
        if (selectedSounds.contains("SCHOOL_BELL") && l.contains("school bell")) return DetectedSoundKind.SCHOOL_BELL
        
        return null
    }

    private fun isLikelySpeechOrMusic(l: String): Boolean {
        val s = l.lowercase(Locale.ROOT)
        return when {
            s.contains("speech") -> true
            s.contains("conversation") -> true
            s.contains("talk") -> true
            s.contains("chatter") -> true
            s.contains("music") -> true
            s.contains("singing") || s.contains("song") -> true
            s.contains("radio") || s.contains("television") -> true
            else -> false
        }
    }
}
