package com.meapet.mobile.live2d

import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils

/**
 * Manages OpenGL textures loaded from PNG files in assets.
 */
class Live2dTextureManager {
    data class TextureInfo(
        val id: Int,
        val width: Int,
        val height: Int,
        val filePath: String
    )

    fun createTextureFromPngFile(filePath: String): TextureInfo? {
        // Return cached texture if already loaded
        textures.find { it.filePath == filePath }?.let { return it }

        val activity = Live2dDelegate.getInstance().activity ?: return null
        val assetManager = activity.assets
        val stream = assetManager.open(filePath)

        val options = BitmapFactory.Options().apply {
            inPremultiplied = Live2dDefine.PREMULTIPLIED_ALPHA_ENABLE
        }
        val bitmap = BitmapFactory.decodeStream(stream, null, options)
            ?: error("Failed to decode bitmap from $filePath")
        stream.close()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val info = TextureInfo(
            id = textureIds[0],
            width = bitmap.width,
            height = bitmap.height,
            filePath = filePath
        )
        textures.add(info)

        bitmap.recycle()
        return info
    }

    /**
     * 释放全部纹理的 GL 资源并清空缓存。
     *
     * 必须在 GL 线程调用（有有效 EGL 上下文时）。旧实现只 `clear()` 列表而不调
     * `glDeleteTextures`：GL 上下文销毁时资源会随上下文释放，但若在存活上下文中
     * 反复切模型/重建，列表里的纹理 id 会累积泄漏 GPU 内存。
     */
    fun releaseInvalidTextures() {
        if (textures.isNotEmpty()) {
            val ids = IntArray(textures.size) { textures[it].id }
            GLES20.glDeleteTextures(ids.size, ids, 0)
        }
        textures.clear()
    }

    private val textures = mutableListOf<TextureInfo>()
}
