package net.hiddenhally.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.hiddenhally.client.command.LolcatCommand;
import net.hiddenhally.client.config.SeedStorage;
import net.hiddenhally.client.enchant.EnchantCommand;
import net.hiddenhally.client.enchant.EnchantOverlay;
import net.hiddenhally.client.finder.LootSearchEngine;
import net.hiddenhally.client.finder.SeedFinderEngine;
import net.hiddenhally.client.odds.OddsCommand;

public class LolcatModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		SeedFinderEngine.init();
		LootSearchEngine.warmUp();
		SeedStorage.load();
		EnchantOverlay.register();

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, _) -> {
			LolcatCommand.register(dispatcher);
			EnchantCommand.register(dispatcher); // si unisce al nodo "lolcat" già registrato
			OddsCommand.register(dispatcher);
		});
	}
}