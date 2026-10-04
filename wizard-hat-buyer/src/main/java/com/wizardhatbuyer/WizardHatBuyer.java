package com.wizardhatbuyer;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.entity.WidgetGroup;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.World;
import com.epicbot.api.shared.model.WorldType;
import com.epicbot.api.shared.script.LoopScript;
import com.epicbot.api.shared.script.ScriptManifest;
import com.epicbot.api.shared.util.paint.PaintContext;
import com.epicbot.api.os.model.game.WidgetID;

import java.awt.Color;
import java.awt.Font;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

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
    private final Map<Integer, Long> failedWorldUntil = new HashMap<>();
    private State state = State.CHECK;
    private long stateEnteredAt = 0L;
    private int hopFromWorld = -1;
    private int hopTargetWorld = -1;
    private long hopRequestedAt = 0L;
    private long hopArrivedAt = 0L;
    private long lastHopActivityAt = 0L;
    private long scriptStartedAt = 0L;
    private int zeroCoinStableChecks = 0;
    private long lastZeroCoinConfirmationAt = 0L;
    private int lastKnownHatCount = 0;
    private long shopOpenedAt = 0L;
    private boolean bluePurchasePending = false;
    private int blueCountBeforePurchase = 0;
    private long bluePurchaseRequestedAt = 0L;
    private boolean blackPurchasePending = false;
    private int blackCountBeforePurchase = 0;
    private long blackPurchaseRequestedAt = 0L;
    private boolean forceTradeAfterHop = false;
    private boolean forcedShopCloseSent = false;
    private boolean blueDepositPending = false;
    private int blueCountBeforeDeposit = 0;
    private long blueDepositRequestedAt = 0L;
    private boolean blackDepositPending = false;
    private int blackCountBeforeDeposit = 0;
    private long blackDepositRequestedAt = 0L;
    private long depositOpenRequestedAt = 0L;
    private int consecutiveHopFailures = 0;
    private long lastWaitingLogAt = 0L;
    private long lastStatsLogAt = 0L;
    private int hatsBought = 0;
    private int blueHatsBought = 0;
    private int blackHatsBought = 0;
    private int hatsDeposited = 0;
    private int successfulHops = 0;
    private int failedPurchaseRequests = 0;
    private int failedDepositRequests = 0;
    private int failedHopRequests = 0;
    private int watchdogRecoveries = 0;
    private long gpSpent = 0L;
    private int blueCoinsBeforePurchase = -1;
    private int blackCoinsBeforePurchase = -1;
    private int bluePurchaseRetries = 0;
    private int blackPurchaseRetries = 0;
    private static final long COIN_GRACE_MS = 7_000L;
    private static final int ZERO_COIN_CONFIRMATIONS = 8;
    private static final long ZERO_COIN_CONFIRMATION_INTERVAL_MS = 1_000L;
    private static final long SHOP_SETTLE_MS = 250L;
    private static final long PURCHASE_POLL_DELAY_MS = 60L;
    private static final long HOP_ACTION_DELAY_MS = 70L;
    private static final long WORLD_UNCHANGED_RETRY_MS = 750L;
    private static final long WORLD_INVENTORY_SETTLE_TIMEOUT_MS = 1_500L;
    private static final long PURCHASE_CONFIRM_TIMEOUT_MS = 2_500L;
    private static final int MAX_PURCHASE_RETRIES = 1;
    private static final long DEPOSIT_CONFIRM_TIMEOUT_MS = 3_000L;
    private static final long DEPOSIT_OPEN_RETRY_MS = 3_000L;
    private static final long DEPOSIT_INTERFACE_SETTLE_MS = 1_200L;
    private static final long FAILED_WORLD_COOLDOWN_MS = 15_000L;
    private static final long WAIT_LOG_INTERVAL_MS = 1_000L;
    private static final long STATS_LOG_INTERVAL_MS = 60_000L;

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

    enum DepositStep {
        CONFIRMED,
        WAIT,
        RETRY,
        OPEN_INTERFACE,
        REQUEST
    }

    @Override
    public boolean onStart(String... args) {
        getLogger().info("Wizard Hat Buyer starting | F2P | Betty -> Port Sarim BOAT-PIER deposit box (not a bank)");
        state = State.CHECK;
        scriptStartedAt = System.currentTimeMillis();
        stateEnteredAt = scriptStartedAt;
        lastHopActivityAt = scriptStartedAt;
        zeroCoinStableChecks = 0;
        lastZeroCoinConfirmationAt = 0L;
        lastKnownHatCount = 0;
        shopOpenedAt = 0L;
        bluePurchasePending = false;
        blackPurchasePending = false;
        bluePurchaseRetries = 0;
        blackPurchaseRetries = 0;
        forceTradeAfterHop = false;
        forcedShopCloseSent = false;
        blueDepositPending = false;
        blackDepositPending = false;
        depositOpenRequestedAt = 0L;
        consecutiveHopFailures = 0;
        lastWaitingLogAt = 0L;
        lastStatsLogAt = scriptStartedAt;
        hatsBought = 0;
        blueHatsBought = 0;
        blackHatsBought = 0;
        hatsDeposited = 0;
        successfulHops = 0;
        failedPurchaseRequests = 0;
        failedDepositRequests = 0;
        failedHopRequests = 0;
        watchdogRecoveries = 0;
        gpSpent = 0L;
        failedWorldUntil.clear();
        recentWorlds.clear();
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
        int hats = hatCount(ctx);
        if (hats > 0) lastKnownHatCount = hats;
        boolean hopOrLoadGrace =
                state == State.HOP_WORLD
                        || state == State.WAIT_WORLD
                        || now - lastHopActivityAt < COIN_GRACE_MS
                        || now - scriptStartedAt < COIN_GRACE_MS;
        boolean inventoryMayBeHidden =
                state == State.HOP_WORLD
                        || state == State.WAIT_WORLD
                        || state == State.OPEN_DEPOSIT
                        || state == State.DEPOSIT_HATS
                        || depositInterfaceReady(ctx);

        if (coins > 0) {
            zeroCoinStableChecks = 0;
            lastZeroCoinConfirmationAt = 0L;
        } else if (shouldIgnoreZeroCoins(hopOrLoadGrace, inventoryMayBeHidden)
                || !coinStopSnapshotUsable(hats, lastKnownHatCount)) {
            // Ignore transient empty inventory while hopping/loading.
            zeroCoinStableChecks = 0;
            lastZeroCoinConfirmationAt = 0L;
            logWaiting("COINS: zero ignored while inventory may be hidden | state={}", state);
        } else if (shouldRecordZeroCoinConfirmation(
                now, lastZeroCoinConfirmationAt, ZERO_COIN_CONFIRMATION_INTERVAL_MS)) {
            zeroCoinStableChecks++;
            lastZeroCoinConfirmationAt = now;
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

        logStatsIfDue(now);

        int watchdogDelay = recoverTimedOutState(ctx, now);
        if (watchdogDelay >= 0) return watchdogDelay;

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
            logWaiting("CHECK: waiting for inventory snapshot before routing | state={}", state);
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

        logWaiting("Walking to Betty's Magic Emporium | state={}", state);
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
                return 160;
            }

            NPC betty = findBetty(ctx);
            if (betty == null) {
                logWaiting("SHOP: waiting for Betty for required post-hop retrade | state={}", state);
                return 180;
            }

            logWaiting("SHOP: forcing fresh trade after hop | distance={}", betty.tileDistanceTo(ctx));
            if (betty.interact("Trade") || betty.interact("Trade-with")) {
                forceTradeAfterHop = false;
                forcedShopCloseSent = false;
                return 250;
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

        logWaiting("SHOP: Betty found; opening trade | distance={}", betty.tileDistanceTo(ctx));

        // Different client builds can expose the trade action slightly differently.
        if (betty.interact("Trade") || betty.interact("Trade-with")) {
            return 250;
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
            return (int) PURCHASE_POLL_DELAY_MS;
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
                bluePurchaseRetries = 0;
                int purchased = current - blueCountBeforePurchase;
                hatsBought += purchased;
                blueHatsBought += purchased;
                gpSpent += observedCoinSpend(ctx, blueCoinsBeforePurchase);
                setState(State.BUY_BLACK, "blue purchase confirmed");
                return (int) PURCHASE_POLL_DELAY_MS;
            }

            if (System.currentTimeMillis() - bluePurchaseRequestedAt < PURCHASE_CONFIRM_TIMEOUT_MS) {
                logWaiting("BUY: waiting for Blue wizard hat inventory confirmation | count={}", current);
                return (int) PURCHASE_POLL_DELAY_MS;
            }

            int stock = ctx.store().getCount(BLUE_HAT_ID);
            if (bluePurchaseRetries < MAX_PURCHASE_RETRIES) {
                bluePurchaseRetries++;
                bluePurchasePending = false;
                getLogger().warn("BUY: {} not confirmed - retrying once | reported stock={}", BLUE_HAT, stock);
                return 120;
            }

            getLogger().warn("BUY: {} unavailable or retry failed; continuing to black hat | stock={}", BLUE_HAT, stock);
            failedPurchaseRequests++;
            bluePurchasePending = false;
            bluePurchaseRetries = 0;
            setState(State.BUY_BLACK, "blue purchase unavailable");
            return (int) PURCHASE_POLL_DELAY_MS;
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
            blueCoinsBeforePurchase = coinStackSize(ctx);
            getLogger().info("BUY REQUESTED: {} | before={} | world={}",
                    BLUE_HAT, before, ctx.world().getCurrent());
            return (int) PURCHASE_POLL_DELAY_MS;
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
                blackPurchaseRetries = 0;
                int purchased = current - blackCountBeforePurchase;
                hatsBought += purchased;
                blackHatsBought += purchased;
                gpSpent += observedCoinSpend(ctx, blackCoinsBeforePurchase);
                setState(State.HOP_WORLD, "black purchase confirmed; world cycle complete");
                return (int) PURCHASE_POLL_DELAY_MS;
            }

            if (System.currentTimeMillis() - blackPurchaseRequestedAt < PURCHASE_CONFIRM_TIMEOUT_MS) {
                logWaiting("BUY: waiting for Black wizard hat inventory confirmation | count={}", current);
                return (int) PURCHASE_POLL_DELAY_MS;
            }

            int stock = ctx.store().getCount(BLACK_HAT_ID);
            if (blackPurchaseRetries < MAX_PURCHASE_RETRIES) {
                blackPurchaseRetries++;
                blackPurchasePending = false;
                getLogger().warn("BUY: {} not confirmed - retrying once | reported stock={}", BLACK_HAT, stock);
                return 120;
            }

            getLogger().warn("BUY: {} unavailable or retry failed; world cycle complete | stock={}", BLACK_HAT, stock);
            failedPurchaseRequests++;
            blackPurchasePending = false;
            blackPurchaseRetries = 0;
            setState(State.HOP_WORLD, "black purchase unavailable; world cycle complete");
            return (int) PURCHASE_POLL_DELAY_MS;
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
            blackCoinsBeforePurchase = coinStackSize(ctx);
            getLogger().info("BUY REQUESTED: {} | before={} | world={}",
                    BLACK_HAT, before, ctx.world().getCurrent());
            return (int) PURCHASE_POLL_DELAY_MS;
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
            if (ctx.store().isOpen() && !worldMenuVisible(ctx)) ctx.store().close();
            setState(State.WALK_DEPOSIT, "world cycle complete; inventory ready to deposit");
            return 140;
        }

        int current = ctx.world().getCurrent();

        // EpicBot NXT can return false from hop() even though the client changes
        // world. Recognize that completed transition before opening another menu
        // or clicking another target.
        if (worldChangedAfterHopRequest(hopRequestedAt, hopFromWorld, current)) {
            lastHopActivityAt = System.currentTimeMillis();
            hopArrivedAt = 0L;
            setState(State.WAIT_WORLD, "world changed after unconfirmed hop request");
            return (int) HOP_ACTION_DELAY_MS;
        }

        // IMPORTANT: once the world switcher is visible, ignore ctx.store().isOpen().
        // EpicBot NXT can leave the shop-open flag stale after closing Betty's shop.
        // The previous build kept calling store.close() forever even though the
        // world switcher was already on screen.
        if (worldMenuVisible(ctx)) {
            getLogger().info("HOP: world menu ready on world {}", current);
        } else {
            if (ctx.store().isOpen()) {
                getLogger().info("HOP: closing shop once before world switch");
                ctx.store().close();
            }

            getLogger().info("HOP: opening world menu from world {}", current);
            ctx.world().openWorldMenu();
            return (int) HOP_ACTION_DELAY_MS;
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
            long completedRequestAt = completedHopRequestAt(
                    () -> tryWorldHopActions(
                            () -> ctx.world().hop(hopTargetWorld),
                            () -> clickWorldSwitcherRow(ctx, hopTargetWorld)),
                    System::currentTimeMillis);
            if (completedRequestAt > 0L) {
                hopRequestedAt = completedRequestAt;
                lastHopActivityAt = completedRequestAt;
                hopArrivedAt = 0L;
                setState(State.WAIT_WORLD, "world hop requested");
                return (int) HOP_ACTION_DELAY_MS;
            }

            // IMPORTANT: do NOT call hopToF2P() here.
            // EpicBot NXT's built-in F2P hopper can close/replace the current script instance.
            // Mark this target as temporarily skipped and try another explicit F2P world next loop.
            getLogger().warn("HOP: API and world-row click both rejected {}; trying another F2P world", hopTargetWorld);
            failedWorldUntil.put(hopTargetWorld, System.currentTimeMillis() + FAILED_WORLD_COOLDOWN_MS);
            consecutiveHopFailures++;
            failedHopRequests++;
            rememberWorld(hopTargetWorld);
            hopTargetWorld = -1;
            return hopRetryDelay(consecutiveHopFailures);
        }

        getLogger().warn("HOP: no explicit safe F2P target available; clearing recent list and retrying");
        recentWorlds.clear();
        consecutiveHopFailures++;
        return hopRetryDelay(consecutiveHopFailures);
    }

    private boolean clickWorldSwitcherRow(APIContext ctx, int worldId) {
        WidgetGroup worldSwitcher = ctx.widgets().get(WidgetID.WORLD_HOPPER_GROUP);
        if (worldSwitcher == null || !worldSwitcher.isVisible()) return false;

        List<WidgetChild> matches = ctx.widgets().getAllChildren(child ->
                child != null
                        && child.isValid()
                        && child.getGroup() != null
                        && child.getGroup().getIndex() == WidgetID.WORLD_HOPPER_GROUP
                        && worldWidgetMatches(child.getText(), worldId));
        if (matches == null || matches.isEmpty()) return false;

        WidgetChild label = matches.get(0);
        WidgetChild clickable = label;
        String action = worldSwitchAction(clickable);
        for (int depth = 0; action == null && clickable != null && depth < 6; depth++) {
            clickable = clickable.getParent();
            action = worldSwitchAction(clickable);
        }

        if (clickable == null) clickable = label;
        WidgetChild scrollArea = worldSwitcher.getChild(10);
        if (!ctx.widgets().isOnScreen(clickable) && scrollArea != null) {
            ctx.widgets().scroll(clickable, scrollArea);
        }

        boolean clicked = action != null ? clickable.interact(action) : clickable.click();
        if (clicked) {
            getLogger().info("HOP: clicked world-switcher widget row for {}", worldId);
        }
        return clicked;
    }

    private static String worldSwitchAction(WidgetChild child) {
        if (child == null || child.getActions() == null) return null;
        return child.getActions().stream()
                .filter(action -> action != null
                        && (action.toLowerCase().contains("switch")
                        || action.toLowerCase().contains("hop")))
                .findFirst()
                .orElse(null);
    }

    private int waitWorld(APIContext ctx) {
        int current = ctx.world().getCurrent();
        long now = System.currentTimeMillis();

        if (worldChangedAfterHopRequest(hopRequestedAt, hopFromWorld, current)) {
            if (hopArrivedAt == 0L) {
                hopArrivedAt = now;
                lastHopActivityAt = now;
                getLogger().info("HOP SUCCESS: {} -> {} | waiting for inventory to settle", hopFromWorld, current);
                successfulHops++;
                consecutiveHopFailures = 0;
                return (int) HOP_ACTION_DELAY_MS;
            }

            int coins = ctx.inventory().getCount("Coins");
            if (coins > 0 || now - hopArrivedAt >= WORLD_INVENTORY_SETTLE_TIMEOUT_MS) {
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
                return (int) HOP_ACTION_DELAY_MS;
            }

            logWaiting("HOP: waiting for inventory on world {}", current);
            return (int) HOP_ACTION_DELAY_MS;
        }

        // If the world menu closed but the world did not change, try another explicit target quickly.
        if (!worldMenuVisible(ctx)
                && hopRequestedAt > 0L
                && now - hopRequestedAt > WORLD_UNCHANGED_RETRY_MS) {
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

        logWaiting("Walking to Port Sarim boat-pier deposit box | hats={}", hatCount(ctx));
        ctx.webWalking().walkTo(PORT_SARIM_DEPOSIT);
        return 180;
    }

    private int openDeposit(APIContext ctx) {
        if (depositInterfaceReady(ctx)) {
            depositOpenRequestedAt = 0L;
            setState(State.DEPOSIT_HATS, "deposit box opened");
            return 140;
        }

        long now = System.currentTimeMillis();
        if (depositOpenRequestedAt > 0L
                && now - depositOpenRequestedAt < DEPOSIT_OPEN_RETRY_MS) {
            logWaiting("DEPOSIT: waiting for box interface | state={}", state);
            return 180;
        }

        SceneObject box = findDepositBox(ctx);
        if (box == null) {
            setState(State.WALK_DEPOSIT, "deposit box not found");
            return 160;
        }

        depositOpenRequestedAt = now;
        if (box.interact("Deposit")) {
            return 180;
        }

        // Deposit interaction problems are recovery/retry conditions, never stop conditions.
        return 160;
    }

    private int depositHats(APIContext ctx) {
        long now = System.currentTimeMillis();
        boolean interfaceReady = depositInterfaceReady(ctx);
        if (interfaceReady && now - stateEnteredAt < DEPOSIT_INTERFACE_SETTLE_MS) {
            logWaiting("DEPOSIT: allowing box interface to settle | state={}", state);
            return 250;
        }

        int blueCount = ctx.inventory().getCount(BLUE_HAT_ID);
        if (blueDepositPending) {
            DepositStep blueStep = nextDepositStep(
                    true,
                    blueCount,
                    now - blueDepositRequestedAt,
                    DEPOSIT_CONFIRM_TIMEOUT_MS,
                    interfaceReady);
            if (blueStep == DepositStep.CONFIRMED) {
                getLogger().info("DEPOSIT CONFIRMED: {} x{}", BLUE_HAT, blueCountBeforeDeposit);
                hatsDeposited += blueCountBeforeDeposit;
                blueDepositPending = false;
            } else if (blueStep == DepositStep.WAIT) {
                logWaiting("DEPOSIT: waiting for Blue wizard hat confirmation | count={}", blueCount);
                return 180;
            } else {
                getLogger().warn("DEPOSIT: {} request was not confirmed; retrying", BLUE_HAT);
                failedDepositRequests++;
                blueDepositPending = false;
                return 300;
            }
        }

        if (!interfaceReady) {
            logWaiting("DEPOSIT: interface closed; automatic reopen suppressed | state={}", state);
            return 500;
        }

        if (blueCount > 0) {
            blueCountBeforeDeposit = blueCount;
            boolean accepted = requestDepositAll(ctx, BLUE_HAT_ID);
            blueDepositRequestedAt = now;
            blueDepositPending = true;
            if (accepted) {
                getLogger().info("DEPOSIT REQUESTED: {} x{}", BLUE_HAT, blueCount);
            } else {
                getLogger().warn("DEPOSIT SENT: EpicBot returned false for {}; verifying inventory before retry", BLUE_HAT);
            }
            return 180;
        }

        int blackCount = ctx.inventory().getCount(BLACK_HAT_ID);
        if (blackDepositPending) {
            DepositStep blackStep = nextDepositStep(
                    true,
                    blackCount,
                    now - blackDepositRequestedAt,
                    DEPOSIT_CONFIRM_TIMEOUT_MS,
                    interfaceReady);
            if (blackStep == DepositStep.CONFIRMED) {
                getLogger().info("DEPOSIT CONFIRMED: {} x{}", BLACK_HAT, blackCountBeforeDeposit);
                hatsDeposited += blackCountBeforeDeposit;
                blackDepositPending = false;
            } else if (blackStep == DepositStep.WAIT) {
                logWaiting("DEPOSIT: waiting for Black wizard hat confirmation | count={}", blackCount);
                return 180;
            } else {
                getLogger().warn("DEPOSIT: {} request was not confirmed; retrying", BLACK_HAT);
                failedDepositRequests++;
                blackDepositPending = false;
                return 300;
            }
        }


        if (!interfaceReady) {
            logWaiting("DEPOSIT: interface closed; automatic reopen suppressed | state={}", state);
            return 500;
        }

        if (blackCount > 0) {
            blackCountBeforeDeposit = blackCount;
            boolean accepted = requestDepositAll(ctx, BLACK_HAT_ID);
            blackDepositRequestedAt = now;
            blackDepositPending = true;
            if (accepted) {
                getLogger().info("DEPOSIT REQUESTED: {} x{}", BLACK_HAT, blackCount);
            } else {
                getLogger().warn("DEPOSIT SENT: EpicBot returned false for {}; verifying inventory before retry", BLACK_HAT);
            }
            return 180;
        }

        ctx.bank().close();
        lastKnownHatCount = 0;
        keepCoinsInFirstSlot(ctx);
        forceTradeAfterHop = true;
        forcedShopCloseSent = false;
        setState(State.CHECK, "hats deposited; rechecking inventory");
        return 220;
    }

    private boolean requestDepositAll(APIContext ctx, int itemId) {
        // The deposit box renders a separate inventory inside widget group 192.
        // Using ctx.inventory() opens the normal inventory tab and closes this UI.
        return tryDepositActions(
                () -> {
                    WidgetGroup depositBox = ctx.widgets().get(WidgetID.DEPOSIT_BOX_GROUP_ID);
                    if (depositBox == null || !depositBox.isVisible()) return false;
                    WidgetChild item = depositBox.find(child -> child != null
                            && child.isValid()
                            && child.getItemId() == itemId
                            && child.hasAction("Deposit-All"));
                    return item != null && item.interact("Deposit-All");
                },
                () -> ctx.bank().depositAll(itemId));
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

    static boolean depositConfirmed(int currentCount) {
        return currentCount == 0;
    }

    static DepositStep nextDepositStep(
            boolean pending,
            int currentCount,
            long elapsedMs,
            long timeoutMs,
            boolean interfaceReady) {
        if (pending && depositConfirmed(currentCount)) return DepositStep.CONFIRMED;
        if (pending && elapsedMs < timeoutMs) return DepositStep.WAIT;
        if (pending) return DepositStep.RETRY;
        return interfaceReady ? DepositStep.REQUEST : DepositStep.OPEN_INTERFACE;
    }

    static boolean tryDepositActions(BooleanSupplier inventoryAction, BooleanSupplier bankFallback) {
        return inventoryAction.getAsBoolean() || bankFallback.getAsBoolean();
    }

    static boolean hasTimedOut(long now, long enteredAt, long timeoutMs) {
        return now - enteredAt >= timeoutMs;
    }

    static boolean worldOnCooldown(long now, long cooldownUntil) {
        return now < cooldownUntil;
    }

    static boolean worldChangedAfterHopRequest(long requestedAt, int fromWorld, int currentWorld) {
        return requestedAt > 0L
                && fromWorld > 0
                && currentWorld > 0
                && currentWorld != fromWorld;
    }

    static boolean tryWorldHopActions(BooleanSupplier apiAction, BooleanSupplier widgetFallback) {
        return apiAction.getAsBoolean() || widgetFallback.getAsBoolean();
    }

    static long completedHopRequestAt(BooleanSupplier action, LongSupplier clock) {
        return action.getAsBoolean() ? clock.getAsLong() : 0L;
    }

    private boolean worldMenuVisible(APIContext ctx) {
        WidgetGroup worldSwitcher = ctx.widgets().get(WidgetID.WORLD_HOPPER_GROUP);
        return isWorldMenuVisible(
                ctx.world().isWorldMenuOpen(),
                worldSwitcher != null && worldSwitcher.isVisible());
    }

    static boolean isWorldMenuVisible(boolean apiMenuOpen, boolean worldWidgetVisible) {
        return apiMenuOpen || worldWidgetVisible;
    }

    static boolean worldWidgetMatches(String text, int targetWorld) {
        if (text == null || targetWorld <= 0) return false;
        String plainText = text.replaceAll("<[^>]*>", " ");
        for (String token : plainText.split("\\D+")) {
            if (!token.isEmpty() && Integer.parseInt(token) == targetWorld) return true;
        }
        return false;
    }

    static int hopRetryDelay(int consecutiveFailures) {
        return Math.min(1_500, 250 + Math.max(0, consecutiveFailures) * 250);
    }

    static boolean shouldIgnoreZeroCoins(boolean hopOrLoadGrace, boolean inventoryMayBeHidden) {
        return hopOrLoadGrace || inventoryMayBeHidden;
    }

    static boolean coinStopSnapshotUsable(int currentHatCount, int lastKnownHatCount) {
        return currentHatCount > 0 || lastKnownHatCount <= 0;
    }

    static boolean shouldRecordZeroCoinConfirmation(long now, long lastConfirmationAt, long intervalMs) {
        return lastConfirmationAt <= 0L || now - lastConfirmationAt >= intervalMs;
    }

    static boolean shouldLogAgain(long now, long lastLogAt, long intervalMs) {
        return now - lastLogAt >= intervalMs;
    }

    private void logWaiting(String message, Object value) {
        long now = System.currentTimeMillis();
        if (shouldLogAgain(now, lastWaitingLogAt, WAIT_LOG_INTERVAL_MS)) {
            getLogger().debug(message, value);
            lastWaitingLogAt = now;
        }
    }

    private int recoverTimedOutState(APIContext ctx, long now) {
        long timeout = stateTimeoutMs(state);
        if (!hasTimedOut(now, stateEnteredAt, timeout)) return -1;

        watchdogRecoveries++;
        getLogger().warn("WATCHDOG: {} exceeded {}ms; recovering", state, timeout);
        bluePurchasePending = false;
        blackPurchasePending = false;
        blueDepositPending = false;
        blackDepositPending = false;

        switch (state) {
            case CHECK -> stateEnteredAt = now;
            case WALK_SHOP -> setState(State.CHECK, "watchdog reset shop route");
            case OPEN_SHOP, BUY_BLUE, BUY_BLACK -> {
                if (ctx.store().isOpen()) ctx.store().close();
                forceTradeAfterHop = false;
                forcedShopCloseSent = false;
                setState(State.WALK_SHOP, "watchdog reset shop interaction");
            }
            case HOP_WORLD -> {
                ctx.widgets().closeInterface();
                stateEnteredAt = now;
                consecutiveHopFailures++;
            }
            case WAIT_WORLD -> setState(State.HOP_WORLD, "watchdog reset world hop");
            case WALK_DEPOSIT -> setState(State.CHECK, "watchdog reset deposit route");
            case OPEN_DEPOSIT, DEPOSIT_HATS -> {
                ctx.widgets().closeInterface();
                setState(State.WALK_DEPOSIT, "watchdog reset deposit interaction");
            }
        }
        return 300;
    }

    private long stateTimeoutMs(State currentState) {
        return switch (currentState) {
            case CHECK -> 30_000L;
            case WALK_SHOP, WALK_DEPOSIT -> 120_000L;
            case OPEN_SHOP, OPEN_DEPOSIT -> 20_000L;
            case BUY_BLUE, BUY_BLACK -> 12_000L;
            case HOP_WORLD -> 25_000L;
            case WAIT_WORLD -> 20_000L;
            case DEPOSIT_HATS -> 25_000L;
        };
    }

    private int coinStackSize(APIContext ctx) {
        ItemWidget coins = ctx.inventory().getItem("Coins");
        return coins != null && coins.isValid() ? coins.getStackSize() : -1;
    }

    private long observedCoinSpend(APIContext ctx, int beforeCoins) {
        int currentCoins = coinStackSize(ctx);
        if (beforeCoins < 0 || currentCoins < 0) return 0L;
        return Math.max(0, beforeCoins - currentCoins);
    }

    private void logStatsIfDue(long now) {
        if (shouldLogAgain(now, lastStatsLogAt, STATS_LOG_INTERVAL_MS)) {
            logStats(now);
            lastStatsLogAt = now;
        }
    }

    private void logStats(long now) {
        long runtimeSeconds = Math.max(0L, (now - scriptStartedAt) / 1_000L);
        getLogger().info(
                "STATS: runtime={}s | bought={} | deposited={} | hops={} | gp spent={} | "
                        + "purchase failures={} | deposit failures={} | hop failures={} | recoveries={}",
                runtimeSeconds,
                hatsBought,
                hatsDeposited,
                successfulHops,
                gpSpent,
                failedPurchaseRequests,
                failedDepositRequests,
                failedHopRequests,
                watchdogRecoveries);
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
        long cooldownUntil = failedWorldUntil.getOrDefault(world.getId(), 0L);
        if (worldOnCooldown(System.currentTimeMillis(), cooldownUntil)) return false;

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
            stateEnteredAt = System.currentTimeMillis();
            lastWaitingLogAt = 0L;
            if (next == State.BUY_BLUE) shopOpenedAt = System.currentTimeMillis();
            if (next != State.OPEN_DEPOSIT && next != State.DEPOSIT_HATS) {
                depositOpenRequestedAt = 0L;
            }
        }
    }

    static String formatRuntime(long runtimeMillis) {
        long totalSeconds = Math.max(0L, runtimeMillis) / 1_000L;
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    static long hatsPerHour(int totalHats, long runtimeMillis) {
        if (totalHats <= 0 || runtimeMillis <= 0L) return 0L;
        return Math.round(totalHats * 3_600_000.0 / runtimeMillis);
    }

    @Override
    protected void onPaint(PaintContext paint, APIContext ctx) {
        long runtimeMillis = Math.max(0L, System.currentTimeMillis() - scriptStartedAt);
        float x = 12f;
        float y = 48f;
        float width = 238f;
        float height = 174f;
        float lineY = y + 27f;
        Font titleFont = new Font("SansSerif", Font.BOLD, 14);
        Font valueFont = new Font("SansSerif", Font.PLAIN, 13);

        paint.fill(new RoundRectangle2D.Float(x, y, width, height, 7f, 7f),
                new Color(12, 15, 18, 218));
        paint.draw(new RoundRectangle2D.Float(x, y, width, height, 7f, 7f),
                new Color(190, 46, 46, 235), 2);
        paint.drawText("Wizard Hat Buyer", x + 12f, lineY, Color.WHITE, titleFont);

        lineY += 23f;
        paint.drawText("Runtime: " + formatRuntime(runtimeMillis), x + 12f, lineY,
                new Color(220, 224, 229), valueFont);
        lineY += 19f;
        paint.drawText("Blue hats: " + blueHatsBought, x + 12f, lineY,
                new Color(91, 168, 255), valueFont);
        lineY += 19f;
        paint.drawText("Black hats: " + blackHatsBought, x + 12f, lineY,
                new Color(205, 209, 215), valueFont);
        lineY += 19f;
        paint.drawText("Total: " + hatsBought + "  |  Per hour: "
                        + hatsPerHour(hatsBought, runtimeMillis),
                x + 12f, lineY, Color.WHITE, valueFont);
        lineY += 19f;
        paint.drawText("Hops: " + successfulHops + "  |  GP spent: " + gpSpent,
                x + 12f, lineY, new Color(245, 204, 92), valueFont);
        lineY += 19f;
        paint.drawText("State: " + state, x + 12f, lineY,
                new Color(148, 226, 162), valueFont);
    }

    @Override
    protected void onStop() {
        logStats(System.currentTimeMillis());
        getLogger().info("Wizard Hat Buyer stopped");
    }
}
