package com.app.pakeplus

import android.Manifest
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var gestureDetector: GestureDetectorCompat


    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.single_main)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR); // 固定竖屏

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.ConstraintLayout))
        { view, insets ->
            val systemBar = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBar.left, systemBar.top, systemBar.right, systemBar.bottom)
            insets
        }

        webView = findViewById<WebView>(R.id.webview)

        webView.settings.apply {
            javaScriptEnabled = true       // 启用JS
            domStorageEnabled = true       // 启用DOM存储
            allowFileAccess = true         // 允许文件访问
            allowContentAccess = true      // 允许内容访问
            setSupportMultipleWindows(true)
            loadsImagesAutomatically = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // 缓存配置
            databaseEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.settings.loadWithOverviewMode = true
        webView.settings.setSupportZoom(false)

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

        // 下载监听
        webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            var fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            fileName = renameFileName(fileName)
            Log.d("MainActivity", "下载文件：" + url + " " + fileName)

            // 选择下载方式
            // 1、选择默认浏览器下载
            triggerBrowserDownload(url) // 调用上面的下载方法
            // 2、普通下载
//            downloadFile(url, fileName)
            // 3、自定义带有通知的下载
//            downloadFileWithNotification(url, fileName)
        }

//        webView.loadUrl("https://juejin.cn/")
        webView.loadUrl("https://m.190699.xyz/")
        // 检查并申请权限
        checkAndRequestPermissions()
        checkStoragePermission()
    }

    // region 下载文件
    // 普通下载
    private fun downloadFile(url: String, fileName: String) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    // 带有通知的下载
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun downloadFileWithNotification(url: String, fileName: String) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("正在下载 $fileName")
            // 关键设置：显示通知栏进度条（下载中和完成都会显示）
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 可选：设置下载文件类型（影响系统对文件的默认处理方式）
            setMimeType(getMimeType(fileName))
            // 保存到系统下载目录（Android 10+ 无需权限）
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            // 可选：允许在计量网络（如移动数据）下下载
            setAllowedOverMetered(true)
        }

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request) // 返回下载ID可用于后续查询进度

        // 可选：监听下载完成事件（通过BroadcastReceiver）
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        Toast.makeText(context, "下载完成", Toast.LENGTH_SHORT).show()
                        unregisterReceiver(this) // 记得取消注册
                    }
                }
            },
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
    }

    // 浏览器下载
    private fun downloadWithBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                // 强制使用浏览器打开（避免被其他应用拦截）
                setPackage("com.android.chrome") // 可选：指定Chrome，移除则让用户选择
                addCategory(Intent.CATEGORY_BROWSABLE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "未找到浏览器应用", Toast.LENGTH_SHORT).show()
        }
    }

    // 选择默认浏览器下载
    private fun triggerBrowserDownload(url: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            // 明确告诉系统这是下载行为
            putExtra(Intent.EXTRA_TEXT, "download")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    // 重命名，避免冲突
    private fun renameFileName(fileName: String): String {
        val baseName = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")
        val extensionWithDot = if (extension.isNotEmpty()) ".$extension" else ""

        // 使用时间戳确保文件名唯一
        val timeStamp = SimpleDateFormat("mmss", Locale.getDefault()).format(Date())
        val finalFileName = if (extension.isNotEmpty()) {
            "${baseName}_$timeStamp$extensionWithDot"
        } else {
            "${baseName}_$timeStamp"
        }
        return finalFileName
    }

    // 辅助方法：根据文件名猜测MIME类型
    private fun getMimeType(fileName: String): String {
        return when (fileName.substringAfterLast(".").lowercase()) {
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "*/*"
        }
    }
    // endregion

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // region 申请权限
    // 需要申请的权限列表
    private val requiredPermissions = mutableListOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.CAMERA
    ).apply {
        // 添加 Android 10+ 的媒体位置权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }

        // 添加 Android 11+ 的存储管理权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
        }

        // 添加 Android 13+ 的通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // 权限请求结果回调
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handlePermissionResults(permissions)
    }

    /**
     * 检查存储权限（特殊处理）
     */
    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(WRITE_EXTERNAL_STORAGE) != PERMISSION_GRANTED) {
                requestPermissions(arrayOf(WRITE_EXTERNAL_STORAGE), REQUEST_CODE_STORAGE)
            }
        }
    }

    /**
     * 检查并申请所需权限
     */
    private fun checkAndRequestPermissions() {
        // 检查哪些权限尚未授予
        val permissionsToRequest = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            // 对于Android 13+的通知权限，可以先解释为什么需要这个权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                permissionsToRequest.contains(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                requestPermissionLauncher.launch(permissionsToRequest)
            } else {
                // 直接请求缺失的权限
                requestPermissionLauncher.launch(permissionsToRequest)
            }
        } else {
            // 所有权限已授予，继续应用逻辑
            onAllPermissionsGranted()
        }
    }

    /**
     * 处理权限请求结果
     */
    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        val allGranted = permissions.all { it.value }

        if (allGranted) {
            // 所有权限已授予
            onAllPermissionsGranted()
        } else {
            // 处理未授予的权限
            handleDeniedPermissions(permissions)
        }
    }

    /**
     * 所有权限已授予
     */
    private fun onAllPermissionsGranted() {
        // 这里可以初始化需要权限的功能
        Toast.makeText(this, "所有权限已授予", Toast.LENGTH_SHORT).show()
    }

    /**
     * 部分权限已授予
     */
    private fun onPermissionsPartiallyGranted() {
        // 这里可以初始化部分功能
        Toast.makeText(this, "部分权限已授予", Toast.LENGTH_SHORT).show()
    }

    /**
     * 处理被拒绝的权限
     */
    private fun handleDeniedPermissions(permissions: Map<String, Boolean>) {
        val deniedPermissions = permissions.filter { !it.value }.keys

        // 检查是否有权限被永久拒绝（不再询问）
        val permanentlyDenied = deniedPermissions.any { permission ->
            !shouldShowRequestPermissionRationale(permission)
        }

        if (permanentlyDenied) {
            // 显示设置对话框引导用户手动开启权限
//            showPermissionSettingsDialog()
        } else {
            // 部分权限被拒绝，但可以再次请求
            Toast.makeText(
                this,
                "部分权限被拒绝，功能可能受限",
                Toast.LENGTH_SHORT
            ).show()

            // 继续应用逻辑（部分功能可能受限）
            onPermissionsPartiallyGranted()
        }
    }

    /**
     * 显示引导用户去设置开启权限的对话框
     */
    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要权限")
            .setMessage("部分功能需要权限才能使用，请在设置中开启权限")
            .setPositiveButton("去设置") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 打开应用设置页面
     */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
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
    // endregion

    // region 拍照上传
    // 文件上传相关变量
    private var uploadMessage: ValueCallback<Array<Uri>>? = null
    private var filePathCallbackLegacy: ValueCallback<Uri>? = null
    private val fileChooserResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleFileChooserResult(result.resultCode, result.data)
    }

    companion object {
        private const val REQUEST_CODE_STORAGE = 1001  // 可以是任意唯一整数
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

    // 处理拍照结果
    private fun handleCameraResult(uri: Uri) {
        try {
            // 只保留新版API回调
            uploadMessage?.onReceiveValue(arrayOf(uri))
            uploadMessage = null
        } catch (e: Exception) {
            Log.e("CameraResult", "处理拍照结果失败: ${e.message}")
            uploadMessage?.onReceiveValue(null)
            uploadMessage = null
        }
    }

    // 创建图片文件
    @Throws(IOException::class)
    private fun createImageFile(): File {
        // 创建唯一的文件名
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"

        // 获取 /storage/emulated/0/ 路径
        val cameraDir = File(
            Environment.getExternalStorageDirectory(), // 外部存储根目录
            "a_moments"                             // 配置的子路径
        )

        // 检查并创建目录
        if (!cameraDir.exists()) {
            cameraDir.mkdirs()
        } else {
            // 扫描目录并删除0B文件
            cleanZeroByteFiles(cameraDir)
        }

        // 打印路径（调试用）
        Log.d("CameraPath", "绝对路径: ${cameraDir.absolutePath}")

        return File.createTempFile(
            imageFileName, /* prefix */
            ".jpg", /* suffix */
            cameraDir /* directory */
        )
    }

    /**
     * 删除目录中所有大小为0B的文件
     */
    private fun cleanZeroByteFiles(directory: File) {
        if (directory.isDirectory) {
            val files = directory.listFiles()
            files?.forEach { file ->
                if (file.isFile && file.length() == 0L) {
                    try {
                        if (file.delete()) {
                            Log.d("FileUtils", "已删除0B文件: ${file.name}")
                        } else {
                            Log.w("FileUtils", "删除0B文件失败: ${file.name}")
                        }
                    } catch (e: SecurityException) {
                        Log.e("FileUtils", "无权限删除文件: ${file.name}", e)
                    }
                }
            }
        }
    }

    /**
     * 打开文件选择器（添加拍照选项）
     */
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
        val chooserIntent =
            Intent.createChooser(contentSelectionIntent, params?.title ?: "选择文件")

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

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            val url = view?.url
            println("web view url:$url")
        }
    }

    // endregion 拍照上传

    //region 待完善的自定义下载拦截
    /*        // 设置能捕获请求的WebViewClient （放在 onCreate()）
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (isDownloadRequest(request)) {
                    handleWebViewDownload(request)
                    return null // 或返回自定义响应
                }
                return super.shouldInterceptRequest(view, request)
            }
        }*/
    private fun isDownloadRequest(request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        val headers = request.requestHeaders

        return when {
            url.contains("download=true") -> true
            headers["Content-Disposition"]?.contains("attachment") == true -> true
            listOf(".pdf", ".apk", ".zip").any { url.endsWith(it) } -> true
            else -> false
        }
    }

    private fun handleWebViewDownload(request: WebResourceRequest) {
        val url = request.url.toString()
        val headers = request.requestHeaders.toMutableMap()

        // 确保包含Cookie
        if (!headers.containsKey("Cookie")) {
            CookieManager.getInstance().getCookie(url)?.let { cookies ->
                headers["Cookie"] = cookies
            }
        }

        // 选择下载方式
        downloadWithHeaders(url, headers) // 使用DownloadManager
    }

    private fun downloadWithHeaders(url: String, originalHeaders: Map<String, String>) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            // 设置通知栏显示
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setTitle("下载文件")
            setDescription(url.substringAfterLast("/"))

            // 添加原始请求头
            originalHeaders.forEach { (key, value) ->
                addRequestHeader(key, value)
            }

            // 特别处理Cookie
            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrEmpty()) {
                addRequestHeader("Cookie", cookies)
            }
        }

        // 设置下载路径
        val fileName = URLUtil.guessFileName(url, null, null)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

        // 开始下载
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)
    }
    //endregion

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
}