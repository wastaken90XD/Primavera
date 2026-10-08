package org.wastaken.kotatsu.api21.booru.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.booru.media.ui.BooruPlayerActivity

/**
 * NewPipe-popup-style floating player: a small draggable video card added
 * straight to the WindowManager from [BooruMediaService] so it floats over
 * every screen (incl. other apps) while the foreground service lives.
 *
 * Window type: TYPE_PHONE on API 21-25, TYPE_APPLICATION_OVERLAY on 26+
 * (requires the SYSTEM_ALERT_WINDOW grant, checked on 23+ via canDrawOverlays;
 * 21/22 need no runtime prompt). Size/position come from settings presets.
 */
@SuppressLint("ClickableViewAccessibility")
class FloatingPlayerController(
	private val context: Context,
	private val service: BooruMediaService,
) {

	private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
	private var rootView: View? = null
	private var textureView: TextureView? = null
	private var gestureDetector: GestureDetector? = null
	private var dragging = false

	val isShowing: Boolean
		get() = rootView != null

	fun canShowOverlay(): Boolean =
		Build.VERSION.SDK_INT < Build.VERSION_CODES.M || AndroidSettings.canDrawOverlays(context)

	fun overlaySettingsIntent(): android.content.Intent = android.content.Intent(
		AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
		android.net.Uri.parse("package:${context.packageName}"),
	)

	fun show() {
		if (rootView != null) return
		if (!canShowOverlay()) return
		// the Service context carries no theme on API 21-22 (MaterialCardView
		// crashes ThemeEnforcement there): inflate through the app theme
		// wrapper, exactly like NewPipe's popup view does
		val themedContext = android.view.ContextThemeWrapper(context, R.style.Theme_Kotatsu)
		val layout = runCatching {
			LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_player, null)
		}.getOrElse { e ->
			Toast.makeText(context, context.getString(R.string.media_floating_error, e.message), Toast.LENGTH_LONG).show()
			return
		}
		val params = buildLayoutParams()
		val texture = layout.findViewById<TextureView>(R.id.floatTexture)
		val buttonPlay = layout.findViewById<ImageButton>(R.id.floatButtonPlay)
		val buttonClose = layout.findViewById<ImageButton>(R.id.floatButtonClose)
		val buttonExpand = layout.findViewById<ImageButton>(R.id.floatButtonExpand)

		texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
			override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
				service.bindSurface(Surface(surface), width, height)
				service.bindSurfaceTexture(surface, width, height)
			}

			override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
				service.bindSurfaceTexture(surface, width, height)
			}

			override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
				surface.release()
				service.bindSurface(null)
				service.bindSurfaceTexture(null)
				return true
			}

			override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
		}
		val isLocked = service.settings.isMediaFloatingLock
		buttonPlay.visibility = if (isLocked) View.GONE else View.VISIBLE
		buttonClose.visibility = if (isLocked) View.GONE else View.VISIBLE
		buttonPlay.setOnClickListener { service.togglePlayPause(); syncPlayButton(buttonPlay) }
		buttonClose.setOnClickListener { service.stopPlaybackAndQueueClear() }
		buttonExpand.setOnClickListener { expandToFullScreen() }
		syncPlayButton(buttonPlay)
		installGesture(layout)
		try {
			windowManager.addView(layout, params)
		} catch (e: WindowManager.BadTokenException) {
			// known Samsung API 21 edge: surface a clear message instead of crashing
			Toast.makeText(context, context.getString(R.string.media_floating_error, e.message), Toast.LENGTH_LONG).show()
			return
		} catch (e: Exception) {
			Toast.makeText(context, context.getString(R.string.media_floating_error, e.message), Toast.LENGTH_LONG).show()
			return
		}
		rootView = layout
		textureView = texture
	}

	fun hide() {
		val view = rootView ?: return
		service.bindSurface(null)
		service.bindSurfaceTexture(null)
		runCatching { windowManager.removeView(view) }
		rootView = null
		textureView = null
	}

	fun syncPlayButton() {
		val view = rootView ?: return
		syncPlayButton(view.findViewById(R.id.floatButtonPlay))
	}

	private fun syncPlayButton(button: ImageButton) {
		button.setImageResource(if (service.isVideoPlaying()) R.drawable.ic_action_pause else R.drawable.ic_play)
	}

	private fun expandToFullScreen() {
		val item = service.currentItem
		val intent = if (item != null) {
			BooruPlayerActivity.newIntent(context, item).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
		} else {
			return
		}
		hide()
		context.startActivity(intent)
	}

	@Suppress("DEPRECATION")
	private fun screenSize(): Point = Point().also {
		// defaultDisplay is still the documented way to size overlay windows
		// (TYPE_PHONE / TYPE_APPLICATION_OVERLAY are not part of the app hierarchy)
		windowManager.defaultDisplay.getSize(it)
	}

	private fun buildLayoutParams(): WindowManager.LayoutParams {
		val (widthDp, heightDp) = when (service.settings.mediaFloatingSize) {
			FloatingWindowSize.SMALL -> 240 to 135
			FloatingWindowSize.MEDIUM -> 300 to 170
			FloatingWindowSize.LARGE -> 420 to 240
		}
		val density = context.resources.displayMetrics.density
		val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
		} else {
			@Suppress("DEPRECATION")
			WindowManager.LayoutParams.TYPE_PHONE
		}
		val margin = (8 * density).toInt()
		val width = (widthDp * density).toInt()
		val height = (heightDp * density).toInt()
		val screen = screenSize()
		return WindowManager.LayoutParams(
			width,
			height,
			type,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
			PixelFormat.TRANSLUCENT,
		).apply {
			// drag math only works when x/y share the rawX/rawY origin (top-left):
			// with Gravity.END/BOTTOM, x/y are offsets from the far corner and every
			// MOVE event computed against rawX/rawY teleported the window off screen
			gravity = Gravity.TOP or Gravity.START
			when (service.settings.mediaFloatingPosition) {
				FloatingWindowPosition.TOP_RIGHT -> {
					x = maxOf(0, screen.x - width - margin)
					y = margin
				}
				FloatingWindowPosition.BOTTOM_RIGHT -> {
					x = maxOf(0, screen.x - width - margin)
					y = maxOf(0, screen.y - height - margin)
				}
				FloatingWindowPosition.BOTTOM_LEFT -> {
					x = margin
					y = maxOf(0, screen.y - height - margin)
				}
			}
		}
	}

	/**
	 * Tap = expand, double-tap = play/pause, long-press = drag until release.
	 * Drag drops are clamped to the visible screen (NewPipe popup behavior):
	 * without clamping a fast fling can park the window where it can't be
	 * grabbed again.
	 */
	private fun installGesture(layout: View) {
		val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
			override fun onDown(e: MotionEvent): Boolean = true

			override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
				expandToFullScreen()
				return true
			}

			override fun onDoubleTap(e: MotionEvent): Boolean {
				service.togglePlayPause()
				syncPlayButton()
				return true
			}

			override fun onLongPress(e: MotionEvent) {
				dragging = true
			}
		})
		gestureDetector = detector
		var downX = 0f
		var downY = 0f
		layout.setOnTouchListener { _, event ->
			detector.onTouchEvent(event)
			val view = rootView ?: return@setOnTouchListener true
			val params = view.layoutParams as WindowManager.LayoutParams
			when (event.action) {
				MotionEvent.ACTION_DOWN -> {
					downX = event.rawX - params.x
					downY = event.rawY - params.y
				}
				MotionEvent.ACTION_MOVE -> if (dragging) {
					val screen = screenSize()
					// params.x/y are absolute screen offsets (gravity is TOP|START),
					// but event.rawX/rawY include the window's anchor offset too;
					// downX/downY captured on ACTION_DOWN keep the space consistent
					params.x = (event.rawX - downX).toInt()
						.coerceIn(0, maxOf(0, screen.x - view.width))
					params.y = (event.rawY - downY).toInt()
						.coerceIn(0, maxOf(0, screen.y - view.height))
					windowManager.updateViewLayout(view, params)
				}
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
			}
			true
		}
	}
}
