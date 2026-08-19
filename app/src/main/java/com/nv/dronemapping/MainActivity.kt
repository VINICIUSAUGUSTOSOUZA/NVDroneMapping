package com.nv.dronemapping

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
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
import com.nv.dronemapping.io.KmlImporter
import com.nv.dronemapping.model.LatLng
import com.nv.dronemapping.model.MissionPlan
import com.nv.dronemapping.model.MissionSettings
import com.nv.dronemapping.model.SavedProject
import com.nv.dronemapping.storage.ProjectStore
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ProjectStore

    private val boundary = mutableListOf<LatLng>()
    private val vertexMarkers = mutableListOf<Marker>()
    private val routeOverlays = mutableListOf<Polyline>()

    private var boundaryOverlay: Polygon? = null
    private var plan: MissionPlan? = null
    private var pendingExportPart = 0

    private var userLocationMarker: Marker? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->

            uri ?: return@registerForActivityResult

            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            runCatching {
                KmlImporter.importBoundary(contentResolver, uri)
            }
                .onSuccess { points ->

                    boundary.clear()
                    boundary.addAll(points)

                    invalidatePlan()

                    redrawBoundary(
                        fit = true
                    )

                    updateStatsAreaOnly()

                    toast(
                        "Perímetro importado: ${points.size} vértices"
                    )
                }
                .onFailure {

                    toast(
                        "Falha ao importar: ${it.message}"
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
            }
                .onSuccess {

                    toast(
                        "Missão DJI salva com sucesso"
                    )
                }
                .onFailure {

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
                    ?.use {

                        KmzExporter.writePreviewKml(
                            current,
                            it
                        )
                    }
                    ?: error(
                        "Não foi possível criar a prévia"
                    )
            }
                .onSuccess {

                    toast(
                        "Prévia KML salva"
                    )
                }
                .onFailure {

                    toast(
                        "Erro ao salvar prévia: ${it.message}"
                    )
                }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->

            if (grants.values.any { it }) {

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

        super.onCreate(savedInstanceState)

        binding =
            ActivityMainBinding.inflate(
                layoutInflater
            )

        setContentView(
            binding.root
        )

        store =
            ProjectStore(this)

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

        updateStatsAreaOnly()
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

        val events =
            MapEventsOverlay(
                object :
                    MapEventsReceiver {

                    override fun singleTapConfirmedHelper(
                        p: GeoPoint
                    ): Boolean {

                        boundary += LatLng(
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

        binding.btnUndo
            .setOnClickListener {

                if (boundary.isNotEmpty()) {

                    boundary.removeLast()

                    invalidatePlan()

                    redrawBoundary(
                        false
                    )

                    updateStatsAreaOnly()
                }
            }

        binding.btnClear
            .setOnClickListener {

                boundary.clear()

                invalidatePlan()

                redrawBoundary(
                    false
                )

                updateStatsAreaOnly()
            }

        binding.btnImport
            .setOnClickListener {

                importLauncher.launch(
                    arrayOf(
                        "application/vnd.google-earth.kml+xml",
                        "application/vnd.google-earth.kmz",
                        "application/zip",
                        "application/octet-stream",
                        "text/xml",
                        "text/plain"
                    )
                )
            }

        binding.btnGenerate
            .setOnClickListener {

                generateMission()
            }

        binding.btnExport
            .setOnClickListener {

                choosePartAndExport()
            }

        binding.btnShare
            .setOnClickListener {

                shareMission()
            }

        binding.btnSaveProject
            .setOnClickListener {

                showSaveProjectDialog()
            }

        binding.btnOpenProject
            .setOnClickListener {

                showProjectsDialog()
            }

        /*
         * BOTÃO ⌖
         * LOCALIZAÇÃO APROXIMADA
         */

        binding.btnMyLocation
            .setOnClickListener {

                requestLocationAndLocate()
            }

        /*
         * BOTÃO ▣
         * ENQUADRAR ÁREA
         */

        binding.btnFitBoundary
            .setOnClickListener {

                if (boundary.isNotEmpty()) {

                    fitToPoints(
                        boundary
                    )

                } else {

                    toast(
                        "Desenhe ou importe um perímetro primeiro"
                    )
                }
            }

        binding.btnPreset2d
            .setOnClickListener {

                apply2dPreset()
            }

        binding.btnPreset3d
            .setOnClickListener {

                apply3dPreset()
            }

        binding.btnPreviewKml
            .setOnClickListener {

                if (plan != null) {

                    previewLauncher.launch(
                        "NV_Mapping_preview.kml"
                    )
                }
            }

        binding.btnDjiGuide
            .setOnClickListener {

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
            }

        binding.inBearing.isEnabled =
            !binding.checkAutoBearing.isChecked
    }

    private fun generateMission() {

        if (boundary.size < 3) {

            toast(
                "Desenhe ou importe um perímetro com pelo menos 3 vértices"
            )

            return
        }

        val settings =
            readSettings()
                ?: return

        runCatching {

            GridPlanner.plan(
                boundary.toList(),
                settings
            )
        }
            .onSuccess { generated ->

                plan =
                    generated

                binding.btnExport.isEnabled =
                    true

                binding.btnShare.isEnabled =
                    true

                binding.btnPreviewKml.isEnabled =
                    true

                drawRoute(
                    generated
                )

                updateStats(
                    generated
                )

                binding.txtHint.text =
                    if (
                        generated.parts.size > 1
                    ) {

                        "Missão dividida automaticamente em ${generated.parts.size} partes"

                    } else {

                        "Missão pronta: revise a rota antes de exportar"
                    }
            }
            .onFailure {

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

            if (v == null) {

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
            binding.inMaxWaypoints
                .text
                ?.toString()
                ?.toIntOrNull()

        val droneEnum =
            binding.inDroneEnum
                .text
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
                binding
                    .checkAutoBearing
                    .isChecked,

            crossHatch =
                binding
                    .checkCrossHatch
                    .isChecked,

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

        boundaryOverlay
            ?.let {

                binding.map
                    .overlays
                    .remove(it)
            }

        vertexMarkers
            .forEach {

                binding.map
                    .overlays
                    .remove(it)
            }

        vertexMarkers.clear()

        if (
            boundary.size >= 2
        ) {

            boundaryOverlay =
                Polygon(
                    binding.map
                ).apply {

                    points =
                        boundary.map {

                            GeoPoint(
                                it.lat,
                                it.lon
                            )
                        }

                    outlinePaint.color =
                        Color.rgb(
                            13,
                            93,
                            165
                        )

                    outlinePaint.strokeWidth =
                        5f

                    fillPaint.color =
                        Color.argb(
                            45,
                            47,
                            128,
                            237
                        )
                }

            binding.map
                .overlays
                .add(
                    boundaryOverlay
                )
        }

        boundary
            .forEachIndexed {
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
                            "Vértice ${index + 1}"

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

                                    boundary[index] =
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

                vertexMarkers +=
                    marker

                binding.map
                    .overlays
                    .add(
                        marker
                    )
            }

        binding.map.invalidate()

        if (
            fit &&
            boundary.isNotEmpty()
        ) {

            fitToPoints(
                boundary
            )
        }
    }

    private fun drawRoute(
        plan: MissionPlan
    ) {

        routeOverlays
            .forEach {

                binding.map
                    .overlays
                    .remove(it)
            }

        routeOverlays.clear()

        val colors =
            intArrayOf(

                Color.rgb(
                    0,
                    150,
                    136
                ),

                Color.rgb(
                    255,
                    152,
                    0
                ),

                Color.rgb(
                    156,
                    39,
                    176
                ),

                Color.rgb(
                    0,
                    121,
                    107
                ),

                Color.rgb(
                    198,
                    40,
                    40
                )
            )

        plan.parts
            .forEachIndexed {
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
                                idx % colors.size
                            ]

                        outlinePaint.strokeWidth =
                            5f

                        title =
                            "Missão ${idx + 1}/${plan.parts.size}"
                    }

                routeOverlays +=
                    line

                binding.map
                    .overlays
                    .add(
                        line
                    )
            }

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

            binding.map
                .zoomToBoundingBox(
                    box,
                    true,
                    80
                )
        }
    }

    private fun updateStatsAreaOnly() {

        val area =
            if (
                boundary.size >= 3
            ) {

                GeoMath.polygonAreaM2(
                    boundary
                )

            } else {

                0.0
            }

        binding.txtStats.text =
            if (
                area > 0
            ) {

                "Área: ${formatArea(area)} | Toque em GERAR MISSÃO"

            } else {

                "Área: -- | GSD: -- | Fotos: -- | Tempo: --"
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
            )
                .toInt()

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

    private fun choosePartAndExport() {

        val current =
            plan ?: return

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
            current.parts
                .indices
                .map { i ->

                    "Parte ${i + 1}/${current.parts.size} — ${current.parts[i].size} waypoints"
                }
                .toTypedArray()

        AlertDialog
            .Builder(this)
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
            plan ?: return

        runCatching {

            val dir =
                File(
                    cacheDir,
                    "exports"
                ).apply {

                    mkdirs()
                }

            val uris =
                current.parts
                    .mapIndexed {
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

                        file
                            .outputStream()
                            .use {

                                KmzExporter.writeKmz(
                                    current,
                                    index,
                                    "NV_Mapping",
                                    it
                                )
                            }

                        FileProvider
                            .getUriForFile(
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
        }
            .onFailure {

                toast(
                    "Erro ao compartilhar: ${it.message}"
                )
            }
    }

    private fun showSaveProjectDialog() {

        if (
            boundary.size < 3
        ) {

            toast(
                "Desenhe um perímetro antes de salvar"
            )

            return
        }

        val input =
            EditText(this)
                .apply {

                    hint =
                        "Nome do projeto"

                    setText(

                        "Mapeamento ${
                            SimpleDateFormat(
                                "dd-MM HHmm",
                                Locale.getDefault()
                            )
                                .format(
                                    Date()
                                )
                        }"
                    )

                    selectAll()
                }

        AlertDialog
            .Builder(this)
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
                        name,
                        boundary.toList(),
                        settings,
                        System.currentTimeMillis()
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
            projects
                .map { p ->

                    val date =
                        SimpleDateFormat(
                            "dd/MM/yyyy HH:mm",
                            Locale.getDefault()
                        )
                            .format(
                                Date(
                                    p.savedAtMs
                                )
                            )

                    "${p.name}\n$date"
                }
                .toTypedArray()

        AlertDialog
            .Builder(this)
            .setTitle(
                "Projetos salvos"
            )
            .setItems(
                labels
            ) {
                    _,
                    which ->

                loadProject(
                    projects[which]
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
            }
                .toTypedArray()

        AlertDialog
            .Builder(this)
            .setTitle(
                "Excluir projeto"
            )
            .setItems(
                names
            ) {
                    _,
                    which ->

                AlertDialog
                    .Builder(this)
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

        boundary.clear()

        boundary.addAll(
            project.boundary
        )

        applySettings(
            project.settings
        )

        invalidatePlan()

        redrawBoundary(
            true
        )

        updateStatsAreaOnly()

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

        updateStatsAreaOnly()

        toast(
            "Preset 2D: 60 m, 80/70 e gimbal -90°"
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

        updateStatsAreaOnly()

        toast(
            "Preset cruzado aplicado"
        )
    }

    private fun showDjiGuide() {

        AlertDialog
            .Builder(this)
            .setTitle(
                "Levar a missão ao DJI Fly"
            )
            .setMessage(

                "1. Gere a missão e confira a rota no mapa.\n\n" +

                    "2. Exporte o KMZ DJI.\n\n" +

                    "3. No DJI Fly, crie e salve uma missão Waypoint temporária.\n\n" +

                    "4. No armazenamento do aparelho ou controle, substitua o KMZ da missão temporária pelo KMZ exportado pelo NV Drone Mapping.\n\n" +

                    "5. Reabra a missão no DJI Fly.\n\n" +

                    "6. CONFIRA altura, rota, RTH, gimbal e ações de fotografia antes de iniciar.\n\n" +

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

        routeOverlays
            .forEach {

                binding.map
                    .overlays
                    .remove(it)
            }

        routeOverlays.clear()

        binding.map.invalidate()

        binding.txtHint.text =
            "Toque no mapa para desenhar o perímetro"
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

            /*
             * REMOVE MARCADOR ANTIGO
             */

            userLocationMarker
                ?.let {

                    binding.map
                        .overlays
                        .remove(it)
                }

            /*
             * MOSTRA SUA LOCALIZAÇÃO
             * NO MAPA
             */

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

                            "Lat %.6f | Lon %.6f",

                            last.latitude,

                            last.longitude
                        )

                    setAnchor(

                        Marker.ANCHOR_CENTER,

                        Marker.ANCHOR_BOTTOM
                    )
                }

            binding.map
                .overlays
                .add(
                    userLocationMarker
                )

            /*
             * CENTRALIZA O MAPA
             */

            binding.map
                .controller
                .animateTo(
                    point
                )

            binding.map
                .controller
                .setZoom(
                    16.5
                )

            binding.map.invalidate()

            toast(
                "Localização aproximada encontrada"
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
            )
                .format(
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

    private fun toast(
        message: String
    ) {

        Toast
            .makeText(
                this,
                message,
                Toast.LENGTH_LONG
            )
            .show()
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
