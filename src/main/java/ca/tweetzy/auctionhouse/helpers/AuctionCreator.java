/*
 * Auction House
 * Copyright 2022 Kiran Hart
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ca.tweetzy.auctionhouse.helpers;

import ca.tweetzy.auctionhouse.AuctionHouse;
import ca.tweetzy.auctionhouse.ahv3.api.ListingResult;
import ca.tweetzy.auctionhouse.api.AuctionAPI;
import ca.tweetzy.auctionhouse.api.hook.McMMOHook;
import ca.tweetzy.auctionhouse.auction.AuctionPlayer;
import ca.tweetzy.auctionhouse.auction.AuctionedItem;
import ca.tweetzy.auctionhouse.settings.Settings;
import ca.tweetzy.core.hooks.EconomyManager;
import ca.tweetzy.core.utils.PlayerUtils;
import ca.tweetzy.flight.comp.NBTEditor;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static ca.tweetzy.auctionhouse.ahv3.api.ListingResult.*;

@UtilityClass
public final class AuctionCreator {

	public void create(@NonNull final AuctionPlayer auctionPlayer, @NonNull final AuctionedItem auctionItem, @NonNull final BiConsumer<AuctionedItem, ListingResult> result) {
		final AtomicReference<ListingResult> status = new AtomicReference<>(SUCCESS);

		final AuctionHouse instance = AuctionHouse.getInstance();
		final Player seller = auctionPlayer.getPlayer();

		// Check if player is even valid?!?
		if (seller == null) {
			result.accept(auctionItem, PLAYER_INSTANCE_NOT_FOUND);
			return;
		}

		// Hooks & Special Cases
		if (McMMOHook.isUsingAbility(seller)) {
			instance.getLocale().getMessage("general.mcmmo_ability_active").sendPrefixedMessage(seller);
			result.accept(auctionItem, CANNOT_LIST_WITH_MCMMO_ABILITY_ACTIVE);
			return;
		}

		if (!Settings.ALLOW_SALE_OF_DAMAGED_ITEMS.getBoolean() && AuctionAPI.getInstance().isDamaged(auctionItem.getItem())) {
			instance.getLocale().getMessage("general.cannot list damaged item").sendPrefixedMessage(seller);
			result.accept(auctionItem, CANNOT_SELL_DAMAGED_ITEM);
			return;
		}

		if (Settings.PREVENT_SALE_OF_REPAIRED_ITEMS.getBoolean() && AuctionAPI.getInstance().isRepaired(auctionItem.getItem())) {
			instance.getLocale().getMessage("general.cannot list repaired item").sendPrefixedMessage(seller);
			result.accept(auctionItem, CANNOT_SELL_REPAIRED_ITEM);
			return;
		}

		if (!AuctionAPI.getInstance().meetsMinItemPrice(NBTEditor.contains(auctionItem.getItem(), "AuctionBundleItem"), auctionItem.isBidItem(), auctionItem.getItem(), auctionItem.getBasePrice(), auctionItem.getBidStartingPrice())) {
			instance.getLocale().getMessage("pricing.minitemprice").processPlaceholder("price", AuctionAPI.getInstance().formatNumber(AuctionHouse.getInstance().getMinItemPriceManager().getMinPrice(auctionItem.getItem()).getPrice())).sendPrefixedMessage(seller);
			return;
		}

		final double originalBasePrice = auctionItem.getBasePrice();
		final double originalStartPrice = auctionItem.getBidStartingPrice();
		final double originalIncrementPrice = auctionItem.getBidIncrementPrice();

		/*

		auctionedItem.setBasePrice(Settings.ROUND_ALL_PRICES.getBoolean() ? Math.round(this.isAllowingBuyNow ? buyNowPrice : -1) : this.isAllowingBuyNow ? buyNowPrice : -1);
		auctionedItem.setBidStartingPrice(Settings.ROUND_ALL_PRICES.getBoolean() ? Math.round(this.isBiddingItem ? this.bidStartPrice : 0) : this.isBiddingItem ? this.bidStartPrice : 0);
		auctionedItem.setBidIncrementPrice(Settings.ROUND_ALL_PRICES.getBoolean() ? Math.round(this.isBiddingItem ? this.bidIncrementPrice != null ? this.bidIncrementPrice : Settings.MIN_AUCTION_INCREMENT_PRICE.getDouble() : 0) : this.isBiddingItem ? this.bidIncrementPrice != null ? this.bidIncrementPrice : Settings.MIN_AUCTION_INCREMENT_PRICE.getDouble() : 0);
		auctionedItem.setCurrentPrice(Settings.ROUND_ALL_PRICES.getBoolean() ? Math.round(this.isBiddingItem ? this.bidStartPrice : this.buyNowPrice <= -1 ? this.bidStartPrice : this.buyNowPrice) : this.isBiddingItem ? this.bidStartPrice : this.buyNowPrice <= -1 ? this.bidStartPrice : this.buyNowPrice);
		 */

		final double listingFee = Settings.TAX_ENABLED.getBoolean() && Settings.TAX_CHARGE_LISTING_FEE.getBoolean() ? AuctionAPI.getInstance().calculateListingFee(originalBasePrice) : 0;

		// check tax
		if (Settings.TAX_ENABLED.getBoolean() && Settings.TAX_CHARGE_LISTING_FEE.getBoolean()) {
			if (!EconomyManager.hasBalance(seller, listingFee)) {
				instance.getLocale().getMessage("auction.tax.cannotpaylistingfee").processPlaceholder("price", AuctionAPI.getInstance().formatNumber(listingFee)).sendPrefixedMessage(seller);
				result.accept(auctionItem, CANNOT_PAY_LISTING_FEE);
				return;
			}

			EconomyManager.withdrawBalance(seller, listingFee);
			AuctionHouse.getInstance().getLocale().getMessage("auction.tax.paidlistingfee").processPlaceholder("price", AuctionAPI.getInstance().formatNumber(listingFee)).sendPrefixedMessage(seller);
			AuctionHouse.getInstance().getLocale().getMessage("pricing.moneyremove").processPlaceholder("player_balance", AuctionAPI.getInstance().formatNumber(EconomyManager.getBalance(seller))).processPlaceholder("price", AuctionAPI.getInstance().formatNumber(listingFee)).sendPrefixedMessage(seller);
		}

		// final item adjustments
		if (auctionItem.getListedWorld() == null)
			auctionItem.setListedWorld(seller.getWorld().getName());

		// Actually attempt the insertion now
		AuctionHouse.getInstance().getDataManager().insertAuctionAsync(auctionItem, (error, inserted) -> {
			auctionPlayer.setItemBeingListed(null);

			if (error != null) {
				instance.getLocale().getMessage("general.something_went_wrong_while_listing").sendPrefixedMessage(seller);
				ItemStack originalCopy = auctionItem.getItem().clone();
				int totalOriginal = NBTEditor.contains(originalCopy, "AuctionBundleItem") ? AuctionAPI.getInstance().getItemCountInPlayerInventory(seller, originalCopy) : originalCopy.getAmount();

				if (NBTEditor.contains(originalCopy, "AuctionBundleItem")) {
					originalCopy.setAmount(1);
					for (int i = 0; i < totalOriginal; i++) PlayerUtils.giveItem(seller, originalCopy);
				} else {
					originalCopy.setAmount(totalOriginal);
					PlayerUtils.giveItem(seller, originalCopy);
				}

				// If the item could not be added for whatever reason and the tax listing fee is enabled, refund them
				if (Settings.TAX_ENABLED.getBoolean() && Settings.TAX_CHARGE_LISTING_FEE.getBoolean()) {
					EconomyManager.deposit(seller, listingFee);
					AuctionHouse.getInstance().getLocale().getMessage("pricing.moneyadd").processPlaceholder("player_balance", AuctionAPI.getInstance().formatNumber(EconomyManager.getBalance(seller))).processPlaceholder("price", AuctionAPI.getInstance().formatNumber(listingFee)).sendPrefixedMessage(seller);
				}

				result.accept(auctionItem, UNKNOWN);
				return;
			}

			AuctionHouse.getInstance().getAuctionItemManager().addAuctionItem(auctionItem);
			result.accept(auctionItem, SUCCESS);

		});
	}
}