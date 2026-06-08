@file:Suppress("MagicNumber")

package com.example.foresightapk

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import androidx.compose.ui.graphics.lerp as colorLerp

private val BarWhite = Color(0xFFFFFFFF)
private val IconGray = Color(0xFF2C2C2C)
private const val HANDLE_BASE = 0.44f
private val ARC_BASE_DP = 22.dp
private val WAVE_MAX_DP = 6.dp
private const val MIN_DUR = 700
private const val MAX_DUR = 1200
private val PATH_EASING = CubicBezierEasing(0.15f, 0.00f, 0.00f, 1.00f)
private const val SRC_STRETCH_END = 0.35f
private const val SRC_CLOSE_END = 0.48f
private const val SOURCE_OVERSHOOT = 0.16f
private const val LAND_START = 0.70f
private const val LAND_END = 1.00f
private const val DEST_POP = 0.16f
private val ICON_CENTER_BIAS_DP = (-1.5).dp

@Composable
fun ConcaveNotchBottomBar(
    items: List<ImageVector>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 104.dp,
    cornerRadius: Dp = 18.dp,
    bubbleSize: Dp = 44.dp,
    notchPadding: Dp = 12.dp,
    barTopFraction: Float = 0.44f,
    itemBoxWidth: Dp = 34.dp,
    edgeTightenDp: Dp = 14.dp,
    bubbleColor: Color = Color(0xFF4FC3F7),
    selectedIconColor: Color = Color.White,
    unselectedIconColor: Color = IconGray
) {
    var fromIndex by remember { mutableIntStateOf(selectedIndex) }
    var toIndex by remember { mutableIntStateOf(selectedIndex) }
    val tAnim = remember { Animatable(1f) }

    LaunchedEffect(selectedIndex, items.size) {
        if (selectedIndex == toIndex) return@LaunchedEffect
        fromIndex = toIndex
        toIndex = selectedIndex
        val steps = abs(toIndex - fromIndex)
        val maxSteps = max(1, items.size - 1)
        val dur = lerpInt(MIN_DUR, MAX_DUR, steps.toFloat() / maxSteps)
        tAnim.snapTo(0f)
        tAnim.animateTo(1f, tween(dur))
    }

    val density = LocalDensity.current
    val barTopDp = barHeight * barTopFraction
    val whiteHeightDp = barHeight * (1f - barTopFraction)

    Surface(color = Color.Transparent, modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { barHeight.toPx() }
            val cornerPx = with(density) { cornerRadius.toPx() }
            val barTop = heightPx * barTopFraction
            val whiteHeight = heightPx - barTop
            val bubbleRadius = with(density) { bubbleSize.toPx() } / 2f
            val paddingPx = with(density) { notchPadding.toPx() }
            val notchHalfWidth = bubbleRadius * 1.72f + paddingPx
            val notchDepth = min(bubbleRadius + paddingPx * 1.35f, (heightPx - barTop) * 0.92f)
            val hardInset = cornerPx + notchHalfWidth
            val maxPull = max(0f, hardInset - (notchHalfWidth + with(density) { 4.dp.toPx() }))
            val inset = hardInset - min(with(density) { edgeTightenDp.toPx() }, maxPull)
            val itemWidthPx = with(density) { itemBoxWidth.toPx() }
            val centerStart = inset + itemWidthPx / 2f
            val centerEnd = (widthPx - inset) - itemWidthPx / 2f
            val step = if (items.size > 1) (centerEnd - centerStart) / (items.size - 1) else 0f
            fun centerXFor(index: Int) = centerStart + index * step

            val t = PATH_EASING.transform(tAnim.value)
            val slingT = slingTimeSmooth(t)
            val fromCx = centerXFor(fromIndex)
            val toCx = centerXFor(toIndex)
            val dx = toCx - fromCx
            val handle = HANDLE_BASE * (1f + 0.35f * (abs(dx) / (step * max(1, items.size - 1))))
            val bubbleCx = cubicBezier1D(fromCx, fromCx + dx * handle, toCx - dx * handle, toCx, slingT)
            val arcPx = with(density) { ARC_BASE_DP.toPx() } * (1f + 0.25f * (abs(dx) / max(step, 1f)))
            val jumpArc = -arcPx * sin(PI * slingT).toFloat()
            val valleyY = barTop + notchDepth
            val rawBubbleCy = valleyY - (bubbleRadius + paddingPx) + jumpArc
            val bubbleCy = max(bubbleRadius + with(density) { 2.dp.toPx() }, rawBubbleCy)
            val iconRestCY = barTop + whiteHeight / 2f
            val iconLiftPx = (bubbleCy - iconRestCY) + with(density) { ICON_CENTER_BIAS_DP.toPx() }
            val horizontalInsetDp = with(density) { inset.toDp() }
            val closeGate = 1f - gate(slingT, 0f, SRC_CLOSE_END)
            val stretchBell = softBell(slingT, 0f, SRC_STRETCH_END)
            val srcOvershoot = 1f + SOURCE_OVERSHOOT * stretchBell
            val slotsAway = abs((bubbleCx - toCx) / (step.takeIf { it > 0f } ?: 1f))
            val prox = smoothstepPow(1f - slotsAway.coerceIn(0f, 1.1f), 3.0f)
            val contactRaw = 1f - (abs(jumpArc) / max(arcPx, 1e-3f))
            val contact = (contactRaw.coerceIn(0f, 1f) * gate(slingT, LAND_START, LAND_END)).coerceIn(0f, 1f)
            val destStrength = (prox * contact).coerceIn(0f, 1f)
            val contactPop = if (contact > 0f) destStrength.pow(0.6f) else 0f
            val sourceStrength = closeGate
            val notches = buildList {
                if (sourceStrength > 0.001f) add(
                    Notch(
                        cx = fromCx,
                        halfWidth = notchHalfWidth * sourceStrength * srcOvershoot,
                        depth = notchDepth * sourceStrength * srcOvershoot
                    )
                )
                if (destStrength > 0.001f) add(
                    Notch(
                        cx = toCx,
                        halfWidth = notchHalfWidth * destStrength * (1f + DEST_POP * contactPop),
                        depth = notchDepth * destStrength * (1f + DEST_POP * contactPop)
                    )
                )
            }
            val wave = waveAmplitude(slingT, with(density) { WAVE_MAX_DP.toPx() }) + (2f * contactPop)
            val prevLiftStartPx = (valleyY - (bubbleRadius + paddingPx) - iconRestCY) +
                with(density) { ICON_CENTER_BIAS_DP.toPx() }
            val prevReturnLiftPx = prevLiftStartPx * closeGate
            val prevTintColor = colorLerp(selectedIconColor, unselectedIconColor, gate(slingT, 0f, SRC_CLOSE_END))

            Box(Modifier.fillMaxWidth()) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                ) {
                    drawTopRoundedBarWithNotches(
                        width = widthPx,
                        height = heightPx,
                        top = barTop,
                        topCornerRadius = cornerPx,
                        notches = notches,
                        waveCenterX = bubbleCx,
                        waveAmplitude = wave,
                        color = BarWhite
                    )
                    drawCircle(color = bubbleColor, radius = bubbleRadius, center = Offset(bubbleCx, bubbleCy))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                ) {
                    Spacer(Modifier.height(barTopDp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = horizontalInsetDp)
                            .height(whiteHeightDp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        items.forEachIndexed { index, icon ->
                            val isTo = index == toIndex
                            val isFrom = index == fromIndex && fromIndex != toIndex
                            val lift = when {
                                isTo -> iconLiftPx
                                isFrom -> prevReturnLiftPx
                                else -> 0f
                            }
                            val tint = when {
                                isTo -> selectedIconColor
                                isFrom -> prevTintColor
                                else -> unselectedIconColor
                            }
                            Box(
                                modifier = Modifier
                                    .width(itemBoxWidth)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { onItemSelected(index) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier
                                        .graphicsLayer { translationY = lift }
                                        .size(if (isTo) 28.dp else 26.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawTopRoundedBarWithNotches(
    width: Float,
    height: Float,
    top: Float,
    topCornerRadius: Float,
    notches: List<Notch>,
    waveCenterX: Float,
    waveAmplitude: Float,
    color: Color
) {
    val right = width
    val bottom = height
    val ns = notches.sortedBy { it.cx }
    val eps = 0.8f
    val nearThreshold = topCornerRadius + 2f
    val waveWidth = width * 0.26f
    val ctrlY = top - waveAmplitude * 0.6f
    val rightCtrlX = min(right - topCornerRadius * 1.1f, waveCenterX + waveWidth * 0.55f)
    val leftCtrlX = max(topCornerRadius * 1.1f, waveCenterX - waveWidth * 0.55f)
    val leftmost = ns.firstOrNull()
    val rightmost = ns.lastOrNull()
    val leftNearCorner = leftmost != null &&
        (leftmost.cx - leftmost.halfWidth) <= (topCornerRadius + nearThreshold)
    val rightNearCorner = rightmost != null &&
        (rightmost.cx + rightmost.halfWidth) >= (right - topCornerRadius - nearThreshold)

    val path = Path().apply {
        moveTo(0f, bottom)
        lineTo(right, bottom)
        lineTo(right, top + topCornerRadius)
        quadraticTo(right, top, right - topCornerRadius, top)

        for (i in ns.indices.reversed()) {
            val notch = ns[i]
            val endX = notch.cx + notch.halfWidth + eps
            if (i == ns.lastIndex && rightNearCorner) {
                lineTo(endX, top)
            } else {
                quadraticTo(rightCtrlX, ctrlY, endX, top)
            }
            roundedU(notch.cx, notch.halfWidth, notch.depth, top, eps)
        }

        if (leftNearCorner) {
            lineTo(topCornerRadius, top)
        } else {
            quadraticTo(leftCtrlX, ctrlY, topCornerRadius, top)
        }
        quadraticTo(0f, top, 0f, top + topCornerRadius)
        lineTo(0f, bottom)
        close()
    }
    drawPath(path = path, color = color)
}

private fun Path.roundedU(cx: Float, halfWidth: Float, depth: Float, top: Float, eps: Float) {
    val k = 0.66f
    val startX = cx - halfWidth - eps
    val endX = cx + halfWidth + eps
    val valleyY = top + depth
    cubicTo(endX - halfWidth * (1f - k), top, cx + halfWidth * k, valleyY, cx, valleyY)
    cubicTo(cx - halfWidth * k, valleyY, startX + halfWidth * (1f - k), top, startX, top)
}

private data class Notch(val cx: Float, val halfWidth: Float, val depth: Float)

private fun cubicBezier1D(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
    val u = 1f - t
    return u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
}

private fun slingTimeSmooth(t: Float): Float {
    val fastOut = CubicBezierEasing(0.05f, 0.00f, 0.20f, 1.00f).transform(t)
    val blend = if (t < 0.5f) 0.85f else 0.55f
    val base = (fastOut * blend + t * (1f - blend)).coerceIn(0f, 1f)
    val cushion = smoothstep((base / 0.25f).coerceIn(0f, 1f))
    return (0.12f * cushion + 0.88f * base).coerceIn(0f, 1f)
}

private fun softBell(t: Float, a: Float, b: Float): Float {
    if (t <= a || t >= b) return 0f
    val x = ((t - a) / (b - a)).coerceIn(0f, 1f)
    val s = smoothstep(x)
    val mid = if (s <= 0.5f) s * 2f else (1f - s) * 2f
    return smoothstep(mid)
}

private fun gate(t: Float, a: Float, b: Float): Float {
    if (t <= a) return 0f
    if (t >= b) return 1f
    return smoothstep(((t - a) / (b - a)).coerceIn(0f, 1f))
}

private fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * t * (t * (t * 6 - 15) + 10)
}

private fun smoothstepPow(x: Float, power: Float): Float {
    return smoothstep(x).toDouble().pow(power.toDouble()).toFloat()
}

private fun waveAmplitude(t: Float, maxAmp: Float): Float {
    val takeoff = if (t <= 0.35f) sin(t / 0.35f * PI).toFloat() else 0f
    val land = if (t >= 0.55f) sin((t - 0.55f) / 0.45f * PI).toFloat() else 0f
    return maxAmp * max(takeoff, land)
}

private fun lerpInt(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt()
