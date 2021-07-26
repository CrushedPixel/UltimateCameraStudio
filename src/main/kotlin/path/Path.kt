package net.crushedpixel.ultimatecamerastudio.path

import org.bukkit.Location

public class Path(private val segments: List<PathSegment>) {

    private val length: Double by lazy { segments.sumOf(PathSegment::length) }

    /** For each path segment, the relative position on the path where it starts. */
    private val segmentsWithStart: List<Pair<PathSegment, Double>> = run {
        val segmentsWithStart = mutableListOf<Pair<PathSegment, Double>>()

        var currentLengthSum = 0.0

        for (segment in segments) {
            segmentsWithStart += segment to currentLengthSum
            currentLengthSum += segment.length / length
        }

        segmentsWithStart
    }

    /** Returns the desired [amount] of (not yet)equidistant points on the path. */
    public fun getPoints(amount: Int): List<Location> {
        val points = mutableListOf<Location>()

        for (i in 0 until amount) {
            val pos = i.toDouble() / (amount - 1)

            val (segmentIndex, segmentPos) = getSegmentIndexAndPos(pos)

            points += segments[segmentIndex].valueAt(segmentPos)
        }

        return points
    }

    /**
     * Returns the index of the segment at the given relative [pos] on the path, as well as the
     * relative position on that segment.
     */
    private fun getSegmentIndexAndPos(pos: Double): Pair<Int, Double> {
        require(pos in 0.0..1.0)

        val segmentIndex: Int
        val segmentStart: Double
        val nextSegmentStart: Double

        val nextSegmentIndex = segmentsWithStart.indexOfFirst { it.second > pos }
        if (nextSegmentIndex == -1) {
            segmentIndex = segmentsWithStart.lastIndex
            segmentStart = segmentsWithStart.last().second
            nextSegmentStart = 1.0
        } else {
            segmentIndex = nextSegmentIndex - 1
            segmentStart = segmentsWithStart[nextSegmentIndex - 1].second
            nextSegmentStart = segmentsWithStart[nextSegmentIndex].second
        }

        val segmentLengthRelative = nextSegmentStart - segmentStart
        val posOnSegment = (pos - segmentStart) / segmentLengthRelative

        return segmentIndex to posOnSegment
    }
}
