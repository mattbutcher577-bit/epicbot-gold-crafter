package com.goldcrafter;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import com.epicbot.api.shared.entity.GroundItem;
import com.epicbot.api.shared.entity.Player;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.model.ItemDetail;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.script.LoopScript;
import com.epicbot.api.shared.script.ScriptManifest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

@ScriptManifest(name = "Gold Crafter", gameType = GameType.OS)
public class GoldCrafter extends LoopScript {

    private static final Product DEFAULT_PRODUCT = Product.GOLD_NECKLACE;

    private static final Tile EDGEVILLE_BANK = new Tile(3094, 3492, 0);
    private static final Tile EDGEVILLE_FURNACE = new Tile(3109, 3499, 0);
    private static final Tile GRAND_EXCHANGE = new Tile(3164, 3487, 0);

    // Same rendezvous used by the older Gold Profit Crafter.
    private static final int MULE_WORLD = 698;
    private static final Tile MULE_RENDEZVOUS = new Tile(3302, 3555, 0);

    private static final int TARGET_GOLD_BARS = 1000;
    private static final int TARGET_GEMS = 500;
    private static final int SELL_AT = 100;

    // EpicBot pricing high/low values already represent the current instant-buy / instant-sell market.
    // Do not add another 3% penalty when comparing or placing offers; that was biasing gem recipes downward.
    private static final double BUY_MULTIPLIER = 1.00;
    private static final double SELL_MULTIPLIER = 1.00;
    private static final double GE_TAX_RATE = 0.02;
    private static final long GE_TIMEOUT_MS = 90_000L;

    private Product product = DEFAULT_PRODUCT;
    private boolean autoSelectProduct = true;

    private State state = State.CHECK;

    private int barsBeforeCraft;
    private long lastCraftProgress;
    private int interfaceFailures;

    private int pendingBarBuy;
    private int pendingGemBuy;
    private boolean pendingMouldBuy;

    private String waitingOfferItem;
    private long waitingOfferStarted;

    private boolean muleLiquidationMode;
    private String activeMuleCommandId;
    private String lastFinishedMuleCommandId;
    private boolean muleReady;
    private boolean muleAtRendezvous;
    private int muleDeadRound = -1;
    private int mulePickedRound = -1;
    private int muleBaselineRound = -1;
    private int muleBaselineCoins;
    private int muleSettingAttempts;
    private boolean finalMuleLogoutRequested;

    private final MuleCoordinator mule = new MuleCoordinator();

    private enum State {
        CHECK,
        WALK_BANK,
        OPEN_BANK,
        BANK_DEPOSIT,
        BANK_DECIDE,
        BANK_WITHDRAW,
        WALK_FURNACE,
        OPEN_FURNACE,
        SELECT_PRODUCT,
        CRAFTING,
        GE_PREPARE,
        WALK_GE,
        GE_OPEN,
        GE_NEXT,
        GE_WAIT,
        GE_FINISH,
        MULE_PREPARE,
        MULE_WAIT_CLIENTS,
        MULE_RENDEZVOUS,
        MULE_FIGHT,
        MULE_ELIMINATED
    }

    private enum Product {
        GOLD_RING("Gold ring", "Ring mould", null, 5),
        GOLD_NECKLACE("Gold necklace", "Necklace mould", null, 6),
        GOLD_AMULET("Gold amulet (u)", "Amulet mould", null, 8),

        SAPPHIRE_RING("Sapphire ring", "Ring mould", "Sapphire", 20),
        SAPPHIRE_NECKLACE("Sapphire necklace", "Necklace mould", "Sapphire", 22),
        SAPPHIRE_AMULET("Sapphire amulet (u)", "Amulet mould", "Sapphire", 24),

        EMERALD_RING("Emerald ring", "Ring mould", "Emerald", 27),
        EMERALD_NECKLACE("Emerald necklace", "Necklace mould", "Emerald", 29),
        EMERALD_AMULET("Emerald amulet (u)", "Amulet mould", "Emerald", 31),

        RUBY_RING("Ruby ring", "Ring mould", "Ruby", 34),
        RUBY_NECKLACE("Ruby necklace", "Necklace mould", "Ruby", 40),
        RUBY_AMULET("Ruby amulet (u)", "Amulet mould", "Ruby", 50),

        DIAMOND_RING("Diamond ring", "Ring mould", "Diamond", 43),
        DIAMOND_NECKLACE("Diamond necklace", "Necklace mould", "Diamond", 56),
        DIAMOND_AMULET("Diamond amulet (u)", "Amulet mould", "Diamond", 70);

        final String output;
        final String mould;
        final String gem;
        final int requiredLevel;

        Product(String output, String mould, String gem, int requiredLevel) {
            this.output = output;
            this.mould = mould;
            this.gem = gem;
            this.requiredLevel = requiredLevel;
        }

        boolean usesGem() {
            return gem != null;
        }

        int barsPerBatch() {
            return usesGem() ? 13 : 27;
        }

        int gemsPerBatch() {
            return usesGem() ? 13 : 0;
        }

        static Product fromArg(String value) {
            if (value == null) return null;
            String key = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (Product p : values()) {
                if (p.name().equals(key)) return p;
            }
            return null;
        }
    }

    @Override
    public boolean onStart(String... args) {
        if (args != null) {
            for (String arg : args) {
                Product selected = Product.fromArg(arg);
                if (selected != null) {
                    product = selected;
                    autoSelectProduct = false;
                }
                if ("mule-now".equalsIgnoreCase(arg)) {
                    mule.requestMuleNow(safeAccountName(getAPIContext()));
                }
            }
        }

        getLogger().info("Gold Crafter starting | product={} | auto-profit={}", product.output, autoSelectProduct);
        state = State.CHECK;
        return true;
    }

    @Override
    protected int loop() {
        APIContext ctx = getAPIContext();

        if (ctx.localPlayer().get() == null) return 1000;

        if (!ctx.walking().isRunEnabled() && ctx.walking().getRunEnergy() >= 30) {
            ctx.walking().setRun(true);
        }

        processMuleControl(ctx);

        return switch (state) {
            case CHECK -> check(ctx);
            case WALK_BANK -> walkBank(ctx);
            case OPEN_BANK -> openBank(ctx);
            case BANK_DEPOSIT -> bankDeposit(ctx);
            case BANK_DECIDE -> bankDecide(ctx);
            case BANK_WITHDRAW -> bankWithdraw(ctx);
            case WALK_FURNACE -> walkFurnace(ctx);
            case OPEN_FURNACE -> openFurnace(ctx);
            case SELECT_PRODUCT -> selectProduct(ctx);
            case CRAFTING -> monitorCrafting(ctx);
            case GE_PREPARE -> gePrepare(ctx);
            case WALK_GE -> walkGe(ctx);
            case GE_OPEN -> geOpen(ctx);
            case GE_NEXT -> geNext(ctx);
            case GE_WAIT -> geWait(ctx);
            case GE_FINISH -> geFinish(ctx);
            case MULE_PREPARE -> mulePrepare(ctx);
            case MULE_WAIT_CLIENTS -> muleWaitClients(ctx);
            case MULE_RENDEZVOUS -> muleRendezvous(ctx);
            case MULE_FIGHT -> muleFight(ctx);
            case MULE_ELIMINATED -> muleEliminated(ctx);
        };
    }

    private int check(APIContext ctx) {
        if (hasCraftBatch(ctx)) {
            setState(State.WALK_FURNACE, "valid batch already in inventory");
        } else {
            setState(State.WALK_BANK, "need bank");
        }
        return 250;
    }

    private int walkBank(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            setState(State.BANK_DEPOSIT, "bank already open");
            return 250;
        }

        if (EDGEVILLE_BANK.tileDistanceTo(ctx) <= 6) {
            setState(State.OPEN_BANK, "at Edgeville bank");
            return 200;
        }

        getLogger().info("Walking to Edgeville bank");
        ctx.webWalking().walkTo(EDGEVILLE_BANK);
        return 600;
    }

    private int openBank(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            setState(State.BANK_DEPOSIT, "bank opened");
            return 200;
        }

        ctx.bank().open();
        return 600;
    }

    private int bankDeposit(APIContext ctx) {
        if (!ctx.bank().isOpen()) {
            setState(State.OPEN_BANK, "bank closed");
            return 300;
        }

        if (!ctx.inventory().isEmpty()) {
            getLogger().info("Depositing inventory");
            ctx.bank().depositInventory();
            return 600;
        }

        setState(State.BANK_DECIDE, "inventory clear");
        return 200;
    }

    private int bankDecide(APIContext ctx) {
        if (!ctx.bank().isOpen()) {
            setState(State.OPEN_BANK, "bank closed");
            return 300;
        }

        if (autoSelectProduct) {
            Product best = selectBestProduct(ctx);
            if (best == null) {
                getLogger().warn("No positive-profit jewellery recipe is currently available for Crafting level {}",
                        ctx.skills().crafting().getRealLevel());
                return 15_000;
            }
            if (best != product) {
                getLogger().info("PROFIT: switching {} -> {}", product.output, best.output);
                product = best;
            }
        }

        int bars = ctx.bank().getCount("Gold bar");
        int gems = product.usesGem() ? ctx.bank().getCount(product.gem) : Integer.MAX_VALUE;
        boolean mouldMissing = !ctx.bank().contains(product.mould);

        boolean cannotCraftBatch = bars < product.barsPerBatch()
                || (product.usesGem() && gems < product.gemsPerBatch())
                || mouldMissing;

        int totalOutputs = countAllBankOutputs(ctx);
        boolean oldRecipeOutput = hasOtherRecipeOutput(ctx, product);
        boolean shouldSell = totalOutputs >= SELL_AT || oldRecipeOutput;

        if (cannotCraftBatch || shouldSell) {
            pendingMouldBuy = mouldMissing;
            pendingBarBuy = Math.max(0, TARGET_GOLD_BARS - bars);
            pendingGemBuy = product.usesGem() ? Math.max(0, TARGET_GEMS - gems) : 0;

            getLogger().info(
                    "GE trip: outputs={}, mouldMissing={}, barsMissing={}, gemsMissing={}",
                    totalOutputs, pendingMouldBuy, pendingBarBuy, pendingGemBuy
            );

            setState(State.GE_PREPARE, "GE required");
            return 250;
        }

        setState(State.BANK_WITHDRAW, "supplies ready");
        return 200;
    }

    private int bankWithdraw(APIContext ctx) {
        if (!ctx.bank().isOpen()) {
            setState(State.OPEN_BANK, "bank closed");
            return 300;
        }

        if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
            return 350;
        }

        if (!ctx.inventory().contains(product.mould)) {
            if (!ctx.bank().contains(product.mould)) {
                setState(State.BANK_DECIDE, "mould disappeared");
                return 250;
            }
            ctx.bank().withdraw(1, product.mould);
            return 450;
        }

        int bars = ctx.inventory().getCount("Gold bar");
        if (bars < product.barsPerBatch()) {
            int need = product.barsPerBatch() - bars;
            if (ctx.bank().getCount("Gold bar") < need) {
                setState(State.BANK_DECIDE, "bars below batch");
                return 250;
            }
            ctx.bank().withdraw(need, "Gold bar");
            return 450;
        }

        if (product.usesGem()) {
            int gems = ctx.inventory().getCount(product.gem);
            if (gems < product.gemsPerBatch()) {
                int need = product.gemsPerBatch() - gems;
                if (ctx.bank().getCount(product.gem) < need) {
                    setState(State.BANK_DECIDE, "gems below batch");
                    return 250;
                }
                ctx.bank().withdraw(need, product.gem);
                return 450;
            }
        }

        if (!hasCraftBatch(ctx)) return 400;

        ctx.bank().close();
        setState(State.WALK_FURNACE, "batch loaded");
        return 400;
    }

    private int walkFurnace(APIContext ctx) {
        if (!hasCraftBatch(ctx)) {
            setState(State.WALK_BANK, "batch invalid");
            return 250;
        }

        SceneObject furnace = findFurnace(ctx);
        if (furnace != null && furnace.tileDistanceTo(ctx) <= 5) {
            setState(State.OPEN_FURNACE, "furnace in range");
            return 200;
        }

        ctx.webWalking().walkTo(EDGEVILLE_FURNACE);
        return 550;
    }

    private int openFurnace(APIContext ctx) {
        if (!hasCraftBatch(ctx)) {
            setState(State.WALK_BANK, "batch invalid");
            return 250;
        }

        SceneObject furnace = findFurnace(ctx);
        if (furnace == null) {
            setState(State.WALK_FURNACE, "furnace not found");
            return 400;
        }

        if (furnace.interact("Smelt")) {
            interfaceFailures = 0;
            setState(State.SELECT_PRODUCT, "furnace opened");
            return 800;
        }

        return 500;
    }

    private int selectProduct(APIContext ctx) {
        // Never click a grey/unavailable recipe. A gem recipe must have its gem in inventory.
        if (!hasCraftBatch(ctx)) {
            getLogger().warn("CRAFT: selected {} but required inputs are no longer in inventory; returning to bank",
                    product.output);
            setState(State.WALK_BANK, "selected recipe inputs missing");
            return 300;
        }

        WidgetChild widget = findCraftWidget(ctx);
        if (widget == null) {
            interfaceFailures++;
            if (interfaceFailures >= 6) {
                interfaceFailures = 0;
                setState(State.OPEN_FURNACE, "exact recipe widget not found");
            }
            return 450;
        }

        barsBeforeCraft = ctx.inventory().getCount("Gold bar");

        if (interactExactProduct(widget)) {
            lastCraftProgress = System.currentTimeMillis();
            getLogger().info("CRAFT: exact recipe selected {} | bars={}{}",
                    product.output,
                    ctx.inventory().getCount("Gold bar"),
                    product.usesGem() ? " | " + product.gem + "=" + ctx.inventory().getCount(product.gem) : "");
            setState(State.CRAFTING, "exact recipe started");
            return 700;
        }

        setState(State.OPEN_FURNACE, "exact recipe interaction failed");
        return 500;
    }

    private int monitorCrafting(APIContext ctx) {
        int bars = ctx.inventory().getCount("Gold bar");

        if (bars <= 0 || (product.usesGem() && ctx.inventory().getCount(product.gem) <= 0)) {
            setState(State.WALK_BANK, "batch finished");
            return 300;
        }

        if (ctx.localPlayer().isAnimating()) return 550;

        if (bars < barsBeforeCraft) {
            barsBeforeCraft = bars;
            lastCraftProgress = System.currentTimeMillis();
            return 600;
        }

        if (System.currentTimeMillis() - lastCraftProgress > 4000L) {
            setState(State.OPEN_FURNACE, "crafting stopped early");
            return 300;
        }

        return 500;
    }

    private int gePrepare(APIContext ctx) {
        if (!ctx.bank().isOpen()) {
            if (muleLiquidationMode) {
                setState(State.MULE_PREPARE, "bank closed before mule GE prep");
            } else {
                setState(State.OPEN_BANK, "bank closed before GE prep");
            }
            return 300;
        }

        // Withdraw every supported finished product as notes, not just the current recipe.
        if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE)) {
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.NOTE);
            return 350;
        }

        for (Product p : Product.values()) {
            if (ctx.bank().getCount(p.output) > 0 && !ctx.inventory().contains(p.output)) {
                getLogger().info("Withdrawing {} for GE sale", p.output);
                ctx.bank().withdrawAll(p.output);
                return 450;
            }
        }

        // Sweep ALL GP from the bank before every selling/restock trip.
        if (ctx.bank().getCount("Coins") > 0) {
            getLogger().info("Withdrawing remaining {} GP from bank", ctx.bank().getCount("Coins"));
            ctx.bank().withdrawAll("Coins");
            return 450;
        }

        ctx.bank().close();
        setState(State.WALK_GE, "GE supplies prepared");
        return 400;
    }

    private int walkGe(APIContext ctx) {
        if (GRAND_EXCHANGE.tileDistanceTo(ctx) <= 9) {
            setState(State.GE_OPEN, "at GE");
            return 250;
        }

        ctx.webWalking().walkTo(GRAND_EXCHANGE);
        return 650;
    }

    private int geOpen(APIContext ctx) {
        if (ctx.grandExchange().isOpen()) {
            collectAny(ctx);
            setState(State.GE_NEXT, "GE open");
            return 300;
        }

        ctx.grandExchange().open();
        return 600;
    }

    private int geNext(APIContext ctx) {
        if (!ctx.grandExchange().isOpen()) {
            setState(State.GE_OPEN, "GE closed");
            return 300;
        }

        if (collectAny(ctx)) return 600;

        if (hasBusyOffer(ctx)) {
            setState(State.GE_WAIT, "existing offer active");
            return 500;
        }

        Product sellProduct = findInventoryOutput(ctx);
        if (sellProduct != null) {
            int qty = ctx.inventory().getCount(sellProduct.output);
            int price = quickSellPrice(ctx, sellProduct.output);
            if (price > 0 && ctx.grandExchange().placeSellOffer(sellProduct.output, qty, price)) {
                startWaiting(sellProduct.output);
                return 700;
            }
            return 500;
        }

        if (!muleLiquidationMode) {
            if (pendingMouldBuy) {
                int price = quickBuyPrice(ctx, product.mould);
                if (price > 0 && ctx.grandExchange().placeBuyOffer(product.mould, 1, price)) {
                    pendingMouldBuy = false;
                    startWaiting(product.mould);
                    return 700;
                }
                return 500;
            }

            if (pendingBarBuy > 0) {
                int qty = pendingBarBuy;
                int price = quickBuyPrice(ctx, "Gold bar");
                if (price > 0 && ctx.grandExchange().placeBuyOffer("Gold bar", qty, price)) {
                    pendingBarBuy = 0;
                    startWaiting("Gold bar");
                    return 700;
                }
                return 500;
            }

            if (product.usesGem() && pendingGemBuy > 0) {
                int qty = pendingGemBuy;
                int price = quickBuyPrice(ctx, product.gem);
                if (price > 0 && ctx.grandExchange().placeBuyOffer(product.gem, qty, price)) {
                    pendingGemBuy = 0;
                    startWaiting(product.gem);
                    return 700;
                }
                return 500;
            }
        }

        setState(State.GE_FINISH, "GE queue complete");
        return 300;
    }

    private int geWait(APIContext ctx) {
        if (!ctx.grandExchange().isOpen()) {
            setState(State.GE_OPEN, "GE closed while waiting");
            return 300;
        }

        if (collectAny(ctx)) {
            waitingOfferItem = null;
            setState(State.GE_NEXT, "offer collected");
            return 650;
        }

        GrandExchangeSlot slot = findOfferSlot(ctx, waitingOfferItem);
        if (slot == null) {
            waitingOfferItem = null;
            setState(State.GE_NEXT, "offer slot cleared");
            return 400;
        }

        if (System.currentTimeMillis() - waitingOfferStarted > GE_TIMEOUT_MS) {
            getLogger().warn("GE timeout on {}, aborting", waitingOfferItem);
            slot.abortOffer();
            waitingOfferStarted = System.currentTimeMillis();
            return 700;
        }

        return 1000;
    }

    private int geFinish(APIContext ctx) {
        if (collectAny(ctx)) return 600;
        if (hasBusyOffer(ctx)) {
            setState(State.GE_WAIT, "offer still active");
            return 500;
        }

        ctx.grandExchange().close();

        if (muleLiquidationMode) {
            setState(State.MULE_PREPARE, "mule liquidation GE complete");
        } else {
            setState(State.WALK_BANK, "GE complete");
        }
        return 500;
    }

    private boolean collectAny(APIContext ctx) {
        for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
            if (slot != null && slot.canCollect()) {
                return ctx.grandExchange().collectToBank();
            }
        }
        return false;
    }

    private boolean hasBusyOffer(APIContext ctx) {
        for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
            if (slot == null || !slot.inUse()) continue;
            if (slot.canCollect() || slot.isCompleted()) continue;
            GrandExchangeOffer offer = slot.getOffer();
            if (offer != null && offer.isValid() && offer.getRemaining() > 0) return true;
        }
        return false;
    }

    private GrandExchangeSlot findOfferSlot(APIContext ctx, String itemName) {
        if (itemName == null) return null;
        for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
            if (slot == null || !slot.inUse()) continue;
            GrandExchangeOffer offer = slot.getOffer();
            if (offer != null && offer.getItemName() != null
                    && offer.getItemName().equalsIgnoreCase(itemName)) {
                return slot;
            }
        }
        return null;
    }

    private void startWaiting(String itemName) {
        waitingOfferItem = itemName;
        waitingOfferStarted = System.currentTimeMillis();
        setState(State.GE_WAIT, "waiting for " + itemName);
    }

    private Product selectBestProduct(APIContext ctx) {
        int craftingLevel = ctx.skills().crafting().getRealLevel();
        int barBuy = quickBuyPrice(ctx, "Gold bar");
        if (barBuy <= 0) {
            getLogger().warn("PROFIT: no Gold bar buy price; cannot compare recipes");
            return null;
        }

        Product best = null;
        int bestItemProfit = Integer.MIN_VALUE;
        long bestBatchProfit = Long.MIN_VALUE;

        for (Product candidate : Product.values()) {
            // F2P-only enum: rings, necklaces and amulets. Bracelets are intentionally absent.
            if (craftingLevel < candidate.requiredLevel) continue;

            int sell = quickSellPrice(ctx, candidate.output);
            if (sell <= 0) {
                getLogger().info("PROFIT: SKIP {} | no sell price", candidate.output);
                continue;
            }

            int gemBuy = 0;
            if (candidate.usesGem()) {
                gemBuy = quickBuyPrice(ctx, candidate.gem);
                if (gemBuy <= 0) {
                    getLogger().info("PROFIT: SKIP {} | no {} buy price", candidate.output, candidate.gem);
                    continue;
                }
            }

            int input = barBuy + gemBuy;
            int tax = geTaxPerItem(sell);
            int netSell = sell - tax;
            int itemProfit = netSell - input;
            long batchProfit = (long) itemProfit * candidate.barsPerBatch();

            getLogger().info(
                    "PROFIT: {} | req={} | sell={} | tax={} | bar={} | gem={} | net/item={} | batch={}",
                    candidate.output, candidate.requiredLevel, sell, tax, barBuy, gemBuy, itemProfit, batchProfit
            );

            // Match the older Gold Profit Crafter behaviour: choose the highest eligible NET GP PER ITEM.
            // Batch profit was wrong here because plain-gold batches hold 27 items while gem batches hold 13,
            // which unfairly made plain gold win even when sapphire/emerald made much more per crafted item.
            if (itemProfit > 0
                    && (itemProfit > bestItemProfit
                    || (itemProfit == bestItemProfit && batchProfit > bestBatchProfit))) {
                best = candidate;
                bestItemProfit = itemProfit;
                bestBatchProfit = batchProfit;
            }
        }

        if (best != null) {
            getLogger().info("PROFIT SELECTED: Crafting {} -> {} | {} gp/item | est batch {} gp",
                    craftingLevel, best.output, bestItemProfit, bestBatchProfit);
        } else {
            getLogger().warn("PROFIT: no positive-profit F2P recipe available at Crafting {}", craftingLevel);
        }

        return best;
    }

    private int geTaxPerItem(int salePrice) {
        if (salePrice < 50) return 0;
        return (int) Math.floor(salePrice * GE_TAX_RATE);
    }

    private int quickBuyPrice(APIContext ctx, String item) {
        ItemDetail detail = ctx.pricing().get(item);
        if (detail == null) return -1;

        int market = detail.getHighestPrice() > 0
                ? detail.getHighestPrice()
                : detail.getLowestPrice();

        if (market <= 0) return -1;
        return Math.max(1, (int) Math.ceil(market * BUY_MULTIPLIER));
    }

    private int quickSellPrice(APIContext ctx, String item) {
        ItemDetail detail = ctx.pricing().get(item);
        if (detail == null) return -1;

        int market = detail.getLowestPrice() > 0
                ? detail.getLowestPrice()
                : detail.getHighestPrice();

        if (market <= 0) return -1;
        return Math.max(1, (int) Math.floor(market * SELL_MULTIPLIER));
    }

    private int countAllBankOutputs(APIContext ctx) {
        int total = 0;
        for (Product p : Product.values()) {
            total += ctx.bank().getCount(p.output);
        }
        return total;
    }

    private boolean hasOtherRecipeOutput(APIContext ctx, Product selected) {
        for (Product p : Product.values()) {
            if (p != selected && ctx.bank().getCount(p.output) > 0) return true;
        }
        return false;
    }

    private Product findInventoryOutput(APIContext ctx) {
        for (Product p : Product.values()) {
            if (ctx.inventory().getCount(p.output) > 0) return p;
        }
        return null;
    }

    private SceneObject findFurnace(APIContext ctx) {
        List<SceneObject> furnaces = ctx.objects().getAll(
                15,
                o -> o != null
                        && o.isValid()
                        && "Furnace".equalsIgnoreCase(o.getName())
                        && o.hasAction("Smelt")
        );

        if (furnaces == null || furnaces.isEmpty()) return null;

        return furnaces.stream()
                .min(Comparator.comparingInt(o -> o.tileDistanceTo(ctx)))
                .orElse(null);
    }

    private WidgetChild findCraftWidget(APIContext ctx) {
        String expected = normalize(product.output);

        List<WidgetChild> widgets = ctx.widgets().getAllChildren(w ->
                w != null && w.isValid() && w.isVisible()
        );

        if (widgets == null || widgets.isEmpty()) return null;

        // Primary match: the actual jewellery interface exposes actions such as
        // "Make <col=ff9040>Sapphire necklace</col>". Match that exact recipe name.
        for (WidgetChild widget : widgets) {
            List<String> actions = widget.getActions();
            if (actions == null) continue;

            for (String action : actions) {
                String normalizedAction = normalize(action);
                if (normalizedAction.contains("make") && normalizedAction.contains(expected)) {
                    getLogger().debug("CRAFT widget action matched exact recipe: {}", normalizedAction);
                    return widget;
                }
            }
        }

        // Fallback only when the widget itself explicitly names the selected product.
        for (WidgetChild widget : widgets) {
            if (normalize(widget.getName()).contains(expected)
                    || normalize(widget.getText()).contains(expected)
                    || normalize(widget.getRawText()).contains(expected)) {
                return widget;
            }
        }

        return null;
    }

    private boolean interactExactProduct(WidgetChild widget) {
        String expected = normalize(product.output);
        List<String> actions = widget.getActions();

        if (actions != null) {
            for (String action : actions) {
                if (action == null) continue;
                String normalizedAction = normalize(action);

                // Never use a generic Make action from a neighbouring grey necklace slot.
                if (normalizedAction.contains("make") && normalizedAction.contains(expected)) {
                    getLogger().info("CRAFT: interacting exact action '{}'", normalizedAction);
                    return widget.interact(action);
                }
            }
        }

        // Safe fallback: click only if the widget itself explicitly identifies our exact product.
        if (normalize(widget.getName()).contains(expected)
                || normalize(widget.getText()).contains(expected)
                || normalize(widget.getRawText()).contains(expected)) {
            return widget.click();
        }

        getLogger().warn("CRAFT: refused generic/ambiguous Make action for {}", product.output);
        return false;
    }

    private boolean hasCraftBatch(APIContext ctx) {
        if (!ctx.inventory().contains(product.mould)) return false;
        if (ctx.inventory().getCount("Gold bar") <= 0) return false;
        return !product.usesGem() || ctx.inventory().getCount(product.gem) > 0;
    }

    // -------------------------------------------------------------------------
    // Wilderness worker-to-worker mule relay
    // -------------------------------------------------------------------------

    private void processMuleControl(APIContext ctx) {
        String account = safeAccountName(ctx);
        MuleCommand cmd = mule.readCommand();

        if (activeMuleCommandId != null) {
            mule.writeParticipant(new MuleParticipant(
                    account,
                    activeMuleCommandId,
                    ctx.localPlayer().getCombatLevel(),
                    ctx.inventory().getCount("Coins"),
                    muleReady,
                    muleAtRendezvous,
                    muleDeadRound,
                    mulePickedRound,
                    System.currentTimeMillis()
            ));
            mule.maybeAdvance(account);
        }

        if (cmd == null || cmd.id == null || cmd.id.isEmpty()) return;

        if ("DONE".equals(cmd.phase)) {
            if (cmd.id.equals(activeMuleCommandId)) {
                lastFinishedMuleCommandId = cmd.id;

                if (account.equalsIgnoreCase(cmd.finalSurvivor)) {
                    int coins = ctx.inventory().getCount("Coins");
                    if (!ctx.localPlayer().isInWilderness()) {
                        getLogger().warn("MULE: final survivor is no longer in Wilderness; refusing to walk anywhere before logout");
                        ctx.script().stop("Mule relay complete; final survivor left Wilderness unexpectedly");
                        return;
                    }

                    if (coins <= 0) {
                        getLogger().warn("MULE: final survivor has no verified GP stack; not claiming mule completion");
                        return;
                    }

                    if (!finalMuleLogoutRequested) {
                        getLogger().info("MULE: final survivor {} has {} GP - logging out in Wilderness", account, coins);
                        finalMuleLogoutRequested = ctx.game().logout();
                    }

                    if (finalMuleLogoutRequested) {
                        ctx.script().stop("Mule relay complete; final survivor logout requested in Wilderness");
                    }
                } else {
                    getLogger().info("MULE: relay complete | final survivor={} | this worker={}", cmd.finalSurvivor, account);
                    ctx.script().stop("Mule relay complete; this worker is not the final survivor");
                }
            }
            return;
        }

        if ("ABORT".equals(cmd.phase)) {
            if (cmd.id.equals(activeMuleCommandId)) {
                getLogger().warn("MULE ABORTED: {}", cmd.reason);
                resetMuleState(cmd.id);
                setState(State.WALK_BANK, "mule aborted");
            }
            return;
        }

        if (!cmd.id.equals(lastFinishedMuleCommandId)
                && activeMuleCommandId == null
                && !isMuleState(state)) {
            activeMuleCommandId = cmd.id;
            muleReady = false;
            muleAtRendezvous = false;
            muleDeadRound = -1;
            mulePickedRound = -1;
            muleBaselineRound = -1;
            muleSettingAttempts = 0;
            finalMuleLogoutRequested = false;
            muleLiquidationMode = true;
            getLogger().info("MULE: command {} received - liquidating finished jewellery", cmd.id);
            setState(State.MULE_PREPARE, "mule command received");
        }
    }

    private int mulePrepare(APIContext ctx) {
        if (GRAND_EXCHANGE.tileDistanceTo(ctx) > 10 && !ctx.bank().isOpen()) {
            ctx.webWalking().walkTo(GRAND_EXCHANGE);
            return 650;
        }

        if (!ctx.bank().isOpen()) {
            ctx.bank().open();
            return 600;
        }

        if (!ctx.inventory().isEmpty()) {
            ctx.bank().depositInventory();
            return 500;
        }

        if (countAllBankOutputs(ctx) > 0) {
            pendingMouldBuy = false;
            pendingBarBuy = 0;
            pendingGemBuy = 0;
            muleLiquidationMode = true;
            setState(State.GE_PREPARE, "mule: sell finished jewellery");
            return 300;
        }

        if (ctx.bank().getCount("Coins") > 0) {
            if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
                ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
                return 300;
            }
            getLogger().info("MULE: withdrawing ALL {} GP from bank", ctx.bank().getCount("Coins"));
            ctx.bank().withdrawAll("Coins");
            return 500;
        }

        int coins = ctx.inventory().getCount("Coins");
        if (coins <= 0) {
            getLogger().warn("MULE: no GP available after liquidation; waiting");
            return 2000;
        }

        ctx.bank().close();
        muleReady = true;
        getLogger().info("MULE: GP ready={} - waiting for workers", coins);
        setState(State.MULE_WAIT_CLIENTS, "GP ready");
        return 500;
    }

    private int muleWaitClients(APIContext ctx) {
        MuleCommand cmd = mule.readCommand();
        if (cmd == null || !cmd.id.equals(activeMuleCommandId)) return 1000;

        mule.maybeAdvance(safeAccountName(ctx));
        cmd = mule.readCommand();

        if ("RENDEZVOUS".equals(cmd.phase)) {
            setState(State.MULE_RENDEZVOUS, "relay roster locked");
            return 300;
        }

        if ("ABORT".equals(cmd.phase)) {
            return 500;
        }

        return 1000;
    }

    private int muleRendezvous(APIContext ctx) {
        MuleCommand cmd = mule.readCommand();
        if (cmd == null || !cmd.id.equals(activeMuleCommandId)) return 1000;

        if (ctx.world().getCurrent() != MULE_WORLD) {
            getLogger().info("MULE: hopping {} -> {}", ctx.world().getCurrent(), MULE_WORLD);
            ctx.world().hop(MULE_WORLD);
            return 1500;
        }

        if (MULE_RENDEZVOUS.tileDistanceTo(ctx) > 3) {
            getLogger().info("MULE: walking behind Varrock sawmill to Wilderness level 5");
            ctx.webWalking().walkTo(MULE_RENDEZVOUS);
            return 700;
        }

        if (!preparePvpSettings(ctx)) {
            return 600;
        }

        muleAtRendezvous = true;
        mule.maybeAdvance(safeAccountName(ctx));
        cmd = mule.readCommand();

        if ("FIGHT".equals(cmd.phase)) {
            getLogger().info("MULE: START_FIGHT accepted | round={} | roster={}", cmd.round, cmd.roster);
            setState(State.MULE_FIGHT, "rendezvous complete");
            return 300;
        }

        return 800;
    }

    private int muleFight(APIContext ctx) {
        MuleCommand cmd = mule.readCommand();
        if (cmd == null || !cmd.id.equals(activeMuleCommandId)) return 1000;

        if ("DONE".equals(cmd.phase) || "ABORT".equals(cmd.phase)) return 300;

        if (!"FIGHT".equals(cmd.phase)) {
            return 700;
        }

        List<String> roster = cmd.rosterList();
        int round = cmd.round;
        if (round < 0 || round >= roster.size() - 1) {
            mule.maybeAdvance(safeAccountName(ctx));
            return 500;
        }

        String account = safeAccountName(ctx);
        String loserName = roster.get(round);
        String collectorName = roster.get(round + 1);

        int myIndex = indexOfIgnoreCase(roster, account);
        if (myIndex >= 0 && myIndex < round) {
            setState(State.MULE_ELIMINATED, "relay turn complete");
            return 300;
        }

        if (account.equalsIgnoreCase(loserName)) {
            return muleLoser(ctx, round, collectorName);
        }

        if (account.equalsIgnoreCase(collectorName)) {
            return muleCollector(ctx, round, loserName);
        }

        return 900;
    }

    private int muleLoser(APIContext ctx, int round, String collectorName) {
        if (!ctx.localPlayer().isInWilderness() || ctx.inventory().getCount("Coins") <= 0) {
            muleDeadRound = Math.max(muleDeadRound, round);
            getLogger().info("MULE: loser death verified | round={}", round);
            mule.maybeAdvance(safeAccountName(ctx));
            setState(State.MULE_ELIMINATED, "died and dropped GP");
            return 500;
        }

        Player collector = findPlayer(ctx, collectorName);
        if (collector == null) {
            getLogger().info("MULE: waiting for collector {}", collectorName);
            return 700;
        }

        if (!collector.hasAction("Attack")) {
            if (!preparePvpSettings(ctx)) return 600;
        }

        if (!ctx.localPlayer().isInCombat()) {
            getLogger().info("MULE: loser attacking {} | round={}", collectorName, round);
            collector.interact("Attack");
            return 700;
        }

        return 650;
    }

    private int muleCollector(APIContext ctx, int round, String loserName) {
        int coins = ctx.inventory().getCount("Coins");

        if (muleBaselineRound != round) {
            muleBaselineRound = round;
            muleBaselineCoins = coins;
            getLogger().info("MULE: collector baseline={} GP | round={}", muleBaselineCoins, round);
        }

        if (coins > muleBaselineCoins) {
            mulePickedRound = Math.max(mulePickedRound, round);
            getLogger().info("MULE: COINS_VERIFIED | round={} | before={} | after={}",
                    round, muleBaselineCoins, coins);
            mule.maybeAdvance(safeAccountName(ctx));
            return 500;
        }

        GroundItem droppedCoins = findGroundCoins(ctx);
        if (droppedCoins != null) {
            getLogger().info("MULE: taking dropped Coins | round={}", round);
            droppedCoins.interact("Take");
            return 650;
        }

        Player loser = findPlayer(ctx, loserName);
        if (loser != null && !ctx.localPlayer().isInCombat()) {
            if (!loser.hasAction("Attack")) {
                if (!preparePvpSettings(ctx)) return 600;
            }
            // The loser initiates first; this explicit retaliation avoids relying on auto-retaliate.
            loser.interact("Attack");
            return 700;
        }

        return 650;
    }

    private int muleEliminated(APIContext ctx) {
        MuleCommand cmd = mule.readCommand();
        if (cmd == null || !cmd.id.equals(activeMuleCommandId)) return 1000;

        mule.maybeAdvance(safeAccountName(ctx));

        if ("DONE".equals(cmd.phase)) {
            String account = safeAccountName(ctx);
            if (account.equalsIgnoreCase(cmd.finalSurvivor)
                    && ctx.localPlayer().isInWilderness()
                    && ctx.inventory().getCount("Coins") > 0) {
                getLogger().info("MULE: final survivor {} logging out in Wilderness with {} GP",
                        account, ctx.inventory().getCount("Coins"));
                ctx.game().logout();
            }
            ctx.script().stop("Mule relay complete");
            return -1;
        }

        if ("ABORT".equals(cmd.phase)) {
            resetMuleState(cmd.id);
            setState(State.WALK_BANK, "mule aborted");
            return 500;
        }

        return 1000;
    }

    private boolean preparePvpSettings(APIContext ctx) {
        Player anyTarget = findAnyRelayTarget(ctx);
        if (anyTarget != null && anyTarget.hasAction("Attack")) return true;

        muleSettingAttempts++;
        if (muleSettingAttempts > 12) {
            getLogger().warn("MULE: Attack option still unavailable. PK Skull Prevention / Player Attack Options could not be verified automatically.");
            return false;
        }

        if (!ctx.tabs().isOpen(ITabsAPI.Tabs.SETTINGS)) {
            ctx.tabs().open(ITabsAPI.Tabs.SETTINGS);
            return false;
        }

        List<WidgetChild> widgets = ctx.widgets().getAllChildren(w -> {
            if (w == null || !w.isValid() || !w.isVisible()) return false;
            String all = normalize(w.getName()) + " " + normalize(w.getText()) + " " + normalize(w.getRawText());
            return all.contains("pk skull prevention") || all.contains("player attack options");
        });

        if (widgets != null) {
            for (WidgetChild w : widgets) {
                if (clickToggleNear(w)) {
                    getLogger().info("MULE: adjusted PvP setting near '{}'", cleanWidgetText(w));
                    return false;
                }
            }
        }

        // Some layouts hide the controls under All Settings.
        List<WidgetChild> allSettings = ctx.widgets().getAllChildren(w ->
                w != null && w.isValid() && w.isVisible()
                        && (normalize(w.getText()).contains("all settings")
                        || normalize(w.getName()).contains("all settings")));

        if (allSettings != null && !allSettings.isEmpty()) {
            allSettings.get(0).click();
            return false;
        }

        return false;
    }

    private boolean clickToggleNear(WidgetChild label) {
        if (label.hasActionMatch("(?i).*(toggle|select|change).*")) {
            return label.click();
        }

        WidgetChild parent = label.getParent();
        if (parent != null && parent.getChildren() != null) {
            for (WidgetChild child : parent.getChildren()) {
                if (child == null || !child.isValid() || !child.isVisible()) continue;
                if (child.hasActionMatch("(?i).*(toggle|select|change).*")) {
                    return child.click();
                }
            }
        }

        return label.click();
    }

    private Player findPlayer(APIContext ctx, String name) {
        if (name == null || name.isEmpty()) return null;
        List<Player> players = ctx.players().getAll(p ->
                p != null && p.isValid() && p.getName() != null && p.getName().equalsIgnoreCase(name));
        if (players == null || players.isEmpty()) return null;
        return players.get(0);
    }

    private Player findAnyRelayTarget(APIContext ctx) {
        MuleCommand cmd = mule.readCommand();
        if (cmd == null) return null;
        String me = safeAccountName(ctx);
        for (String name : cmd.rosterList()) {
            if (!name.equalsIgnoreCase(me)) {
                Player p = findPlayer(ctx, name);
                if (p != null) return p;
            }
        }
        return null;
    }

    private GroundItem findGroundCoins(APIContext ctx) {
        List<GroundItem> coins = ctx.groundItems().getAll(8,
                item -> item != null && item.isValid()
                        && item.getName() != null
                        && item.getName().equalsIgnoreCase("Coins")
                        && item.hasGroundAction("Take"));
        if (coins == null || coins.isEmpty()) return null;
        return coins.stream()
                .min(Comparator.comparingInt(i -> i.tileDistanceTo(ctx)))
                .orElse(null);
    }

    private boolean isMuleState(State s) {
        return s == State.MULE_PREPARE
                || s == State.MULE_WAIT_CLIENTS
                || s == State.MULE_RENDEZVOUS
                || s == State.MULE_FIGHT
                || s == State.MULE_ELIMINATED;
    }

    private void resetMuleState(String commandId) {
        lastFinishedMuleCommandId = commandId;
        activeMuleCommandId = null;
        muleReady = false;
        muleAtRendezvous = false;
        muleDeadRound = -1;
        mulePickedRound = -1;
        muleBaselineRound = -1;
        muleLiquidationMode = false;
        muleSettingAttempts = 0;
        finalMuleLogoutRequested = false;
    }

    private String safeAccountName(APIContext ctx) {
        String name = ctx.localPlayer().getName();
        if (name == null || name.trim().isEmpty()) name = ctx.client().getUsername();
        return name == null ? "unknown" : name.trim();
    }

    private int indexOfIgnoreCase(List<String> list, String value) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(value)) return i;
        }
        return -1;
    }

    private String cleanWidgetText(WidgetChild w) {
        String text = w.getText();
        if (text == null || text.isEmpty()) text = w.getName();
        return text == null ? "" : text.replaceAll("<[^>]*>", "").trim();
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
    }

    private void setState(State next, String reason) {
        if (state != next) {
            getLogger().info("STATE {} -> {} | {}", state, next, reason);
            state = next;
        }
    }

    @Override
    protected void onStop() {
        getLogger().info("Gold Crafter stopped");
    }

    // -------------------------------------------------------------------------
    // Shared-file mule coordinator. Every EpicBot worker can run this same JAR.
    // -------------------------------------------------------------------------

    private static final class MuleCoordinator {
        private static final long STALE_MS = 15_000L;
        private static final long SETTLE_MS = 8_000L;

        private final File dir;
        private final File participantsDir;
        private final File commandFile;
        private final File commandLock;

        MuleCoordinator() {
            this.dir = new File(new File(new File(System.getProperty("user.home"), ".epicbot"),
                    "gold-crafter"), "mule");
            this.participantsDir = new File(dir, "participants");
            this.commandFile = new File(dir, "command.properties");
            this.commandLock = new File(dir, "command.lock");
            participantsDir.mkdirs();
        }

        void requestMuleNow(String initiator) {
            MuleCommand cmd = new MuleCommand();
            cmd.id = UUID.randomUUID().toString();
            cmd.phase = "PREPARE";
            cmd.initiator = initiator == null ? "" : initiator;
            cmd.createdAt = System.currentTimeMillis();
            cmd.updatedAt = cmd.createdAt;
            writeCommand(cmd);
        }

        MuleCommand readCommand() {
            if (!commandFile.isFile()) return null;
            Properties p = load(commandFile);
            if (p == null) return null;
            return MuleCommand.from(p);
        }

        void writeParticipant(MuleParticipant participant) {
            File file = new File(participantsDir, safeFileName(participant.name) + ".properties");
            Properties p = new Properties();
            p.setProperty("name", participant.name);
            p.setProperty("commandId", participant.commandId == null ? "" : participant.commandId);
            p.setProperty("combat", String.valueOf(participant.combat));
            p.setProperty("coins", String.valueOf(participant.coins));
            p.setProperty("ready", String.valueOf(participant.ready));
            p.setProperty("atRendezvous", String.valueOf(participant.atRendezvous));
            p.setProperty("deadRound", String.valueOf(participant.deadRound));
            p.setProperty("pickedRound", String.valueOf(participant.pickedRound));
            p.setProperty("updatedAt", String.valueOf(participant.updatedAt));
            atomicStore(file, p);
        }

        void maybeAdvance(String localName) {
            if (!commandFile.isFile()) return;

            try {
                dir.mkdirs();
                commandLock.getParentFile().mkdirs();
                try (RandomAccessFile raf = new RandomAccessFile(commandLock, "rw");
                     FileChannel channel = raf.getChannel();
                     FileLock ignored = channel.lock()) {

                    MuleCommand cmd = readCommand();
                    if (cmd == null || "DONE".equals(cmd.phase) || "ABORT".equals(cmd.phase)) return;

                    List<MuleParticipant> participants = readParticipants(cmd.id);
                    if (participants.isEmpty()) return;

                    List<String> order = cmd.rosterList();
                    String coordinator;
                    if (!order.isEmpty()) {
                        coordinator = order.get(order.size() - 1);
                    } else {
                        participants.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
                        coordinator = participants.get(participants.size() - 1).name;
                    }

                    if (!coordinator.equalsIgnoreCase(localName)) return;

                    if ("PREPARE".equals(cmd.phase)) {
                        if (System.currentTimeMillis() - cmd.createdAt < SETTLE_MS) return;
                        if (participants.size() < 2) return;
                        for (MuleParticipant p : participants) {
                            if (!p.ready || p.coins <= 0) return;
                        }

                        order = buildOrder(participants, cmd.initiator);
                        String problem = validateCombatOrder(order, participants);
                        if (problem != null) {
                            cmd.phase = "ABORT";
                            cmd.reason = problem;
                            cmd.updatedAt = System.currentTimeMillis();
                            writeCommand(cmd);
                            return;
                        }

                        cmd.roster = String.join(",", order);
                        cmd.finalSurvivor = order.get(order.size() - 1);
                        cmd.phase = "RENDEZVOUS";
                        cmd.updatedAt = System.currentTimeMillis();
                        writeCommand(cmd);
                        return;
                    }

                    if ("RENDEZVOUS".equals(cmd.phase)) {
                        if (order.size() < 2) return;
                        for (String name : order) {
                            MuleParticipant p = find(participants, name);
                            if (p == null || !p.atRendezvous) return;
                        }
                        cmd.phase = "FIGHT";
                        cmd.round = 0;
                        cmd.updatedAt = System.currentTimeMillis();
                        writeCommand(cmd);
                        return;
                    }

                    if ("FIGHT".equals(cmd.phase)) {
                        if (order.size() < 2) return;
                        if (cmd.round >= order.size() - 1) {
                            cmd.phase = "DONE";
                            cmd.updatedAt = System.currentTimeMillis();
                            writeCommand(cmd);
                            return;
                        }

                        String collectorName = order.get(cmd.round + 1);
                        MuleParticipant collector = find(participants, collectorName);
                        if (collector != null && collector.pickedRound >= cmd.round) {
                            cmd.round++;
                            if (cmd.round >= order.size() - 1) {
                                cmd.phase = "DONE";
                            }
                            cmd.updatedAt = System.currentTimeMillis();
                            writeCommand(cmd);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }

        private List<MuleParticipant> readParticipants(String commandId) {
            List<MuleParticipant> out = new ArrayList<>();
            File[] files = participantsDir.listFiles((d, name) -> name.endsWith(".properties"));
            if (files == null) return out;

            long now = System.currentTimeMillis();
            for (File file : files) {
                Properties p = load(file);
                if (p == null) continue;
                MuleParticipant participant = MuleParticipant.from(p);
                if (!commandId.equals(participant.commandId)) continue;
                if (now - participant.updatedAt > STALE_MS) continue;
                out.add(participant);
            }
            return out;
        }

        private List<String> buildOrder(List<MuleParticipant> participants, String initiator) {
            List<String> names = new ArrayList<>();
            for (MuleParticipant p : participants) names.add(p.name);
            names.sort(String.CASE_INSENSITIVE_ORDER);

            if (initiator != null && !initiator.isEmpty()) {
                String actual = null;
                for (String name : names) {
                    if (name.equalsIgnoreCase(initiator)) {
                        actual = name;
                        break;
                    }
                }
                if (actual != null) {
                    names.remove(actual);
                    names.add(actual);
                }
            }
            return names;
        }

        private String validateCombatOrder(List<String> order, List<MuleParticipant> participants) {
            for (int i = 0; i < order.size() - 1; i++) {
                MuleParticipant a = find(participants, order.get(i));
                MuleParticipant b = find(participants, order.get(i + 1));
                if (a == null || b == null) return "participant disappeared before relay";
                if (Math.abs(a.combat - b.combat) > 5) {
                    return "combat levels incompatible with Wilderness level 5: "
                            + a.name + "(" + a.combat + ") vs "
                            + b.name + "(" + b.combat + ")";
                }
            }
            return null;
        }

        private MuleParticipant find(List<MuleParticipant> participants, String name) {
            for (MuleParticipant p : participants) {
                if (p.name.equalsIgnoreCase(name)) return p;
            }
            return null;
        }

        private void writeCommand(MuleCommand cmd) {
            Properties p = new Properties();
            p.setProperty("id", nullToEmpty(cmd.id));
            p.setProperty("phase", nullToEmpty(cmd.phase));
            p.setProperty("initiator", nullToEmpty(cmd.initiator));
            p.setProperty("roster", nullToEmpty(cmd.roster));
            p.setProperty("round", String.valueOf(cmd.round));
            p.setProperty("reason", nullToEmpty(cmd.reason));
            p.setProperty("finalSurvivor", nullToEmpty(cmd.finalSurvivor));
            p.setProperty("createdAt", String.valueOf(cmd.createdAt));
            p.setProperty("updatedAt", String.valueOf(System.currentTimeMillis()));
            atomicStore(commandFile, p);
        }

        private Properties load(File file) {
            try (FileInputStream in = new FileInputStream(file)) {
                Properties p = new Properties();
                p.load(in);
                return p;
            } catch (IOException e) {
                return null;
            }
        }

        private void atomicStore(File file, Properties p) {
            try {
                file.getParentFile().mkdirs();
                File tmp = new File(file.getParentFile(), file.getName() + ".tmp-" + UUID.randomUUID());
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    p.store(out, "EpicBot Gold Crafter mule state");
                }
                try {
                    Files.move(tmp.toPath(), file.toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ignored) {
            }
        }

        private static String safeFileName(String name) {
            if (name == null || name.isEmpty()) return "unknown";
            return name.replaceAll("[^A-Za-z0-9._-]", "_");
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }

    private static final class MuleParticipant {
        final String name;
        final String commandId;
        final int combat;
        final int coins;
        final boolean ready;
        final boolean atRendezvous;
        final int deadRound;
        final int pickedRound;
        final long updatedAt;

        MuleParticipant(String name, String commandId, int combat, int coins,
                        boolean ready, boolean atRendezvous, int deadRound,
                        int pickedRound, long updatedAt) {
            this.name = name;
            this.commandId = commandId;
            this.combat = combat;
            this.coins = coins;
            this.ready = ready;
            this.atRendezvous = atRendezvous;
            this.deadRound = deadRound;
            this.pickedRound = pickedRound;
            this.updatedAt = updatedAt;
        }

        static MuleParticipant from(Properties p) {
            return new MuleParticipant(
                    p.getProperty("name", "unknown"),
                    p.getProperty("commandId", ""),
                    parseInt(p.getProperty("combat"), 0),
                    parseInt(p.getProperty("coins"), 0),
                    Boolean.parseBoolean(p.getProperty("ready", "false")),
                    Boolean.parseBoolean(p.getProperty("atRendezvous", "false")),
                    parseInt(p.getProperty("deadRound"), -1),
                    parseInt(p.getProperty("pickedRound"), -1),
                    parseLong(p.getProperty("updatedAt"), 0L)
            );
        }
    }

    private static final class MuleCommand {
        String id = "";
        String phase = "";
        String initiator = "";
        String roster = "";
        int round;
        String reason = "";
        String finalSurvivor = "";
        long createdAt;
        long updatedAt;

        List<String> rosterList() {
            List<String> out = new ArrayList<>();
            if (roster == null || roster.trim().isEmpty()) return out;
            for (String s : roster.split(",")) {
                String value = s.trim();
                if (!value.isEmpty()) out.add(value);
            }
            return out;
        }

        static MuleCommand from(Properties p) {
            MuleCommand c = new MuleCommand();
            c.id = p.getProperty("id", "");
            c.phase = p.getProperty("phase", "");
            c.initiator = p.getProperty("initiator", "");
            c.roster = p.getProperty("roster", "");
            c.round = parseInt(p.getProperty("round"), 0);
            c.reason = p.getProperty("reason", "");
            c.finalSurvivor = p.getProperty("finalSurvivor", "");
            c.createdAt = parseLong(p.getProperty("createdAt"), 0L);
            c.updatedAt = parseLong(p.getProperty("updatedAt"), 0L);
            return c;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
