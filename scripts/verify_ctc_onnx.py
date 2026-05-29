#!/usr/bin/env python3
"""
CTC ONNX 模型验证脚本

对比 PyTorch 模型和 ONNX 模型的推理输出，确保数值一致。
"""

import sys
import math
from pathlib import Path

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch import Tensor
from typing import Tuple, List, Optional
import numpy as np

# Paths
SCRIPT_DIR = Path(__file__).parent
REPO_ROOT = SCRIPT_DIR.parent
MODEL_DIR = REPO_ROOT / ".reference" / "models"
CHECKPOINT_PATH = MODEL_DIR / "ocr-ctc.ckpt"
ALPHABET_PATH = MODEL_DIR / "alphabet-all-v5.txt"
ONNX_PATH = REPO_ROOT / "tools" / "ctc_onnx" / "model.onnx"


# ============================================================
# Model architecture (same as export script)
# ============================================================

class PositionalEncoding(nn.Module):
    def __init__(self, d_model, dropout=0.1, max_len=5000):
        super().__init__()
        self.dropout = nn.Dropout(p=dropout)
        pe = torch.zeros(max_len, d_model)
        position = torch.arange(0, max_len, dtype=torch.float).unsqueeze(1)
        div_term = torch.exp(torch.arange(0, d_model, 2).float() * (-math.log(10000.0) / d_model))
        pe[:, 0::2] = torch.sin(position * div_term)
        pe[:, 1::2] = torch.cos(position * div_term)
        pe = pe.unsqueeze(0)
        self.register_buffer('pe', pe)

    def forward(self, x, offset=0):
        x = x + self.pe[:, offset:offset + x.size(1), :]
        return x


class CustomTransformerEncoderLayer(nn.Module):
    def __init__(self, d_model, nhead, dim_feedforward=2048, dropout=0.1,
                 activation="gelu", layer_norm_eps=1e-5, batch_first=False,
                 norm_first=False):
        super().__init__()
        self.self_attn = nn.MultiheadAttention(d_model, nhead, dropout=dropout, batch_first=batch_first)
        self.linear1 = nn.Linear(d_model, dim_feedforward)
        self.dropout = nn.Dropout(dropout)
        self.linear2 = nn.Linear(dim_feedforward, d_model)
        self.norm_first = norm_first
        self.norm1 = nn.LayerNorm(d_model, eps=layer_norm_eps)
        self.norm2 = nn.LayerNorm(d_model, eps=layer_norm_eps)
        self.dropout1 = nn.Dropout(dropout)
        self.dropout2 = nn.Dropout(dropout)
        self.pe = PositionalEncoding(d_model, max_len=2048)
        self.activation = F.gelu

    def forward(self, src: Tensor, src_mask=None, src_key_padding_mask=None, is_causal=None) -> Tensor:
        x = src
        if self.norm_first:
            x = x + self._sa_block(self.norm1(x), src_mask, src_key_padding_mask)
            x = x + self._ff_block(self.norm2(x))
        else:
            x = self.norm1(x + self._sa_block(x, src_mask, src_key_padding_mask))
            x = self.norm2(x + self._ff_block(x))
        return x

    def _sa_block(self, x: Tensor, attn_mask, key_padding_mask) -> Tensor:
        x = self.self_attn(self.pe(x), self.pe(x), x,
                           attn_mask=attn_mask,
                           key_padding_mask=key_padding_mask,
                           need_weights=False)[0]
        return self.dropout1(x)

    def _ff_block(self, x: Tensor) -> Tensor:
        x = self.linear2(self.dropout(self.activation(self.linear1(x))))
        return self.dropout2(x)


class BasicBlock(nn.Module):
    expansion = 1

    def __init__(self, inplanes, planes, stride=1, downsample=None):
        super().__init__()
        self.bn1 = nn.BatchNorm2d(inplanes)
        self.conv1 = nn.Conv2d(inplanes, planes, kernel_size=3, stride=stride, padding=1, bias=False)
        self.bn2 = nn.BatchNorm2d(planes)
        self.conv2 = nn.Conv2d(planes, planes, kernel_size=3, stride=1, padding=1, bias=False)
        self.downsample = downsample
        self.stride = stride

    def forward(self, x):
        residual = x
        out = F.relu(self.bn1(x))
        out = self.conv1(out)
        out = F.relu(self.bn2(out))
        out = self.conv2(out)
        if self.downsample is not None:
            residual = self.downsample(residual)
        return out + residual


class ResNet(nn.Module):
    def __init__(self, input_channel, output_channel, block, layers):
        super().__init__()
        self.output_channel_block = [int(output_channel / 4), int(output_channel / 2), output_channel, output_channel]
        self.inplanes = int(output_channel / 8)

        self.conv0_1 = nn.Conv2d(input_channel, int(output_channel / 8), kernel_size=3, stride=1, padding=1, bias=False)
        self.bn0_1 = nn.BatchNorm2d(int(output_channel / 8))
        self.conv0_2 = nn.Conv2d(int(output_channel / 8), self.inplanes, kernel_size=3, stride=1, padding=1, bias=False)

        self.maxpool1 = nn.AvgPool2d(kernel_size=2, stride=2, padding=0)
        self.layer1 = self._make_layer(block, self.output_channel_block[0], layers[0])
        self.bn1 = nn.BatchNorm2d(self.output_channel_block[0])
        self.conv1 = nn.Conv2d(self.output_channel_block[0], self.output_channel_block[0], kernel_size=3, stride=1, padding=1, bias=False)

        self.maxpool2 = nn.AvgPool2d(kernel_size=2, stride=2, padding=0)
        self.layer2 = self._make_layer(block, self.output_channel_block[1], layers[1], stride=1)
        self.bn2 = nn.BatchNorm2d(self.output_channel_block[1])
        self.conv2 = nn.Conv2d(self.output_channel_block[1], self.output_channel_block[1], kernel_size=3, stride=1, padding=1, bias=False)

        self.maxpool3 = nn.AvgPool2d(kernel_size=2, stride=(2, 1), padding=(0, 1))
        self.layer3 = self._make_layer(block, self.output_channel_block[2], layers[2], stride=1)
        self.bn3 = nn.BatchNorm2d(self.output_channel_block[2])
        self.conv3 = nn.Conv2d(self.output_channel_block[2], self.output_channel_block[2], kernel_size=3, stride=1, padding=1, bias=False)

        self.layer4 = self._make_layer(block, self.output_channel_block[3], layers[3], stride=1)
        self.bn4_1 = nn.BatchNorm2d(self.output_channel_block[3])
        self.conv4_1 = nn.Conv2d(self.output_channel_block[3], self.output_channel_block[3], kernel_size=3, stride=(2, 1), padding=(1, 1), bias=False)
        self.bn4_2 = nn.BatchNorm2d(self.output_channel_block[3])
        self.conv4_2 = nn.Conv2d(self.output_channel_block[3], self.output_channel_block[3], kernel_size=3, stride=1, padding=0, bias=False)
        self.bn4_3 = nn.BatchNorm2d(self.output_channel_block[3])

    def _make_layer(self, block, planes, blocks, stride=1):
        downsample = None
        if stride != 1 or self.inplanes != planes * block.expansion:
            downsample = nn.Sequential(
                nn.BatchNorm2d(self.inplanes),
                nn.Conv2d(self.inplanes, planes * block.expansion, kernel_size=1, stride=stride, bias=False),
            )
        layers = [block(self.inplanes, planes, stride, downsample)]
        self.inplanes = planes * block.expansion
        for _ in range(1, blocks):
            layers.append(block(self.inplanes, planes))
        return nn.Sequential(*layers)

    def forward(self, x):
        x = self.conv0_1(x)
        x = self.bn0_1(x)
        x = F.relu(x)
        x = self.conv0_2(x)

        x = self.maxpool1(x)
        x = self.layer1(x)
        x = self.bn1(x)
        x = F.relu(x)
        x = self.conv1(x)

        x = self.maxpool2(x)
        x = self.layer2(x)
        x = self.bn2(x)
        x = F.relu(x)
        x = self.conv2(x)

        x = self.maxpool3(x)
        x = self.layer3(x)
        x = self.bn3(x)
        x = F.relu(x)
        x = self.conv3(x)

        x = self.layer4(x)
        x = self.bn4_1(x)
        x = F.relu(x)
        x = self.conv4_1(x)
        x = self.bn4_2(x)
        x = F.relu(x)
        x = self.conv4_2(x)
        x = self.bn4_3(x)
        return x


class ResNet_FeatureExtractor(nn.Module):
    def __init__(self, input_channel, output_channel=128):
        super().__init__()
        self.ConvNet = ResNet(input_channel, output_channel, BasicBlock, [4, 6, 8, 6, 3])

    def forward(self, input):
        return self.ConvNet(input)


class OCR(nn.Module):
    def __init__(self, dictionary, max_len):
        super().__init__()
        self.max_len = max_len
        self.dictionary = dictionary
        self.dict_size = len(dictionary)
        self.backbone = ResNet_FeatureExtractor(3, 320)
        enc = CustomTransformerEncoderLayer(320, 8, 320 * 4, dropout=0.05, batch_first=True, norm_first=True)
        self.encoders = nn.TransformerEncoder(enc, 3)
        self.char_pred_norm = nn.Sequential(nn.LayerNorm(320), nn.Dropout(0.1), nn.GELU())
        self.char_pred = nn.Linear(320, self.dict_size)
        self.color_pred1 = nn.Linear(320, 6)

    def forward(self, img: Tensor) -> Tuple[Tensor, Tensor]:
        feats = self.backbone(img).squeeze(2)
        feats = self.encoders(feats.permute(0, 2, 1))
        pred_char_logits = self.char_pred(self.char_pred_norm(feats))
        pred_color_values = self.color_pred1(feats)
        return pred_char_logits, pred_color_values


def load_pytorch_model(checkpoint_path, alphabet_path):
    print("Loading alphabet...")
    with open(alphabet_path, 'r', encoding='utf-8') as fp:
        dictionary = [s[:-1] for s in fp.readlines()]
    print("  Dictionary size: {}".format(len(dictionary)))

    print("Loading checkpoint...")
    checkpoint = torch.load(checkpoint_path, map_location='cpu')
    state_dict = checkpoint['model'] if 'model' in checkpoint else checkpoint
    keys_to_delete = [k for k in state_dict.keys() if '.pe.pe' in k]
    for k in keys_to_delete:
        del state_dict[k]
        print("  Deleted: {}".format(k))

    model = OCR(dictionary, 768)
    model.load_state_dict(state_dict, strict=False)
    model.eval()
    print("  Model loaded OK")
    return model


def verify_onnx_model(onnx_path):
    print("Verifying ONNX model...")

    try:
        import onnx
        model = onnx.load(str(onnx_path))
        onnx.checker.check_model(model)
        print("  onnx.checker.check_model passed")
    except ImportError:
        print("  onnx not installed, skipping onnx check")
        return

    # Check model structure
    print("  Inputs:")
    for inp in model.graph.input:
        dims = [d.dim_param or d.dim_value for d in inp.type.tensor_type.shape.dim]
        print("    {}: {}".format(inp.name, dims))

    print("  Outputs:")
    for out in model.graph.output:
        dims = [d.dim_param or d.dim_value for d in out.type.tensor_type.shape.dim]
        print("    {}: {}".format(out.name, dims))

    # Check for quantization/dequantization nodes (should be none)
    node_types = [n.op_type for n in model.graph.node]
    if 'QuantizeLinear' in node_types or 'DequantizeLinear' in node_types:
        print("  WARNING: Model contains quantization nodes!")
    else:
        print("  No quantization nodes found (FP32 model)")

    return model


def compare_pytorch_vs_onnx(pt_model, onnx_path, test_inputs):
    print("\nComparing PyTorch vs ONNX inference...")

    try:
        import onnxruntime as ort
    except ImportError:
        print("  onnxruntime not installed, skipping runtime comparison")
        print("  (Install with: pip install onnxruntime)")
        return

    print("  Loading ONNX model with ONNX Runtime...")
    sess_options = ort.SessionOptions()
    sess_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
    sess = ort.InferenceSession(str(onnx_path), sess_options)

    for i, (name, tensor) in enumerate(test_inputs):
        print("\n  Test input #{}: {}".format(i + 1, name))
        print("    Shape: {}".format(tensor.shape))

        # PyTorch inference
        with torch.no_grad():
            pt_logits, pt_colors = pt_model(tensor)

        # ONNX Runtime inference
        onnx_inputs = {"images": tensor.numpy().astype(np.float32)}
        onnx_outputs = sess.run(None, onnx_inputs)
        onnx_logits = onnx_outputs[0]
        onnx_colors = onnx_outputs[1]

        # Compare char_logits
        diff_logits = np.abs(pt_logits.numpy() - onnx_logits)
        max_diff_logits = np.max(diff_logits)
        mean_diff_logits = np.mean(diff_logits)
        print("    char_logits:")
        print("      Max diff:   {:.6e}".format(max_diff_logits))
        print("      Mean diff:  {:.6e}".format(mean_diff_logits))
        print("      Match: {}".format("YES" if max_diff_logits < 1e-4 else "NO"))

        # Compare color_values
        diff_colors = np.abs(pt_colors.numpy() - onnx_colors)
        max_diff_colors = np.max(diff_colors)
        mean_diff_colors = np.mean(diff_colors)
        print("    color_values:")
        print("      Max diff:   {:.6e}".format(max_diff_colors))
        print("      Mean diff:  {:.6e}".format(mean_diff_colors))
        print("      Match: {}".format("YES" if max_diff_colors < 1e-4 else "NO"))

        # Check shapes
        print("    Shapes:")
        print("      PT char_logits:   {}".format(pt_logits.shape))
        print("      ONNX char_logits: {}".format(onnx_logits.shape))
        print("      PT color_values:  {}".format(pt_colors.shape))
        print("      ONNX color_values: {}".format(onnx_colors.shape))
        shape_match = (pt_logits.shape == onnx_logits.shape and pt_colors.shape == onnx_colors.shape)
        print("      Shape match: {}".format("YES" if shape_match else "NO"))

        # Argmax comparison (CTC decoding)
        pt_argmax = np.argmax(pt_logits.numpy(), axis=-1)
        onnx_argmax = np.argmax(onnx_logits, axis=-1)
        argmax_match = np.array_equal(pt_argmax, onnx_argmax)
        print("    Argmax match: {}".format("YES" if argmax_match else "PARTIAL"))


def ctc_decode_simple(logits, dict_size, blank=0):
    """Simple CTC decoding (greedy)"""
    log_probs = np.exp(logits) / np.sum(np.exp(logits), axis=-1, keepdims=True)
    log_probs = np.log(log_probs + 1e-8)
    preds = np.argmax(log_probs, axis=-1)

    decoded = []
    last = blank
    for p in preds:
        if p != blank and p != last:
            decoded.append(int(p))
        last = p
    return decoded


def test_ctc_decoding(pt_model, onnx_path):
    print("\nTesting CTC decoding...")

    try:
        import onnxruntime as ort
    except ImportError:
        print("  onnxruntime not installed")
        return

    with open(ALPHABET_PATH, 'r', encoding='utf-8') as fp:
        dictionary = [s[:-1] for s in fp.readlines()]

    sess = ort.InferenceSession(str(onnx_path))

    # Create a test image (simulate text)
    np.random.seed(42)
    test_img = np.random.randn(1, 3, 48, 256).astype(np.float32)

    # PyTorch inference
    with torch.no_grad():
        pt_logits, pt_colors = pt_model(torch.from_numpy(test_img))

    # ONNX inference
    onnx_outputs = sess.run(None, {"images": test_img})
    onnx_logits = onnx_outputs[0]
    onnx_colors = onnx_outputs[1]

    # CTC decode both
    pt_decoded = ctc_decode_simple(pt_logits.numpy()[0], len(dictionary))
    onnx_decoded = ctc_decode_simple(onnx_logits[0], len(dictionary))

    print("  Decoded char IDs (PT):    {}".format(pt_decoded[:10]))
    print("  Decoded char IDs (ONNX):  {}".format(onnx_decoded[:10]))
    print("  Match: {}".format("YES" if pt_decoded == onnx_decoded else "NO"))

    # Convert to text
    def ids_to_text(ids, dictionary):
        chars = []
        for idx in ids:
            ch = dictionary[idx] if idx < len(dictionary) else "?"
            if ch == '<SP>':
                ch = ' '
            chars.append(ch)
        return ''.join(chars)

    pt_text = ids_to_text(pt_decoded, dictionary)
    onnx_text = ids_to_text(onnx_decoded, dictionary)
    print("  Text (PT):   {}".format(repr(pt_text[:50])))
    print("  Text (ONNX): {}".format(repr(onnx_text[:50])))


def main():
    if not CHECKPOINT_PATH.exists():
        print("Error: checkpoint not found: {}".format(CHECKPOINT_PATH))
        sys.exit(1)

    if not ONNX_PATH.exists():
        print("Error: ONNX model not found: {}".format(ONNX_PATH))
        sys.exit(1)

    print("=" * 60)
    print("CTC ONNX Model Verification")
    print("=" * 60)

    # 1. Load PyTorch model
    pt_model = load_pytorch_model(CHECKPOINT_PATH, ALPHABET_PATH)

    # 2. Verify ONNX structure
    verify_onnx_model(ONNX_PATH)

    # 3. Create test inputs
    test_inputs = [
        ("small", torch.randn(1, 3, 48, 128)),
        ("medium", torch.randn(1, 3, 48, 256)),
        ("large", torch.randn(1, 3, 48, 512)),
        ("actual_text", torch.randn(1, 3, 48, 200)),
    ]

    # 4. Compare PyTorch vs ONNX
    compare_pytorch_vs_onnx(pt_model, ONNX_PATH, test_inputs)

    # 5. Test CTC decoding
    test_ctc_decoding(pt_model, ONNX_PATH)

    print("\n" + "=" * 60)
    print("Verification complete!")
    print("=" * 60)


if __name__ == "__main__":
    main()