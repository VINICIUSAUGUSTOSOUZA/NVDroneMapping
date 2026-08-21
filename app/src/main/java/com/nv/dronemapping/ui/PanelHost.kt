package com.nv.dronemapping.ui

import android.view.View
import android.view.ViewGroup

class PanelHost(
    private val parent: ViewGroup
) {

    private var current: View? = null

    fun show(view: View) {

        hide()

        current = view

        parent.addView(view)

        view.visibility = View.VISIBLE
    }

    fun hide() {

        current?.let {
            parent.removeView(it)
        }

        current = null
    }

    fun isOpen(): Boolean {
        return current != null
    }
}
