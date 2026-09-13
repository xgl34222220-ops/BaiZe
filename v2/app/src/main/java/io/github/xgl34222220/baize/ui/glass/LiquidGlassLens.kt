// SPDX-License-Identifier: Apache-2.0
package io.github.xgl34222220.baize.ui.glass

// Adapted from LuoShu@fe4df5f, based on compose-miuix-ui/miuix's LiquidGlass Lens
// and Kyant0/AndroidLiquidGlass (Apache-2.0). See docs/UI-LUOSHU-DOCK.md.
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

internal fun BackdropEffectScope.liquidGlassLens(
    refractionHeight: Float, refractionAmount: Float,
    depthEffect: Boolean = false, chromaticAberration: Float = 0f,
) {
    if (!isRuntimeShaderSupported() || refractionHeight <= 0f || refractionAmount <= 0f) return
    if (padding < refractionAmount) padding = refractionAmount
    val radii = roundedRectCornerRadii() ?: return
    val scale = downscaleFactor.coerceAtLeast(1).toFloat()
    runtimeShaderEffect(
        key = "BaiZeLuoShuLiquidLens",
        shaderString = REFRACTION_SHADER,
        uniformShaderName = "content",
    ) {
        setFloatUniform("size", size.width / scale, size.height / scale)
        setFloatUniform("offset", -padding / scale, -padding / scale)
        setFloatUniform("cornerRadii", FloatArray(4) { radii[it] / scale })
        setFloatUniform("refractionHeight", refractionHeight / scale)
        setFloatUniform("refractionAmount", -refractionAmount / scale)
        setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
        setFloatUniform("chromaticAberration", chromaticAberration)
    }
}

private fun BackdropEffectScope.roundedRectCornerRadii(): FloatArray? {
    val s = shape as? CornerBasedShape ?: return null
    val maxRadius = size.minDimension / 2f
    val ltr = layoutDirection == LayoutDirection.Ltr
    return floatArrayOf(
        (if (ltr) s.topStart else s.topEnd).toPx(size, this),
        (if (ltr) s.topEnd else s.topStart).toPx(size, this),
        (if (ltr) s.bottomEnd else s.bottomStart).toPx(size, this),
        (if (ltr) s.bottomStart else s.bottomEnd).toPx(size, this),
    ).map { it.coerceAtMost(maxRadius) }.toFloatArray()
}

// Same dispersion sampling and optical mapping as LuoShu. centeredCoord selects
// asymmetric corner radii correctly in non-floating/RTL layouts.
private const val REFRACTION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}
float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}
float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}
float circleMap(float x) { return 1.0 - sqrt(max(0.0, 1.0 - x * x)); }
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) return content.eval(coord);
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    float2 refractedCoord = coord + d * grad;
    if (chromaticAberration <= 0.0) return content.eval(refractedCoord);
    float dispersion = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 split = d * grad * dispersion;
    half4 color = half4(0.0);
    half4 red = content.eval(refractedCoord + split);
    color.r += red.r / 3.5; color.a += red.a / 7.0;
    half4 orange = content.eval(refractedCoord + split * (2.0 / 3.0));
    color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;
    half4 yellow = content.eval(refractedCoord + split * (1.0 / 3.0));
    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;
    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5; color.a += green.a / 7.0;
    half4 cyan = content.eval(refractedCoord - split * (1.0 / 3.0));
    color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;
    half4 blue = content.eval(refractedCoord - split * (2.0 / 3.0));
    color.b += blue.b / 3.0; color.a += blue.a / 7.0;
    half4 purple = content.eval(refractedCoord - split);
    color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;
    return color;
}
"""
