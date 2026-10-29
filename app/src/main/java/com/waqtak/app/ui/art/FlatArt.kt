package com.waqtak.app.ui.art

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.waqtak.app.domain.DrinkType
import com.waqtak.app.domain.MealType
import com.waqtak.app.ui.theme.WaqtakColors as C

/**
 * Original flat illustrations drawn with Compose Canvas (no bitmaps, no emoji). Each is drawn
 * in a 100x100 design space scaled to the available size. Motion is slow and limited.
 */
enum class Art { BREAKFAST, LUNCH, DINNER, SNACK, WATER, TEA, COFFEE, MILK, LABAN, JUICE, SODA, ENERGY, CUSTOM_DRINK, MEAL_END, BELL }

fun MealType?.art(): Art = when (this) {
    MealType.BREAKFAST -> Art.BREAKFAST
    MealType.LUNCH -> Art.LUNCH
    MealType.DINNER -> Art.DINNER
    MealType.SNACK -> Art.SNACK
    null -> Art.LUNCH
}

fun DrinkType.art(): Art = when (this) {
    DrinkType.WATER -> Art.WATER
    DrinkType.TEA -> Art.TEA
    DrinkType.COFFEE -> Art.COFFEE
    DrinkType.MILK -> Art.MILK
    DrinkType.LABAN -> Art.LABAN
    DrinkType.JUICE -> Art.JUICE
    DrinkType.SODA -> Art.SODA
    DrinkType.ENERGY -> Art.ENERGY
    DrinkType.CUSTOM -> Art.CUSTOM_DRINK
}

@Composable
fun FlatArt(art: Art, modifier: Modifier = Modifier, animated: Boolean = false, description: String? = null) {
    val phase = if (animated) {
        val t = rememberInfiniteTransition(label = "art")
        val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse), label = "phase")
        p
    } else 0.5f
    val m = if (description != null) modifier.semantics { contentDescription = description } else modifier
    Canvas(m) {
        val s = minOf(size.width, size.height) / 100f
        val dx = (size.width - 100f * s) / 2f
        val dy = (size.height - 100f * s) / 2f
        val u = U(this, s, dx, dy)
        when (art) {
            Art.BREAKFAST -> u.breakfast(phase)
            Art.LUNCH -> u.lunch(phase)
            Art.DINNER -> u.dinner(phase)
            Art.SNACK -> u.snack()
            Art.WATER -> u.glass(C.Sky.copy(alpha = 0.85f), phase)
            Art.TEA -> u.cup(C.Tea, phase, tea = true)
            Art.COFFEE -> u.cup(C.Coffee, phase, tea = false)
            Art.MILK -> u.bottle(Color.White, C.SkyLight)
            Art.LABAN -> u.bottle(Color(0xFFF7F3E8), C.SageLight)
            Art.JUICE -> u.juice()
            Art.SODA -> u.can(C.Berry)
            Art.ENERGY -> u.can(C.SkyDark, bolt = true)
            Art.CUSTOM_DRINK -> u.glass(C.Peach, phase)
            Art.MEAL_END -> u.mealEnd(phase)
            Art.BELL -> u.bell(phase)
        }
    }
}

/** Helper that maps the 100x100 design space onto the canvas. */
private class U(val d: DrawScope, val s: Float, val dx: Float, val dy: Float) {
    fun o(x: Float, y: Float) = Offset(dx + x * s, dy + y * s)
    fun sz(w: Float, h: Float) = Size(w * s, h * s)

    fun circle(c: Color, x: Float, y: Float, r: Float) = d.drawCircle(c, r * s, o(x, y))
    fun rect(c: Color, x: Float, y: Float, w: Float, h: Float, r: Float = 0f) =
        d.drawRoundRect(c, o(x, y), sz(w, h), CornerRadius(r * s, r * s))
    fun ring(c: Color, x: Float, y: Float, r: Float, w: Float) = d.drawCircle(c, r * s, o(x, y), style = Stroke(w * s))
    fun line(c: Color, x1: Float, y1: Float, x2: Float, y2: Float, w: Float) =
        d.drawLine(c, o(x1, y1), o(x2, y2), w * s, StrokeCap.Round)
    fun path(c: Color, block: Path.() -> Unit) = d.drawPath(Path().apply(block), c)
    fun Path.m(x: Float, y: Float) = moveTo(dx + x * s, dy + y * s)
    fun Path.l(x: Float, y: Float) = lineTo(dx + x * s, dy + y * s)
    fun Path.q(x1: Float, y1: Float, x2: Float, y2: Float) = quadraticTo(dx + x1 * s, dy + y1 * s, dx + x2 * s, dy + y2 * s)

    fun backdrop(c: Color) = circle(c, 50f, 50f, 47f)

    fun plate(cx: Float = 50f, cy: Float = 66f, r: Float = 28f) {
        d.drawOval(C.Shadow, o(cx - r, cy - r * 0.3f + 4f), sz(2 * r, r * 0.6f + 4f))
        d.drawOval(C.Plate, o(cx - r, cy - r * 0.35f), sz(2 * r, r * 0.7f))
        d.drawOval(C.IvoryDeep, o(cx - r * 0.7f, cy - r * 0.24f), sz(1.4f * r, r * 0.48f))
    }

    fun steam(x: Float, y: Float, phase: Float, c: Color = Color.White.copy(alpha = 0.8f)) {
        val sway = (phase - 0.5f) * 4f
        for (i in 0..1) {
            val bx = x + i * 8f
            d.drawPath(Path().apply {
                m(bx, y); q(bx - 4f + sway, y - 6f, bx, y - 12f); q(bx + 4f - sway, y - 18f, bx, y - 24f)
            }, c, style = Stroke(2.4f * s, cap = StrokeCap.Round))
        }
    }

    fun breakfast(phase: Float) {
        backdrop(C.PeachLight)
        // morning sun with slowly turning rays
        d.rotate(phase * 20f, o(72f, 28f)) {
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                line(C.Sun, 72f + 13f * kotlin.math.cos(a).toFloat(), 28f + 13f * kotlin.math.sin(a).toFloat(),
                    72f + 18f * kotlin.math.cos(a).toFloat(), 28f + 18f * kotlin.math.sin(a).toFloat(), 2.6f)
            }
        }
        circle(C.Sun, 72f, 28f, 10f)
        plate(46f, 68f, 30f)
        // fried egg + bread
        path(Color.White) { m(30f, 66f); q(32f, 56f, 44f, 58f); q(54f, 56f, 54f, 64f); q(56f, 74f, 42f, 73f); q(28f, 74f, 30f, 66f); close() }
        circle(C.Sun, 42f, 65f, 5.5f)
        rect(Color(0xFFE2B27A), 52f, 58f, 16f, 13f, 4f)
        rect(Color(0xFFF3D3A6), 54f, 60f, 12f, 9f, 3f)
        // small cup
        rect(C.SkyDark, 14f, 44f, 14f, 15f, 4f)
        ring(C.SkyDark, 29f, 51f, 4f, 2.2f)
    }

    fun lunch(phase: Float) {
        backdrop(C.SageLight)
        plate(50f, 64f, 32f)
        // rice, salad, protein
        d.drawOval(Color(0xFFFFF8E8), o(30f, 54f), sz(22f, 14f))
        circle(C.Sage, 60f, 58f, 6f); circle(Color(0xFF7FA36B), 66f, 62f, 5f); circle(C.Berry, 58f, 64f, 3.5f)
        d.drawOval(C.PeachDark.copy(alpha = 0.85f), o(42f, 62f), sz(18f, 9f))
        // cutlery
        line(C.InkSoft, 12f, 40f, 12f, 82f, 3f)
        for (i in -1..1) line(C.InkSoft, 12f + i * 3f, 32f, 12f + i * 3f, 42f, 1.8f)
        line(C.InkSoft, 88f, 32f, 88f, 82f, 3f)
        path(C.InkSoft) { m(88f, 32f); q(94f, 40f, 88f, 54f); close() }
        steam(44f, 50f, phase)
    }

    fun dinner(phase: Float) {
        backdrop(Color(0xFFDDE3EC))
        // crescent moon and stars
        circle(C.Moon, 70f, 26f, 12f)
        circle(Color(0xFFDDE3EC), 76f, 22f, 10f)
        val tw = 0.6f + 0.4f * phase
        circle(C.Sun.copy(alpha = tw), 30f, 20f, 2f); circle(C.Sun.copy(alpha = 1.2f - tw), 44f, 30f, 1.6f); circle(C.Sun.copy(alpha = tw), 86f, 46f, 1.6f)
        plate(50f, 70f, 30f)
        // bowl of soup
        path(C.SkyDark) { m(30f, 60f); q(50f, 92f, 70f, 60f); close() }
        d.drawOval(C.Peach, o(31f, 56f), sz(38f, 8f))
        circle(C.Sage, 44f, 60f, 2f); circle(C.Sage, 54f, 59f, 2f)
        steam(45f, 52f, phase)
    }

    fun snack() {
        backdrop(C.PeachLight)
        plate(50f, 66f, 28f)
        circle(C.Berry, 40f, 58f, 9f); line(C.SageDark, 40f, 49f, 42f, 44f, 2.4f)
        path(C.Sage) { m(42f, 46f); q(50f, 40f, 52f, 48f); close() }
        d.drawOval(C.Sun, o(52f, 56f), sz(18f, 11f))
        circle(Color(0xFFE2B27A), 62f, 66f, 4f); circle(Color(0xFFE2B27A), 52f, 68f, 3.5f)
    }

    fun glass(liquid: Color, phase: Float) {
        backdrop(C.SkyLight)
        val level = 46f + (phase - 0.5f) * 2f
        path(Color.White.copy(alpha = 0.9f)) { m(32f, 24f); l(68f, 24f); l(63f, 84f); l(37f, 84f); close() }
        path(liquid) { m(33.6f, level); q(50f, level - 3f, 66.4f, level); l(63f, 84f); l(37f, 84f); close() }
        line(Color.White, 40f, 32f, 42f, 74f, 2.4f)
        d.drawPath(Path().apply { m(32f, 24f); l(68f, 24f); l(63f, 84f); l(37f, 84f); close() }, C.SkyDark, style = Stroke(2.2f * s))
        circle(Color.White, 56f, 60f, 2.2f); circle(Color.White, 50f, 70f, 1.6f)
    }

    fun cup(drink: Color, phase: Float, tea: Boolean) {
        backdrop(if (tea) C.PeachLight else Color(0xFFEFE3D6))
        d.drawOval(C.Shadow, o(22f, 78f), sz(56f, 8f))
        d.drawOval(Color.White, o(20f, 74f), sz(60f, 10f))
        if (tea) {
            // glass tea cup
            path(Color.White.copy(alpha = 0.85f)) { m(34f, 40f); l(66f, 40f); q(64f, 60f, 60f, 76f); l(40f, 76f); q(36f, 60f, 34f, 40f); close() }
            path(drink) { m(35.5f, 48f); l(64.5f, 48f); q(63f, 62f, 59.5f, 74f); l(40.5f, 74f); q(37f, 62f, 35.5f, 48f); close() }
            rect(C.Sage, 60f, 44f, 16f, 6f, 3f) // mint leaf on the rim
        } else {
            rect(Color.White, 30f, 42f, 36f, 34f, 8f)
            rect(drink, 33f, 44f, 30f, 6f, 3f)
            ring(Color.White, 70f, 58f, 7f, 4f)
            rect(C.Peach, 30f, 58f, 36f, 6f)
        }
        steam(44f, 36f, phase, C.InkSoft.copy(alpha = 0.35f))
    }

    fun bottle(body: Color, label: Color) {
        backdrop(C.SkyLight)
        rect(C.SkyDark, 42f, 14f, 16f, 8f, 3f)
        path(body) { m(42f, 22f); l(58f, 22f); q(60f, 30f, 68f, 36f); l(68f, 84f); q(68f, 88f, 64f, 88f); l(36f, 88f); q(32f, 88f, 32f, 84f); l(32f, 36f); q(40f, 30f, 42f, 22f); close() }
        rect(label, 32f, 50f, 36f, 20f)
        circle(C.Sage, 50f, 60f, 5f)
        d.drawPath(Path().apply { m(42f, 22f); l(58f, 22f); q(60f, 30f, 68f, 36f); l(68f, 84f); q(68f, 88f, 64f, 88f); l(36f, 88f); q(32f, 88f, 32f, 84f); l(32f, 36f); q(40f, 30f, 42f, 22f); close() }, C.SkyDark.copy(alpha = 0.5f), style = Stroke(1.6f * s))
    }

    fun juice() {
        backdrop(C.PeachLight)
        path(Color.White.copy(alpha = 0.9f)) { m(34f, 30f); l(66f, 30f); l(62f, 84f); l(38f, 84f); close() }
        path(C.Juice) { m(35f, 40f); l(65f, 40f); l(62f, 84f); l(38f, 84f); close() }
        line(C.Berry, 58f, 16f, 52f, 60f, 3f)
        circle(C.Sun, 66f, 32f, 9f); circle(Color(0xFFFFE7A3), 66f, 32f, 6f)
        for (i in 0 until 6) {
            val a = Math.toRadians(i * 60.0)
            line(C.Sun, 66f, 32f, 66f + 6f * kotlin.math.cos(a).toFloat(), 32f + 6f * kotlin.math.sin(a).toFloat(), 1f)
        }
    }

    fun can(body: Color, bolt: Boolean = false) {
        backdrop(if (bolt) C.SkyLight else C.PeachLight)
        rect(Color(0xFFC9CED1), 34f, 18f, 32f, 8f, 4f)
        rect(body, 32f, 24f, 36f, 60f, 8f)
        rect(Color(0xFFC9CED1), 34f, 80f, 32f, 6f, 3f)
        if (bolt) {
            path(C.Sun) { m(54f, 34f); l(42f, 56f); l(50f, 56f); l(46f, 74f); l(60f, 50f); l(52f, 50f); close() }
        } else {
            rect(Color.White.copy(alpha = 0.9f), 32f, 46f, 36f, 12f)
            circle(Color.White, 44f, 34f, 2f); circle(Color.White, 56f, 38f, 1.5f); circle(Color.White, 50f, 68f, 2f)
        }
    }

    fun mealEnd(phase: Float) {
        backdrop(C.SageLight)
        plate(46f, 70f, 30f)
        d.drawOval(C.IvoryDeep, o(28f, 64f), sz(36f, 12f))
        // clock
        circle(Color.White, 70f, 34f, 18f)
        ring(C.Sage, 70f, 34f, 18f, 3f)
        line(C.SageDark, 70f, 34f, 70f, 22f, 2.6f)
        d.rotate(90f + phase * 30f, o(70f, 34f)) { line(C.SkyDark, 70f, 34f, 70f, 24f, 2.2f) }
        circle(C.SageDark, 70f, 34f, 2.2f)
        // cutlery resting on plate
        line(C.InkSoft, 26f, 58f, 52f, 74f, 2.6f)
        line(C.InkSoft, 36f, 56f, 60f, 72f, 2.6f)
    }

    fun bell(phase: Float) {
        backdrop(C.SkyLight)
        d.rotate((phase - 0.5f) * 16f, o(50f, 22f)) {
            path(C.Sage) { m(50f, 20f); q(30f, 22f, 30f, 50f); l(26f, 66f); l(74f, 66f); l(70f, 50f); q(70f, 22f, 50f, 20f); close() }
            circle(C.SageDark, 50f, 72f, 6f)
        }
        circle(C.SageDark, 50f, 18f, 4f)
        line(C.Sky, 16f, 40f, 22f, 44f, 2.4f); line(C.Sky, 84f, 40f, 78f, 44f, 2.4f)
    }
}
