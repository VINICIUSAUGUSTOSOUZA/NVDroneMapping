package com.nv.dronemapping.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.nv.dronemapping.R

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
    private val barColor = Color.rgb(13, 33, 55)

    private val items = mutableListOf<TextView>()
    private var selectedIndex = -1

    private val host: Activity?
        get() = context as? Activity

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(4), dp(4), dp(4))

        background = GradientDrawable().apply {
            setColor(barColor)
        }

        addItem(
            label = "Projetos",
            containerId = R.id.panelProjects,
            invokeLegacyAction = false,
            showStats = false,
            action = onProject
        )
        addItem(
            label = "Voo",
            containerId = R.id.panelFlight,
            invokeLegacyAction = true,
            showStats = true,
            action = onFlight
        )
        addItem(
            label = "Foto",
            containerId = R.id.panelPhoto,
            invokeLegacyAction = true,
            showStats = true,
            action = onCapture
        )
        addItem(
            label = "DJI",
            containerId = R.id.panelDji,
            invokeLegacyAction = false,
            showStats = false,
            action = onDJI
        )
        addItem(
            label = "Avançado",
            containerId = R.id.panelAdvanced,
            invokeLegacyAction = false,
            showStats = false,
            forceAdvancedVisible = true,
            action = onAdvanced
        )
        addItem(
            label = "Área",
            containerId = R.id.panelArea,
            invokeLegacyAction = true,
            showStats = true,
            action = onArea
        )
    }

    private fun addItem(
        label: String,
        containerId: Int,
        invokeLegacyAction: Boolean,
        showStats: Boolean,
        forceAdvancedVisible: Boolean = false,
        action: () -> Unit
    ) {
        val index = items.size

        val item = TextView(context).apply {
            text = label
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(normalColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, dp(2), 0)
            minHeight = dp(42)
            background = itemBackground(false)

            setOnClickListener {
                val panel = host?.findViewById<View>(R.id.panel)
                val isSameOpen =
                    selectedIndex == index && panel?.visibility == View.VISIBLE

                if (isSameOpen) {
                    collapsePanel()
                    return@setOnClickListener
                }

                showOnly(containerId, showStats, forceAdvancedVisible)
                selectItem(index)

                if (invokeLegacyAction) {
                    action()
                }
            }
        }

        items += item

        addView(
            item,
            LayoutParams(
                0,
                LayoutParams.MATCH_PARENT,
                1f
            )
        )
    }

    private fun showOnly(
        containerId: Int,
        showStats: Boolean,
        forceAdvancedVisible: Boolean
    ) {
        val activity = host ?: return
        val panel = activity.findViewById<View>(R.id.panel)

        listOf(
            R.id.panelProjects,
            R.id.panelFlight,
            R.id.panelPhoto,
            R.id.panelDji,
            R.id.panelAdvanced,
            R.id.panelArea
        ).forEach { id ->
            activity.findViewById<View>(id)?.visibility =
                if (id == containerId) View.VISIBLE else View.GONE
        }

        activity.findViewById<View>(R.id.txtStats)?.visibility =
            if (showStats) View.VISIBLE else View.GONE

        activity.findViewById<View>(R.id.advancedContainer)?.visibility =
            if (forceAdvancedVisible) View.VISIBLE else View.GONE

        panel?.visibility = View.VISIBLE
        panel?.post {
            panel.requestLayout()
        }
    }

    private fun collapsePanel() {
        val activity = host ?: return

        activity.findViewById<View>(R.id.panel)?.visibility = View.GONE
        activity.findViewById<View>(R.id.txtStats)?.visibility = View.GONE
        activity.findViewById<View>(R.id.advancedContainer)?.visibility = View.GONE

        listOf(
            R.id.panelProjects,
            R.id.panelFlight,
            R.id.panelPhoto,
            R.id.panelDji,
            R.id.panelAdvanced,
            R.id.panelArea
        ).forEach { id ->
            activity.findViewById<View>(id)?.visibility = View.GONE
        }

        selectedIndex = -1
        refreshSelection()
    }

    private fun selectItem(index: Int) {
        selectedIndex = index
        refreshSelection()
    }

    private fun refreshSelection() {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            item.setTextColor(if (selected) Color.WHITE else normalColor)
            item.background = itemBackground(selected)
        }
    }

    private fun itemBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(
                if (selected) selectedColor
                else Color.TRANSPARENT
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
