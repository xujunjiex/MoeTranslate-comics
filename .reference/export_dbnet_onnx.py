#!/usr/bin/env python3
"""
导出 DBNet 检测模型为 ONNX 格式。

用法:
    python export_dbnet_onnx.py --ckpt detect-20241225.ckpt --output dbnet_detector.onnx

依赖:
    pip install torch onnx

模型架构: ResNet34 backbone + DBHead (来自 manga-image-translator)
输入: [1, 3, H, W] float32, 归一化到 [-1, 1]
输出:
    - db: [1, 2, H/4, W/4] 文字概率图 (shrink_maps + threshold_maps)
    - mask: [1, 1, H, W] 分割掩码
"""

import argparse
import os
import sys

import torch
import torch.nn as nn

# ---- 模型架构定义 (与参考项目完全一致) ----

from torchvision.models import resnet34


class DBHead(nn.Module):
    def __init__(self, in_channels, out_channels, k=50):
        super().__init__()
        self.k = k
        self.binarize = nn.Sequential(
            nn.Conv2d(in_channels, in_channels // 4, 3, padding=1),
            nn.BatchNorm2d(in_channels // 4),
            nn.ReLU(inplace=True),
            nn.ConvTranspose2d(in_channels // 4, in_channels // 4, 4, 2, 1),
            nn.BatchNorm2d(in_channels // 4),
            nn.ReLU(inplace=True),
            nn.ConvTranspose2d(in_channels // 4, 1, 4, 2, 1),
        )
        # thresh 结构与原始 _init_thresh 一致 (bias=False + BatchNorm)
        self.thresh = nn.Sequential(
            nn.Conv2d(in_channels, in_channels // 4, 3, padding=1, bias=False),
            nn.BatchNorm2d(in_channels // 4),
            nn.ReLU(inplace=True),
            nn.ConvTranspose2d(in_channels // 4, in_channels // 4, 4, 2, 1),
            nn.BatchNorm2d(in_channels // 4),
            nn.ReLU(inplace=True),
            nn.ConvTranspose2d(in_channels // 4, 1, 4, 2, 1),
            nn.Sigmoid(),
        )

    def forward(self, x):
        shrink_maps = self.binarize(x)
        threshold_maps = self.thresh(x)
        # 推理时输出 2 通道: shrink_maps + threshold_maps
        y = torch.cat((shrink_maps, threshold_maps), dim=1)
        return y


class double_conv(nn.Module):
    def __init__(self, in_ch, mid_ch, out_ch, stride=1, planes=256):
        super().__init__()
        self.down = None
        if stride > 1:
            self.down = nn.AvgPool2d(2, stride=2)
        self.conv = nn.Sequential(
            nn.Conv2d(in_ch + mid_ch, mid_ch, 3, padding=1, stride=1, bias=False),
            nn.BatchNorm2d(mid_ch),
            nn.ReLU(inplace=True),
            nn.Conv2d(mid_ch, mid_ch, 3, padding=1, stride=1, bias=False),
            nn.BatchNorm2d(mid_ch),
            nn.ReLU(inplace=True),
            nn.Conv2d(mid_ch, out_ch, 3, stride=1, padding=1, bias=False),
            nn.BatchNorm2d(out_ch),
            nn.ReLU(inplace=True),
        )

    def forward(self, x):
        if self.down is not None:
            x = self.down(x)
        return self.conv(x)


class double_conv_up(nn.Module):
    def __init__(self, in_ch, mid_ch, out_ch, planes=256):
        super().__init__()
        self.conv = nn.Sequential(
            nn.Conv2d(in_ch + mid_ch, mid_ch, 3, padding=1, stride=1, bias=False),
            nn.BatchNorm2d(mid_ch),
            nn.ReLU(inplace=True),
            nn.Conv2d(mid_ch, mid_ch, 3, stride=1, padding=1, bias=False),
            nn.BatchNorm2d(mid_ch),
            nn.ReLU(inplace=True),
            nn.ConvTranspose2d(mid_ch, out_ch, 4, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(out_ch),
            nn.ReLU(inplace=True),
        )

    def forward(self, x):
        return self.conv(x)


class TextDetection(nn.Module):
    def __init__(self):
        super().__init__()
        self.backbone = resnet34(pretrained=False)

        self.conv_db = DBHead(64, 0)

        self.conv_mask = nn.Sequential(
            nn.Conv2d(64, 64, 3, padding=1), nn.ReLU(inplace=True),
            nn.Conv2d(64, 64, 3, padding=1), nn.ReLU(inplace=True),
            nn.Conv2d(64, 32, 3, padding=1), nn.ReLU(inplace=True),
            nn.Conv2d(32, 1, 1),
            nn.Sigmoid(),
        )

        self.down_conv1 = double_conv(0, 512, 512, 2)
        self.down_conv2 = double_conv(0, 512, 512, 2)
        self.down_conv3 = double_conv(0, 512, 512, 2)

        self.upconv1 = double_conv_up(0, 512, 256)
        self.upconv2 = double_conv_up(256, 512, 256)
        self.upconv3 = double_conv_up(256, 512, 256)
        self.upconv4 = double_conv_up(256, 512, 256, planes=128)
        self.upconv5 = double_conv_up(256, 256, 128, planes=64)
        self.upconv6 = double_conv_up(128, 128, 64, planes=32)
        self.upconv7 = double_conv_up(64, 64, 64, planes=16)

    def forward(self, x):
        x = self.backbone.conv1(x)
        x = self.backbone.bn1(x)
        x = self.backbone.relu(x)
        x = self.backbone.maxpool(x)

        h4 = self.backbone.layer1(x)
        h8 = self.backbone.layer2(h4)
        h16 = self.backbone.layer3(h8)
        h32 = self.backbone.layer4(h16)
        h64 = self.down_conv1(h32)
        h128 = self.down_conv2(h64)
        h256 = self.down_conv3(h128)

        up256 = self.upconv1(h256)
        up128 = self.upconv2(torch.cat([up256, h128], dim=1))
        up64 = self.upconv3(torch.cat([up128, h64], dim=1))
        up32 = self.upconv4(torch.cat([up64, h32], dim=1))
        up16 = self.upconv5(torch.cat([up32, h16], dim=1))
        up8 = self.upconv6(torch.cat([up16, h8], dim=1))
        up4 = self.upconv7(torch.cat([up8, h4], dim=1))

        db = self.conv_db(up8)
        mask = self.conv_mask(up4)
        return db, mask


def main():
    parser = argparse.ArgumentParser(description="Export DBNet to ONNX")
    parser.add_argument("--ckpt", required=True, help="Path to detect-20241225.ckpt")
    parser.add_argument("--output", default="dbnet_detector.onnx", help="Output ONNX file")
    parser.add_argument("--height", type=int, default=1024, help="Input height (default 1024)")
    parser.add_argument("--width", type=int, default=1024, help="Input width (default 1024)")
    parser.add_argument("--opset", type=int, default=17, help="ONNX opset version")
    args = parser.parse_args()

    if not os.path.exists(args.ckpt):
        print(f"Error: checkpoint not found: {args.ckpt}")
        sys.exit(1)

    print(f"Loading checkpoint: {args.ckpt}")
    model = TextDetection()
    sd = torch.load(args.ckpt, map_location="cpu")
    if "model" in sd:
        sd = sd["model"]
    model.load_state_dict(sd)
    model.eval()
    print("Model loaded successfully")

    # 固定输入尺寸用于移动端
    h, w = args.height, args.width
    dummy_input = torch.randn(1, 3, h, w)

    print(f"Exporting to ONNX: {args.output}")
    print(f"  Input shape: [1, 3, {h}, {w}]")
    print(f"  Opset: {args.opset}")

    torch.onnx.export(
        model,
        dummy_input,
        args.output,
        opset_version=args.opset,
        input_names=["input"],
        output_names=["db", "mask"],
        dynamic_axes=None,  # 固定尺寸，移动端性能更好
    )

    file_size = os.path.getsize(args.output) / (1024 * 1024)
    print(f"Export complete: {args.output} ({file_size:.1f} MB)")
    print(f"\nOutputs:")
    print(f"  db:   [1, 2, {h // 4}, {w // 4}] - text probability maps")
    print(f"  mask: [1, 1, {h}, {w}] - segmentation mask")


if __name__ == "__main__":
    main()
