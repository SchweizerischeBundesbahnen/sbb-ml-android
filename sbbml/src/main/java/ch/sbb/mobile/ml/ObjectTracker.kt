/*
 * Copyright 2022 SBB AG. License: CC0-1.0
*/
package ch.sbb.mobile.ml

import android.graphics.RectF
import java.util.*
import timber.log.Timber


/**
 * Object tracker class that tracks objects across consecutive preview frames.
 * It provides a simplified Java interface to the analogous native object defined by
 * cpp/object_tracking/object_tracker_jni.cc
 *
 * Currently, the ObjectTracker is a singleton due to native code restrictions, and so must
 * be allocated by ObjectTracker.getInstance(). In addition, release() should be called
 * as soon as the ObjectTracker is no longer needed, and before a new one is created.
 *
 * nextFrame() should be called as new frames become available, preferably as often as possible.
 *
 * After allocation, new TrackedObjects may be instantiated via trackObject(). TrackedObjects
 * are associated with the ObjectTracker that created them, and are only valid while that
 * ObjectTracker still exists.
 */
class ObjectTracker protected  constructor(protected val frameWidth: Int, protected val frameHeight: Int, private val rowStride: Int, protected val alwaysTrack: Boolean) {

    private val downsampledFrame: ByteArray
    private val trackedObjects: MutableMap<String, TrackedObject>
    private var lastTimestamp: Long = 0
    private val timestampedDeltas: LinkedList<TimestampedDeltas>
    private var downsampledTimestamp: Long = 0

    /** ********************* NATIVE CODE ************************************  */

    /** This will contain an opaque pointer to the native ObjectTracker  */
    private val nativeObjectTracker: Long = 0

    private class TimestampedDeltas(internal val timestamp: Long, internal val deltas: ByteArray)

    /**
     * A simple class that records keypoint information, which includes
     * local location, score and type. This will be used in calculating
     * FrameChange.
     */
    class Keypoint {
        val x: Float
        val y: Float
        val score: Float
        val type: Int

        constructor(x: Float, y: Float) {
            this.x = x
            this.y = y
            this.score = 0f
            this.type = -1
        }

        constructor(x: Float, y: Float, score: Float, type: Int) {
            this.x = x
            this.y = y
            this.score = score
            this.type = type
        }

        internal fun delta(other: Keypoint): Keypoint {
            return Keypoint(this.x - other.x, this.y - other.y)
        }
    }

    /**
     * A simple class that could calculate Keypoint delta.
     * This class will be used in calculating frame translation delta
     * for optical flow.
     */
    class PointChange(x1: Float, y1: Float,
                      x2: Float, y2: Float,
                      score: Float, type: Int, public var wasFound: Boolean) {
        val keypointA: Keypoint
        val keypointB: Keypoint

        init {
            keypointA = Keypoint(x1, y1, score, type)
            keypointB = Keypoint(x2, y2)
        }


    }

    /** A class that records a timestamped frame translation delta for optical flow.  */
    class FrameChange(framePoints: FloatArray) {

        val pointDeltas: Vector<PointChange>

        val minScore: Float
        val maxScore: Float

        init {
            var minScore = 100.0f
            var maxScore = -100.0f

            pointDeltas = Vector(framePoints.size / KEYPOINT_STEP)

            var ix = 0
            while (ix < framePoints.size) {
                val x1 = framePoints[ix + 0] * DOWNSAMPLE_FACTOR
                val y1 = framePoints[ix + 1] * DOWNSAMPLE_FACTOR

                val wasFound = framePoints[ix + 2] > 0.0f

                val x2 = framePoints[ix + 3] * DOWNSAMPLE_FACTOR
                val y2 = framePoints[ix + 4] * DOWNSAMPLE_FACTOR
                val score = framePoints[ix + 5]
                val type = framePoints[ix + 6].toInt()

                minScore = Math.min(minScore, score)
                maxScore = Math.max(maxScore, score)

                pointDeltas.add(PointChange(x1, y1, x2, y2, score, type, wasFound))
                ix += KEYPOINT_STEP
            }

            this.minScore = minScore
            this.maxScore = maxScore
        }

        companion object {
            val KEYPOINT_STEP = 7
        }
    }

    init {
        this.timestampedDeltas = LinkedList()
        trackedObjects = HashMap()
        downsampledFrame = ByteArray((frameWidth + DOWNSAMPLE_FACTOR - 1) / DOWNSAMPLE_FACTOR * (frameWidth + DOWNSAMPLE_FACTOR - 1) / DOWNSAMPLE_FACTOR)
    }

    protected fun init() {
        // The native tracker never sees the full frame, so pre-scale dimensions
        // by the downsample factor.
        initNative(frameWidth / DOWNSAMPLE_FACTOR, frameHeight / DOWNSAMPLE_FACTOR, alwaysTrack)
    }

    @Synchronized
    fun nextFrame(
            frameData: ByteArray, uvData: ByteArray?,
            timestamp: Long, transformationMatrix: FloatArray?) {
        if (downsampledTimestamp != timestamp) {
            downsampleImageNative(
                    frameWidth, frameHeight, rowStride, frameData, DOWNSAMPLE_FACTOR, downsampledFrame)
            downsampledTimestamp = timestamp
        }

        // Do Lucas Kanade using the fullframe initializer.
        nextFrameNative(downsampledFrame, uvData, timestamp, transformationMatrix)

        timestampedDeltas.add(TimestampedDeltas(timestamp, getKeypointsPacked(DOWNSAMPLE_FACTOR.toFloat())))
        while (timestampedDeltas.size > MAX_FRAME_HISTORY_SIZE) {
            timestampedDeltas.removeFirst()
        }

        for (trackedObject in trackedObjects.values) {
            trackedObject.updateTrackedPosition()
        }

        lastTimestamp = timestamp
    }

    @Synchronized
    fun release() {
        releaseMemoryNative()
        synchronized(ObjectTracker::class.java) {
            instance = null
        }
    }

    private fun downscaleRect(fullFrameRect: RectF): RectF {
        return RectF(
                fullFrameRect.left / DOWNSAMPLE_FACTOR,
                fullFrameRect.top / DOWNSAMPLE_FACTOR,
                fullFrameRect.right / DOWNSAMPLE_FACTOR,
                fullFrameRect.bottom / DOWNSAMPLE_FACTOR)
    }

    private fun upscaleRect(downsampledFrameRect: RectF): RectF {
        return RectF(
                downsampledFrameRect.left * DOWNSAMPLE_FACTOR,
                downsampledFrameRect.top * DOWNSAMPLE_FACTOR,
                downsampledFrameRect.right * DOWNSAMPLE_FACTOR,
                downsampledFrameRect.bottom * DOWNSAMPLE_FACTOR)
    }

    /**
     * A TrackedObject represents a native TrackedObject, and provides access to the
     * relevant native tracking information available after every frame update. They may
     * be safely passed around and accessed externally, but will become invalid after
     * stopTracking() is called or the related creating ObjectTracker is deactivated.
     *
     * @author andrewharp@google.com (Andrew Harp)
     */
    inner class TrackedObject internal constructor(position: RectF, timestamp: Long, data: ByteArray) {
        private val id: String

        @get:Synchronized
        internal var lastExternalPositionTime: Long = 0
            private set

        private var lastTrackedPosition: RectF? = null
        private var visibleInLastFrame: Boolean = false

        private var isDead: Boolean = false

        val currentCorrelation: Float
            get() {
                checkValidObject()
                return this@ObjectTracker.getCurrentCorrelation(id)
            }

        val trackedPositionInPreviewFrame: RectF?
            @Synchronized get() {
                checkValidObject()

                return if (lastTrackedPosition == null) {
                    null
                } else upscaleRect(lastTrackedPosition!!)
            }

        init {
            isDead = false

            id = Integer.toString(this.hashCode())

            lastExternalPositionTime = timestamp

            synchronized(this@ObjectTracker) {
                registerInitialAppearance(position, data)
                setPreviousPosition(position, timestamp)
                trackedObjects.put(id, this)
            }
        }

        fun isValid(): Boolean {
            return !isDead
        }

        fun stopTracking() {
            checkValidObject()

            synchronized(this@ObjectTracker) {
                isDead = true
                forgetNative(id)
                trackedObjects.remove(id)
            }
        }

        internal fun registerInitialAppearance(position: RectF, data: ByteArray) {
            val externalPosition = downscaleRect(position)
            registerNewObjectWithAppearanceNative(id,
                    externalPosition.left, externalPosition.top,
                    externalPosition.right, externalPosition.bottom,
                    data)
        }

        @Synchronized
        internal fun setPreviousPosition(position: RectF, timestamp: Long) {
            checkValidObject()
            synchronized(this@ObjectTracker) {
                if (lastExternalPositionTime > timestamp) {
                    Timber.w("Tried to use older position time!")
                    return
                }
                val externalPosition = downscaleRect(position)
                lastExternalPositionTime = timestamp

                setPreviousPositionNative(id,
                        externalPosition.left, externalPosition.top,
                        externalPosition.right, externalPosition.bottom,
                        lastExternalPositionTime)

                updateTrackedPosition()
            }
        }

        @Synchronized
        fun updateTrackedPosition() {
            checkValidObject()

            val delta = FloatArray(4)
            getTrackedPositionNative(id, delta)
            lastTrackedPosition = RectF(delta[0], delta[1], delta[2], delta[3])

            visibleInLastFrame = isObjectVisible(id)
        }

        private fun checkValidObject() {
            if (isDead) {
                ;//error("TrackedObject already removed from tracking!")
            } else if (this@ObjectTracker !== instance) {
                error("TrackedObject created with another ObjectTracker!")
            }
        }
    }

    @Synchronized
    fun trackObject(
            position: RectF, timestamp: Long, frameData: ByteArray): TrackedObject {
        if (downsampledTimestamp != timestamp) {
            downsampleImageNative(
                    frameWidth, frameHeight, rowStride, frameData, DOWNSAMPLE_FACTOR, downsampledFrame)
            downsampledTimestamp = timestamp
        }
        return TrackedObject(position, timestamp, downsampledFrame)
    }

    @Synchronized
    fun trackObject(position: RectF, frameData: ByteArray): TrackedObject {
        return TrackedObject(position, lastTimestamp, frameData)
    }

    private external fun initNative(imageWidth: Int, imageHeight: Int, alwaysTrack: Boolean)

    protected external fun registerNewObjectWithAppearanceNative(
            objectId: String, x1: Float, y1: Float, x2: Float, y2: Float, data: ByteArray)

    protected external fun setPreviousPositionNative(
            objectId: String, x1: Float, y1: Float, x2: Float, y2: Float, timestamp: Long)

    protected external fun setCurrentPositionNative(
            objectId: String, x1: Float, y1: Float, x2: Float, y2: Float)

    protected external fun forgetNative(key: String)

    protected external fun getModelIdNative(key: String): String

    protected external fun haveObject(key: String): Boolean
    protected external fun isObjectVisible(key: String): Boolean
    protected external fun getCurrentCorrelation(key: String): Float

    protected external fun getMatchScore(key: String): Float

    protected external fun getTrackedPositionNative(key: String, points: FloatArray)

    protected external fun nextFrameNative(
            frameData: ByteArray, uvData: ByteArray?, timestamp: Long, frameAlignMatrix: FloatArray?)

    protected external fun releaseMemoryNative()

    protected external fun getCurrentPositionNative(timestamp: Long,
                                                    positionX1: Float, positionY1: Float,
                                                    positionX2: Float, positionY2: Float,
                                                    delta: FloatArray)

    protected external fun getKeypointsPacked(scaleFactor: Float): ByteArray

    protected external fun getKeypointsNative(onlyReturnCorrespondingKeypoints: Boolean): FloatArray

    protected external fun drawNative(viewWidth: Int, viewHeight: Int, frameToCanvas: FloatArray)

    protected external fun downsampleImageNative(
            width: Int, height: Int, rowStride: Int, input: ByteArray, factor: Int, output: ByteArray)

    companion object {

        private var libraryFound = false

        init {
            try {
                System.loadLibrary("sbbml")
                libraryFound = true
            } catch (e: UnsatisfiedLinkError) {
                Timber.e("sbbml.so not found, tracking unavailable")
            }
        }

        /**
         * How many frames of optical flow deltas to record.
         * TODO(andrewharp): Push this down to the native level so it can be polled
         * efficiently into a an array for upload, instead of keeping a duplicate
         * copy in Java.
         */
        private val MAX_FRAME_HISTORY_SIZE = 200

        private val DOWNSAMPLE_FACTOR = 2

        protected var instance: ObjectTracker? = null

        @Synchronized
        fun getInstance(
                frameWidth: Int, frameHeight: Int, rowStride: Int, alwaysTrack: Boolean): ObjectTracker? {
            if (!libraryFound) {
                // Native object tracking support not found. See tensorflow/examples/android/README.md for details.
                return null
            }

            if (instance == null) {
                instance = ObjectTracker(frameWidth, frameHeight, rowStride, alwaysTrack)
                instance!!.init()
            } else {
                throw RuntimeException(
                        "Tried to create a new objectracker before releasing the old one!")
            }
            return instance
        }

        @Synchronized
        fun clearInstance() {
            if (instance != null) {
                instance!!.release()
            }
        }

        private fun floatToChar(value: Float): Int {
            return Math.max(0, Math.min((value * 255.999f).toInt(), 255))
        }
    }
}
