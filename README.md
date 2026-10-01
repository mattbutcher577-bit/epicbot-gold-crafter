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
