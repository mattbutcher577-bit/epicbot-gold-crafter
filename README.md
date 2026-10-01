# EpicBot Gold Crafter

EpicBot Java 21 script for an Edgeville gold-jewellery crafting loop with Grand Exchange restocking/selling.

## Quick build on Windows

Double-click `download-and-build.cmd` after cloning this repository, or run:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

The build helper downloads EpicBot's official public script template, injects this project's source into the template, builds with Java 21, and copies the resulting JAR into `release\`.

## Script flow

- Edgeville bank
- Deposit finished/unrelated inventory
- Withdraw the selected mould plus an exact crafting batch
- Walk to Edgeville furnace
- Smelt and Make-All
- Bank the output
- When supplies are short or the output stockpile reaches the sell threshold:
  - walk to the Grand Exchange
  - sell finished jewellery
  - buy only the missing amount of gold bars/gems
  - buy the mould if missing
  - collect to bank
  - return to Edgeville

The default product is **Gold necklace**. You can change `DEFAULT_PRODUCT` in `GoldCrafter.java`.

## Requirements

- Windows
- Git
- Java 21
- EpicBot

The build helper can install Temurin Java 21 with winget when it is missing.

EpicBot developer docs: https://docs.epicbot.com/docs/developer/
EpicBot API docs: https://api.epicbot.com/javadoc/

GitHub Actions compiles the script against the current public EpicBot template on every push.


## Automatic profit selection

With no product argument, the script reads the account's real Crafting level and compares every jewellery recipe the account can make. It estimates aggressive buy prices, sale proceeds after the current 2% GE tax, and batch profit, then selects the highest positive estimated batch profit.

Supported level gates:

- Gold ring 5, necklace 6, amulet (u) 8
- Sapphire ring 20, necklace 22, amulet (u) 24
- Emerald ring 27, necklace 29, amulet (u) 31
- Ruby ring 34, necklace 40, amulet (u) 50
- Diamond ring 43, necklace 56, amulet (u) 70

Passing a product name as a script argument locks the script to that product instead.

## Wilderness mule relay

Run \`mule-now.cmd\` while two or more Gold Crafter workers are running on the same Windows account.

The workers share state under:

\`\`\`
%USERPROFILE%\.epicbot\gold-crafter\mule
\`\`\`

The relay mirrors the old Gold Profit Crafter flow:

1. Every worker liquidates finished jewellery.
2. Every remaining GP stack is withdrawn from the bank.
3. Workers hop to world 698.
4. They rendezvous behind the Varrock sawmill at tile 3302,3555 (Wilderness level 5).
5. The coordinator checks that adjacent workers are within 5 combat levels.
6. Exactly one loser/collector pair acts per round.
7. The loser attacks first; the collector retaliates.
8. The collector takes the dropped Coins.
9. The next round starts only after the collector's coin stack is verified to have increased.
10. After the last verified pickup, the scripts stop and the combined GP remains on the final survivor.

The script attempts to expose the Attack option by adjusting PK Skull Prevention / Player Attack Options through the Settings interface. This part is deliberately verified at runtime: if the Attack action still cannot be exposed, the relay waits rather than pretending the transfer succeeded.


After the final verified pickup, the final survivor stays in the Wilderness and requests a normal logout immediately. It does not walk to Lumbridge or another bank first.
