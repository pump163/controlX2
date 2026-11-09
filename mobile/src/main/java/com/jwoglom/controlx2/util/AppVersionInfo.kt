package com.jwoglom.controlx2.util

import android.content.Context
import android.content.Context.MODE_PRIVATE
import com.google.common.base.Strings
import com.jwoglom.controlx2.BuildConfig
import hu.supercluster.paperwork.Paperwork
import org.json.JSONObject
import java.util.UUID

class AppVersionInfo(val context: Context) {
    val version: String
        get() = BuildConfig.VERSION_NAME

    // 反射读取：BuildConfig 字段是编译期常量，直接引用会被内联成字面量，
    // 而 Kotlin 增量编译不跟踪常量值变化，改 pumpx2_version 后本文件不会重编，版本号会停在旧值
    val pumpX2: String
        get() = runCatching {
            com.jwoglom.pumpx2.BuildConfig::class.java.getField("PUMPX2_VERSION").get(null) as String
        }.getOrDefault("?")

    val pumpX2BuildTime: String
        get() = runCatching {
            com.jwoglom.pumpx2.BuildConfig::class.java.getField("PUMPX2_BUILD_TIME").get(null) as String
        }.getOrDefault("?")

    val buildVersion: String
        get() = Paperwork(context).get("build_version")

    val buildTime: String
        get() = Paperwork(context).get("build_time")

    fun toJsonObject(): JSONObject {
        val o = JSONObject()
        o.put("version", version)
        o.put("pumpX2", pumpX2)
        o.put("pumpX2BuildTime", pumpX2BuildTime)
        o.put("buildVersion", buildVersion)
        o.put("buildTime", buildTime)

        return o
    }

}