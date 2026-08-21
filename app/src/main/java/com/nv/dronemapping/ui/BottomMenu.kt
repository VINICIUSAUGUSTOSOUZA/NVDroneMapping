package com.nv.dronemapping.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout

class BottomMenu(
    context: Context,
    private val onProject: () -> Unit,
    private val onArea: () -> Unit,
    private val onFlight: () -> Unit,
    private val onCapture: () -> Unit,
    private val onDJI: () -> Unit,
    private val onAdvanced: () -> Unit
) : LinearLayout(context) {

    init {

        orientation = HORIZONTAL
        gravity = Gravity.CENTER

        setBackgroundColor(Color.WHITE)

        val buttons = listOf(
            Triple("📁", "Projeto", onProject),
            Triple("🗺️", "Área", onArea),
            Triple("✈️", "Voo", onFlight),
            Triple("📷", "Captura", onCapture),
            Triple("🚁", "DJI", onDJI),
            Triple("⚙️", "Avançado", onAdvanced)
        )


        buttons.forEach { item ->

            val button = Button(context)

            button.text = "${item.first}\n${item.second}"

            button.setOnClickListener {
                item.third()
            }

            addView(
                button,
                LayoutParams(
                    0,
                    LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }
    }
}
