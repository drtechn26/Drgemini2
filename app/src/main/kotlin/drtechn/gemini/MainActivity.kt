package drtechn.gemini

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    // File Chooser Launcher
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (fileChooserCallback == null) return@registerForActivityResult
        
        var results: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            if (cameraImageUri != null) {
                // Return foto hasil kamera
                results = arrayOf(cameraImageUri!!)
            } else {
                // Return file dari galeri / file manager
                val dataString = result.data?.dataString
                val clipData = result.data?.clipData
                
                if (clipData != null) {
                    // Limit to 10 files maximum
                    val count = minOf(clipData.itemCount, 10)
                    results = Array(count) { i -> clipData.getItemAt(i).uri }
                } else if (dataString != null) {
                    results = arrayOf(Uri.parse(dataString))
                }
            }
        }
        
        fileChooserCallback?.onReceiveValue(results)
        fileChooserCallback = null
        cameraImageUri = null // Reset cache uri
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start Foreground Service to keep app alive when minimized
        val serviceIntent = Intent(this, WebForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        webView = findViewById(R.id.geminiWebView)
        setupWebView()

        // Modern Back Navigation (Android 13+)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish() // Exit app if no history
                }
            }
        })

        webView.loadUrl("https://gemini.google.com")
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        
        // GPU Rendering Hardware Acceleration
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = WebViewClient()
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (fileChooserCallback != null) {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                }
                fileChooserCallback = filePathCallback

                val isCapture = fileChooserParams?.isCaptureEnabled ?: false
                
                if (isCapture) {
                    // Smart Routing: Camera (Langsung & Aman dari Force Close)
                    try {
                        val photoFile = java.io.File(cacheDir, "images").apply { mkdirs() }
                        val tempFile = java.io.File.createTempFile("IMG_", ".jpg", photoFile)
                        cameraImageUri = androidx.core.content.FileProvider.getUriForFile(
                            this@MainActivity, 
                            "${applicationContext.packageName}.fileprovider", 
                            tempFile
                        )
                        val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                        }
                        fileChooserLauncher.launch(captureIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = null
                    }
                } else {
                    // Smart Routing: Dialog 2 Pilihan
                    val options = arrayOf("Photo Gallery", "Upload Files")
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Select Action")
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> {
                                    // Photo Gallery (Image Only)
                                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "image/*"
                                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                    }
                                    fileChooserLauncher.launch(Intent.createChooser(intent, "Select Photos"))
                                }
                                1 -> {
                                    // Upload Files (All Types)
                                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "*/*"
                                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                    }
                                    fileChooserLauncher.launch(Intent.createChooser(intent, "Select Files"))
                                }
                            }
                        }
                        .setOnCancelListener {
                            fileChooserCallback?.onReceiveValue(null)
                            fileChooserCallback = null
                        }
                        .show()
                }
                return true
            }
        }
    }
    
    override fun onDestroy() {
        // Optional: Stop service when app is fully closed by user
        stopService(Intent(this, WebForegroundService::class.java))
        super.onDestroy()
    }
}

