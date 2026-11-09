package com.pumppan.controlx2.diag

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.jwoglom.controlx2.shared.diag.DiagMonitor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val MIME_ZIP = "application/zip"
private const val MAX_EXPORT_ZIPS = 5

private fun stamp(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

/**
 * 把现场整包导出为 zip：incident 原文（含 protected）、live、状态快照、可读清单。
 * 导出成功后写 export-marker.json；清理只删 marker 里列出的、非 protected 的 incident。
 */
fun buildDiagExport(context: Context): File? {
    val incidents = DiagMonitor.incidentFiles()
    val live = DiagMonitor.liveFile()
    if (incidents.isEmpty() && live == null) return null

    val outDir = File(context.filesDir, "diag-export").also { it.mkdirs() }
    outDir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(MAX_EXPORT_ZIPS - 1)?.forEach { it.delete() }

    val now = System.currentTimeMillis()
    val name = "diag-bundle-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))}.zip"
    val zip = File(outDir, name)
    val normalNames = mutableListOf<String>()
    val incidentEntries = JSONArray()
    var rangeStart = Long.MAX_VALUE
    var rangeEnd = 0L

    runCatching {
        ZipOutputStream(FileOutputStream(zip, false)).use { zos ->
            incidents.forEach { f ->
                val protected = DiagMonitor.isProtectedIncident(f)
                val entry = if (protected) "incident/protected/${f.name}" else "incident/${f.name}"
                zos.putNextEntry(ZipEntry(entry))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()

                if (!protected) normalNames.add(f.name)
                if (f.lastModified() in 1 until rangeStart) rangeStart = f.lastModified()
                if (f.lastModified() > rangeEnd) rangeEnd = f.lastModified()

                val trigger = runCatching {
                    JSONObject(f.readText()).optString("trigger")
                }.getOrDefault("")
                incidentEntries.put(JSONObject().apply {
                    put("entry", entry)
                    put("name", f.name)
                    put("bytes", f.length())
                    put("modified", f.lastModified())
                    put("trigger", trigger)
                    put("protected", protected)
                })
            }

            live?.let { f ->
                zos.putNextEntry(ZipEntry("live.jsonl"))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            val state = DiagMonitor.snapshotJson()
            zos.putNextEntry(ZipEntry("state.json"))
            zos.write(state.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val summary = DiagMonitor.exportSummary()
            zos.putNextEntry(ZipEntry("summary.txt"))
            zos.write(summary.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val readable = buildManifestText(now, incidents, live, state, summary, incidentEntries, rangeStart, rangeEnd)
            zos.putNextEntry(ZipEntry("manifest.txt"))
            zos.write(readable.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val manifestJson = JSONObject().apply {
                put("exportedAt", stamp(now))
                put("exportedAtMs", now)
                put("rangeStart", if (rangeStart == Long.MAX_VALUE) "" else stamp(rangeStart))
                put("rangeEnd", if (rangeEnd == 0L) "" else stamp(rangeEnd))
                put("incidents", incidentEntries)
                put("liveEntry", live?.let { "live.jsonl" } ?: "")
            }
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestJson.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }.onFailure {
        zip.delete()
        return null
    }

    DiagMonitor.recordExportedIncidents(normalNames)
    return zip
}

fun shareDiagExport(context: Context, zip: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName, zip)
    val send = Intent(Intent.ACTION_SEND)
        .setType(MIME_ZIP)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "PumpX2 断连诊断包 ${zip.name}")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(send, "发送诊断包")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

private fun buildManifestText(
    now: Long,
    incidents: List<File>,
    live: File?,
    stateJson: String,
    summary: String,
    entries: JSONArray,
    rangeStart: Long,
    rangeEnd: Long,
): String = buildString {
    appendLine("PumpX2 断连诊断导出")
    appendLine("导出时间: ${stamp(now)}")
    appendLine("incident 时间范围: ${if (rangeStart == Long.MAX_VALUE) "无" else stamp(rangeStart)} ~ ${if (rangeEnd == 0L) "无" else stamp(rangeEnd)}")
    appendLine("incident 数量: ${incidents.size}（protected ${incidents.count { DiagMonitor.isProtectedIncident(it) }}）")
    appendLine()
    appendLine("文件清单：")
    for (i in 0 until entries.length()) {
        val o = entries.getJSONObject(i)
        appendLine(
            "  ${o.optString("entry")}  ${o.optLong("bytes")} bytes  " +
                "trigger=${o.optString("trigger")}  " +
                "protected=${o.optBoolean("protected")}  " +
                "mtime=${stamp(o.optLong("modified"))}"
        )
    }
    live?.let { appendLine("  live.jsonl  ${it.length()} bytes") }
    appendLine("  state.json  ${stateJson.length} chars")
    appendLine("  summary.txt  ${summary.length} chars")
    appendLine("  manifest.json / manifest.txt")
    appendLine()
    appendLine("说明：清理只会删除本清单中非 protected 的 incident；protected 与 live/state 永不自动清理。")
}
