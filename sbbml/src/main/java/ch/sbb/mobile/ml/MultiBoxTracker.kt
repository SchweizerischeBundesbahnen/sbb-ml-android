/*
 * Copyright 2022 SBB AG. License: CC0-1.0
*/
package ch.sbb.mobile.ml

import android.graphics.RectF
import android.util.Pair
import timber.log.Timber
import java.util.*
import java.util.concurrent.Semaphore

class MultiBoxTracker() {

    var objectTracker: ObjectTracker? = null
    internal val screenRects: MutableList<Pair<Float, RectF>> = LinkedList()
    private val trackedObjects = LinkedList<TrackedRecognition>()
    private val trackedObjectsSemaphore = Semaphore(1)
    private val initializeSemaphore = Semaphore(1)
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0
    private var initialized = false

    companion object {
        // Maximum percentage of a box that can be overlapped by another box at detection time. Otherwise
        // the lower scored box (new or old) will be removed.
        private val MAX_OVERLAP = 0.1f

        private val MIN_SIZE = 16.0f

        // Allow replacement of the tracked box with new results if
        // correlation has dropped below this level.
        private val MARGINAL_CORRELATION = 0.75f

        // Consider object to be lost if correlation falls below this threshold.
        private val MIN_CORRELATION = 0.20f
    }

    class TrackedRecognition internal constructor(trackedObjectParam: ObjectTracker.TrackedObject? = null, locationParam: RectF? = null, detectionConfidenceParam: Float = 0.toFloat(), titleParam: String? = null){
        internal var trackedObject: ObjectTracker.TrackedObject? = trackedObjectParam
        internal var location: RectF? = locationParam
        internal var detectionConfidence: Float = detectionConfidenceParam
        internal var title: String? = titleParam

        // for some reason java code cannot access this class if these getters are not explicitely defined here
        fun getTrackedObject() :  ObjectTracker.TrackedObject? {
            return trackedObject
        }

        fun getTitle(): String? {
            return title
        }

        fun getLocation(): RectF? {
            return location
        }

        fun getDetectionConfidence(): Float {
            return detectionConfidence
        }
    }

    fun trackResults(results: List<Recognition>, frame: ByteArray, timestamp: Long) {
        Timber.i("Processing ${results.size} results from timestamp $timestamp")
        processResults(timestamp, results, frame)
    }

    fun publish(): List<TrackedRecognition> {
        return trackedObjects
    }

    fun onFrame(w: Int, h: Int, rowStride: Int, frame: ByteArray, timestamp: Long) {
        initializeSemaphore.acquireUninterruptibly()
        if (objectTracker == null && !initialized) {
            ObjectTracker.clearInstance()
            Timber.i("Initializing ObjectTracker: $w, $h")
            objectTracker = ObjectTracker.getInstance(w, h, rowStride, true)
            frameWidth = w
            frameHeight = h
            initialized = true
        }
        initializeSemaphore.release()

        if (objectTracker == null) {
            return
        }

        objectTracker!!.nextFrame(frame, null, timestamp, null)

        trackedObjectsSemaphore.acquireUninterruptibly()
        // Clean up any objects not worth tracking any more.
        val copyList = LinkedList(trackedObjects)
        for (recognition in copyList) {
            val trackedObject = recognition.trackedObject
            trackedObject?.let {
                val correlation = trackedObject!!.currentCorrelation
                if (correlation < MIN_CORRELATION) {
                    Timber.i("Removing tracked object ${recognition.title} because NCC is $correlation")
                    trackedObject.stopTracking()
                    trackedObjects.remove(recognition)
                }
            }
        }
        trackedObjectsSemaphore.release()
    }

    private fun processResults(timestamp: Long, results: List<Recognition>, originalFrame: ByteArray) {
        val rectsToTrack = LinkedList<Pair<Float, Recognition>>()
        screenRects.clear()

        for (result in results) {
            if (result.location == null) {
                continue
            }
            val detectionFrameRect = RectF(result.location)
            screenRects.add(Pair(result.confidence!!, detectionFrameRect))

            if (detectionFrameRect.width() < MIN_SIZE || detectionFrameRect.height() < MIN_SIZE) {
                Timber.i("Degenerate rectangle! $detectionFrameRect")
                continue
            }

            rectsToTrack.add(Pair<Float, Recognition>(result.confidence, result))
        }

        if (rectsToTrack.isEmpty()) {
            Timber.i("Nothing to track, aborting.")
            return
        }

        if (objectTracker == null) {
            trackedObjectsSemaphore.acquireUninterruptibly()
            trackedObjects.clear()
            for (potential in rectsToTrack) {
                val trackedRecognition = TrackedRecognition()
                trackedRecognition.detectionConfidence = potential.first
                trackedRecognition.location = RectF(potential.second.location)
                trackedRecognition.trackedObject = null
                trackedRecognition.title = potential.second.title
                trackedObjects.add(trackedRecognition)
            }
            trackedObjectsSemaphore.release()
            return
        }

        Timber.i("$rectsToTrack.size rects to track")
        for (potential in rectsToTrack) {
            handleDetection(originalFrame, timestamp, potential)
        }
    }

    private fun handleDetection(frameCopy: ByteArray, timestamp: Long, potential: Pair<Float, Recognition>) {
        val potentialObject = objectTracker!!.trackObject(potential.second.location!!, timestamp, frameCopy)

        if (potentialObject.currentCorrelation < MARGINAL_CORRELATION) {
            Timber.i("Correlation too low to begin tracking $potentialObject.currentCorrelation")
            potentialObject.stopTracking()
            return
        }

        trackedObjectsSemaphore.acquireUninterruptibly()
        val removeList = LinkedList<TrackedRecognition>()
        var maxIntersect = 0.0f

        // Look for intersections that will be overridden by this object or an intersection that would
        // prevent this one from being placed.
        for (trackedRecognition in trackedObjects) {
            if(trackedRecognition.trackedObject != null && potentialObject.trackedPositionInPreviewFrame != null) {
                val a = trackedRecognition.trackedObject!!.trackedPositionInPreviewFrame
                val b = potentialObject.trackedPositionInPreviewFrame
                val intersection = RectF()
                val intersects = intersection.setIntersect(a!!, b!!)

                val intersectArea = intersection.width() * intersection.height()
                val totalArea = a!!.width() * a.height() + b!!.width() * b.height() - intersectArea
                val intersectOverUnion = intersectArea / totalArea

                // If there is an intersection with this currently tracked box above the maximum overlap
                // percentage allowed, either the new recognition needs to be dismissed or the old
                // recognition needs to be removed and possibly replaced with the new one.
                if (intersects && (intersectOverUnion > MAX_OVERLAP)) {
                    if (potential.first < trackedRecognition.detectionConfidence && trackedRecognition.trackedObject!!.currentCorrelation > MARGINAL_CORRELATION) {
                        // If track for the existing object is still going strong and the detection score was
                        // good, reject this new object.
                        potentialObject.stopTracking()
                        trackedObjectsSemaphore.release()
                        return
                    } else {
                        removeList.add(trackedRecognition)

                        // Let the previously tracked object with max intersection amount donate its color to
                        // the new object.
                        if (intersectOverUnion > maxIntersect) {
                            maxIntersect = intersectOverUnion
                        }
                    }
                }
            }
        }

        // Remove everything that got intersected.
        for (trackedRecognition in removeList) {
            if(trackedRecognition.trackedObject != null) {
                Timber.i("Removing tracked object ${trackedRecognition.title} with detection confidence ${trackedRecognition.detectionConfidence}, correlation ${trackedRecognition.trackedObject!!.currentCorrelation}")
                trackedRecognition.trackedObject!!.stopTracking()
                trackedObjects.remove(trackedRecognition)
            }
        }

        // Finally safe to say we can track this object.
        val trackedRecognition = TrackedRecognition()
        trackedRecognition.detectionConfidence = potential.first
        trackedRecognition.trackedObject = potentialObject
        trackedRecognition.title = potential.second.title
        Timber.i("Tracking object ${trackedRecognition.title}")

        trackedObjects.add(trackedRecognition)
        trackedObjectsSemaphore.release()
    }
}