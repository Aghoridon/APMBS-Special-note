package com.example.marknote

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSaveBytes: ByteArray? = null
    private var isPageLoaded = false
    private var pendingJsCall: String? = null

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val results = if (uri != null) arrayOf(uri) else null
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    private val createDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { out ->
                    pendingSaveBytes?.let { bytes -> out.write(bytes) }
                }
                Toast.makeText(this, "फ़ाइल सेव हो गई!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "सेव त्रुटि: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingSaveBytes = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    isPageLoaded = true
                    pendingJsCall?.let { js ->
                        evaluateJavascript(js, null)
                        pendingJsCall = null
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    openFileLauncher.launch(arrayOf("*/*"))
                    return true
                }
            }

            addJavascriptInterface(WebAppBridge(), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setContentView(webView)
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action

        if (action == Intent.ACTION_SEND) {
            val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            if (streamUri != null) {
                dispatchUriToEditor(streamUri)
            } else {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
                if (!sharedText.isNullOrEmpty()) {
                    val js = "window.receiveSharedText(${JSONObject.quote(sharedSubject)}, ${JSONObject.quote(sharedText)});"
                    runOrQueueJs(js)
                }
            }
        } else if (action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                dispatchUriToEditor(uri)
            }
        }
    }

    private fun dispatchUriToEditor(uri: Uri) {
        try {
            var fileName = "Shared_File.md"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex) ?: fileName
                }
            }

            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val js = "window.receiveSharedFile(${JSONObject.quote(fileName)}, ${JSONObject.quote(b64)});"
            runOrQueueJs(js)
        } catch (e: Exception) {
            Toast.makeText(this, "फ़ाइल पढ़ने में त्रुटि: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runOrQueueJs(js: String) {
        if (isPageLoaded) {
            webView.evaluateJavascript(js, null)
        } else {
            pendingJsCall = js
        }
    }

    inner class WebAppBridge {

        @JavascriptInterface
        fun shareText(title: String, text: String) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(intent, "टेक्स्ट शेयर करें"))
            }
        }

        @JavascriptInterface
        fun shareFile(base64Data: String, fileName: String, mimeType: String) {
            runOnUiThread {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val exportDir = File(cacheDir, "exports").apply { if (!exists()) mkdirs() }
                    val outFile = File(exportDir, fileName)
                    FileOutputStream(outFile).use { it.write(bytes) }

                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "$packageName.fileprovider",
                        outFile
                    )

                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "फ़ाइल शेयर करें"))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "शेयर त्रुटि: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun saveFile(base64Data: String, fileName: String, mimeType: String) {
            runOnUiThread {
                pendingSaveBytes = Base64.decode(base64Data, Base64.DEFAULT)
                createDocLauncher.launch(fileName)
            }
        }

        @JavascriptInterface
        fun exportPdf(title: String, content: String, actionMode: String) {
            runOnUiThread {
                try {
                    val pdfBytes = buildPdfBytes(title, content)
                    val fileName = "$title.pdf"
                    if (actionMode == "share") {
                        val b64 = Base64.encodeToString(pdfBytes, Base64.NO_WRAP)
                        shareFile(b64, fileName, "application/pdf")
                    } else {
                        pendingSaveBytes = pdfBytes
                        createDocLauncher.launch(fileName)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "PDF त्रुटि: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun buildPdfBytes(title: String, content: String): ByteArray {
        val pdfDocument = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 48
        val contentWidth = pageWidth - (margin * 2)

        val fullText = if (title.isNotBlank()) "$title\n\n$content" else content
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 13f
        }

        val layout = StaticLayout.Builder
            .obtain(fullText, 0, fullText.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(4f, 1.15f)
            .build()

        val usableHeight = pageHeight - (margin * 2)
        var currentLine = 0
        var pageNum = 1

        while (currentLine < layout.lineCount) {
            val page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
            val startY = layout.getLineTop(currentLine)
            var endLine = currentLine
            while (endLine < layout.lineCount && (layout.getLineBottom(endLine) - startY) <= usableHeight) {
                endLine++
            }
            if (endLine == currentLine) endLine++

            page.canvas.save()
            page.canvas.translate(margin.toFloat(), (margin - startY).toFloat())
            page.canvas.clipRect(0, startY, contentWidth, startY + usableHeight)
            layout.draw(page.canvas)
            page.canvas.restore()

            pdfDocument.finishPage(page)
            currentLine = endLine
            pageNum++
        }

        if (layout.lineCount == 0) {
            val page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
            pdfDocument.finishPage(page)
        }

        val stream = ByteArrayOutputStream()
        pdfDocument.writeTo(stream)
        pdfDocument.close()
        return stream.toByteArray()
    }
}
