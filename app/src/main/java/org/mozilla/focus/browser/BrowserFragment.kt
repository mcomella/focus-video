/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.browser

import android.os.Bundle
import android.support.annotation.UiThread
import android.text.TextUtils
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.android.synthetic.main.browser_overlay.*
import kotlinx.android.synthetic.main.browser_overlay.view.*
import kotlinx.android.synthetic.main.fragment_browser.*
import kotlinx.android.synthetic.main.fragment_browser.view.*
import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import mozilla.components.browser.session.Session
import org.mozilla.focus.MainActivity
import org.mozilla.focus.MediaSessionHolder
import org.mozilla.focus.R
import org.mozilla.focus.ScreenController
import org.mozilla.focus.browser.BrowserFragment.Companion.APP_URL_HOME
import org.mozilla.focus.browser.cursor.CursorController
import org.mozilla.focus.ext.components
import org.mozilla.focus.ext.isVisible
import org.mozilla.focus.ext.toUri
import org.mozilla.focus.home.BundledTilesManager
import org.mozilla.focus.home.CustomTilesManager
import org.mozilla.focus.home.HomeTilesManager
import org.mozilla.focus.iwebview.IWebView
import org.mozilla.focus.iwebview.IWebViewLifecycleFragment
import org.mozilla.focus.session.NullSession
import org.mozilla.focus.session.SessionCallbackProxy
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.telemetry.UrlTextInputLocation
import org.mozilla.focus.utils.ViewUtils.showCenteredTopToast
import org.mozilla.focus.widget.InlineAutocompleteEditText

private const val ARGUMENT_SESSION_UUID = "sessionUUID"

private const val TOAST_Y_OFFSET = 200

/**
 * Fragment for displaying the browser UI.
 */
class BrowserFragment : IWebViewLifecycleFragment(), Session.Observer {
    companion object {
        const val FRAGMENT_TAG = "browser"
        const val APP_URL_PREFIX = "firefox:"
        const val APP_URL_HOME = "${APP_URL_PREFIX}home"

        @JvmStatic
        fun createForSession(session: Session) = BrowserFragment().apply {
            arguments = Bundle().apply { putString(ARGUMENT_SESSION_UUID, session.id) }
        }
    }

    // IWebViewLifecycleFragment expects a value for these properties before onViewCreated. We use a getter
    // for the properties that reference session because it is lateinit.
    override lateinit var session: Session
    override val initialUrl get() = session.url
    override val iWebViewCallback get() = SessionCallbackProxy(session, BrowserIWebViewCallback(this))

    private val mediaSessionHolder get() = activity as MediaSessionHolder? // null when not attached.

    /**
     * The current URL.
     *
     * Use this instead of the WebView's URL which can return null, return a null URL, or return
     * data: URLs (for error pages).
     */
    var url: String? = null
        private set

    val isUrlEqualToHomepage: Boolean get() = url == APP_URL_HOME

    /**
     * Encapsulates the cursor's components. If this value is null, the Cursor is not attached
     * to the view hierarchy.
     */
    var cursor: CursorController? = null
        @UiThread get set // Set from the UI thread so serial access is required for simplicity.

    // Cache the overlay visibility state to persist in fragment back stack
    private var overlayVisibleCached: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSession()
    }

    private fun initSession() {
        val sessionUUID = arguments?.getString(ARGUMENT_SESSION_UUID)
                ?: throw IllegalAccessError("No session exists")
        session = context!!.components.sessionManager.findSessionById(sessionUUID) ?: NullSession.create()

        webView?.setBlockingEnabled(session.trackerBlockingEnabled)

        session.register(observer = this, owner = this)
    }

    override fun onUrlChanged(session: Session, url: String) {
        this.url = url
    }

    override fun onLoadingStateChanged(session: Session, loading: Boolean) {
        if (browserOverlay.isVisible) {
            browserOverlay.updateOverlayForCurrentState()
        }
    }

    private val onNavigationEvent = { event: NavigationEvent, value: String?,
            autocompleteResult: InlineAutocompleteEditText.AutocompleteResult? ->
        when (event) {
            NavigationEvent.BACK -> if (webView?.canGoBack() ?: false) webView?.goBack()
            NavigationEvent.FORWARD -> if (webView?.canGoForward() ?: false) webView?.goForward()
            NavigationEvent.TURBO, NavigationEvent.RELOAD -> webView?.reload()
            NavigationEvent.SETTINGS -> ScreenController.showSettingsScreen(fragmentManager!!)
            NavigationEvent.LOAD_URL -> {
                (activity as MainActivity).onTextInputUrlEntered(value!!, autocompleteResult!!, UrlTextInputLocation.MENU)
                setOverlayVisibleByUser(false)
            }
            NavigationEvent.LOAD_TILE -> {
                (activity as MainActivity).onNonTextInputUrlEntered(value!!)
                setOverlayVisibleByUser(false)
            }
            NavigationEvent.POCKET -> ScreenController.showPocketScreen(fragmentManager!!)
            NavigationEvent.PIN_ACTION -> {
                this@BrowserFragment.url?.let { url ->
                    when (value) {
                        NavigationEvent.VAL_CHECKED -> {
                            CustomTilesManager.getInstance(context!!).pinSite(context!!, url,
                                    webView?.takeScreenshot())
                            browserOverlay.refreshTilesForInsertion()
                            showCenteredTopToast(context, R.string.notification_pinned_site, 0, TOAST_Y_OFFSET)
                        }
                        NavigationEvent.VAL_UNCHECKED -> {
                            url.toUri()?.let {
                                val tileId = BundledTilesManager.getInstance(context!!).unpinSite(context!!, it)
                                        ?: CustomTilesManager.getInstance(context!!).unpinSite(context!!, url)
                                // tileId should never be null, unless, for some reason we don't
                                // have a reference to the tile/the tile isn't a Bundled or Custom tile
                                if (tileId != null && !tileId.isEmpty()) {
                                    browserOverlay.removePinnedSiteFromTiles(tileId)
                                    showCenteredTopToast(context, R.string.notification_unpinned_site, 0, TOAST_Y_OFFSET)
                                }
                            }
                        }
                        else -> throw IllegalArgumentException("Unexpected value for PIN_ACTION: " + value)
                    }
                }
            }
        }
        Unit
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val layout = inflater.inflate(R.layout.fragment_browser, container, false)

        cursor = CursorController(this, cursorParent = layout.browserFragmentRoot,
                view = layout.cursorView)
        lifecycle.addObserver(cursor!!)

        with(layout.browserOverlay) {
            onNavigationEvent = this@BrowserFragment.onNavigationEvent
            navigationStateProvider = NavigationStateProvider()
            visibility = overlayVisibleCached ?: View.GONE
            onPreSetVisibilityListener = { isVisible ->
                // The overlay can clear the DOM and a previous focused element cache (e.g. reload)
                // so we need to do our own caching: see FocusedDOMElementCache for details.
                if (!isVisible) { webView?.focusedDOMElement?.cache() }
            }

            openHomeTileContextMenu = {
                activity?.openContextMenu(browserOverlay.tileContainer)
            }
            registerForContextMenu(browserOverlay.tileContainer)
        }

        layout.progressBar.initialize(this)

        // We break encapsulation here: we should use the super.webView reference but it's not init until
        // onViewCreated. However, overriding both onCreateView and onViewCreated in a single class
        // is confusing so I'd rather break encapsulation than confuse devs.
        mediaSessionHolder?.videoVoiceCommandMediaSession?.onCreateWebView(layout.webview, session)

        return layout
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.remove -> {
                val homeTileAdapter = tileContainer.adapter as HomeTileAdapter
                val tileToRemove = homeTileAdapter.getItemAtPosition(browserOverlay.getFocusedTilePosition())
                        ?: return false

                // This assumes that since we're deleting from a Home Tile object that we created
                // that the Uri is valid, so we do not do error handling here.
                HomeTilesManager.removeHomeTile(tileToRemove, context!!)
                homeTileAdapter.removeItemAtPosition(browserOverlay.getFocusedTilePosition())
                TelemetryWrapper.homeTileRemovedEvent(tileToRemove)
                return true
            }
            else -> return false
        }
    }

    override fun onDestroyView() {
        mediaSessionHolder?.videoVoiceCommandMediaSession?.onDestroyWebView(webView!!, session)

        super.onDestroyView()

        lifecycle.removeObserver(cursor!!)
        cursor = null
        overlayVisibleCached = browserOverlay.visibility
        // Since we start the async jobs in View.init and Android is inflating the view for us,
        // there's no good way to pass in the uiLifecycleJob. We could consider other solutions
        // but it'll add complexity that I don't think is probably worth it.
        browserOverlay.uiLifecycleCancelJob.cancel(CancellationException("Parent lifecycle has ended"))
    }

    override fun onCreateContextMenu(menu: ContextMenu?, v: View?, menuInfo: ContextMenu.ContextMenuInfo?) {
        activity?.menuInflater?.inflate(R.menu.menu_context_hometile, menu)
    }

    fun onBackPressed(): Boolean {
        when {
            browserOverlay.isVisible && !isUrlEqualToHomepage -> setOverlayVisibleByUser(false)
            webView?.canGoBack() ?: false -> {
                webView?.goBack()
                TelemetryWrapper.browserBackControllerEvent()
            }
            else -> {
                context!!.components.sessionManager.remove()
                // Delete session, but we allow the parent to handle back behavior.
                return false
            }
        }
        return true
    }

    fun loadUrl(url: String) {
        val webView = webView
        if (webView != null && !TextUtils.isEmpty(url)) {
            webView.loadUrl(url)
        }
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        /**
         * Key handling order:
         * - Menu to control overlay
         * - Youtube remap of BACK to ESC
         * - Cursor
         * - Return false, as unhandled
         */
        return handleSpecialKeyEvent(event) ||
                (cursor?.keyDispatcher?.dispatchKeyEvent(event) ?: false)
    }

    private fun handleSpecialKeyEvent(event: KeyEvent): Boolean {
        val actionIsDown = event.action == KeyEvent.ACTION_DOWN

        if (event.keyCode == KeyEvent.KEYCODE_MENU && !isUrlEqualToHomepage) {
            if (actionIsDown) {
                val toShow = !browserOverlay.isVisible
                setOverlayVisibleByUser(toShow)
            }
            return true
        }

        if (!browserOverlay.isVisible && webView!!.isYoutubeTV &&
                event.keyCode == KeyEvent.KEYCODE_BACK) {
            val escKeyEvent = KeyEvent(event.action, KeyEvent.KEYCODE_ESCAPE)
            activity?.dispatchKeyEvent(escKeyEvent)
            return true
        }
        return false
    }

    /**
     * Changes the overlay visibility: this should be called instead of changing
     * [BrowserNavigationOverlay.isVisible] directly.
     *
     * It's important this is only called for user actions because our Telemetry
     * is dependent on it.
     */
    private fun setOverlayVisibleByUser(toShow: Boolean) {
        browserOverlay.visibility = if (toShow) View.VISIBLE else View.GONE
        if (toShow) cursor?.onPause() else cursor?.onResume()
        cursor?.setEnabledForCurrentState()
        TelemetryWrapper.drawerShowHideEvent(toShow)
    }

    private inner class NavigationStateProvider : BrowserNavigationOverlay.BrowserNavigationStateProvider {
        override fun isBackEnabled() = webView?.canGoBack() ?: false
        override fun isForwardEnabled() = webView?.canGoForward() ?: false
        override fun isPinEnabled() = !isUrlEqualToHomepage
        override fun isRefreshEnabled() = !isUrlEqualToHomepage
        override fun getCurrentUrl() = url
        override fun isURLPinned() = url.toUri()?.let {
            // TODO: #569 fix CustomTilesManager to use Uri too
            CustomTilesManager.getInstance(context!!).isURLPinned(it.toString()) ||
                    BundledTilesManager.getInstance(context!!).isURLPinned(it) } ?: false
    }
}

private class BrowserIWebViewCallback(
    private val browserFragment: BrowserFragment
) : IWebView.Callback {

    private var fullscreenCallback: IWebView.FullscreenCallback? = null

    override fun onPageStarted(url: String) {}

    override fun onPageFinished(isSecure: Boolean) {}
    override fun onProgress(progress: Int) {}

    override fun onURLChanged(url: String) {}
    override fun onRequest(isTriggeredByUserGesture: Boolean) {}

    override fun onBlockingStateChanged(isBlockingEnabled: Boolean) {}

    override fun onLongPress(hitTarget: IWebView.HitTarget) {}
    override fun onShouldInterceptRequest(url: String) {
        // This might not be called from the UI thread but needs to be, so we use launch.
        launch(UI) {
            when (url) {
                APP_URL_HOME -> browserFragment.browserOverlay?.visibility = View.VISIBLE
            }
        }
    }

    override fun onEnterFullScreen(callback: IWebView.FullscreenCallback, view: View?) {
        fullscreenCallback = callback
        if (view == null) return

        with(browserFragment) {
            // Hide browser UI and web content
            browserContainer.visibility = View.INVISIBLE

            val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            videoContainer.addView(view, params)
            videoContainer.visibility = View.VISIBLE
        }
    }

    override fun onExitFullScreen() {
        with(browserFragment) {
            videoContainer.removeAllViews()
            videoContainer.visibility = View.GONE

            browserContainer.visibility = View.VISIBLE
        }

        fullscreenCallback?.fullScreenExited()
        fullscreenCallback = null
    }
}
