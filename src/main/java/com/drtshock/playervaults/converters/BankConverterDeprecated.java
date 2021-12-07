package com.drtshock.playervaults.converters;

import com.drtshock.playervaults.PlayerVaults;
import com.drtshock.playervaults.config.file.Config;
import com.drtshock.playervaults.vaultmanagement.VaultManager;
//import com.elmakers.mine.bukkit.api.item.ItemData;
//import com.elmakers.mine.bukkit.api.magic.MagicAPI;
import com.google.gson.*;
import me.dablakbandit.bank.BankPlugin;
import me.dablakbandit.bank.api.BankAPI;
import me.dablakbandit.bank.api.Economy_Bank;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.instrument.IllegalClassFormatException;
import java.sql.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Simple converter for Bank Vaults (https://www.spigotmc.org/resources/bank-lite-16k-downloads-updated-for-1-17.18968/
 *
 * @author Naums Mogers (naums.mogers@gmail.com)
 */
public class BankConverterDeprecated implements Converter {

    Connection connection = null;
    static final String dbFileName = "database.db";
    PlayerVaults plugin = null;

    static final String[] testedBankVersions = {"4.3.0-RELEASE"};
    String conversionNotRecommendedMsg = null;
    boolean overrideFurtherVersionChecks = false;

    @Override
    public int run(CommandSender initiator) {
        if (conversionNotRecommendedMsg != null) {
            if (initiator != null)
                initiator.sendMessage(conversionNotRecommendedMsg);

            conversionNotRecommendedMsg = null;
            overrideFurtherVersionChecks = true;
            return -1;
        }

        if (plugin == null)
            plugin = PlayerVaults.getInstance();

        VaultManager pvVaults = VaultManager.getInstance();

        if (!connect())
            return -1;

        try (Statement schemaSt = connection.createStatement();
             ResultSet schemaResult = schemaSt.executeQuery(
                     "SELECT id FROM bank_info_type WHERE LOWER(info) == \"bankitemsinfo\" LIMIT 1;")) {

            if (!schemaResult.next()) {
                throw new SQLException("Cannot get schema info from the Bank database.");
            } else {
                int bankItemsInfoFieldId = schemaResult.getInt("id");

                try (Statement itemsInfoSt = connection.createStatement();
                     ResultSet itemsInfoResult = itemsInfoSt.executeQuery(String.format(
                             "SELECT * FROM bank_player_info where info_id == %d", bankItemsInfoFieldId))) {

                    int converted = 0;
                    long lastUpdate = 0;

//                    MagicAPI magic = getMagicAPI();

                    while (itemsInfoResult.next()) {
                        UUID uuid = UUID.fromString(itemsInfoResult.getString("uuid"));
                        String itemInfoStr = itemsInfoResult.getString("value");
                        try {
                            BankVaultsInfo bankVaultsInfo = (new Gson()).fromJson(itemInfoStr, BankVaultsInfo.class);

                            boolean savedAVault = false;

                            Map<String, ItemStack[]> bankVaults = bankVaultsInfo.getVaults(/*magic*/);

                            for (Map.Entry<String, ItemStack[]> bankVault : bankVaults.entrySet()) {

                                ItemStack[] bankVaultItems = bankVault.getValue();

                                if (bankVaultItems.length == 0)
                                    continue;

                                int vaultNum;
                                try {
                                    vaultNum = Integer.parseInt(bankVault.getKey());
                                } catch (NumberFormatException e) {
                                    plugin.getLogger().warning("Unexpected Bank Vault info format:\n" + e.getMessage());
                                    return -1;
                                }

                                Inventory vault = pvVaults.getVault(uuid.toString(), vaultNum);
                                if (vault == null) {
                                    vault = plugin.getServer().createInventory(null, bankVaultItems.length);
                                }

                                vault.addItem(bankVaultItems);
                                pvVaults.saveVault(vault, uuid.toString(), vaultNum);
                                savedAVault = true;
                            }

                            if (savedAVault)
                                converted++;

                            if (System.currentTimeMillis() - lastUpdate >= 1500) {
                                plugin.getLogger().info(converted + " players' bank vaults have been converted");
                                lastUpdate = System.currentTimeMillis();
                            }

                        } catch (JsonParseException e) {
                            plugin.getLogger().warning(e.getMessage());
                            return -1;
                        }
                    }

                    return converted;
                }
            }

        } catch (SQLException e) {
            plugin.getLogger().warning("Error accessing Bank database:\n" + e.getMessage());
            return -1;
        } finally {
            try {
                connection.close();
                connection = null;
                plugin.getLogger().info("Closed connection to the database");
            } catch (SQLException e) {
                plugin.getLogger().warning(e.getMessage());
            }
        }
    }

    private boolean connect() {
        try {
            if (connection != null && !connection.isClosed())
                return true;
        } catch (SQLException e) {
            connection = null;
        }

        if (plugin == null)
            plugin = PlayerVaults.getInstance();

        try {
            String dbPath = "jdbc:sqlite:" + plugin.getDataFolder().getParentFile() + File.separator + "Bank" + File.separator + dbFileName;
            connection = DriverManager.getConnection(dbPath);
            plugin.getLogger().info("Connected to " + dbPath);
        } catch (SQLException e) {
            plugin.getLogger().warning("Error opening Bank database:\n" + e.getMessage());
            return false;
        }

        return true;
    }

    /**
     * If the Bank plugin version check fails or cannot be performed, canConvert() returns true and requires the user to confirm their intention to proceed by replacing the next execution of run() with printing a warning. After the next execution of run(), this check is disabled.
     */
    @Override
    public boolean canConvert() {
        //noinspection ConstantConditions
        assert(testedBankVersions.length > 0);

        PlayerVaults plugin = PlayerVaults.getInstance();
        File expectedFolder = new File(plugin.getDataFolder().getParentFile(), "Bank");
        if (!expectedFolder.exists())
            return false;

        File databaseFile = new File(expectedFolder, dbFileName);
        if (!databaseFile.exists())
            return false;

        Plugin bankPlugin = plugin.getServer().getPluginManager().getPlugin("Bank");

        boolean connected = connect();

        if (!connected)
            return false;

        if (!overrideFurtherVersionChecks) {
            if (bankPlugin == null || bankPlugin.getDescription() == null || bankPlugin.getDescription().getVersion() == null) {
                conversionNotRecommendedMsg = "Could not confirm the version of the Bank plugin. Tested versions are: " +
                        String.join(", ", testedBankVersions) +
                        ". Run the command again if you want to proceed anyway.";
            } else {
                String bankPluginVersion = bankPlugin.getDescription().getVersion();
                boolean bankPluginVersionWasTested = false;

                for (String testedVersion : testedBankVersions) {
                    if (bankPluginVersion.equals(testedVersion)) {
                        bankPluginVersionWasTested = true;
                        break;
                    }
                }

                if (!bankPluginVersionWasTested) {
                    conversionNotRecommendedMsg = "Bank plugin version " + bankPluginVersion +
                            " has not been tested. Tested versions are: " +
                            String.join(", ", testedBankVersions) +
                            ". Run the command again if you want to proceed anyway.";
                }
            }
        }

        return true;
    }

    @Override
    public String getName() {
        return "Bank";
    }

    private final static Map<String, BiConsumer<ConfigurationSection, Object>> displayParser =
            new HashMap<String, BiConsumer<ConfigurationSection, Object>>() {{
                //noinspection unchecked
                put("Lore", (c, arg) -> c.set("tags.display.Lore", ((ArrayList<Object>)((ArrayList<Object>)(((ArrayList<Object>)arg).get(1))).get(0)).get(1)));
                //noinspection unchecked
                put("Name", (c, arg) -> c.set("tags.display.Name", (((ArrayList<Object>)arg).get(1))));
            }};

    private final static Map<String, BiConsumer<ConfigurationSection, Object>> tagParser =
            new HashMap<String, BiConsumer<ConfigurationSection, Object>>() {{
                //noinspection unchecked
                put("Unbreakable",  (c, arg) -> c.set("tags.Unbreakable", ((ArrayList<Integer>) arg).get(1)));
                put("HideFlags",    (c, arg) -> {
                    @SuppressWarnings("unchecked") int oneHotEncoding = ((ArrayList<Integer>) arg).get(1);
                    c.set("tags.HideFlags", oneHotEncoding);
                });
                put("display",      (c, arg) -> {
                    //noinspection unchecked
                    ((Map<String, Object>) ((ArrayList<Object>)arg).get(1)).forEach((k, v) ->
                            displayParser.get(k).accept(c, v));
                });
                put("Damage",       (c, arg) -> {
                    @SuppressWarnings("unchecked") Object argArg = ((ArrayList<Object>) arg).get(1);
                    int intArgArg = (argArg instanceof Double ? (int)(double)argArg : (int)argArg);
//                    assert c.getInt("meta.Damage") == 0 || c.getInt("meta.Damage") == intArgArg;

                    c.set("tags.Damage", intArgArg);
                });
            }};

    private final static Map<String, BiConsumer<ConfigurationSection, Object>> rootParser =
            new HashMap<String, BiConsumer<ConfigurationSection, Object>>() {{
                put("amount",           (c, arg) -> c.set("tags.Count", arg));
                put("material",         (c, arg) -> c.set("type", arg));
                put("tag",              (c, arg) -> {
                    //noinspection unchecked
                    ((Map<String, Object>) arg).forEach((k, v) ->
                            tagParser.get(k).accept(c, v));
                });
            }};

    private static class BankVaultsInfo {
        public Map<String, Map<String, Object>[]> itemMap;

        public BankVaultsInfo(Map<String, Map<String, Object>[]> itemMap) {
            this.itemMap = itemMap;
        }

        public @NotNull Map<String, ItemStack[]> getVaults(/*MagicAPI magic*/) {
            HashMap<String, ItemStack[]> vaults = new HashMap<>();

            for (Map.Entry<String, Map<String, Object>[]> vaultItemMaps : this.itemMap.entrySet()) {
                ArrayList<ItemStack> stack = new ArrayList<>();

                for (Map<String, Object> item : vaultItemMaps.getValue()) {
//                    HashMap<String, Object> mutableItem = new HashMap<>(item);
//
//                    if (mutableItem.containsKey("material")) {
//                        Object material = mutableItem.remove("material");
//                        mutableItem.put("type", material);
//                    }
//
//                    if (mutableItem.containsKey("tag")) {
//                        Object tag = mutableItem.remove("tag");
//                        mutableItem.put("meta", tag);
//                    }
//                    // Prevents Spigot / Paper from prepending material name with "LEGACY_" in ItemStack.deserialize
//                    mutableItem.put("v", 0);
//
//                    stack.add(ItemStack.deserialize(mutableItem));

                    MemoryConfiguration itemConfig = new MemoryConfiguration();
                    itemConfig.createSection("item");
                    itemConfig.set("item.==", "org.bukkit.inventory.ItemStack");
                    itemConfig.set("item.v", 2586); // todo: confirm
                    itemConfig.set("item.meta.==", "ItemMeta");
                    itemConfig.set("item.meta.meta-type", "UNSPECIFIC");
                    item.forEach((k, v) ->
                            rootParser.get(k).accept(itemConfig.getConfigurationSection("item"), v));
//                    ItemStack result = itemConfig.getItemStack("item");
//                    magic.getController().loadItemTemplate(
//                            Objects.requireNonNull(itemConfig.get("item.type")).toString(),
//                            itemConfig);
//                    ItemData itemData = magic.getController().getOrCreateItem(
//                            Objects.requireNonNull(itemConfig.get("item.type")).toString());
//                    ItemStack result = itemData.getItemStack();

//                    if (result == null)
//                        throw new IllegalStateException();
//
//                    stack.add(result);
                }

                ItemStack[] stackArray = new ItemStack[stack.size()];
                stackArray = stack.toArray(stackArray);
                vaults.put(vaultItemMaps.getKey(), stackArray);
            }

            return vaults;
        }
    }

//    MagicAPI getMagicAPI() {
//        Plugin magicPlugin = Bukkit.getPluginManager().getPlugin("Magic");
//        if (magicPlugin == null || !(magicPlugin instanceof MagicAPI)) {
//            return null;
//        }
//        return (MagicAPI)magicPlugin;
//    }

//    BankAPI getBankAPI() {
//        Plugin bankPlugin = Bukkit.getPluginManager().getPlugin("Bank");
//        if (!(bankPlugin instanceof BankPlugin)) {
//            throw new IllegalStateException();
//        }
//
//        bankPlugin.
//    }
}
