package dev.efnilite.witp.player;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.efnilite.witp.WITP;
import dev.efnilite.witp.generator.ParkourGenerator;
import dev.efnilite.witp.util.Util;
import dev.efnilite.witp.util.Verbose;
import dev.efnilite.witp.util.inventory.InventoryBuilder;
import dev.efnilite.witp.util.inventory.ItemBuilder;
import fr.mrmicky.fastboard.FastBoard;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

/**
 * Class to envelop every user in WITP.
 */
public abstract class ParkourUser {

    public UUID openInventory;
    protected final Player player;
    protected FastBoard board;
    protected GameMode previousGamemode;
    protected Location previousLocation;
    protected HashMap<Integer, ItemStack> previousInventory;

    protected static final HashMap<String, ParkourUser> users = new HashMap<>();
    protected static final HashMap<Player, ParkourPlayer> players = new HashMap<>();
    protected static HashMap<UUID, Integer> highScores = new LinkedHashMap<>();
    protected static final Gson gson = new GsonBuilder().disableHtmlEscaping().excludeFieldsWithoutExposeAnnotation().create();

    public ParkourUser(@NotNull Player player) {
        this.player = player;
        saveInventory();
        this.previousLocation = player.getLocation().clone();
        this.previousGamemode = player.getGameMode();
        this.board = new FastBoard(player);
        // remove duplicates
        users.put(player.getName(), this);
    }

    /**
     * Saves the inventory to cache, so if the player leaves the player gets their items back
     */
    protected void saveInventory() {
        this.previousInventory = new HashMap<>();
        if (ParkourGenerator.Configurable.INVENTORY_HANDLING) {
            int index = 0;
            Inventory inventory = player.getInventory();
            for (ItemStack item : inventory.getContents()) {
                if (item != null) {
                    previousInventory.put(index, item);
                }
                index++;
            }
        }
    }

    /**
     * Gets a user from a Bukkit Player
     *
     * @param   player
     *          The Bukkit Player
     *
     * @return the associated {@link ParkourUser}
     */
    public static @Nullable ParkourUser getUser(@NotNull Player player) {
        for (ParkourUser user : users.values()) {
            if (user.player.getUniqueId() == player.getUniqueId()) {
                return user;
            }
        }
        return null;
    }

    /**
     * Updates the scoreboard
     */
    protected abstract void updateScoreboard();

    /**
     * Gets the highscores of all player
     *
     * @throws  IOException
     *          When creating the file reader goes wrong
     */
    public static void fetchHighScores() throws IOException {
        File folder = new File(WITP.getInstance().getDataFolder() + "/players/");
        if (!(folder.exists())) {
            folder.mkdirs();
            return;
        }
        for (File file : folder.listFiles()) {
            FileReader reader = new FileReader(file);
            ParkourPlayer from = gson.fromJson(reader, ParkourPlayer.class);
            String name = file.getName();
            highScores.put(UUID.fromString(name.substring(0, name.lastIndexOf('.'))), from.highScore);
        }
    }

    /**
     * Sends a message or array of it - coloured allowed, using '&'
     *
     * @param   messages
     *          The message
     */
    public void send(String... messages) {
        for (String msg : messages) {
            player.sendMessage(Util.color(msg));
        }
    }

    /**
     * Opens the gamemode menu
     */
    public void gamemode() {
        InventoryBuilder gamemode = new InventoryBuilder(this, 3, "Gamemode").open();
        InventoryBuilder spectatable = new InventoryBuilder(this, 3, "Select a player").open();
        gamemode.setItem(12, new ItemBuilder(Material.BARREL, "&c&lNormal").setLore("&7Play the game like normal").build(), (t, e) -> {
            try {
                player.closeInventory();
                if (this instanceof ParkourSpectator) {
                    ParkourSpectator spectator = (ParkourSpectator) this;
                    spectator.watching.removeSpectators(spectator);
                }
                ParkourPlayer.register(player);
            } catch (IOException ex) {
                ex.printStackTrace();
                Verbose.error("Error while trying to register player" + player.getName());
            }
        });
        gamemode.setItem(14, new ItemBuilder(Material.GLASS, "&c&lSpectate").setLore("&7Spectate another player").build(), (t, e) -> {
            int index = 0;
            player.closeInventory();
            for (ParkourPlayer pp : getActivePlayers()) {
                if (pp == null || pp.getGenerator() == null) {
                    continue;
                }
                Player player = pp.getPlayer();
                if (player.getUniqueId() != this.player.getUniqueId()) {
                    ItemStack item = new ItemBuilder(Material.PLAYER_HEAD, 1, "&c&l" + player.getName())
                            .setLore("&7Click to spectate " + player.getName()).build();
                    SkullMeta meta = (SkullMeta) item.getItemMeta();
                    if (meta == null) {
                        continue;
                    }
                    meta.setOwningPlayer(player);
                    item.setItemMeta(meta);
                    spectatable.setItem(index, item, (t2, e2) -> new ParkourSpectator(this, pp));
                    index++;
                    if (index == 25) {
                        break;
                    }
                }
            }
            spectatable.setItem(25, new ItemBuilder(Material.PAPER, getTranslated("item-search")).setLore(getTranslated("item-search-lore")).build(),
                    (t2, e2) -> {
                        player.closeInventory();
                        BaseComponent[] send = new ComponentBuilder().append(getTranslated("click-search"))
                                .event(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/witp search ")).create();
                        player.spigot().sendMessage(send);
                    });
            spectatable.setItem(26, new ItemBuilder(Material.ARROW, getTranslated("item-close")).build(), (t2, e2) -> player.closeInventory());
            spectatable.build();
        });
        gamemode.setItem(26, new ItemBuilder(Material.ARROW, getTranslated("item-close")).build(), (t2, e2) -> player.closeInventory());
        gamemode.build();
    }

    /**
     * Shows the scoreboard (as a chat message)
     */
    public void scoreboard(int page) {
        if (highScores.size() == 0) {
            try {
                fetchHighScores();
            } catch (IOException ex) {
                ex.printStackTrace();
                Verbose.error("Error while trying to fetch the high scores!");
            }
        }

        int lowest = page * 10;
        int highest = (page - 1) * 10;
        if (page < 1) {
            return;
        }
        if (page > 1 && highest > highScores.size()) {
            return;
        }

        HashMap<UUID, Integer> sorted = Util.sortByValue(highScores);
        highScores = sorted;
        List<UUID> uuids = new ArrayList<>(sorted.keySet());

        send("", "", "", "", "", "", "", "");
        sendTranslated("divider");
        for (int i = highest; i < lowest; i++) {
            if (i == uuids.size()) {
                break;
            }
            UUID uuid = uuids.get(i);
            if (uuid == null) {
                continue;
            }
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            int rank = i + 1;
            send("&a#" + rank + ". &7" + name + " &f- " + highScores.get(uuid));
        }
        sendTranslated("your-rank", Integer.toString(getRank(player.getUniqueId())), Integer.toString(highScores.get(player.getUniqueId())));
        send("");

        int prevPage = page - 1;
        int nextPage = page + 1;
        BaseComponent[] previous = new ComponentBuilder()
                .append(getTranslated("previous-page"))
                .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/witp leaderboard " + prevPage))
                .append(" | ").color(net.md_5.bungee.api.ChatColor.GRAY)
                .event((ClickEvent) null)
                .append(getTranslated("next-page"))
                .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/witp leaderboard " + nextPage))
                .create();

        player.spigot().sendMessage(previous);
        sendTranslated("divider");
    }

    /**
     * Gets the rank of a certain player
     *
     * @param   player
     *          The player
     *
     * @return the rank (starts at 1.)
     */
    protected int getRank(UUID player) {
        return new ArrayList<>(highScores.keySet()).indexOf(player) + 1;
    }

    /**
     * Gets a message from lang.yml
     *
     * @param   path
     *          The path name in lang.yml (for example: 'time-preference')
     *
     * @param   replaceable
     *          What can be replaced (for example: %s to yes)
     */
    public void sendTranslated(String path, String... replaceable) {
        path = "messages.en." + path;
        String string = WITP.getConfiguration().getString("lang", path);
        if (string == null) {
            Verbose.error("Unknown path: " + path + " - try deleting the config");
            return;
        }
        for (String s : replaceable) {
            string = string.replaceAll("%[a-z]", s);
        }
        send(string);
    }

    /**
     * Same as {@link #sendTranslated(String, String...)}, but without sending the text (used in GUIs)
     * @param path
     * @param replaceable
     * @return
     */
    public String getTranslated(String path, String... replaceable) {
        path = "messages.en." + path;
        String string = WITP.getConfiguration().getString("lang", path);
        if (string == null) {
            Verbose.error("Unknown path: " + path + " - try deleting the config");
            return "";
        }
        for (String s : replaceable) {
            string = string.replaceAll("%[a-z]", s);
        }
        return string;
    }

    public static List<ParkourUser> getUsers() {
        return new ArrayList<>(users.values());
    }

    public static List<ParkourPlayer> getActivePlayers() {
        return new ArrayList<>(players.values());
    }

    /**
     * Gets the scoreboard of the player
     *
     * @return the {@link FastBoard} of the player
     */
    public FastBoard getBoard() {
        return board;
    }

    /**
     * Gets the Bukkit version of the player
     *
     * @return the player
     */
    public @NotNull Player getPlayer() {
        return player;
    }
}