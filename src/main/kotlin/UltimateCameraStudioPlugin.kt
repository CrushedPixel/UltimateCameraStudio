package net.crushedpixel.ultimatecamerastudio

import cloud.commandframework.CommandManager
import cloud.commandframework.annotations.AnnotationParser
import cloud.commandframework.annotations.Argument
import cloud.commandframework.annotations.CommandMethod
import cloud.commandframework.bukkit.BukkitCommandManager
import cloud.commandframework.execution.CommandExecutionCoordinator
import cloud.commandframework.meta.SimpleCommandMeta
import com.mojang.authlib.GameProfile
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kr.entree.spigradle.annotations.PluginMain
import net.crushedpixel.ultimatecamerastudio.interpolation.CatmullRomSplineInterpolation
import net.crushedpixel.ultimatecamerastudio.interpolation.Interpolation
import net.crushedpixel.ultimatecamerastudio.path.Path
import net.crushedpixel.ultimatecamerastudio.path.PathSegment
import net.minecraft.server.v1_16_R3.Entity
import net.minecraft.server.v1_16_R3.EnumGamemode
import net.minecraft.server.v1_16_R3.IChatBaseComponent
import net.minecraft.server.v1_16_R3.Packet
import net.minecraft.server.v1_16_R3.PacketPlayOutCamera
import net.minecraft.server.v1_16_R3.PacketPlayOutEntity
import net.minecraft.server.v1_16_R3.PacketPlayOutEntityDestroy
import net.minecraft.server.v1_16_R3.PacketPlayOutEntityHeadRotation
import net.minecraft.server.v1_16_R3.PacketPlayOutEntityTeleport
import net.minecraft.server.v1_16_R3.PacketPlayOutNamedEntitySpawn
import net.minecraft.server.v1_16_R3.PacketPlayOutPlayerInfo
import net.minecraft.server.v1_16_R3.PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER
import net.minecraft.server.v1_16_R3.PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.craftbukkit.v1_16_R3.entity.CraftPlayer
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

    @CommandMethod("cam start <duration>")
    private fun startCamCommand(player: Player, @Argument("duration") duration: Int) {
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

        val path = Path(segments)
        val pointsOnPath = path.getPoints(numPoints)

        // create player info packet for the fake player
        val playerInfoPacket = PacketPlayOutPlayerInfo(ADD_PLAYER)

        val entityId =
            Reflect.onClass(Entity::class.java).get<AtomicInteger>("entityCount").incrementAndGet()

        val entityUUID = UUID.randomUUID()

        val fakePlayerInfoData =
            Reflect.onClass(PacketPlayOutPlayerInfo.PlayerInfoData::class.java)
                .create(
                    playerInfoPacket,
                    GameProfile(entityUUID, entityUUID.toString().substring(0, 16)),
                    entityId,
                    EnumGamemode.CREATIVE,
                    null as IChatBaseComponent?)
                .get<PacketPlayOutPlayerInfo.PlayerInfoData>()

        Reflect.on(playerInfoPacket)
            .get<MutableList<PacketPlayOutPlayerInfo.PlayerInfoData>>("b")
            .add(fakePlayerInfoData)

        player.sendPacket(playerInfoPacket)

        // create player spawn packet for the fake player
        val spawnPlayerPacket = PacketPlayOutNamedEntitySpawn()
        Reflect.on(spawnPlayerPacket)
            .set("a", entityId)
            .set("b", entityUUID)
            .set("c", pointsOnPath.first().x)
            .set("d", pointsOnPath.first().y)
            .set("e", pointsOnPath.first().z)
            .set("f", angleToByte(pointsOnPath.first().yaw))
            .set("g", angleToByte(pointsOnPath.first().pitch))

        player.sendPacket(spawnPlayerPacket)

        // make the player spectate the fake player
        val originalGameMode = player.gameMode
        player.gameMode = GameMode.SPECTATOR

        val spectatePacket = PacketPlayOutCamera()
        Reflect.on(spectatePacket).set("a", entityId)
        player.sendPacket(spectatePacket)

        for ((i, point) in pointsOnPath.withIndex()) {
            val posPacket =
                if (i == 0) {
                    val entityTeleportPacket = PacketPlayOutEntityTeleport()
                    Reflect.on(entityTeleportPacket)
                        .set("a", entityId)
                        .set("b", point.x)
                        .set("c", point.y)
                        .set("d", point.z)
                        .set("e", angleToByte(point.yaw))
                        .set("f", angleToByte(point.pitch))

                    entityTeleportPacket
                } else {
                    val prevPoint = pointsOnPath[i - 1]

                    val relativePacket = PacketPlayOutEntity.PacketPlayOutRelEntityMoveLook()
                    Reflect.on(relativePacket)
                        .set("a", entityId)
                        .set("b", positionDelta(prevPoint.x, point.x))
                        .set("c", positionDelta(prevPoint.y, point.y))
                        .set("d", positionDelta(prevPoint.z, point.z))
                        .set("e", angleToByte(point.yaw))
                        .set("f", angleToByte(point.pitch))
                        .set("g", false)

                    relativePacket
                }

            val entityHeadRotationPacket = PacketPlayOutEntityHeadRotation()
            Reflect.on(entityHeadRotationPacket).set("a", entityId).set("b", angleToByte(point.yaw))

            Bukkit.getScheduler()
                .scheduleSyncDelayedTask(
                    this,
                    {
                        player.sendPacket(posPacket)
                        player.sendPacket(entityHeadRotationPacket)
                        player.sendPacket(spectatePacket)
                    },
                    i.toLong())
        }

        Bukkit.getScheduler()
            .scheduleSyncDelayedTask(
                this,
                {
                    // make them stop spectating the fake player
                    player.sendPacket(PacketPlayOutCamera((player as CraftPlayer).handle))

                    // despawn the fake player
                    val entityRemovePacket = PacketPlayOutEntityDestroy(entityId)
                    player.sendPacket(entityRemovePacket)

                    // remove the fake player from the tab list
                    val entityRemoveFromInfoPacket = PacketPlayOutPlayerInfo(REMOVE_PLAYER)
                    val fakePlayerInfoData =
                        Reflect.onClass(PacketPlayOutPlayerInfo.PlayerInfoData::class.java)
                            .create(
                                entityRemoveFromInfoPacket,
                                GameProfile(entityUUID, entityUUID.toString().substring(0, 16)),
                                entityId,
                                EnumGamemode.CREATIVE,
                                null as IChatBaseComponent?)
                            .get<PacketPlayOutPlayerInfo.PlayerInfoData>()

                    Reflect.on(entityRemoveFromInfoPacket)
                        .get<MutableList<PacketPlayOutPlayerInfo.PlayerInfoData>>("b")
                        .add(fakePlayerInfoData)
                    player.sendPacket(entityRemoveFromInfoPacket)

                    player.gameMode = originalGameMode
                },
                pointsOnPath.size.toLong())
    }
}

private fun Player.sendPacket(packet: Packet<*>) {
    (this as CraftPlayer).handle.playerConnection.sendPacket(packet)
}

private fun angleToByte(angle: Float): Byte = (angle * 256 / 360).toInt().toByte()

private fun positionDelta(prev: Double, cur: Double): Short =
    ((cur * 32 - prev * 32) * 128).toInt().toShort()
