package com.drtshock.playervaults.converters;

import com.drtshock.playervaults.PlayerVaults;
import com.drtshock.playervaults.vaultmanagement.VaultManager;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import me.dablakbandit.bank.BankPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Simple converter for Bank Vaults (https://www.spigotmc.org/resources/bank-lite-16k-downloads-updated-for-1-17.18968/
 *
 * @author Naums Mogers (naums.mogers@gmail.com)
 */
public class BankConverter implements Converter {

    PlayerVaults plugin = null;
    BankPlugin bankPlugin = null;

    @Override
    public int run(CommandSender initiator) {
        if (plugin == null)
            plugin = PlayerVaults.getInstance();

        if (bankPlugin == null) {
            Plugin p = Bukkit.getPluginManager().getPlugin("Bank");
            if (!(p instanceof BankPlugin)) {
                throw new IllegalStateException();
            } else {
                bankPlugin = (BankPlugin) p;
            }
        }

        VaultManager pvVaults = VaultManager.getInstance();

        int converted = 0;
        long lastUpdate = 0;

        boolean savedAVault = false;

        Map<UUID, Map<Integer, List<ItemStack>>> bankVaults = bankPlugin.itemVaultsToExportToPV;

        for (Map.Entry<UUID, Map<Integer, List<ItemStack>>> playerVaults : bankVaults.entrySet()) {
            UUID uuid = playerVaults.getKey();

            for (Map.Entry<Integer, List<ItemStack>> bankVault : playerVaults.getValue().entrySet()) {

                Integer vaultNum = bankVault.getKey();
                List<ItemStack> bankVaultItems = bankVault.getValue();

                if (bankVaultItems.size() == 0)
                    continue;

                Inventory vault = pvVaults.getVault(uuid.toString(), vaultNum);
                if (vault == null) {
                    vault = plugin.getServer().createInventory(null, bankVaultItems.size());
                }

                vault.addItem(bankVaultItems.toArray(new ItemStack[0]));
                pvVaults.saveVault(vault, uuid.toString(), vaultNum);
                savedAVault = true;
            }

            if (savedAVault)
                converted++;

            if (System.currentTimeMillis() - lastUpdate >= 1500) {
                plugin.getLogger().info(converted + " players' bank vaults have been converted");
                lastUpdate = System.currentTimeMillis();
            }
        }

        plugin.getLogger().info(converted + " players' bank vaults have been converted");

        return converted;
    }

    @Override
    public boolean canConvert() {
        return true;
    }

    @Override
    public String getName() {
        return "Bank";
    }
}
