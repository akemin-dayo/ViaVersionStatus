// ViaVersionStatus - Logs players' client versions
// Copyright 2019 Bobcat00
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package com.bobcat00.viaversionstatus;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandException;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.scheduler.BukkitRunnable;

import com.bobcat00.viaversionstatus.connections.ProtocolVersion;
import com.bobcat00.viaversionstatus.connections.PSConnection;
import com.bobcat00.viaversionstatus.connections.ViaConnection;

import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.network.session.GeyserSession;
import org.geysermc.floodgate.FloodgateAPI;
import org.geysermc.floodgate.FloodgatePlayer;

public final class Listeners implements Listener
{
    private ViaVersionStatus plugin;
    
    private ViaConnection via;
    private PSConnection ps;
    
    public enum UseConnection
    {
        USE_VIA,
        USE_PS,
        USE_BOTH,
        USE_NONE
    }
    
    private UseConnection useConnection = UseConnection.USE_NONE;
    
    // Variables for outputting supported protocols at startup
    private boolean outputVia = false;
    private boolean outputPs = false;
    private int protocolListCounter = 0;
    
    // Constructor
    
    public Listeners(ViaVersionStatus plugin)
    {
        this.plugin = plugin;
        
        // Register listener
        
        EventPriority priority = EventPriority.NORMAL;
        if (plugin.config.getHighPriority())
        {
            // Use MONITOR if true
            priority = EventPriority.MONITOR;
        }
        
        plugin.getServer().getPluginManager().registerEvent(PlayerJoinEvent.class, this, priority,
            new EventExecutor() { public void execute(Listener l, Event e) { onPlayerJoin((PlayerJoinEvent)e); }},
            plugin);
        
        plugin.getLogger().info("Using listener priority " + priority.toString() + ".");
        
        // Determine which connection(s) to use
        
        via = new ViaConnection();
        ps = new PSConnection();
        
        if (via.isValid() && ps.isValid())
        {
            useConnection = UseConnection.USE_BOTH;
            outputVia = true;
            outputPs = true;
            plugin.getLogger().info("Using both ViaVersion and ProtocolSupport to determine versions.");
        }
        else if (via.isValid())
        {
            useConnection = UseConnection.USE_VIA;
            outputVia = true;
            plugin.getLogger().info("Using ViaVersion to determine versions.");
        }
        else if (ps.isValid())
        {
            useConnection = UseConnection.USE_PS;
            outputPs = true;
            plugin.getLogger().info("Using ProtocolSupport to determine versions.");
        }
        else
        {
            plugin.getLogger().severe("This plugin requires either ViaVersion or ProtocolSupport or both.");
            plugin.shutdown();
            throw new RuntimeException("ViaVersion or ProtocolSupport required."); // Get the user's attention
        }
        
        // Output supported protocols after giving ViaVersion/ProtocolSupport time to populate them
        
        if (plugin.config.getListSupportedProtocols())
        {
            new BukkitRunnable()
            {
                @Override
                public void run()
                {
                    if (outputVia)
                    {
                        List<ProtocolVersion> protocols = via.getSupportedProtocols();

                        if ((protocols != null) && (!protocols.isEmpty()))
                        {
                            plugin.getLogger().info("ViaVersion supported protocols:");
                            for(ProtocolVersion protocol : protocols)
                            {
                                plugin.getLogger().info(protocol.toString());
                            }
                            // Indicate done
                            outputVia = false;
                        }
                    }
                    
                    if (outputPs)
                    {
                        List<ProtocolVersion> protocols = ps.getSupportedProtocols();

                        if ((protocols != null) && (!protocols.isEmpty()))
                        {
                            plugin.getLogger().info("ProtocolSupport supported protocols:");
                            for(ProtocolVersion protocol : protocols)
                            {
                                plugin.getLogger().info(protocol.toString());
                            }
                            // Indicate done
                            outputPs = false;
                        }
                    }

                    ++protocolListCounter;
                    // Cancel if nothing more is to be done or if we tried 10 times
                    if ((!outputVia && !outputPs) || protocolListCounter >= 10)
                    {
                        this.cancel();
                    }
                }
            }.runTaskTimer(plugin,
                           100L,  // delay 5 sec
                           100L); // period 5 sec
        }
    }
    
    // Get connection used
    
    public UseConnection getConnectionUsed()
    {
        return useConnection;
    }
    
    // Player join event
    
    public void onPlayerJoin(PlayerJoinEvent e)
    {
        final Player player = e.getPlayer();
        
        if (player.hasPermission("viaversionstatus.exempt"))
        {
            return;
        }
        
        // Protocol consists of a Name and Id (toString returns both as a combined string)
        ProtocolVersion serverProtocol = null;
        ProtocolVersion clientProtocol = null;
        
        switch (useConnection)
        {
        case USE_VIA:
            serverProtocol = via.getServerProtocol();
            clientProtocol = via.getProtocol(player);
            break;
            
        case USE_PS:
            serverProtocol = ps.getServerProtocol();
            clientProtocol = ps.getProtocol(player);
            break;
            
        case USE_BOTH:
            serverProtocol = via.getServerProtocol(); // Get server info from ViaVersion

            // If PS ID < server ID, use PS; else use Via
            if (ps.getProtocol(player).getId() < serverProtocol.getId())
            {
                // Use PS
                clientProtocol = ps.getProtocol(player);
            }
            else
            {
                // Use Via
                clientProtocol = via.getProtocol(player);
            }
            break;
            
        case USE_NONE:
            // Should never get here
            return;
            
        default:
            // Should never get here
            return;
        }

        final String javaClientVersion = clientProtocol.getName();
        final String serverVersion = serverProtocol.getName();

        // The usage of the Floodgate API here covers servers that are running Geyser as a standalone service (often on another machine), but with the Floodgate plugin enabled.
        // TODO: Add support for the upcoming Floodgate 2.0, which made significant changes to the API (including renaming the entire API class to FloodgateApi).
        final boolean isGeyserEnabled = plugin.getServer().getPluginManager().getPlugin("Geyser-Spigot") != null;
        final boolean isFloodgateEnabled = plugin.getServer().getPluginManager().getPlugin("floodgate-bukkit") != null;
        final boolean isPlayerUsingBedrock;

        if (isGeyserEnabled)
        {
            isPlayerUsingBedrock = GeyserConnector.getInstance().getPlayerByUuid(player.getUniqueId()) != null;
        }
        else if (isFloodgateEnabled)
        {
            // TODO: Floodgate 2.0's API uses the method isFloodgatePlayer(player.getUniqueId()) instead.
            isPlayerUsingBedrock = FloodgateAPI.isBedrockPlayer(player);
        }
        else
        {
            isPlayerUsingBedrock = false;
        }

        String bedrockClientVersion = null;
        String bedrockClientOperatingSystem = null;

        if (isPlayerUsingBedrock && isGeyserEnabled)
        {
            GeyserSession geyserSession = GeyserConnector.getInstance().getPlayerByUuid(player.getUniqueId());
            bedrockClientVersion = geyserSession.getClientData().getGameVersion();
            bedrockClientOperatingSystem = geyserSession.getClientData().getDeviceOS().toString();
        }
        else if (isPlayerUsingBedrock && isFloodgateEnabled)
        {
            FloodgatePlayer floodgatePlayer = FloodgateAPI.getPlayer(player);
            bedrockClientVersion = floodgatePlayer.getVersion();
            bedrockClientOperatingSystem = floodgatePlayer.getDeviceOS().toString();
        }

        final String bedrockClientInfoString = bedrockClientVersion + " (Bedrock [" + bedrockClientOperatingSystem + "], equivalent to Java " + javaClientVersion + ")";
        final String displayedClientVersionString = (isPlayerUsingBedrock) ? bedrockClientInfoString : ((isGeyserEnabled || isFloodgateEnabled) ? javaClientVersion + " (Java)" : javaClientVersion);

        // 1. Write to log file
        
        if (!player.hasPermission("viaversionstatus.exempt.log"))
        {
            plugin.getLogger().info(player.getName() + " is using Minecraft Java network protocol version " + clientProtocol.toString() + ".");
            if (isPlayerUsingBedrock)
            {
                plugin.getLogger().info(player.getName() + " is connecting from a Bedrock client, version " + bedrockClientInfoString + ".");
            }
        }

        // 2. Notify any player with the `viaversionstatus.notify` permission (ops by default)

        if (!player.hasPermission("viaversionstatus.exempt.notify"))
        {
            if (!player.hasPermission("viaversionstatus.exempt.notify.message"))
            {
                for (Player p : Bukkit.getServer().getOnlinePlayers())
                {
                    if (p.hasPermission("viaversionstatus.notify"))
                    {
                        p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            plugin.config.getNotifyString().replace("%player%",      player.getName()).
                                                            replace("%displayname%", player.getDisplayName()).
                                                            replace("%version%",     displayedClientVersionString).
                                                            replace("%server%",      serverVersion)));
                    }
                }
            }

            if (!player.hasPermission("viaversionstatus.exempt.notify.command"))
            {
                String notifyCommand = plugin.config.getNotifyCommand();
                if (!notifyCommand.isEmpty())
                {
                    notifyCommand = notifyCommand.replace("%player%",      player.getName()).
                                                  replace("%displayname%", player.getDisplayName()).
                                                  replace("%version%",     displayedClientVersionString).
                                                  replace("%server%",      serverVersion);
                    plugin.getLogger().info("Executing command " + notifyCommand);
                    try
                    {
                        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), notifyCommand);
                    }
                    catch (CommandException exc)
                    {
                        plugin.getLogger().info("Command returned exception: " + exc.getMessage());
                    }
                }
            }
        }
        
        // 3. Warn player if they are connecting using an older version
        
        if (plugin.config.getOlderVersionWarnPlayers() &&
            !serverVersion.equals("UNKNOWN") &&
            (clientProtocol.getId() < serverProtocol.getId()) &&
            !player.hasPermission("viaversionstatus.exempt.warn"))
        {
            handleMismatchedClientServerVersionsForTargetPlayer(player, "viaversionstatus.exempt.warn.message", "viaversionstatus.exempt.warn.command", plugin.config.getOlderVersionWarnString(), plugin.config.getOlderVersionWarnCommand(), displayedClientVersionString, serverVersion);
        }

        // 4. Warn player if they are connecting using a newer version

        if (plugin.config.getNewerVersionWarnPlayers() &&
            !serverVersion.equals("UNKNOWN") &&
            (clientProtocol.getId() > serverProtocol.getId()) &&
            !player.hasPermission("viaversionstatus.exempt.warn.newer"))
        {
            handleMismatchedClientServerVersionsForTargetPlayer(player, "viaversionstatus.exempt.warn.newer.message", "viaversionstatus.exempt.warn.newer.command", plugin.config.getNewerVersionWarnString(), plugin.config.getNewerVersionWarnCommand(), displayedClientVersionString, serverVersion);
        }
        
        // 5. Send to Prism
        
        if (plugin.prismHooked)
        {
            plugin.prismEvent.callPrismEvent(plugin, "vvs-client-connect", player, clientProtocol.toString());
        }

    }

    private void handleMismatchedClientServerVersionsForTargetPlayer(Player targetPlayer, String messagePermissionString, String commandPermissionString, String warnMessageFromPreferences, String warnCommandFromPreferences, String currentPlayerClientVersion, String currentServerVersion)
    {
        if (!targetPlayer.hasPermission(messagePermissionString))
        {
            // Delay by 250 msec (5 ticks) to make sure the player sees the message
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable()
            {
                @Override
                public void run()
                {
                    if (targetPlayer.isOnline())
                    {
                        targetPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            warnMessageFromPreferences.replace("%player%",      targetPlayer.getName()).
                                                       replace("%displayname%", targetPlayer.getDisplayName()).
                                                       replace("%version%",     currentPlayerClientVersion).
                                                       replace("%server%",      currentServerVersion)));
                    }
                }
            }, 5L); // time delay (ticks)
        }

        if (!targetPlayer.hasPermission(commandPermissionString))
        {
            if (!warnCommandFromPreferences.isEmpty())
            {
                warnCommandFromPreferences = warnCommandFromPreferences.replace("%player%",      targetPlayer.getName()).
                                                                        replace("%displayname%", targetPlayer.getDisplayName()).
                                                                        replace("%version%",     currentPlayerClientVersion).
                                                                        replace("%server%",      currentServerVersion);
                plugin.getLogger().info("Executing command " + warnCommandFromPreferences);
                try
                {
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), warnCommandFromPreferences);
                }
                catch (CommandException exc)
                {
                    plugin.getLogger().info("Command returned exception: " + exc.getMessage());
                }
            }
        }
    }
}
