package com.nv.dronemapping.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object DronePanels {

    fun createPanel(
        context: Context,
        title: String,
        options: List<String>
    ): LinearLayout {

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.WHITE)
        }

        val header = TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        panel.addView(header)

        options.forEach { option ->

            val button = Button(context).apply {
                text = option
                isAllCaps = false
            }

            panel.addView(button)
        }

        return panel
    }


    fun createProjectsPanel(context: Context): LinearLayout {

        return createPanel(
            context,
            "PROJETOS",
            listOf(
                "Novo projeto",
                "Abrir projeto",
                "Projetos salvos"
            )
        )
    }


    fun createFlightPanel(context: Context): LinearLayout {

        return createPanel(
            context,
            "VOO",
            listOf(
                "Altura",
                "Velocidade",
                "Sobreposição",
                "Direção",
                "Aplicar plano"
            )
        )
    }


    fun createCapturePanel(context: Context): LinearLayout {

        return createPanel(
            context,
            "CAPTURA",
            listOf(
                "Fotos",
                "Intervalo",
                "Configuração"
            )
        )
    }


    fun createDjiPanel(context: Context): LinearLayout {

        return createPanel(
            context,
            "DJI",
            listOf(
                "Drone",
                "Configurações DJI",
                "Exportação"
            )
        )
    }


    fun createAdvancedPanel(context: Context): LinearLayout {

        return createPanel(
            context,
            "AVANÇADO",
            listOf(
                "Configurações avançadas",
                "Ajustes da missão",
                "Parâmetros"
            )
        )
    }


    fun createAreaPanel(context: Context): LinearLayout {

        return createPanel(
            context,
            "ÁREA",
            listOf(
                "Desenhar área",
                "Importar KML",
                "Limpar área"
            )
        )
    }
}
