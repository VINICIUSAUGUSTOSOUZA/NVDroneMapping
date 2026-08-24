package com.nv.dronemapping

import com.nv.dronemapping.ui.BottomNavigation
import android.widget.LinearLayout

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.DashPathEffect
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.location.LocationManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.nv.dronemapping.databinding.ActivityMainBinding
import com.nv.dronemapping.dji.KmzExporter
import com.nv.dronemapping.geometry.GeoMath
import com.nv.dronemapping.geometry.GridPlanner
import com.nv.dronemapping.io.DxfImporter
import com.nv.dronemapping.io.KmlImporter
import com.nv.dronemapping.model.LatLng
import com.nv.dronemapping.model.MissionPlan
import com.nv.dronemapping.model.MissionSettings
import com.nv.dronemapping.model.SavedProject
import com.nv.dronemapping.storage.ProjectStore
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ProjectStore

    private val referenceBoundary = mutableListOf<LatLng>()
    private val flightBoundary = mutableListOf<LatLng>()

    private val flightVertexMarkers = mutableListOf<Marker>()
    private val routeOverlays = mutableListOf<Polyline>()
    private val routeArrowMarkers = mutableListOf<Marker>()

    private var referenceOverlay: Polygon? = null
    private var flightBoundaryOverlay: Polygon? = null

    private var plan: MissionPlan? = null
    private var pendingExportPart = 0
    private var userLocationMarker: Marker? = null
    private var preferredStartMarker: Marker? = null
    private var preferredStart: LatLng? = null
    private var selectingStartPoint = false

    private var routeStartMarker: Marker? = null
    private var routeEndMarker: Marker? = null
    private var approachLineOverlay: Polyline? = null

    private var satelliteMode = false
    private var showReferenceLayer = true
    private var showFlightLayer = true
    private var showRouteLayer = true

    private val satelliteTileSource by lazy {
        object : OnlineTileSourceBase(
            "EsriWorldImagery",
            0,
            19,
            256,
            ".jpg",
            arrayOf(
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
            )
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return "${getBaseUrl()}$z/$y/$x$mImageFilenameEnding"
            }
        }
    }

    private val importLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            uri ?: return@registerForActivityResult

            runCatching {

                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            val displayName = getDisplayName(uri)
            val mimeType = contentResolver.getType(uri).orEmpty()
            if (
                displayName.endsWith(".dxf", ignoreCase = true) ||
                mimeType.contains("dxf", ignoreCase = true)
            ) {
                importDxfReference(uri)
                return@registerForActivityResult
            }

            runCatching {

                KmlImporter.importBoundary(
                    contentResolver,
                    uri
                )

            }.onSuccess { points ->

                referenceBoundary.clear()
                referenceBoundary.addAll(points)

                redrawReference(
                    fit = true
                )

                toast(
                    "Referência importada: ${points.size} vértices. Agora desenhe o quadro de voo."
                )

            }.onFailure {

                toast(
                    "Falha ao importar referência: ${it.message}"
                )
            }
        }

    private val exportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/vnd.google-earth.kmz"
            )
        ) { uri ->

            val current =
                plan ?: return@registerForActivityResult

            uri ?: return@registerForActivityResult

            runCatching {

                contentResolver
                    .openOutputStream(uri)
                    ?.use { out ->

                        KmzExporter.writeKmz(
                            current,
                            pendingExportPart,
                            "NV_Mapping",
                            out
                        )
                    }
                    ?: error(
                        "Não foi possível criar o arquivo"
                    )

            }.onSuccess {

                toast(
                    "Missão DJI salva com sucesso"
                )

            }.onFailure {

                toast(
                    "Erro ao exportar: ${it.message}"
                )
            }
        }

    private val previewLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/vnd.google-earth.kml+xml"
            )
        ) { uri ->

            val current =
                plan ?: return@registerForActivityResult

            uri ?: return@registerForActivityResult

            runCatching {

                contentResolver
                    .openOutputStream(uri)
                    ?.use { out ->

                        writePreviewKml(
                            current,
                            out
                        )
                    }
                    ?: error(
                        "Não foi possível criar a prévia"
                    )

            }.onSuccess {

                toast(
                    "Prévia KML salva"
                )

            }.onFailure {

                toast(
                    "Erro ao salvar prévia: ${it.message}"
                )
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->

            if (
                grants.values.any { it }
            ) {

                locateUser()

            } else {

                toast(
                    "Permissão de localização não concedida"
                )
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        binding =
            ActivityMainBinding.inflate(
                layoutInflater
            )

        setContentView(
            binding.root
        )

        setupPlanningNavigation()

        store =
            ProjectStore(
                this
            )

        Configuration
            .getInstance()
            .userAgentValue =
            packageName

        Configuration
            .getInstance()
            .load(
                this,
                getSharedPreferences(
                    "osmdroid",
                    MODE_PRIVATE
                )
            )

        setupMap()
        setupActions()
        setupSettingsBehavior()

        updateBearingStatus()
        updateStatsAreaOnly()
        ensureConsentAndTutorial()
    }

    private fun setupPlanningNavigation() {

        val planningContent =
            binding.panel.getChildAt(0) as LinearLayout

        val navigation =
            BottomNavigation(
                this,
                onProject = {
                    showProjectsDialog()
                },
                onFlight = {
                    scrollPlanningTo(binding.inAltitude)
                },
                onCapture = {
                    scrollPlanningTo(binding.inFrontOverlap)
                },
                onDJI = {
                    showDjiGuide()
                },
                onAdvanced = {
                    toggleAdvancedPanel()
                    scrollPlanningTo(binding.btnAdvanced)
                },
                onArea = {
                    updateStatsAreaOnly()
                    scrollPlanningTo(binding.txtStats)
                }
            )

        planningContent.addView(
            navigation,
            1,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin =
                    (5f * resources.displayMetrics.density).toInt()
            }
        )
    }

    private fun scrollPlanningTo(
        view: View
    ) {

        binding.panel.post {
            val planningContent =
                binding.panel.getChildAt(0) as LinearLayout

            val position = Rect()
            view.getDrawingRect(position)
            planningContent.offsetDescendantRectToMyCoords(
                view,
                position
            )

            binding.panel.smoothScrollTo(
                0,
                position.top
            )
        }
    }

    private fun setupMap() {

        binding.map.setTileSource(
            TileSourceFactory.MAPNIK
        )

        binding.map.setMultiTouchControls(
            true
        )

        binding.map.controller.setZoom(
            4.5
        )

        binding.map.controller.setCenter(
            GeoPoint(
                -14.2,
                -51.9
            )
        )

        val scaleBar = ScaleBarOverlay(binding.map).apply {
            setAlignBottom(true)
            setAlignRight(false)
            setScaleBarOffset(10, 10)
            setEnableAdjustLength(true)
            setUnitsOfMeasure(ScaleBarOverlay.UnitsOfMeasure.metric)
        }
        binding.map.overlays.add(scaleBar)

        val events =
            MapEventsOverlay(

                object :
                    MapEventsReceiver {

                    override fun singleTapConfirmedHelper(
                        p: GeoPoint
                    ): Boolean {

                        if (selectingStartPoint) {
                            selectingStartPoint = false
                            preferredStart = LatLng(
                                p.latitude,
                                p.longitude
                            )
                            redrawPreferredStartMarker()
                            if (plan != null) {
                                generateMission(showToast = false)
                            }
                            toast("Ponto inicial preferido definido")
                            return true
                        }

                        flightBoundary +=
                            LatLng(
                                p.latitude,
                                p.longitude
                            )

                        invalidatePlan()

                        redrawBoundary(
                            fit = false
                        )

                        updateStatsAreaOnly()

                        return true
                    }

                    override fun longPressHelper(
                        p: GeoPoint
                    ): Boolean {

                        return false
                    }
                }
            )

        binding.map.overlays.add(
            events
        )
    }

    private fun setupActions() {

        binding.btnUndo.setOnClickListener {

            if (
                flightBoundary.isNotEmpty()
            ) {

                flightBoundary.removeLast()

                invalidatePlan()

                redrawBoundary(
                    false
                )

                updateStatsAreaOnly()
            }
        }

        binding.btnClear.setOnClickListener {

            showClearDialog()
        }

        binding.btnImport.setOnClickListener {

            importLauncher.launch(

                arrayOf(
                    "application/vnd.google-earth.kml+xml",
                    "application/vnd.google-earth.kmz",
                    "application/zip",
                    "application/octet-stream",
                    "application/dxf",
                    "image/vnd.dxf",
                    "text/xml",
                    "text/plain"
                )
            )
        }

        binding.btnMapType.setOnClickListener {

            toggleMapType()
        }

        binding.btnGenerate.setOnClickListener {

            generateMission()
        }

        binding.btnRotateLeft.setOnClickListener {

            rotateFlightLines(
                -15.0
            )
        }

        binding.btnRotateRight.setOnClickListener {

            rotateFlightLines(
                15.0
            )
        }

        binding.btnInvert.setOnClickListener {

            invertFlightDirection()
        }

        binding.btnAdvanced.setOnClickListener {

            toggleAdvancedPanel()
        }

        binding.btnStartPoint.setOnClickListener {

            if (flightBoundary.size < 3) {
                toast("Desenhe o quadro de voo antes de definir o início")
            } else {
                selectingStartPoint = true
                binding.txtHint.text = "Toque no mapa próximo de onde deseja iniciar o voo"
                toast("Toque no mapa para definir o início preferido")
            }
        }

        binding.btnLayers.setOnClickListener {

            showLayersDialog()
        }

        binding.btnTutorial.setOnClickListener {

            showTutorial(0)
        }

        binding.btnExport.setOnClickListener {

            choosePartAndExport()
        }

        binding.btnShare.setOnClickListener {

            shareMission()
        }

        binding.btnSaveProject.setOnClickListener {

            showSaveProjectDialog()
        }

        binding.btnOpenProject.setOnClickListener {

            showProjectsDialog()
        }

        binding.btnMyLocation.setOnClickListener {

            requestLocationAndLocate()
        }

        binding.btnFitBoundary.setOnClickListener {

            when {

                flightBoundary.isNotEmpty() -> {

                    fitToPoints(
                        flightBoundary
                    )
                }

                referenceBoundary.isNotEmpty() -> {

                    fitToPoints(
                        referenceBoundary
                    )
                }

                else -> {

                    toast(
                        "Importe uma referência ou desenhe o quadro de voo"
                    )
                }
            }
        }

        binding.btnPreset2d.setOnClickListener {

            apply2dPreset()
        }

        binding.btnPreset3d.setOnClickListener {

            apply3dPreset()
        }

        binding.btnPreviewKml.setOnClickListener {

            if (
                plan != null
            ) {

                showPreviewKmlMenu()
            }
        }

        binding.btnDjiGuide.setOnClickListener {

            showDjiGuide()
        }
    }

    private fun setupSettingsBehavior() {

        binding.checkAutoBearing
            .setOnCheckedChangeListener {
                    _,
                    checked ->

                binding.inBearing.isEnabled =
                    !checked

                updateBearingStatus()
            }

        binding.inBearing.isEnabled =
            !binding.checkAutoBearing.isChecked

        binding.advancedContainer.visibility = View.GONE
        binding.btnAdvanced.text = "AVANÇADO ▼"
    }

    private fun rotateFlightLines(
        deltaDeg: Double
    ) {

        if (
            flightBoundary.size < 3
        ) {

            toast(
                "Desenhe o quadro de voo antes de rotacionar as linhas"
            )

            return
        }

        val startingBearing =
            when {

                plan != null -> {

                    plan!!.stats.effectiveBearingDeg
                }

                else -> {

                    binding.inBearing.text
                        ?.toString()
                        ?.replace(
                            ',',
                            '.'
                        )
                        ?.toDoubleOrNull()
                        ?: 0.0
                }
            }

        val newBearing =
            normalizeBearing(
                startingBearing +
                    deltaDeg
            )

        binding.checkAutoBearing.isChecked =
            false

        binding.inBearing.setText(
            trimNumber(
                newBearing
            )
        )

        binding.inBearing.isEnabled =
            true

        updateBearingStatus(
            newBearing
        )

        generateMission(
            showToast = false
        )
    }

    private fun invertFlightDirection() {

        val currentPlan = plan

        if (currentPlan == null) {
            toast("Gere o plano de voo antes de inverter")
            return
        }

        val invertedPlan = currentPlan.copy(
            waypoints = currentPlan.waypoints.reversed(),
            parts = currentPlan.parts.reversed().map {
                it.reversed()
            }
        )

        plan = invertedPlan

        drawRoute(invertedPlan)
        updateStats(invertedPlan)
        updateBearingStatus(invertedPlan.stats.effectiveBearingDeg)

        binding.txtHint.text =
            "Plano invertido. Saída e chegada foram trocadas."

        toast("Missão invertida: sentido, saída e chegada atualizados")
    }

    private fun generateMission(
        showToast: Boolean = true
    ) {

        if (
            flightBoundary.size < 3
        ) {

            toast(
                "Desenhe o quadro de voo com pelo menos 3 vértices"
            )

            return
        }

        val settings =
            readSettings()
                ?: return

        runCatching {

            GridPlanner.plan(
                flightBoundary.toList(),
                settings
            )

        }.onSuccess { generated ->

            val oriented = GridPlanner.orientTowardStart(
                generated,
                preferredStart
            )

            plan =
                oriented

            binding.btnExport.isEnabled =
                true

            binding.btnShare.isEnabled =
                true

            binding.btnPreviewKml.isEnabled =
                true

            drawRoute(
                oriented
            )

            updateStats(
                oriented
            )

            updateBearingStatus(
                oriented.stats.effectiveBearingDeg
            )

            binding.txtHint.text =
                if (
                    oriented.parts.size > 1
                ) {

                    "Plano aplicado: ${oriented.parts.size} partes. Revise as linhas."

                } else {

                    "Plano aplicado. Use -15° / +15° para rotacionar as linhas."
                }

            if (
                showToast
            ) {

                toast(
                    "Plano de voo aplicado dentro do quadro manual"
                )
                showLongRouteWarningIfNeeded(oriented)
            }

        }.onFailure {

            toast(
                it.message
                    ?: "Erro ao gerar missão"
            )
        }
    }

    private fun readSettings():
        MissionSettings? {

        fun value(
            edit: EditText,
            label: String
        ): Double? {

            val v =
                edit.text
                    ?.toString()
                    ?.replace(
                        ',',
                        '.'
                    )
                    ?.toDoubleOrNull()

            if (
                v == null
            ) {

                toast(
                    "Informe $label corretamente"
                )
            }

            return v
        }

        val altitude =
            value(
                binding.inAltitude,
                "a altura"
            )
                ?: return null

        val speed =
            value(
                binding.inSpeed,
                "a velocidade"
            )
                ?: return null

        val front =
            value(
                binding.inFrontOverlap,
                "a sobreposição frontal"
            )
                ?: return null

        val side =
            value(
                binding.inSideOverlap,
                "a sobreposição lateral"
            )
                ?: return null

        val bearing =
            value(
                binding.inBearing,
                "a direção"
            )
                ?: return null

        val gimbal =
            value(
                binding.inGimbal,
                "o ângulo do gimbal"
            )
                ?: return null

        val maxWaypoints =
            binding.inMaxWaypoints.text
                ?.toString()
                ?.toIntOrNull()

        val droneEnum =
            binding.inDroneEnum.text
                ?.toString()
                ?.toIntOrNull()

        if (
            gimbal !in -135.0..80.0
        ) {

            toast(
                "Gimbal fora do intervalo -135° a 80°"
            )

            return null
        }

        if (
            maxWaypoints == null ||
            maxWaypoints !in 20..200
        ) {

            toast(
                "Máximo de pontos deve ficar entre 20 e 200"
            )

            return null
        }

        if (
            droneEnum == null ||
            droneEnum <= 0
        ) {

            toast(
                "Código DJI inválido"
            )

            return null
        }

        return MissionSettings(

            altitudeM =
                altitude,

            speedMs =
                speed,

            frontOverlapPct =
                front,

            sideOverlapPct =
                side,

            bearingDeg =
                bearing,

            autoBearing =
                binding.checkAutoBearing.isChecked,

            crossHatch =
                binding.checkCrossHatch.isChecked,

            gimbalPitchDeg =
                gimbal,

            maxWaypointsPerMission =
                maxWaypoints,

            droneEnumValue =
                droneEnum,

            finishAction =
                "goHome",

            rcLostAction =
                "goBack"
        )
    }

    private fun redrawBoundary(
        fit: Boolean
    ) {

        flightBoundaryOverlay?.let {

            binding.map.overlays.remove(
                it
            )
        }

        flightVertexMarkers.forEach {

            binding.map.overlays.remove(
                it
            )
        }

        flightVertexMarkers.clear()

        if (
            flightBoundary.size >= 2
        ) {

            flightBoundaryOverlay =
                Polygon(
                    binding.map
                ).apply {

                    points =
                        flightBoundary.map {

                            GeoPoint(
                                it.lat,
                                it.lon
                            )
                        }

                    // QUADRO DE VOO = LARANJA
                    outlinePaint.color =
                        Color.rgb(
                            255,
                            128,
                            0
                        )

                    outlinePaint.strokeWidth =
                        5f

                    fillPaint.color =
                        Color.TRANSPARENT
                }

            binding.map.overlays.add(
                flightBoundaryOverlay
            )
        }

        val smallVertexIcon =
            createSmallVertexIcon()

        flightBoundary.forEachIndexed {
                index,
                p ->

            val marker =
                Marker(
                    binding.map
                ).apply {

                    position =
                        GeoPoint(
                            p.lat,
                            p.lon
                        )

                    title =
                        "Quadro ${index + 1}"

                    snippet = String.format(
                        Locale.getDefault(),
                        "Lat %.7f | Lon %.7f",
                        p.lat,
                        p.lon
                    )

                    setOnMarkerClickListener { clicked, _ ->
                        clicked.showInfoWindow()
                        true
                    }

                    smallVertexIcon?.let {

                        icon =
                            it
                    }

                    setAnchor(
                        Marker.ANCHOR_CENTER,
                        Marker.ANCHOR_BOTTOM
                    )

                    isDraggable =
                        true

                    setOnMarkerDragListener(

                        object :
                            Marker.OnMarkerDragListener {

                            override fun onMarkerDrag(
                                marker: Marker?
                            ) = Unit

                            override fun onMarkerDragStart(
                                marker: Marker?
                            ) = Unit

                            override fun onMarkerDragEnd(
                                marker: Marker?
                            ) {

                                marker
                                    ?: return

                                flightBoundary[index] =
                                    LatLng(
                                        marker.position.latitude,
                                        marker.position.longitude
                                    )

                                invalidatePlan()

                                redrawBoundary(
                                    false
                                )

                                updateStatsAreaOnly()
                            }
                        }
                    )
                }

            flightVertexMarkers +=
                marker

            binding.map.overlays.add(
                marker
            )
        }

        redrawReference(
            fit = false
        )

        userLocationMarker?.let {

            binding.map.overlays.remove(
                it
            )

            binding.map.overlays.add(
                it
            )
        }

        redrawPreferredStartMarker()
        applyLayerVisibility()
        binding.map.invalidate()

        if (
            fit &&
            flightBoundary.isNotEmpty()
        ) {

            fitToPoints(
                flightBoundary
            )
        }
    }

    private fun drawRoute(
        plan: MissionPlan
    ) {

        routeOverlays.forEach {

            binding.map.overlays.remove(
                it
            )
        }

        routeArrowMarkers.forEach {

            binding.map.overlays.remove(
                it
            )
        }

        routeOverlays.clear()
        routeArrowMarkers.clear()
        clearRouteEndpointOverlays()

        /*
         * PLANO DE VOO começa em CIANO.
         * Fica claramente diferente do quadro laranja.
         */
        val colors =
            intArrayOf(

                Color.rgb(
                    0,
                    188,
                    212
                ),

                Color.rgb(
                    156,
                    39,
                    176
                ),

                Color.rgb(
                    0,
                    150,
                    136
                ),

                Color.rgb(
                    211,
                    47,
                    47
                ),

                Color.rgb(
                    0,
                    121,
                    191
                )
            )

        val arrowStride =
            if (plan.parts.size <= 4) 1 else kotlin.math.ceil(plan.parts.size / 8.0).toInt()

        plan.parts.forEachIndexed {
                idx,
                part ->

            val line =
                Polyline(
                    binding.map
                ).apply {

                    setPoints(

                        part.map {

                            GeoPoint(
                                it.lat,
                                it.lon
                            )
                        }
                    )

                    outlinePaint.color =
                        colors[
                            idx %
                                colors.size
                        ]

                    outlinePaint.strokeWidth =
                        6f

                    title =
                        "Missão ${idx + 1}/${plan.parts.size}"
                }

            routeOverlays +=
                line

            binding.map.overlays.add(
                line
            )

            if (
                plan.parts.size <= 4 ||
                idx % arrowStride == 0 ||
                idx == plan.parts.lastIndex
            ) {
                addDirectionArrows(
                    part,
                    colors[idx % colors.size],
                    if (plan.parts.size <= 4) 3 else 1
                )
            }
        }

        redrawRouteEndpoints(
            plan
        )

        redrawPreferredStartMarker()

        userLocationMarker?.let {

            binding.map.overlays.remove(
                it
            )

            binding.map.overlays.add(
                it
            )
        }

        applyLayerVisibility()
        binding.map.invalidate()

        fitToPoints(
            plan.boundary
        )
    }

    private fun fitToPoints(
        points: List<LatLng>
    ) {

        if (
            points.isEmpty()
        ) {

            return
        }

        val north =
            points.maxOf {
                it.lat
            }

        val south =
            points.minOf {
                it.lat
            }

        val east =
            points.maxOf {
                it.lon
            }

        val west =
            points.minOf {
                it.lon
            }

        val box =
            BoundingBox(
                north,
                east,
                south,
                west
            )

        binding.map.post {

            binding.map.zoomToBoundingBox(
                box,
                true,
                80
            )
        }
    }

    private fun updateStatsAreaOnly() {

        val area =
            if (
                flightBoundary.size >= 3
            ) {

                GeoMath.polygonAreaM2(
                    flightBoundary
                )

            } else {

                0.0
            }

        binding.txtStats.text =
            if (
                area > 0
            ) {

                "Quadro: ${formatArea(area)} | Toque em APLICAR PLANO"

            } else {

                "Quadro: -- | GSD: -- | Fotos: -- | Tempo: --"
            }
    }

    private fun updateStats(
        plan: MissionPlan
    ) {

        val s =
            plan.stats

        val minutes =
            ceil(
                s.estimatedFlightSeconds /
                    60.0
            ).toInt()

        val distance =
            if (
                s.routeDistanceM >= 1000
            ) {

                String.format(
                    Locale.getDefault(),
                    "%.2f km",
                    s.routeDistanceM /
                        1000.0
                )

            } else {

                String.format(
                    Locale.getDefault(),
                    "%.0f m",
                    s.routeDistanceM
                )
            }

        binding.txtStats.text =
            buildString {

                append(
                    "Área: ${formatArea(s.areaM2)}\n"
                )

                append(
                    String.format(
                        Locale.getDefault(),
                        "GSD: %.2f cm/px | Faixas: %d | Direção: %.0f°\n",
                        s.gsdCmPx,
                        s.flightLineCount,
                        s.effectiveBearingDeg
                    )
                )

                append(
                    String.format(
                        Locale.getDefault(),
                        "Espaçamento: %.1f m lateral / %.1f m fotos\n",
                        s.lineSpacingM,
                        s.photoSpacingM
                    )
                )

                append(
                    "Fotos: ${s.photoCount} | Rota: $distance | Tempo: ~${minutes} min"
                )

                if (
                    s.partCount > 1
                ) {

                    append(
                        "\nDJI: ${s.partCount} missões"
                    )
                }
            }
    }

    private fun updateBearingStatus(
        effectiveBearing: Double? = null
    ) {

        if (
            binding.checkAutoBearing.isChecked
        ) {

            binding.txtBearingStatus.text =
                if (
                    effectiveBearing != null
                ) {

                    String.format(
                        Locale.getDefault(),
                        "Linhas: AUTO → %.0f°",
                        effectiveBearing
                    )

                } else {

                    "Linhas: direção automática"
                }

        } else {

            val bearing =
                effectiveBearing
                    ?: binding.inBearing.text
                        ?.toString()
                        ?.replace(
                            ',',
                            '.'
                        )
                        ?.toDoubleOrNull()
                    ?: 0.0

            binding.txtBearingStatus.text =
                String.format(
                    Locale.getDefault(),
                    "Linhas: %.0f° — use -15° / +15° para rotacionar",
                    normalizeBearing(
                        bearing
                    )
                )
        }
    }

    private fun choosePartAndExport() {

        val current =
            plan
                ?: return

        val minutes = ceil(current.stats.estimatedFlightSeconds / 60.0).toInt()
        val route = if (current.stats.routeDistanceM >= 1000.0) {
            String.format(
                Locale.getDefault(),
                "%.2f km",
                current.stats.routeDistanceM / 1000.0
            )
        } else {
            String.format(
                Locale.getDefault(),
                "%.0f m",
                current.stats.routeDistanceM
            )
        }

        val summary = buildString {
            append("Altura: ${trimNumber(current.settings.altitudeM)} m\n")
            append("Velocidade: ${trimNumber(current.settings.speedMs)} m/s\n")
            append("Sobreposição: ${trimNumber(current.settings.frontOverlapPct)}% frontal / ${trimNumber(current.settings.sideOverlapPct)}% lateral\n")
            append("Direção: ${trimNumber(current.stats.effectiveBearingDeg)}°\n")
            append("Fotos: ${current.stats.photoCount}\n")
            append("Rota: $route\n")
            append("Tempo estimado: ~${minutes} min\n")
            append("Partes DJI: ${current.parts.size}")
        }

        AlertDialog.Builder(this)
            .setTitle("Resumo antes de exportar")
            .setMessage(summary)
            .setPositiveButton("CONTINUAR") { _, _ ->
                choosePartAndExportInternal(current)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun choosePartAndExportInternal(
        current: MissionPlan
    ) {

        if (
            current.parts.size == 1
        ) {

            pendingExportPart =
                0

            exportLauncher.launch(
                defaultKmzName(
                    0,
                    1
                )
            )

            return
        }

        val labels =
            current.parts.indices
                .map { i ->

                    "Parte ${i + 1}/${current.parts.size} — ${current.parts[i].size} waypoints"

                }
                .toTypedArray()

        AlertDialog.Builder(
            this
        )
            .setTitle(
                "Qual missão deseja exportar?"
            )
            .setItems(
                labels
            ) {
                    _,
                    which ->

                pendingExportPart =
                    which

                exportLauncher.launch(

                    defaultKmzName(
                        which,
                        current.parts.size
                    )
                )
            }
            .setNegativeButton(
                "Cancelar",
                null
            )
            .show()
    }

    private fun shareMission() {

        val current =
            plan
                ?: return

        runCatching {

            val dir =
                File(
                    cacheDir,
                    "exports"
                ).apply {

                    mkdirs()
                }

            val uris =
                current.parts.mapIndexed {
                        index,
                        _ ->

                    val file =
                        File(
                            dir,
                            defaultKmzName(
                                index,
                                current.parts.size
                            )
                        )

                    file.outputStream().use {

                        KmzExporter.writeKmz(
                            current,
                            index,
                            "NV_Mapping",
                            it
                        )
                    }

                    FileProvider.getUriForFile(
                        this,
                        "$packageName.fileprovider",
                        file
                    )
                }

            val intent =
                if (
                    uris.size == 1
                ) {

                    Intent(
                        Intent.ACTION_SEND
                    ).apply {

                        type =
                            "application/vnd.google-earth.kmz"

                        putExtra(
                            Intent.EXTRA_STREAM,
                            uris.first()
                        )

                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }

                } else {

                    Intent(
                        Intent.ACTION_SEND_MULTIPLE
                    ).apply {

                        type =
                            "application/vnd.google-earth.kmz"

                        putParcelableArrayListExtra(
                            Intent.EXTRA_STREAM,
                            ArrayList<Uri>(
                                uris
                            )
                        )

                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }

            startActivity(

                Intent.createChooser(
                    intent,
                    "Compartilhar missão DJI"
                )
            )

        }.onFailure {

            toast(
                "Erro ao compartilhar: ${it.message}"
            )
        }
    }

    private fun showSaveProjectDialog() {

        if (
            flightBoundary.size < 3
        ) {

            toast(
                "Desenhe o quadro de voo antes de salvar"
            )

            return
        }

        val input =
            EditText(
                this
            ).apply {

                hint =
                    "Nome do projeto"

                setText(
                    "Mapeamento ${
                        SimpleDateFormat(
                            "dd-MM HHmm",
                            Locale.getDefault()
                        ).format(
                            Date()
                        )
                    }"
                )

                selectAll()
            }

        AlertDialog.Builder(
            this
        )
            .setTitle(
                "Salvar projeto"
            )
            .setView(
                input
            )
            .setPositiveButton(
                "Salvar"
            ) {
                    _,
                    _ ->

                val name =
                    input.text
                        .toString()
                        .trim()

                val settings =
                    readSettings()
                        ?: return@setPositiveButton

                if (
                    name.isBlank()
                ) {

                    return@setPositiveButton
                }

                store.save(

                    SavedProject(
                        name = name,
                        boundary = flightBoundary.toList(),
                        settings = settings,
                        savedAtMs = System.currentTimeMillis(),
                        referenceBoundary = referenceBoundary.toList(),
                        preferredStart = preferredStart,
                        plan = plan
                    )
                )

                toast(
                    "Projeto salvo"
                )
            }
            .setNegativeButton(
                "Cancelar",
                null
            )
            .show()
    }

    private fun showProjectsDialog() {

        val projects =
            store.loadAll()

        if (
            projects.isEmpty()
        ) {

            toast(
                "Nenhum projeto salvo"
            )

            return
        }

        val labels =
            projects.map { p ->

                val date =
                    SimpleDateFormat(
                        "dd/MM/yyyy HH:mm",
                        Locale.getDefault()
                    ).format(
                        Date(
                            p.savedAtMs
                        )
                    )

                "${p.name}\n$date"

            }.toTypedArray()

        AlertDialog.Builder(
            this
        )
            .setTitle(
                "Projetos salvos"
            )
            .setItems(
                labels
            ) {
                    _,
                    which ->

                loadProject(
                    projects[
                        which
                    ]
                )
            }
            .setNeutralButton(
                "Excluir..."
            ) {
                    _,
                    _ ->

                showDeleteProjectDialog(
                    projects
                )
            }
            .setNegativeButton(
                "Fechar",
                null
            )
            .show()
    }

    private fun showDeleteProjectDialog(
        projects: List<SavedProject>
    ) {

        val names =
            projects.map {
                it.name
            }.toTypedArray()

        AlertDialog.Builder(
            this
        )
            .setTitle(
                "Excluir projeto"
            )
            .setItems(
                names
            ) {
                    _,
                    which ->

                AlertDialog.Builder(
                    this
                )
                    .setTitle(
                        "Excluir ${projects[which].name}?"
                    )
                    .setPositiveButton(
                        "Excluir"
                    ) {
                            _,
                            _ ->

                        store.delete(
                            projects[which].name
                        )

                        toast(
                            "Projeto excluído"
                        )
                    }
                    .setNegativeButton(
                        "Cancelar",
                        null
                    )
                    .show()
            }
            .show()
    }

    private fun loadProject(
        project: SavedProject
    ) {

        invalidatePlan()

        flightBoundary.clear()
        flightBoundary.addAll(
            project.boundary
        )

        referenceBoundary.clear()
        referenceBoundary.addAll(
            project.referenceBoundary
        )

        preferredStart =
            project.preferredStart

        applySettings(
            project.settings
        )

        redrawReference(
            false
        )

        redrawBoundary(
            true
        )

        redrawPreferredStartMarker()

        val savedPlan =
            project.plan

        if (savedPlan != null) {
            plan = savedPlan
            binding.btnExport.isEnabled = true
            binding.btnShare.isEnabled = true
            binding.btnPreviewKml.isEnabled = true
            drawRoute(savedPlan)
            updateStats(savedPlan)
            updateBearingStatus(savedPlan.stats.effectiveBearingDeg)
            binding.txtHint.text = "Projeto e plano carregados"
        } else {
            updateBearingStatus()
            updateStatsAreaOnly()
        }

        toast(
            "Projeto carregado"
        )
    }

    private fun applySettings(
        s: MissionSettings
    ) {

        binding.inAltitude.setText(
            trimNumber(
                s.altitudeM
            )
        )

        binding.inSpeed.setText(
            trimNumber(
                s.speedMs
            )
        )

        binding.inFrontOverlap.setText(
            trimNumber(
                s.frontOverlapPct
            )
        )

        binding.inSideOverlap.setText(
            trimNumber(
                s.sideOverlapPct
            )
        )

        binding.inBearing.setText(
            trimNumber(
                s.bearingDeg
            )
        )

        binding.checkAutoBearing.isChecked =
            s.autoBearing

        binding.checkCrossHatch.isChecked =
            s.crossHatch

        binding.inGimbal.setText(
            trimNumber(
                s.gimbalPitchDeg
            )
        )

        binding.inMaxWaypoints.setText(
            s.maxWaypointsPerMission
                .toString()
        )

        binding.inDroneEnum.setText(
            s.droneEnumValue
                .toString()
        )

        updateBearingStatus()
    }

    private fun apply2dPreset() {

        binding.inAltitude.setText(
            "60"
        )

        binding.inSpeed.setText(
            "5"
        )

        binding.inFrontOverlap.setText(
            "80"
        )

        binding.inSideOverlap.setText(
            "70"
        )

        binding.inGimbal.setText(
            "-90"
        )

        binding.checkAutoBearing.isChecked =
            true

        binding.checkCrossHatch.isChecked =
            false

        invalidatePlan()

        updateBearingStatus()

        updateStatsAreaOnly()

        toast(
            "Preset 2D aplicado: 60 m, 80/70, gimbal -90°"
        )
    }

    private fun apply3dPreset() {

        binding.inAltitude.setText(
            "60"
        )

        binding.inSpeed.setText(
            "4"
        )

        binding.inFrontOverlap.setText(
            "80"
        )

        binding.inSideOverlap.setText(
            "75"
        )

        binding.inGimbal.setText(
            "-90"
        )

        binding.checkAutoBearing.isChecked =
            true

        binding.checkCrossHatch.isChecked =
            true

        invalidatePlan()

        updateBearingStatus()

        updateStatsAreaOnly()

        toast(
            "Preset cruzado aplicado: duas direções de voo"
        )
    }

    private fun showDjiGuide() {

        AlertDialog.Builder(
            this
        )
            .setTitle(
                "Levar a missão ao DJI Fly"
            )
            .setMessage(

                "1. Importe KML/KMZ/DXF apenas como referência visual, se quiser.\n\n" +

                    "2. Desenhe manualmente o QUADRO DE VOO sobre o mapa.\n\n" +

                    "3. Toque em APLICAR PLANO para gerar as linhas dentro do quadro.\n\n" +

                    "4. Se quiser mudar o sentido das passadas, use -15° ou +15°.\n\n" +

                    "5. Revise a rota e exporte o KMZ DJI.\n\n" +

                    "6. No DJI Fly, crie e salve uma missão Waypoint temporária.\n\n" +

                    "7. No armazenamento do aparelho/controle, substitua o KMZ dessa missão pelo KMZ exportado pelo NV Drone Mapping.\n\n" +

                    "8. Reabra a missão no DJI Fly e CONFIRA rota, altura, RTH, gimbal e ações de foto antes de iniciar.\n\n" +

                    "Faça o primeiro teste em área aberta e pequena."
            )
            .setPositiveButton(
                "ENTENDI",
                null
            )
            .show()
    }

    private fun invalidatePlan() {

        plan =
            null

        binding.btnExport.isEnabled =
            false

        binding.btnShare.isEnabled =
            false

        binding.btnPreviewKml.isEnabled =
            false

        routeOverlays.forEach {

            binding.map.overlays.remove(
                it
            )
        }

        routeArrowMarkers.forEach {

            binding.map.overlays.remove(
                it
            )
        }

        routeOverlays.clear()
        routeArrowMarkers.clear()

        clearRouteEndpointOverlays()

        binding.map.invalidate()

        binding.txtHint.text =
            "Toque no mapa para desenhar o QUADRO DE VOO"
    }

    private fun redrawReference(
        fit: Boolean
    ) {

        referenceOverlay?.let {

            binding.map.overlays.remove(
                it
            )
        }

        referenceOverlay =
            null

        if (
            referenceBoundary.size >= 2
        ) {

            referenceOverlay =
                Polygon(
                    binding.map
                ).apply {

                    points =
                        referenceBoundary.map {

                            GeoPoint(
                                it.lat,
                                it.lon
                            )
                        }

                    outlinePaint.color =
                        Color.rgb(
                            0,
                            122,
                            255
                        )

                    outlinePaint.strokeWidth =
                        4f

                    fillPaint.color =
                        Color.TRANSPARENT
                }

            referenceOverlay?.let {

                binding.map.overlays.add(
                    0,
                    it
                )
            }
        }

        applyLayerVisibility()
        binding.map.invalidate()

        if (
            fit &&
            referenceBoundary.isNotEmpty()
        ) {

            fitToPoints(
                referenceBoundary
            )
        }
    }

    private fun createSmallVertexIcon():
        BitmapDrawable? {

        /*
         * Usa o mesmo PIN padrão que já existia.
         * Apenas reduz para 78% do tamanho original.
         */

        val original =
            Marker(
                binding.map
            ).icon
                ?: return null

        val density =
            resources.displayMetrics.density

        val baseWidth =
            if (
                original.intrinsicWidth > 0
            ) {

                original.intrinsicWidth

            } else {

                (
                    48f *
                        density
                    ).toInt()
            }

        val baseHeight =
            if (
                original.intrinsicHeight > 0
            ) {

                original.intrinsicHeight

            } else {

                (
                    48f *
                        density
                    ).toInt()
            }

        val scale =
            0.78f

        val width =
            (
                baseWidth *
                    scale
                )
                .toInt()
                .coerceAtLeast(
                    1
                )

        val height =
            (
                baseHeight *
                    scale
                )
                .toInt()
                .coerceAtLeast(
                    1
                )

        val bitmap =
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(
                bitmap
            )

        original.setBounds(
            0,
            0,
            width,
            height
        )

        original.draw(
            canvas
        )

        return BitmapDrawable(
            resources,
            bitmap
        )
    }

    private fun createUserLocationDotIcon():
        BitmapDrawable {

        /*
         * LOCALIZAÇÃO ATUAL:
         * pequeno ponto azul.
         * Sem alvo.
         */

        val density =
            resources.displayMetrics.density

        val iconSize =
            (
                22f *
                    density
                )
                .toInt()
                .coerceAtLeast(
                    22
                )

        val dotRadius =
            (
                5f *
                    density
                )
                .coerceAtLeast(
                    5f
                )

        val bitmap =
            Bitmap.createBitmap(
                iconSize,
                iconSize,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(
                bitmap
            )

        val paint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                color =
                    Color.rgb(
                        33,
                        150,
                        243
                    )

                style =
                    Paint.Style.FILL
            }

        canvas.drawCircle(
            iconSize / 2f,
            iconSize / 2f,
            dotRadius,
            paint
        )

        paint.style =
            Paint.Style.STROKE

        paint.strokeWidth =
            (
                1.5f *
                    density
                )
                .coerceAtLeast(
                    1.5f
                )

        paint.color =
            Color.WHITE

        canvas.drawCircle(
            iconSize / 2f,
            iconSize / 2f,
            dotRadius,
            paint
        )

        return BitmapDrawable(
            resources,
            bitmap
        )
    }

    private fun showClearDialog() {

        val options =
            arrayOf(
                "Limpar quadro de voo",
                "Remover referência importada",
                "Limpar tudo"
            )

        AlertDialog.Builder(
            this
        )
            .setTitle(
                "O que deseja limpar?"
            )
            .setItems(
                options
            ) {
                    _,
                    which ->

                when (
                    which
                ) {

                    0 -> {

                        flightBoundary.clear()
                        preferredStart = null
                        removePreferredStartMarker()

                        invalidatePlan()

                        redrawBoundary(
                            false
                        )

                        updateStatsAreaOnly()

                        toast(
                            "Quadro de voo limpo"
                        )
                    }

                    1 -> {

                        referenceBoundary.clear()

                        redrawReference(
                            false
                        )

                        toast(
                            "Referência removida"
                        )
                    }

                    2 -> {

                        flightBoundary.clear()
                        referenceBoundary.clear()
                        preferredStart = null
                        removePreferredStartMarker()

                        invalidatePlan()

                        redrawBoundary(
                            false
                        )

                        redrawReference(
                            false
                        )

                        updateStatsAreaOnly()

                        toast(
                            "Mapa limpo"
                        )
                    }
                }
            }
            .setNegativeButton(
                "Cancelar",
                null
            )
            .show()
    }

    private fun toggleMapType() {

        val centerBefore = GeoPoint(
            binding.map.mapCenter.latitude,
            binding.map.mapCenter.longitude
        )
        val zoomBefore = binding.map.zoomLevelDouble

        satelliteMode =
            !satelliteMode

        if (
            satelliteMode
        ) {

            binding.map.setTileSource(
                satelliteTileSource
            )

            binding.btnMapType.text =
                "MAP"

            toast(
                "Imagem de satélite ativada"
            )

        } else {

            binding.map.setTileSource(
                TileSourceFactory.MAPNIK
            )

            binding.btnMapType.text =
                "SAT"

            toast(
                "Mapa normal ativado"
            )
        }

        binding.map.controller.setCenter(centerBefore)
        binding.map.controller.setZoom(zoomBefore)
        binding.map.post {
            binding.map.controller.setCenter(centerBefore)
            binding.map.controller.setZoom(zoomBefore)
            binding.map.invalidate()
        }
    }

    private fun toggleAdvancedPanel() {

        val opening =
            binding.advancedContainer.visibility != View.VISIBLE

        binding.advancedContainer.visibility =
            if (opening) View.VISIBLE else View.GONE

        binding.btnAdvanced.text =
            if (opening) "AVANÇADO ▲" else "AVANÇADO ▼"
    }

    private fun showLayersDialog() {

        val labels = arrayOf(
            "Referência importada",
            "Quadro de voo",
            "Plano / rota"
        )

        val checked = booleanArrayOf(
            showReferenceLayer,
            showFlightLayer,
            showRouteLayer
        )

        AlertDialog.Builder(this)
            .setTitle("Camadas visíveis")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                when (which) {
                    0 -> showReferenceLayer = isChecked
                    1 -> showFlightLayer = isChecked
                    2 -> showRouteLayer = isChecked
                }
                applyLayerVisibility()
                binding.map.invalidate()
            }
            .setPositiveButton("FECHAR", null)
            .show()
    }

    private fun applyLayerVisibility() {

        referenceOverlay?.isEnabled =
            showReferenceLayer

        flightBoundaryOverlay?.isEnabled =
            showFlightLayer

        flightVertexMarkers.forEach {
            it.isEnabled = showFlightLayer
        }

        routeOverlays.forEach {
            it.isEnabled = showRouteLayer
        }

        routeArrowMarkers.forEach {
            it.isEnabled = showRouteLayer
        }

        preferredStartMarker?.isEnabled =
            showRouteLayer

        routeStartMarker?.isEnabled =
            showRouteLayer

        routeEndMarker?.isEnabled =
            showRouteLayer

        approachLineOverlay?.isEnabled =
            showRouteLayer
    }

    private fun redrawPreferredStartMarker() {

        removePreferredStartMarker()

        val start =
            preferredStart
                ?: return

        preferredStartMarker =
            Marker(
                binding.map
            ).apply {

                position =
                    GeoPoint(
                        start.lat,
                        start.lon
                    )

                title =
                    "Partida do drone"

                snippet =
                    String.format(
                        Locale.getDefault(),
                        "Lat %.7f | Lon %.7f",
                        start.lat,
                        start.lon
                    )

                icon =
                    createDroneStartIcon()

                setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_CENTER
                )

                isDraggable =
                    false

                setOnMarkerClickListener {
                        marker,
                        _ ->

                    marker.showInfoWindow()
                    true
                }
            }

        preferredStartMarker?.let {

            binding.map.overlays.add(
                it
            )
        }

        applyLayerVisibility()
    }

    private fun removePreferredStartMarker() {

        preferredStartMarker?.let {
            binding.map.overlays.remove(it)
        }

        preferredStartMarker = null
    }

    private fun clearRouteEndpointOverlays() {

        routeStartMarker?.let {
            binding.map.overlays.remove(it)
        }

        routeEndMarker?.let {
            binding.map.overlays.remove(it)
        }

        approachLineOverlay?.let {
            binding.map.overlays.remove(it)
        }

        routeStartMarker = null
        routeEndMarker = null
        approachLineOverlay = null
    }

    private fun redrawRouteEndpoints(
        currentPlan: MissionPlan
    ) {

        clearRouteEndpointOverlays()

        if (
            currentPlan.waypoints.isEmpty()
        ) {
            return
        }

        val routeStart =
            currentPlan.waypoints.first()

        val routeEnd =
            currentPlan.waypoints.last()

        /*
         * Linha visual entre o local escolhido em INÍCIO
         * e o primeiro waypoint real do mapeamento.
         * Não altera o plano nem o KMZ exportado.
         */
        preferredStart?.let { departure ->

            approachLineOverlay =
                Polyline(
                    binding.map
                ).apply {

                    setPoints(
                        listOf(
                            GeoPoint(
                                departure.lat,
                                departure.lon
                            ),
                            GeoPoint(
                                routeStart.lat,
                                routeStart.lon
                            )
                        )
                    )

                    outlinePaint.color =
                        Color.rgb(
                            46,
                            125,
                            50
                        )

                    outlinePaint.strokeWidth =
                        4f

                    val density =
                        resources
                            .displayMetrics
                            .density

                    outlinePaint.pathEffect =
                        DashPathEffect(
                            floatArrayOf(
                                14f * density,
                                9f * density
                            ),
                            0f
                        )

                    title =
                        "Deslocamento até o início da rota"
                }

            approachLineOverlay?.let {
                binding.map.overlays.add(it)
            }
        }

        routeStartMarker =
            Marker(
                binding.map
            ).apply {

                position =
                    GeoPoint(
                        routeStart.lat,
                        routeStart.lon
                    )

                title =
                    "Início do trajeto"

                snippet =
                    String.format(
                        Locale.getDefault(),
                        "Lat %.7f | Lon %.7f",
                        routeStart.lat,
                        routeStart.lon
                    )

                icon =
                    createRouteEndpointIcon(
                        Color.rgb(
                            46,
                            125,
                            50
                        )
                    )

                setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_CENTER
                )

                setOnMarkerClickListener {
                        marker,
                        _ ->

                    marker.showInfoWindow()
                    true
                }
            }

        routeEndMarker =
            Marker(
                binding.map
            ).apply {

                position =
                    GeoPoint(
                        routeEnd.lat,
                        routeEnd.lon
                    )

                title =
                    "Fim do trajeto"

                snippet =
                    String.format(
                        Locale.getDefault(),
                        "Lat %.7f | Lon %.7f",
                        routeEnd.lat,
                        routeEnd.lon
                    )

                icon =
                    createRouteEndpointIcon(
                        Color.rgb(
                            211,
                            47,
                            47
                        )
                    )

                setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_CENTER
                )

                setOnMarkerClickListener {
                        marker,
                        _ ->

                    marker.showInfoWindow()
                    true
                }
            }

        routeStartMarker?.let {
            binding.map.overlays.add(it)
        }

        routeEndMarker?.let {
            binding.map.overlays.add(it)
        }

        applyLayerVisibility()
        binding.map.invalidate()
    }

    private fun createRouteEndpointIcon(
        color: Int
    ): BitmapDrawable {

        val density =
            resources
                .displayMetrics
                .density

        val size =
            (22f * density)
                .toInt()
                .coerceAtLeast(22)

        val radius =
            6f * density

        val bitmap =
            Bitmap.createBitmap(
                size,
                size,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(bitmap)

        val paint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                this.color =
                    color

                style =
                    Paint.Style.FILL
            }

        canvas.drawCircle(
            size / 2f,
            size / 2f,
            radius,
            paint
        )

        paint.style =
            Paint.Style.STROKE

        paint.strokeWidth =
            2f * density

        paint.color =
            Color.WHITE

        canvas.drawCircle(
            size / 2f,
            size / 2f,
            radius,
            paint
        )

        return BitmapDrawable(
            resources,
            bitmap
        )
    }

    private fun createDroneStartIcon():
        BitmapDrawable {

        val density =
            resources
                .displayMetrics
                .density

        val size =
            (42f * density)
                .toInt()
                .coerceAtLeast(42)

        val bitmap =
            Bitmap.createBitmap(
                size,
                size,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(bitmap)

        val center =
            size / 2f

        val arm =
            11f * density

        val rotorRadius =
            4.2f * density

        val whitePaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                color =
                    Color.WHITE

                style =
                    Paint.Style.STROKE

                strokeWidth =
                    5f * density

                strokeCap =
                    Paint.Cap.ROUND
            }

        val dronePaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                color =
                    Color.rgb(
                        13,
                        33,
                        55
                    )

                style =
                    Paint.Style.STROKE

                strokeWidth =
                    2.4f * density

                strokeCap =
                    Paint.Cap.ROUND
            }

        val rotorCenters =
            listOf(
                center - arm to center - arm,
                center + arm to center - arm,
                center - arm to center + arm,
                center + arm to center + arm
            )

        rotorCenters.forEach { rotor ->

            canvas.drawLine(
                center,
                center,
                rotor.first,
                rotor.second,
                whitePaint
            )

            canvas.drawCircle(
                rotor.first,
                rotor.second,
                rotorRadius,
                whitePaint
            )
        }

        rotorCenters.forEach { rotor ->

            canvas.drawLine(
                center,
                center,
                rotor.first,
                rotor.second,
                dronePaint
            )

            canvas.drawCircle(
                rotor.first,
                rotor.second,
                rotorRadius,
                dronePaint
            )
        }

        val bodyWhite =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                color =
                    Color.WHITE

                style =
                    Paint.Style.FILL
            }

        val bodyPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                color =
                    Color.rgb(
                        13,
                        33,
                        55
                    )

                style =
                    Paint.Style.FILL
            }

        canvas.drawCircle(
            center,
            center,
            6f * density,
            bodyWhite
        )

        canvas.drawCircle(
            center,
            center,
            4f * density,
            bodyPaint
        )

        val nosePaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                color =
                    Color.rgb(
                        0,
                        188,
                        212
                    )

                style =
                    Paint.Style.FILL
            }

        canvas.drawCircle(
            center,
            center - 4f * density,
            1.8f * density,
            nosePaint
        )

        return BitmapDrawable(
            resources,
            bitmap
        )
    }

    private fun addDirectionArrows(
        part: List<LatLng>,
        color: Int,
        maxArrows: Int
    ) {

        if (part.size < 2) return


        val indexes = listOf(
            part.size / 4,
            part.size / 2,
            part.size * 3 / 4
        )
            .distinct()
            .filter { it in 1 until part.size }
            .take(maxArrows.coerceAtLeast(1))

        indexes.forEach { index ->
            val from = part[index - 1]
            val to = part[index]
            val bearing = bearingBetween(from, to)

            val marker = Marker(binding.map).apply {
                position = GeoPoint(to.lat, to.lon)
                icon = createDirectionArrowIcon(bearing, color)
                setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_CENTER
                )
                setOnMarkerClickListener { _, _ -> true }
            }

            routeArrowMarkers += marker
            binding.map.overlays.add(marker)
        }
    }

    private fun createDirectionArrowIcon(
        bearingDeg: Double,
        color: Int
    ): BitmapDrawable {

        val density = resources.displayMetrics.density
        val size = (24f * density).toInt().coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(
            size,
            size,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        canvas.rotate(
            bearingDeg.toFloat(),
            size / 2f,
            size / 2f
        )

        val path = Path().apply {
            moveTo(size / 2f, size * 0.12f)
            lineTo(size * 0.82f, size * 0.82f)
            lineTo(size / 2f, size * 0.67f)
            lineTo(size * 0.18f, size * 0.82f)
            close()
        }

        canvas.drawPath(path, paint)
        return BitmapDrawable(resources, bitmap)
    }

    private fun bearingBetween(
        from: LatLng,
        to: LatLng
    ): Double {

        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)

        val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2)
        val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
            kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLon)

        val bearing = Math.toDegrees(kotlin.math.atan2(y, x))
        return (bearing + 360.0) % 360.0
    }

    private fun showLongRouteWarningIfNeeded(
        current: MissionPlan
    ) {

        val minutes =
            ceil(current.stats.estimatedFlightSeconds / 60.0).toInt()

        if (
            minutes <= 20 &&
            current.stats.routeDistanceM <= 5_000.0
        ) {
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Atenção ao plano de voo")
            .setMessage(
                "A rota estimada ficou com aproximadamente $minutes min e " +
                    String.format(
                        Locale.getDefault(),
                        "%.2f km.",
                        current.stats.routeDistanceM / 1000.0
                    ) +
                    "\n\nRevise autonomia, condições de voo e a necessidade de dividir a missão antes de decolar."
            )
            .setPositiveButton("ENTENDI", null)
            .show()
    }

    private fun getDisplayName(uri: Uri): String {

        var name = uri.lastPathSegment.orEmpty()

        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(index).orEmpty()
            }
        }

        return name
    }

    private fun importDxfReference(uri: Uri) {

        runCatching {
            contentResolver.openInputStream(uri)?.use {
                DxfImporter.readPolylines(it)
            } ?: error("Não foi possível abrir o DXF")
        }.onSuccess { polylines ->
            if (polylines.isEmpty()) {
                toast("Nenhuma POLYLINE/LWPOLYLINE encontrada no DXF")
                return@onSuccess
            }
            chooseDxfPolyline(polylines)
        }.onFailure {
            toast("Falha ao importar DXF: ${it.message}")
        }
    }

    private fun chooseDxfPolyline(
        polylines: List<DxfImporter.DxfPolyline>
    ) {

        if (polylines.size == 1) {
            chooseDxfCrs(polylines.first())
            return
        }

        val labels = polylines.map { polyline ->
            val state = if (polyline.closed) "fechada" else "aberta"
            "${polyline.name} | layer ${polyline.layer} | ${polyline.points.size} vértices | $state"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Escolha a polilinha do DXF")
            .setItems(labels) { _, which ->
                chooseDxfCrs(polylines[which])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun chooseDxfCrs(
        polyline: DxfImporter.DxfPolyline
    ) {

        val options = DxfImporter.Crs.values()
        val labels = options.map { it.label }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Sistema de coordenadas do DXF")
            .setItems(labels) { _, which ->
                runCatching {
                    DxfImporter.toLatLng(
                        polyline,
                        options[which]
                    )
                }.onSuccess { points ->
                    referenceBoundary.clear()
                    referenceBoundary.addAll(points)
                    redrawReference(fit = true)
                    toast(
                        "DXF importado como referência: ${points.size} vértices"
                    )
                }.onFailure {
                    toast("Falha ao converter DXF: ${it.message}")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun ensureConsentAndTutorial() {

        val prefs = getSharedPreferences(
            "nv_drone_consent",
            MODE_PRIVATE
        )

        if (
            !prefs.getBoolean("accepted", false) ||
            prefs.getString("termsVersion", null) != "1"
        ) {
            showConsentDialog()
            return
        }

        if (!prefs.getBoolean("tutorialShown", false)) {
            showTutorial(0)
        }
    }

    private fun showConsentDialog() {

        val padding = (18f * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }

        val termsTextView = TextView(this)

        termsTextView.text =
            "O NV Drone Mapping é uma ferramenta de planejamento de voo. " +
                "Antes de qualquer operação, confira no aplicativo DJI a rota, altura, " +
                "Home/RTH, obstáculos, autonomia, condições locais e regras aplicáveis.\n\n" +
                "A importação de arquivos e os cálculos devem ser conferidos pelo usuário antes do voo. " +
                "O aceite abaixo será registrado somente neste aparelho com data/hora, " +
                "versão destes termos e versão do aplicativo."

        termsTextView.textSize = 14f

        val check = CheckBox(this).apply {
            text = "Li e aceito"
        }

        container.addView(termsTextView)
        container.addView(check)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Termos de uso")
            .setView(container)
            .setPositiveButton("LI E ACEITO", null)
            .setNegativeButton("SAIR") { _, _ ->
                finishAffinity()
            }
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = false

            check.setOnCheckedChangeListener { _, checked ->
                positive.isEnabled = checked
            }

            positive.setOnClickListener {
                if (!check.isChecked) return@setOnClickListener

                getSharedPreferences(
                    "nv_drone_consent",
                    MODE_PRIVATE
                ).edit()
                    .putBoolean("accepted", true)
                    .putLong("acceptedAtMs", System.currentTimeMillis())
                    .putString("termsVersion", "1")
                    .putString("appVersion", currentAppVersion())
                    .apply()

                dialog.dismiss()
                showTutorial(0)
            }
        }

        dialog.show()
    }

    private fun currentAppVersion(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")
    }

    private fun showTutorial(step: Int) {

        val steps = listOf(
            "1/8 — Referência" to
                "Use IMPORTAR REF para abrir KML, KMZ ou DXF. O arquivo importado é somente referência visual e não vira o quadro de voo automaticamente.",
            "2/8 — Quadro de voo" to
                "Toque no mapa para desenhar o QUADRO DE VOO. Os pins podem ser arrastados para ajustar os vértices. Toque em um pin para ver suas coordenadas.",
            "3/8 — Início" to
                "Use INÍCIO e depois toque no mapa próximo do lado onde deseja começar. O plano mantém a mesma geometria e prioriza o extremo mais próximo desse ponto.",
            "4/8 — Gerar plano" to
                "Defina altura, velocidade e sobreposições e toque em APLICAR PLANO. Use -15°, +15° e INVERTER sem alterar o quadro desenhado.",
            "5/8 — Mapa e camadas" to
                "SAT/MAP troca apenas o fundo do mapa. CAMADAS permite mostrar ou ocultar Referência, Quadro e Plano sem apagar os dados.",
            "6/8 — Avançado" to
                "Toque em AVANÇADO para expandir ou recolher os campos DJI de gimbal, máximo de pontos e código DJI.",
            "7/8 — Conferência" to
                "As setas sobre a rota mostram o sentido de deslocamento. Confira área, GSD, fotos, distância e tempo antes de exportar.",
            "8/8 — Salvar e exportar" to
                "SALVAR guarda referência, quadro, parâmetros, início preferido e o plano atual. EXPORTAR KMZ mostra um resumo antes de criar a missão. O GUIA DJI continua disponível para o fluxo de transferência."
        )

        if (step !in steps.indices) {
            markTutorialShown()
            return
        }

        val current = steps[step]
        val last = step == steps.lastIndex

        AlertDialog.Builder(this)
            .setTitle(current.first)
            .setMessage(current.second)
            .setPositiveButton(if (last) "CONCLUIR" else "PRÓXIMO") { _, _ ->
                if (last) {
                    markTutorialShown()
                } else {
                    showTutorial(step + 1)
                }
            }
            .setNegativeButton("PULAR") { _, _ ->
                markTutorialShown()
            }
            .show()
    }

    private fun markTutorialShown() {
        getSharedPreferences(
            "nv_drone_consent",
            MODE_PRIVATE
        ).edit()
            .putBoolean("tutorialShown", true)
            .apply()
    }

    private fun showPreviewKmlMenu() {

        val options =
            arrayOf(
                "Abrir em aplicativo",
                "Compartilhar",
                "Salvar no dispositivo"
            )

        AlertDialog.Builder(
            this
        )
            .setTitle(
                "Prévia KML"
            )
            .setItems(
                options
            ) {
                    _,
                    which ->

                when (
                    which
                ) {

                    0 -> {

                        openPreviewKml()
                    }

                    1 -> {

                        sharePreviewKml()
                    }

                    2 -> {

                        previewLauncher.launch(
                            "NV_Mapping_preview.kml"
                        )
                    }
                }
            }
            .setNegativeButton(
                "Cancelar",
                null
            )
            .show()
    }

    private fun createPreviewKmlFile():
        File {

        val current =
            plan
                ?: error(
                    "Gere o plano antes da prévia"
                )

        val dir =
            File(
                cacheDir,
                "exports"
            ).apply {

                mkdirs()
            }

        val file =
            File(
                dir,
                "NV_Mapping_preview.kml"
            )

        file.outputStream().use { out ->

            writePreviewKml(
                current,
                out
            )
        }

        return file
    }

    private fun openPreviewKml() {

        runCatching {

            val file =
                createPreviewKmlFile()

            val uri =
                FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file
                )

            val intent =
                Intent(
                    Intent.ACTION_VIEW
                ).apply {

                    setDataAndType(
                        uri,
                        "application/vnd.google-earth.kml+xml"
                    )

                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

            startActivity(

                Intent.createChooser(
                    intent,
                    "Abrir prévia KML com"
                )
            )

        }.onFailure {

            toast(
                "Não foi possível abrir a prévia: ${it.message}"
            )
        }
    }

    private fun sharePreviewKml() {

        runCatching {

            val file =
                createPreviewKmlFile()

            val uri =
                FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file
                )

            val intent =
                Intent(
                    Intent.ACTION_SEND
                ).apply {

                    type =
                        "application/vnd.google-earth.kml+xml"

                    putExtra(
                        Intent.EXTRA_STREAM,
                        uri
                    )

                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

            startActivity(

                Intent.createChooser(
                    intent,
                    "Compartilhar prévia KML"
                )
            )

        }.onFailure {

            toast(
                "Erro ao compartilhar prévia: ${it.message}"
            )
        }
    }

    private fun writePreviewKml(
        current: MissionPlan,
        out: OutputStream
    ) {

        OutputStreamWriter(
            out,
            Charsets.UTF_8
        ).use { writer ->

            fun coords(
                points: List<LatLng>
            ): String {

                if (
                    points.isEmpty()
                ) {

                    return ""
                }

                return points.joinToString(
                    " "
                ) {

                    "${it.lon},${it.lat},0"
                }
            }

            fun closedCoords(
                points: List<LatLng>
            ): String {

                if (
                    points.isEmpty()
                ) {

                    return ""
                }

                val closed =
                    if (
                        points.first() ==
                        points.last()
                    ) {

                        points

                    } else {

                        points +
                            points.first()
                    }

                return coords(
                    closed
                )
            }

            writer.appendLine(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            )

            writer.appendLine(
                "<kml xmlns=\"http://www.opengis.net/kml/2.2\">"
            )

            writer.appendLine(
                "<Document>"
            )

            writer.appendLine(
                "<name>NV Drone Mapping - Prévia</name>"
            )

            writer.appendLine(
                "<Style id=\"ref\"><LineStyle><color>ffff7a00</color><width>3</width></LineStyle></Style>"
            )

            writer.appendLine(
                "<Style id=\"frame\"><LineStyle><color>ff0080ff</color><width>4</width></LineStyle></Style>"
            )

            writer.appendLine(
                "<Style id=\"route\"><LineStyle><color>ff00ffff</color><width>4</width></LineStyle></Style>"
            )

            if (
                referenceBoundary.size >= 2
            ) {

                writer.appendLine(
                    "<Placemark>"
                )

                writer.appendLine(
                    "<name>Referência</name><styleUrl>#ref</styleUrl>"
                )

                writer.appendLine(
                    "<LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode>"
                )

                writer.appendLine(
                    "<coordinates>${
                        closedCoords(
                            referenceBoundary
                        )
                    }</coordinates></LineString>"
                )

                writer.appendLine(
                    "</Placemark>"
                )
            }

            if (
                flightBoundary.size >= 2
            ) {

                writer.appendLine(
                    "<Placemark>"
                )

                writer.appendLine(
                    "<name>Quadro de voo</name><styleUrl>#frame</styleUrl>"
                )

                writer.appendLine(
                    "<LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode>"
                )

                writer.appendLine(
                    "<coordinates>${
                        closedCoords(
                            flightBoundary
                        )
                    }</coordinates></LineString>"
                )

                writer.appendLine(
                    "</Placemark>"
                )
            }

            current.parts.forEachIndexed {
                    index,
                    part ->

                if (
                    part.size >= 2
                ) {

                    writer.appendLine(
                        "<Placemark>"
                    )

                    writer.appendLine(
                        "<name>Traçado ${index + 1}</name><styleUrl>#route</styleUrl>"
                    )

                    writer.appendLine(
                        "<LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode>"
                    )

                    writer.appendLine(
                        "<coordinates>${
                            coords(
                                part
                            )
                        }</coordinates></LineString>"
                    )

                    writer.appendLine(
                        "</Placemark>"
                    )
                }
            }

            writer.appendLine(
                "</Document>"
            )

            writer.appendLine(
                "</kml>"
            )
        }
    }

    private fun requestLocationAndLocate() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) ==
            PackageManager.PERMISSION_GRANTED ||

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) ==
            PackageManager.PERMISSION_GRANTED
        ) {

            locateUser()

        } else {

            locationPermissionLauncher.launch(

                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @Suppress(
        "MissingPermission"
    )
    private fun locateUser() {

        val lm =
            getSystemService(
                LOCATION_SERVICE
            ) as LocationManager

        val providers =
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            )

        val last =
            providers
                .mapNotNull { provider ->

                    runCatching {

                        lm.getLastKnownLocation(
                            provider
                        )

                    }.getOrNull()
                }
                .maxByOrNull {

                    it.time
                }

        if (
            last != null
        ) {

            val point =
                GeoPoint(
                    last.latitude,
                    last.longitude
                )

            userLocationMarker?.let {

                binding.map.overlays.remove(
                    it
                )
            }

            userLocationMarker =
                Marker(
                    binding.map
                ).apply {

                    position =
                        point

                    title =
                        "Minha localização aproximada"

                    snippet =
                        String.format(
                            Locale.getDefault(),
                            "Lat %.6f | Lon %.6f | precisão ~%.0f m",
                            last.latitude,
                            last.longitude,
                            last.accuracy
                        )

                    /*
                     * SUA LOCALIZAÇÃO =
                     * PONTO AZUL PEQUENO.
                     */
                    icon =
                        createUserLocationDotIcon()

                    setAnchor(
                        Marker.ANCHOR_CENTER,
                        Marker.ANCHOR_CENTER
                    )
                }

            binding.map.overlays.add(
                userLocationMarker
            )

            binding.map.controller.animateTo(
                point
            )

            binding.map.controller.setZoom(
                16.5
            )

            binding.map.invalidate()

            toast(
                "Localização aproximada marcada em azul"
            )

            return
        }

        toast(
            "Ative a localização do celular e toque em ⌖ novamente"
        )
    }

    private fun defaultKmzName(
        index: Int,
        total: Int
    ): String {

        val stamp =
            SimpleDateFormat(
                "yyyyMMdd_HHmm",
                Locale.US
            ).format(
                Date()
            )

        return if (
            total > 1
        ) {

            "NV_Mapping_${stamp}_${index + 1}_of_${total}.kmz"

        } else {

            "NV_Mapping_${stamp}.kmz"
        }
    }

    private fun formatArea(
        areaM2: Double
    ): String {

        return if (
            areaM2 >= 10_000
        ) {

            String.format(
                Locale.getDefault(),
                "%.3f ha",
                areaM2 /
                    10_000.0
            )

        } else {

            String.format(
                Locale.getDefault(),
                "%.0f m²",
                areaM2
            )
        }
    }

    private fun trimNumber(
        v: Double
    ): String {

        return if (
            v % 1.0 == 0.0
        ) {

            v.toInt()
                .toString()

        } else {

            String.format(
                Locale.US,
                "%.2f",
                v
            )
        }
    }

    private fun normalizeBearing(
        value: Double
    ): Double {

        val normalized =
            value %
                180.0

        return if (
            normalized < 0.0
        ) {

            normalized +
                180.0

        } else {

            normalized
        }
    }

    private fun toast(
        message: String
    ) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onResume() {

        super.onResume()

        binding.map.onResume()
    }

    override fun onPause() {

        binding.map.onPause()

        super.onPause()
    }
}
