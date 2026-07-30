package com.moe.starflow.manga

/**
 * 所有可下载模型的统一标识。
 *
 * 命名规则：
 * - 单文件模型：单一枚举值（RT_DETR_V2、PP_OCR_V5_DET 等）
 * - 多文件模型：`*_GROUP` 后缀（MANGA_OCR_GROUP、NLLB_GROUP），由 Repository 展开成多个 FileInfo
 *
 * 用作 JSON 配置 `model_key` 字段值（`ModelKey.valueOf(keyStr)`），错误键名会被跳过。
 */
enum class ModelKey {
    /** RT-DETR-V2 漫画气泡/文字检测模型（单文件 ~11MB） */
    RT_DETR_V2,

    /** manga-ocr 识别组（encoder.onnx + decoder.onnx + vocab.txt） */
    MANGA_OCR_GROUP,

    /** PP-OCRv5 文字检测模型（单文件 ~4.6MB） */
    PP_OCR_V5_DET,

    /** PP-OCRv5 中文识别模型（rec_zh.onnx + rec_zh_dict.txt） */
    PP_OCR_V5_REC_ZH,

    /** PP-OCRv5 英文识别模型（rec_en.onnx + rec_en_dict.txt） */
    PP_OCR_V5_REC_EN,

    /** PP-OCRv5 韩文识别模型（rec_ko.onnx + rec_ko_dict.txt） */
    PP_OCR_V5_REC_KO,

    /** PP-OCRv5 俄文识别模型（rec_ru.onnx + rec_ru_dict.txt） */
    PP_OCR_V5_REC_RU,

    /** PP-OCRv6 medium 检测模型（单文件 ~60MB） */
    PP_OCR_V6_MEDIUM_DET,

    /** PP-OCRv6 medium 识别模型（单文件 ~74MB） */
    PP_OCR_V6_MEDIUM_REC,

    /** NLLB 翻译模型组（NLLB_encoder.onnx + NLLB_decoder.onnx + sentencepiece_bpe.model） */
    NLLB_GROUP
}