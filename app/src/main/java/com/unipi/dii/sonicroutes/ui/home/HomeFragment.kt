package com.unipi.dii.sonicroutes.ui.home

import GeocodingUtil
import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.gson.Gson
import com.unipi.dii.sonicroutes.R
import com.unipi.dii.sonicroutes.model.Apis
import com.unipi.dii.sonicroutes.model.Crossing
import com.unipi.dii.sonicroutes.model.Edge
import com.unipi.dii.sonicroutes.model.NoiseData
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class HomeFragment : Fragment(), OnMapReadyCallback, GoogleMap.OnMapClickListener {
    private lateinit var map: GoogleMap
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private lateinit var userLocation: LatLng
    private lateinit var geocodingUtil: GeocodingUtil
    private var isSelectingPoints = false
    private lateinit var selectPointsButton: Button
    private val markers = ArrayList<Crossing>()
    private var firstPoint: LatLng? = null
    private val route = ArrayList<Edge>() // contiene gli edge tra i checkpoint, serve per ricostruire il percorso ed avere misura del rumore
    private var cumulativeNoise = 0.0 // tiene conto del rumore cumulativo in un edge (percorso tra due checkpoint)
    private var numberOfMeasurements = 0 // tiene conto del numero di misurazioni effettuate in un edge
    private var lastCheckpoint: Crossing? = null // contiene l'ultimo checkpoint visitato, serve per capire se si è in un nuovo checkpoint
    private var filename = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        mapFragment?.getMapAsync(this)
        val startRecordingButton = view.findViewById<View>(R.id.startRecordingButton) as Button
        changeButtonColor(startRecordingButton)
        startRecordingButton.setOnClickListener { toggleRecording(startRecordingButton) }
        geocodingUtil = GeocodingUtil(requireContext())

        // Check and request GPS enablement if not enabled
        checkAndPromptToEnableGPS(startRecordingButton)
    }

    private fun checkAndPromptToEnableGPS(startRecordingButton: Button) {
        val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            // GPS is not enabled, show dialog to enable it
            val builder = AlertDialog.Builder(requireContext())
            builder.setMessage("Il GPS è disabilitato. \nSi prega di abilitare il GPS per utilizzare l'applicazione.")
            builder.setPositiveButton("Abilita") { _, _ ->
                // Open GPS settings screen
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            builder.setNegativeButton("Annulla") { dialog, _ ->
                dialog.dismiss()
            }
            builder.create().show()
        } /*else {
            // GPS is enabled, enable the startRecordingButton
            startRecordingButton.isEnabled = true
        }*/
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        checkPermissionsAndSetupMap()
        addMarkersFromCSV() // Add markers from CSV when the map is ready

        map.setOnMapClickListener(this)

        selectPointsButton = view?.findViewById(R.id.selectPointsButton)!!
        selectPointsButton.setOnClickListener {
            // Abilita la modalità di selezione dei punti sulla mappa
            enablePointSelection()
        }
    }

    private fun enablePointSelection() {
        isSelectingPoints = true
        // Modifica il testo del pulsante per indicare all'utente lo stato corrente
        selectPointsButton.text = "Seleziona il primo punto"
    }

    override fun onMapClick(point: LatLng) {
        if (isSelectingPoints) {
            // Gestisci la selezione dei punti in base allo stato corrente
            if (selectPointsButton.text == "Seleziona il primo punto") {
                // Salva il primo punto selezionato
                firstPoint = point
                selectPointsButton.text = "Seleziona il secondo punto"
            } else if (selectPointsButton.text == "Seleziona il secondo punto") {
                // Salva il secondo punto selezionato
                val secondPoint = point
                // Invia le coordinate dei punti al server
                sendPointsToServer(firstPoint!!, secondPoint)
                // Disabilita la modalità di selezione dei punti e ripristina il testo del pulsante
                isSelectingPoints = false
                selectPointsButton.text = "Seleziona punti sulla mappa"
            }
        }
    }

    private fun sendPointsToServer(firstPoint: LatLng, secondPoint: LatLng) {
        // Invia le coordinate dei punti al server utilizzando le API appropriate
        // Qui puoi implementare la logica per inviare le coordinate al tuo server
    }

    private fun addMarkersFromCSV() {
        try {
            val inputStream = resources.openRawResource(R.raw.intersections_clustered)
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            reader.readLine() // Skip the header
            while (reader.readLine().also { line = it } != null) {
                val columns = line!!.split(",")
                val id = columns[0].toInt()
                val latitude = columns[1].toDouble()
                val longitude = columns[2].toDouble()
                val streetName = columns[3].split(";").map { it.trim() } // Trim each street name
                //todo : inutile o no? direi di sì
                val streetCounter = columns[4].toInt() // street counter is at index 4

                // Create a POI object with latitude, longitude, and street name
                val poi = Crossing(id, latitude, longitude, streetName)

                // Add the POI object to the markers list
                markers.add(poi)
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("HomeFragment", "Error adding markers from CSV: ${e.message}")
        }
    }

    private fun checkPermissionsAndSetupMap() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            setupMap()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            setupMap()
        } else {
            showMessageToUser("Per favore, concedere il permesso per la localizzazione.")
        }
    }

    private fun setupMap() {

        try {
            map.isMyLocationEnabled = true
            map.uiSettings.isMyLocationButtonEnabled = true
            startLocationUpdates()
        } catch (e: SecurityException) {
            Log.e("HomeFragment", "Security Exception: ${e.message}")
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(5000)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (locationResult.locations.isNotEmpty()) {
                    val location = locationResult.locations.first()
                    updateMap(location)
                }
            }
        }

        try {
            fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(requireActivity())
            fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e("HomeFragment", "Security Exception while requesting location updates: ${e.message}")
        }
    }

    private fun updateMap(location: Location) {
        if(location.latitude !=0.0){
            userLocation = LatLng(location.latitude, location.longitude)
        }

        map.animateCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 18f))
        geocodingUtil.getAddressFromLocation(location.latitude, location.longitude) { address ->
            println(address)
            // todo : forse sto address è inutile, ora i controlli sono sulle 'streets' dei crossing
        }
    }

    override fun onResume() {
        super.onResume()
        if(isRecording)
            checkPermissionsAndSetupRecording()
    }

    private fun checkPermissionsAndSetupRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startNoiseRecording()
        }
    }

    private fun startNoiseRecording() {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build())
                .setBufferSizeInBytes(minBufferSize)
                .build()

            audioRecord?.startRecording()

            val audioData = ShortArray(minBufferSize)
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed(object : Runnable {
                override fun run() {
                    if (isRecording) {
                        val readResult = audioRecord?.read(audioData, 0, minBufferSize, AudioRecord.READ_BLOCKING) ?: -1
                        if (readResult >= 0) {
                            processRecordingData(audioData)
                        } else {
                            Log.e("HomeFragment", "Error reading audio data")
                        }
                        handler.postDelayed(this, 5000)
                    }
                }
            }, 5000)
        } catch (e: SecurityException) {
            Log.e("HomeFragment", "Security Exception during audio recording setup: ${e.message}")
        }
    }

    private fun findNearestMarker(userLocation: LatLng, markerList: ArrayList<Crossing>): LatLng? {
        var nearestMarker: Crossing? = null
        var minDistance = Double.MAX_VALUE

        for (marker in markerList) {
            val distance = calculateDistance(userLocation, marker.getLatLng())
            if (distance < minDistance) {
                minDistance = distance
                nearestMarker = marker
            }
        }
        Log.d("HomeFragment", "Distance: $minDistance")
        var contains = false
        // controllo se route.last.getStreetname() contiene almeno una street in comune con nearestmarker
        if (lastCheckpoint!=null && nearestMarker != null && lastCheckpoint!!.getCrossingId() != nearestMarker.getCrossingId()) {
            for (name in lastCheckpoint!!.getStreetName()) {
                if (nearestMarker.getStreetName().contains(name)) {
                    contains = true // essentially, this is saying that we are in a new checkpoint
                    break
                }
            }
        }
        if (minDistance < 40 && (lastCheckpoint == null || contains)) { // se entro qui sono in nuovo checkpoint
            if(lastCheckpoint!=null) { // se non sono al primo checkpoint, allora creo un edge tra il nuovo checkpoint ed il precedente
                val edge = Edge(lastCheckpoint!!.getCrossingId(), nearestMarker!!.getCrossingId(), cumulativeNoise, numberOfMeasurements)
                route.add(edge)
                // stampo l'edge per debug
                Log.d("HomeFragment", "Edge: $edge")
                // reset delle variabili per il prossimo checkpoint
                cumulativeNoise = 0.0
                numberOfMeasurements = 0
                // invio l'edge al server
                Apis(requireContext()).uploadEdge(edge)
            }
            if (nearestMarker != null) {
                lastCheckpoint = nearestMarker
                return nearestMarker.getLatLng()
            }
        }
        return null
    }

    private fun calculateDistance(location1: LatLng, location2: LatLng): Double {
        val radius = 6371.0 // Raggio della Terra in chilometri
        val latDistance = Math.toRadians(location2.latitude - location1.latitude)
        val lonDistance = Math.toRadians(location2.longitude - location1.longitude)
        val a = sin(latDistance / 2) * sin(latDistance / 2) +
                cos(Math.toRadians(location1.latitude)) * cos(Math.toRadians(location2.latitude)) *
                sin(lonDistance / 2) * sin(lonDistance / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return radius * c * 1000 // Converti in metri
    }

    private fun processRecordingData(audioData: ShortArray) {
        if (isRecording &&::userLocation.isInitialized && audioData.isNotEmpty()) {

            if (lastCheckpoint!=null) { // sound not recorded until at least one checkpoint is reached
                val amplitude = audioData.maxOrNull()?.toInt() ?: 0
                Log.d("HomeFragment", "Current Noise Level: $amplitude")
                val jsonEntry = Gson().toJson(
                    NoiseData(
                        latitude = userLocation.latitude,
                        longitude = userLocation.longitude,
                        amplitude = amplitude,
                        timestamp = System.currentTimeMillis()
                    )
                )
                cumulativeNoise += amplitude
                numberOfMeasurements++
                Log.d("HomeFragment", "Cumulative Noise: $cumulativeNoise")
                Log.d("HomeFragment", "Number of Measurements: $numberOfMeasurements")
                Log.d("HomeFragment", "JSON Entry: $jsonEntry")

                val file = File(context?.filesDir, filename)
                try {
                    FileOutputStream(file, true).use { fos ->
                        OutputStreamWriter(fos).use { writer ->
                            writer.write(jsonEntry + "\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HomeFragment", "Failed to write data to file", e)
                }

            }

            findNearestMarker(userLocation, markers)?.let { nearestMarker ->
                Log.d("HomeFragment", "Nearest Marker: $nearestMarker")

                // Aggiungi il marker più vicino alla mappa con un colore diverso
                map.addMarker(
                    MarkerOptions()
                        .position(nearestMarker)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                )
            }

        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            try {
                audioRecord?.stop() // Ferma la registrazione
                audioRecord?.release() // Rilascia le risorse dell'oggetto AudioRecord
                isRecording = false // Imposta lo stato di registrazione su falso
            } catch (e: SecurityException) {
                Log.e("HomeFragment", "Security Exception during audio recording stop: ${e.message}")
            }
        }
    }

    private fun showMessageToUser(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("ATTENZIONE!")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }


    private fun toggleRecording(startRecordingButton: Button) {
        isRecording = !isRecording
        if (isRecording) {
            startRecordingButton.text = getString(R.string.stop_recording)
            startRecordingButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_light))
            checkPermissionsAndSetupRecording()
            // Add markers to the map
            for (marker in markers) {
                map.addMarker(MarkerOptions().position(marker.getLatLng()))
            }

            val filenamePrefix = "data_"
            val filesDir = context?.filesDir
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            // Costruisci il nome del nuovo file utilizzando il timestamp attuale
            filename = "$filenamePrefix$timestamp.json"

            // Ottieni la lista dei file nella directory (debug)
            filesDir?.listFiles()?.forEach {
                Log.d("HomeFragment", "File nella directory: ${it.name}")
            }

            // Creo un file con timestamp corrente
            File(filesDir, filename)

        } else {
            stopRecording()
            startRecordingButton.text = getString(R.string.start_recording)
            startRecordingButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_purple))
            map.clear()
            lastCheckpoint = null
        }
    }

    private fun changeButtonColor(startRecordingButton: Button) {
        if(!isRecording) {
            startRecordingButton.setBackgroundColor(
                ContextCompat.getColor(
                    requireContext(),
                    android.R.color.holo_purple
                )
            )
        }else {
            startRecordingButton.setBackgroundColor(
                ContextCompat.getColor(
                    requireContext(),
                    android.R.color.holo_red_light
                )
            )
            startRecordingButton.text = getString(R.string.stop_recording)
        }
    }
}
