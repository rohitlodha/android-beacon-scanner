package com.bridou_n.beaconscanner.features.beaconList

import android.media.Rating
import android.os.Bundle
import android.os.RemoteException
import android.support.design.widget.BottomSheetBehavior
import android.support.v4.widget.NestedScrollView
import android.util.Log
import com.bridou_n.beaconscanner.API.LoggingService
import com.bridou_n.beaconscanner.events.Events
import com.bridou_n.beaconscanner.events.RxBus
import com.bridou_n.beaconscanner.models.BeaconSaved
import com.bridou_n.beaconscanner.models.LoggingRequest
import com.bridou_n.beaconscanner.utils.BluetoothManager
import com.bridou_n.beaconscanner.utils.PreferencesHelper
import com.bridou_n.beaconscanner.utils.RatingHelper
import com.google.firebase.analytics.FirebaseAnalytics
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.functions.BiFunction
import io.reactivex.schedulers.Schedulers
import io.realm.Realm
import io.realm.RealmResults
import io.realm.Sort
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.Region
import org.altbeacon.beacon.utils.UrlBeaconUrlCompressor
import java.util.*
import java.util.concurrent.TimeUnit


/**
 * Created by bridou_n on 22/08/2017.
 */

class BeaconListPresenter(val view: BeaconListContract.View,
                          val rxBus: RxBus,
                          val prefs: PreferencesHelper,
                          val realm: Realm,
                          val loggingService: LoggingService,
                          val bluetoothState: BluetoothManager,
                          val ratingHelper: RatingHelper,
                          val tracker: FirebaseAnalytics) : BeaconListContract.Presenter {

    private val TAG = "BeaconListPresenter"

    private var beaconResults: RealmResults<BeaconSaved> = realm.where(BeaconSaved::class.java).findAllSortedAsync(arrayOf("lastMinuteSeen", "distance"), arrayOf(Sort.DESCENDING, Sort.ASCENDING))

    private var bluetoothStateDisposable: Disposable? = null
    private var rangeDisposable: Disposable? = null
    private var beaconManager: BeaconManager? = null

    private var numberOfScansSinceLog = 0
    private val MAX_RETRIES = 3
    private var loggingRequests = CompositeDisposable()

    override fun setBeaconManager(bm: BeaconManager) {
        beaconManager = bm
    }

    override fun start() {
        // Setup an observable on the bluetooth changes
        bluetoothStateDisposable = bluetoothState.asFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { e ->
                    if (e is Events.BluetoothState) {
                        view.updateBluetoothState(e.getBluetoothState(), bluetoothState.isEnabled)

                        if (e.getBluetoothState() == BeaconListActivity.BluetoothState.STATE_OFF) {
                            stopScan()
                        }
                    }
                }

        view.setAdapter(beaconResults)

        beaconResults.addChangeListener { results ->
            view.showEmptyView(results.size == 0)
        }

        // Show the tutorial if needed
        if (!prefs.hasSeenTutorial()) {
            view.showTutorial()
            prefs.setHasSeenTutorial(true)
        }

        // Start scanning if the scan on open is activated or if we were previously scanning
        if (prefs.isScanOnOpen || prefs.wasScanning()) {
            startScan()
        }
    }

    override fun toggleScan() {
        if (!isScanning()) {
            tracker.logEvent("start_scanning_clicked", null)
            return startScan()
        }
        tracker.logEvent("stop_scanning_clicked", null)
        stopScan()
    }

    override fun startScan() {
        if (!view.hasCoarseLocationPermission()) {
            return view.askForCoarseLocationPermission()
        }

        if (!bluetoothState.isEnabled || beaconManager == null) {
            return view.showBluetoothNotEnabledError()
        }

        if (!(beaconManager?.isBound(view) ?: false)) {
            Log.d(TAG, "binding beaconManager")
            beaconManager?.bind(view)
        }

        view.showScanningState(true)
        rangeDisposable?.dispose() // clear the previous subscription if any
        rangeDisposable = rxBus.asFlowable() // Listen for range events
                .observeOn(AndroidSchedulers.mainThread()) // We use this so we use the realm on the good thread & we can make UI changes
                .subscribe { e ->
                    if (e is Events.RangeBeacon && e.beacons.isNotEmpty()) {
                        logToWebhookIfNeeded()
                        handleRating()
                        storeBeaconsAround(e.beacons)
                    }
                }
    }

    override fun onBeaconServiceConnect() {
        Log.d(TAG, "beaconManager is bound, ready to start scanning")
        beaconManager?.addRangeNotifier { beacons, region -> rxBus.send(Events.RangeBeacon(beacons, region)) }

        try {
            beaconManager?.startRangingBeaconsInRegion(Region("com.bridou_n.beaconscanner", null, null, null))
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    override fun onLocationPermissionGranted() {
        tracker.logEvent("permission_granted", null)
        startScan()
    }

    override fun onLocationPermissionDenied(requestCode: Int, permList: List<String>) {
        tracker.logEvent("permission_denied", null)

        // If the user refused the permission, we just disabled the scan on open
        prefs.isScanOnOpen = false
        if (view.hasSomePermissionPermanentlyDenied(permList)) {
            tracker.logEvent("permission_denied_permanently", null)
            view.showEnablePermissionSnackbar()
        }
    }

    fun handleRating() {
        if (ratingHelper.shouldShowRatingRationale()) {
            ratingHelper.setRatingOngoing()
            view.showRating(RatingHelper.STEP_ONE)
        }
    }

    override fun onRatingInteraction(step: Int, answer: Boolean) {
        Log.d(TAG, "step: $step -- answer : $answer")
        if (!answer) { // The user answered "no" to any question
            ratingHelper.setPopupSeen()
            return view.showRating(step, false)
        }

        when (step) {
            RatingHelper.STEP_ONE -> view.showRating(RatingHelper.STEP_TWO)
            RatingHelper.STEP_TWO -> {
                view.redirectToStorePage()
                view.showRating(step, false)
            }
        }
    }

    override fun storeBeaconsAround(beacons: Collection<Beacon>) {
        realm.executeTransactionAsync { tRealm ->
            for (b: Beacon in beacons) {
                val beacon = BeaconSaved()

                // Common field to every beacon
                beacon.hashcode = b.hashCode()
                beacon.lastSeen = Date().time
                beacon.lastMinuteSeen = Date().time / 1000 / 60
                beacon.beaconAddress = b.bluetoothAddress
                beacon.rssi = b.rssi
                beacon.manufacturer = b.manufacturer
                beacon.txPower = b.txPower
                beacon.distance = b.distance
                if (b.serviceUuid == 0xfeaa) { // This is an Eddystone beacon
                    // Do we have telemetry data?
                    if (b.extraDataFields.size > 0) {
                        beacon.hasTelemetryData = true
                        beacon.telemetryVersion = b.extraDataFields[0]
                        beacon.batteryMilliVolts = b.extraDataFields[1]
                        beacon.setTemperature(b.extraDataFields[2].toFloat())
                        beacon.pduCount = b.extraDataFields[3]
                        beacon.uptime = b.extraDataFields[4]
                    } else {
                        beacon.hasTelemetryData = false
                    }

                    when (b.beaconTypeCode) {
                        0x00 -> {
                            beacon.beaconType = BeaconSaved.TYPE_EDDYSTONE_UID
                            // This is a Eddystone-UID frame
                            beacon.namespaceId = b.id1.toString()
                            beacon.instanceId = b.id2.toString()
                        }
                        0x10 -> {
                            beacon.beaconType = BeaconSaved.TYPE_EDDYSTONE_URL
                            // This is a Eddystone-URL frame
                            beacon.url = UrlBeaconUrlCompressor.uncompress(b.id1.toByteArray())
                        }
                    }
                } else { // This is an iBeacon or ALTBeacon
                    beacon.beaconType = if (b.beaconTypeCode == 0xbeac) BeaconSaved.TYPE_ALTBEACON else BeaconSaved.TYPE_IBEACON // 0x4c000215 is iBeacon
                    beacon.uuid = b.id1.toString()
                    beacon.major = b.id2.toString()
                    beacon.minor = b.id3.toString()
                }

                val infos = Bundle()

                infos.putInt("manufacturer", beacon.manufacturer)
                infos.putInt("type", beacon.beaconType)
                infos.putDouble("distance", beacon.distance)

                tracker.logEvent("adding_or_updating_beacon", infos)
                tRealm.copyToRealmOrUpdate(beacon)
            }
        }
    }

    fun logToWebhookIfNeeded() {
        if (prefs.isLoggingEnabled && prefs.loggingEndpoint != null &&
                ++numberOfScansSinceLog == prefs.getLoggingFrequency()) {
            val beaconToLog = realm.where(BeaconSaved::class.java).greaterThan("lastSeen", prefs.lasLoggingCall).findAllAsync()

            beaconToLog.addChangeListener { results ->
                if (results.isLoaded) {
                    Log.d(TAG, "Result is loaded size : ${results.size} - lastLoggingCall : ${prefs.lasLoggingCall}")

                    // Execute the network request
                    prefs.lasLoggingCall = Date().time

                    // We clone the objects
                    val resultPlainObjects = results.map { it.clone() }
                    val req = LoggingRequest(prefs.loggingDeviceName ?: "", resultPlainObjects)

                    loggingRequests.add(loggingService.postLogs(prefs.loggingEndpoint ?: "", req)
                            .retryWhen({ errors: Flowable<Throwable> ->
                                errors.zipWith(Flowable.range(1, MAX_RETRIES + 1), BiFunction { error: Throwable, attempt: Int ->
                                    Log.d(TAG, "attempt : $attempt")
                                    if (attempt > MAX_RETRIES) {
                                        view.showLoggingError()
                                    }
                                    attempt
                                }).flatMap { attempt ->
                                    if (attempt > MAX_RETRIES) {
                                        Flowable.empty()
                                    } else {
                                        Flowable.timer(Math.pow(4.0, attempt.toDouble()).toLong(), TimeUnit.SECONDS)
                                    }
                                }
                            })
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe())

                    numberOfScansSinceLog = 0
                    beaconToLog.removeAllChangeListeners()
                }
            }
        }
    }

    override fun stopScan() {
        unbindBeaconManager()
        rangeDisposable?.dispose()
        view.showScanningState(false)
    }

    override fun onBluetoothToggle() {
        bluetoothState.toggle()
        tracker.logEvent("action_bluetooth", null)
    }

    override fun onSettingsClicked() {
        tracker.logEvent("action_settings", null)
        view.startSettingsActivity()
    }

    override fun onClearClicked() {
        tracker.logEvent("action_clear", null)
        view.showClearDialog()
    }

    override fun onClearAccepted() {
        tracker.logEvent("action_clear_accepted", null)
        realm.executeTransactionAsync { tRealm -> tRealm.where(BeaconSaved::class.java).findAll().deleteAllFromRealm() }
    }

    fun isScanning() = !(rangeDisposable?.isDisposed ?: true)

    override fun stop() {
        prefs.setScanningState(isScanning())
        unbindBeaconManager()
        beaconResults.removeAllChangeListeners()
        loggingRequests.clear()
        bluetoothStateDisposable?.dispose()
        rangeDisposable?.dispose()

    }

    fun unbindBeaconManager() {
        if (beaconManager?.isBound(view) ?: false) {
            Log.d(TAG, "Unbinding from beaconManager")
            beaconManager?.unbind(view)
        }
    }

    override fun clear() {
        realm.close()
    }
}