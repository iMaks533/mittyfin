// Mittyfin native part: libdovi (quietvoid/dovi_tool, MIT) RPU parsing for the GPU FEL composer.
// The composer-parameter function and the RPU parse helpers come from the NuvioTV-Fork dovi_bridge.cpp.
#include <jni.h>
#include <android/log.h>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include <libdovi/rpu_parser.h>

static inline bool dovi_has_error(const DoviRpuOpaque* rpu, std::string* out_error) {
    if (rpu == nullptr) {
        if (out_error != nullptr) {
            *out_error = "null-rpu";
        }
        return true;
    }

    const char* error = dovi_rpu_get_error(rpu);
    if (error == nullptr || error[0] == '\0') {
        return false;
    }

    if (out_error != nullptr) {
        *out_error = error;
    }
    return true;
}

static inline DoviRpuOpaque* dovi_parse_any_rpu(const std::vector<uint8_t>& payload, std::string* out_error) {
    if (payload.empty()) {
        if (out_error != nullptr) {
            *out_error = "empty-input";
        }
        return nullptr;
    }

    std::string parse_error;
    DoviRpuOpaque* parsed = dovi_parse_unspec62_nalu(payload.data(), payload.size());
    if (parsed != nullptr && !dovi_has_error(parsed, &parse_error)) {
        return parsed;
    }
    if (parsed != nullptr) {
        dovi_rpu_free(parsed);
    }

    parsed = dovi_parse_rpu(payload.data(), payload.size());
    if (parsed != nullptr && !dovi_has_error(parsed, &parse_error)) {
        return parsed;
    }
    if (parsed != nullptr) {
        dovi_rpu_free(parsed);
    }

    if (out_error != nullptr) {
        *out_error = parse_error.empty() ? "failed-parse-rpu" : parse_error;
    }
    return nullptr;
}

// --- GPU FEL composer parameters ---
//
// Fills float[kFelParamsSize] with what the GPU composer shader needs for one RPU, normalized the way
// libplacebo's pl_map_dovi_metadata does it (src/include/libplacebo/utils/libav_internal.h):
//   pivots / (2^bl_bit_depth - 1), coefficients / 2^coefficient_log2_denom. NLQ stays in EL code units (the shader
//   dequantizes integer EL codes, so the dead zone rr == 0 is exact): offset as the code, slope S / 2^denom,
//   threshold (T - S/2) / 2^denom (the spec's (|rr| - 0.5) * S rounding folded in), max = vdr_in_max / 2^denom.
// Layout (keep in step with FelComposerParams.kt):
//   [0..2] BL / EL / VDR bit depth, [3] NLQ active, [4] EL spatial resampling, [5] colour matrices present,
//   [6..14] ycc_to_rgb (row-major), [15..17] ycc_to_rgb offset, [18..26] rgb_to_lms (row-major),
//   [27..29] NLQ offset, [30..32] NLQ slope, [33..35] NLQ threshold, [36..37] source min/max PQ (12-bit code),
//   [38] EL type (2 FEL, 1 MEL, 0 none), [39..41] NLQ residual limit (vdr_in_max),
//   [42..44] L1 min / max / avg PQ (12-bit code, -1 absent), [45..47] reserved,
//   component c at 48 + c * 220: [0] num_pivots, [1] method (0 polynomial, 1 MMR), [2..10] pivots,
//   [11..18] order per piece, [19..42] polynomial coefficients [piece][3], [43..50] MMR constant [piece],
//   [51..218] MMR coefficients [piece][order][7].
// Returns 1 = filled, 0 = the RPU reuses the previous mapping (keep the last parameters), -1 = parse failure,
// -2 = stub build / bad arguments, -3 = syntax the composer does not handle (float coefficients, MU_LAW NLQ, ...).
static constexpr int kFelParamsSize = 48 + 3 * 220;
static constexpr int kFelCompBase = 48;
static constexpr int kFelCompStride = 220;

extern "C" JNIEXPORT jint JNICALL
Java_app_mittyfin_fel_FelNative_nativeGetFelComposerParams(
        JNIEnv* env,
        jclass /* clazz */,
        jbyteArray sample,
        jint offset,
        jint length,
        jfloatArray out) try {
    if (sample == nullptr || out == nullptr || offset < 0 || length <= 0 || length > 65536) return -2;
    if (offset > env->GetArrayLength(sample) - length) return -2;
    if (env->GetArrayLength(out) < kFelParamsSize) return -2;

    std::vector<uint8_t> input(static_cast<size_t>(length));
    env->GetByteArrayRegion(sample, offset, length, reinterpret_cast<jbyte*>(input.data()));
    if (env->ExceptionCheck()) return -2;

    std::string parse_error;
    DoviRpuOpaque* rpu = dovi_parse_any_rpu(input, &parse_error);
    if (rpu == nullptr) return -1;

    const DoviRpuDataHeader* header = dovi_rpu_get_header(rpu);
    if (header == nullptr) {
        dovi_rpu_free(rpu);
        return -1;
    }
    if (header->use_prev_vdr_rpu_flag) {
        dovi_rpu_free_header(header);
        dovi_rpu_free(rpu);
        return 0;
    }
    if (header->coefficient_data_type != 0) {
        dovi_rpu_free_header(header);
        dovi_rpu_free(rpu);
        return -3;
    }

    std::vector<float> v(kFelParamsSize, 0.0f);
    const bool hasSeqInfo = header->vdr_seq_info_present_flag && (header->rpu_format & 0x700) == 0;
    const int blDepth = hasSeqInfo ? static_cast<int>(header->bl_bit_depth_minus8 + 8) : 10;
    const int elDepth = hasSeqInfo ? static_cast<int>(header->el_bit_depth_minus8 + 8) : 10;
    const int vdrDepth = hasSeqInfo ? static_cast<int>(header->vdr_bit_depth_minus8 + 8) : 12;
    const int denomBits = static_cast<int>(header->coefficient_log2_denom);
    const double coefScale = 1.0 / static_cast<double>(1ULL << denomBits);
    const double blScale = 1.0 / static_cast<double>((1 << blDepth) - 1);
    v[0] = static_cast<float>(blDepth);
    v[1] = static_cast<float>(elDepth);
    v[2] = static_cast<float>(vdrDepth);
    v[4] = header->el_spatial_resampling_filter_flag ? 1.0f : 0.0f;
    v[36] = -1.0f;
    v[37] = -1.0f;
    v[42] = -1.0f;
    v[43] = -1.0f;
    v[44] = -1.0f;
    if (header->el_type != nullptr) {
        if (std::strcmp(header->el_type, "FEL") == 0) v[38] = 2.0f;
        else if (std::strcmp(header->el_type, "MEL") == 0) v[38] = 1.0f;
    }
    const bool disableResidual = header->disable_residual_flag;
    dovi_rpu_free_header(header);

    const DoviRpuDataMapping* mapping = dovi_rpu_get_data_mapping(rpu);
    if (mapping == nullptr) {
        dovi_rpu_free(rpu);
        return 0;
    }

    // Fixed-point value as coded with coefficient_data_type 0: int part + frac / 2^denom.
    auto fixedCoef = [&](int64_t ipart, uint64_t fpart) -> float {
        return static_cast<float>(static_cast<double>(ipart) + static_cast<double>(fpart) * coefScale);
    };

    int result = 1;
    for (int c = 0; c < 3 && result == 1; c++) {
        const DoviReshapingCurve& curve = mapping->curves[c];
        float* comp = v.data() + kFelCompBase + c * kFelCompStride;
        const size_t numPivots = static_cast<size_t>(curve.num_pivots_minus2 + 2);
        if (numPivots < 2 || numPivots > 9 || curve.pivots.len < numPivots) {
            result = -3;
            break;
        }
        comp[0] = static_cast<float>(numPivots);
        comp[1] = static_cast<float>(curve.mapping_idc);
        // libdovi keeps the coded pred_pivot_value deltas; the composer works on absolute pivots.
        uint32_t pivot = 0;
        for (size_t i = 0; i < numPivots; i++) {
            pivot += curve.pivots.data[i];
            comp[2 + i] = static_cast<float>(blScale * static_cast<double>(pivot));
        }
        const size_t numPieces = numPivots - 1;
        if (curve.mapping_idc == 0) {
            const DoviPolynomialCurve* poly = curve.polynomial;
            if (poly == nullptr || poly->poly_order_minus1.len < numPieces ||
                poly->poly_coef_int.len < numPieces || poly->poly_coef.len < numPieces) {
                result = -3;
                break;
            }
            for (size_t p = 0; p < numPieces && result == 1; p++) {
                const int order = static_cast<int>(poly->poly_order_minus1.data[p]) + 1;
                if (order < 1 || order > 2) { result = -3; break; }
                if (poly->linear_interp_flag.len > p && poly->linear_interp_flag.data[p]) { result = -3; break; }
                comp[11 + p] = static_cast<float>(order);
                const DoviI64Data* ints = poly->poly_coef_int.list[p];
                const DoviU64Data* fracs = poly->poly_coef.list[p];
                if (ints == nullptr || fracs == nullptr || ints->len < static_cast<size_t>(order + 1) ||
                    fracs->len < static_cast<size_t>(order + 1)) { result = -3; break; }
                for (int k = 0; k <= order; k++) {
                    comp[19 + p * 3 + k] = fixedCoef(ints->data[k], fracs->data[k]);
                }
            }
        } else if (curve.mapping_idc == 1) {
            const DoviMMRCurve* mmr = curve.mmr;
            // MMR is chroma-only by syntax; luma MMR would need the cross-component input the shader skips.
            if (c == 0 || mmr == nullptr || mmr->mmr_order_minus1.len < numPieces ||
                mmr->mmr_constant_int.len < numPieces || mmr->mmr_constant.len < numPieces ||
                mmr->mmr_coef_int.len < numPieces || mmr->mmr_coef.len < numPieces) {
                result = -3;
                break;
            }
            for (size_t p = 0; p < numPieces && result == 1; p++) {
                const int order = static_cast<int>(mmr->mmr_order_minus1.data[p]) + 1;
                if (order < 1 || order > 3) { result = -3; break; }
                comp[11 + p] = static_cast<float>(order);
                comp[43 + p] = fixedCoef(mmr->mmr_constant_int.data[p], mmr->mmr_constant.data[p]);
                const DoviI64Data2D* ints = mmr->mmr_coef_int.list[p];
                const DoviU64Data2D* fracs = mmr->mmr_coef.list[p];
                if (ints == nullptr || fracs == nullptr || ints->len < static_cast<size_t>(order) ||
                    fracs->len < static_cast<size_t>(order)) { result = -3; break; }
                for (int o = 0; o < order; o++) {
                    const DoviI64Data* oi = ints->list[o];
                    const DoviU64Data* of = fracs->list[o];
                    if (oi == nullptr || of == nullptr || oi->len < 7 || of->len < 7) { result = -3; break; }
                    for (int k = 0; k < 7; k++) {
                        comp[51 + p * 21 + o * 7 + k] = fixedCoef(oi->data[k], of->data[k]);
                    }
                }
            }
        } else {
            result = -3;
        }
    }

    if (result == 1 && !disableResidual && mapping->nlq_method_idc == 0 && mapping->nlq != nullptr) {
        if (mapping->nlq_num_pivots_minus2 > 0) {
            result = -3;
        } else {
            const DoviRpuDataNlq& nlq = *mapping->nlq;
            const uint64_t one = 1ULL << denomBits;
            bool trivial = true;
            for (int c = 0; c < 3; c++) {
                const uint64_t vdrInMax = (nlq.vdr_in_max_int[c] << denomBits) | nlq.vdr_in_max[c];
                const uint64_t slope = (nlq.linear_deadzone_slope_int[c] << denomBits) | nlq.linear_deadzone_slope[c];
                const uint64_t threshold =
                    (nlq.linear_deadzone_threshold_int[c] << denomBits) | nlq.linear_deadzone_threshold[c];
                if (nlq.nlq_offset[c] != 0 || vdrInMax != one || slope != 0 || threshold != 0) trivial = false;
                const double s = static_cast<double>(slope);
                const double t = static_cast<double>(threshold);
                v[27 + c] = static_cast<float>(nlq.nlq_offset[c]);
                v[30 + c] = static_cast<float>(coefScale * s);
                v[33 + c] = static_cast<float>(coefScale * (t - 0.5 * s));
                v[39 + c] = static_cast<float>(coefScale * static_cast<double>(vdrInMax));
            }
            v[3] = trivial ? 0.0f : 1.0f;
        }
    } else if (result == 1 && !disableResidual && mapping->nlq_method_idc > 0) {
        result = -3; // NLQ_MU_LAW: no known content, not implemented
    }
    dovi_rpu_free_data_mapping(mapping);

    if (result == 1) {
        const DoviVdrDmData* dm = dovi_rpu_get_vdr_dm_data(rpu);
        if (dm != nullptr) {
            const int16_t ycc[9] = {dm->ycc_to_rgb_coef0, dm->ycc_to_rgb_coef1, dm->ycc_to_rgb_coef2,
                                    dm->ycc_to_rgb_coef3, dm->ycc_to_rgb_coef4, dm->ycc_to_rgb_coef5,
                                    dm->ycc_to_rgb_coef6, dm->ycc_to_rgb_coef7, dm->ycc_to_rgb_coef8};
            const uint32_t yccOffset[3] = {dm->ycc_to_rgb_offset0, dm->ycc_to_rgb_offset1, dm->ycc_to_rgb_offset2};
            const int16_t lms[9] = {dm->rgb_to_lms_coef0, dm->rgb_to_lms_coef1, dm->rgb_to_lms_coef2,
                                    dm->rgb_to_lms_coef3, dm->rgb_to_lms_coef4, dm->rgb_to_lms_coef5,
                                    dm->rgb_to_lms_coef6, dm->rgb_to_lms_coef7, dm->rgb_to_lms_coef8};
            // Same fixed-point scales as FFmpeg's dovi_rpudec.c (profile 7: offsets in 1/2^28).
            for (int i = 0; i < 9; i++) {
                v[6 + i] = static_cast<float>(ycc[i]) / 8192.0f;
                v[18 + i] = static_cast<float>(lms[i]) / 16384.0f;
            }
            for (int i = 0; i < 3; i++) {
                v[15 + i] = static_cast<float>(static_cast<double>(yccOffset[i]) / static_cast<double>(1u << 28));
            }
            v[5] = 1.0f;
            v[36] = static_cast<float>(dm->source_min_pq);
            v[37] = static_cast<float>(dm->source_max_pq);
            // L1: per-shot min / max / avg of the content (PQ), the input of dynamic tone mapping.
            if (dm->dm_data.level1 != nullptr) {
                v[42] = static_cast<float>(dm->dm_data.level1->min_pq);
                v[43] = static_cast<float>(dm->dm_data.level1->max_pq);
                v[44] = static_cast<float>(dm->dm_data.level1->avg_pq);
            }
            dovi_rpu_free_vdr_dm_data(dm);
        }
    }
    dovi_rpu_free(rpu);

    if (result == 1) env->SetFloatArrayRegion(out, 0, kFelParamsSize, v.data());
    return result;
} catch (...) { return -1; }

extern "C" JNIEXPORT jint JNICALL
Java_app_mittyfin_fel_FelNative_nativeProbe(JNIEnv* /* env */, jclass /* clazz */) {
    return 1;
}
