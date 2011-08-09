package com.garbagemule.MobArena;

import java.io.File;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.block.BlockListener;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.entity.EntityListener;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.config.Configuration;

import com.nijiko.permissions.PermissionHandler;
import com.nijikokun.bukkit.Permissions.Permissions;
import com.garbagemule.MobArena.util.FileUtils;
import com.garbagemule.register.payment.Method;
import com.garbagemule.register.payment.Methods;

/**
 * MobArena
 * @author garbagemule
 */
public class MobArena extends JavaPlugin
{
    private Configuration config;
    private ArenaMaster am;
    
    // Permissions stuff
    private PermissionHandler permissionHandler;
    
    // Economy stuff
    protected Methods Methods;
    protected Method  Method;
    
    // Global variables
    public static PluginDescriptionFile desc;
    public static File dir, arenaDir;
    public static final double MIN_PLAYER_DISTANCE = 256.0;
    public static final int ECONOMY_MONEY_ID = -29;

    public void onEnable()
    {        
        // Description file and data folders
        desc     = getDescription();
        dir      = getDataFolder();
        arenaDir = new File(dir, "arenas"); 
        if (!dir.exists()) dir.mkdir();
        if (!arenaDir.exists()) arenaDir.mkdir();
        
        // Create default files and initialize config-file
        FileUtils.extractDefaults("config.yml");
        loadConfig();
        
        // Download external libraries if needed.
        FileUtils.fetchLibs(config);
        
        // Set up permissions and economy
        //setupSuperPerms();
        setupPermissions();
        setupRegister();
        
        // Set up the ArenaMaster and the announcements
        am = new ArenaMaster(this);
        am.initialize();
        MAMessages.init(this);
        
        // Register event listeners
        registerListeners();
        
        // Announce enable!
        info("v" + desc.getVersion() + " enabled.");
    }
    
    public void onDisable()
    {
        // Force all arenas to end.
        if (am == null) return;
        for (Arena arena : am.arenas)
            arena.forceEnd();
        am.arenaMap.clear();
        
        // Permissions & Economy
        permissionHandler = null;
        if (Methods != null && Methods.hasMethod())
        {
            Methods = null;
            info("Payment method was disabled. No longer accepting payments.");
        }
        
        info("disabled.");
    }
    
    private void loadConfig()
    {
        File file = new File(dir, "config.yml");
        if (!file.exists())
        {
            error("Config-file could not be created!");
            return;
        }
        
        config = new Configuration(file);
        config.load();
        config.setHeader(getHeader());
    }
    
    private void registerListeners()
    {
        // Bind the /ma, /mobarena commands to MACommands.
        MACommands commandExecutor = new MACommands(this, am);
        getCommand("ma").setExecutor(commandExecutor);
        getCommand("mobarena").setExecutor(commandExecutor);
        
        // Create event listeners.
        PluginManager pm = getServer().getPluginManager();
        PlayerListener playerListener = new MAPlayerListener(this, am);
        EntityListener entityListener = new MAEntityListener(am);
        BlockListener  blockListener  = new MABlockListener(am);
        
        // Register events.
        pm.registerEvent(Event.Type.PLAYER_INTERACT,           playerListener,   Priority.Normal,  this);
        pm.registerEvent(Event.Type.PLAYER_DROP_ITEM,          playerListener,   Priority.Normal,  this);
        pm.registerEvent(Event.Type.PLAYER_BUCKET_EMPTY,       playerListener,   Priority.Normal,  this);
        pm.registerEvent(Event.Type.PLAYER_TELEPORT,           playerListener,   Priority.Normal,  this);
        pm.registerEvent(Event.Type.PLAYER_QUIT,               playerListener,   Priority.Normal,  this);
        pm.registerEvent(Event.Type.PLAYER_KICK,               playerListener,   Priority.Normal,  this);
        pm.registerEvent(Event.Type.PLAYER_JOIN,               playerListener,   Priority.Normal,  this);
        pm.registerEvent(Event.Type.PLAYER_ANIMATION,          playerListener,   Priority.Normal,  this);
        pm.registerEvent(Event.Type.BLOCK_BREAK,               blockListener,    Priority.Highest, this);
        pm.registerEvent(Event.Type.BLOCK_PLACE,               blockListener,    Priority.Highest, this);
        pm.registerEvent(Event.Type.ENTITY_DAMAGE,             entityListener,   Priority.High,    this); // mcMMO is "Highest"
        pm.registerEvent(Event.Type.ENTITY_DEATH,              entityListener,   Priority.Lowest,  this); // Lowest because of Tombstone
        pm.registerEvent(Event.Type.ENTITY_REGAIN_HEALTH,      entityListener,   Priority.Normal,  this);
        pm.registerEvent(Event.Type.ENTITY_EXPLODE,            entityListener,   Priority.Highest, this);
        pm.registerEvent(Event.Type.EXPLOSION_PRIME,           entityListener,   Priority.Normal,  this);
        pm.registerEvent(Event.Type.ENTITY_COMBUST,            entityListener,   Priority.Normal,  this);
        pm.registerEvent(Event.Type.ENTITY_TARGET,             entityListener,   Priority.Normal,  this);
        pm.registerEvent(Event.Type.CREATURE_SPAWN,            entityListener,   Priority.Highest, this);
        pm.registerEvent(Event.Type.PLAYER_COMMAND_PREPROCESS, playerListener,   Priority.Monitor, this);
    }
    
    // Permissions stuff
    public boolean has(Player p, String s)
    {
        //return (permissionHandler == null || permissionHandler.has(p, s));
        return hasSuperPerms(p, s) || hasNijikoPerms(p, s);
    }
    
    public boolean hasSuperPerms(Player p, String s)
    {
        return p.hasPermission(s);
    }
    
    public boolean hasNijikoPerms(Player p, String s)
    {
        return permissionHandler != null && permissionHandler.has(p, s);
    }
    
    // Console printing
    public static void info(String msg)    { Bukkit.getServer().getLogger().info("[MobArena] " + msg); }
    public static void warning(String msg) { Bukkit.getServer().getLogger().warning("[MobArena] " + msg); }    
    public static void error(String msg)   { Bukkit.getServer().getLogger().severe("[MobArena] " + msg); }
    
    private void setupSuperPerms()
    {
        getServer().getPluginManager().addPermission(new Permission("mobarena.classes"));
        getServer().getPluginManager().addPermission(new Permission("mobarena.arenas"));
    }
    
    private void setupPermissions()
    {
        if (permissionHandler != null)
            return;
    
        Plugin permissionsPlugin = this.getServer().getPluginManager().getPlugin("Permissions");
        if (permissionsPlugin == null) return;
        
        permissionHandler = ((Permissions) permissionsPlugin).getHandler();
    }
    
    private void setupRegister()
    {
        Methods = new Methods();
        if (!Methods.hasMethod() && Methods.setMethod(this))
        {
            Method = Methods.getMethod();
            info("Payment method found (" + Method.getName() + " version: " + Method.getVersion() + ")");
        }
    }
    
    public Configuration getConfig()      { return config; }
    public ArenaMaster   getAM()          { return am; } // More convenient.
    public ArenaMaster   getArenaMaster() { return am; }
    
    private String getHeader()
    {
        String sep = System.getProperty("line.separator");
        return "# MobArena v" + desc.getVersion() + " - Config-file" + sep + 
               "# Read the Wiki for details on how to set up this file: http://goo.gl/F5TTc" + sep +
               "# Note: You -must- use spaces instead of tabs!";
    }
}