package com.example.noreelsgram

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

class MainActivity : Activity() {

    private lateinit var web: WebView

    // Any path segment named "reel" or "reels" (/reels/, /reel/ID/, /user/reels/, /user/reel/ID/)
    private val reelPath = Regex("(^|/)reels?(/|$)")

    private fun isReelUrl(uri: Uri?): Boolean {
        if (uri == null) return false
        val path = uri.path ?: return false
        return reelPath.containsMatchIn(path)
    }

    private fun isInstagram(uri: Uri?): Boolean {
        val h = uri?.host ?: return false
        return h == "instagram.com" || h.endsWith(".instagram.com") ||
            h.endsWith(".facebook.com") || h.endsWith(".cdninstagram.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Drop the "; wv" marker so Instagram treats this like regular mobile Chrome
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.webChromeClient = WebChromeClient()

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val uri = req.url
                if (isReelUrl(uri) && isInstagram(uri)) return true // block
                if (!isInstagram(uri)) {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (isInstagram(u) && (u.path ?: "").contains("/api/v1/clips/")) {
                    return WebResourceResponse("application/json", "utf-8", 403, "Blocked",
                        emptyMap(), ByteArrayInputStream("{}".toByteArray()))
                }
                return super.shouldInterceptRequest(view, req)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.evaluateJavascript(BLOCK_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(BLOCK_JS, null)
                CookieManager.getInstance().flush() // persist login
            }
        }

        web.loadUrl("https://www.instagram.com/")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    companion object {
        // Instagram's web app is a single-page app, so in-page navigation never hits
        // shouldOverrideUrlLoading. This script guards history changes, clicks, and the DOM.
        private const val BLOCK_JS = """
(function(){
  if (window.__noReels) return; window.__noReels = true;
  var RE = /(^|\/)reels?(\/|${'$'})/;
  function bad(u){ try { return RE.test(new URL(u, location.href).pathname); } catch(e){ return false; } }
  ['pushState','replaceState'].forEach(function(k){
    var o = history[k];
    history[k] = function(s,t,u){ if (u && bad(u)) return; return o.apply(this, arguments); };
  });
  document.addEventListener('click', function(e){
    var a = e.target.closest && e.target.closest('a');
    if (a && bad(a.href)) { e.preventDefault(); e.stopPropagation(); }
  }, true);
  function hide(el){ el.style.setProperty('display','none','important'); }
  function sweep(){
    if (bad(location.href)) { location.replace('/'); return; }
    document.querySelectorAll('a[href*="/reel/"], a[href*="/reels"]').forEach(function(a){
      hide(a.closest('article') || a);
    });
  }
  setInterval(sweep, 500);
  new MutationObserver(sweep).observe(document.documentElement, {childList:true, subtree:true});
  sweep();
})();
"""
    }
}
