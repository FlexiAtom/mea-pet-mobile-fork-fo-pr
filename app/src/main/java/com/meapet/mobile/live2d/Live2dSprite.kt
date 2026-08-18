package com.meapet.mobile.live2d

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Simple quad sprite for rendering a texture via GL.
 */
class Live2dSprite(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    private val textureId: Int,
    programId: Int
) {
    private val rect = Rect().apply {
        left = x - width * 0.5f
        right = x + width * 0.5f
        up = y + height * 0.5f
        down = y - height * 0.5f
    }

    private val positionLocation: Int = GLES20.glGetAttribLocation(programId, "position")
    private val uvLocation: Int = GLES20.glGetAttribLocation(programId, "uv")
    private val textureLocation: Int = GLES20.glGetUniformLocation(programId, "texture")
    private val colorLocation: Int = GLES20.glGetUniformLocation(programId, "baseColor")

    /** Whether the sprite shader has valid attribute locations */
    private val valid: Boolean = positionLocation >= 0 && uvLocation >= 0

    private val spriteColor = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
    private var maxWidth = 0
    private var maxHeight = 0

    // 预分配并复用的顶点缓冲（位置 8 float + UV 8 float），避免每帧分配堆外内存。
    // 仅在 GL 线程使用，无需额外同步。
    private val positionVertex = FloatArray(8)
    private val posBuf: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val uvBuf: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun renderImmediate(textureId: Int, uvVertex: FloatArray) {
        if (!valid || maxWidth <= 0 || maxHeight <= 0) return

        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glEnableVertexAttribArray(uvLocation)

        GLES20.glUniform1i(textureLocation, 0)

        // Normalized device coordinates for the quad
        val w2 = maxWidth * 0.5f
        val h2 = maxHeight * 0.5f
        positionVertex[0] = (rect.right - w2) / w2; positionVertex[1] = (rect.up - h2) / h2
        positionVertex[2] = (rect.left - w2) / w2;  positionVertex[3] = (rect.up - h2) / h2
        positionVertex[4] = (rect.left - w2) / w2;  positionVertex[5] = (rect.down - h2) / h2
        positionVertex[6] = (rect.right - w2) / w2; positionVertex[7] = (rect.down - h2) / h2

        posBuf.clear(); posBuf.put(positionVertex); posBuf.position(0)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, posBuf)

        uvBuf.clear(); uvBuf.put(uvVertex); uvBuf.position(0)
        GLES20.glVertexAttribPointer(uvLocation, 2, GLES20.GL_FLOAT, false, 0, uvBuf)

        GLES20.glUniform4f(
            colorLocation,
            spriteColor[0], spriteColor[1], spriteColor[2], spriteColor[3]
        )

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
    }

    fun resize(x: Float, y: Float, width: Float, height: Float) {
        rect.left = x - width * 0.5f
        rect.right = x + width * 0.5f
        rect.up = y + height * 0.5f
        rect.down = y - height * 0.5f
    }

    fun setColor(r: Float, g: Float, b: Float, a: Float) {
        spriteColor[0] = r
        spriteColor[1] = g
        spriteColor[2] = b
        spriteColor[3] = a
    }

    fun setWindowSize(width: Int, height: Int) {
        maxWidth = width
        maxHeight = height
    }

    private class Rect {
        var left = 0f
        var right = 0f
        var up = 0f
        var down = 0f
    }
}
