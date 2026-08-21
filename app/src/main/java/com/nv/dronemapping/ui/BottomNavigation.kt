package com.nv.dronemapping.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class BottomNavigation(
    context: Context,
    private val onProject: () -> Unit,
    private val onFlight: () -> Unit,
    private val onCapture: () -> Unit,
    private val onDJI: () -> Unit,
    private val onAdvanced: () -> Unit,
    private val onArea: () -> Unit
) : LinearLayout(context) {

    private val selectedColor = Color.rgb(33, 150, 243)
    private val normalColor = Color.WHITE

    private val items = mutableListOf<TextView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(6, 4, 6, 4)

        background = GradientDrawable().apply {
            setColor(Color.rgb(18, 18, 18))
        }

        addItem("📁", "Projetos", onProject)
        addItem("✈", "Voo", onFlight)
        addItem("📷", "Foto", onCapture)
        addItem("🚁", "DJI", onDJI)
        addItem("⚙", "Avançado", onAdvanced)
        addItem("🗺", "Área", onArea)
    }

    private fun addItem(
        icon: String,
        label: String,
        action: () -> Unit
    ) {

        val item = TextView(context).apply {

            text = "$icon\n$label"

            textSize = 10f

            gravity = Gravity.CENTER

            setTextColor(normalColor)

            typeface = Typeface.DEFAULT

            setPadding(2, 6, 2, 6)

            setOnClickListener {

                clearSelection()

                setTextColor(selectedColor)

                action()
            }
        }

        items.add(item)

        addView(
            item,
            LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            )
        )
    }

    private fun clearSelection() {

        items.forEach {

            it.setTextColor(normalColor)
        }
    }
}
