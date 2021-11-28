package com.drtshock.playervaults.converters;

import com.drtshock.playervaults.PlayerVaults;
import com.drtshock.playervaults.vaultmanagement.VaultManager;
import com.google.gson.*;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * Simple converter for Bank Vaults (https://www.spigotmc.org/resources/bank-lite-16k-downloads-updated-for-1-17.18968/
 *
 * @author Naums Mogers (naums.mogers@gmail.com)
 */
public class BankConverter implements Converter {

    Connection connection = null;
    String dbFileName = "database.db";
    PlayerVaults plugin = null;

    @Override
    public int run(CommandSender initiator) {
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

                    while (itemsInfoResult.next()) {
                        UUID uuid = UUID.fromString(itemsInfoResult.getString("uuid"));
                        String itemInfoStr = itemsInfoResult.getString("value");
                        try {
                            BankVaultsInfo bankVaultsInfo = (new Gson()).fromJson(itemInfoStr, BankVaultsInfo.class);

                            boolean savedAVault = false;

                            Map<String, ItemStack[]> bankVaults = bankVaultsInfo.getVaults();

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

    @Override
    public boolean canConvert() {
        PlayerVaults plugin = PlayerVaults.getInstance();
        File expectedFolder = new File(plugin.getDataFolder().getParentFile(), "Bank");
        if (!expectedFolder.exists())
            return false;

        File databaseFile = new File(expectedFolder, dbFileName);
        if (!databaseFile.exists())
            return false; //todo: check version

        return connect();
    }

    @Override
    public String getName() {
        return "Bank";
    }

    private static class BankVaultsInfo {
        public Map<String, Map<String, Object>[]> itemMap;

        public BankVaultsInfo(Map<String, Map<String, Object>[]> itemMap) {
            this.itemMap = itemMap;
        }

        public @NotNull Map<String, ItemStack[]> getVaults() {
            HashMap<String, ItemStack[]> vaults = new HashMap<>();

            for (Map.Entry<String, Map<String, Object>[]> vaultItemMaps : this.itemMap.entrySet()) {
                ArrayList<ItemStack> stack = new ArrayList<>();

                for (Map<String, Object> item : vaultItemMaps.getValue()) {
                    HashMap<String, Object> mutableItem = new HashMap<>(item);

                    if (mutableItem.containsKey("material")) {
                        Object material = mutableItem.remove("material");
                        mutableItem.put("type", material);
                    }
                    // Prevents Spigot / Paper from prepending material name with "LEGACY_" in ItemStack.deserialize
                    mutableItem.put("v", 0);

                    stack.add(ItemStack.deserialize(mutableItem));
                }

                ItemStack[] stackArray = new ItemStack[stack.size()];
                stackArray = stack.toArray(stackArray);
                vaults.put(vaultItemMaps.getKey(), stackArray);
            }

            return vaults;
        }
    }
}
