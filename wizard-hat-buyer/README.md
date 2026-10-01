# EpicBot Wizard Hat Buyer

Separate F2P EpicBot NXT script for Betty's Magic Emporium in Port Sarim.

Flow:
- Keeps Coins in inventory slot 1.
- Buys 1 Blue wizard hat and 1 black Wizard hat from Betty whenever stocked.
- Finishes both colour checks before hopping.
- Hops only to normal F2P worlds and excludes PvP/high-risk/special worlds.
- At 26+ hats (or a full inventory), walks to the Port Sarim deposit box.
- Deposits ONLY wizard hats; Coins stay in the inventory.
- Returns to Betty and repeats.
- Normal stop condition: Coins == 0.
- Empty stock, one colour missing, failed shop opening, hop failure, walking failure, and deposit interaction failure retry/recover instead of stopping.

The shop is centred around Betty at 3014,3258. The deposit route targets the Port Sarim Entrana-monks deposit area around 3045,3235.

Build: GitHub Actions workflow `.github/workflows/build-wizard-hat-buyer.yml` uses the official EpicBot public script template and Java 21.
