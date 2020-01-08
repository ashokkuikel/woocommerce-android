package com.woocommerce.android.widgets

import android.content.Context
import android.text.Editable
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.doAfterTextChanged
import com.woocommerce.android.R
import kotlinx.android.synthetic.main.view_material_outlined_edittext.view.*

/**
 * Custom View that mimics a TextInputEditText with a summary TextView below it. This view will display
 * a text box and a summary. The entire view acts as a single component.
 *
 */
class WCMaterialOutlinedEditTextView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null)
    : ConstraintLayout(ctx, attrs) {
    init {
        View.inflate(context, R.layout.view_material_outlined_edittext, this)
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.WCMaterialOutlinedEditTextView)
            try {
                // Set the edit text hint
                edit_text_input.hint = a.getString(R.styleable.WCMaterialOutlinedEditTextView_editTextHint).orEmpty()

                // Set the edit text summary
                edit_text_summary.text =
                        a.getString(R.styleable.WCMaterialOutlinedEditTextView_editTextSummary).orEmpty()

                // Set the edit text input type
                edit_text.inputType = a.getInt(
                        R.styleable.WCMaterialOutlinedEditTextView_android_inputType,
                        EditorInfo.TYPE_TEXT_VARIATION_NORMAL
                )
            } finally {
                a.recycle()
            }
        }
    }

    fun setText(selectedText: String) {
        edit_text.setText(selectedText)
    }

    fun setOnTextChangedListener(cb: (text: Editable?) -> Unit) {
        edit_text.doAfterTextChanged {
            clearError()
            cb(it)
        }
    }

    fun setError(error: String) {
        edit_text_input.error = error
        edit_text_summary.visibility = View.GONE
    }

    private fun clearError() {
        edit_text_input.error = null
        edit_text_input.isErrorEnabled = false
        edit_text_summary.visibility = View.VISIBLE
    }
}
