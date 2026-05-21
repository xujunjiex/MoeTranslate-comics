"""将 dbnet_detector.onnx 从固定形状转为动态形状。"""
import sys
import onnx

INPUT_PATH = r"D:\xjj20\Desktop\fyapp\MoeTranslate-comics\app\src\main\assets\dbnet\dbnet_detector.onnx"
OUTPUT_PATH = INPUT_PATH  # 直接覆盖

model = onnx.load(INPUT_PATH)

# 打印原始形状
print("=== 原始形状 ===")
for inp in model.graph.input:
    shape = [d.dim_value if d.dim_value else d.dim_param or '?' for d in inp.type.tensor_type.shape.dim]
    print(f"  input '{inp.name}': {shape}")
for out in model.graph.output:
    shape = [d.dim_value if d.dim_value else d.dim_param or '?' for d in out.type.tensor_type.shape.dim]
    print(f"  output '{out.name}': {shape}")

# 将 H, W 维度改为动态符号
input_shape = model.graph.input[0].type.tensor_type.shape
input_shape.dim[2].dim_param = "height"
input_shape.dim[3].dim_param = "width"

# db 输出: [1, 2, H, W]
db_shape = model.graph.output[0].type.tensor_type.shape
db_shape.dim[2].dim_param = "height"
db_shape.dim[3].dim_param = "width"

# mask 输出: [1, 1, H/2, W/2]
mask_shape = model.graph.output[1].type.tensor_type.shape
mask_shape.dim[2].dim_param = "height_half"
mask_shape.dim[3].dim_param = "width_half"

# 验证模型
onnx.checker.check_model(model)

# 保存
onnx.save(model, OUTPUT_PATH)

# 打印新形状
model2 = onnx.load(OUTPUT_PATH)
print("\n=== 动态形状 ===")
for inp in model2.graph.input:
    shape = [d.dim_value if d.dim_value else d.dim_param or '?' for d in inp.type.tensor_type.shape.dim]
    print(f"  input '{inp.name}': {shape}")
for out in model2.graph.output:
    shape = [d.dim_value if d.dim_value else d.dim_param or '?' for d in out.type.tensor_type.shape.dim]
    print(f"  output '{out.name}': {shape}")

print("\n完成! 模型已保存为动态形状。")
