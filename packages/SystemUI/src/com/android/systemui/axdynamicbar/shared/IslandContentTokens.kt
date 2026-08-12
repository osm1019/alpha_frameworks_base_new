package com.android.systemui.axdynamicbar.shared

import android.app.ActivityOptions
import com.android.systemui.res.R
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.android.systemui.axdynamicbar.model.IslandEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.systemui.media.ax.ui.compose.MediaChrome

internal val SpaceXxs = 2.dp
internal val SpaceXs = 4.dp
internal val SpaceSm = 6.dp
internal val SpaceMd = 8.dp
internal val SpaceLg = 12.dp
internal val SpaceXl = 14.dp
internal val SpaceXxl = 16.dp
internal val SpaceSection = 20.dp
internal val SpacePanel = 24.dp
internal val SpacePanelLarge = 28.dp

internal val ShapeXs = RoundedCornerShape(8.dp)
internal val ShapeSm = RoundedCornerShape(12.dp)
internal val ShapeLg = RoundedCornerShape(24.dp)
internal val ShapeXl = RoundedCornerShape(32.dp)
internal val ShapeCard = RoundedCornerShape(28.dp)

internal val SizeBadge = 14.dp
internal val SizeIconSm = 20.dp
internal val SizeIconMd = 28.dp
internal val SizeButton = 48.dp
internal val SizeButtonLg = 48.dp
internal val SizeButtonXl = 64.dp
internal val SizeAlbumSm = 52.dp
internal val SizeAlbumLg = 200.dp
internal val SizeSeekHeight = 16.dp
internal val SizeProgressHeight = 8.dp
internal val SizeStrokeWidth = 2.dp
internal val SizeStrokeThin = 1.5f
internal val SizeCompactIcon = 40.dp
internal val SizeActionHeight = 48.dp

// ── Pill width tokens ──
internal val StatusBarIconWidth = 14.dp
internal val StatusBarBadgeWidth = StatusBarIconWidth
internal val StatusBarContentWidth = 74.dp
internal val StatusBarPillWidth = 88.dp
// Reserved icon lane for centered cutout pills.
// 18dp (largest chip icon, e.g. media art) + 4dp + 4dp lane paddings + 2dp breathing room.
internal val CutoutCenterIconAreaWidth = 28.dp
// Right text lane for centered cutout pills.
internal val CutoutCenterContentAreaWidth = StatusBarContentWidth
// Explicit status widths by state.
internal val StatusBarPillWidthWithBadge = StatusBarPillWidth + StatusBarBadgeWidth
// Cutout geometry paddings around the physical cutout bounds.
internal val CutoutPadTop = 4.dp
internal val CutoutPadBottom = 3.dp
internal val CutoutPadSide = 3.dp
internal val CutoutPillContentMin = 80.dp
internal val CutoutPillContentMax = 140.dp
internal val KeyguardPillMinWidth = 88.dp
internal val KeyguardPillMaxWidth = 136.dp
internal val KeyguardEventPillMaxWidth = 220.dp
internal val CenterSideMaxWidth = 120.dp
internal val PillTextMaxWidth = 80.dp
// Minimum width for ticking-text containers (timer, stopwatch, recording).
// Covers the widest standard format ("00:00.0" = 7 mono chars) so the
// text container never shrinks per-tick, eliminating centering jitter.
internal val TickingTextMinWidth = 48.dp

internal const val AlphaSecondary = 0.7f
internal const val AlphaTertiary = 0.5f
internal const val AlphaHint = 0.4f
internal const val AlphaDisabled = 0.3f
internal const val AlphaSubtle = 0.15f
internal const val AlphaFaint = 0.1f
internal const val AlphaBorder = 0.08f
internal const val AlphaStatusChip = 0.14f
internal const val AlphaIconBg = 0.16f
internal const val AlphaTrack = 0.25f

internal val TsBadge: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(
        fontSize = 8.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )


internal val BatteryChargingColor = Color(0xFF66BB6A)
internal val BatteryPowerSaveColor = Color(0xFFFFA726)
internal val BatteryNeutralColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant
internal val ChipContentDark = Color(0xFF1B1B1B)

internal val RedAccent = Color(0xFFEF5350)
internal val PinkAccent = Color(0xFFEC407A)
internal val OrangeAccent = Color(0xFFFFA726)
internal val YellowAccent = Color(0xFFFFCA28)
internal val GreenAccent = Color(0xFF66BB6A)
internal val MintAccent = Color(0xFF26A69A)
internal val TealAccent = Color(0xFF29B6F6)
internal val BlueAccent = Color(0xFF42A5F5)
internal val IndigoAccent = Color(0xFF7E57C2)
internal val PurpleAccent = Color(0xFFAB47BC)
internal val PausedGray = Color(0xFF8E8E93)

internal val ExpandedMaxWidth = 420.dp

/**
 * Dynamic Bar expand cards sit on dense glass (same material as the media sheet).
 * Content tokens track [MediaChrome] so day mode is dark-on-light-gray and night is light-on-dark.
 */
internal val SubtleGray: Color
    @Composable get() = MediaChrome.OnGlassSecondary
internal val CardBg: Color
    @Composable get() = MediaChrome.GlassBody
/** Nested box inside an expand card — steps up a surface role rather than going translucent. */
internal val DarkCard: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest
internal val OnCardText: Color
    @Composable get() = MediaChrome.OnGlass
internal val OnCardSecondary: Color
    @Composable get() = MediaChrome.OnGlassSecondary
internal val ActionBg: Color
    @Composable get() = MaterialTheme.colorScheme.primary
internal val OnActionText: Color
    @Composable get() = MaterialTheme.colorScheme.onPrimary
internal val DestructiveBg: Color
    @Composable get() = MaterialTheme.colorScheme.errorContainer
internal val OnDestructiveText: Color
    @Composable get() = MaterialTheme.colorScheme.onErrorContainer

/** Hairline on glass expand shells — flat, matches [MediaChrome.GlassBorder] weight. */
internal val CardBorderBrush: Brush
    @Composable
    get() {
        val border = MediaChrome.GlassBorder
        return Brush.verticalGradient(colors = listOf(border, border))
    }

/**
 * Shared glass shell for Dynamic Bar chips and cards.
 *
 * Event colour codes stay: non-media chips tint the glass with the event accent (charging
 * green, timer orange, …). Media uses neutral glass and keeps accent on play / progress.
 * Bodies are never solid full-fill accent. Body seed follows night/day via [MediaChrome].
 */
internal data class IslandGlassChrome(
    val body: Color,
    val border: Color,
    val content: Color,
)

/**
 * How hard non-media event accent tints the glass.
 * The day surface role is a light, low-chroma grey, so the same mix reads washed-out against it —
 * push harder in day mode to keep charging / timer / call distinguishable at arm's length.
 */
private const val IslandGlassEventTintDark = 0.45f
private const val IslandGlassEventTintLight = 0.62f

/** WCAG AA for icons and short labels at the sizes these chips use. */
private const val MinContentContrast = 4.5

/**
 * Content that stays legible on [body].
 *
 * The event palette is fixed hex (see [RedAccent] and friends) and does not follow the theme, so a
 * tinted body can land anywhere on the luminance range regardless of mode.
 *
 * Measure contrast rather than testing which side of mid-grey the body falls on: a body at ~0.45
 * luminance is "not light" by that test and gets a light glyph, which is only about 2:1. Prefer the
 * palette's own on-surface roles, and only fall back to plain black/white when neither clears AA.
 */
@Composable
private fun contentColorOn(body: Color): Color {
    // calculateContrast demands an opaque background; tinted bodies may carry alpha.
    val bg = body.copy(alpha = 1f).toArgb()
    val best =
        listOf(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.inverseOnSurface)
            .maxByOrNull { ColorUtils.calculateContrast(it.toArgb(), bg) }!!
    if (ColorUtils.calculateContrast(best.toArgb(), bg) >= MinContentContrast) return best
    return if (
        ColorUtils.calculateContrast(Color.White.toArgb(), bg) >=
            ColorUtils.calculateContrast(Color.Black.toArgb(), bg)
    ) Color.White
    else Color.Black
}

/**
 * Chip / pill glass. [accent] is from [chipAccentColorFor].
 * @param isMedia media keeps neutral glass; accent stays on controls
 */
@Composable
internal fun islandGlassChrome(
    accent: Color,
    isMedia: Boolean,
): IslandGlassChrome {
    val body = MediaChrome.GlassBody
    if (isMedia) {
        return IslandGlassChrome(
            body = body,
            border = MediaChrome.GlassBorder,
            content = MediaChrome.OnGlass,
        )
    }
    val tintAmount =
        if (isSystemInDarkTheme()) IslandGlassEventTintDark else IslandGlassEventTintLight
    val mixed = lerp(body, accent.copy(alpha = 1f), tintAmount)
    return IslandGlassChrome(
        body = mixed,
        border = MediaChrome.EventTintBorder,
        content = contentColorOn(mixed),
    )
}

/** Expand-card shell — neutral dense glass; accent lives in content chrome. */
@Composable
internal fun islandCardChrome(): IslandGlassChrome =
    IslandGlassChrome(
        body = MediaChrome.GlassBody,
        border = MediaChrome.GlassBorder,
        content = MediaChrome.OnGlass,
    )

internal val PillPrimary: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.SemiBold,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

internal val PillAccent: TextStyle
    @Composable get() = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
internal val PillMono: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.Monospace,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
internal val TsMono: TextStyle
    @Composable get() = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)

internal val ShapeIconLarge = RoundedCornerShape(20.dp)
internal val ShapeIconMedium = RoundedCornerShape(16.dp)
internal val ShapeAlbum = RoundedCornerShape(16.dp)
internal val ShapeChip = RoundedCornerShape(percent = 50)
internal val ShapeCompact = RoundedCornerShape(12.dp)

internal fun accentColorFor(event: IslandEvent): Color = eventStyleFor(event).accent

/**
 * Glyph / label colour on a solid accent fill (play button, call actions, action circles).
 * Was unconditionally white, which fails on the light end of the palette — [YellowAccent] and a
 * light Monet media colour both take a white glyph and lose it.
 */
@Composable
internal fun chipContentColorOn(background: Color): Color = contentColorOn(background)

internal fun darkenColor(color: Color, keep: Float = 0.35f): Color =
    Color(
        red = color.red * keep,
        green = color.green * keep,
        blue = color.blue * keep,
        alpha = 1f,
    )

private const val DARK_THEME_MIN_L = 0.15
private const val LIGHT_THEME_MAX_L = 0.4

private fun ensureContrast(color: Color, isDark: Boolean): Color {
    val lum = ColorUtils.calculateLuminance(color.toArgb())
    if (isDark && lum >= DARK_THEME_MIN_L) return color
    if (!isDark && lum <= LIGHT_THEME_MAX_L) return color
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    if (isDark) {
        hsl[2] = (hsl[2] + 0.15f).coerceAtMost(0.65f)
    } else {
        hsl[2] = (hsl[2] - 0.15f).coerceAtLeast(0.25f)
    }
    return Color(ColorUtils.HSLToColor(hsl))
}

internal data class IslandColorScheme(
    val accent: Color,
    val onAccent: Color,
    val tonal: Color,
    val surfaceTint: Color,
)

@Composable
internal fun rememberIslandColors(event: IslandEvent): IslandColorScheme =
    buildColorScheme(accentColorFor(event))

@Composable
internal fun rememberMediaColors(event: IslandEvent.Media): IslandColorScheme {
    val raw = if (event.mediaColor != 0) Color(event.mediaColor) else PurpleAccent
    return buildColorScheme(raw)
}

@Composable
private fun buildColorScheme(raw: Color): IslandColorScheme {
    val isDark = ColorUtils.calculateLuminance(
        MaterialTheme.colorScheme.surface.toArgb()
    ) < 0.5
    val accent = if (!isDark) ensureContrast(raw) else raw
    return IslandColorScheme(
        accent = accent,
        onAccent = chipContentColorOn(accent),
        tonal = accent.copy(alpha = AlphaFaint),
        surfaceTint = if (isDark) darkenColor(raw, keep = 0.25f) else accent.copy(alpha = 0.12f),
    )
}

private fun ensureContrast(color: Color): Color {
    val lum = ColorUtils.calculateLuminance(color.toArgb()).toFloat()
    return if (lum > 0.5f) darkenColor(color, keep = 0.55f) else color
}

@Composable
internal fun chipAccentColorFor(event: IslandEvent): Color {
    if (event is IslandEvent.Media && event.mediaColor != 0) {
        return darkenColor(Color(event.mediaColor))
    }
    if (event is IslandEvent.AppSwitch) {
        val app = event.previousApp ?: event.recentApps.firstOrNull()
        if (app?.appIcon != null) {
            val color = rememberPaletteColor(app.appIcon!!)
            if (color != null) return color
        }
    }
    if (event is IslandEvent.Notification && event.appIcon != null) {
        val isDark = isSystemInDarkTheme()
        val color = rememberPaletteColor(event.appIcon!!)
        if (color != null) return ensureContrast(color, isDark)
    }
    if (event is IslandEvent.AospChip) {
        val ctx = LocalContext.current
        return Color(event.active.colors.background(ctx).defaultColor)
    }
    return accentColorFor(event)
}

@Composable
private fun rememberPaletteColor(drawable: Drawable): Color? {
    val color by produceState<Color?>(null, drawable) {
        
        val bitmap = try {
            withContext(Dispatchers.Main) { drawable.toBitmap(24, 24) }
        } catch (_: Exception) { null }
        value = bitmap?.let {
            withContext(Dispatchers.Default) {
                try {
                    val palette = Palette.from(it).generate()
                    val swatch =
                        palette.darkVibrantSwatch
                            ?: palette.vibrantSwatch
                            ?: palette.darkMutedSwatch
                            ?: palette.dominantSwatch
                    swatch?.let { s -> Color(s.rgb) }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    return color
}

@Composable
internal fun StatusChip(text: String, color: Color = SubtleGray) {
    Box(
        modifier =
            Modifier.background(color.copy(alpha = AlphaSubtle), ShapeChip)
                .padding(horizontal = SpaceXl, vertical = SpaceSm)
    ) {
        Text(text.uppercase(), color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun CircleButton(
    color: Color = ActionBg,
    size: Dp = 48.dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = color,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) { content() }
    }
}

@Composable
internal fun ExpandedCardLayout(
    accentColor: Color,
    icon: @Composable () -> Unit,
    iconSize: Dp = 44.dp,
    iconBackground: Boolean = true,
    title: @Composable ColumnScope.() -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    actions: @Composable (ColumnScope.() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SpaceLg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeLg)
                .background(accentColor.copy(alpha = AlphaFaint))
                .padding(SpaceLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceLg),
        ) {
            if (iconBackground) {
                Box(
                    modifier = Modifier
                        .size(iconSize)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = AlphaSubtle)),
                    contentAlignment = Alignment.Center,
                ) { icon() }
            } else {
                Box(
                    modifier = Modifier.size(iconSize),
                    contentAlignment = Alignment.Center,
                ) { icon() }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SpaceXs),
            ) { title() }
            trailing?.invoke()
        }
        actions?.invoke(this)
    }
}

@Composable
internal fun ActionChip(
    label: String,
    icon: ImageVector? = null,
    color: Color = OnActionText,
    bg: Color = ActionBg,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ShapeChip,
        color = bg,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.height(SizeActionHeight).padding(horizontal = SpacePanel),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceMd, Alignment.CenterHorizontally),
        ) {
            if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size(SizeIconSm))
            }
            Text(label, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
internal fun ExpressivePillButton(
    label: String,
    icon: ImageVector? = null,
    contentColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = backgroundColor,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.height(SizeActionHeight).padding(horizontal = SpacePanel),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            if (icon != null) {
                Icon(
                    icon, null, tint = contentColor, modifier = Modifier.size(SizeIconSm),
                )
            }
            Text(label, color = contentColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

internal data class MediaProgress(val progress: Float, val positionMs: Long)

@Composable
internal fun rememberMediaProgress(event: IslandEvent.Media): MediaProgress {
    return MediaProgress(event.progress, event.position)
}

internal fun formatElapsedTime(ms: Long): String {
    val secs = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(secs / 60, secs % 60)
}

internal fun formatCountdownSeconds(secs: Long): String {
    val s = secs.coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

internal fun formatCountdownLong(ms: Long): String {
    val secs = (ms / 1000).coerceAtLeast(0)
    val mins = secs / 60
    val hrs = mins / 60
    return if (hrs > 0) "%02d:%02d:%02d".format(hrs, mins % 60, secs % 60)
    else "%02d:%02d".format(mins, secs % 60)
}

internal fun formatStopwatch(ms: Long): String {
    val totalSecs = (ms / 1000).coerceAtLeast(0)
    val hrs = totalSecs / 3600
    val mins = (totalSecs % 3600) / 60
    val secs = totalSecs % 60
    val tenths = ((ms % 1000) / 100).coerceIn(0, 9)
    return if (hrs > 0) "%02d:%02d:%02d.%d".format(hrs, mins, secs, tenths)
    else "%02d:%02d.%d".format(mins, secs, tenths)
}

internal fun formatTimeAgo(timestampMs: Long, res: android.content.res.Resources): String {
    val diff = System.currentTimeMillis() - timestampMs
    if (diff < 0) return ""
    val secs = diff / 1000
    val mins = secs / 60
    val hrs = mins / 60
    val days = hrs / 24
    return when {
        secs < 60 -> res.getString(R.string.ax_dynamic_bar_just_now)
        mins < 60 -> res.getString(R.string.ax_dynamic_bar_mins_ago, mins.toInt())
        hrs < 24 -> res.getString(R.string.ax_dynamic_bar_hours_ago, hrs.toInt())
        days < 7 -> res.getString(R.string.ax_dynamic_bar_days_ago, days.toInt())
        else -> res.getString(R.string.ax_dynamic_bar_weeks_ago, (days / 7).toInt())
    }
}

@Composable
internal fun Drawable.toScaledBitmap(sizeDp: Dp): ImageBitmap {
    val px = with(LocalDensity.current) { sizeDp.roundToPx() }
    return remember(this, px) { toBitmap(px, px).asImageBitmap() }
}

/**
 * Rasterised to *cover* a [sizeDp] square, keeping the drawable's aspect: the short side lands on
 * the target and the long one overhangs, for `ContentScale.Crop` to trim. [toScaledBitmap] forces
 * the square instead, which squashes anything that is not one.
 */
@Composable
internal fun Drawable.toCoverBitmap(sizeDp: Dp): ImageBitmap {
    val px = with(LocalDensity.current) { sizeDp.roundToPx() }
    return remember(this, px) {
        val w = intrinsicWidth
        val h = intrinsicHeight
        if (w <= 0 || h <= 0) {
            toBitmap(px, px).asImageBitmap()
        } else {
            val scale = max(px.toFloat() / w, px.toFloat() / h)
            toBitmap((w * scale).roundToInt(), (h * scale).roundToInt()).asImageBitmap()
        }
    }
}

internal fun chipProgressFor(event: IslandEvent): Float? =
    when (event) {
        is IslandEvent.Media ->
            if (event.duration > 0) (event.position.toFloat() / event.duration).coerceIn(0f, 1f)
            else null
        is IslandEvent.PromotedOngoing ->
            if (event.progress >= 0f) event.progress.coerceIn(0f, 1f) else null
        is IslandEvent.Notification ->
            if (event.progress >= 0)
                (event.progress.toFloat() / event.progressMax).coerceIn(0f, 1f)
            else null
        else -> null
    }

internal fun iconKeyFor(event: IslandEvent): Any =
    when (event) {
        is IslandEvent.Media -> event.albumArt?.hashCode() ?: "media_default"
        is IslandEvent.Notification -> event.appIcon?.hashCode() ?: "notif_default"
        is IslandEvent.AppSwitch -> {
            val app = event.previousApp ?: event.recentApps.firstOrNull()
            app?.appIcon?.hashCode() ?: "app_default"
        }
        else -> event::class.simpleName ?: "default"
    }

internal fun textKeyFor(event: IslandEvent): Any =
    when (event) {
        is IslandEvent.Media -> {
            val actionsKey = event.customActions.joinToString(separator = ",") { "${it.action}:${it.label}" }
            "${event.track}|${event.artist}|${event.isPlaying}|${event.outputDeviceName}|$actionsKey"
        }
        is IslandEvent.Sports -> "${event.id}|${event.score1}|${event.score2}|${event.team1Name}|${event.team2Name}"
        is IslandEvent.Charging -> "${event.id}|${event.level}|${event.isCharging}"
        is IslandEvent.Notification -> "${event.id}|${event.title}|${event.text}"
        is IslandEvent.Timer,
        is IslandEvent.Stopwatch,
        is IslandEvent.AudioRecording -> "tick_text"
        else -> event.id
    }

/**
 * Map a custom-action label to a stock glyph when we recognise the intent.
 * Prefer this over the app drawable: apps (ViviMusic especially) ship multi-state
 * icons as filled rounded squares with the arrows cut out — [Icon] + tint is SrcIn,
 * so every opaque pixel becomes the tint and you get a solid white badge.
 *
 * Returns null when the label is unknown so the caller can fall back to the app art
 * without inventing a Shuffle glyph for "Add to queue".
 */
internal fun resolveLabelIcon(label: String): ImageVector? {
    val lower = label.lowercase()
    return when {
        lower.contains("shuffle") -> Icons.Filled.Shuffle
        // Multi-state repeat: "repeat one" / "repeat_one" / "single" before bare "repeat".
        lower.contains("repeat") &&
            (lower.contains("one") ||
                lower.contains("single") ||
                lower.contains("track") ||
                Regex("""\b1\b""").containsMatchIn(lower)) -> Icons.Filled.RepeatOne
        lower.contains("repeat") -> Icons.Filled.Repeat
        lower.contains("thumb") && lower.contains("up") -> Icons.Filled.ThumbUp
        lower.contains("thumb") && lower.contains("down") -> Icons.Filled.ThumbDown
        lower.contains("like") || lower.contains("love") || lower.contains("favorite") ->
            Icons.Filled.Favorite
        else -> null
    }
}

/** Raster size for custom-action drawables — big enough to key on, cheap to scan (2304 px). */
private const val CustomActionRaster = 48

/** Above this share of opaque pixels a drawable is a plate, not a glyph. */
private const val GlyphMaxCoverage = 0.55f

/** Above this there is effectively no alpha channel to key on at all. */
private const val OpaqueTileMinCoverage = 0.95f

/**
 * Reduce an app's custom-action drawable to an alpha mask, so [Icon] can paint it in our colour.
 *
 * Apps are meant to ship a monochrome glyph on transparency, and most do — that case passes
 * through unchanged and simply gets tinted. Two shapes have to be corrected first, because
 * tinting is SrcIn and would otherwise paint the wrong pixels:
 *
 * - **Knocked-out badge** (ViviMusic's `repeat_on`): a filled rounded square with the arrows cut
 *   out of it. The glyph is the *transparent* part, so tinting fills the square solid. Inverting
 *   alpha recovers the glyph.
 * - **Fully opaque tile**: no alpha to key on. Treat the corner pixel as background and derive
 *   alpha from how far each pixel departs from it.
 *
 * Deciding from the pixels means no dependence on the action's label, which is app-authored and
 * localised.
 */
private fun glyphMaskOf(src: android.graphics.Bitmap): ImageBitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)

    var opaque = 0
    for (p in px) if ((p ushr 24) > 127) opaque++
    val coverage = opaque.toFloat() / px.size

    val out = IntArray(px.size)
    when {
        coverage < GlyphMaxCoverage ->
            for (i in px.indices) out[i] = (px[i] and ALPHA_MASK) or RGB_WHITE
        coverage < OpaqueTileMinCoverage ->
            for (i in px.indices) out[i] = ((255 - (px[i] ushr 24)) shl 24) or RGB_WHITE
        else -> {
            val bgLum = ColorUtils.calculateLuminance(px[0])
            for (i in px.indices) {
                val d = kotlin.math.abs(ColorUtils.calculateLuminance(px[i]) - bgLum)
                out[i] = ((d * 255).roundToInt().coerceIn(0, 255) shl 24) or RGB_WHITE
            }
        }
    }
    return android.graphics.Bitmap
        .createBitmap(out, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

private const val ALPHA_MASK = 0xFF000000.toInt()
private const val RGB_WHITE = 0x00FFFFFF

@Composable
internal fun CustomActionIcon(
    ca: IslandEvent.MediaCustomAction,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    // The app's own drawable is the only thing that knows what this action is and what state
    // it is in — PlaybackState carries no type and no on/off flag, and the app republishes a
    // new drawable whenever the state changes. Draw what it sent; never substitute a glyph of
    // our own for one we think we recognise (a forced heart is always filled).
    val mask =
        ca.icon?.let { drawable ->
            remember(drawable) {
                try {
                    glyphMaskOf(drawable.toBitmap(CustomActionRaster, CustomActionRaster))
                } catch (_: Exception) {
                    null
                }
            }
        }
    if (mask != null) {
        Icon(mask, ca.label, tint = tint, modifier = modifier)
        return
    }
    // Only with no drawable at all do we fall back to guessing from the label.
    Icon(resolveLabelIcon(ca.label) ?: Icons.Filled.Shuffle, ca.label, tint = tint, modifier = modifier)
}

internal fun PendingIntent.sendWithBal(context: Context, fillIntent: Intent? = null) {
    val options = ActivityOptions.makeBasic()
    options.setPendingIntentBackgroundActivityStartMode(
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
    )
    send(context, 0, fillIntent, null, null, null, options.toBundle())
}


@Composable
internal fun IslandStackCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black,
) {
    Box(
        modifier = modifier
            .background(backgroundColor, CircleShape)
            .border(1.dp, Color.White, CircleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = count.toString(),
            style = TsBadge.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp
            ),
            color = Color.White
        )
    }
}
