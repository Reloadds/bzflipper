# Bazaar Flipper (non-headless, Fabric mod)

A **watchable** Bazaar flipping macro for Hypixel SkyBlock. It runs inside your
real Minecraft client and drives the Bazaar GUI by clicking slots, so you can
see exactly what it does. Once you trust the logic, the same decision code ports
to a **headless** Mineflayer bot (see roadmap).

> ⚠️ Automating the Bazaar breaks Hypixel's rules (Rule #4, exploiting) and can
> get an account **banned**. Use a throwaway alt. This is a learning project.

## Requirements

- **JDK 21** (Minecraft 1.21.x needs Java 21)
- Minecraft **1.21.11** + **Fabric Loader** + **Fabric API**
- IntelliJ IDEA (recommended) or a terminal with Gradle

## Build & run

1. Confirm the version strings in `gradle.properties` against
   <https://fabricmc.net/develop> (pick Minecraft 1.21.11) and the Fabric API
   version on Modrinth. These change over time.
2. Generate the Gradle wrapper jar if it's missing: `gradle wrapper`
   (or just open the folder in IntelliJ — it does this for you).
3. Dev run (launches a dev client with the mod loaded):
   ```
   ./gradlew runClient
   ```
4. Build a distributable jar for your normal launcher:
   ```
   ./gradlew build      # -> build/libs/bzflipper-0.1.0.jar
   ```
   Drop that jar into `.minecraft/mods` alongside Fabric API.

## Using it

- Join Hypixel, go to SkyBlock, **open the Bazaar** to the main category screen.
- Press **`\`** (backslash) to start one cycle. Press **`End`** to panic-stop.
- The HUD (top-left) shows state, prices read, margin, and orders placed.
- **`dryRun` is `true` by default** in `config/bzflipper.json` — it will only
  navigate and read/report prices, never place an order. Watch it first, then
  flip `dryRun` to `false` when you're comfortable.

## Configuration — `.minecraft/config/bzflipper.json`

- `targets`: which items to flip. `category` / `product` must match the Bazaar's
  on-screen text exactly.
  - `amount`: units per buy order. `0` = the "buy 1 stack" preset (64). Any
    value `> 0` is typed into the **Custom Amount** sign popup.
- `minMargin`: skip a flip unless net margin (after `taxFraction`) clears this.
- `useCustomPrice`: if `true`, place at our exact computed price (top buy + 0.1 /
  lowest sell − 0.1) via the **Custom Price** sign popup instead of a preset
  button.
- `actionDelayTicks` / `actionJitterTicks`: pacing (20 ticks = 1s). Slower =
  more human.
- `dryRun`: plan-only mode — reads purse, ranks flips, shows the HUD, but never
  places an order. Great for a first safe watch.

### Purse-aware spending
- `coinReserve`: never let the purse drop below this.
- `orderBudgetFraction`: fraction of spendable purse (`purse − reserve`) per order.
- `maxUnitsPerOrder`: hard cap on units per order (Bazaar's own limit is 71,680).
- `maxOpenOrders`: how many concurrent buy orders to keep working.

Because Hypixel escrows coins the moment a buy order is placed, the live purse
read naturally reflects committed coins — so it stops opening orders when funds
run low, no separate bookkeeping needed.

### Auto flip sourcing (Hypixel Bazaar API)
Set `useApiFlips: true` to pick the best flips live instead of the fixed
`targets` list. Data comes from `https://api.hypixel.net/v2/skyblock/bazaar`
(no key), fetched on a background thread and ranked by **net margin × liquidity**.
- `apiRefreshSeconds`, `apiMinMargin`, `apiMinWeeklyVolume`, `apiMaxUnitPrice`.

> Note: Coflnet's public bazaar endpoints track *your own* orders/flips (and need
> a key); they aren't a "best flips now" feed. The official Hypixel Bazaar API is
> the correct live source and is what Coflnet itself ingests.

### Live coins/hour
The HUD shows total realized profit, a whole-session rate, and a rolling
10-minute "recent" rate that reacts to how it's flipping right now. Purse is read
from the SkyBlock sidebar (`PurseReader`); if it reads `—`, the sidebar wording
changed — adjust the regex there.

### Known tuning points (need live confirmation)
- **Manage-Orders anchors** in `BazaarStrings` (`LORE_FILLED`, `LORE_OUTBID`,
  `LORE_SIDE_BUY/SELL`, `BTN_CANCEL_ORDER`).
- **Search navigation** (`BTN_SEARCH`) — the portfolio reaches items via the
  Bazaar search sign.
- **Tag→name mapping** for API items is best-effort; items whose Bazaar name
  differs from the auto-derived name won't be found via search — add them as
  explicit `targets` (with `useApiFlips: false`) if needed.

### Tuning the GUI matching

The macro finds buttons by **name text**, all collected in
`core/BazaarStrings.java`. Hypixel's exact wording ("Buy 1 stack", "Custom
Amount", etc.) is what you'll most likely need to tweak — open each Bazaar
screen and make the strings match what you see. Nothing else should need
touching to get navigation working.

## Architecture (built for the headless port)

```
core/PriceMath.java     <- PURE logic (no Minecraft). The reusable brain.
core/BazaarStrings.java  <- all Hypixel UI text in one place.
config/                  <- JSON config (targets, pacing, dryRun).
mc/GuiHelper.java        <- read/click chest GUIs  ── the swappable "driver"
mc/BazaarMacro.java      <- tick-driven state machine (uses GuiHelper)
hud/Overlay.java         <- on-screen status
BzFlipper.java           <- entrypoint: keybinds + tick + HUD
```

The seam is `GuiHelper` + the state machine's actions. For headless:

## Roadmap

- [x] v0.1: navigate → read prices → optional single buy order.
- [x] v0.2 (this): full round-trip loop — buy → monitor → claim → sell →
      monitor → claim → repeat, with **undercut detection + auto-relist** on
      both sides. Manage-Orders anchors in `BazaarStrings` need live tuning.
- [x] v0.3: custom amount / exact price via **sign-input handling**
      (Mixin accessor on the sign screen). Set `amount > 0` per target and/or
      `useCustomPrice: true`. If sign input misbehaves, check the field name in
      `mixin/AbstractSignEditScreenAccessor.java` (`messages` vs `text`).
- [x] v0.4 (this): **multi-slot portfolio** + **purse-aware sizing** + **live
      coins/hr HUD** + **auto flip sourcing** from the Hypixel Bazaar API.
- [ ] Headless Mineflayer port (reuses PriceMath + BazaarApi + the state machine).
- [ ] **Headless (Mineflayer, 1.21.11):** reimplement the `GuiHelper` seam
      against Mineflayer's `bot.openContainer` / window events; port
      `PriceMath` + the state machine logic 1:1. Attach `prismarine-viewer`
      to watch it, then disable the viewer to go fully headless.
```
