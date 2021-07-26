package net.crushedpixel.ultimatecamerastudio.path

import net.crushedpixel.ultimatecamerastudio.interpolation.Interpolation
import org.bukkit.Location
import org.bukkit.World

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

    public fun valueAt(pos: Double): Location {
        return Location(
            world,
            xInterpolation.valueAt(pos),
            yInterpolation.valueAt(pos),
            zInterpolation.valueAt(pos),
            yawInterpolation.valueAt(pos).toFloat(),
            pitchInterpolation.valueAt(pos).toFloat())
    }

    /** The path segment's approximate length. */
    public val length: Double by lazy {
        var len: Double = 0.0
        var prevPos: Location? = null

        for (i in 0..APPROXIMATION_SAMPLE_POINTS) {
            val posRel = i.toDouble() / APPROXIMATION_SAMPLE_POINTS

            val pos = valueAt(posRel)

            if (prevPos != null) {
                len += prevPos.distance(pos)
            }

            prevPos = pos
        }

        len
    }
}
