#!/usr/bin/env python3
"""
导出 manga-ocr 模型为 ONNX 格式（支持动态序列长度）。

用法:
    python export_manga_ocr_onnx.py --output-dir app/src/main/assets/manga_ocr/

依赖:
    pip install torch transformers onnx onnxruntime

模型: kha-white/manga-ocr-base (ViT Encoder + BERT Decoder)
关键：decoder 的 input_ids 使用动态 seq_len，而非固定 [1, 1]。
"""

import argparse
import os
import sys

import torch
import torch.nn as nn
from transformers import AutoTokenizer, VisionEncoderDecoderModel


class EncoderWrapper(nn.Module):
    """只导出 encoder 的 pixel_values → last_hidden_state"""
    def __init__(self, encoder):
        super().__init__()
        self.encoder = encoder

    def forward(self, pixel_values):
        return self.encoder(pixel_values).last_hidden_state


class DecoderWrapper(nn.Module):
    """简化 decoder：input_ids + encoder_hidden_states → logits
    直接调用 BERT 内部的 embeddings + encoder，绕过 _create_attention_masks。
    """
    def __init__(self, decoder):
        super().__init__()
        bert = decoder.bert
        self.embeddings = bert.embeddings
        self.encoder = bert.encoder
        self.cls = decoder.cls

    def forward(self, input_ids, encoder_hidden_states):
        batch_size, seq_len = input_ids.shape
        device = input_ids.device

        # 构建 4D causal attention mask: [batch, 1, seq_len, seq_len]
        causal_mask = torch.tril(torch.ones(seq_len, seq_len, device=device)).unsqueeze(0).unsqueeze(0)
        causal_mask = causal_mask.expand(batch_size, 1, seq_len, seq_len)

        # embeddings
        position_ids = torch.arange(seq_len, dtype=torch.long, device=device).unsqueeze(0).expand(batch_size, -1)
        hidden_states = self.embeddings(input_ids=input_ids, position_ids=position_ids)

        # encoder layers（直接调用，绕过 _create_attention_masks）
        encoder_outputs = self.encoder(
            hidden_states,
            attention_mask=causal_mask,
            encoder_hidden_states=encoder_hidden_states,
            return_dict=False,
        )
        sequence_output = encoder_outputs[0]

        # lm_head
        logits = self.cls(sequence_output)
        return logits


def _split_external_data(onnx_path):
    """将大的 .onnx 文件拆分为小 .onnx + .onnx.data"""
    import onnx
    from onnx import external_data_helper
    file_size = os.path.getsize(onnx_path)
    if file_size < 50 * 1024 * 1024:  # 小于 50MB 不拆分
        return
    print(f"  Splitting external data: {onnx_path} ({file_size / 1024 / 1024:.1f} MB)")
    model_proto = onnx.load(onnx_path)
    data_path = onnx_path + ".data"
    external_data_helper.convert_model_to_external_data(
        model_proto, all_tensors_to_one_file=True, location=os.path.basename(data_path)
    )
    onnx.save(model_proto, onnx_path)
    new_size = os.path.getsize(onnx_path)
    data_size = os.path.getsize(data_path)
    print(f"  -> {os.path.basename(onnx_path)}: {new_size / 1024:.0f} KB, {os.path.basename(data_path)}: {data_size / 1024 / 1024:.1f} MB")


def main():
    parser = argparse.ArgumentParser(description="Export manga-ocr to ONNX")
    parser.add_argument("--model-name", default="kha-white/manga-ocr-base",
                        help="HuggingFace model name or path")
    parser.add_argument("--output-dir", default="app/src/main/assets/manga_ocr/",
                        help="Output directory for ONNX files")
    parser.add_argument("--opset", type=int, default=14, help="ONNX opset version")
    args = parser.parse_args()

    os.makedirs(args.output_dir, exist_ok=True)

    print(f"Loading model: {args.model_name}")
    model = VisionEncoderDecoderModel.from_pretrained(args.model_name)
    model.eval()

    tokenizer = AutoTokenizer.from_pretrained(args.model_name)

    print(f"Vocab size: {tokenizer.vocab_size}")
    print(f"Special tokens: bos={tokenizer.bos_token_id}, eos={tokenizer.eos_token_id}, "
          f"cls={tokenizer.cls_token_id}, sep={tokenizer.sep_token_id}")

    # ---- Export Encoder ----
    print("\n=== Exporting Encoder ===")
    encoder_wrapper = EncoderWrapper(model.encoder)
    encoder_wrapper.eval()

    dummy_pixel = torch.randn(1, 3, 224, 224)
    encoder_path = os.path.join(args.output_dir, "manga_ocr_encoder.onnx")

    torch.onnx.export(
        encoder_wrapper,
        (dummy_pixel,),
        encoder_path,
        opset_version=args.opset,
        input_names=["pixel_values"],
        output_names=["last_hidden_state"],
        dynamic_axes=None,
        dynamo=False,
    )
    # 拆分为小 .onnx + 大 .onnx.data（减小 APK 内 assets 大小）
    _split_external_data(encoder_path)
    enc_size = os.path.getsize(encoder_path) / (1024 * 1024)
    print(f"Encoder exported: {encoder_path} ({enc_size:.1f} MB)")

    # ---- Export Decoder ----
    print("\n=== Exporting Decoder ===")
    decoder_wrapper = DecoderWrapper(model.decoder)
    decoder_wrapper.eval()

    dummy_ids = torch.tensor([[tokenizer.cls_token_id or 0]])  # [1, 1]
    dummy_enc = torch.randn(1, 197, 768)
    decoder_path = os.path.join(args.output_dir, "manga_ocr_decoder.onnx")

    torch.onnx.export(
        decoder_wrapper,
        (dummy_ids, dummy_enc),
        decoder_path,
        opset_version=args.opset,
        input_names=["input_ids", "encoder_hidden_states"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {1: "seq_len"},
            "encoder_hidden_states": {1: "enc_seq_len"},
            "logits": {1: "seq_len"},
        },
        dynamo=False,
    )
    # 拆分为小 .onnx + 大 .onnx.data
    _split_external_data(decoder_path)

    dec_size = os.path.getsize(decoder_path) / (1024 * 1024)
    print(f"Decoder exported: {decoder_path} ({dec_size:.1f} MB)")

    # ---- Verify with ONNX Runtime ----
    print("\n=== Verification ===")
    try:
        import onnxruntime as ort
        import numpy as np

        # Test encoder
        enc_sess = ort.InferenceSession(encoder_path, providers=["CPUExecutionProvider"])
        print("Encoder inputs:")
        for i in enc_sess.get_inputs():
            print(f"  {i.name}: {i.shape} ({i.type})")
        print("Encoder outputs:")
        for o in enc_sess.get_outputs():
            print(f"  {o.name}: {o.shape} ({o.type})")

        dummy_img = np.random.randn(1, 3, 224, 224).astype(np.float32)
        enc_out = enc_sess.run(None, {"pixel_values": dummy_img})[0]
        print(f"Encoder output shape: {enc_out.shape}")

        # Test decoder
        dec_sess = ort.InferenceSession(decoder_path, providers=["CPUExecutionProvider"])
        print("\nDecoder inputs:")
        for i in dec_sess.get_inputs():
            print(f"  {i.name}: {i.shape} ({i.type})")
        print("Decoder outputs:")
        for o in dec_sess.get_outputs():
            print(f"  {o.name}: {o.shape} ({o.type})")

        # Test with seq_len=1
        ids_1 = np.array([[2]], dtype=np.int64)
        out_1 = dec_sess.run(None, {"input_ids": ids_1, "encoder_hidden_states": enc_out})[0]
        print(f"Decoder output (seq_len=1): {out_1.shape}")

        # Test with seq_len=5
        ids_5 = np.array([[2, 10, 20, 30, 40]], dtype=np.int64)
        out_5 = dec_sess.run(None, {"input_ids": ids_5, "encoder_hidden_states": enc_out})[0]
        print(f"Decoder output (seq_len=5): {out_5.shape}")

        print("\nAll tests passed!")
    except Exception as e:
        print(f"Verification error: {e}")
        import traceback
        traceback.print_exc()

    # ---- Save vocab.txt ----
    tokenizer.save_vocabulary(args.output_dir)
    print(f"\nVocabulary saved to: {args.output_dir}")


if __name__ == "__main__":
    main()
