package com.example.studycompanion.update

import android.content.Context
import java.io.File

object CiaBuilder {
    @Deprecated("CIAは対応する3DSビルドから事前生成して取り込んでください")
    @Suppress("UNUSED_PARAMETER")
    fun buildCia(
        context: Context,
        input3dsx: File,
        outputCia: File,
        titleId: Long = 0x000400000F764200L
    ) {
        throw UnsupportedOperationException(
            "3DSXからインストール可能なCIAを生成できません。ビルドで作成したStudy Gadget CIAを取り込んでください。"
        )
    }
}
