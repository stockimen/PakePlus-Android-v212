package com.app.pakeplus

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var sharedPrefs: SharedPreferences

    // 文件上传相关变量
    private var uploadMessage: ValueCallback<Array<Uri>>? = null
    private var filePathCallbackLegacy: ValueCallback<Uri>? = null
    private val fileChooserResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleFileChooserResult(result.resultCode, result.data)
    }

    // 拍照相关变量
    private var cameraImageUri: Uri? = null
    private val takePictureResultLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraImageUri?.let { uri ->
                // 处理拍照结果
                handleCameraResult(uri)
            }
        } else {
            // 拍照失败
            uploadMessage?.onReceiveValue(null)
            uploadMessage = null
            filePathCallbackLegacy?.onReceiveValue(null)
            filePathCallbackLegacy = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.single_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.ConstraintLayout))
        { view, insets ->
            val systemBar = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBar.left, systemBar.top, systemBar.right, systemBar.bottom)
            insets
        }

        // 初始化SharedPreferences
        sharedPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        webView = findViewById<WebView>(R.id.webview)

        webView.settings.apply {
            javaScriptEnabled = true       // 启用JS
            domStorageEnabled = true       // 启用DOM存储
            allowFileAccess = true         // 允许文件访问
            allowContentAccess = true      // 允许内容访问
            allowFileAccessFromFileURLs = true  // 允许文件URL访问
            allowUniversalAccessFromFileURLs = true  // 允许通用文件URL访问
            setSupportMultipleWindows(true)
            loadsImagesAutomatically = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // 缓存配置
            databaseEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        webView.settings.loadWithOverviewMode = true
        webView.settings.setSupportZoom(false)

        // 缓存逻辑
        val isFirstRun = sharedPrefs.getBoolean("is_first_run", true)
        if (isFirstRun) {
            webView.clearCache(true)
            sharedPrefs.edit().putBoolean("is_first_run", false).apply()
            Toast.makeText(this, "首次运行，缓存已清理", Toast.LENGTH_SHORT).show()
        } else {
            webView.settings.cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        // inject js
        webView.webViewClient = MyWebViewClient()

        // get web load progress
        webView.webChromeClient = MyChromeClient()

        // Setup gesture detector
        gestureDetector =
            GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false

                    val diffX = e2.x - e1.x
                    val diffY = e2.y - e1.y

                    // Only handle horizontal swipes
                    if (Math.abs(diffX) > Math.abs(diffY)) {
                        if (Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                            if (diffX > 0) {
                                // Swipe right - go back
                                if (webView.canGoBack()) {
                                    webView.goBack()
                                    return true
                                }
                            } else {
                                // Swipe left - go forward
                                if (webView.canGoForward()) {
                                    webView.goForward()
                                    return true
                                }
                            }
                        }
                    }
                    return false
                }
            })

        // Set touch listener for WebView
        webView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        webView.loadUrl("https://juejin.cn/")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // 应用恢复时恢复缓存设置
    override fun onResume() {
        super.onResume()
        if (!sharedPrefs.getBoolean("is_first_run", true)) {
            webView.settings.cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
    }

    // 处理文件选择结果
    private fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        if (uploadMessage == null && filePathCallbackLegacy == null) return

        var results: Array<Uri>? = null

        if (resultCode == RESULT_OK) {
            if (data != null) {
                val dataString = data.dataString
                val clipData = data.clipData

                if (clipData != null) {
                    // 多文件选择
                    results = Array(clipData.itemCount) { i ->
                        clipData.getItemAt(i).uri
                    }
                } else if (dataString != null) {
                    // 单文件选择
                    results = arrayOf(Uri.parse(dataString))
                }
            }
        }

        // 处理新版本API的文件选择回调
        uploadMessage?.onReceiveValue(results)
        uploadMessage = null

        // 处理旧版本API的文件选择回调
        filePathCallbackLegacy?.onReceiveValue(results?.get(0))
        filePathCallbackLegacy = null
    }

    // 处理拍照结果
    private fun handleCameraResult(uri: Uri) {
        try {
            // 处理新版本API的文件选择回调
            uploadMessage?.onReceiveValue(arrayOf(uri))
            uploadMessage = null

            // 处理旧版本API的文件选择回调
            filePathCallbackLegacy?.onReceiveValue(uri)
            filePathCallbackLegacy = null
        } catch (e: Exception) {
            Log.e("CameraResult", "处理拍照结果失败: ${e.message}")
            uploadMessage?.onReceiveValue(null)
            uploadMessage = null
            filePathCallbackLegacy?.onReceiveValue(null)
            filePathCallbackLegacy = null
        }
    }

    // 创建图片文件
    @Throws(IOException::class)
    private fun createImageFile(): File {
        // 创建唯一的文件名
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            imageFileName, /* prefix */
            ".jpg", /* suffix */
            storageDir /* directory */
        )
    }

    // 打开文件选择器（添加拍照选项）
    private fun openFileChooser(params: WebChromeClient.FileChooserParams?) {
        // 创建拍照Intent
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val photoFile: File? = try {
            createImageFile()
        } catch (ex: IOException) {
            Log.e("FileChooser", "创建图片文件失败", ex)
            null
        }

        photoFile?.let {
            // 使用FileProvider获取安全的Uri
            cameraImageUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                it
            )
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
        }

        // 创建文件选择Intent
        val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"  // 允许所有文件类型

            // 如果指定了MIME类型，使用指定的类型
            params?.let { chooserParams ->
                if (chooserParams.acceptTypes.isNotEmpty()) {
                    type = chooserParams.acceptTypes[0] ?: "*/*"
                }

                // 是否允许多选
                if (chooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
        }

        // 创建选择器Intent，包含拍照和文件选择选项
        val chooserIntent = Intent.createChooser(contentSelectionIntent, params?.title ?: "选择文件")

        // 添加拍照选项
        if (takePictureIntent.resolveActivity(packageManager) != null) {
            chooserIntent.putExtra(
                Intent.EXTRA_INITIAL_INTENTS,
                arrayOf(takePictureIntent)
            )
        }

        try {
            fileChooserResultLauncher.launch(chooserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器: ${e.message}", Toast.LENGTH_LONG).show()
            uploadMessage?.onReceiveValue(null)
            uploadMessage = null
            filePathCallbackLegacy?.onReceiveValue(null)
            filePathCallbackLegacy = null
        }
    }

    inner class MyWebViewClient : WebViewClient() {

        // vConsole debug
        private var debug = false

        @Deprecated("Deprecated in Java", ReplaceWith("false"))
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            return false
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            println("webView onReceivedError: ${error?.description}")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (debug) {
                // vConsole
                val vConsole = assets.open("vConsole.js").bufferedReader().use { it.readText() }
                val openDebug = """var vConsole = new window.VConsole()"""
                view?.evaluateJavascript(vConsole + openDebug, null)
            }
            // inject js
            val injectJs = assets.open("custom.js").bufferedReader().use { it.readText() }
            view?.evaluateJavascript(injectJs, null)
        }
    }

    inner class MyChromeClient : WebChromeClient() {

        // 处理Android 5.0+的文件上传
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            uploadMessage = filePathCallback
            openFileChooser(fileChooserParams)
            return true
        }

        // 处理Android 4.1-4.4的文件上传（兼容旧版本）
        @Suppress("DEPRECATION")
        fun openFileChooser(uploadMsg: ValueCallback<Uri>?) {
            filePathCallbackLegacy = uploadMsg
            openFileChooser(null)
        }

        @Suppress("DEPRECATION")
        fun openFileChooser(uploadMsg: ValueCallback<Uri>?, acceptType: String?) {
            filePathCallbackLegacy = uploadMsg
            openFileChooser(null)
        }

        @Suppress("DEPRECATION")
        fun openFileChooser(uploadMsg: ValueCallback<Uri>?, acceptType: String?, capture: String?) {
            filePathCallbackLegacy = uploadMsg
            openFileChooser(null)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            val url = view?.url
            println("web view url:$url")
        }
    }
}