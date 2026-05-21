#!/usr/bin/env python3
"""
manga-ocr ONNX 模型导出脚本

将 manga-ocr 的 PyTorch 模型导出为 ONNX 格式，用于 Android 端推理。

使用方法:
    pip install manga-ocr torch onnx onnxscript
    python export_manga_ocr_onnx.py

输出文件:
    manga_ocr_encoder.onnx  - ViT Encoder
    manga_ocr_decoder.onnx  - BERT-like Decoder (无 KV cache)
    vocab.json              - 词表
"""

import sys
from pathlib import Path

import torch
import torch.nn as nn
from torch import Tensor

# 输出目录
OUTPUT_DIR = Path("manga_ocr_onnx")
OUTPUT_DIR.mkdir(exist_ok=True)


def export_encoder(model, output_path: Path):
    """导出 ViT Encoder（支持动态 batch_size）

    两步导出：
    1. 使用 dynamo=False（旧版 TorchScript tracer）正确处理 CLS token expand
    2. 将内嵌权重拆分到外部 .onnx.data 文件（减小 .onnx 文件大小）
    """
    print("导出 ViT Encoder...")

    class EncoderWrapper(nn.Module):
        def __init__(self, encoder):
            super().__init__()
            self.encoder = encoder

        def forward(self, pixel_values: Tensor) -> Tensor:
            outputs = self.encoder(pixel_values)
            return outputs.last_hidden_state

    wrapper = EncoderWrapper(model.encoder)
    wrapper.eval()

    dummy_input = torch.randn(1, 3, 224, 224)

    # Step 1: 导出（权重内嵌，dynamo=False 正确处理 CLS token）
    torch.onnx.export(
        wrapper,
        dummy_input,
        str(output_path),
        export_params=True,
        opset_version=18,
        do_constant_folding=True,
        input_names=["pixel_values"],
        output_names=["last_hidden_state"],
        dynamic_axes={
            "pixel_values": {0: "batch_size"},
            "last_hidden_state": {0: "batch_size"},
        },
        dynamo=False,
    )

    # Step 2: 将内嵌权重拆分到外部文件
    import onnx
    from onnx.external_data_helper import convert_model_to_external_data
    onnx_model = onnx.load(str(output_path))
    convert_model_to_external_data(
        onnx_model,
        all_tensors_to_one_file=True,
        location=output_path.name + ".data",
        size_threshold=1024,
    )
    onnx.save(onnx_model, str(output_path))

    print(f"  Encoder 导出完成: {output_path} ({output_path.stat().st_size / 1024 / 1024:.1f} MB)")


def export_decoder(model, output_path: Path):
    """
    导出 BERT-like Decoder (简化版，无 KV cache)

    注意: Decoder 不支持动态 batch_size，因为 BERT 内部的 Reshape/Attention
    节点会硬编码 batch 维度。Decoder 始终以 batch_size=1 运行，
    通过多 Session 并行实现吞吐量提升。

    输入: input_ids + encoder_hidden_states
    输出: logits
    """
    print("导出 Decoder...")

    decoder = model.decoder
    decoder.eval()
    config = decoder.config

    n_layers = config.num_hidden_layers
    n_heads = config.num_attention_heads
    hidden_size = config.hidden_size

    print(f"  模型配置: layers={n_layers}, heads={n_heads}, hidden={hidden_size}")

    class DecoderWrapper(nn.Module):
        def __init__(self, decoder):
            super().__init__()
            self.decoder = decoder

        def forward(self, input_ids: Tensor, encoder_hidden_states: Tensor) -> Tensor:
            outputs = self.decoder(
                input_ids=input_ids,
                encoder_hidden_states=encoder_hidden_states,
                use_cache=False,
                return_dict=True,
            )
            return outputs.logits

    wrapper = DecoderWrapper(decoder)
    wrapper.eval()

    # 虚拟输入
    dummy_input_ids = torch.zeros(1, 1, dtype=torch.long)
    dummy_enc_hidden = torch.randn(1, 197, hidden_size)

    torch.onnx.export(
        wrapper,
        (dummy_input_ids, dummy_enc_hidden),
        str(output_path),
        export_params=True,
        opset_version=18,
        do_constant_folding=True,
        input_names=["input_ids", "encoder_hidden_states"],
        output_names=["logits"],
    )

    print(f"  Decoder 导出完成: {output_path} ({output_path.stat().st_size / 1024 / 1024:.1f} MB)")


def copy_tokenizer(model_name: str, output_dir: Path):
    """复制 tokenizer 文件"""
    from transformers import AutoTokenizer

    print("复制 Tokenizer 文件...")
    tokenizer = AutoTokenizer.from_pretrained(model_name)

    tokenizer.save_vocabulary(str(output_dir))

    # 检查生成的文件
    for f in output_dir.iterdir():
        if f.name.startswith("vocab") or f.name.startswith("merges"):
            print(f"  {f.name}: {f.stat().st_size / 1024:.1f} KB")


def main():
    try:
        from manga_ocr import MangaOcr
    except ImportError:
        print("错误: 请先安装 manga-ocr")
        print("  pip install manga-ocr")
        return

    print("加载 manga-ocr 模型...")
    print("首次运行会下载模型文件 (~400MB)")
    mocr = MangaOcr()
    model = mocr.model

    # 导出 encoder
    encoder_path = OUTPUT_DIR / "manga_ocr_encoder.onnx"
    export_encoder(model, encoder_path)

    # 导出 decoder
    decoder_path = OUTPUT_DIR / "manga_ocr_decoder.onnx"
    export_decoder(model, decoder_path)

    # 复制 tokenizer
    copy_tokenizer("kha-white/manga-ocr-base", OUTPUT_DIR)

    print("\n导出完成!")
    print(f"输出目录: {OUTPUT_DIR.absolute()}")
    print("\n文件列表:")
    for f in sorted(OUTPUT_DIR.iterdir()):
        size = f.stat().st_size
        if size > 1024 * 1024:
            print(f"  {f.name}: {size / 1024 / 1024:.1f} MB")
        else:
            print(f"  {f.name}: {size / 1024:.1f} KB")


if __name__ == "__main__":
    main()
