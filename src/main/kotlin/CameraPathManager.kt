package net.crushedpixel.ultimatecamerastudio

import com.github.steveice10.mc.protocol.data.game.entity.RotationOrigin
import com.github.steveice10.mc.protocol.data.game.entity.metadata.EntityMetadata
import com.github.steveice10.mc.protocol.data.game.entity.metadata.MetadataType
import com.github.steveice10.mc.protocol.data.game.entity.type.EntityType
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.ServerEntityDestroyPacket
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.ServerEntityMetadataPacket
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.ServerEntityPositionRotationPacket
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.ServerEntitySetPassengersPacket
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.player.ServerPlayerFacingPacket
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.spawn.ServerSpawnEntityPacket
import com.github.steveice10.packetlib.packet.Packet
import de.mcmdev.betterprotocol.BetterProtocol
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import net.crushedpixel.ultimatecamerastudio.interpolation.CatmullRomSplineInterpolation
import net.crushedpixel.ultimatecamerastudio.interpolation.Interpolation
import net.crushedpixel.ultimatecamerastudio.path.Path
import net.crushedpixel.ultimatecamerastudio.path.PathSegment
import net.minecraft.server.v1_16_R3.Entity
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.joor.Reflect

/** Contains information about the state of a player's path playback. */
private class PathPlayback
private constructor(
    private val player: Player,
    private val points: List<Location>,
    private var aecId: Int,
    private val originalGameMode: GameMode
) {

    companion object {
        /** Sets up the path playback for a player, spawning all the necessary entities. */
        @ExperimentalTime
        fun start(
            plugin: Plugin,
            player: Player,
            keyframes: List<Location>,
            duration: Duration
        ): PathPlayback {
            // TODO: allow for specification of interpolation method
            // TODO: allow more complex duration strings (10m5s) instead of just a second value

            val numPoints = (duration.toDouble(DurationUnit.SECONDS) * 20).roundToInt()

            val yawCorrectedKeyframes =
                keyframes.mapIndexed { i, point ->
                    var newYaw = point.yaw % 360

                    if (i > 0) {
                        val prevYaw = keyframes[i - 1].yaw % 360
                        val diff = abs(newYaw - prevYaw)
                        if (diff > 180) {
                            if (prevYaw < 180) {
                                newYaw -= 360
                            } else {
                                newYaw += 360
                            }
                        }
                    }

                    point.clone().apply { yaw = newYaw }
                }

            val segments = mutableListOf<PathSegment>()
            for (i in 1..yawCorrectedKeyframes.lastIndex) {
                val p0 = yawCorrectedKeyframes[(i - 2).coerceAtLeast(0)]
                val p1 = yawCorrectedKeyframes[i - 1]
                val p2 = yawCorrectedKeyframes[i]
                val p3 = yawCorrectedKeyframes[i.coerceAtMost(yawCorrectedKeyframes.lastIndex)]

                val xInter: Interpolation = CatmullRomSplineInterpolation(p0.x, p1.x, p2.x, p3.x)
                val yInter: Interpolation = CatmullRomSplineInterpolation(p0.y, p1.y, p2.y, p3.y)
                val zInter: Interpolation = CatmullRomSplineInterpolation(p0.z, p1.z, p2.z, p3.z)

                val yawInter: Interpolation =
                    CatmullRomSplineInterpolation(
                        p0.yaw.toDouble(), p1.yaw.toDouble(), p2.yaw.toDouble(), p3.yaw.toDouble())

                val pitchInter: Interpolation =
                    CatmullRomSplineInterpolation(
                        p0.pitch.toDouble(),
                        p1.pitch.toDouble(),
                        p2.pitch.toDouble(),
                        p3.pitch.toDouble())

                segments += PathSegment(p0.world!!, xInter, yInter, zInter, yawInter, pitchInter)
            }

            val path = Path(segments)
            val points = path.getPoints(numPoints)

            // spawn a fake area effect cloud for the player to ride on
            val aecId = // TODO: get rid of NMS
                Reflect.onClass(Entity::class.java)
                    .get<AtomicInteger>("entityCount")
                    .incrementAndGet()
            val fakeEntityUUID = UUID.randomUUID()

            player.sendPacket(
                ServerSpawnEntityPacket(
                    aecId,
                    fakeEntityUUID,
                    EntityType.AREA_EFFECT_CLOUD,
                    points.first().x,
                    points.first().y,
                    points.first().z,
                    0.0f,
                    0.0f,
                    0.0,
                    0.0,
                    0.0))

            // set the AEC's radius to 0 to make it invisible
            player.sendPacket(
                ServerEntityMetadataPacket(
                    aecId,
                    arrayOf(
                        // set AEC radius to 0
                        EntityMetadata(7, MetadataType.FLOAT, 0.0f))))

            // mount the player onto the AEC
            player.sendPacket(ServerEntitySetPassengersPacket(aecId, intArrayOf(player.entityId)))

            val originalGameMode = player.gameMode
            player.gameMode = GameMode.CREATIVE

            // schedule the tick function to be run every tick
            val playback = PathPlayback(player, points, aecId, originalGameMode)
            val taskId =
                Bukkit.getScheduler()
                    .scheduleSyncRepeatingTask(plugin, { playback.tick(player) }, 0, 1)
            playback.taskId = taskId

            return playback
        }
    }

    private var taskId: Int = 0
    private var currentIndex = 0

    private fun tick(player: Player) {
        if (currentIndex == 0) {
            currentIndex++
            return
        }

        val point = points[currentIndex]
        val prevPoint = points[currentIndex - 1]

        // move the AEC to the new location
        player.sendPacket(
            ServerEntityPositionRotationPacket(
                aecId,
                point.x - prevPoint.x,
                point.y - prevPoint.y,
                point.z - prevPoint.z,
                0f,
                0f,
                false))

        // update player's head rotation
        player.setHeadRotation(point.yaw, point.pitch)

        if (++currentIndex > points.lastIndex) {
            stop()
        }
    }

    fun stop() {
        Bukkit.getScheduler().cancelTask(taskId)
        player.gameMode = originalGameMode

        // despawn the AEC
        player.sendPacket(ServerEntityDestroyPacket(intArrayOf(aecId)))
    }

    private fun Player.setHeadRotation(yaw: Float, pitch: Float) {
        // get a location 10000 blocks from the player in the desired direction
        val loc = eyeLocation
        loc.yaw = yaw
        loc.pitch = pitch
        loc.add(loc.direction.multiply(10000))

        sendPacket(ServerPlayerFacingPacket(RotationOrigin.EYES, loc.x, loc.y, loc.z))
    }
}

@ExperimentalTime
public class CameraPathManager(private val plugin: Plugin) {

    /** For each player, the list of points they have set. */
    private val points: MutableMap<UUID, MutableList<Location>> = mutableMapOf()

    private val activePlaybacks: MutableMap<UUID, PathPlayback> = mutableMapOf()

    public fun getPoints(player: Player): MutableList<Location> =
        points.computeIfAbsent(player.uniqueId) { mutableListOf() }

    public fun addPoint(player: Player, location: Location, index: Int? = null): Boolean {
        val keyframes = getPoints(player)

        if (!keyframes.all { it.world == location.world }) return false

        if (index == null) {
            keyframes.add(location)
        } else {
            keyframes.add(index, location)
        }

        return true
    }

    public fun removePoint(player: Player, index: Int? = null): Boolean {
        val keyframes = getPoints(player)

        if (index == null) {
            if (keyframes.isEmpty()) return false
            keyframes.removeLast()
        } else {
            if (index !in keyframes.indices) return false
            keyframes.removeAt(index)
        }

        return true
    }

    public fun clearPoints(player: Player) {
        getPoints(player).clear()
    }

    /** Plays the player's current camera path over the given [duration]. */
    public fun start(player: Player, duration: Duration) {
        // stop playing the current path, if any
        stop(player)

        val keyframes = points[player.uniqueId]
        if (keyframes == null || keyframes.size < 2) {
            player.sendMessage("${ChatColor.RED}At least 2 camera points are required")
            return
        }

        activePlaybacks[player.uniqueId] = PathPlayback.start(plugin, player, keyframes, duration)
    }

    /**
     * Stops the player's current path playback, if any. Returns whether the player was on a path.
     */
    public fun stop(player: Player): Boolean {
        val playback = activePlaybacks.remove(player.uniqueId) ?: return false
        playback.stop()

        return true
    }
}

public fun Player.sendPacket(packet: Packet) {
    BetterProtocol.get<Player>().send(this, packet)
}
