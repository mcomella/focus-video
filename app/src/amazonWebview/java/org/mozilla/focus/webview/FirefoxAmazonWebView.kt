/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.webview

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.annotation.VisibleForTesting
import android.util.AttributeSet
import android.view.View
import com.amazon.android.webkit.AmazonWebChromeClient
import com.amazon.android.webkit.AmazonWebView
import mozilla.components.browser.session.Session
import org.mozilla.focus.ext.deleteData
import org.mozilla.focus.ext.savedWebViewState
import org.mozilla.focus.iwebview.FirefoxAmazonFocusedDOMElementCache
import org.mozilla.focus.iwebview.IWebView
import org.mozilla.focus.utils.UrlUtils

private val uiHandler = Handler(Looper.getMainLooper())

/**
 * An IWebView implementation using AmazonWebView.
 *
 * Initialization for this class should primarily occur in WebViewProvider,
 * which is visible by the main code base and constructs this class.
 */
@Suppress("ViewConstructor") // We only construct this in code.
internal class FirefoxAmazonWebView(
    context: Context,
    attrs: AttributeSet,
    private val client: FocusWebViewClient,
    private val chromeClient: FirefoxAmazonWebChromeClient
) : NestedWebView(context, attrs), IWebView {

    @get:VisibleForTesting
    override var callback: IWebView.Callback? = null
        set(callback) {
            field = callback
            client.setCallback(callback)
            chromeClient.callback = callback
            linkHandler.setCallback(callback)
        }

    // Init for link handler must occur here if we want immutability because they have cyclic references.
    private val linkHandler = LinkHandler(this)
    init {
        setOnLongClickListener(linkHandler)
        isLongClickable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // From a user's perspective, the WebView receives focus. Under the hood,
        // the AmazonWebView's child, *Delegate, is actually receiving focus.
        //
        // This child is not added at init time so we modify it here.
        val webViewDelegate = getChildAt(0)
        webViewDelegate.setOnFocusChangeListener(::onFocusChange)
    }

    override fun onStop() {
        // NB: onStop unexpectedly calls onPause: see below.
        //
        // When the user says "Alexa pause [the video]", the Activity will be paused/resumed while
        // Alexa handles the request. If the WebView is paused during video playback, the video will
        // have poor behavior (on YouTube the screen goes black, may rebuffer, and may lose the voice
        // command). Unfortunately, there does not appear to be any way to prevent this other than
        // to not call WebView.onPause so we pause the WebView later, here in onStop, when it isn't
        // affected by Alexa voice commands. Luckily, Alexa pauses the video for us. afaict, on
        // Fire TV, `onPause` without `onStop` isn't called very often so I don't think there will
        // be many side effects (#936).
        //
        // The problem is not reproducible when onPause is called here, even if pauseTimers is
        // called in onPause.
        onPause()
    }

    override fun onStart() {
        // NB: onStart unexpectedly calls onResume: see onStop for details.
        onResume()
    }

    @Suppress("UNUSED_PARAMETER") // To avoid allocation, we use a function reference but it's on a platform interface.
    private fun onFocusChange(view: View, hasFocus: Boolean): Unit = if (!hasFocus) {
        // For why we're modifying the focusedDOMElement, see FocusedDOMElementCache.
        //
        // Any views (like BrowserNavigationOverlay) that may clear the cache, e.g. by
        // reloading the page, are required to handle their own caching. Here we'll handle
        // cases where the page cache isn't cleared.
        focusedDOMElement.cache()
    } else {
        // Trying to restore immediately doesn't work - perhaps the WebView hasn't actually
        // received focus yet? Posting to the end of the UI queue seems to solve the problem.
        uiHandler.post { focusedDOMElement.restore() }
        Unit
    }

    override fun restoreWebViewState(session: Session) {
        val stateData = session.savedWebViewState

        val backForwardList = if (stateData != null) super.restoreState(stateData) else null
        val desiredURL = session.url

        client.restoreState(stateData)
        client.notifyCurrentURL(desiredURL)

        // Pages are only added to the back/forward list when loading finishes. If a new page is
        // loading when the Activity is paused/killed, then that page won't be in the list,
        // and needs to be restored separately to the history list. We detect this by checking
        // whether the last fully loaded page (getCurrentItem()) matches the last page that the
        // WebView was actively loading (which was retrieved during onSaveInstanceState():
        // WebView.getUrl() always returns the currently loading or loaded page).
        // If the app is paused/killed before the initial page finished loading, then the entire
        // list will be null - so we need to additionally check whether the list even exists.
        if (backForwardList != null && backForwardList.currentItem.url == desiredURL) {
            // restoreState doesn't actually load the current page, it just restores navigation history,
            // so we also need to explicitly reload in this case:
            reload()
        } else {
            loadUrl(desiredURL)
        }
    }

    override fun saveWebViewState(session: Session) {
        // We store the actual state into another bundle that we will keep in memory as long as this
        // browsing session is active. The data that WebView stores in this bundle is too large for
        // Android to save and restore as part of the state bundle.
        val stateData = Bundle()

        super.saveState(stateData)
        client.saveState(this, stateData)

        session.savedWebViewState = stateData
    }

    override fun setBlockingEnabled(enabled: Boolean) {
        client.isBlockingEnabled = enabled
        this.callback?.onBlockingStateChanged(enabled)
    }

    override fun loadUrl(url: String) {
        // We need to check external URL handling here - shouldOverrideUrlLoading() is only
        // called by webview when clicking on a link, and not when opening a new page for the
        // first time using loadUrl().
        if (!client.shouldOverrideUrlLoading(this, url)) {
            super.loadUrl(url, mapOf("X-Requested-With" to ""))
        }

        client.notifyCurrentURL(url)
    }

    override fun evalJS(js: String) {
        super.loadUrl("javascript:$js")
    }

    override fun cleanup() {
        this.deleteData()
    }

    override fun takeScreenshot(): Bitmap {
        // Under the hood, createBitmap may create a new reference to the existing Bitmap so
        // it's efficient: we don't have to copy the existing Bitmap or GC it on destroy.
        buildDrawingCache()
        val outBitmap = Bitmap.createBitmap(drawingCache)
        destroyDrawingCache()
        return outBitmap
    }

    override val focusedDOMElement = FirefoxAmazonFocusedDOMElementCache(this)

    override fun scrollByClamped(vx: Int, vy: Int) {
        // This is not a true clamp: it can only stop us from
        // continuing to scroll if we've already overscrolled.
        val scrollX = clampScroll(vx) { canScrollHorizontally(it) }
        val scrollY = clampScroll(vy) { canScrollVertically(it) }

        super.scrollBy(scrollX, scrollY)
    }
}

private inline fun clampScroll(scroll: Int, canScroll: (direction: Int) -> Boolean) = if (scroll != 0 && canScroll(scroll)) {
    scroll
} else {
    0
}

// todo: move into WebClients file with FocusWebViewClient.
internal class FirefoxAmazonWebChromeClient : AmazonWebChromeClient() {
    var callback: IWebView.Callback? = null

    override fun onProgressChanged(view: AmazonWebView?, newProgress: Int) {
        callback?.let { callback ->
            // This is the earliest point where we might be able to confirm a redirected
            // URL: we don't necessarily get a shouldInterceptRequest() after a redirect,
            // so we can only check the updated url in onProgressChanges(), or in onPageFinished()
            // (which is even later).
            val viewURL = view!!.url
            if (!UrlUtils.isInternalErrorURL(viewURL) && viewURL != null) {
                callback.onURLChanged(viewURL)
            }
            callback.onProgress(newProgress)
        }
    }

    override fun onShowCustomView(view: View?, webviewCallback: AmazonWebChromeClient.CustomViewCallback?) {
        val fullscreenCallback = object : IWebView.FullscreenCallback {
            override fun fullScreenExited() {
                webviewCallback?.onCustomViewHidden()
            }
        }

        callback?.onEnterFullScreen(fullscreenCallback, view)
    }

    override fun onHideCustomView() {
        callback?.onExitFullScreen()
    }
}
