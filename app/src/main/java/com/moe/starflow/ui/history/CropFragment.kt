package com.moe.starflow.ui.history

import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.moe.starflow.databinding.FragmentCropBinding

class CropFragment : DialogFragment() {

    companion object {
        const val RESULT_KEY = "crop_result"
        private const val ARG_IMAGE_PATH = "imagePath"
        private const val ARG_PRESET_CROP = "presetCrop"

        fun newInstance(imagePath: String, presetCrop: RectF? = null): CropFragment {
            return CropFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_IMAGE_PATH, imagePath)
                    if (presetCrop != null) {
                        putString(ARG_PRESET_CROP, "${presetCrop.left},${presetCrop.top},${presetCrop.right},${presetCrop.bottom}")
                    }
                }
            }
        }
    }

    private var _binding: FragmentCropBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCropBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val imagePath = arguments?.getString(ARG_IMAGE_PATH) ?: run { dismiss(); return }
        val bitmap = BitmapFactory.decodeFile(imagePath) ?: run { dismiss(); return }

        // 显示原图
        binding.ivCropImage.setImageBitmap(bitmap)

        // 设置初始裁剪框
        binding.cropView.post {
            binding.cropView.setRectCentered(0.8f, 0.6f)
        }

        val presetStr = arguments?.getString(ARG_PRESET_CROP)
        if (presetStr != null) {
            val parts = presetStr.split(",").map { it.toFloatOrNull() ?: 0f }
            if (parts.size == 4) {
                binding.cropView.post {
                    binding.cropView.setRect(RectF(parts[0], parts[1], parts[2], parts[3]))
                }
            }
        }

        binding.btnConfirmCrop.setOnClickListener {
            val rect = binding.cropView.mRect
            val bundle = Bundle().apply {
                putInt("cropLeft", rect.left.toInt().coerceAtLeast(0))
                putInt("cropTop", rect.top.toInt().coerceAtLeast(0))
                putInt("cropRight", rect.right.toInt().coerceAtMost(bitmap.width))
                putInt("cropBottom", rect.bottom.toInt().coerceAtMost(bitmap.height))
            }
            setFragmentResult(RESULT_KEY, bundle)
            dismiss()
        }

        binding.btnCancelCrop.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
