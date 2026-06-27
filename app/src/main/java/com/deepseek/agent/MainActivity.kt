package com.deepseek.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val DEEPSEEK_URL = "https://chat.deepseek.com"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    // 文件选择回调
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoPath: String? = null

    // 权限请求
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            if (!cameraGranted && filePathCallback != null) {
                // 权限被拒绝，取消文件选择
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
        }

    // 拍照结果
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraPhotoPath != null) {
                val uri = Uri.fromFile(File(cameraPhotoPath!!))
                filePathCallback?.onReceiveValue(arrayOf(uri))
            } else {
                filePathCallback?.onReceiveValue(null)
            }
            filePathCallback = null
        }

    // 文件选择结果
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            var results: Array<Uri>? = null

            if (result.resultCode == RESULT_OK) {
                if (data != null) {
                    val dataString = data.dataString
                    val clipData = data.clipData
                    if (clipData != null) {
                        results = Array(clipData.itemCount) { clipData.getItemAt(it).uri }
                    } else if (dataString != null) {
                        results = arrayOf(Uri.parse(dataString))
                    }
                }
                // 如果没有从 data 获取到，检查相机路径
                if (results == null && cameraPhotoPath != null) {
                    results = arrayOf(Uri.fromFile(File(cameraPhotoPath!!)))
                }
            }

            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        setupWebView()
        loadDeepSeek()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings

        // 基础设置
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setGeolocationEnabled(true)

        // 缓存策略
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setAppCacheEnabled(true)

        // 混合内容（允许 HTTPS 页面加载 HTTP 资源）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // 设置 User-Agent 模拟移动端 Chrome
        settings.userAgentString = settings.userAgentString +
            " DeepSeekAgent/1.0"

        // 强制深色模式（跟随系统，如 WebView 支持）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
        }
        // 算法深色模式
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        }

        // WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // 拦截第三方链接，用外部浏览器打开
                if (!url.startsWith(DEEPSEEK_URL) &&
                    !url.startsWith("https://accounts.google.com") &&
                    !url.startsWith("https://cdn.deepseek.com") &&
                    !url.startsWith("javascript:")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                    }
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // 只在主页面加载失败时显示提示
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "无法连接到 DeepSeek，请检查网络",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // WebChromeClient — 处理文件选择、进度等
        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 90) {
                    progressBar.visibility = View.GONE
                }
            }

            // 处理文件选择器（Android 5.0+）
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (this@MainActivity.filePathCallback != null) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                }
                this@MainActivity.filePathCallback = filePathCallback

                // 检查相机权限
                val hasCameraPermission = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                // 构建选择器
                val captureIntent: Intent? = if (fileChooserParams?.isCaptureEnabled == true
                    && hasCameraPermission) {
                    createCaptureIntent()
                } else {
                    null
                }

                val contentIntent = Intent(Intent.ACTION_GET_CONTENT)
                contentIntent.addCategory(Intent.CATEGORY_OPENABLE)
                contentIntent.type = "*/*"
                contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

                val chooserIntent = if (captureIntent != null) {
                    Intent.createChooser(contentIntent, "选择文件")
                        .apply { putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent)) }
                } else {
                    contentIntent
                }

                try {
                    fileChooserLauncher.launch(chooserIntent)
                } catch (e: Exception) {
                    filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    return false
                }

                return true
            }
        }
    }

    private fun createCaptureIntent(): Intent? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            val imageFileName = "JPEG_${timeStamp}_"
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val imageFile = File.createTempFile(imageFileName, ".jpg", storageDir)
            cameraPhotoPath = imageFile.absolutePath

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                imageFile
            )
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
            }
        } catch (e: IOException) {
            cameraPhotoPath = null
            null
        }
    }

    private fun loadDeepSeek() {
        webView.loadUrl(DEEPSEEK_URL)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        // 清理 WebView
        webView.apply {
            stopLoading()
            settings.javaScriptEnabled = false
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }
}
