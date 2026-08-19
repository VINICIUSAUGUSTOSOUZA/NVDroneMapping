private fun setupActions() {
    binding.btnUndo.setOnClickListener {
        if (boundary.isNotEmpty()) {
            boundary.removeLast()
            invalidatePlan()
            redrawBoundary(false)
            updateStatsAreaOnly()
        }
    }

    binding.btnClear.setOnClickListener {
        boundary.clear()
        invalidatePlan()
        redrawBoundary(false)
        updateStatsAreaOnly()
    }

    binding.btnImport.setOnClickListener {
        importLauncher.launch(arrayOf(
            "application/vnd.google-earth.kml+xml",
            "application/vnd.google-earth.kmz",
            "application/zip",
            "application/octet-stream",
            "text/xml",
            "text/plain"
        ))
    }

    binding.btnGenerate.setOnClickListener { generateMission() }
    binding.btnExport.setOnClickListener { choosePartAndExport() }
    binding.btnShare.setOnClickListener { shareMission() }
    binding.btnSaveProject.setOnClickListener { showSaveProjectDialog() }
    binding.btnOpenProject.setOnClickListener { showProjectsDialog() }

    binding.btnMyLocation.setOnClickListener { requestLocationAndLocate() }

    binding.btnFitBoundary.setOnClickListener {
        if (boundary.isNotEmpty()) {
            fitToPoints(boundary)
        } else {
            toast("Desenhe ou importe um perímetro primeiro")
        }
    }

    binding.btnPreset2d.setOnClickListener { apply2dPreset() }
    binding.btnPreset3d.setOnClickListener { apply3dPreset() }

    binding.btnPreviewKml.setOnClickListener {
        if (plan != null) previewLauncher.launch("NV_Mapping_preview.kml")
    }

    binding.btnDjiGuide.setOnClickListener { showDjiGuide() }
}
