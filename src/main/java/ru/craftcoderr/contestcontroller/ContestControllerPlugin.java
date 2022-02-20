package ru.craftcoderr.contestcontroller;

import cloud.commandframework.services.ServicePipeline;
import com.google.common.eventbus.Subscribe;
import com.plotsquared.core.PlotAPI;
import com.plotsquared.core.PlotSquared;
import com.plotsquared.core.configuration.caption.TranslatableCaption;
import com.plotsquared.core.database.DBFunc;
import com.plotsquared.core.events.PlayerAutoPlotEvent;
import com.plotsquared.core.events.PlayerClaimPlotEvent;
import com.plotsquared.core.events.PlotClaimedNotifyEvent;
import com.plotsquared.core.events.TeleportCause;
import com.plotsquared.core.events.post.PostPlayerAutoPlotEvent;
import com.plotsquared.core.player.MetaDataAccess;
import com.plotsquared.core.player.OfflinePlotPlayer;
import com.plotsquared.core.player.PlayerMetaDataKeys;
import com.plotsquared.core.player.PlotPlayer;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.PlotArea;
import com.plotsquared.core.services.plots.AutoService;
import com.plotsquared.core.util.query.PlotQuery;
import com.plotsquared.core.util.task.AutoClaimFinishTask;
import com.plotsquared.core.util.task.RunnableVal;
import com.plotsquared.core.util.task.TaskManager;
import com.plotsquared.plothider.HideFlag;
import edu.umd.cs.findbugs.annotations.NonNull;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.MiniMessageImpl;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nullable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class ContestControllerPlugin extends JavaPlugin implements Listener {

    PlotAPI plotApi = null;
    LuckPerms luckPerms = null;
    DataSource ds = null;
    FileConfiguration config = null;
    List<Component> welcomeMessage = new ArrayList<>();

    @Override
    public void onEnable() {
        plotApi = new PlotAPI();
        luckPerms = LuckPermsProvider.get();
        saveDefaultConfig();
        config = getConfig();

        for (String line : config.getStringList("welcome-message")) {
            welcomeMessage.add(MiniMessage.get().parse(line));
        }

        ds = new DataSource(config);
        Bukkit.getPluginManager().registerEvents(this, this);
        plotApi.registerListener(this);
        getLogger().info("Enabled!");
    }

    @Override
    public void onDisable() {
        welcomeMessage.clear();
        getLogger().info("Disabled!");
    }

    @EventHandler
    public void onAsyncLogin(AsyncPlayerPreLoginEvent event) {
        String username = event.getName();
        if (!username.startsWith("user")) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Неправильное имя пользователя"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        for (Component line : welcomeMessage) {
            event.getPlayer().sendMessage(line);
        }

        PlotPlayer<?> player = PlotSquared.platform().playerManager().getPlayer(event.getPlayer().getUniqueId());
        Optional<Plot> playerPlot = PlotQuery.newQuery().asStream()
                .filter((plot) -> playerId.equals(plot.getOwnerAbs()) || plot.getTrusted().contains(playerId))
                .findFirst();
        if (playerPlot.isPresent()) {
            Plot foundPlot = playerPlot.get();
            if (foundPlot.getArea() == null) {
                getLogger().severe("Plot without area contains player " + event.getPlayer().getName());
                return;
            }
            if (!foundPlot.equals(player.getCurrentPlot())) {
                foundPlot.teleportPlayer(player, TeleportCause.COMMAND_HOME, result -> {});
            }
            return;
        }

        int userId = Integer.parseInt(event.getPlayer().getName().split("user")[1]);
        try (Connection conn = ds.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("SELECT t.leader_id FROM mc_users u JOIN teams t ON t.id = u.team_id WHERE u.id = ?");
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    getLogger().severe("User " + event.getPlayer().getName() + " not found in database!");
                    return;
                }
                int leaderId = rs.getInt(1);
                if (leaderId != userId) {
                    // Try to find leader plot and add as trusted if user is not leader
                    OfflinePlayer offlineLeader = Bukkit.getOfflinePlayer(getNameByUserId(leaderId));
                    Optional<Plot> foundPlot = PlotQuery.newQuery().asStream().filter(
                        plot -> offlineLeader.getUniqueId().equals(plot.getOwnerAbs())
                    ).findFirst();
                    if (foundPlot.isEmpty()) {
                        event.getPlayer().kick(Component.text("Капитан команды не активировал участок!"));
                        return;
                    }
                    foundPlot.get().addTrusted(player.getUUID());
                    foundPlot.get().teleportPlayer(player, TeleportCause.COMMAND_HOME, result -> {});
                    return;
                }
                // Auto-claim plot if user is leader
                User user = luckPerms.getUserManager().loadUser(event.getPlayer().getUniqueId()).join();
                if (!user.data().add(Node.builder("group.leader").build()).wasSuccessful()) {
                    event.getPlayer().kick(Component.text("Ошибка активации!"));
                    return;
                }
                Bukkit.dispatchCommand(event.getPlayer(), "plot auto");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Subscribe
    public void onPlotClaimed(PostPlayerAutoPlotEvent event) {
        event.getPlot().setFlag(HideFlag.HIDE_FLAG_TRUE);
    }

    private String getNameByUserId(int userId) {
        return "user" + String.valueOf(userId);
    }



}
