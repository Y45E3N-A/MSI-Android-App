package com.example.msiandroidapp.ui.pmfi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.msiandroidapp.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText

class PmfiEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "pmfi_ini_text"
        const val EXTRA_RESULT_TEXT = "pmfi_ini_text_result"
    }

    private lateinit var edit: TextInputEditText
    private var hasReturned = false // guard to prevent double-callback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pmfi_editor)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        edit = findViewById(R.id.fullIniEdit)

        // Toolbar setup
        toolbar.title = "Edit PMFI INI"
        toolbar.inflateMenu(R.menu.menu_pmfi_editor)
        toolbar.setNavigationIcon(R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener {
            finish() // triggers onPause() -> auto save
        }

        // Clear (X) icon handling
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_clear -> {
                    if (edit.text.isNullOrEmpty()) return@setOnMenuItemClickListener true
                    AlertDialog.Builder(this)
                        .setTitle("Clear text?")
                        .setMessage("This will delete all text in the PMFI configuration field.")
                        .setPositiveButton("Yes") { _, _ -> edit.setText("") }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                else -> false
            }
        }

        // Restore previous text
        edit.setText(intent.getStringExtra(EXTRA_TEXT).orEmpty())
    }

    override fun finish() {
        if (!hasReturned) {
            val data = Intent().apply {
                putExtra(EXTRA_RESULT_TEXT, edit.text?.toString().orEmpty())
            }
            setResult(Activity.RESULT_OK, data)
            hasReturned = true
        }
        super.finish()
    }

    // Auto-save when leaving with gesture or home button
    override fun onPause() {
        super.onPause()
        if (isFinishing && !hasReturned) finish()
    }
}
