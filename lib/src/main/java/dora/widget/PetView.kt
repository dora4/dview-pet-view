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
import kotlin.math.abs
import androidx.core.graphics.withSave
import dora.widget.petview.R
import kotlin.math.sin

class PetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val INTERACTION_COOLDOWN = 3_000L
    }

    private var petBitmap: Bitmap =
        BitmapFactory.decodeResource(resources, R.drawable.ic_dview_pet)
    private var petState: PetState = PetState.NORMAL

    /**
     * 宠物健康值，范围为 0～100。
     */
    private var health = 100f

    /**
     * 宠物死亡时使用的灰度滤镜。
     */
    private val grayFilter = ColorMatrixColorFilter(
        ColorMatrix().apply {
            setSaturation(0f)
        }
    )

    /**
     * 宠物当前状态。
     */
    enum class PetState {
        NORMAL,
        ILL,
        DEAD
    }

    /**
     * 设置宠物健康值，并自动限制在合法范围内。
     *
     * @param value 新的健康值。
     */
    private fun setHealth(value: Float) {
        health = value.coerceIn(0f, 100f)
    }

    /**
     * 根据健康值和死亡状态更新宠物外观状态。
     *
     * @param health 当前健康值。
     * @param dead 是否死亡。
     */
    fun updateState(health: Float, dead: Boolean) {
        setHealth(health)
        petState = when {
            dead -> PetState.DEAD
            health <= 20f -> PetState.ILL
            else -> PetState.NORMAL
        }
        invalidate()
    }

    /**
     * 用于接收宠物互动事件的监听器。
     */
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

    /**
     * 绘制宠物图像的画笔。
     */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 用于自动巡逻动画的主线程 Handler。
     */
    private val handler = Handler(Looper.getMainLooper())

    /** 宠物当前位置坐标及垂直速度。 */
    private var petX = 0f
    private var petY = 0f
    private var velocityY = 0f

    /** 重力加速度和地面 Y 坐标。 */
    private var gravity = 1.2f
    private var groundY = 0f

    /** 是否处于跳跃状态以及当前移动方向。 */
    private var isJumping = false
    private var movingRight = true

    /**
     * 上一次用户成功互动的时间。
     */
    private var lastInteractionTime = 0L

    /**
     * 自动巡逻。
     */
    private var autoMoveRunnable: Runnable? = null

    /**
     * 根据控件尺寸初始化宠物位置与自动巡逻。
     */
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
                    sin(System.currentTimeMillis() / 120.0)
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

    /**
     * 根据当前状态计算巡逻速度。
     */
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

    /**
     * 启动宠物自动巡逻。
     */
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

    /**
     * 更新跳跃物理效果。
     */
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

    /**
     * 判断指定坐标是否点击到了宠物。
     */
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
     */
    private fun performInteraction() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastInteractionTime
        val remainMillis = INTERACTION_COOLDOWN - elapsed
        if (remainMillis > 0L) {
            interactionListener?.onCooldown(remainMillis)
            return
        }
        if (isJumping) {
            return
        }
        lastInteractionTime = now
        jump(notifyInteraction = true)
    }

    /**
     * 使宠物跳跃。
     *
     * @param notifyInteraction 是否通知监听器。
     */
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
        val elapsed = System.currentTimeMillis() - lastInteractionTime
        return (INTERACTION_COOLDOWN - elapsed).coerceAtLeast(0L)
    }

    /**
     * 停止自动巡逻任务，释放资源。
     */
    override fun onDetachedFromWindow() {
        autoMoveRunnable?.let(handler::removeCallbacks)
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}