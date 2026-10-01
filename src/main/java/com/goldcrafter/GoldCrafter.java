package com.goldcrafter;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.model.ItemDetail;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.script.LoopScript;
import com.epicbot.api.shared.script.ScriptManifest;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@ScriptManifest(name = "Gold Crafter", gameType = GameType.OS)
public class GoldCrafter extends LoopScript {

    private static final Product DEFAULT_PRODUCT = Product.GOLD_NECKLACE;

    private static final Tile EDGEVILLE_BANK = new Tile(3094, 3492, 0);
    private static final Tile EDGEVILLE_FURNACE = new Tile(3109, 3499, 0);
    private static final Tile GRAND_EXCHANGE = new Tile(3164, 3487, 0);

    private static final int TARGET_GOLD_BARS = 1000;
    private static final int TARGET_GEMS = 500;
    private static final int SELL_AT = 100;
    private static final int CASH_RESERVE = 10_000;

    private static final double BUY_MULTIPLIER = 1.03;
    private static final double SELL_MULTIPLIER = 0.97;
    private static final long GE_TIMEOUT_MS = 90_000L;

    private Product product = DEFAULT_PRODUCT;
    private State state = State.CHECK;

    private int barsBeforeCraft;
    private long lastCraftProgress;
    private int interfaceFailures;

    private int pendingSellQty;
    private int pendingBarBuy;
    private int pendingGemBuy;
    private boolean pendingMouldBuy;

    private String waitingOfferItem;
    private long waitingOfferStarted;

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
        GE_FINISH
    }

    private enum Product {
        GOLD_RING("Gold ring", "Ring mould", null),
        GOLD_NECKLACE("Gold necklace", "Necklace mould", null),
        GOLD_AMULET("Gold amulet (u)", "Amulet mould", null),

        SAPPHIRE_RING("Sapphire ring", "Ring mould", "Sapphire"),
        SAPPHIRE_NECKLACE("Sapphire necklace", "Necklace mould", "Sapphire"),
        SAPPHIRE_AMULET("Sapphire amulet (u)", "Amulet mould", "Sapphire"),

        EMERALD_RING("Emerald ring", "Ring mould", "Emerald"),
        EMERALD_NECKLACE("Emerald necklace", "Necklace mould", "Emerald"),
        EMERALD_AMULET("Emerald amulet (u)", "Amulet mould", "Emerald"),

        RUBY_RING("Ruby ring", "Ring mould", "Ruby"),
        RUBY_NECKLACE("Ruby necklace", "Necklace mould", "Ruby"),
        RUBY_AMULET("Ruby amulet (u)", "Amulet mould", "Ruby"),

        DIAMOND_RING("Diamond ring", "Ring mould", "Diamond"),
        DIAMOND_NECKLACE("Diamond necklace", "Necklace mould", "Diamond"),
        DIAMOND_AMULET("Diamond amulet (u)", "Amulet mould", "Diamond");

        final String output;
        final String mould;
        final String gem;

        Product(String output, String mould, String gem) {
            this.output = output;
            this.mould = mould;
            this.gem = gem;
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
        if (args != null && args.length > 0) {
            Product selected = Product.fromArg(args[0]);
            if (selected != null) product = selected;
        }

        getLogger().info("Gold Crafter starting: {}", product.output);
        getLogger().info("Restock target: {} gold bars{}", TARGET_GOLD_BARS,
                product.usesGem() ? ", " + TARGET_GEMS + " " + product.gem : "");
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

        if (ctx.localPlayer().getLocation().tileDistanceTo(EDGEVILLE_BANK) <= 6) {
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

        int bars = ctx.bank().getCount("Gold bar");
        int gems = product.usesGem() ? ctx.bank().getCount(product.gem) : Integer.MAX_VALUE;
        int outputs = ctx.bank().getCount(product.output);
        boolean mouldMissing = !ctx.bank().contains(product.mould);

        boolean cannotCraftBatch = bars < product.barsPerBatch()
                || (product.usesGem() && gems < product.gemsPerBatch())
                || mouldMissing;

        boolean shouldSell = outputs >= SELL_AT;

        if (cannotCraftBatch || shouldSell) {
            pendingSellQty = outputs;
            pendingMouldBuy = mouldMissing;
            pendingBarBuy = Math.max(0, TARGET_GOLD_BARS - bars);
            pendingGemBuy = product.usesGem() ? Math.max(0, TARGET_GEMS - gems) : 0;

            getLogger().info(
                    "GE trip: sell={}, mould={}, barsMissing={}, gemsMissing={}",
                    pendingSellQty, pendingMouldBuy, pendingBarBuy, pendingGemBuy
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
        if (furnace != null && furnace.tileDistanceTo(ctx.localPlayer().getLocation()) <= 5) {
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
        WidgetChild widget = findCraftWidget(ctx);
        if (widget == null) {
            interfaceFailures++;
            if (interfaceFailures >= 6) {
                interfaceFailures = 0;
                setState(State.OPEN_FURNACE, "craft interface not found");
            }
            return 450;
        }

        barsBeforeCraft = ctx.inventory().getCount("Gold bar");

        if (interactMakeAll(widget)) {
            lastCraftProgress = System.currentTimeMillis();
            setState(State.CRAFTING, "Make-All started");
            return 700;
        }

        setState(State.OPEN_FURNACE, "Make-All failed");
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
            setState(State.OPEN_BANK, "bank closed before GE prep");
            return 300;
        }

        if (pendingSellQty > 0 && !ctx.inventory().contains(product.output)) {
            if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE)) {
                ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.NOTE);
                return 350;
            }
            ctx.bank().withdrawAll(product.output);
            return 500;
        }

        int coins = ctx.inventory().getCount("Coins");
        if (coins < CASH_RESERVE && ctx.bank().contains("Coins")) {
            ctx.bank().withdrawAll("Coins");
            return 500;
        }

        ctx.bank().close();
        setState(State.WALK_GE, "GE supplies prepared");
        return 400;
    }

    private int walkGe(APIContext ctx) {
        if (ctx.localPlayer().getLocation().tileDistanceTo(GRAND_EXCHANGE) <= 9) {
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

        if (pendingSellQty > 0 && ctx.inventory().getCount(product.output) > 0) {
            int qty = ctx.inventory().getCount(product.output);
            int price = quickSellPrice(ctx, product.output);
            if (ctx.grandExchange().placeSellOffer(product.output, qty, price)) {
                pendingSellQty = 0;
                startWaiting(product.output);
                return 700;
            }
            return 500;
        }

        if (pendingMouldBuy) {
            int price = quickBuyPrice(ctx, product.mould);
            if (ctx.grandExchange().placeBuyOffer(product.mould, 1, price)) {
                pendingMouldBuy = false;
                startWaiting(product.mould);
                return 700;
            }
            return 500;
        }

        if (pendingBarBuy > 0) {
            int qty = pendingBarBuy;
            int price = quickBuyPrice(ctx, "Gold bar");
            if (ctx.grandExchange().placeBuyOffer("Gold bar", qty, price)) {
                pendingBarBuy = 0;
                startWaiting("Gold bar");
                return 700;
            }
            return 500;
        }

        if (product.usesGem() && pendingGemBuy > 0) {
            int qty = pendingGemBuy;
            int price = quickBuyPrice(ctx, product.gem);
            if (ctx.grandExchange().placeBuyOffer(product.gem, qty, price)) {
                pendingGemBuy = 0;
                startWaiting(product.gem);
                return 700;
            }
            return 500;
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
        setState(State.WALK_BANK, "GE complete");
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

    private SceneObject findFurnace(APIContext ctx) {
        List<SceneObject> furnaces = ctx.objects().getAll(
                15,
                o -> o != null
                        && o.isValid()
                        && "Furnace".equalsIgnoreCase(o.getName())
                        && o.hasAction("Smelt")
        );

        if (furnaces == null || furnaces.isEmpty()) return null;

        Tile player = ctx.localPlayer().getLocation();
        return furnaces.stream()
                .min(Comparator.comparingInt(o -> o.tileDistanceTo(player)))
                .orElse(null);
    }

    private WidgetChild findCraftWidget(APIContext ctx) {
        String expected = normalize(product.output);

        List<WidgetChild> widgets = ctx.widgets().getAllChildren(w ->
                w != null
                        && w.isValid()
                        && w.isVisible()
                        && (
                        normalize(w.getName()).contains(expected)
                                || normalize(w.getText()).contains(expected)
                                || normalize(w.getRawText()).contains(expected)
                )
        );

        if (widgets == null || widgets.isEmpty()) return null;

        for (WidgetChild widget : widgets) {
            if (widget.hasActionMatch("(?i).*make.*")) return widget;
        }

        return widgets.get(0);
    }

    private boolean interactMakeAll(WidgetChild widget) {
        List<String> actions = widget.getActions();
        if (actions != null) {
            for (String action : actions) {
                if (action == null) continue;
                String lower = action.toLowerCase(Locale.ROOT);
                if (lower.contains("make") && lower.contains("all")) {
                    return widget.interact(action);
                }
            }

            for (String action : actions) {
                if (action != null && action.toLowerCase(Locale.ROOT).contains("make")) {
                    return widget.interact(action);
                }
            }
        }

        return widget.click();
    }

    private boolean hasCraftBatch(APIContext ctx) {
        if (!ctx.inventory().contains(product.mould)) return false;
        if (ctx.inventory().getCount("Gold bar") <= 0) return false;
        return !product.usesGem() || ctx.inventory().getCount(product.gem) > 0;
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
}
