package com.yidream.mdm

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Opens a conversation chosen by the user; never sends a message or device credentials. */
internal object AdminContactLinks {
    const val PHONE = "33766790352"
    const val DISPLAY_PHONE = "+33 7 66 79 03 52"
    const val EMAIL = "yiddishdream@gmail.com"

    fun whatsapp(message: String): Intent = Intent(Intent.ACTION_VIEW,
        Uri.Builder().scheme("https").authority("wa.me").appendPath(PHONE)
            .appendQueryParameter("text", message).build())

    fun email(): Intent = Intent(Intent.ACTION_SENDTO,
        Uri.fromParts("mailto", EMAIL, null)).putExtra(Intent.EXTRA_SUBJECT, "YiDream")
}

internal class AdminContactController(
    private val activity: AppCompatActivity,
    private val container: LinearLayout,
    private val open: (Intent) -> Unit = { activity.startActivity(it) }
) {
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun bind() {
        container.removeAllViews()
        val card = container
        paragraph(card, activity.getString(R.string.contact_intro))
        action(card, R.id.contactWhatsappButton, activity.getString(R.string.contact_whatsapp), true) {
            launch(AdminContactLinks.whatsapp(activity.getString(R.string.contact_whatsapp_message)))
        }
        paragraph(card, AdminContactLinks.DISPLAY_PHONE, 12f).apply {
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        action(card, R.id.contactLabel, activity.getString(R.string.contact_email), false) {
            launch(AdminContactLinks.email())
        }
        paragraph(card, AdminContactLinks.EMAIL, 12f).apply {
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        paragraph(card, activity.getString(R.string.contact_external_notice), 12f)
    }

    fun bindInformation(information: LinearLayout) {
        action(information, R.id.contactUsageButton, activity.getString(R.string.contact_usage_title), false) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.contact_usage_title)
                .setMessage(R.string.contact_usage_body)
                .setPositiveButton(R.string.contact_close, null)
                .show()
        }
    }

    private fun paragraph(parent: LinearLayout, value: String, size: Float = 14f): TextView =
        TextView(activity).also { text ->
            text.text = value
            text.textSize = size
            text.setTextColor(activity.getColor(R.color.text_secondary))
            text.setLineSpacing(dp(3).toFloat(), 1f)
            parent.addView(text, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            })
        }

    private fun action(parent: LinearLayout, id: Int, title: String, whatsapp: Boolean, clicked: () -> Unit) {
        val tint = if (whatsapp) 0xFF177648.toInt() else activity.getColor(R.color.accent_blue)
        parent.addView(Button(activity).apply {
            this.id = id
            text = title
            textSize = 14f
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(52)
            minimumHeight = dp(52)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            backgroundTintList = null
            background = GradientDrawable().apply {
                setColor(if (whatsapp) tint else activity.getColor(R.color.accent_blue_soft))
                cornerRadius = dp(16).toFloat()
            }
            setTextColor(if (whatsapp) activity.getColor(R.color.white) else tint)
            stateListAnimator = null
            setOnClickListener { clicked() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun launch(intent: Intent) {
        try {
            open(intent)
        } catch (_: ActivityNotFoundException) {
            showContactFallback()
        } catch (_: SecurityException) {
            // A managed phone can prevent a browser or mail app from opening.
            showContactFallback()
        }
    }

    private fun showContactFallback() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.contact_unavailable_title)
            .setMessage(activity.getString(R.string.contact_unavailable_body,
                AdminContactLinks.DISPLAY_PHONE, AdminContactLinks.EMAIL))
            .setPositiveButton(R.string.contact_copy_number) { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("YiDream", "+${AdminContactLinks.PHONE}"))
                Toast.makeText(activity, R.string.contact_number_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.contact_close, null)
            .show()
    }
}
