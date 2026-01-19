/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * vim: ts=4 sw=4 expandtab:
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.geckoview

import android.R
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.print.PrintManager
import android.util.AttributeSet
import android.util.Log
import android.util.SparseArray
import android.util.TypedValue
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewStructure
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import androidx.annotation.AnyThread
import androidx.annotation.IntDef
import androidx.annotation.UiThread
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.InputStream
import java.lang.ref.WeakReference
import org.mozilla.gecko.AndroidGamepadManager
import org.mozilla.gecko.EventDispatcher
import org.mozilla.gecko.InputMethods
import org.mozilla.gecko.SurfaceViewWrapper
import org.mozilla.gecko.util.ThreadUtils

/**
 * A view container that hosts Gecko rendering, manages its surface, and dispatches input/events.
 */
@UiThread
open class GeckoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), GeckoDisplay.NewSurfaceProvider {

    private val mWindowInsetsListeners = HashMap<String, androidx.core.view.OnApplyWindowInsetsListener>()

    /** Manages the underlying GeckoDisplay surface lifecycle and layout. */
    val mDisplay: Display = Display()

    private var mLastCoverColor: Int? = null

    /** The currently attached GeckoSession, or null if none is set. */
    var session: GeckoSession? = null
        /**
         * Attach a session to this view. If this instance already has an open session, you must use
         * [releaseSession] first. This is to avoid potentially leaking the currently opened session.
         *
         * @param value The session to be attached.
         */
        @UiThread
        set(value) {
            ThreadUtils.assertOnUiThread()

            if (value == field) {
                // Nothing to do
                return
            }

            // Release the old session before setting the new one.
            field?.let {
                releaseSessionInternal(it)
            }

            field = value
            mIsSessionPoisoned = false

            val currentSession = value ?: return

            // Setup logic for the new session
            currentSession.setOwner(mSessionOwner)

            // Make sure the clear color is set to the default
            currentSession.compositorController.setClearColor(defaultColor())

            if (ViewCompat.isAttachedToWindow(this)) {
                mDisplay.acquire(currentSession.acquireDisplay())
            }

            val ctx = context
            currentSession.overscrollEdgeEffect.setTheme(ctx)
            currentSession.overscrollEdgeEffect.setSession(currentSession)
            currentSession.overscrollEdgeEffect.setInvalidationCallback {
                this@GeckoView.postInvalidateOnAnimation()
            }

            val metrics = ctx.resources.displayMetrics
            val outValue = TypedValue()
            if (ctx.theme.resolveAttribute(R.attr.listPreferredItemHeight, outValue, true)) {
                currentSession.panZoomController.setScrollFactor(outValue.getDimension(metrics))
            } else {
                currentSession.panZoomController.setScrollFactor(0.075f * metrics.densityDpi)
            }

            currentSession.compositorController.setFirstPaintCallback(this::uncover)

            if (currentSession.textInput.view == null) {
                currentSession.textInput.view = this
            }

            if (currentSession.accessibility.view == null) {
                currentSession.accessibility.view = this
            }

            if (currentSession.selectionActionDelegate == null && mSelectionActionDelegate != null) {
                currentSession.selectionActionDelegate = mSelectionActionDelegate
            }

            if (isAutofillEnabled) {
                currentSession.autofillDelegate = mAutofillDelegate
            }

            if (currentSession.magnifier.view == null) {
                mSurfaceWrapper?.view?.let {
                    currentSession.magnifier.setView(it)
                }
            }

            if (currentSession.printDelegate == null && mPrintDelegate != null) {
                currentSession.printDelegate = mPrintDelegate
            }

            if (isFocused) {
                currentSession.setFocused(true)
            }
        }

    private var mAutofillSession: WeakReference<Autofill.Session?> = WeakReference(null)

    // Whether this GeckoView instance has a session that is no longer valid, e.g. because the session
    // associated to this GeckoView was attached to a different GeckoView instance.
    private var mIsSessionPoisoned = false

    private var mSurfaceWrapper: SurfaceViewWrapper? = null

    private var mIsResettingFocus = false

    var isAutofillEnabled = true
        /**
         * Sets whether or not this View participates in Android autofill.
         *
         * When enabled, this will set an [Autofill.Delegate] on the [GeckoSession] for
         * this instance.
         *
         * @param enabled Whether or not Android autofill is enabled for this view.
         */
        set(enabled) {
            field = enabled

            session?.let {
                if (!enabled && it.autofillDelegate === mAutofillDelegate) {
                    it.autofillDelegate = null
                } else if (enabled) {
                    it.autofillDelegate = mAutofillDelegate
                }
            }
        }

    private var mSelectionActionDelegate: GeckoSession.SelectionActionDelegate? = null
    private var mAutofillDelegate: Autofill.Delegate? = null
    var activityContextDelegate: ActivityContextDelegate? = null
    var printDelegate: GeckoSession.PrintDelegate?
        get() = mPrintDelegate
        set(value) {
            mPrintDelegate = value
        }
    private var mPrintDelegate: GeckoSession.PrintDelegate? = null

    inner class Display : SurfaceViewWrapper.Listener, androidx.core.view.OnApplyWindowInsetsListener {
        private val mOrigin = IntArray(2)

        private var mDisplay: GeckoDisplay? = null
        private var mValid = false

        private var mClippingHeight = 0
        private var mDynamicToolbarMaxHeight = 0

        fun acquire(display: GeckoDisplay?) {
            mDisplay = display

            if (!mValid) {
                return
            }

            setVerticalClipping(mClippingHeight)

            // Tell display there is already a surface.
            onGlobalLayout()
            mSurfaceWrapper?.let { wrapper ->
                mDisplay?.surfaceChanged(
                    GeckoDisplay.SurfaceInfo.Builder(wrapper.surface)
                        .surfaceControl(wrapper.surfaceControl)
                        .newSurfaceProvider(this@GeckoView)
                        .size(wrapper.width, wrapper.height)
                        .build()
                )
                mDisplay?.setDynamicToolbarMaxHeight(mDynamicToolbarMaxHeight)
                this@GeckoView.setActive(true)
            }
        }

        fun release(): GeckoDisplay? {
            if (mValid) {
                mDisplay?.surfaceDestroyed()
                this@GeckoView.setActive(false)
            }

            val display = mDisplay
            mDisplay = null
            return display
        }

        override fun onSurfaceChanged(
            surface: Surface,
            surfaceControl: SurfaceControl?,
            width: Int,
            height: Int
        ) {
            mDisplay?.let {
                it.surfaceChanged(
                    GeckoDisplay.SurfaceInfo.Builder(surface)
                        .surfaceControl(surfaceControl)
                        .newSurfaceProvider(this@GeckoView)
                        .size(width, height)
                        .build()
                )
                it.setDynamicToolbarMaxHeight(mDynamicToolbarMaxHeight)
                if (!mValid) {
                    this@GeckoView.setActive(true)
                }
            }
            mValid = true
        }

        override fun onSurfaceDestroyed() {
            mDisplay?.surfaceDestroyed()
            this@GeckoView.setActive(false)
            mValid = false
        }

        fun onGlobalLayout() {
            val display = mDisplay ?: return

            mSurfaceWrapper?.view?.let {
                it.getLocationOnScreen(mOrigin)
                display.screenOriginChanged(mOrigin[0], mOrigin[1])
                // cutout support
                if (Build.VERSION.SDK_INT >= 28) {
                    it.rootWindowInsets?.displayCutout?.let { cutout ->
                        display.safeAreaInsetsChanged(
                            cutout.safeInsetTop,
                            cutout.safeInsetRight,
                            cutout.safeInsetBottom,
                            cutout.safeInsetLeft
                        )
                    }
                }
            }
        }

        override fun onApplyWindowInsets(
            view: View,
            insets: WindowInsetsCompat
        ): WindowInsetsCompat {
            mDisplay?.windowInsetsChanged(insets)
            return insets
        }

        fun shouldPinOnScreen(): Boolean {
            return mDisplay?.shouldPinOnScreen() ?: false
        }

        fun setVerticalClipping(clippingHeight: Int) {
            mClippingHeight = clippingHeight
            mDisplay?.setVerticalClipping(clippingHeight)
        }

        fun setDynamicToolbarMaxHeight(height: Int) {
            mDynamicToolbarMaxHeight = height

            // Reset the vertical clipping value to zero whenever we change
            // the dynamic toolbar __max__ height so that it can be properly
            // propagated to both the main thread and the compositor thread,
            // thus we will be able to reset the __current__ toolbar height
            // on the both threads whatever the __current__ toolbar height is.
            setVerticalClipping(0)

            mDisplay?.setDynamicToolbarMaxHeight(height)
        }

        /**
         * Request a [Bitmap] of the visible portion of the web page currently being rendered.
         *
         * @return A [GeckoResult] that completes with a [Bitmap] containing the pixels and
         *     size information of the currently visible rendered web page.
         */
        @UiThread
        fun capturePixels(): GeckoResult<Bitmap> {
            return mDisplay?.capturePixels()
                ?: GeckoResult.fromException(IllegalStateException("Display must be created before pixels can be captured"))
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        // We are adding descendants to this LayerView, but we don't want the
        // descendants to affect the way LayerView retains its focus.
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS

        // When GeckoView.requestFocus() is called with hardware keyboard, the focused state color
        // might be applied on this view. But we don't want to apply it as default.
        val drawable = StateListDrawable()
        drawable.addState(
            intArrayOf(R.attr.state_focused, -R.attr.state_focused),
            ColorDrawable(Color.WHITE)
        )
        background = drawable

        // This will stop PropertyAnimator from creating a drawing cache (i.e. a
        // bitmap) from a SurfaceView, which is just not possible (the bitmap will be
        // transparent).
        setWillNotCacheDrawing(false)

        mSurfaceWrapper = SurfaceViewWrapper(context).apply {
            setBackgroundColor(Color.WHITE)
            addView(
                view,
                ViewGroup.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT
                )
            )
            setListener(mDisplay)
        }

        getActivityFromContext(context)?.let {
            mSelectionActionDelegate = BasicSelectionActionDelegate(it)
        }

        mAutofillDelegate = AndroidAutofillDelegate()
        mPrintDelegate = GeckoViewPrintDelegate()
    }

    private fun getActivityFromContext(outerContext: Context): Activity? {
        var context: Context? = outerContext
        while (context is ContextWrapper) {
            if (context is Activity) {
                return context
            }
            context = context.baseContext
        }
        return null
    }

    /**
     * Set a color to cover the display surface while a document is being shown. The color is
     * automatically cleared once the new document starts painting.
     *
     * @param color Cover color.
     */
    fun coverUntilFirstPaint(color: Int) {
        mLastCoverColor = color
        session?.compositorController?.setClearColor(color)
        coverUntilFirstPaintInternal(color)
    }

    private fun uncover() {
        coverUntilFirstPaintInternal(Color.TRANSPARENT)
    }

    private fun coverUntilFirstPaintInternal(color: Int) {
        ThreadUtils.assertOnUiThread()
        mSurfaceWrapper?.setBackgroundColor(color)
    }

    /** View backend type definitions for GeckoView display backends. */
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(BACKEND_SURFACE_VIEW, BACKEND_TEXTURE_VIEW)
    annotation class ViewBackend

    /**
     * Set which view should be used by this GeckoView instance to display content.
     *
     * By default, GeckoView will use a [SurfaceView].
     *
     * @param backend Any of [BACKEND_SURFACE_VIEW] or [BACKEND_TEXTURE_VIEW].
     */
    fun setViewBackend(@ViewBackend backend: Int) {
        val wrapper = mSurfaceWrapper ?: return
        removeView(wrapper.view)

        when (backend) {
            BACKEND_SURFACE_VIEW -> wrapper.useSurfaceView(context)
            BACKEND_TEXTURE_VIEW -> wrapper.useTextureView(context)
        }

        addView(wrapper.view)

        session?.magnifier?.setView(wrapper.view)
    }

    /**
     * Return whether the view should be pinned on the screen. When pinned, the view should not be
     * moved on the screen due to animation, scrolling, etc. A common reason for the view being pinned
     * is when the user is dragging a selection caret inside the view; normal user interaction would
     * be disrupted in that case if the view was moved on screen.
     *
     * @return True if view should be pinned on the screen.
     */
    fun shouldPinOnScreen(): Boolean {
        ThreadUtils.assertOnUiThread()
        return mDisplay.shouldPinOnScreen()
    }

    /**
     * Update the amount of vertical space that is clipped or visibly obscured in the bottom portion
     * of the view. Tells gecko where to put bottom fixed elements so they are fully visible.
     *
     * Optional call. The display's visible vertical space has changed. Must be called on the
     * application main thread.
     *
     * @param clippingHeight The height of the bottom clipped space in screen pixels.
     */
    fun setVerticalClipping(clippingHeight: Int) {
        ThreadUtils.assertOnUiThread()
        mDisplay.setVerticalClipping(clippingHeight)
    }

    /**
     * Set the maximum height of the dynamic toolbar(s).
     *
     * If there are two or more dynamic toolbars, the height value should be the total amount of
     * the height of each dynamic toolbar.
     *
     * @param height The the maximum height of the dynamic toolbar(s).
     */
    fun setDynamicToolbarMaxHeight(height: Int) {
        mDisplay.setDynamicToolbarMaxHeight(height)
    }

    internal fun setActive(active: Boolean) {
        session?.setActive(active)
    }

    private fun defaultColor(): Int {
        // If the app set a default color, just use that
        mLastCoverColor?.let {
            return it
        }

        val currentSession = session
        if (currentSession == null || !currentSession.isOpen) {
            return Color.WHITE
        }

        // ... otherwise use the prefers-color-scheme color
        return if (currentSession.runtime?.usesDarkTheme() == true) DEFAULT_DARK_COLOR else Color.WHITE
    }

    /**
     * Unsets the current session from this instance and returns it, if any. You must call this before
     * setting a new session if there is already an open session set for this instance.
     *
     * Note: this method does not close the session and the session remains active. The caller is
     * responsible for calling [GeckoSession.close] when appropriate.
     *
     * @return The [GeckoSession] that was set for this instance. May be null.
     */
    @UiThread
    fun releaseSession(): GeckoSession? {
        ThreadUtils.assertOnUiThread()
        val sessionToRelease = session ?: return null
        // Setting session to null will trigger the setter, which in turn
        // will call releaseSessionInternal on the session being released.
        session = null
        return sessionToRelease
    }

    private fun releaseSessionInternal(sessionToRelease: GeckoSession) {
        mDisplay.release()?.let { sessionToRelease.releaseDisplay(it) }
        sessionToRelease.overscrollEdgeEffect.setInvalidationCallback(null)
        sessionToRelease.overscrollEdgeEffect.setSession(null)
        sessionToRelease.compositorController.setFirstPaintCallback(null)

        if (sessionToRelease.accessibility.view === this) {
            sessionToRelease.accessibility.view = null
        }

        if (sessionToRelease.textInput.view === this) {
            sessionToRelease.textInput.view = null
        }

        if (sessionToRelease.selectionActionDelegate === mSelectionActionDelegate) {
            sessionToRelease.selectionActionDelegate = null
        }

        if (sessionToRelease.autofillDelegate === mAutofillDelegate) {
            sessionToRelease.autofillDelegate = null
        }

        if (sessionToRelease.printDelegate === mPrintDelegate) {
            sessionToRelease.printDelegate = null
        }

//        if (sessionToRelease.magnifier.view === mSurfaceWrapper?.view) {
//            sessionToRelease.magnifier.view = null
//        }

        if (isFocused) {
            sessionToRelease.setFocused(false)
        }
        sessionToRelease.releaseOwner()
    }

    private val mSessionOwner = object : GeckoSession.Owner {
        override fun onRelease() {
            // The session that we own is being owned by some other object so we need to release it
            // here.
            releaseSession()
            // The session associated to this GeckoView is now invalid, but the app is not aware of
            // it. We cannot display this GeckoView until the app sets a session again (or releases
            // the poisoned session).
            mIsSessionPoisoned = true
        }
    }

    @get:AnyThread
    val eventDispatcher: EventDispatcher?
        get() = session?.eventDispatcher

    /**
     * Retrieves the controller responsible for panning and zooming gestures.
     *
     * @return The non-null PanZoomController for this GeckoView.
     */
    val panZoomController: PanZoomController?
        @UiThread
        get() {
            ThreadUtils.assertOnUiThread()
            return session?.panZoomController
        }

    /**
     * Register an internal windowInsetsListener that will forward its calls to the listeners
     * registered in [GeckoView.mWindowInsetsListeners]
     *
     * @param activity The target Activity to observe.
     */
    private fun attachWindowInsetsListener(activity: Activity) {
        try {
            val rootView = activity.window.decorView.rootView
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
                var updatedInsets = WindowInsetsCompat.toWindowInsetsCompat(
                    view.onApplyWindowInsets(insets.toWindowInsets())
                )

                for (listener in mWindowInsetsListeners.values) {
                    updatedInsets = listener.onApplyWindowInsets(view, updatedInsets)
                }
                updatedInsets
            }
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to attach WindowInsetsListener: ", e)
        }
    }

    /**
     * Unregister the internal WindowInsetsListener attached to the Activity's root view and clear the
     * listeners that were attached through [GeckoView.addWindowInsetsListener]
     *
     * @param activity The target Activity to stop observing.
     */
    private fun detachWindowInsetsListener(activity: Activity) {
        try {
            val rootView = activity.window.decorView.rootView
            ViewCompat.setOnApplyWindowInsetsListener(rootView, null)
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to detach WindowInsetsListener: ", e)
        }
    }

    /**
     * Add an OnApplyWindowInsetsListener to observe the root view WindowInsets changes.
     *
     * @param key The key associated to the listener.
     * @param listener The OnApplyWindowInsetsListener to be invoked.
     */
    @UiThread
    fun addWindowInsetsListener(
        key: String,
        listener: androidx.core.view.OnApplyWindowInsetsListener?
    ) {
        ThreadUtils.assertOnUiThread()
        if (listener != null) {
            mWindowInsetsListeners[key] = listener
        }
    }

    /**
     * Remove the OnApplyWindowInsetsListener to stop observing WindowInsets changed.
     *
     * @param key The key associated to the listener to remove.
     */
    @UiThread
    fun removeWindowInsetsListener(key: String) {
        ThreadUtils.assertOnUiThread()
        mWindowInsetsListeners.remove(key)
    }

    override fun onAttachedToWindow() {
        if (mIsSessionPoisoned) {
            throw IllegalStateException("Trying to display a view with invalid session.")
        }
        session?.runtime?.orientationChanged()

        session?.let {
            mDisplay.acquire(it.acquireDisplay())
        }

        super.onAttachedToWindow()

        // This needs to be called after the `super.onAttachedToWindow()`.
        addWindowInsetsListener(KEYBOARD_WINDOW_INSETS_LISTENER, mDisplay)
        getActivityFromContext(context)?.let {
            attachWindowInsetsListener(it)
        }
    }

    public override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        removeWindowInsetsListener(KEYBOARD_WINDOW_INSETS_LISTENER)
        if (mWindowInsetsListeners.isEmpty()) {
            getActivityFromContext(context)?.let {
                detachWindowInsetsListener(it)
            }
        }

        val currentSession = session ?: return

        // Release the display before we detach from the window.
        mDisplay.release()?.let {
            currentSession.releaseDisplay(it)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        session?.runtime?.configurationChanged(newConfig)
    }

    override fun gatherTransparentRegion(region: Region?): Boolean {
        // For detecting changes in SurfaceView layout, we take a shortcut here and
        // override gatherTransparentRegion, instead of registering a layout listener,
        // which is more expensive.
        if (mSurfaceWrapper != null) {
            mDisplay.onGlobalLayout()
        }
        return super.gatherTransparentRegion(region)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)

        // Only call setFocus(true) when the window gains focus. Any focus loss could be temporary
        // (e.g. due to auto-fill popups) and we don't want to call setFocus(false) in those cases.
        // Instead, we call setFocus(false) in onWindowVisibilityChanged.
        if (session != null && hasWindowFocus && isFocused) {
            session?.setFocused(true)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)

        // We can be reasonably sure that the focus loss is not temporary, so call setFocus(false).
        if (session != null && visibility != VISIBLE && !hasWindowFocus()) {
            session?.setFocused(false)
        }
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)

        if (mIsResettingFocus) {
            return
        }

        session?.setFocused(gainFocus)

        if (!gainFocus) {
            return
        }

        post {
            if (!isFocused) {
                return@post
            }

            val imm = InputMethods.getInputMethodManager(context)
            // Bug 1404111: Through View#onFocusChanged, the InputMethodManager queues
            // up a checkFocus call for the next spin of the message loop, so by
            // posting this Runnable after super#onFocusChanged, the IMM should have
            // completed its focus change handling at this point and we should be the
            // active view for input handling.

            // If however onViewDetachedFromWindow for the previously active view gets
            // called *after* onFocusChanged, but *before* the focus change has been
            // fully processed by the IMM with the help of checkFocus, the IMM will
            // lose track of the currently active view, which means that we can't
            // interact with the IME.
            if (imm != null && !imm.isActive(this@GeckoView)) {
                // If that happens, we bring the IMM's internal state back into sync
                // by clearing and resetting our focus.
                mIsResettingFocus = true
                clearFocus()
                // After calling clearFocus we might regain focus automatically, but
                // we explicitly request it again in case this doesn't happen.  If
                // we've already got the focus back, this will then be a no-op anyway.
                requestFocus()
                mIsResettingFocus = false
            }
        }
    }

    override fun getHandler(): Handler? {
        return super.getHandler()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        return session?.textInput?.onCreateInputConnection(outAttrs)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (super.onKeyPreIme(keyCode, event)) {
            return true
        }
        return session?.textInput?.onKeyPreIme(keyCode, event) ?: false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (super.onKeyUp(keyCode, event)) {
            return true
        }
        if (AndroidGamepadManager.handleKeyEvent(event)) {
            return true
        }
        return session?.textInput?.onKeyUp(keyCode, event) ?: false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (super.onKeyDown(keyCode, event)) {
            return true
        }
        if (AndroidGamepadManager.handleKeyEvent(event)) {
            return true
        }
        return session?.textInput?.onKeyDown(keyCode, event) ?: false
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (super.onKeyLongPress(keyCode, event)) {
            return true
        }
        return session?.textInput?.onKeyLongPress(keyCode, event) ?: false
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (super.onKeyMultiple(keyCode, repeatCount, event)) {
            return true
        }
        return session?.textInput?.onKeyMultiple(keyCode, repeatCount, event) ?: false
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        session?.overscrollEdgeEffect?.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            requestFocus()
        }

        val currentSession = session ?: return false

        currentSession.panZoomController.onTouchEvent(event)
        return true
    }

    /**
     * Dispatches a [MotionEvent] to the [PanZoomController]. This is the same as [onTouchEvent],
     * but instead returns a [PanZoomController.InputResult] indicating how the event was handled.
     *
     * NOTE: It is highly recommended to only call this with ACTION_DOWN or in otherwise limited
     * capacity. Returning a GeckoResult for every touch event will generate a lot of allocations and
     * unnecessary GC pressure.
     *
     * @param event A [MotionEvent]
     * @return A GeckoResult resolving to [PanZoomController.InputResultDetail].
     */
    fun onTouchEventForDetailResult(event: MotionEvent): GeckoResult<PanZoomController.InputResultDetail> {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            requestFocus()
        }

        val currentSession = session
        if (currentSession == null) {
            return GeckoResult.fromValue(
                PanZoomController.InputResultDetail(
                    PanZoomController.INPUT_RESULT_UNHANDLED,
                    PanZoomController.SCROLLABLE_FLAG_NONE,
                    PanZoomController.OVERSCROLL_FLAG_NONE
                )
            )
        }

        // NOTE: Treat mouse events as "touch" rather than as "mouse", so mouse can be
        // used to pan/zoom. Call onMouseEvent() instead for behavior similar to desktop.
        return currentSession.panZoomController.onTouchEventForDetailResult(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (AndroidGamepadManager.handleMotionEvent(event)) {
            return true
        }

        val currentSession = session ?: return true

        if (currentSession.accessibility.onMotionEvent(event)) {
            return true
        }

        currentSession.panZoomController.onMotionEvent(event)
        return true
    }

    override fun onProvideAutofillVirtualStructure(structure: ViewStructure, flags: Int) {
        val autofillSession = session?.autofillSession ?: return

        // Let's store the session here in case we need to autofill it later
        mAutofillSession = WeakReference(autofillSession)
        autofillSession.fillViewStructure(this, structure, flags)
    }

    override fun autofill(values: SparseArray<AutofillValue>) {
        // Note: we can't use mSession.getAutofillSession() because the app might have swapped
        // the session under us between the onProvideAutofillVirtualStructure and this call
        // so mSession could refer to a different session or we might not have a session at all.
        val session = mAutofillSession.get() ?: return

        val strValues = SparseArray<CharSequence>(values.size())
        for (i in 0 until values.size()) {
            val value = values.valueAt(i)
            if (value.isText) {
                // Only text is currently supported.
                strValues.put(values.keyAt(i), value.textValue)
            }
        }
        session.autofill(strValues)
    }

    override fun isVisibleToUserForAutofill(virtualId: Int): Boolean {
        // If autofill service works with compatibility mode,
        // View.isVisibleToUserForAutofill walks through the accessibility nodes.
        // This override avoids it.
        return true
    }

    /**
     * Request a [Bitmap] of the visible portion of the web page currently being rendered.
     *
     * See [GeckoDisplay.capturePixels] for more details.
     *
     * @return A [GeckoResult] that completes with a [Bitmap] containing the pixels and
     *     size information of the currently visible rendered web page.
     */
    @UiThread
    fun capturePixels(): GeckoResult<Bitmap> {
        return mDisplay.capturePixels()
    }

    private inner class AndroidAutofillDelegate : Autofill.Delegate {
        var mAutofillManager: AutofillManager? = null
        var mDisabled = false

        private fun ensureAutofillManager() {
            if (mDisabled || mAutofillManager != null) {
                // Nothing to do
                return
            }

            mAutofillManager = this@GeckoView.context.getSystemService(AutofillManager::class.java)
            if (mAutofillManager == null) {
                // If we can't get a reference to the autofill manager, we cannot run the autofill service
                mDisabled = true
            }
        }

        private fun displayRectForId(
            session: GeckoSession,
            node: Autofill.Node?
        ): Rect {
            if (node == null) {
                return Rect(0, 0, 0, 0)
            }

            if (!node.screenRect.isEmpty) {
                return node.screenRect
            }

            val matrix = Matrix()
            val rectF = RectF(node.dimensions)
            session.getPageToScreenMatrix(matrix)
            matrix.mapRect(rectF)

            val screenRect = Rect()
            rectF.roundOut(screenRect)
            return screenRect
        }

        override fun onNodeBlur(
            session: GeckoSession,
            prev: Autofill.Node,
            data: Autofill.NodeData
        ) {
            ensureAutofillManager()
            val autofillManager = mAutofillManager ?: return
            try {
                autofillManager.notifyViewExited(this@GeckoView, data.id)
            } catch (e: SecurityException) {
                Log.e(LOGTAG, "Failed to call AutofillManager.notifyViewExited: ", e)
            }
        }

        override fun onNodeAdd(
            session: GeckoSession,
            node: Autofill.Node,
            data: Autofill.NodeData
        ) {
            val currentSession = this@GeckoView.session ?: return
            if (!currentSession.autofillSession.isVisible(node)) {
                return
            }
            val focused = currentSession.autofillSession.focused ?: return
            val focusedData = currentSession.autofillSession.dataFor(focused) ?: return

            ensureAutofillManager()
            val autofillManager = mAutofillManager ?: return
            try {
                autofillManager.notifyViewExited(this@GeckoView, focusedData.id)
                autofillManager.notifyViewEntered(
                    this@GeckoView,
                    focusedData.id,
                    displayRectForId(session, focused)
                )
            } catch (e: SecurityException) {
                Log.e(
                    LOGTAG,
                    "Failed to call AutofillManager.notifyViewExited or AutofillManager.notifyViewEntered: ",
                    e
                )
            }
        }

        override fun onNodeFocus(
            session: GeckoSession,
            focused: Autofill.Node,
            data: Autofill.NodeData
        ) {
            ensureAutofillManager()
            val autofillManager = mAutofillManager ?: return
            try {
                autofillManager.notifyViewEntered(
                    this@GeckoView,
                    data.id,
                    displayRectForId(session, focused)
                )
            } catch (e: SecurityException) {
                Log.e(LOGTAG, "Failed to call AutofillManager.notifyViewEntered: ", e)
            }
        }

        override fun onNodeRemove(
            session: GeckoSession,
            node: Autofill.Node,
            data: Autofill.NodeData
        ) {}

        override fun onNodeUpdate(
            session: GeckoSession,
            node: Autofill.Node,
            data: Autofill.NodeData
        ) {
            ensureAutofillManager()
            val autofillManager = mAutofillManager ?: return
            try {
                autofillManager.notifyValueChanged(
                    this@GeckoView,
                    data.id,
                    AutofillValue.forText(data.value)
                )
            } catch (e: SecurityException) {
                Log.e(LOGTAG, "Failed to call AutofillManager.notifyValueChanged: ", e)
            }
        }

        override fun onSessionCancel(session: GeckoSession) {
            ensureAutofillManager()
            val autofillManager = mAutofillManager ?: return
            try {
                // This line seems necessary for auto-fill to work on the initial page.
                autofillManager.cancel()
            } catch (e: SecurityException) {
                Log.e(LOGTAG, "Failed to call AutofillManager.cancel: ", e)
            }
        }

        override fun onSessionCommit(
            session: GeckoSession,
            node: Autofill.Node,
            data: Autofill.NodeData
        ) {
            ensureAutofillManager()
            val autofillManager = mAutofillManager ?: return
            try {
                autofillManager.commit()
            } catch (e: SecurityException) {
                Log.e(LOGTAG, "Failed to call AutofillManager.commit: ", e)
            }
        }

        override fun onSessionStart(session: GeckoSession) {
            ensureAutofillManager()
            val autofillManager = mAutofillManager ?: return
            try {
                // This line seems necessary for auto-fill to work on the initial page.
                autofillManager.cancel()
            } catch (e: SecurityException) {
                Log.e(LOGTAG, "Failed to call AutofillManager.cancel: ", e)
            }
        }
    }

    /**
     * This delegate is used to provide the GeckoView an Activity context for certain operations such
     * as retrieving a PrintManager, which requires an Activity context. Using getContext() directly
     * might retrieve an Activity context or a Fragment context, this delegate ensures an Activity
     * context.
     *
     * Not to be confused with the GeckoRuntime delegate [GeckoRuntime.ActivityDelegate]
     * which is tightly coupled with WebAuthn - see bug 1671988.
     */
    @AnyThread
    fun interface ActivityContextDelegate {
        /**
         * Method should return an Activity context. May return null if not available.
         *
         * @return Activity context
         */
        fun getActivityContext(): Context?
    }

    private inner class GeckoViewPrintDelegate : GeckoSession.PrintDelegate {
        override fun onPrint(session: GeckoSession) {
            val geckoResult = session.saveAsPdf()
            geckoResult.accept(
                { pdfStream -> pdfStream?.let { onPrint(it) } },
                { exception -> Log.e(LOGTAG, "Could not create a content PDF to print.", exception) }
            )
        }

        override fun onPrint(pdfStream: InputStream) {
            onPrintWithStatus(pdfStream)
        }

        override fun onPrintWithStatus(pdfStream: InputStream): GeckoResult<Boolean> {
            val isDialogFinished = GeckoResult<Boolean>()
            val delegate = activityContextDelegate
            if (delegate == null) {
                Log.w(LOGTAG, "Missing an activity context delegate, which is required for printing.")
                isDialogFinished.completeExceptionally(
                    GeckoSession.GeckoPrintException(GeckoSession.GeckoPrintException.ERROR_NO_ACTIVITY_CONTEXT_DELEGATE)
                )
                return isDialogFinished
            }
            val printContext = delegate.getActivityContext()
            if (printContext == null) {
                Log.w(LOGTAG, "An activity context is required for printing.")
                isDialogFinished.completeExceptionally(
                    GeckoSession.GeckoPrintException(GeckoSession.GeckoPrintException.ERROR_NO_ACTIVITY_CONTEXT)
                )
                return isDialogFinished
            }
            val printManager = printContext.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            if (printManager == null) {
                isDialogFinished.completeExceptionally(
                    GeckoSession.GeckoPrintException(GeckoSession.GeckoPrintException.ERROR_NO_ACTIVITY_CONTEXT)
                )
                return isDialogFinished
            }
            val pda = GeckoViewPrintDocumentAdapter(pdfStream, context, isDialogFinished)
            printManager.print("Firefox", pda, null)
            return isDialogFinished
        }
    }

    // GeckoDisplay.NewSurfaceProvider

    override fun requestNewSurface() {
        // Toggling the View's visibility is enough to provoke a surfaceChanged callback with a new
        // Surface on all current versions of Android tested from 5 through to 13. On the more recent of
        // those versions, however, this does not work when called from within a prior surfaceChanged
        // callback, which we probably are here. We therefore post a Runnable to toggle the visibility
        // from outside of the current callback.
        post {
            mSurfaceWrapper?.view?.visibility = INVISIBLE
            mSurfaceWrapper?.view?.visibility = VISIBLE
        }
    }

    /** Handle drag and drop event */
    override fun onDragEvent(event: DragEvent): Boolean {
        return session?.panZoomController?.onDragEvent(event) ?: false
    }

    companion object {
        private const val LOGTAG = "GeckoView"
        private const val KEYBOARD_WINDOW_INSETS_LISTENER = "KEYBOARD_WINDOW_INSETS_LISTENER"

        /**
         * This GeckoView instance will be backed by a [SurfaceView].
         *
         * This option offers the best performance at the price of not being able to animate GeckoView.
         */
        const val BACKEND_SURFACE_VIEW = 1

        /**
         * This GeckoView instance will be backed by a [TextureView].
         *
         * This option offers worse performance compared to [BACKEND_SURFACE_VIEW] but allows
         * you to animate GeckoView or to paint a GeckoView on top of another GeckoView.
         */
        const val BACKEND_TEXTURE_VIEW = 2

        // TODO: Bug 1670805 this should really be configurable
        // Default dark color for about:blank, keep it in sync with PresShell.cpp
        const val DEFAULT_DARK_COLOR = -0xd5d5d2 // 0xFF2A2A2E
    }
}
