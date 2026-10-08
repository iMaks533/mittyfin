package app.mittyfin.fel

import android.util.Log

/** JNI facade of libfelnative.so (src/main/cpp/fel_jni.cpp): libdovi RPU parsing for the GPU FEL composer. */
object FelNative {
    private const val TAG = "FelNative"

    val isAvailable: Boolean by lazy {
        runCatching { System.loadLibrary("felnative"); nativeProbe() == 1 }
            .onFailure { Log.w(TAG, "libfelnative not loaded: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Composer parameters of one RPU NAL into [out] (size [FelComposerParams.SIZE]; layout in fel_jni.cpp).
     * Returns 1 = filled, 0 = the RPU reuses the previous mapping, < 0 = failure / unsupported syntax (-3).
     */
    fun getComposerParams(sample: ByteArray, offset: Int, len: Int, out: FloatArray): Int {
        if (!isAvailable || len <= 0) return -2
        return runCatching { nativeGetFelComposerParams(sample, offset, len, out) }
            .onFailure { Log.w(TAG, "composer params read failed: ${it.message}") }
            .getOrDefault(-1)
    }

    @JvmStatic private external fun nativeProbe(): Int
    @JvmStatic private external fun nativeGetFelComposerParams(sample: ByteArray, offset: Int, length: Int, out: FloatArray): Int
}
