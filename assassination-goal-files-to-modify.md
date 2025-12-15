# Assassination Goal - Files to Modify

## Summary

This document lists all files that will be **created** or **modified** to implement the Assassination goal.

---

## 1. Files to CREATE (New Files)

### 1.1 Main Goal Class
**File:** `src/main/java/net/slipcor/pvparena/goals/GoalAssassination.java`
- **Type:** New Java class file
- **Purpose:** Main implementation of the Assassination goal
- **Extends:** `ArenaGoal` (or potentially `AbstractTeamKillGoal` if we want team-based lives)
- **Estimated Size:** ~300-400 lines
- **Key Features:**
  - VIP designation system (random or configurable)
  - VIP tracking: `Map<ArenaTeam, ArenaPlayer>`
  - Special death handling for VIPs
  - Visual indicators (glowing effect, name tag)
  - Win condition checking

---

## 2. Files to MODIFY (Existing Files)

### 2.1 Goal Registration
**File:** `src/main/java/net/slipcor/pvparena/loadables/ArenaGoalManager.java`
- **Line:** ~39-57 (in `addInternalGoals()` method)
- **Change:** Add `this.addInternalLoadable(GoalAssassination.class);`
- **Purpose:** Register the new goal so it can be loaded by the plugin
- **Impact:** Low - Single line addition

**Example:**
```java
private void addInternalGoals() {
    this.addInternalLoadable(GoalBlockDestroy.class);
    // ... existing goals ...
    this.addInternalLoadable(GoalTeamPlayerLives.class);
    this.addInternalLoadable(GoalAssassination.class);  // <-- NEW LINE
}
```

---

### 2.2 Configuration Constants (Optional but Recommended)
**File:** `src/main/java/net/slipcor/pvparena/core/Config.java`
- **Line:** ~220-255 (in the CFG enum, around other GOAL_ constants)
- **Change:** Add config constants for Assassination goal settings
- **Purpose:** Define configurable options for the goal
- **Impact:** Low - 2-3 new enum entries

**Example additions:**
```java
// Around line 251, after GOAL_TLIVES_LIVES
GOAL_ASSASSINATION_VIP_LIVES("goal.assassination.viplives", 1, "Assassination"),
GOAL_ASSASSINATION_VIP_GLOWING("goal.assassination.vipglowing", true, "Assassination"),
GOAL_ASSASSINATION_VIP_RANDOM("goal.assassination.viprandom", true, "Assassination"),
```

**Config Options:**
- `goal.assassination.viplives` - Number of lives for VIP (default: 1)
- `goal.assassination.vipglowing` - Enable glowing effect for VIP (default: true)
- `goal.assassination.viprandom` - Randomly select VIP or use first player (default: true)
- `goal.assassination.vipdisconnecteliminate` - Eliminate team if VIP disconnects (default: true, false = allow rejoin)

---

### 2.3 Language Strings (Optional but Recommended)
**File:** `src/main/java/net/slipcor/pvparena/core/Language.java`
- **Line:** ~427-450 (in the MSG enum, around other GOAL_ messages)
- **Change:** Add language strings for Assassination goal messages
- **Purpose:** Provide user-friendly messages for the goal
- **Impact:** Low - 3-5 new enum entries

**Example additions:**
```java
// Around line 450, after other GOAL_ messages
GOAL_ASSASSINATION_VIP_SELECTED("goal.assassination.vipselected", "&e%1% is the VIP for team %2%!"),
GOAL_ASSASSINATION_VIP_KILLED("goal.assassination.vipkilled", "&cThe VIP of team %1% has been eliminated!"),
GOAL_ASSASSINATION_TEAM_ELIMINATED("goal.assassination.teameliminated", "&cTeam %1% has been eliminated! Their VIP was killed."),
```

**Language Keys:**
- `goal.assassination.vipselected` - Message when VIP is selected
- `goal.assassination.vipkilled` - Message when VIP is killed
- `goal.assassination.teameliminated` - Message when team is eliminated
- `goal.assassination.vipdisconnected` - Message when VIP disconnects
- `goal.assassination.viprejoined` - Message when VIP rejoins

---

## 3. Optional Files (Documentation)

### 3.1 Goals List Documentation
**File:** `doc/goals.md`
- **Line:** ~7-26 (in the goals table)
- **Change:** Add Assassination to the goals table
- **Purpose:** Document the new goal in the plugin documentation
- **Impact:** Low - Single table row addition

**Example:**
```markdown
| [Assassination](goals/assassination.md) | Protect your VIP, kill enemy VIP | team | none |
```

---

### 3.2 Goal Documentation
**File:** `doc/goals/assassination.md` (NEW FILE)
- **Type:** New Markdown documentation file
- **Purpose:** Detailed documentation for the Assassination goal
- **Content:** Setup instructions, config options, gameplay description
- **Impact:** Low - Documentation only

---

## 4. Files Summary Table

| File | Type | Priority | Lines Changed | Complexity |
|------|------|----------|---------------|------------|
| `GoalAssassination.java` | **CREATE** | **REQUIRED** | ~300-400 new | Medium |
| `ArenaGoalManager.java` | **MODIFY** | **REQUIRED** | +1 line | Low |
| `Config.java` | **MODIFY** | Recommended | +2-3 lines | Low |
| `Language.java` | **MODIFY** | Recommended | +3-5 lines | Low |
| `doc/goals.md` | **MODIFY** | Optional | +1 line | Low |
| `doc/goals/assassination.md` | **CREATE** | Optional | ~50-100 new | Low |

---

## 5. Implementation Order

### Phase 1: Core Implementation (Required)
1. ✅ Create `GoalAssassination.java` with basic structure
2. ✅ Modify `ArenaGoalManager.java` to register the goal
3. ✅ Test that goal loads and appears in `/pa <arena> goal` command

### Phase 2: Configuration (Recommended)
4. ✅ Add config constants to `Config.java`
5. ✅ Use config values in `GoalAssassination.java`
6. ✅ Test config loading and defaults

### Phase 3: User Experience (Recommended)
7. ✅ Add language strings to `Language.java`
8. ✅ Use language strings in `GoalAssassination.java`
9. ✅ Test messages display correctly

### Phase 4: Documentation (Optional)
10. ✅ Update `doc/goals.md`
11. ✅ Create `doc/goals/assassination.md`
12. ✅ Test documentation completeness

---

## 6. Key Implementation Details

### 6.1 VIP Selection
- **Method:** Random selection from team members (if `viprandom = true`)
- **Alternative:** First player to join team (if `viprandom = false`)
- **Timing:** On arena start (`parseStart()` method)
- **Storage:** `Map<ArenaTeam, ArenaPlayer> vipMap`

### 6.2 Visual Indicators
- **Glowing Effect:** `PotionEffectType.GLOWING` (if enabled in config)
- **Name Tag:** Could use scoreboard team prefix/suffix
- **Application:** In `parseStart()` and `lateJoin()` methods
- **Removal:** In `disconnect()` and `parseLeave()` methods

### 6.3 Death Handling
- **Override:** `commitPlayerDeath()` method
- **Check:** If dead player is VIP
- **Action:** If VIP dies, eliminate entire team
- **Respawn:** VIP cannot respawn (or limited respawns based on config)

### 6.4 Win Condition
- **Override:** `checkEnd()` method
- **Logic:** Check if any team has no VIP alive
- **Return:** `true` if only one team has VIP remaining
- **Edge Cases:** Must check for offline VIPs, removed VIPs, and VIPs with null players

### 6.5 Disconnection Handling (CRITICAL)
- **Override:** `disconnect(ArenaPlayer)` method
- **Purpose:** Handle VIP disconnection scenarios
- **Logic:** 
  - Check if disconnecting player is VIP
  - If rejoin allowed: Mark VIP as offline, wait for rejoin
  - If rejoin disabled: Eliminate team immediately
- **Override:** `parseLeave(ArenaPlayer)` method
- **Purpose:** Clean up VIP tracking when player leaves
- **Logic:** Remove VIP from map, check if team should be eliminated

### 6.6 Rejoin Handling
- **Override:** `lateJoin(ArenaPlayer)` method
- **Purpose:** Handle VIP rejoining after disconnect
- **Logic:**
  - Check if rejoining player was VIP
  - Restore VIP status and visual indicators
  - Re-apply glowing effect if enabled
- **Alternative:** If team has no VIP, assign late joiner as VIP

---

## 7. Edge Cases & Critical Scenarios

### 7.1 VIP Disconnection Scenarios

**CRITICAL:** The Assassination goal must handle VIP disconnections properly. Here are the key scenarios:

#### Scenario 1: VIP Disconnects During Match
**Current Behavior (from codebase analysis):**
- When a player disconnects, `PlayerListener.onPlayerQuit()` is called
- If `JOIN_ALLOW_REJOIN` is enabled and player status is `FIGHT` or `DEAD`, player status is set to `OFFLINE`
- `arena.getGoal().checkEnd()` is called to see if game should end
- If `checkEnd()` returns true, player is force-removed and arena ends
- Otherwise, player can rejoin later

**Required Implementation:**
- Override `disconnect(ArenaPlayer)` method to handle VIP disconnection
- Override `parseLeave(ArenaPlayer)` method to clean up VIP tracking
- Override `checkEnd()` to check if VIP is offline/dead (not just dead)
- **Decision Point:** Should VIP disconnection = team elimination?

**Recommended Behavior:**
```java
// Option A: VIP disconnect = team elimination (strict)
if (vipMap.get(team).equals(disconnectingPlayer)) {
    eliminateTeam(team); // Treat as VIP death
}

// Option B: VIP disconnect = allow rejoin (lenient)
if (vipMap.get(team).equals(disconnectingPlayer)) {
    if (arena.getConfig().getBoolean(CFG.JOIN_ALLOW_REJOIN)) {
        // Mark VIP as offline, allow rejoin
        // Check in checkEnd() if VIP is offline/dead
    } else {
        eliminateTeam(team); // No rejoin = elimination
    }
}
```

#### Scenario 2: VIP Rejoins After Disconnect
**Required Implementation:**
- Override `lateJoin(ArenaPlayer)` method
- Check if rejoining player is the VIP
- Restore VIP status and visual indicators
- Re-apply glowing effect if enabled

#### Scenario 3: VIP Removed by Admin/Plugin
**Required Implementation:**
- `parseLeave()` is called when player is removed
- Check if removed player is VIP
- If VIP removed, eliminate team (same as death)

#### Scenario 4: VIP Goes Offline (Network Issues)
**Current System:**
- Player status becomes `OFFLINE` (not `LOST`)
- `checkEnd()` should check for offline VIPs
- Need to distinguish between offline (can rejoin) vs dead (eliminated)

**Required Implementation:**
```java
@Override
public boolean checkEnd() {
    // Check if any team has no active VIP
    for (ArenaTeam team : arena.getTeams()) {
        ArenaPlayer vip = vipMap.get(team);
        if (vip == null) {
            continue; // No VIP assigned (shouldn't happen)
        }
        
        // VIP is eliminated if:
        // 1. Status is LOST (dead, no respawns)
        // 2. Status is not FIGHT/DEAD and rejoin not allowed
        // 3. Player is null (removed from game)
        
        if (vip.getStatus() == PlayerStatus.LOST) {
            return true; // VIP eliminated, game should end
        }
        
        if (vip.getStatus() == PlayerStatus.OFFLINE) {
            if (!arena.getConfig().getBoolean(CFG.JOIN_ALLOW_REJOIN)) {
                return true; // No rejoin allowed, VIP offline = eliminated
            }
            // Rejoin allowed, wait for VIP to return
        }
        
        if (vip.getPlayer() == null) {
            return true; // VIP removed from game
        }
    }
    
    // Count teams with active VIPs
    long teamsWithActiveVIP = arena.getTeams().stream()
        .filter(team -> {
            ArenaPlayer vip = vipMap.get(team);
            if (vip == null) return false;
            
            // Active VIP = FIGHT or DEAD status (can respawn)
            return vip.getStatus() == PlayerStatus.FIGHT || 
                   vip.getStatus() == PlayerStatus.DEAD;
        })
        .count();
    
    return teamsWithActiveVIP <= 1; // Only one team left with active VIP
}
```

### 7.2 VIP Selection Edge Cases

#### Scenario 5: VIP Selected, Then Player Leaves Before Match Starts
**Required Implementation:**
- VIP selection happens in `parseStart()`
- If player leaves before start, reselect VIP in `parseLeave()`
- Or delay VIP selection until match actually starts

#### Scenario 6: All Team Members Leave Except VIP
**Required Implementation:**
- Check in `checkEnd()` if VIP is the only team member
- Game can continue (VIP alone vs other teams)
- Or auto-eliminate if team has no other members (configurable)

#### Scenario 7: VIP Joins Late (After Match Started)
**Required Implementation:**
- Override `lateJoin(ArenaPlayer)` method
- If team has no VIP yet, assign this player as VIP
- If team already has VIP, new player is regular member

### 7.3 Visual Indicator Edge Cases

#### Scenario 8: VIP Glowing Effect Removed
**Required Implementation:**
- Re-apply effect periodically (runnable)
- Re-apply on respawn
- Re-apply on rejoin

#### Scenario 9: VIP Name Tag Not Visible
**Required Implementation:**
- Use scoreboard team prefix/suffix
- Or use boss bar
- Or use action bar messages

### 7.4 Death Handling Edge Cases

#### Scenario 10: VIP Dies But Has Multiple Lives
**Required Implementation:**
- Check config: `GOAL_ASSASSINATION_VIP_LIVES`
- If VIP lives > 1, allow respawn
- Only eliminate team when VIP lives reach 0

#### Scenario 11: VIP Dies to Team Kill
**Required Implementation:**
- Check if killer is on same team
- If team kill allowed, treat as normal death
- If team kill not allowed, might need special handling

#### Scenario 12: VIP Dies to Environmental Damage
**Required Implementation:**
- Same as normal death
- Check VIP lives, eliminate if 0

---

## 8. Testing Checklist

### 8.1 Basic Functionality
- [ ] Goal appears in `/pa <arena> goal` command list
- [ ] Goal can be set: `/pa <arena> goal set Assassination`
- [ ] VIP is selected on arena start
- [ ] VIP has glowing effect (if enabled)
- [ ] Regular player death allows respawn
- [ ] VIP death eliminates team (when lives = 0)
- [ ] Game ends when all enemy VIPs are dead
- [ ] Config options work correctly
- [ ] Language messages display correctly
- [ ] Goal resets properly on arena reset

### 8.2 Disconnection Scenarios (CRITICAL)
- [ ] **VIP disconnects during match** → Team eliminated (or marked offline if rejoin enabled)
- [ ] **VIP disconnects, rejoin enabled** → VIP can rejoin, status restored
- [ ] **VIP disconnects, rejoin disabled** → Team eliminated immediately
- [ ] **VIP goes offline (network issues)** → Handled correctly based on rejoin config
- [ ] **VIP removed by admin** → Team eliminated
- [ ] **VIP removed by plugin** → Team eliminated
- [ ] **Regular player disconnects** → No impact on VIP status

### 8.3 Edge Cases
- [ ] **VIP selected, then leaves before match starts** → VIP reselected or delayed
- [ ] **All team members leave except VIP** → Game continues or auto-eliminates (configurable)
- [ ] **VIP joins late** → Assigned as VIP if team has none
- [ ] **VIP has multiple lives** → Can respawn, only eliminated when lives = 0
- [ ] **VIP dies to team kill** → Handled correctly based on team kill config
- [ ] **VIP dies to environmental damage** → Same as normal death
- [ ] **Glowing effect persists** → Re-applied on respawn/rejoin
- [ ] **Multiple teams, one VIP dies** → Other teams continue, game ends when all enemy VIPs dead

### 8.4 Rejoin Scenarios
- [ ] **VIP rejoins after disconnect** → VIP status restored, glowing effect re-applied
- [ ] **Regular player rejoins** → No VIP status change
- [ ] **VIP rejoins but team already eliminated** → Cannot rejoin (team eliminated)

### 8.5 Win Condition Scenarios
- [ ] **All enemy VIPs eliminated** → Game ends, winning team announced
- [ ] **Only one team has active VIP** → Game ends
- [ ] **VIP offline but rejoin allowed** → Game continues, waits for VIP
- [ ] **VIP offline and rejoin disabled** → Team eliminated, game may end

### 8.6 Stress Testing
- [ ] **Multiple VIPs disconnect simultaneously** → All handled correctly
- [ ] **VIP disconnects right as they die** → No double-elimination
- [ ] **VIP disconnects during respawn** → Handled correctly
- [ ] **Rapid disconnect/reconnect** → No state corruption
- [ ] **VIP removed while offline** → Team eliminated correctly

---

## 8. Estimated Impact

- **Total Files Modified:** 2-4 files (depending on optional additions)
- **Total Files Created:** 1-2 files (goal class + optional docs)
- **Total Lines Added:** ~350-500 lines
- **Complexity:** Low-Medium
- **Risk Level:** Low (isolated to new goal, doesn't affect existing goals)

---

**Document Created:** 2025-12-06  
**Status:** Ready for Implementation

