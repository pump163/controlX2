package com.jwoglom.controlx2.presentation.screens.sections.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme

/**
 * Horizontal pump battery readout: level artwork plus the percent number.
 *
 * While charging the whole readout switches to the theme's primary color and a solid
 * lightning bolt is drawn as its own layer between the icon and the number.
 *
 * The bolt is deliberately NOT taken from the `battery_horiz_*_charging` vectors: there the
 * bolt is a hole (evenOdd) in the level fill, so it inherits the page background color, cannot
 * be tinted separately, and below ~40% it sits in the already-empty part of the battery, which
 * makes it disappear completely.
 *
 * @param batteryPercent from 0 to 100
 * @param batteryCharging if charging, the readout turns accent-colored and shows the bolt
 */
@Composable
fun HorizBatteryIcon(
    batteryPercent: Int?,
    batteryCharging: Boolean? = null,
    height: Dp = 24.dp,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val percent = batteryPercent ?: return
    val charging = batteryCharging == true
    val neutralColor = if (color == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        color
    }
    val contentColor = if (charging) MaterialTheme.colorScheme.primary else neutralColor

    Row(
        modifier = modifier.height(height),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            getPainterForBatteryPercent(percent),
            contentDescription = stringResource(R.string.common_battery_icon),
            tint = contentColor,
            modifier = Modifier.size(height)
        )
        if (charging) {
            Spacer(Modifier.width(4.dp))
            ChargingBolt(modifier = Modifier.size(height * 0.58f))
        }
        Text(
            "${percent}%",
            color = contentColor,
            fontWeight = if (charging) FontWeight.SemiBold else null,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ChargingBolt(modifier: Modifier = Modifier) {
    val pulse = rememberChargingPulse()
    Icon(
        painterResource(R.drawable.ic_battery_charging_bolt),
        contentDescription = stringResource(R.string.common_charging),
        tint = MaterialTheme.colorScheme.primary,
        // Alpha is applied in the layer so the infinite animation only redraws this icon.
        modifier = modifier.graphicsLayer { alpha = pulse.value }
    )
}

/**
 * Breathing alpha for the charging bolt. Falls back to a constant (fully visible) alpha when
 * animations are switched off system-wide, and in previews / screenshot tests so they stay stable.
 */
@Composable
private fun rememberChargingPulse(): State<Float> {
    // areAnimatorsEnabled() is false when the system animator duration scale is 0 ("remove animations").
    if (LocalInspectionMode.current || !ValueAnimator.areAnimatorsEnabled()) {
        return remember { mutableStateOf(1f) }
    }
    val transition = rememberInfiniteTransition(label = "chargingBolt")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "chargingBoltAlpha"
    )
}

@Composable
fun getPainterForBatteryPercent(batteryPercent: Int, charging: Boolean = false): Painter {
    return painterResource(
        if (charging) {
            when {
                batteryPercent >= 80 -> R.drawable.battery_horiz_100_charging
                batteryPercent >= 60 -> R.drawable.battery_horiz_075_charging
                batteryPercent >= 40 -> R.drawable.battery_horiz_050_charging
                batteryPercent >= 25 -> R.drawable.battery_horiz_025_charging
                batteryPercent >= 10 -> R.drawable.battery_horiz_010_charging
                else -> R.drawable.battery_horiz_000_charging
            }
        } else {
            when {
                batteryPercent >= 80 -> R.drawable.battery_horiz_100
                batteryPercent >= 60 -> R.drawable.battery_horiz_075
                batteryPercent >= 40 -> R.drawable.battery_horiz_050
                batteryPercent >= 25 -> R.drawable.battery_horiz_025
                batteryPercent >= 10 -> R.drawable.battery_horiz_010
                else -> R.drawable.battery_horiz_000
            }
        }
    )
}

@Preview
@Composable
internal fun HorizBatteryIconPreview0() {
    Surface(
        color = Color.White,
    ) {
        HorizBatteryIcon(0, false)
    }
}

@Preview
@Composable
internal fun HorizBatteryIconPreview20() {
    Surface(
        color = Color.White,
    ) {
        HorizBatteryIcon(20, false)
    }
}

@Preview
@Composable
internal fun HorizBatteryIconPreview30() {
    Surface(
        color = Color.White,
    ) {
        HorizBatteryIcon(30, false)
    }
}

@Preview
@Composable
internal fun HorizBatteryIconPreview40() {
    Surface(
        color = Color.White,
    ) {
        HorizBatteryIcon(40, false)
    }
}

@Preview
@Composable
internal fun HorizBatteryIconPreview60() {
    Surface(
        color = Color.White,
    ) {
        HorizBatteryIcon(60, false)
    }
}

@Preview
@Composable
internal fun HorizBatteryIconPreview80() {
    Surface(
        color = Color.White,
    ) {
        HorizBatteryIcon(80, false)
    }
}


@Preview
@Composable
internal fun HorizBatteryIconPreview0Charging() {
    Surface(
        color = Color.White,
    ) {
        HorizBatteryIcon(0, true)
    }
}

@Preview
@Composable
internal fun HorizBatteryIconPreview20Charging() {
    Surface(
        color = Color.White,
    ) {
        HorizBatteryIcon(20, true)
    }
}

@Preview
@Composable
internal fun HorizBatteryIconPreview30Charging() {
    Surface(
        color = Color.White,
    ) {
        HorizBatteryIcon(30, true)
    }
}

@Preview
@Composable
internal fun HorizBatteryIconPreview40Charging() {
    Surface(
        color = Color.White,
    ) {
        HorizBatteryIcon(40, true)
    }
}

@Preview
@Composable
internal fun HorizBatteryIconPreview60Charging() {
    Surface(
        color = Color.White,
    ) {
        HorizBatteryIcon(60, true)
    }
}

@Preview
@Composable
internal fun HorizBatteryIconPreview80Charging() {
    Surface(
        color = Color.White,
    ) {
        HorizBatteryIcon(80, true)
    }
}

@Preview(showBackground = true)
@Composable
internal fun HorizBatteryIconPreview5ChargingDark() {
    ControlX2Theme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            HorizBatteryIcon(5, true)
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun HorizBatteryIconPreview5ChargingLight() {
    ControlX2Theme(darkTheme = false, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            HorizBatteryIcon(5, true)
        }
    }
}
