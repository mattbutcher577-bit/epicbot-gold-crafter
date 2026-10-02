package com.wizardhatbuyer;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetGroup;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.World;
import com.epicbot.api.shared.model.WorldType;
import com.epicbot.api.shared.script.LoopScript;
import com.epicbot.api.shared.script.ScriptManifest;
import com.epicbot.api.os.model.game.WidgetID;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;

@ScriptManifest(name = "Wizard Hat Buyer", gameType = GameType.OS)
public class WizardHatBuyer extends LoopScript {

    private static final Tile BETTY_SHOP = new Tile(3014, 3258, 0);
    // Stand-alone Port Sarim deposit box on the pier beside the Entrana ferry/boats.
    // This is NOT a bank.
    private static final Tile PORT_SARIM_DEPOSIT = new Tile(3045, 3236, 0);

    private static final String BLUE_HAT = "Blue wizard hat";
    private static final String BLUE_HAT_ALIAS = "Wizard hat";
    private static final String BLACK_HAT = "Black wizard hat";
    private static final int BLUE_HAT_ID = 579;
    private static final int BLACK_HAT_ID = 1017;

    // Coins occupy slot 0, leaving 27 inventory slots.
    // We finish each WORLD purchase cycle before checking this threshold, so an odd
    // incoming hat count can legitimately become 27 after buying both colours.
    private static final int DEPOSIT_THRESHOLD = 26;

    private final Deque<Integer> recentWorlds = new ArrayDeque<>();
    private State state = State.CHECK;
    private int hopFromWorld = -1;
    private int hopTargetWorld = -1;
    private long hopRequestedAt = 0L;
    private long hopArrivedAt = 0L;
    private long lastHopActivityAt = 0L;
    private long scriptStartedAt = 0L;
    private int zeroCoinStableChecks = 0;
    private long shopOpenedAt = 0L;
    private boolean bluePurchasePending = false;
    private int blueCountBeforePurchase = 0;
    private long bluePurchaseRequestedAt = 0L;
    private boolean blackPurchasePending = false;
    private int blackCountBeforePurchase = 0;
    private long blackPurchaseRequestedAt = 0L;
    private boolean forceTradeAfterHop = false;
    private boolean forcedShopCloseSent = false;
    private static final long COIN_GRACE_MS = 7_000L;
    private static final int ZERO_COIN_CONFIRMATIONS = 8;
    private static final long SHOP_SETTLE_MS = 800L;
    private static final long PURCHASE_CONFIRM_TIMEOUT_MS = 2_500L;

    private enum State {
        CHECK,
        WALK_SHOP,
        OPEN_SHOP,
        BUY_BLUE,
        BUY_BLACK,
        HOP_WORLD,
        WAIT_WORLD,
        WALK_DEPOSIT,
        OPEN_DEPOSIT,
        DEPOSIT_HATS
    }

    @Override
    public boolean onStart(String... args) {
        getLogger().info("Wizard Hat Buyer starting | F2P | Betty -> Port Sarim BOAT-PIER deposit box (not a bank)");
        state = State.CHECK;
        scriptStartedAt = System.currentTimeMillis();
        lastHopActivityAt = scriptStartedAt;
        zeroCoinStableChecks = 0;
        shopOpenedAt = 0L;
        bluePurchasePending = false;
        blackPurchasePending = false;
        forceTradeAfterHop = false;
        forcedShopCloseSent = false;
        return true;
    }

    @Override
    protected int loop() {
        APIContext ctx = getAPIContext();

        if (ctx.localPlayer().get() == null) {
            return 600;
        }

        // User requirement: the ONLY normal stop condition is genuinely being out of Coins.
        // EpicBot can temporarily expose an empty inventory during a world hop/loading transition,
        // so never trust a single zero-coin read.
        long now = System.currentTimeMillis();
        int coins = ctx.inventory().getCount("Coins");
        boolean hopOrLoadGrace =
                state == State.HOP_WORLD
                        || state == State.WAIT_WORLD
                        || now - lastHopActivityAt < COIN_GRACE_MS
                        || now - scriptStartedAt < COIN_GRACE_MS;

        if (coins > 0) {
            zeroCoinStableChecks = 0;
        } else if (hopOrLoadGrace) {
            // Ignore transient empty inventory while hopping/loading.
            zeroCoinStableChecks = 0;
            getLogger().debug("COINS: zero ignored during hop/loading grace | state={}", state);
        } else {
            zeroCoinStableChecks++;
            getLogger().warn("COINS: zero check {}/{} - confirming before stop",
                    zeroCoinStableChecks, ZERO_COIN_CONFIRMATIONS);

            if (zeroCoinStableChecks >= ZERO_COIN_CONFIRMATIONS) {
                getLogger().info("No Coins remain after {} stable checks - stopping Wizard Hat Buyer",
                        ZERO_COIN_CONFIRMATIONS);
                ctx.script().stop("Out of Coins");
                return 600;
            }
        }

        keepCoinsInFirstSlot(ctx);

        if (!ctx.walking().isRunEnabled() && ctx.walking().getRunEnergy() >= 30) {
            ctx.walking().setRun(true);
        }

        return switch (state) {
            case CHECK -> check(ctx);
            case WALK_SHOP -> walkShop(ctx);
            case OPEN_SHOP -> openShop(ctx);
            case BUY_BLUE -> buyBlue(ctx);
            case BUY_BLACK -> buyBlack(ctx);
            case HOP_WORLD -> hopWorld(ctx);
            case WAIT_WORLD -> waitWorld(ctx);
            case WALK_DEPOSIT -> walkDeposit(ctx);
            case OPEN_DEPOSIT -> openDeposit(ctx);
            case DEPOSIT_HATS -> depositHats(ctx);
        };
    }

    private int check(APIContext ctx) {
        int hats = hatCount(ctx);
        int coins = ctx.inventory().getCount("Coins");
        if (!inventorySnapshotReady(coins, hats)) {
            getLogger().debug("CHECK: waiting for inventory snapshot before routing");
            return 250;
        }

        if (hats >= DEPOSIT_THRESHOLD) {
            setState(State.WALK_DEPOSIT, "inventory ready to deposit");
        } else {
            setState(State.WALK_SHOP, "need more hats");
        }
        return 140;
    }

    private int walkShop(APIContext ctx) {
        if (shouldUseOpenShopShortcut(ctx.store().isOpen(), forceTradeAfterHop)) {
            setState(State.BUY_BLUE, "shop already open");
            return 140;
        }

        NPC betty = findBetty(ctx);
        if (betty != null && betty.tileDistanceTo(ctx) <= 6) {
            setState(State.OPEN_SHOP, "Betty in range");
            return 140;
        }

        getLogger().info("Walking to Betty's Magic Emporium");
        ctx.webWalking().walkTo(BETTY_SHOP);
        return 180;
    }

    private int openShop(APIContext ctx) {
        if (hatCount(ctx) >= DEPOSIT_THRESHOLD) {
            setState(State.WALK_DEPOSIT, "deposit threshold reached");
            return 140;
        }

        if (forceTradeAfterHop) {
            if (!forcedShopCloseSent) {
                getLogger().info("SHOP: invalidating stale pre-hop shop session");
                if (ctx.store().isOpen()) ctx.store().close();
                forcedShopCloseSent = true;
                return 320;
            }

            NPC betty = findBetty(ctx);
            if (betty == null) {
                getLogger().warn("SHOP: Betty not visible for required post-hop retrade");
                return 180;
            }

            getLogger().info("SHOP: forcing fresh trade after hop | distance={}", betty.tileDistanceTo(ctx));
            if (betty.interact("Trade") || betty.interact("Trade-with")) {
                forceTradeAfterHop = false;
                forcedShopCloseSent = false;
                return 500;
            }

            return 220;
        }

        if (shouldUseOpenShopShortcut(ctx.store().isOpen(), false)) {
            setState(State.BUY_BLUE, "shop opened");
            return 140;
        }

        NPC betty = findBetty(ctx);
        if (betty == null) {
            getLogger().warn("SHOP: Betty not visible yet; retrying nearby");
            setState(State.WALK_SHOP, "Betty not found");
            return 160;
        }

        getLogger().info("SHOP: Betty found at distance {} - opening trade", betty.tileDistanceTo(ctx));

        // Different client builds can expose the trade action slightly differently.
        if (betty.interact("Trade") || betty.interact("Trade-with")) {
            return 320;
        }

        // If the direct action fails, walk one step closer and retry rather than getting stuck.
        if (betty.tileDistanceTo(ctx) > 2) {
            ctx.webWalking().walkTo(BETTY_SHOP);
            return 220;
        }

        getLogger().warn("SHOP: Betty interaction failed; retrying");
        return 160;
    }

    private int buyBlue(APIContext ctx) {
        if (!ctx.store().isOpen()) {
            bluePurchasePending = false;
            setState(State.OPEN_SHOP, "shop closed before blue purchase");
            return 140;
        }

        if (System.currentTimeMillis() - shopOpenedAt < SHOP_SETTLE_MS) {
            return 180;
        }

        if (ctx.inventory().isFull()) {
            bluePurchasePending = false;
            ctx.store().close();
            setState(State.WALK_DEPOSIT, "inventory full");
            return 180;
        }

        if (bluePurchasePending) {
            int current = ctx.inventory().getCount(BLUE_HAT_ID);
            if (purchaseConfirmed(blueCountBeforePurchase, current)) {
                getLogger().info("BUY CONFIRMED: {} | {} -> {} | world={}",
                        BLUE_HAT, blueCountBeforePurchase, current, ctx.world().getCurrent());
                bluePurchasePending = false;
                setState(State.BUY_BLACK, "blue purchase confirmed");
                return 180;
            }

            if (System.currentTimeMillis() - bluePurchaseRequestedAt < PURCHASE_CONFIRM_TIMEOUT_MS) {
                getLogger().debug("BUY: waiting for {} inventory confirmation | count={}", BLUE_HAT, current);
                return 180;
            }

            getLogger().warn("BUY: {} request was not confirmed; continuing to black hat", BLUE_HAT);
            bluePurchasePending = false;
            setState(State.BUY_BLACK, "blue purchase not confirmed");
            return 180;
        }

        List<?> storeItems = ctx.store().getItems();
        int visibleItems = storeItems == null ? 0 : storeItems.size();
        int before = ctx.inventory().getCount(BLUE_HAT_ID);
        if (shouldAttemptPurchase(ctx.store().isOpen(), visibleItems)
                && (ctx.store().buyOne(BLUE_HAT_ID)
                || ctx.store().buyOne(BLUE_HAT)
                || ctx.store().buyOne(BLUE_HAT_ALIAS))) {
            blueCountBeforePurchase = before;
            bluePurchaseRequestedAt = System.currentTimeMillis();
            bluePurchasePending = true;
            getLogger().info("BUY REQUESTED: {} | before={} | world={}",
                    BLUE_HAT, before, ctx.world().getCurrent());
            return 180;
        }

        setState(State.BUY_BLACK, "blue direct purchase attempted");
        return 120;
    }

    private int buyBlack(APIContext ctx) {
        if (!ctx.store().isOpen()) {
            blackPurchasePending = false;
            setState(State.OPEN_SHOP, "shop closed before black purchase");
            return 140;
        }

        if (ctx.inventory().isFull()) {
            blackPurchasePending = false;
            ctx.store().close();
            setState(State.WALK_DEPOSIT, "inventory full");
            return 180;
        }

        if (blackPurchasePending) {
            int current = blackHatCount(ctx);
            if (purchaseConfirmed(blackCountBeforePurchase, current)) {
                getLogger().info("BUY CONFIRMED: {} | {} -> {} | world={}",
                        BLACK_HAT, blackCountBeforePurchase, current, ctx.world().getCurrent());
                blackPurchasePending = false;
                setState(State.HOP_WORLD, "black purchase confirmed; world cycle complete");
                return 180;
            }

            if (System.currentTimeMillis() - blackPurchaseRequestedAt < PURCHASE_CONFIRM_TIMEOUT_MS) {
                getLogger().debug("BUY: waiting for {} inventory confirmation | count={}", BLACK_HAT, current);
                return 180;
            }

            getLogger().warn("BUY: {} request was not confirmed; world cycle complete", BLACK_HAT);
            blackPurchasePending = false;
            setState(State.HOP_WORLD, "black purchase not confirmed; world cycle complete");
            return 180;
        }

        List<?> storeItems = ctx.store().getItems();
        int visibleItems = storeItems == null ? 0 : storeItems.size();
        int before = blackHatCount(ctx);
        if (shouldAttemptPurchase(ctx.store().isOpen(), visibleItems)
                && (ctx.store().buyOne(BLACK_HAT_ID)
                || ctx.store().buyOne(BLACK_HAT))) {
            blackCountBeforePurchase = before;
            blackPurchaseRequestedAt = System.currentTimeMillis();
            blackPurchasePending = true;
            getLogger().info("BUY REQUESTED: {} | before={} | world={}",
                    BLACK_HAT, before, ctx.world().getCurrent());
            return 180;
        }

        // Finish the whole world purchase cycle before deciding whether to deposit.
        // This permits 27 hats when we entered a world on an odd count and both hats were stocked.
        ctx.store().close();

        int hats = hatCount(ctx);
        getLogger().info("WORLD COMPLETE: {} hats total | world={}", hats, ctx.world().getCurrent());

        if (hats >= DEPOSIT_THRESHOLD || ctx.inventory().isFull()) {
            setState(State.WALK_DEPOSIT, "world cycle complete; deposit");
        } else {
            setState(State.HOP_WORLD, "world stock checked");
        }
        return 180;
    }

    private int hopWorld(APIContext ctx) {
        if (hatCount(ctx) >= DEPOSIT_THRESHOLD || ctx.inventory().isFull()) {
            if (ctx.store().isOpen() && !ctx.world().isWorldMenuOpen()) ctx.store().close();
            setState(State.WALK_DEPOSIT, "world cycle complete; inventory ready to deposit");
            return 140;
        }

        int current = ctx.world().getCurrent();

        // IMPORTANT: once the world switcher is visible, ignore ctx.store().isOpen().
        // EpicBot NXT can leave the shop-open flag stale after closing Betty's shop.
        // The previous build kept calling store.close() forever even though the
        // world switcher was already on screen.
        if (ctx.world().isWorldMenuOpen()) {
            getLogger().info("HOP: world menu ready on world {}", current);
        } else {
            if (ctx.store().isOpen()) {
                getLogger().info("HOP: closing shop once before world switch");
                ctx.store().close();
            }

            getLogger().info("HOP: opening world menu from world {}", current);
            ctx.world().openWorldMenu();
            return 140;
        }

        rememberWorld(current);

        List<World> worlds = ctx.world().getWorlds();
        World target = null;

        if (worlds != null && !worlds.isEmpty()) {
            target = worlds.stream()
                    .filter(w -> isSafeF2PWorld(w, current))
                    .sorted(Comparator.comparingInt(World::getId))
                    .findFirst()
                    .orElse(null);
        }

        if (target == null) {
            recentWorlds.clear();
            if (worlds != null && !worlds.isEmpty()) {
                target = worlds.stream()
                        .filter(w -> isSafeF2PWorld(w, current))
                        .sorted(Comparator.comparingInt(World::getId))
                        .findFirst()
                        .orElse(null);
            }
        }

        hopFromWorld = current;
        hopRequestedAt = System.currentTimeMillis();
        lastHopActivityAt = hopRequestedAt;

        if (target != null) {
            hopTargetWorld = target.getId();
            getLogger().info("HOP: clicking world {} -> {}", current, hopTargetWorld);
            if (ctx.world().hop(hopTargetWorld)) {
                lastHopActivityAt = System.currentTimeMillis();
                hopArrivedAt = 0L;
                setState(State.WAIT_WORLD, "explicit world hop clicked");
                return 220;
            }

            // IMPORTANT: do NOT call hopToF2P() here.
            // EpicBot NXT's built-in F2P hopper can close/replace the current script instance.
            // Mark this target as temporarily skipped and try another explicit F2P world next loop.
            getLogger().warn("HOP: explicit hop to {} returned false; trying a different F2P world", hopTargetWorld);
            rememberWorld(hopTargetWorld);
            hopTargetWorld = -1;
            return 180;
        }

        getLogger().warn("HOP: no explicit safe F2P target available; clearing recent list and retrying");
        recentWorlds.clear();
        return 220;
    }

    private int waitWorld(APIContext ctx) {
        int current = ctx.world().getCurrent();
        long now = System.currentTimeMillis();

        if (current > 0 && current != hopFromWorld) {
            if (hopArrivedAt == 0L) {
                hopArrivedAt = now;
                lastHopActivityAt = now;
                getLogger().info("HOP SUCCESS: {} -> {} | waiting for inventory to settle", hopFromWorld, current);
                return 180;
            }

            int coins = ctx.inventory().getCount("Coins");
            if (coins > 0 || now - hopArrivedAt >= 4_000L) {
                if (coins > 0) {
                    getLogger().info("HOP READY: world {} loaded | coins visible={}", current, coins);
                } else {
                    getLogger().warn("HOP READY: world {} loaded but coins still not visible after settle timeout", current);
                }

                hopTargetWorld = -1;
                hopRequestedAt = 0L;
                hopArrivedAt = 0L;
                forceTradeAfterHop = true;
                forcedShopCloseSent = false;
                setState(State.OPEN_SHOP, "new world stable; fresh trade required");
                return 160;
            }

            getLogger().debug("HOP: waiting for inventory on world {}", current);
            return 160;
        }

        // If the world menu closed but the world did not change, try another explicit target quickly.
        if (!ctx.world().isWorldMenuOpen()
                && hopRequestedAt > 0L
                && now - hopRequestedAt > 1_500L) {
            getLogger().warn("HOP: menu closed but still on {}; retrying another world", current);
            if (hopTargetWorld > 0) rememberWorld(hopTargetWorld);
            hopTargetWorld = -1;
            hopArrivedAt = 0L;
            setState(State.HOP_WORLD, "world unchanged");
            return 160;
        }

        if (hopRequestedAt > 0L && now - hopRequestedAt > 3_500L) {
            getLogger().warn("HOP TIMEOUT: still on world {}; trying another target", current);
            if (hopTargetWorld > 0) rememberWorld(hopTargetWorld);
            hopTargetWorld = -1;
            hopArrivedAt = 0L;
            setState(State.HOP_WORLD, "hop timeout");
            return 160;
        }

        return 180;
    }

    private int walkDeposit(APIContext ctx) {
        if (hatCount(ctx) <= 0) {
            setState(State.WALK_SHOP, "nothing to deposit");
            return 140;
        }

        if (depositInterfaceReady(ctx)) {
            setState(State.DEPOSIT_HATS, "deposit interface already open");
            return 140;
        }

        SceneObject box = findDepositBox(ctx);
        if (box != null && box.tileDistanceTo(ctx) <= 6) {
            setState(State.OPEN_DEPOSIT, "deposit box in range");
            return 140;
        }

        getLogger().info("Walking to Port Sarim boat-pier deposit box by Entrana ferry | hats={}", hatCount(ctx));
        ctx.webWalking().walkTo(PORT_SARIM_DEPOSIT);
        return 180;
    }

    private int openDeposit(APIContext ctx) {
        if (depositInterfaceReady(ctx)) {
            setState(State.DEPOSIT_HATS, "deposit box opened");
            return 140;
        }

        SceneObject box = findDepositBox(ctx);
        if (box == null) {
            setState(State.WALK_DEPOSIT, "deposit box not found");
            return 160;
        }

        if (box.interact("Deposit")) {
            return 180;
        }

        // Deposit interaction problems are recovery/retry conditions, never stop conditions.
        return 160;
    }

    private int depositHats(APIContext ctx) {
        if (!depositInterfaceReady(ctx)) {
            setState(State.OPEN_DEPOSIT, "deposit interface closed");
            return 140;
        }

        // Deposit ONLY the wizard hats. Coins are deliberately never included.
        if (ctx.inventory().getCount(BLUE_HAT_ID) > 0) {
            getLogger().info("DEPOSIT: {} x{}", BLUE_HAT, ctx.inventory().getCount(BLUE_HAT_ID));
            ctx.bank().depositAll(BLUE_HAT_ID);
            return 160;
        }

        if (ctx.inventory().getCount(BLACK_HAT_ID) > 0) {
            getLogger().info("DEPOSIT: {} x{}", BLACK_HAT, ctx.inventory().getCount(BLACK_HAT_ID));
            ctx.bank().depositAll(BLACK_HAT_ID);
            return 160;
        }

        ctx.bank().close();
        keepCoinsInFirstSlot(ctx);
        setState(State.WALK_SHOP, "hats deposited; coins retained");
        return 220;
    }

    private NPC findBetty(APIContext ctx) {
        // Do not require hasAction("Trade") here. Some EpicBot/NXT builds do not
        // populate NPC action metadata until interaction time even though Betty is visible.
        List<NPC> npcs = ctx.npcs().getAll(n ->
                n != null
                        && n.isValid()
                        && n.getName() != null
                        && n.getName().equalsIgnoreCase("Betty"));

        if (npcs == null || npcs.isEmpty()) return null;

        return npcs.stream()
                .min(Comparator.comparingInt(n -> n.tileDistanceTo(ctx)))
                .orElse(null);
    }

    private SceneObject findDepositBox(APIContext ctx) {
        List<SceneObject> boxes = ctx.objects().getAll(18, o -> {
            if (o == null || !o.isValid() || !o.hasAction("Deposit")) return false;
            String name = o.getName();
            if (name == null) return false;
            String lower = name.toLowerCase();
            // Only use the stand-alone deposit box by the boats; never select a bank object.
            return lower.contains("deposit box");
        });

        if (boxes == null || boxes.isEmpty()) return null;

        return boxes.stream()
                .min(Comparator.comparingInt(o -> o.tileDistanceTo(ctx)))
                .orElse(null);
    }

    static boolean shouldAttemptPurchase(boolean storeOpen, int visibleItemCount) {
        // Some EpicBot NXT builds return an empty store item list even while the
        // shop is visibly open. Store openness is therefore the reliable gate;
        // direct buyOne calls determine whether each item is actually stocked.
        return storeOpen;
    }

    static boolean purchaseConfirmed(int beforeCount, int currentCount) {
        return currentCount > beforeCount;
    }

    static boolean shouldUseOpenShopShortcut(boolean storeOpen, boolean forceTradeAfterHop) {
        return storeOpen && !forceTradeAfterHop;
    }

    private boolean depositInterfaceReady(APIContext ctx) {
        WidgetGroup depositBox = ctx.widgets().get(WidgetID.DEPOSIT_BOX_GROUP_ID);
        boolean depositWidgetVisible = depositBox != null && depositBox.isVisible();
        return isDepositInterfaceReady(
                ctx.bank().isOpen(),
                ctx.bank().isVisible(),
                depositWidgetVisible);
    }

    static boolean isDepositInterfaceReady(
            boolean bankOpen,
            boolean bankVisible,
            boolean depositWidgetVisible) {
        return bankOpen || bankVisible || depositWidgetVisible;
    }

    static boolean inventorySnapshotReady(int coins, int hats) {
        return coins > 0 || hats > 0;
    }

    private int blackHatCount(APIContext ctx) {
        return ctx.inventory().getCount(BLACK_HAT_ID);
    }

    private int hatCount(APIContext ctx) {
        return ctx.inventory().getCount(BLUE_HAT_ID) + blackHatCount(ctx);
    }

    private void keepCoinsInFirstSlot(APIContext ctx) {
        ItemWidget coins = ctx.inventory().getItem("Coins");
        if (coins != null && coins.isValid() && coins.getIndex() != 0) {
            ctx.inventory().moveItem(coins, 0);
        }
    }

    private boolean isSafeF2PWorld(World world, int current) {
        if (world == null || world.getId() == current || world.isMembers()) return false;
        if (recentWorlds.contains(world.getId())) return false;

        EnumSet<WorldType> types = world.getTypes();
        if (types == null) return true;

        return !types.contains(WorldType.MEMBERS)
                && !types.contains(WorldType.PVP)
                && !types.contains(WorldType.HIGH_RISK)
                && !types.contains(WorldType.DEADMAN)
                && !types.contains(WorldType.BOUNTY)
                && !types.contains(WorldType.PVP_ARENA)
                && !types.contains(WorldType.LAST_MAN_STANDING);
    }

    private void rememberWorld(int world) {
        if (world <= 0) return;
        recentWorlds.remove(world);
        recentWorlds.addLast(world);
        while (recentWorlds.size() > 8) {
            recentWorlds.removeFirst();
        }
    }

    private void setState(State next, String reason) {
        if (state != next) {
            getLogger().info("STATE {} -> {} | {}", state, next, reason);
            state = next;
            if (next == State.BUY_BLUE) shopOpenedAt = System.currentTimeMillis();
        }
    }

    @Override
    protected void onStop() {
        getLogger().info("Wizard Hat Buyer stopped");
    }
}
