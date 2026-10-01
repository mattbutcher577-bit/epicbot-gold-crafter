package com.wizardhatbuyer;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.World;
import com.epicbot.api.shared.model.WorldType;
import com.epicbot.api.shared.script.LoopScript;
import com.epicbot.api.shared.script.ScriptManifest;

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
    // The black hat is exposed as "Wizard hat" in current OSRS shop data.
    // "Black wizard hat" is kept as an alias in case the client/API uses that label.
    private static final String BLACK_HAT = "Wizard hat";
    private static final String BLACK_HAT_ALIAS = "Black wizard hat";

    // Coins occupy slot 0, leaving 27 inventory slots.
    // We finish each WORLD purchase cycle before checking this threshold, so an odd
    // incoming hat count can legitimately become 27 after buying both colours.
    private static final int DEPOSIT_THRESHOLD = 26;

    private final Deque<Integer> recentWorlds = new ArrayDeque<>();
    private State state = State.CHECK;
    private int hopFromWorld = -1;

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
        return true;
    }

    @Override
    protected int loop() {
        APIContext ctx = getAPIContext();

        if (ctx.localPlayer().get() == null) {
            return 1000;
        }

        // User requirement: the ONLY normal stop condition is Coins == 0.
        if (ctx.inventory().getCount("Coins") <= 0) {
            getLogger().info("No Coins remain - stopping Wizard Hat Buyer");
            ctx.script().stop("Out of Coins");
            return 1000;
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
        if (hatCount(ctx) >= DEPOSIT_THRESHOLD) {
            setState(State.WALK_DEPOSIT, "inventory ready to deposit");
        } else {
            setState(State.WALK_SHOP, "need more hats");
        }
        return 200;
    }

    private int walkShop(APIContext ctx) {
        if (ctx.store().isOpen()) {
            setState(State.BUY_BLUE, "shop already open");
            return 200;
        }

        NPC betty = findBetty(ctx);
        if (betty != null && betty.tileDistanceTo(ctx) <= 6) {
            setState(State.OPEN_SHOP, "Betty in range");
            return 200;
        }

        getLogger().info("Walking to Betty's Magic Emporium");
        ctx.webWalking().walkTo(BETTY_SHOP);
        return 650;
    }

    private int openShop(APIContext ctx) {
        if (hatCount(ctx) >= DEPOSIT_THRESHOLD) {
            setState(State.WALK_DEPOSIT, "deposit threshold reached");
            return 200;
        }

        if (ctx.store().isOpen()) {
            setState(State.BUY_BLUE, "shop opened");
            return 200;
        }

        NPC betty = findBetty(ctx);
        if (betty == null) {
            getLogger().warn("SHOP: Betty not visible yet; retrying nearby");
            setState(State.WALK_SHOP, "Betty not found");
            return 500;
        }

        getLogger().info("SHOP: Betty found at distance {} - opening trade", betty.tileDistanceTo(ctx));

        // Different client builds can expose the trade action slightly differently.
        if (betty.interact("Trade") || betty.interact("Trade-with")) {
            return 700;
        }

        // If the direct action fails, walk one step closer and retry rather than getting stuck.
        if (betty.tileDistanceTo(ctx) > 2) {
            ctx.webWalking().walkTo(BETTY_SHOP);
            return 450;
        }

        getLogger().warn("SHOP: Betty interaction failed; retrying");
        return 500;
    }

    private int buyBlue(APIContext ctx) {
        if (!ctx.store().isOpen()) {
            setState(State.OPEN_SHOP, "shop closed before blue purchase");
            return 350;
        }

        if (ctx.inventory().isFull()) {
            ctx.store().close();
            setState(State.WALK_DEPOSIT, "inventory full");
            return 300;
        }

        int stock = ctx.store().getCount(BLUE_HAT);
        if (stock > 0) {
            int before = ctx.inventory().getCount(BLUE_HAT);
            if (ctx.store().buyOne(BLUE_HAT)) {
                getLogger().info("BUY: {} | before={} | world={}",
                        BLUE_HAT, before, ctx.world().getCurrent());
                return 650;
            }
            return 400;
        }

        setState(State.BUY_BLACK, "blue checked for this world");
        return 150;
    }

    private int buyBlack(APIContext ctx) {
        if (!ctx.store().isOpen()) {
            setState(State.OPEN_SHOP, "shop closed before black purchase");
            return 350;
        }

        if (ctx.inventory().isFull()) {
            ctx.store().close();
            setState(State.WALK_DEPOSIT, "inventory full");
            return 300;
        }

        String blackName = blackStoreName(ctx);
        if (blackName != null) {
            int before = blackHatCount(ctx);
            if (ctx.store().buyOne(blackName)) {
                getLogger().info("BUY: {} | before={} | world={}",
                        blackName, before, ctx.world().getCurrent());
                return 650;
            }
            return 400;
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
        return 300;
    }

    private int hopWorld(APIContext ctx) {
        if (ctx.store().isOpen()) {
            ctx.store().close();
            return 250;
        }

        int current = ctx.world().getCurrent();
        rememberWorld(current);
        hopFromWorld = current;

        boolean requested = ctx.world().hop(world -> isSafeF2PWorld(world, current));
        if (requested) {
            getLogger().info("HOP: leaving F2P world {}", current);
            setState(State.WAIT_WORLD, "hop requested");
            return 1000;
        }

        // Hop failure is explicitly recoverable.
        getLogger().warn("HOP: no safe F2P world selected; retrying");
        return 1200;
    }

    private int waitWorld(APIContext ctx) {
        int current = ctx.world().getCurrent();
        if (current != hopFromWorld && current > 0) {
            getLogger().info("HOP: arrived world {}", current);
            setState(State.WALK_SHOP, "new world loaded");
            return 500;
        }

        return 750;
    }

    private int walkDeposit(APIContext ctx) {
        if (hatCount(ctx) <= 0) {
            setState(State.WALK_SHOP, "nothing to deposit");
            return 200;
        }

        if (ctx.bank().isOpen()) {
            setState(State.DEPOSIT_HATS, "deposit interface already open");
            return 200;
        }

        SceneObject box = findDepositBox(ctx);
        if (box != null && box.tileDistanceTo(ctx) <= 6) {
            setState(State.OPEN_DEPOSIT, "deposit box in range");
            return 200;
        }

        getLogger().info("Walking to Port Sarim boat-pier deposit box by Entrana ferry | hats={}", hatCount(ctx));
        ctx.webWalking().walkTo(PORT_SARIM_DEPOSIT);
        return 650;
    }

    private int openDeposit(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            setState(State.DEPOSIT_HATS, "deposit box opened");
            return 200;
        }

        SceneObject box = findDepositBox(ctx);
        if (box == null) {
            setState(State.WALK_DEPOSIT, "deposit box not found");
            return 500;
        }

        if (box.interact("Deposit")) {
            return 650;
        }

        // Deposit interaction problems are recovery/retry conditions, never stop conditions.
        return 500;
    }

    private int depositHats(APIContext ctx) {
        if (!ctx.bank().isOpen()) {
            setState(State.OPEN_DEPOSIT, "deposit interface closed");
            return 350;
        }

        // Deposit ONLY the wizard hats. Coins are deliberately never included.
        if (ctx.inventory().getCount(BLUE_HAT) > 0) {
            getLogger().info("DEPOSIT: {} x{}", BLUE_HAT, ctx.inventory().getCount(BLUE_HAT));
            ctx.bank().depositAll(BLUE_HAT);
            return 500;
        }

        if (ctx.inventory().getCount(BLACK_HAT) > 0) {
            getLogger().info("DEPOSIT: {} x{}", BLACK_HAT, ctx.inventory().getCount(BLACK_HAT));
            ctx.bank().depositAll(BLACK_HAT);
            return 500;
        }

        if (ctx.inventory().getCount(BLACK_HAT_ALIAS) > 0) {
            getLogger().info("DEPOSIT: {} x{}", BLACK_HAT_ALIAS, ctx.inventory().getCount(BLACK_HAT_ALIAS));
            ctx.bank().depositAll(BLACK_HAT_ALIAS);
            return 500;
        }

        ctx.bank().close();
        keepCoinsInFirstSlot(ctx);
        setState(State.WALK_SHOP, "hats deposited; coins retained");
        return 450;
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

    private String blackStoreName(APIContext ctx) {
        if (ctx.store().getCount(BLACK_HAT) > 0) return BLACK_HAT;
        if (ctx.store().getCount(BLACK_HAT_ALIAS) > 0) return BLACK_HAT_ALIAS;
        return null;
    }

    private int blackHatCount(APIContext ctx) {
        return ctx.inventory().getCount(BLACK_HAT)
                + ctx.inventory().getCount(BLACK_HAT_ALIAS);
    }

    private int hatCount(APIContext ctx) {
        return ctx.inventory().getCount(BLUE_HAT) + blackHatCount(ctx);
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
                && !types.contains(WorldType.LAST_MAN_STANDING)
                && !types.contains(WorldType.QUEST_SPEEDRUNNING)
                && !types.contains(WorldType.SKILL_TOTAL)
                && !types.contains(WorldType.BETA_WORLD)
                && !types.contains(WorldType.TOURNAMENT_WORLD)
                && !types.contains(WorldType.FRESH_START_WORLD)
                && !types.contains(WorldType.SEASONAL)
                && !types.contains(WorldType.NOSAVE_MODE);
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
        }
    }

    @Override
    protected void onStop() {
        getLogger().info("Wizard Hat Buyer stopped");
    }
}
