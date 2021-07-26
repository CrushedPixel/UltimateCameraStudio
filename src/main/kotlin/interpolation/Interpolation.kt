package net.crushedpixel.ultimatecamerastudio.interpolation

public interface Interpolation {
    public fun valueAt(pos: Double): Double
}

public class LinearInterpolation(private val start: Double, private val end: Double) :
    Interpolation {

    override fun valueAt(pos: Double): Double = lerp(start, end, pos)
}

/** Performs linear interpolation between points [a] and [b]. */
public fun lerp(a: Double, b: Double, pos: Double): Double {
    require(pos in 0.0..1.0)
    return a + (b - a) * pos
}

public class CatmullRomSplineInterpolation(
    private val p0: Double,
    private val p1: Double,
    private val p2: Double,
    private val p3: Double
) : Interpolation {

    override fun valueAt(pos: Double): Double {
        val posSquared = pos * pos

        val a0 = -0.5 * p0 + 1.5 * p1 - 1.5 * p2 + 0.5 * p3
        val a1 = p0 - 2.5 * p1 + 2 * p2 - 0.5 * p3
        val a2 = -0.5 * p0 + 0.5 * p2
        val a3 = p1

        return a0 * pos * posSquared + a1 * posSquared + a2 * pos + a3
    }
}
