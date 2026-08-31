package com.nv.dronemapping.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.nv.dronemapping.R

class BottomNavigation(
    context: Context,
    private val onProject: () -> Unit,
    private val onFlight: () -> Unit,
    private val onCapture: () -> Unit,
    private val onDJI: () -> Unit,
    private val onAdvanced: () -> Unit,
    @Suppress("UNUSED_PARAMETER") private val onArea: () -> Unit
) : LinearLayout(context) {

    private val selectedColor = Color.rgb(25, 118, 210)
    private val normalColor = Color.WHITE
    private val barColor = Color.rgb(13, 33, 55)

    private val items = mutableListOf<TextView>()
    private var selectedIndex = -1

    private val host: Activity?
        get() = context as? Activity

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(4), dp(5), dp(4))
        elevation = dp(14).toFloat()

        background = GradientDrawable().apply {
            setColor(barColor)
            cornerRadius = dp(14).toFloat()
        }

        configureContextualLayout()
        restoreMapQuickActions()

        addItem(
            icon = "📁",
            label = "Projetos",
            containerId = R.id.panelProjects,
            invokeLegacyAction = false,
            showStats = false,
            action = onProject
        )
        addItem(
            icon = "✈",
            label = "Voo",
            containerId = R.id.panelFlight,
            invokeLegacyAction = true,
            showStats = true,
            action = onFlight
        )
        addItem(
            icon = "📷",
            label = "Foto",
            containerId = R.id.panelPhoto,
            invokeLegacyAction = true,
            showStats = true,
            action = onCapture
        )
        addItem(
            icon = "🚁",
            label = "DJI",
            containerId = R.id.panelDji,
            invokeLegacyAction = false,
            showStats = false,
            action = onDJI
        )
        addItem(
            icon = "⚙",
            label = "Avançado",
            containerId = R.id.panelAdvanced,
            invokeLegacyAction = false,
            showStats = false,
            forceAdvancedVisible = true,
            action = onAdvanced
        )
    }

    /**
     * O mapa fica acima da barra. Quando uma categoria abre, o painel nasce
     * ABAIXO da barra e usa somente a altura do conteúdo daquela categoria.
     * Com o painel fechado, a barra permanece alguns dp acima da borda para
     * ganhar aspecto de barra flutuante e deixar a interface mais leve.
     */
    private fun configureContextualLayout() {
        val activity = host ?: return
        val mapContainer = activity.findViewById<FrameLayout>(R.id.mapContainer) ?: return
        val panel = activity.findViewById<View>(R.id.panel) ?: return
        val navContainer = activity.findViewById<FrameLayout>(R.id.bottomNavigationContainer) ?: return
        val root = mapContainer.parent as? ConstraintLayout ?: return

        navContainer.setBackgroundColor(Color.TRANSPARENT)
        navContainer.elevation = dp(16).toFloat()
        navContainer.clipChildren = false
        navContainer.clipToPadding = false

        (navContainer.layoutParams as? ConstraintLayout.LayoutParams)?.let { params ->
            params.height = dp(58)
            navContainer.layoutParams = params
        }

        panel.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(14).toFloat()
        }
        panel.elevation = dp(12).toFloat()

        val set = ConstraintSet()
        set.clone(root)

        set.clear(panel.id, ConstraintSet.BOTTOM)
        set.connect(
            panel.id,
            ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID,
            ConstraintSet.BOTTOM
        )
        set.setMargin(panel.id, ConstraintSet.START, dp(8))
        set.setMargin(panel.id, ConstraintSet.END, dp(8))
        set.setMargin(panel.id, ConstraintSet.BOTTOM, dp(6))

        set.clear(navContainer.id, ConstraintSet.BOTTOM)
        set.connect(
            navContainer.id,
            ConstraintSet.BOTTOM,
            panel.id,
            ConstraintSet.TOP
        )
        set.setMargin(navContainer.id, ConstraintSet.START, dp(8))
        set.setMargin(navContainer.id, ConstraintSet.END, dp(8))
        set.setMargin(navContainer.id, ConstraintSet.BOTTOM, dp(7))

        set.clear(mapContainer.id, ConstraintSet.BOTTOM)
        set.connect(
            mapContainer.id,
            ConstraintSet.BOTTOM,
            navContainer.id,
            ConstraintSet.TOP
        )

        set.applyTo(root)
    }

    /**
     * Reaproveita os mesmos botões e listeners existentes.
     * DESFAZER / LIMPAR / IMPORTAR REF continuam fixos no topo do mapa.
     */
    private fun restoreMapQuickActions() {
        val activity = host ?: return
        val mapContainer = activity.findViewById<FrameLayout>(R.id.mapContainer) ?: return

        val undo = activity.findViewById<Button>(R.id.btnUndo) ?: return
        val clear = activity.findViewById<Button>(R.id.btnClear) ?: return
        val importRef = activity.findViewById<Button>(R.id.btnImport) ?: return

        val quickBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(7), dp(7), 0)
            elevation = dp(20).toFloat()
        }

        listOf(undo, clear, importRef).forEach { button ->
            (button.parent as? ViewGroup)?.removeView(button)
            button.minHeight = 0
            button.minWidth = 0
            button.layoutParams = LayoutParams(
                0,
                dp(36),
                1f
            ).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            quickBar.addView(button)
        }

        mapContainer.addView(
            quickBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        val layers = activity.findViewById<Button>(R.id.btnLayers)
        val advanced = activity.findViewById<LinearLayout>(R.id.panelAdvanced)
        if (layers != null && advanced != null) {
            (layers.parent as? ViewGroup)?.removeView(layers)
            layers.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36)
            ).apply {
                topMargin = dp(5)
            }
            advanced.addView(layers)
        }
    }

    private fun addItem(
        icon: String,
        label: String,
        containerId: Int,
        invokeLegacyAction: Boolean,
        showStats: Boolean,
        forceAdvancedVisible: Boolean = false,
        action: () -> Unit
    ) {
        val index = items.size

        val item = TextView(context).apply {
            text = "$icon\n$label"
            textSize = 9.5f
            gravity = Gravity.CENTER
            setTextColor(normalColor)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setLineSpacing(0f, 0.92f)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            minHeight = dp(50)
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
            ).apply {
                marginStart = dp(1)
                marginEnd = dp(1)
            }
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
            item.setTextColor(Color.WHITE)
            item.background = itemBackground(selected)
            item.alpha = if (selected) 1f else 0.92f
        }
    }

    private fun itemBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(11).toFloat()
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
