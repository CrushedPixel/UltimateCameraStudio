package net.crushedpixel.ultimatecamerastudio

import cloud.commandframework.CommandManager
import cloud.commandframework.annotations.AnnotationParser
import cloud.commandframework.annotations.Argument
import cloud.commandframework.annotations.CommandMethod
import cloud.commandframework.bukkit.BukkitCommandManager
import cloud.commandframework.execution.CommandExecutionCoordinator
import cloud.commandframework.meta.SimpleCommandMeta
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientChatPacket
import de.mcmdev.betterprotocol.BetterProtocol
import de.mcmdev.betterprotocol.api.PacketEvent
import de.mcmdev.betterprotocol.api.PacketHandler
import de.mcmdev.betterprotocol.api.PacketListener
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration
import kr.entree.spigradle.annotations.PluginMain
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

@PluginMain
@ExperimentalTime
public class UltimateCameraStudioPlugin : JavaPlugin(), Listener, PacketListener {

    private val cameraPathManager = CameraPathManager(this)

    override fun onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this)
        BetterProtocol.get<Player>().eventBus.listen(this)

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

    @PacketHandler
    public fun onChatPacket(event: PacketEvent<ClientChatPacket, Player>) {
        println("Received ${event.packet.message}")
    }

    @EventHandler
    public fun onPlayerQuit(event: PlayerQuitEvent) {
        cameraPathManager.stop(event.player)
    }

    @CommandMethod("cam add [location] [yaw] [pitch]")
    private fun addPointCommand(
        player: Player,
        @Argument("location") location: Location?,
        @Argument("yaw") yaw: Float?,
        @Argument("pitch") pitch: Float?,
    ) {
        val loc = location ?: player.location
        yaw?.let { loc.yaw = it }
        pitch?.let { loc.pitch = pitch }

        if (!cameraPathManager.addPoint(player, loc)) {
            player.sendMessage("${ChatColor.RED}All points must be in the same world!")
            return
        }

        player.sendMessage("${ChatColor.GREEN}Added point ${loc.toPrettyString()}")

        // TODO: dry this up with insertPointCommand
    }

    @CommandMethod("cam insert <index> [location] [yaw] [pitch]")
    private fun insertPointCommand(
        player: Player,
        @Argument("index") index: Int,
        @Argument("location") location: Location?,
        @Argument("yaw") yaw: Float?,
        @Argument("pitch") pitch: Float?,
    ) {
        val loc = location ?: player.location
        yaw?.let { loc.yaw = it }
        pitch?.let { loc.pitch = pitch }

        if (!cameraPathManager.addPoint(player, loc, index)) {
            player.sendMessage("${ChatColor.RED}All points must be in the same world!")
            return
        }

        player.sendMessage("${ChatColor.GREEN}Added point ${loc.toPrettyString()} at index $index")
    }

    @CommandMethod("cam remove [index]")
    private fun removePointCommand(player: Player, @Argument("index") index: Int?) {
        var actualIndex = index
        if (actualIndex != null) {
            // we don't want the user to have to use 0-based indices,
            // so we subtract 1 from them
            actualIndex--
        }

        if (cameraPathManager.removePoint(player, actualIndex)) return

        // the removal was unsuccessful - send an according error message
        if (actualIndex == null) {
            player.sendMessage("${ChatColor.RED}No points to remove!")
        } else {
            player.sendMessage("${ChatColor.RED}Point $index doesn't exist!")
        }
    }

    @CommandMethod("cam clear")
    private fun clearPointsCommand(player: Player) {
        cameraPathManager.clearPoints(player)
        player.sendMessage("${ChatColor.GREEN}All points have been cleared.")
    }

    @CommandMethod("cam goto <index>")
    private fun gotoPointCommand(player: Player, @Argument("index") index: Int) {
        val points = cameraPathManager.getPoints(player)

        // we don't want the user to have to use 0-based indices,
        // so we subtract 1 from them
        val actualIndex = index - 1

        if (actualIndex !in points.indices) {
            player.sendMessage("${ChatColor.RED}Point $index doesn't exist!")
            return
        }

        player.teleport(points[actualIndex])
    }

    @CommandMethod("cam list")
    private fun listPointsCommand(player: Player) {
        val points = cameraPathManager.getPoints(player)

        player.sendMessage("${ChatColor.YELLOW}You have ${points.size} camera points:")
        for ((index, point) in points.withIndex()) {
            player.sendMessage("${ChatColor.YELLOW}${index + 1} - ${point.toPrettyString()}")
        }
    }

    @CommandMethod("cam start <duration>")
    private fun startCamCommand(
        player: Player,
        @Argument("duration") duration: Int,
    ) {
        cameraPathManager.start(player, duration.toDuration(DurationUnit.SECONDS))
    }

    @CommandMethod("cam stop")
    private fun stopCamCommand(player: Player) {
        if (cameraPathManager.stop(player)) return
        player.sendMessage("${ChatColor.RED}You're not on a camera path!")
    }
}

public fun Location.toPrettyString(): String =
    "(%.2f, %.2f, %.2f, %.2f, %.2f)".format(x, y, z, yaw, pitch)
