package com.moe.starflow.manga.engine
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * manga-ocr Tokenizer
 *
 * 加载 vocab.txt，实现 token IDs 与文字的转换。
 * 用于将 manga-ocr 的 token IDs 转换为文字。
 */
class MangaOcrTokenizer(private val context: Context) {

    private lateinit var idToToken: Map<Int, String>
    private lateinit var tokenToId: Map<String, Int>

    // 特殊 token
    private val bosTokenId = 2  // [CLS]
    private val eosTokenId = 3  // [SEP]
    private val padTokenId = 0  // [PAD]

    /**
     * 从 assets 加载 tokenizer
     */
    fun loadFromAssets(assetDir: String = "manga_ocr") {
        val vocabText = loadAssetText("$assetDir/vocab.txt")
        parseVocab(vocabText)
    }

    /**
     * 从文件加载 tokenizer
     */
    fun loadFromFile(vocabFile: File) {
        val vocabText = vocabFile.readText(Charsets.UTF_8)
        parseVocab(vocabText)
    }

    private fun parseVocab(vocabText: String) {
        val idMap = HashMap<Int, String>()
        val tokenMap = HashMap<String, Int>()

        vocabText.lines().forEachIndexed { index, token ->
            val trimmed = token.trim()
            if (trimmed.isNotEmpty()) {
                idMap[index] = trimmed
                tokenMap[trimmed] = index
            }
        }

        idToToken = idMap
        tokenToId = tokenMap
    }

    /**
     * 将 token IDs 解码为文字
     */
    fun decode(tokenIds: List<Int>): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            if (id == bosTokenId || id == eosTokenId || id == padTokenId) continue
            val token = idToToken[id] ?: continue
            // 处理 BPE 的 ## 前缀（子词标记）
            if (token.startsWith("##")) {
                sb.append(token.substring(2))
            } else {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(token)
            }
        }
        return sb.toString()
            .replace(" ##", "").replace("##", "")
            .replace(" ", "")  // 日文竖排逐字 token 间被 BPE 插入空格，去掉
            .trim()
    }

    /**
     * 将文字编码为 token IDs
     */
    fun encode(text: String): List<Int> {
        val tokens = mutableListOf<Int>()
        tokens.add(bosTokenId)
        for (char in text) {
            val token = char.toString()
            val id = tokenToId[token]
            if (id != null) {
                tokens.add(id)
            }
        }
        tokens.add(eosTokenId)
        return tokens
    }

    fun getBosTokenId(): Int = bosTokenId
    fun getEosTokenId(): Int = eosTokenId

    private fun loadAssetText(path: String): String {
        val inputStream = context.assets.open(path)
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        return reader.use { it.readText() }
    }
}
