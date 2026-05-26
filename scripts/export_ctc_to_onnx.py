#!/usr/bin/env python3
"""
CTC (48px_ctc) .ckpt → .onnx 转换脚本

将 manga-image-translator 的 ocr-ctc.ckpt 转换为 ONNX 格式。

依赖:
    pip install torch onnx onnxscript

使用方法:
    python scripts/export_ctc_to_onnx.py

输出:
    model.onnx           - CTC 模型（支持动态宽度）
    alphabet-all-v5.txt  - 字符表
"""

import sys
from pathlib import Path

import torch
import torch.nn as nn
from torch import Tensor
import onnx
from onnx.external_data_helper import convert_model_to_external_data

# 路径配置
SCRIPT_DIR = Path(__file__).parent
REPO_ROOT = SCRIPT_DIR.parent
MODEL_DIR = REPO_ROOT / ".reference" / "models"
OUTPUT_DIR = REPO_ROOT / "tools" / "ctc_onnx"
CHECKPOINT_PATH = MODEL_DIR / "ocr-ctc.ckpt"
ALPHABET_PATH = MODEL_DIR / "alphabet-all-v5.txt"

OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


class CtcEncoder(nn.Module):
    """CTC Encoder: ResNet + Transformer Encoder + CTC Head

    输入: [batch, 3, 48, width]  RGB 图片，归一化到 [-1, 1]
    输出: char_logits [seqLen, batch, dictSize]
          color_values [seqLen, batch, 6] (前景色 + 背景色)
    """

    def __init__(self, encoder, ctc_head):
        super().__init__()
        self.encoder = encoder
        self.ctc_head = ctc_head

    def forward(self, images: Tensor) -> tuple[Tensor, Tensor]:
        """
        @param images: [batch, 3, 48, width] float32, 归一化到 [-1, 1]
        @return (char_logits, color_values)
            char_logits: [seqLen, batch, dictSize]
            color_values: [seqLen, batch, 6]
        """
        features = self.encoder(images)  # [batch, seqLen, hiddenSize]
        char_logits, color_values = self.ctc_head(features)
        return char_logits, color_values


def load_ctc_model(checkpoint_path: Path) -> nn.Module:
    """加载 CTC 模型"""
    print(f"加载 checkpoint: {checkpoint_path}")
    checkpoint = torch.load(checkpoint_path, map_location="cpu")

    # 尝试不同的 key 名称
    state_dict = None
    if "model" in checkpoint:
        state_dict = checkpoint["model"]
    elif "state_dict" in checkpoint:
        state_dict = checkpoint["state_dict"]
    elif "model_state_dict" in checkpoint:
        state_dict = checkpoint["model_state_dict"]
    else:
        # 直接用 checkpoint 本身作为 state_dict
        state_dict = checkpoint

    # 打印 key 前几个
    keys = list(state_dict.keys())[:10]
    print(f"  找到 {len(state_dict)} 个权重 keys, 示例: {keys}")

    # 尝试查找 encoder 和 ctc_head
    encoder_keys = [k for k in state_dict.keys() if "encoder" in k.lower()]
    ctc_keys = [k for k in state_dict.keys() if "ctc" in k.lower()]
    print(f"  encoder keys: {encoder_keys[:5]}")
    print(f"  ctc keys: {ctc_keys[:5]}")

    return state_dict


def export_ctc_onnx(checkpoint_path: Path, output_path: Path):
    """导出 CTC 模型为 ONNX"""
    print("CTC .ckpt → .onnx 转换")

    # 加载 checkpoint
    checkpoint = torch.load(checkpoint_path, map_location="cpu")
    state_dict = checkpoint.get("model", checkpoint.get("state_dict", checkpoint))

    # 尝试找模型定义
    # 从 checkpoint 中提取配置
    config = checkpoint.get("config", {})
    if isinstance(config, dict):
        print(f"  配置信息: {list(config.keys())[:10]}")

    # 构建模型（这里需要根据实际结构来重建）
    # 由于没有官方源码，我们尝试从 state_dict 推断结构
    print("  分析模型结构...")

    # 查找关键权重维度来推断结构
    first_key = list(state_dict.keys())[0]
    sample_weight = state_dict[first_key]
    print(f"  示例权重 {first_key}: shape={sample_weight.shape}")

    # 尝试加载整个模型（如果 checkpoint 包含完整模型）
    if "encoder.conv1.weight" in state_dict:
        print("  检测到 ResNet 结构 (encoder.conv1.weight)")
    if "transformer.encoder_layers" in str(list(state_dict.keys())):
        print("  检测到 Transformer 结构")

    print("\n错误: 需要知道 CTC 模型的具体定义才能重建模型结构")
    print("请参考 manga-image-translator 源码中的 ocr/ctc 目录")
    print("或者提供转换好的 .onnx 文件")
    print("\n可能的解决方案:")
    print("1. 参考 https://github.com/zyddnys/manga-image-translator/blob/master/ocr/ctc/__init__.py")
    print("2. 使用官方提供的 ocr-ctc.zip (已包含 model.onnx)")
    print("3. 从 Google Drive 下载预转换的 onnx 模型")


if __name__ == "__main__":
    if not CHECKPOINT_PATH.exists():
        print(f"错误: 找不到 checkpoint 文件: {CHECKPOINT_PATH}")
        print("请先下载 ocr-ctc.ckpt")
        sys.exit(1)

    export_ctc_onnx(CHECKPOINT_PATH, OUTPUT_DIR / "model.onnx")