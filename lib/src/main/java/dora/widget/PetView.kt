package dora.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dora.widget.petview.R
import kotlin.math.abs
import androidx.core.graphics.withSave

class PetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val INTERACTION_COOLDOWN = 3_000L
        private const val MAX_INTERACTION_COUNT = 2
    }
    /**
     * 互动时间窗口
     */
    private var interactionWindowStart = 0L

    /**
     * 当前窗口内互动次数
     */
    private var interactionCount = 0

    private var petBitmap: Bitmap =
        BitmapFactory.decodeResource(resources, R.drawable.profile_katong)
    private var petState: PetState = PetState.NORMAL
    private var health = 100f

    private val grayFilter = ColorMatrixColorFilter(
        ColorMatrix().apply {
            setSaturation(0f)
        }
    )

    enum class PetState {
        NORMAL,
        ILL,
        DEAD
    }

    private fun setHealth(value: Float) {
        health = value.coerceIn(0f, 100f)
    }

    fun updateState(health: Float, dead: Boolean) {
        setHealth(health)
        petState = when {
            dead -> PetState.DEAD
            health <= 20f -> PetState.ILL
            else -> PetState.NORMAL
        }
        invalidate()
    }

    interface InteractionListener {

        /**
         * 用户成功点击宠物并触发跳跃。
         */
        fun onJump()

        /**
         * 用户点击宠物，但仍在互动冷却中。
         *
         * @param remainMillis 剩余冷却时间
         */
        fun onCooldown(remainMillis: Long) {}
    }

    private var interactionListener: InteractionListener? = null

    fun setInteractionListener(listener: InteractionListener) {
        interactionListener = listener
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())

    private var petX = 0f
    private var petY = 0f
    private var velocityY = 0f

    private var gravity = 1.2f
    private var groundY = 0f

    private var isJumping = false
    private var movingRight = true

    /**
     * 自动巡逻。
     */
    private var autoMoveRunnable: Runnable? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        petX = w / 2f - petBitmap.width / 2f
        groundY = h - petBitmap.height.toFloat()
        petY = groundY

        if (autoMoveRunnable == null) {
            startAutoMove()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.colorFilter = when (petState) {
            PetState.DEAD -> grayFilter
            else -> null
        }
        paint.alpha = when (petState) {
            PetState.DEAD -> 120
            else -> 255
        }
        canvas.withSave {
            if (petState == PetState.ILL) {
                val angle =
                    kotlin.math.sin(System.currentTimeMillis() / 120.0)
                        .toFloat() * 3f
                rotate(
                    angle,
                    petX + petBitmap.width / 2f,
                    petY + petBitmap.height / 2f
                )
            }
            drawBitmap(petBitmap, petX, petY, paint)
        }
        updatePhysics()
        invalidate()
    }

    private fun getMoveSpeed(): Float {
        return when {
            // 濒死状态不再移动
            petState == PetState.DEAD -> 0f
            health <= 0f -> 0f
            // 生病降低移动速度
            health <= 20f -> 3f
            else -> 6f
        }
    }

    private fun startAutoMove() {
        autoMoveRunnable = object : Runnable {
            override fun run() {
                if (!isJumping) {
                    val speed = getMoveSpeed()
                    if (speed > 0f) {
                        petX += if (movingRight) speed else -speed
                        if (petX <= 0f) {
                            petX = 0f
                            movingRight = true
                        } else if (petX + petBitmap.width >= width) {
                            petX = width - petBitmap.width.toFloat()
                            movingRight = false
                        }
                    }
                }
                handler.postDelayed(this, 16L)
            }
        }
        handler.post(autoMoveRunnable!!)
    }

    private fun updatePhysics() {
        if (!isJumping) return
        velocityY += gravity
        petY += velocityY
        if (petY >= groundY) {
            petY = groundY
            velocityY = 0f
            isJumping = false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) {
            return true
        }
        if (!isTouchPet(event.x, event.y)) {
            return true
        }
        performInteraction()
        return true
    }

    private fun isTouchPet(x: Float, y: Float): Boolean {
        val centerX = petX + petBitmap.width / 2f
        val centerY = petY + petBitmap.height / 2f

        val dx = abs(x - centerX)
        val dy = abs(y - centerY)

        return dx < petBitmap.width / 2f &&
                dy < petBitmap.height / 2f
    }

    /**
     * 用户点击宠物的互动逻辑。
     *
     * 3秒内最多跳2次
     */
    private fun performInteraction() {
        val now = System.currentTimeMillis()
        // 超过3秒，重新计算
        if (now - interactionWindowStart >= INTERACTION_COOLDOWN) {
            interactionWindowStart = now
            interactionCount = 0
        }
        // 达到次数限制
        if (interactionCount >= MAX_INTERACTION_COUNT) {
            val remainMillis = INTERACTION_COOLDOWN - (now - interactionWindowStart)
            interactionListener?.onCooldown(remainMillis)
            return
        }
        if (isJumping) {
            return
        }
        interactionCount++
        jump(notifyInteraction = true)
    }

    private fun jump(notifyInteraction: Boolean = false) {
        if (isJumping) return
        isJumping = true
        velocityY = -20f
        if (notifyInteraction) {
            interactionListener?.onJump()
        }
    }

    /**
     * 获取当前互动剩余冷却时间。
     */
    fun getInteractionCooldownRemain(): Long {
        if (interactionCount < MAX_INTERACTION_COUNT) {
            return 0L
        }
        val elapsed = System.currentTimeMillis() - interactionWindowStart
        return (INTERACTION_COOLDOWN - elapsed).coerceAtLeast(0L)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        autoMoveRunnable?.let(handler::removeCallbacks)
        handler.removeCallbacksAndMessages(null)
    }
}
