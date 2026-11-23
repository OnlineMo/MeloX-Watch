package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontAsset
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSelection
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayAlignment
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayPreferences
import io.github.onlinemo.meloxwatch.data.themes.AppLanguage
import io.github.onlinemo.meloxwatch.data.themes.AppearanceOptions
import io.github.onlinemo.meloxwatch.data.themes.BackgroundCrop
import io.github.onlinemo.meloxwatch.data.themes.BackgroundImageRef
import io.github.onlinemo.meloxwatch.data.themes.BuiltinTheme
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundOptions
import io.github.onlinemo.meloxwatch.data.themes.ThemeBackgroundMode
import io.github.onlinemo.meloxwatch.data.themes.ThemeChoice
import io.github.onlinemo.meloxwatch.data.themes.ThemeRef
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.model.LibraryLayout
import io.github.onlinemo.meloxwatch.model.LibraryListContext
import io.github.onlinemo.meloxwatch.model.LibrarySort
import io.github.onlinemo.meloxwatch.model.LibrarySortField
import io.github.onlinemo.meloxwatch.model.PlaybackBackendKind
import io.github.onlinemo.meloxwatch.model.PlaybackControlsPreferences
import io.github.onlinemo.meloxwatch.model.ProgressGestureMode
import io.github.onlinemo.meloxwatch.model.RotaryAction
import io.github.onlinemo.meloxwatch.model.RotaryOrientation
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.SongTapPolicy
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.HelpPreferences
import io.github.onlinemo.meloxwatch.model.PrivacyNoticeChoice
import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.playback.dsp.DspLimits
import io.github.onlinemo.meloxwatch.playback.dsp.DspPreset
import io.github.onlinemo.meloxwatch.playback.dsp.DspSettings
import io.github.onlinemo.meloxwatch.playback.dsp.DspStored
import io.github.onlinemo.meloxwatch.playback.dsp.ReplayGainMode

/** Only portable local choices have an envelope. External-output activation and grants have none. */
internal object BackupPreferencesCodec {
    val KEYS = setOf("player", "appearance", "lyrics-display", "lyrics-fonts", "background", "theme", "dsp")
    private val playerFields = setOf("restorePosition", "screenShape", "libraryLayout", "artworkReadPath", "songTapPolicy",
        "mediaButtonsEnabled", "audioFocusManagement", "playbackBackend", "playbackSpeedTenths", "gaplessPlayback",
        "rotaryEnabled", "rotaryAction", "rotaryOrientation", "rotaryHaptics", "showMainProgress", "progressGesture", "sleepTimerSummary",
        "onboardingCompletedVersion", "onboardingDismissedVersion", "onboardingStep", "showTips", "privacyNoticeVersion", "privacyNoticeChoice") +
        LibraryListContext.entries.flatMap { listOf("sort" + it.name + "Field", "sort" + it.name + "Descending") }
    private val appearanceFields = setOf("scalePercent", "horizontalMarginDp", "verticalMarginDp", "compactPlaybackTitle", "lyricsKeepBright", "language")
    private val displayFields = setOf("textSizeSp", "blockSpacingDp", "alignment", "anchorFraction", "horizontalMarginDp",
        "showTranslation", "emphasizeWords", "legacyKeepScreenOn")
    private val fontFields = listOf("original", "translation").flatMap { prefix ->
        listOf("AssetId", "Sha256", "Bytes", "DisplayName", "RequestedWeight").map { prefix + it }
    }.toSet()
    private val backgroundFields = setOf("mode", "blurLevel", "darkMaskPercent", "zoom", "horizontal", "vertical",
        "imageId", "imageSha256", "imageBytes", "imageMediaType", "imageWidth", "imageHeight", "imageOrientation")
    private val themeFields = listOf("selected", "previous").flatMap { prefix ->
        listOf("Type", "Builtin", "InstallationId", "ThemeId", "ManifestSha256", "PackageResourceId").map { prefix + it }
    }.toSet()
    private val dspFields = setOf("equalizerEnabled", "selectedPresetId", "replayGainMode", "preampTenthsDb", "preventClipping",
        "leftPercent", "rightPercent", "limiterEnabled", "limiterThresholdTenthsDb", "limiterAttackMicros", "limiterReleaseMillis",
        "limiterLookaheadMillis", "presets") + (0 until 10).map { "band$it" }

    fun validate(record: BackupRecord) {
        if (record.kind != BackupRecordKind.PREFERENCE || record.key !in KEYS) reject()
        val expected = when (record.key) {
            "player" -> playerFields; "appearance" -> appearanceFields; "lyrics-display" -> displayFields
            "lyrics-fonts" -> fontFields; "background" -> backgroundFields; "theme" -> themeFields; "dsp" -> dspFields
            else -> reject()
        }
        if (record.values.keys != expected || record.values.any { (key, value) -> value is BackupCell.Text &&
                value.value.length > if (record.key == "dsp" && key == "presets") 24_576 else 4096 }) reject()
        try {
            when (record.key) {
                "player" -> player(record); "appearance" -> appearance(record); "lyrics-display" -> display(record)
                "lyrics-fonts" -> fonts(record); "background" -> background(record)
                "theme" -> {
                    for (prefix in listOf("selected", "previous")) {
                        val chosen = choice(record, prefix)
                        val resource = record.optionalText(prefix + "PackageResourceId")
                        if (resource != null && (!resource.matches(Regex("[0-9a-f]{64}")) || chosen !is ThemeChoice.Installed)) reject()
                    }
                }
                "dsp" -> dsp(record)
            }
        } catch (error: IllegalArgumentException) { throw BackupException(BackupIssue.INVALID_INPUT, error) }
    }

    fun player(value: UserPreferences): BackupRecord = preference("player", mutableMapOf(
        "restorePosition" to BackupCell.Flag(value.restorePosition), "screenShape" to BackupCell.Text(value.screenShape.name),
        "libraryLayout" to BackupCell.Text(value.libraryLayout.name), "artworkReadPath" to BackupCell.Text(value.artworkReadPath.name),
        "songTapPolicy" to BackupCell.Text(value.songTapPolicy.name), "mediaButtonsEnabled" to BackupCell.Flag(value.mediaButtonsEnabled),
        "audioFocusManagement" to BackupCell.Flag(value.audioFocusManagement), "playbackBackend" to BackupCell.Text(value.playbackBackend.name),
        "playbackSpeedTenths" to BackupCell.Integer(value.playbackSpeedTenths.toLong()), "gaplessPlayback" to BackupCell.Flag(value.gaplessPlayback),
        "rotaryEnabled" to BackupCell.Flag(value.controls.rotaryEnabled), "rotaryAction" to BackupCell.Text(value.controls.rotaryAction.name),
        "rotaryOrientation" to BackupCell.Text(value.controls.rotaryOrientation.name), "rotaryHaptics" to BackupCell.Flag(value.controls.rotaryHaptics),
        "showMainProgress" to BackupCell.Flag(value.controls.showMainProgress), "progressGesture" to BackupCell.Text(value.controls.progressGesture.name),
        "sleepTimerSummary" to BackupCell.Flag(value.controls.sleepTimerSummary),
        "onboardingCompletedVersion" to BackupCell.Integer(value.help.onboardingCompletedVersion.toLong()),
        "onboardingDismissedVersion" to BackupCell.Integer(value.help.onboardingDismissedVersion.toLong()),
        "onboardingStep" to BackupCell.Integer(value.help.onboardingStep.toLong()),
        "showTips" to BackupCell.Flag(value.help.showTips), "privacyNoticeVersion" to BackupCell.Integer(value.help.privacyNoticeVersion.toLong()),
        "privacyNoticeChoice" to BackupCell.Text(value.help.privacyNoticeChoice.name),
    ).apply { for (context in LibraryListContext.entries) {
        val sort = checkNotNull(value.librarySorts[context])
        put("sort" + context.name + "Field", BackupCell.Text(sort.field.name))
        put("sort" + context.name + "Descending", BackupCell.Flag(sort.descending))
    } })

    fun player(record: BackupRecord): UserPreferences = UserPreferences(
        // A portable restore cannot re-enable disclosure to an old or different device.
        bluetoothLyricsEnabled = false,
        restorePosition = record.flag("restorePosition"), screenShape = enum(record.text("screenShape")),
        libraryLayout = enum<LibraryLayout>(record.text("libraryLayout")), artworkReadPath = enum<ArtworkReadPath>(record.text("artworkReadPath")),
        librarySorts = LibraryListContext.entries.associateWith { LibrarySort(enum<LibrarySortField>(record.text("sort" + it.name + "Field")), record.flag("sort" + it.name + "Descending")) },
        songTapPolicy = enum<SongTapPolicy>(record.text("songTapPolicy")), mediaButtonsEnabled = record.flag("mediaButtonsEnabled"),
        audioFocusManagement = record.flag("audioFocusManagement"), playbackBackend = enum<PlaybackBackendKind>(record.text("playbackBackend")),
        playbackSpeedTenths = record.int("playbackSpeedTenths"), gaplessPlayback = record.flag("gaplessPlayback"),
        controls = PlaybackControlsPreferences(record.flag("rotaryEnabled"), enum<RotaryAction>(record.text("rotaryAction")),
            enum<RotaryOrientation>(record.text("rotaryOrientation")), record.flag("rotaryHaptics"), record.flag("showMainProgress"),
            enum<ProgressGestureMode>(record.text("progressGesture")), record.flag("sleepTimerSummary")),
        help = HelpPreferences(onboardingCompletedVersion = record.int("onboardingCompletedVersion"),
            onboardingDismissedVersion = record.int("onboardingDismissedVersion"), onboardingStep = record.int("onboardingStep"),
            showTips = record.flag("showTips"), privacyNoticeVersion = record.int("privacyNoticeVersion"),
            privacyNoticeChoice = enum<PrivacyNoticeChoice>(record.text("privacyNoticeChoice"))),
    )

    fun appearance(value: AppearanceOptions): BackupRecord = preference("appearance", mapOf(
        "scalePercent" to BackupCell.Integer(value.scalePercent.toLong()), "horizontalMarginDp" to BackupCell.Integer(value.horizontalMarginDp.toLong()),
        "verticalMarginDp" to BackupCell.Integer(value.verticalMarginDp.toLong()), "compactPlaybackTitle" to BackupCell.Flag(value.compactPlaybackTitle),
        "lyricsKeepBright" to BackupCell.Flag(value.lyricsKeepBright), "language" to BackupCell.Text(value.language.name),
    ))
    fun appearance(record: BackupRecord): AppearanceOptions = AppearanceOptions(record.int("scalePercent"), record.int("horizontalMarginDp"),
        record.int("verticalMarginDp"), record.flag("compactPlaybackTitle"), record.flag("lyricsKeepBright"), enum<AppLanguage>(record.text("language")))

    fun display(value: LyricsDisplayPreferences): BackupRecord = preference("lyrics-display", mapOf(
        "textSizeSp" to BackupCell.Decimal(value.textSizeSp.toDouble()), "blockSpacingDp" to BackupCell.Decimal(value.blockSpacingDp.toDouble()),
        "alignment" to BackupCell.Text(value.alignment.name), "anchorFraction" to BackupCell.Decimal(value.anchorFraction.toDouble()),
        "horizontalMarginDp" to BackupCell.Decimal(value.horizontalMarginDp.toDouble()), "showTranslation" to BackupCell.Flag(value.showTranslation),
        "emphasizeWords" to BackupCell.Flag(value.emphasizeWords), "legacyKeepScreenOn" to BackupCell.Flag(value.legacyKeepScreenOn),
    ))
    fun display(record: BackupRecord): LyricsDisplayPreferences = LyricsDisplayPreferences(
        textSizeSp = record.finiteFloat("textSizeSp"), blockSpacingDp = record.finiteFloat("blockSpacingDp"),
        alignment = enum<LyricsDisplayAlignment>(record.text("alignment")), anchorFraction = record.finiteFloat("anchorFraction"),
        horizontalMarginDp = record.finiteFloat("horizontalMarginDp"), showTranslation = record.flag("showTranslation"),
        emphasizeWords = record.flag("emphasizeWords"), legacyKeepScreenOn = record.flag("legacyKeepScreenOn"),
    )

    fun fonts(value: LyricsFontSettings): BackupRecord = preference("lyrics-fonts", mutableMapOf<String, BackupCell>().apply {
        for ((prefix, selected) in listOf("original" to value.original, "translation" to value.translation)) {
            val asset = selected.asset
            put(prefix + "AssetId", backupText(asset?.id)); put(prefix + "Sha256", backupText(asset?.sha256))
            put(prefix + "Bytes", backupNumber(asset?.byteCount)); put(prefix + "DisplayName", backupText(asset?.displayName))
            put(prefix + "RequestedWeight", backupDecimal(selected.requestedWeight?.toDouble()))
        }
    })

    /** Shapes are validated here; the coordinator independently checks bytes and new license consent. */
    fun fonts(record: BackupRecord): LyricsFontSettings {
        fun selection(prefix: String): LyricsFontSelection {
            val id = record.optionalText(prefix + "AssetId")
            val hash = record.optionalText(prefix + "Sha256")
            val bytes = record.optionalLong(prefix + "Bytes")
            val name = record.optionalText(prefix + "DisplayName")
            if (listOf(id, hash, bytes, name).count { it != null } !in setOf(0, 4)) reject()
            val asset = if (id == null) null else LyricsFontAsset(id, checkNotNull(hash), checkNotNull(bytes), checkNotNull(name),
                LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION)
            val weight = record.optionalDecimal(prefix + "RequestedWeight")?.toFloat()?.takeIf(Float::isFinite)
                ?: if (record.values[prefix + "RequestedWeight"] == BackupCell.Null) null else reject()
            return LyricsFontSelection(asset, weight)
        }
        return LyricsFontSettings(original = selection("original"), translation = selection("translation"))
    }

    fun background(value: PlaybackBackgroundOptions): BackupRecord = preference("background", mapOf(
        "mode" to BackupCell.Text(value.mode.name), "blurLevel" to BackupCell.Integer(value.blurLevel.toLong()),
        "darkMaskPercent" to BackupCell.Integer(value.darkMaskPercent.toLong()), "zoom" to BackupCell.Decimal(value.crop.zoom.toDouble()),
        "horizontal" to BackupCell.Decimal(value.crop.horizontal.toDouble()), "vertical" to BackupCell.Decimal(value.crop.vertical.toDouble()),
        "imageId" to backupText(value.customImage?.id), "imageSha256" to backupText(value.customImage?.sha256),
        "imageBytes" to backupNumber(value.customImage?.byteCount), "imageMediaType" to backupText(value.customImage?.mediaType),
        "imageWidth" to backupNumber(value.customImage?.width?.toLong()), "imageHeight" to backupNumber(value.customImage?.height?.toLong()),
        "imageOrientation" to backupNumber(value.customImage?.orientation?.toLong()),
    ))
    fun background(record: BackupRecord): PlaybackBackgroundOptions {
        val fields = listOf("imageId", "imageSha256", "imageBytes", "imageMediaType", "imageWidth", "imageHeight", "imageOrientation")
        val count = fields.count { record.values[it] != BackupCell.Null }
        if (count != 0 && count != fields.size) reject()
        val image = if (count == 0) null else BackgroundImageRef(record.text("imageId"), record.text("imageSha256"), record.long("imageBytes"),
            record.text("imageMediaType"), record.int("imageWidth"), record.int("imageHeight"), record.int("imageOrientation"))
        return PlaybackBackgroundOptions(enum<ThemeBackgroundMode>(record.text("mode")), record.int("blurLevel"), record.int("darkMaskPercent"),
            BackgroundCrop(record.finiteFloat("zoom"), record.finiteFloat("horizontal"), record.finiteFloat("vertical")), image)
    }

    fun theme(selected: ThemeChoice, previous: ThemeChoice, packages: Map<String, String> = emptyMap()): BackupRecord = preference("theme", mutableMapOf<String, BackupCell>().apply {
        putChoice("selected", selected); putChoice("previous", previous)
        for ((prefix, choice) in listOf("selected" to selected, "previous" to previous)) {
            put(prefix + "PackageResourceId", backupText((choice as? ThemeChoice.Installed)?.ref?.installationId?.let(packages::get)))
        }
    })
    fun choice(record: BackupRecord, prefix: String): ThemeChoice = when (record.text(prefix + "Type")) {
        "BUILTIN" -> {
            if (listOf("InstallationId", "ThemeId", "ManifestSha256").any { record.values[prefix + it] != BackupCell.Null }) reject()
            ThemeChoice.Builtin(enum<BuiltinTheme>(record.text(prefix + "Builtin")))
        }
        "INSTALLED" -> {
            if (record.values[prefix + "Builtin"] != BackupCell.Null) reject()
            ThemeChoice.Installed(ThemeRef(record.text(prefix + "InstallationId"), record.text(prefix + "ThemeId"), record.text(prefix + "ManifestSha256")))
        }
        else -> reject()
    }

    fun resourceReferences(record: BackupRecord): Map<String, String> = when (record.key) {
        "lyrics-fonts" -> buildMap { for (prefix in listOf("original", "translation")) {
            record.optionalText(prefix + "Sha256")?.let { put(prefix, BackupLogicalSchema.resourceId(BackupResourceKind.FONT, it)) }
        } }
        "background" -> record.optionalText("imageSha256")?.let { mapOf("image" to BackupLogicalSchema.resourceId(BackupResourceKind.IMAGE, it)) } ?: emptyMap()
        "theme" -> buildMap { for (prefix in listOf("selected", "previous")) {
            record.optionalText(prefix + "PackageResourceId")?.let { put(prefix, it) }
        } }
        else -> emptyMap()
    }

    fun dsp(value: DspStored): BackupRecord {
        val actual = value.immutableCopy()
        val settings = actual.settings
        val presets = StrictJson.encode(JsonArray(actual.presets.map { preset ->
            JsonObject(mapOf("id" to JsonText(preset.id), "name" to JsonText(preset.name)) +
                preset.bandGainTenthsDb.mapIndexed { index, gain -> "band$index" to JsonNumber(gain.toString()) })
        }), 24_576).toString(Charsets.UTF_8)
        return preference("dsp", mapOf(
            "equalizerEnabled" to BackupCell.Flag(settings.equalizerEnabled), "selectedPresetId" to backupText(settings.selectedPresetId),
            "replayGainMode" to BackupCell.Text(settings.replayGainMode.name), "preampTenthsDb" to BackupCell.Integer(settings.preampTenthsDb.toLong()),
            "preventClipping" to BackupCell.Flag(settings.preventClipping), "leftPercent" to BackupCell.Integer(settings.leftPercent.toLong()),
            "rightPercent" to BackupCell.Integer(settings.rightPercent.toLong()), "limiterEnabled" to BackupCell.Flag(settings.limiterEnabled),
            "limiterThresholdTenthsDb" to BackupCell.Integer(settings.limiterThresholdTenthsDb.toLong()),
            "limiterAttackMicros" to BackupCell.Integer(settings.limiterAttackMicros.toLong()), "limiterReleaseMillis" to BackupCell.Integer(settings.limiterReleaseMillis.toLong()),
            "limiterLookaheadMillis" to BackupCell.Integer(settings.limiterLookaheadMillis.toLong()), "presets" to BackupCell.Text(presets),
        ) + settings.bandGainTenthsDb.mapIndexed { index, gain -> "band$index" to BackupCell.Integer(gain.toLong()) })
    }

    fun dsp(record: BackupRecord): DspStored {
        val parsed = StrictJson.decode(record.text("presets").toByteArray(Charsets.UTF_8), JsonBounds(bytes = 24_576, depth = 4,
            nodes = 1024, stringChars = DspLimits.MAX_NAME_CHARACTERS, members = DspLimits.MAX_PRESETS)) as? JsonArray ?: reject()
        if (parsed.values.size > DspLimits.MAX_PRESETS) reject()
        val presetFields = setOf("id", "name") + (0 until 10).map { "band$it" }
        val presets = parsed.values.map { value ->
            val node = value as? JsonObject ?: reject()
            if (node.fields.keys != presetFields) reject()
            val id = (node["id"] as? JsonText)?.value ?: reject()
            val name = (node["name"] as? JsonText)?.value ?: reject()
            DspPreset(id, name, List(10) { index -> (node["band$index"] as? JsonNumber)?.value?.toIntOrNull() ?: reject() })
        }
        val settings = DspSettings(record.flag("equalizerEnabled"), List(10) { record.int("band$it") }, record.optionalText("selectedPresetId"),
            enum<ReplayGainMode>(record.text("replayGainMode")), record.int("preampTenthsDb"), record.flag("preventClipping"),
            record.int("leftPercent"), record.int("rightPercent"), record.flag("limiterEnabled"), record.int("limiterThresholdTenthsDb"),
            record.int("limiterAttackMicros"), record.int("limiterReleaseMillis"), record.int("limiterLookaheadMillis"))
        // The real store model checks normalized duplicate names and the selected preset's exact curve.
        return DspStored(settings = settings, presets = presets).immutableCopy()
    }

    private fun MutableMap<String, BackupCell>.putChoice(prefix: String, choice: ThemeChoice) {
        put(prefix + "Type", BackupCell.Text(if (choice is ThemeChoice.Builtin) "BUILTIN" else "INSTALLED"))
        put(prefix + "Builtin", backupText((choice as? ThemeChoice.Builtin)?.theme?.name))
        val ref = (choice as? ThemeChoice.Installed)?.ref
        put(prefix + "InstallationId", backupText(ref?.installationId)); put(prefix + "ThemeId", backupText(ref?.themeId))
        put(prefix + "ManifestSha256", backupText(ref?.manifestSha256))
    }
    private fun preference(key: String, values: Map<String, BackupCell>): BackupRecord = BackupRecord(BackupRecordKind.PREFERENCE, key, values)
    private inline fun <reified T : Enum<T>> enum(value: String): T = enumValues<T>().firstOrNull { it.name == value } ?: reject()
    private fun BackupRecord.int(name: String): Int = long(name).takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt() ?: reject()
    private fun BackupRecord.finiteFloat(name: String): Float = decimal(name).toFloat().takeIf(Float::isFinite) ?: reject()
    private fun reject(): Nothing = throw BackupException(BackupIssue.INVALID_INPUT)
}
