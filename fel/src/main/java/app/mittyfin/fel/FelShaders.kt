package app.mittyfin.fel

/**
 * GLSL ES 3.00 sources of the GPU FEL composer.
 *
 * Compose pass (one fragment per BL luma pixel, into an RGB10_A2 texture at BL size): BL reshaping (polynomial /
 * MMR), FEL residual (LINEAR_DZ NLQ), then the Dolby Vision colour decode to PQ BT.2020 RGB. Reshaping and colour
 * decode follow libplacebo (pl_shader_dovi_reshape, PL_COLOR_SYSTEM_DOLBYVISION), as does the EL-to-BL siting
 * (co-sited horizontally, centred vertically). The residual is dequantized per EL texel on integer codes (exact
 * dead zone, clamped to vdr_in_max) and only then upsampled, i.e. dequantize-before-upsample as in the Dolby
 * composer, instead of a non-linear dequantization of interpolated EL values. Planes are P010 in R16UI / RG16UI textures and are
 * sampled with texelFetch (integer textures are not filterable), with manual bilinear for the subsampled planes.
 *
 * Present pass: scales the composed picture into the window and runs the dynamic tone map (see FelToneMap): the
 * BT.2390 EETF from this shot's L1 peak to the panel peak (HDR window, output stays PQ) or to SDR white (SDR
 * window, output gamma BT.709).
 *
 * Dynamic indexing is kept to uniform arrays (vectors are indexed with constants only) for older GLSL compilers.
 */
internal object FelShaders {
    const val VERTEX = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    private const val PQ = """
const float PQ_M1 = 2610.0 / 16384.0;
const float PQ_M2 = 2523.0 / 4096.0 * 128.0;
const float PQ_C1 = 3424.0 / 4096.0;
const float PQ_C2 = 2413.0 / 4096.0 * 32.0;
const float PQ_C3 = 2392.0 / 4096.0 * 32.0;
vec3 pqEotf(vec3 e) {
    vec3 p = pow(clamp(e, 0.0, 1.0), vec3(1.0 / PQ_M2));
    return pow(max(p - PQ_C1, 0.0) / (PQ_C2 - PQ_C3 * p), vec3(1.0 / PQ_M1));
}
vec3 pqOetf(vec3 l) {
    vec3 p = pow(clamp(l, 0.0, 1.0), vec3(PQ_M1));
    return pow((PQ_C1 + PQ_C2 * p) / (1.0 + PQ_C3 * p), vec3(PQ_M2));
}
"""

    const val COMPOSE = """#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
layout(location = 0) out vec4 outColor;

uniform usampler2D uBlY;
uniform usampler2D uBlUV;
uniform usampler2D uElY;
uniform usampler2D uElUV;
uniform ivec2 uBlSize;
uniform ivec2 uElSize;
uniform int uElOn;

uniform vec4 uCoeffs[24];
uniform float uPivots[24];
uniform vec2 uLoHi[3];
uniform float uCompOn[3];
uniform vec4 uMmr[96];
uniform vec3 uNlqOffset;
uniform vec3 uNlqSlope;
uniform vec3 uNlqThreshold;
uniform vec3 uNlqMax;
uniform int uDebugView; // GpuFelStatus.DebugView: 0 normal, 1 without EL, 2 EL residual
uniform mat3 uYccToRgb;
uniform vec3 uYccOffset;
uniform mat3 uLmsToRgb;
""" + PQ + """
const float INV_1023 = 1.0 / 1023.0;
const float RESIDUAL_GAIN = 32.0; // GpuFelStatus.RESIDUAL_GAIN_LABEL

float fetchY(usampler2D t, ivec2 p, ivec2 size) {
    p = clamp(p, ivec2(0), size - 1);
    return float(texelFetch(t, p, 0).r >> 6u) * INV_1023;
}
vec2 fetchUV(usampler2D t, ivec2 p, ivec2 size) {
    p = clamp(p, ivec2(0), size - 1);
    return vec2(texelFetch(t, p, 0).rg >> 6u) * INV_1023;
}
// pos in texel-index space (texel centres on integers)
vec2 bilinearUV(usampler2D t, vec2 pos, ivec2 size) {
    vec2 f = floor(pos);
    vec2 w = pos - f;
    ivec2 i = ivec2(f);
    vec2 a = fetchUV(t, i, size);
    vec2 b = fetchUV(t, i + ivec2(1, 0), size);
    vec2 c = fetchUV(t, i + ivec2(0, 1), size);
    vec2 d = fetchUV(t, i + ivec2(1, 1), size);
    return mix(mix(a, b, w.x), mix(c, d, w.x), w.y);
}

// LINEAR_DZ dequantization of one EL code: rr = code - offset; 0 in the dead zone, else
// sign(rr) * min((|rr| - 0.5) * S + T, vdr_in_max) with the -0.5 * S folded into thr.
float nlq(uint code, float off, float slope, float thr, float mx) {
    float rr = float(code >> 6u) - off; // P010: 10-bit code in the top bits; integers, so == 0.0 is exact
    if (rr == 0.0) return 0.0;
    return sign(rr) * min(abs(rr) * slope + thr, mx);
}
float residualY(ivec2 p, ivec2 size) {
    p = clamp(p, ivec2(0), size - 1);
    return nlq(texelFetch(uElY, p, 0).r, uNlqOffset.x, uNlqSlope.x, uNlqThreshold.x, uNlqMax.x);
}
vec2 residualUV(ivec2 p, ivec2 size) {
    p = clamp(p, ivec2(0), size - 1);
    uvec2 c = texelFetch(uElUV, p, 0).rg;
    return vec2(nlq(c.x, uNlqOffset.y, uNlqSlope.y, uNlqThreshold.y, uNlqMax.y),
                nlq(c.y, uNlqOffset.z, uNlqSlope.z, uNlqThreshold.z, uNlqMax.z));
}
float bilinearResidualY(vec2 pos, ivec2 size) {
    vec2 f = floor(pos);
    vec2 w = pos - f;
    ivec2 i = ivec2(f);
    return mix(mix(residualY(i, size), residualY(i + ivec2(1, 0), size), w.x),
               mix(residualY(i + ivec2(0, 1), size), residualY(i + ivec2(1, 1), size), w.x), w.y);
}
vec2 bilinearResidualUV(vec2 pos, ivec2 size) {
    vec2 f = floor(pos);
    vec2 w = pos - f;
    ivec2 i = ivec2(f);
    return mix(mix(residualUV(i, size), residualUV(i + ivec2(1, 0), size), w.x),
               mix(residualUV(i + ivec2(0, 1), size), residualUV(i + ivec2(1, 1), size), w.x), w.y);
}

float reshape(int c, float s, vec3 sig) {
    int piece = 0;
    for (int i = 0; i < 7; i++) {
        if (s >= uPivots[c * 8 + i]) piece = i + 1;
    }
    vec4 co = uCoeffs[c * 8 + piece];
    float r;
    if (co.w == 0.0) {
        r = (co.z * s + co.y) * s + co.x;
    } else {
        int base = (max(c, 1) - 1) * 48 + piece * 6;
        vec4 sigX = vec4(sig.x * sig.y, sig.x * sig.z, sig.y * sig.z, sig.x * sig.y * sig.z);
        r = co.x + dot(uMmr[base].xyz, sig) + dot(uMmr[base + 1], sigX);
        if (co.w >= 2.0) {
            vec3 sig2 = sig * sig;
            vec4 sigX2 = sigX * sigX;
            r += dot(uMmr[base + 2].xyz, sig2) + dot(uMmr[base + 3], sigX2);
            if (co.w >= 3.0) {
                r += dot(uMmr[base + 4].xyz, sig2 * sig) + dot(uMmr[base + 5], sigX2 * sigX);
            }
        }
    }
    return clamp(r, uLoHi[c].x, uLoHi[c].y);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy); // row 0 = top row of the picture (flipped back in the present pass)
    vec2 pf = vec2(p);
    ivec2 blChromaSize = uBlSize / 2;
    vec3 ycc;
    ycc.x = fetchY(uBlY, p, uBlSize);
    // 4:2:0 chroma, siting type 0 (co-sited horizontally, centred vertically)
    vec2 uv = bilinearUV(uBlUV, vec2(pf.x * 0.5, pf.y * 0.5 - 0.25), blChromaSize);
    ycc.y = uv.x;
    ycc.z = uv.y;

    vec3 sig = clamp(ycc, 0.0, 1.0);
    vec3 vdr = sig;
    if (uCompOn[0] > 0.5) vdr.x = reshape(0, sig.x, sig);
    if (uCompOn[1] > 0.5) vdr.y = reshape(1, sig.y, sig);
    if (uCompOn[2] > 0.5) vdr.z = reshape(2, sig.z, sig);

    vec3 residual = vec3(0.0);
    if (uElOn != 0) {
        vec2 ratio = vec2(uElSize) / vec2(uBlSize);
        vec2 ep = vec2(pf.x * ratio.x, (pf.y + 0.5) * ratio.y - 0.5);
        residual.x = bilinearResidualY(ep, uElSize);
        residual.yz = bilinearResidualUV(vec2(ep.x * 0.5, ep.y * 0.5 - 0.25), uElSize / 2);
    }
    if (uDebugView == 2) {
        // Debug: luma residual around mid grey (flat grey = no EL applied), written as-is to the PQ target.
        outColor = vec4(vec3(clamp(0.5 + residual.x * RESIDUAL_GAIN, 0.0, 1.0)), 1.0);
        return;
    }
    if (uDebugView != 1) vdr += residual;
    vdr = clamp(vdr, 0.0, 1.0);

    vec3 rgbPq = uYccToRgb * (vdr - uYccOffset);
    vec3 lin = uLmsToRgb * pqEotf(rgbPq);
    outColor = vec4(pqOetf(max(lin, 0.0)), 1.0);
}
"""

    const val PRESENT = """#version 300 es
precision highp float;
precision highp sampler2D; // default is lowp: would quantise the 10-bit PQ intermediate
in vec2 vUv;
layout(location = 0) out vec4 outColor;
uniform sampler2D uComposed;
uniform int uSdr;
uniform int uToneMap;
uniform float uSrcPeakNits;
uniform float uDstPeakNits;
""" + PQ + """
const mat3 BT2020_TO_709 = mat3(
     1.6605, -0.1246, -0.0182,
    -0.5876,  1.1329, -0.1006,
    -0.0728, -0.0083,  1.1187);

// BT.2390 EETF on max(RGB) in the PQ domain (black level 0): below the knee ks nothing changes; above it the range
// up to the source peak is rolled off smoothly so the source peak lands exactly on the target peak. Scaling RGB by
// one factor keeps the hue. Pixels above the source peak (L1 is per shot, not per pixel) clip at the target peak.
vec3 eetf(vec3 nits, float srcPeak, float dstPeak) {
    float m = max(max(nits.r, nits.g), nits.b);
    if (m <= 0.0) return nits;
    float srcPq = pqOetf(vec3(srcPeak / 10000.0)).x;
    float dstPq = pqOetf(vec3(dstPeak / 10000.0)).x;
    float e1 = clamp(pqOetf(vec3(m / 10000.0)).x / srcPq, 0.0, 1.0);
    float maxLum = dstPq / srcPq;
    float ks = max(1.5 * maxLum - 0.5, 0.0);
    float e2 = e1;
    if (e1 > ks) {
        float t = (e1 - ks) / (1.0 - ks);
        float t2 = t * t;
        float t3 = t2 * t;
        e2 = (2.0 * t3 - 3.0 * t2 + 1.0) * ks + (t3 - 2.0 * t2 + t) * (1.0 - ks) + (-2.0 * t3 + 3.0 * t2) * maxLum;
    }
    float mapped = pqEotf(vec3(e2 * srcPq)).x * 10000.0;
    return nits * (mapped / m);
}

void main() {
    vec3 pq = texture(uComposed, vec2(vUv.x, 1.0 - vUv.y)).rgb;
    if (uSdr == 0 && uToneMap == 0) { // HDR window, shot fits the panel: untouched
        outColor = vec4(pq, 1.0);
        return;
    }
    vec3 nits = pqEotf(pq) * 10000.0;
    if (uToneMap != 0) nits = eetf(nits, uSrcPeakNits, uDstPeakNits);
    if (uSdr == 0) {
        outColor = vec4(pqOetf(nits / 10000.0), 1.0);
        return;
    }
    // SDR window: the target peak is SDR white.
    vec3 rgb = clamp(BT2020_TO_709 * nits / uDstPeakNits, 0.0, 1.0);
    outColor = vec4(pow(rgb, vec3(1.0 / 2.2)), 1.0);
}
"""
}
