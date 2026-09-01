package studio.cluvex.aether.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.sin
import kotlinx.coroutines.delay
import studio.cluvex.aether.R
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.HevTunnel
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.ui.theme.AetherGlowCyan
import studio.cluvex.aether.ui.theme.AetherMint
import studio.cluvex.aether.ui.theme.CardSubSurface
import studio.cluvex.aether.ui.theme.CardSurfaceBottom
import studio.cluvex.aether.ui.theme.CardSurfaceTop
import studio.cluvex.aether.ui.theme.CardTextDim
import studio.cluvex.aether.ui.theme.CardTextMuted
import studio.cluvex.aether.ui.theme.CardTextPrimary

/**
 * THE bottom block of the home screen (new in 1.2.6).
 *
 * Until 1.2.5 the area under the power button was four separate floating
 * surfaces - status text, traffic meter, IP badge and the protocol/endpoint/
 * latency row - each with its own colour, radius and padding. They read as
 * clutter on a phone screen and nothing tied them together.
 *
 * This is one cohesive glassmorphic card instead, with a single surface colour
 * system and a fixed vertical hierarchy:
 *
 *   1. connection status  (large, mint, with a quiet "tap to disconnect" line)
 *   2. session timer      ("Connected for" + HH:MM:SS in a mono/digital face)
 *   3. server IP pill     (label + country flag + address)
 *   4. speed strip        (live down/up rate and session totals)
 *   5. protocol slide     (full width)
 *   6. endpoint slide     (full width)
 *   7. latency slide      (full width, with the animated ping-strength meter)
 *
 * Nothing floats outside the block: every sub-section is a child container of
 * the same card, drawn from the same palette.
 *
 * ONE ROW PER FACT (1.2.7). Steps 5-7 used to be a single three-column strip,
 * `Protocol | Endpoint | Latency`, each column a third of a phone screen wide.
 * That is not enough room for the values they carry: `WIREGUARD` arrived as
 * `WIREGUARD ...` and an endpoint (`ip:port`, up to 21 characters, more for
 * IPv6) was almost always just `...`. A label nobody can read the value of is
 * decoration. Each now gets its own full-width slide, stacked in the same card,
 * label on the left and value on the right with the whole card width to use.
 *
 * The latency slide also carries the ping-strength meter: on its own, one row
 * for `122 ms` left most of the slide empty, so the free space now shows the
 * measurement as a live travelling waveform whose height and speed follow the
 * quality of the last probe.
 *
 * COLOURS ARE DELIBERATELY NOT FROM MaterialTheme. The app runs Material You
 * (`dynamicDarkColorScheme`) on Android 12+, which repaints every themed
 * surface from the user's wallpaper - a purple or brown wallpaper turned this
 * card into something that no longer looked like Aether. The card is pinned to
 * the brand palette instead: deep navy background (#0A0E1A), mint accent
 * (#3EDBB0), one slate glass surface for everything inside it.
 *
 * CONNECTED-STATE ANIMATION. While the tunnel is up, the card edge carries a
 * light show: segments of light travel around the border and breathe in length,
 * width and intensity like an audio equaliser, so the card feels alive rather
 * than blinking. Each full lap of the perimeter runs in ONE colour and the next
 * lap takes the next colour of [GlowCycleColors] - the four primaries - wrapping
 * back to the first after the last. See [rememberGlowCycle].
 *
 * This card is now the ONLY surface that carries the light. 1.2.7 also put it
 * around the connect button; two light shows competed for the eye, and the ring's
 * bloom cost the top of the screen 70 dp of padding that the content block
 * needed. The button keeps its halo and its tick.
 */
@Composable
fun ConnectionCard(
    connected: Boolean,
    statusTitle: String,
    statusCaption: String,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        error -> ERROR_ACCENT
        connected -> AetherMint
        else -> IDLE_ACCENT
    }

    // Only alive while connected: no frame subscription when there is nothing
    // to show off.
    val cycle = if (connected) rememberGlowCycle() else null

    Box(
        modifier = modifier
            .fillMaxWidth()
            // clip = false so the glow may bloom past the card edge.
            .shadow(
                elevation = 26.dp,
                shape = CARD_SHAPE,
                clip = false,
                ambientColor = Color.Black,
                spotColor = Color.Black,
            )
            .background(
                brush = Brush.verticalGradient(listOf(CardSurfaceTop, CardSurfaceBottom)),
                shape = CARD_SHAPE,
            )
            .glassEdge(accent = accent, cycle = cycle)
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            // 11 dp: three stacked meta slides replaced one strip in 1.2.7, and
            // the card keeps its proportions by tightening the rhythm rather than
            // by growing past the fold. Combined with HomeScreen's fit-to-height
            // pass, this is what keeps the whole block on one screen.
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            StatusBlock(title = statusTitle, caption = statusCaption, accent = accent)
            TimerBlock(connectedSince = connectedSince, connected = connected)
            ServerIpPill(connected = connected, ipInfo = ipInfo, ipLoading = ipLoading)
            SpeedStrip(connectedSince = connectedSince, connected = connected)
            MetaSlides(connected = connected)
        }
    }
}

// --------------------------------------------------------------- 1. status

@Composable
private fun StatusBlock(title: String, caption: String, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = title,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "cardStatus",
        ) { value ->
            Text(
                text = value,
                fontSize = 27.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = accent,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(4.dp))
        AnimatedContent(
            targetState = caption,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "cardCaption",
        ) { value ->
            Text(
                text = value,
                fontSize = 13.sp,
                color = CardTextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------- 2. timer

@Composable
private fun TimerBlock(connectedSince: Long?, connected: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedSince) {
        if (connectedSince == null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val elapsed = if (connectedSince == null) 0L else (now - connectedSince).coerceAtLeast(0L) / 1000L
    val text = String.format(
        Locale.US,
        "%02d:%02d:%02d",
        elapsed / 3600,
        (elapsed % 3600) / 60,
        elapsed % 60,
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.connected_for),
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
            color = CardTextMuted,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 33.sp,
            letterSpacing = 1.2.sp,
            color = if (connected) CardTextPrimary else CardTextDim,
        )
    }
}

// ------------------------------------------------------------ 3. server IP

@Composable
private fun ServerIpPill(connected: Boolean, ipInfo: IpEndpoint?, ipLoading: Boolean) {
    val label = stringResource(
        if (connected) R.string.ip_server_label else R.string.ip_your_label,
    )
    val flag = NetProbe.flagEmoji(ipInfo?.countryCode)
    val value = when {
        ipLoading && ipInfo == null -> stringResource(R.string.ip_checking)
        ipInfo != null -> ipInfo.ip
        else -> stringResource(R.string.ip_unavailable)
    }

    Row(
        modifier = Modifier
            .background(color = CardSubSurface, shape = CircleShape)
            .subEdge(CircleShape)
            .padding(horizontal = 15.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = label, fontSize = 12.sp, color = CardTextMuted)
        if (ipInfo != null) {
            Text(text = flag, fontSize = 15.sp)
        }
        AnimatedContent(
            targetState = value,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "cardIp",
        ) { shown ->
            Text(
                text = shown,
                // BiDi: an address is LTR technical text even in the Persian UI.
                style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Ltr),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = CardTextPrimary,
            )
        }
    }
}

// --------------------------------------------------------------- 4. speeds

@Composable
private fun SpeedStrip(connectedSince: Long?, connected: Boolean) {
    val stats = rememberTrafficStats(connectedSince = connectedSince, connected = connected)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = CardSubSurface, shape = SUB_SHAPE)
            .subEdge(SUB_SHAPE)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedCell(
            icon = Icons.Rounded.ArrowDownward,
            tint = AetherMint,
            label = stringResource(R.string.traffic_download),
            rate = stats.downRate,
            total = stats.downTotal,
            modifier = Modifier.weight(1f),
        )
        CellDivider()
        SpeedCell(
            icon = Icons.Rounded.ArrowUpward,
            tint = AetherGlowCyan,
            label = stringResource(R.string.traffic_upload),
            rate = stats.upRate,
            total = stats.upTotal,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SpeedCell(
    icon: ImageVector,
    tint: Color,
    label: String,
    rate: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.background(color = tint.copy(alpha = 0.14f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier
                    .padding(5.dp)
                    .size(15.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 9.dp)) {
            Text(
                text = formatRate(rate),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = CardTextPrimary,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.traffic_total, formatBytes(total)),
                fontSize = 10.sp,
                color = CardTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------- 5-7. protocol / endpoint / ping

/**
 * The three meta slides, stacked inside the SAME card (1.2.7).
 *
 * They share one source of truth and one probe loop, which is why they are one
 * composable instead of three: the latency refresh is a single coroutine tied to
 * [connected], exactly as the old three-column strip had it.
 */
@Composable
private fun MetaSlides(connected: Boolean) {
    val meta by EngineMeta.state.collectAsState()
    val ping by PingMonitor.state.collectAsState()

    // Live latency, exactly like the desktop edition: one cheap TCP handshake
    // through the tunnel every few seconds, serialised by PingMonitor.
    LaunchedEffect(connected) {
        while (connected) {
            PingMonitor.pingOnce(viaTunnel = true)
            delay(LATENCY_REFRESH_MS)
        }
    }

    // The last GOOD reading. PingMonitor publishes ms = -1 while a probe is in
    // flight, and taking that at face value made the latency value blink to an
    // ellipsis - and the meter collapse - for a moment every four seconds.
    var lastMs by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(connected, ping.ms) {
        lastMs = when {
            !connected -> -1L
            ping.ms >= 0L -> ping.ms
            else -> lastMs
        }
    }

    val dash = "\u2014"
    val protocol = if (connected) meta.protocol ?: dash else dash
    val endpoint = if (connected) meta.endpoint ?: "\u2026" else dash
    val latency = when {
        !connected -> dash
        lastMs >= 0L -> "$lastMs ms"
        ping.running -> "\u2026"
        else -> dash
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetaSlide(label = stringResource(R.string.meta_protocol), value = protocol)
        MetaSlide(
            label = stringResource(R.string.meta_endpoint),
            value = endpoint,
            // An endpoint is up to 21 characters for IPv4:port and longer for
            // IPv6, so it gets two lines rather than an ellipsis.
            valueMaxLines = 2,
        )
        MetaSlide(
            label = stringResource(R.string.meta_latency),
            value = latency,
            below = { PingStrength(connected = connected, ms = lastMs) },
        )
    }
}

/**
 * One full-width slide: label on the left, value on the right, and an optional
 * block underneath for anything that needs the whole width (the ping meter).
 */
@Composable
private fun MetaSlide(
    label: String,
    value: String,
    valueMaxLines: Int = 1,
    below: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = CardSubSurface, shape = SUB_SHAPE)
            .subEdge(SUB_SHAPE)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                letterSpacing = 0.6.sp,
                color = CardTextMuted,
                maxLines = 1,
            )
            Spacer(Modifier.width(12.dp))
            // weight(1f) is what makes the value readable: it owns every pixel
            // the label does not, which is the whole point of the 1.2.7 slides.
            AnimatedContent(
                targetState = value,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "metaValue",
                modifier = Modifier.weight(1f),
            ) { shown ->
                Text(
                    text = shown,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = CardTextPrimary,
                    maxLines = valueMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                    // BiDi: a protocol name or an ip:port is LTR technical text
                    // even in the Persian UI.
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                )
            }
        }
        if (below != null) {
            Spacer(Modifier.height(9.dp))
            below()
        }
    }
}

/**
 * The animated ping-strength meter (1.2.7).
 *
 * A latency slide holding one short number left most of its width empty, so the
 * space carries the measurement instead: a travelling waveform of rounded bars
 * whose height, brightness and colour follow the quality of the last probe. A
 * 40 ms tunnel is a tall mint wave, a 400 ms one a flat rose one. The bars keep a
 * small floor height while disconnected so the row never collapses.
 *
 * The travel speed is FIXED on purpose. Deriving it from the latency meant the
 * spec changed on every probe, and a new spec restarts an infinite transition -
 * the wave visibly jumped once every four seconds. Height and colour carry the
 * measurement; the motion just has to look alive.
 *
 * Frame cost is one Canvas redraw: the animation value is read inside
 * `drawBehind`, and the transition is only composed while connected.
 */
@Composable
private fun PingStrength(connected: Boolean, ms: Long) {
    // 1 = excellent, 0 = unusable. Reused for height, speed and colour so the
    // three can never disagree.
    val strength = when {
        !connected || ms < 0L -> 0f
        ms <= PING_BEST_MS -> 1f
        ms >= PING_WORST_MS -> 0.08f
        else -> 1f - (ms - PING_BEST_MS).toFloat() / (PING_WORST_MS - PING_BEST_MS)
    }.coerceIn(0f, 1f)

    // Animated so a new probe glides to its new height instead of snapping.
    val level by animateFloatAsState(
        targetValue = strength,
        animationSpec = tween(700),
        label = "pingLevel",
    )
    val tint = pingTint(strength)
    val quality = stringResource(pingQualityRes(connected, ms))

    // No frame subscription at all while the tunnel is down.
    val travel = if (connected) {
        rememberInfiniteTransition(label = "pingWave").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(PING_WAVE_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "pingTravel",
        )
    } else {
        null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.meta_ping_strength),
                fontSize = 10.sp,
                letterSpacing = 0.4.sp,
                color = CardTextDim,
                maxLines = 1,
            )
            Text(
                text = quality,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (connected) tint else CardTextDim,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PING_WAVE_HEIGHT)
                .drawBehind {
                    val phase = travel?.value ?: 0f
                    val gap = PING_BAR_GAP.toPx()
                    val barWidth = ((size.width + gap) / PING_BARS) - gap
                    if (barWidth <= 0f) return@drawBehind
                    val radius = CornerRadius(barWidth / 2f)
                    val floorH = size.height * 0.16f

                    for (i in 0 until PING_BARS) {
                        val x = i / (PING_BARS - 1f)
                        // Two harmonics travelling in opposite directions: the
                        // crest never sits still and the pattern never repeats
                        // in a way the eye can lock onto.
                        val a = 0.5f + 0.5f * sin(TWO_PI * (2.0f * x - phase))
                        val b = 0.5f + 0.5f * sin(TWO_PI * (3.7f * x + 1.6f * phase))
                        val wave = 0.58f * a + 0.42f * b
                        val h = floorH + (size.height - floorH) * level * wave
                        val top = (size.height - h) / 2f
                        val left = i * (barWidth + gap)
                        val alpha = 0.30f + 0.70f * wave * (0.25f + 0.75f * level)

                        drawRoundRect(
                            color = tint.copy(alpha = alpha.coerceIn(0f, 1f)),
                            topLeft = Offset(left, top),
                            size = Size(barWidth, h),
                            cornerRadius = radius,
                        )
                    }
                },
        )
    }
}

private fun pingTint(strength: Float): Color = when {
    strength >= 0.66f -> AetherMint
    strength >= 0.33f -> PING_FAIR
    strength > 0.10f -> PING_POOR
    else -> CardTextDim
}

private fun pingQualityRes(connected: Boolean, ms: Long): Int = when {
    !connected -> R.string.ping_quality_offline
    ms < 0L -> R.string.ping_quality_measuring
    ms <= PING_BEST_MS -> R.string.ping_quality_excellent
    ms <= 160L -> R.string.ping_quality_good
    ms <= 300L -> R.string.ping_quality_fair
    else -> R.string.ping_quality_poor
}

@Composable
private fun CellDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(28.dp)
            .background(DIVIDER),
    )
}

// ------------------------------------------------------------ traffic feed

/** Instantaneous rates + session totals, polled once per second. */
private data class TrafficStats(
    val downRate: Long = 0L,
    val upRate: Long = 0L,
    val downTotal: Long = 0L,
    val upTotal: Long = 0L,
)

/**
 * Sums BOTH possible traffic paths so the meter works in every mode:
 *  - hev-socks5-tunnel's direction-corrected counters (system-VPN mode; null in
 *    proxy mode, where there is no TUN),
 *  - ShareBridge: bytes relayed through the local SOCKS5/HTTP listeners (the
 *    only source in proxy mode, plus LAN clients in system-VPN mode).
 *
 * Rates come from deltas against a monotonic clock, so a wall-clock jump cannot
 * invent a spike. A negative delta (core restart during auto-reconnect, or a
 * fresh sharing session resetting the bridge counters) is clamped to zero and
 * the baseline rebases itself.
 */
@Composable
private fun rememberTrafficStats(connectedSince: Long?, connected: Boolean): TrafficStats {
    var stats by remember(connectedSince) { mutableStateOf(TrafficStats()) }

    LaunchedEffect(connectedSince, connected) {
        if (!connected) return@LaunchedEffect
        var lastDown = -1L
        var lastUp = -1L
        var lastAt = 0L
        while (true) {
            val hev = HevTunnel.traffic()
            val share = ShareBridge.traffic()
            if (hev != null || ShareBridge.active.value) {
                val down = (hev?.downloadBytes ?: 0L) + share.downloadBytes
                val up = (hev?.uploadBytes ?: 0L) + share.uploadBytes
                val at = SystemClock.elapsedRealtime()
                var downRate = stats.downRate
                var upRate = stats.upRate
                if (lastAt > 0L && at > lastAt) {
                    val dt = at - lastAt
                    downRate = ((down - lastDown).coerceAtLeast(0L) * 1000L) / dt
                    upRate = ((up - lastUp).coerceAtLeast(0L) * 1000L) / dt
                }
                stats = TrafficStats(downRate, upRate, down, up)
                lastDown = down
                lastUp = up
                lastAt = at
            }
            delay(1_000L)
        }
    }

    return stats
}

// ------------------------------------------------------- the animated edge

/**
 * The card edge: a soft inner glow, a hairline teal border, and - while
 * connected - the travelling light, one colour per lap (see [GlowCycle]).
 */
private fun Modifier.glassEdge(accent: Color, cycle: GlowCycle?): Modifier = drawWithCache {
    val hairline = 1.dp.toPx()
    // The travelling light is drawn a touch wider than the structural hairline:
    // at exactly 1 dp its core lands between two device pixels on most panels and
    // anti-aliasing spreads it into a soft, muddy line. 1.5 dp lands on a pixel
    // boundary far more often and reads as a sharp filament instead.
    val lightStroke = 1.5.dp.toPx()
    val inset = hairline / 2f
    val radius = CARD_RADIUS.toPx()
    val outline = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(inset, inset, size.width - inset, size.height - inset),
                cornerRadius = CornerRadius(radius),
            ),
        )
    }
    val measure = PathMeasure().apply { setPath(outline, true) }
    val perimeter = measure.length
    val band = Path()
    val innerGlow = Brush.radialGradient(
        colors = listOf(accent.copy(alpha = 0.10f), Color.Transparent),
        center = Offset(size.width / 2f, 0f),
        radius = size.width * 0.95f,
    )

    onDrawBehind {
        drawPath(outline, brush = innerGlow)
        drawPath(
            outline,
            color = accent.copy(alpha = if (cycle == null) 0.10f else 0.16f),
            style = Stroke(hairline),
        )
        if (cycle == null) return@onDrawBehind
        drawGlowCycle(
            measure = measure,
            perimeter = perimeter,
            band = band,
            cycle = cycle,
            stroke = lightStroke,
        )
    }
}

/** The 1px low-opacity teal rim shared by every sub-container in the card. */
private fun Modifier.subEdge(shape: CornerBasedShape): Modifier = drawWithCache {
    val hairline = 1.dp.toPx()
    val inset = hairline / 2f
    val radius = shape.topStart.toPx(size, this)
    val outline = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(inset, inset, size.width - inset, size.height - inset),
                cornerRadius = CornerRadius(radius),
            ),
        )
    }
    onDrawBehind { drawPath(outline, color = SUB_BORDER, style = Stroke(hairline)) }
}

// ---------------------------------------------------------------- helpers

private fun formatBytes(v: Long): String {
    if (v < 1024L) return "$v B"
    val kb = v / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun formatRate(v: Long): String = formatBytes(v) + "/s"

private val CARD_RADIUS = 26.dp
private val CARD_SHAPE = RoundedCornerShape(CARD_RADIUS)
private val SUB_SHAPE = RoundedCornerShape(18.dp)
private val SUB_BORDER = Color(0x1F3EDBB0)
private val DIVIDER = Color(0x1FFFFFFF)
private val IDLE_ACCENT = Color(0xFF4C8DFF)
private val ERROR_ACCENT = Color(0xFFFF5C7A)
private const val LATENCY_REFRESH_MS = 4_000L

// ---- ping meter tuning ----
private const val PING_BEST_MS = 80L
private const val PING_WORST_MS = 400L
private const val PING_BARS = 26
private val PING_BAR_GAP = 3.dp
private val PING_WAVE_HEIGHT = 22.dp
private const val PING_WAVE_MS = 1_500
private val PING_FAIR = Color(0xFFFFB43C)
private val PING_POOR = Color(0xFFFF5C7A)
