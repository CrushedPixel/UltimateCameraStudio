package net.crushedpixel.ultimatecamerastudio.path

import net.crushedpixel.ultimatecamerastudio.interpolation.Interpolation
import net.crushedpixel.ultimatecamerastudio.interpolation.lerp
import org.bukkit.Location
import org.bukkit.World

private data class SamplePoint(
    val location: Location,
    val posRel: Double,
    val lengthAtPoint: Double
)

public class PathSegment(
    private val world: World,
    private val xInterpolation: Interpolation,
    private val yInterpolation: Interpolation,
    private val zInterpolation: Interpolation,
    private val yawInterpolation: Interpolation,
    private val pitchInterpolation: Interpolation
) {

    private companion object {
        const val APPROXIMATION_SAMPLE_POINTS: Int = 10
    }

    /**
     * Sample points on the path segment, with its relative position on the path and the length of
     * the path up until that point.
     */
    private val samplePoints: List<SamplePoint> = run {
        var len = 0.0
        var prevPos: Location? = null

        (0..APPROXIMATION_SAMPLE_POINTS).map { i ->
            val pos = i.toDouble() / APPROXIMATION_SAMPLE_POINTS

            val loc = valueAtRelativePos(pos)

            prevPos?.let { len += it.distance(loc) }

            prevPos = loc

            SamplePoint(loc, pos, len)
        }
    }

    /** The path segment's approximate length. */
    public val length: Double = samplePoints.last().lengthAtPoint

    public fun valueAt(pos: Double): Location {
        val actualPos = getRelativePosAtLength(pos * length)
        return valueAtRelativePos(actualPos)
    }

    /**
     * Returns the approximated relative position on the path where the path has the given
     * [targetLength].
     */
    private fun getRelativePosAtLength(targetLength: Double): Double {
        val prevPointIndex = samplePoints.indexOfLast { it.lengthAtPoint <= targetLength }

        if (prevPointIndex == samplePoints.lastIndex) {
            return 1.0
        }

        val (_, prevPointPosRel, lenAtPrevPoint) = samplePoints[prevPointIndex]
        val (_, nextPointPosRel, lenAtNextPoint) = samplePoints[prevPointIndex + 1]

        val samplePointsDistance = lenAtNextPoint - lenAtPrevPoint
        val progressBetweenPoints = (targetLength - lenAtPrevPoint) / samplePointsDistance

        return lerp(prevPointPosRel, nextPointPosRel, progressBetweenPoints)
    }

    private fun valueAtRelativePos(pos: Double): Location =
        Location(
            world,
            xInterpolation.valueAt(pos),
            yInterpolation.valueAt(pos),
            zInterpolation.valueAt(pos),
            yawInterpolation.valueAt(pos).toFloat(),
            pitchInterpolation.valueAt(pos).toFloat())
}
