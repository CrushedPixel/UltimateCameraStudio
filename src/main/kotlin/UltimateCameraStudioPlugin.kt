package net.crushedpixel.ultimatecamerastudio

import cloud.commandframework.CommandManager
import cloud.commandframework.annotations.AnnotationParser
import cloud.commandframework.annotations.Argument
import cloud.commandframework.annotations.CommandMethod
import cloud.commandframework.bukkit.BukkitCommandManager
import cloud.commandframework.execution.CommandExecutionCoordinator
import cloud.commandframework.meta.SimpleCommandMeta
import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.PacketContainer
import com.comphenix.protocol.utility.MinecraftReflection
import com.comphenix.protocol.wrappers.WrappedDataWatcher
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kr.entree.spigradle.annotations.PluginMain
import net.crushedpixel.ultimatecamerastudio.interpolation.CatmullRomSplineInterpolation
import net.crushedpixel.ultimatecamerastudio.interpolation.Interpolation
import net.crushedpixel.ultimatecamerastudio.path.Path
import net.crushedpixel.ultimatecamerastudio.path.PathSegment
import net.minecraft.server.v1_16_R3.ArgumentAnchor
import net.minecraft.server.v1_16_R3.Entity
import net.minecraft.server.v1_16_R3.Packet
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.craftbukkit.v1_16_R3.entity.CraftPlayer
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.joor.Reflect

@PluginMain
public class UltimateCameraStudioPlugin : JavaPlugin(), Listener {

    /** For each player, the list of points they have set. */
    private val pointsByPlayer: MutableMap<UUID, MutableList<Location>> = mutableMapOf()

    override fun onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this)

        // set up cloud commands
        val manager: CommandManager<CommandSender> =
            BukkitCommandManager(
                this, CommandExecutionCoordinator.simpleCoordinator(), { it }, { it })

        val annotationParser: AnnotationParser<CommandSender> =
            AnnotationParser(manager, CommandSender::class.java) { SimpleCommandMeta.empty() }

        annotationParser.parse(this)
    }

    override fun onDisable() {
        HandlerList.unregisterAll(this as Listener)
    }

    @EventHandler
    public fun onPlayerQuit(event: PlayerQuitEvent) {
        pointsByPlayer -= event.player.uniqueId
    }

    @CommandMethod("cam p [location]")
    private fun addPointCommand(player: Player, @Argument("location") location: Location?) {
        val points = pointsByPlayer.computeIfAbsent(player.uniqueId) { mutableListOf() }

        val loc = location ?: player.location
        points += loc

        player.sendMessage("${ChatColor.GREEN}Added point at $loc")
    }

    @CommandMethod("cam start <duration> <reparameterize>")
    private fun startCamCommand(
        player: Player,
        @Argument("duration") duration: Int,
        @Argument("reparameterize") reparameterize: Boolean
    ) {
        // TODO: allow for specification of interpolation method
        // TODO: allow more complex duration strings (10m5s) instead of just a second value

        val points = pointsByPlayer[player.uniqueId]
        if (points == null || points.size < 2) {
            player.sendMessage("${ChatColor.RED}At least 2 camera points are required")
            return
        }

        val numPoints = duration * 20

        val segments = mutableListOf<PathSegment>()
        for (i in 1..points.lastIndex) {
            val p0 = points[(i - 2).coerceAtLeast(0)]
            val p1 = points[i - 1]
            val p2 = points[i]
            val p3 = points[i.coerceAtMost(points.lastIndex)]

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

        // put the player in spectator mode
        val originalGameMode = player.gameMode
        player.gameMode = GameMode.CREATIVE

        val path = Path(segments)
        val pointsOnPath = path.getPoints(numPoints, reparameterize)

        // spawn a fake area effect cloud for the player to ride on
        val fakeEntityId =
            Reflect.onClass(Entity::class.java).get<AtomicInteger>("entityCount").incrementAndGet()
        val entityUUID = UUID.randomUUID()

        val aecPacket = PacketContainer(PacketType.Play.Server.SPAWN_ENTITY)
        val aecMetadataPacket = PacketContainer(PacketType.Play.Server.ENTITY_METADATA)

        aecPacket.integers.write(0, fakeEntityId)
        aecPacket.uuiDs.write(0, entityUUID)

        aecPacket.entityTypeModifier.write(
            0, EntityType.AREA_EFFECT_CLOUD) // Sets the Entity Type to AEC

        aecPacket.doubles.write(0, pointsOnPath.first().x) // Writes X
        aecPacket.doubles.write(1, pointsOnPath.first().y) // Writes Y
        aecPacket.doubles.write(2, pointsOnPath.first().z) // Writes Z
        aecPacket.integers.write(1, 0) // Writes Pitch
        aecPacket.integers.write(2, 0) // Writes Yaw
        aecPacket.integers.write(3, 0) // Writes Object Data (not needed)
        aecPacket.integers.write(4, 0) // Writes velocity X
        aecPacket.integers.write(5, 0) // Writes velocity Y
        aecPacket.integers.write(6, 0) // Writes velocity Z

        val watcher = WrappedDataWatcher() // Creates new Data Watcher
        val serializer = WrappedDataWatcher.Registry.get(java.lang.Float::class.java)

        watcher.entity = player
        watcher.setObject(7, serializer, 0f) // Sets the radius (index 7) to 0
        // https://wiki.vg/Entity_metadata#Area_Effect_Cloud

        aecMetadataPacket.integers.write(0, fakeEntityId)
        aecMetadataPacket.watchableCollectionModifier.write(
            0, watcher.watchableObjects) // set AEC radius to 0

        player.sendPacket(aecPacket)
        player.sendPacket(aecMetadataPacket)

        val mountPacket = PacketContainer(PacketType.Play.Server.MOUNT)
        val players = IntArray(1)
        players[0] = player.entityId
        mountPacket.integers.write(0, fakeEntityId) // Writes EID of the corresponding AEC
        mountPacket.integerArrays.write(
            0, players) // Writes Array of the player, who will be mounted

        player.sendPacket(mountPacket)

        for ((i, point) in pointsOnPath.withIndex()) {
            if (i == 0) continue

            val prevPoint = pointsOnPath[i - 1]

            // move the AEC to the new location
            val entityMovePacket = PacketContainer(PacketType.Play.Server.REL_ENTITY_MOVE)
            entityMovePacket.integers.write(0, fakeEntityId) // Writes the corresponding EID
            entityMovePacket.shorts.write(
                0, positionDelta(prevPoint.x, point.x)) // Writes the new X
            entityMovePacket.shorts.write(
                1, positionDelta(prevPoint.y, point.y)) // Writes the new Y
            entityMovePacket.shorts.write(
                2, positionDelta(prevPoint.z, point.z)) // Writes the new Z
            entityMovePacket.booleans.write(0, false) // Writes onGround to false

            player.sendPacket(entityMovePacket)

            Bukkit.getScheduler()
                .scheduleSyncDelayedTask(
                    this,
                    {
                        player.sendPacket(mountPacket)
                        player.sendPacket(entityMovePacket)
                        player.setHeadRotation(point.yaw, point.pitch)
                    },
                    i.toLong())
        }

        Bukkit.getScheduler()
            .scheduleSyncDelayedTask(
                this,
                {
                    // despawn the fake AEC
                    val entityRemovePacket = PacketContainer(PacketType.Play.Server.ENTITY_DESTROY)
                    entityRemovePacket.integerArrays.write(0, intArrayOf(fakeEntityId))
                    player.sendPacket(entityRemovePacket)

                    player.gameMode = originalGameMode
                },
                pointsOnPath.size.toLong())
    }
}

private fun Player.sendPacket(packet: Packet<*>) {
    (this as CraftPlayer).handle.playerConnection.sendPacket(packet)
}

private fun Player.sendPacket(packet: PacketContainer) {
    ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet)
}

private fun angleToByte(angle: Float): Byte = (angle * 256 / 360).toInt().toByte()

private fun positionDelta(prev: Double, cur: Double): Short =
    ((cur * 32 - prev * 32) * 128).toInt().toShort()

private fun Player.setHeadRotation(yaw: Float, pitch: Float) {
    // get a location 10000 blocks from the player in the desired direction
    val loc = eyeLocation
    loc.yaw = yaw
    loc.pitch = pitch
    loc.add(loc.direction.multiply(10000))

    // send a packet to the player, telling them to look at that location
    val packet = PacketContainer(PacketType.Play.Server.LOOK_AT)
    packet.doubles.write(0, loc.x)
    packet.doubles.write(1, loc.y)
    packet.doubles.write(2, loc.z)

    sendPacket(packet)
}
