package com.school.smartcbt.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import com.school.smartcbt.databinding.DialogAccessDeniedModernBinding

object DialogHelper {

    /**
     * Menampilkan popup Akses Ditolak / Terbatas modern dengan background warna & styling elegan
     */
    fun showAccessDeniedDialog(
        context: Context,
        title: String = "Akses Ditolak",
        message: String,
        subtitle: String = "Sistem Keamanan Smart School CBT",
        hint: String? = "Hubungi staf BK, wali kelas, atau operator sekolah jika merasa ini adalah kekeliruan.",
        actionText: String = "Mengerti",
        onDismiss: (() -> Unit)? = null
    ) {
        try {
            val dialog = Dialog(context)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val binding = DialogAccessDeniedModernBinding.inflate(LayoutInflater.from(context))
            dialog.setContentView(binding.root)

            dialog.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.setLayout(
                    (context.resources.displayMetrics.widthPixels * 0.90).toInt(),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            binding.tvAccessDeniedTitle.text = title
            binding.tvAccessDeniedSubtitle.text = subtitle
            binding.tvAccessDeniedMessage.text = message

            if (hint.isNullOrEmpty()) {
                binding.tvAccessDeniedHint.visibility = android.view.View.GONE
            } else {
                binding.tvAccessDeniedHint.text = hint
                binding.tvAccessDeniedHint.visibility = android.view.View.VISIBLE
            }

            binding.btnAccessDeniedAction.text = actionText
            binding.btnAccessDeniedAction.setOnClickListener {
                dialog.dismiss()
                onDismiss?.invoke()
            }

            dialog.setOnCancelListener {
                onDismiss?.invoke()
            }

            dialog.show()
        } catch (e: Exception) {
            // Fallback gracefully to standard alert if dialog cannot attach to window
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle("⚠️ $title")
                .setMessage(message)
                .setPositiveButton(actionText) { _, _ -> onDismiss?.invoke() }
                .show()
        }
    }
}
