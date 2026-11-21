package com.alibaba.mnnllm.android.modelist

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class GgufModelLibraryActivity : AppCompatActivity() {
    private lateinit var modelList: LinearLayout
    private lateinit var summary: TextView

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            importFolder(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshModels()
    }

    override fun onResume() {
        super.onResume()
        if (::modelList.isInitialized) refreshModels()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        page.addView(TextView(this).apply {
            text = "GGUF 模型库"
            textSize = 22f
        })
        page.addView(TextView(this).apply {
            text = "已导入的模型保存在本机应用目录中，不会上传。"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        })
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(this@GgufModelLibraryActivity).apply {
                text = "导入 GGUF 文件"
                setOnClickListener { filePicker.launch(arrayOf("*/*")) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@GgufModelLibraryActivity).apply {
                text = "导入模型文件夹"
                setOnClickListener { folderPicker.launch(null) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        summary = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(10), 0, dp(8))
        }
        page.addView(summary)
        modelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(ScrollView(this).apply {
            addView(modelList)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(page)
    }

    private fun modelRoot(): File =
        File(getExternalFilesDir(null) ?: filesDir, "gguf_models")

    private fun refreshModels() {
        if (!::modelList.isInitialized) return
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                LocalGgufModelLibrary.scan(modelRoot())
            }
            render(entries)
        }
    }

    private fun render(entries: List<LocalGgufModelLibrary.Entry>) {
        modelList.removeAllViews()
        summary.text = "共导入 ${entries.size} 个模型"
        if (entries.isEmpty()) {
            modelList.addView(TextView(this).apply {
                text = "还没有导入 GGUF 模型。\n可使用上方按钮导入单个文件或完整分片文件夹。"
                textSize = 15f
                setPadding(0, dp(20), 0, dp(20))
            })
            return
        }

        entries.forEach { entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            }
            card.addView(TextView(this).apply {
                text = entry.displayName
                textSize = 17f
            })
            val partText = if (entry.files.size > 1) "分片：${entry.files.size} 个文件" else "单文件模型"
            val stateText = if (entry.isComplete) "完整" else "分片不完整，暂不能加载"
            card.addView(TextView(this).apply {
                text = "${formatSize(entry.totalBytes)} · $partText · $stateText"
                textSize = 12f
                setPadding(0, dp(4), 0, dp(8))
            })
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Button(this@GgufModelLibraryActivity).apply {
                    text = "加载"
                    isEnabled = entry.isComplete
                    setOnClickListener { openModel(entry) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Button(this@GgufModelLibraryActivity).apply {
                    text = "删除"
                    setOnClickListener { confirmDelete(entry) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            modelList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun openModel(entry: LocalGgufModelLibrary.Entry) {
        if (!entry.isComplete || !entry.primaryFile.isFile) {
            Toast.makeText(this, "模型文件不完整，无法加载。", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent(this, GgufChatActivity::class.java)
                .putExtra(GgufChatActivity.EXTRA_MODEL_PATH, entry.primaryFile.absolutePath)
        )
    }

    private fun confirmDelete(entry: LocalGgufModelLibrary.Entry) {
        AlertDialog.Builder(this)
            .setTitle("删除模型")
            .setMessage("确定删除“${entry.displayName}”及其 ${formatSize(entry.totalBytes)} 本地文件吗？此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val historyCleared = withContext(Dispatchers.IO) {
                            LocalGgufModelLibrary.delete(modelRoot(), entry.folder)
                            GgufChatHistoryStore.clear(
                                GgufChatHistoryStore.fileFor(filesDir, entry.primaryFile)
                            )
                        }
                        val message = if (historyCleared) "模型及其聊天记录已删除。"
                            else "模型已删除，但聊天记录文件未能清理。"
                        Toast.makeText(this@GgufModelLibraryActivity, message, Toast.LENGTH_SHORT).show()
                        refreshModels()
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@GgufModelLibraryActivity,
                            "删除失败：" + (e.message ?: e.javaClass.simpleName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun importFile(uri: Uri) {
        val name = queryDisplayName(uri)
        val cancellation = CancellationSignal()
        val progress = showImportProgress(cancellation)
        lifecycleScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    LocalGgufModelImporter.copyFromUri(
                        this@GgufModelLibraryActivity, uri, name, cancellation
                    ) { copied, total -> updateImportProgress(progress, copied, total) }
                }
                Toast.makeText(this@GgufModelLibraryActivity, "已导入：${imported.name}", Toast.LENGTH_LONG).show()
                refreshModels()
            } catch (_: OperationCanceledException) {
                Toast.makeText(this@GgufModelLibraryActivity, "已取消导入，未保留不完整文件。", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@GgufModelLibraryActivity,
                    "导入失败：" + (e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                if (progress.isShowing) progress.dismiss()
            }
        }
    }

    private fun importFolder(uri: Uri) {
        val cancellation = CancellationSignal()
        val progress = showImportProgress(cancellation)
        lifecycleScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    LocalGgufModelImporter.copyFromTree(
                        this@GgufModelLibraryActivity, uri, cancellation
                    ) { copied, total -> updateImportProgress(progress, copied, total) }
                }
                if (imported.isEmpty()) {
                    Toast.makeText(this@GgufModelLibraryActivity, "文件夹内没有 GGUF 文件。", Toast.LENGTH_LONG).show()
                } else {
                    val message = if (imported.size == 1) imported.single().name
                        else "已导入 ${imported.size} 个模型"
                    Toast.makeText(this@GgufModelLibraryActivity, message, Toast.LENGTH_LONG).show()
                    refreshModels()
                }
            } catch (_: OperationCanceledException) {
                Toast.makeText(this@GgufModelLibraryActivity, "已取消导入，未保留不完整文件。", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@GgufModelLibraryActivity,
                    "导入失败：" + (e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                if (progress.isShowing) progress.dismiss()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
    }

    private fun showImportProgress(cancellation: CancellationSignal): ProgressDialog =
        ProgressDialog(this).apply {
            setTitle("导入本地模型")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            isIndeterminate = true
            setMessage("正在复制模型文件…")
            setCancelable(true)
            setCanceledOnTouchOutside(false)
            setOnCancelListener { cancellation.cancel() }
            show()
        }

    private fun updateImportProgress(dialog: ProgressDialog, copied: Long, total: Long) {
        runOnUiThread {
            if (!dialog.isShowing) return@runOnUiThread
            if (total > 0L) {
                dialog.isIndeterminate = false
                dialog.progress = ((copied.coerceAtMost(total) * 100L) / total).toInt()
                dialog.setMessage("已复制 ${formatSize(copied)} / ${formatSize(total)}，可以取消")
            } else {
                dialog.isIndeterminate = true
                dialog.setMessage("已复制 ${formatSize(copied)}，正在读取文件…")
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unit = 0
        while (size >= 1024.0 && unit < units.lastIndex) {
            size /= 1024.0
            unit++
        }
        return String.format(Locale.ROOT, "%.2f %s", size, units[unit])
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
