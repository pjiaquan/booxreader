package com.example.booxreader.data.remote

object HttpConfig {

    // TODO: 換成你自己的 server 位址
    // 例如: http://192.168.0.10:8080/bookmarks
    const val BOOKMARK_ENDPOINT: String =
        "https://node-ajil.risc-v.tw/boox-update"

    // ✅ 閱讀進度上報
    const val PROGRESS_ENDPOINT: String =
        "https://node-ajil.risc-v.tw/boox-progress"

    // ✅ 選取文字發佈
    const val TEXT_AI_ENDPOINT: String =
        "https://node-ajil.risc-v.tw/boox-text-ai"
}