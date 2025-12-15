# PVP Arena Goals - Analysis & Suggestions

## Executive Summary

This document analyzes the existing PVP Arena goal system and provides suggestions for new goals, including the requested "Search and Destroy" goal. All analysis is done without modifying code, focusing on understanding the architecture and proposing feasible additions.

---

## 1. Current Goals Overview

### 1.1 Goal Categories

The plugin currently has **20 goals** that can be categorized as follows:

#### **Team-Based Goals** (12 goals)
- **BlockDestroy**: Destroy opponent team's block(s) to eliminate them
- **Domination**: Capture and hold flag positions to earn points over time
- **Flags**: Capture flags and bring them to your base
- **Food**: Cook food and deliver to team chests
- **Liberation**: Jail enemies, free allies
- **PhysicalFlags**: Destroy enemy flag block and place at your base
- **Sabotage**: Ignite opponent team's TNT to eliminate them
- **TeamDeathConfirm**: Confirmed team kills win
- **TeamDeathMatch**: Team kills win
- **TeamLives**: Last alive team wins
- **TeamPlayerLives**: Last team with alive players wins
- **Tank**: All vs one (special team mode)

#### **Free-For-All Goals** (5 goals)
- **CheckPoints**: Reach checkpoints in order to win
- **Infect**: Infect people to win or kill infected players
- **PlayerDeathMatch**: Score points by killing players
- **PlayerKillReward**: Players get better gear when killing
- **PlayerLives**: Last alive player wins

#### **Hybrid Goals** (3 goals)
- Goals that can work in both team and FFA modes

---

## 2. Goal Architecture Analysis

### 2.1 Base Class Structure

All goals extend `ArenaGoal` which provides:

**Key Methods:**
- `checkEnd()` - Determines if game should end (returns boolean)
- `commitEnd(boolean force)` - Handles game ending logic
- `commitPlayerDeath()` - Handles player death
- `checkInteract()` - Handles player interactions
- `checkBreak()` - Handles block breaking
- `checkPlace()` - Handles block placing
- `checkInventory()` - Handles inventory interactions
- `parseStart()` - Called when arena starts
- `reset(boolean force)` - Resets goal state

**Abstract Base Classes:**
- `AbstractFlagGoal` - For flag-based goals (Flags, PhysicalFlags)
- `AbstractTeamKillGoal` - For team kill-based goals
- `AbstractPlayerLivesGoal` - For lives-based goals

### 2.2 Common Patterns

**Pattern 1: Block-Based Objectives**
- Uses `PABlock` system to track objective blocks
- Commands: `/pa <arena> <goal> set <team>` to set blocks
- Examples: BlockDestroy, Sabotage, PhysicalFlags

**Pattern 2: Location-Based Objectives**
- Uses `Location` tracking for capture points
- Periodic checking via runnables
- Examples: Domination, CheckPoints

**Pattern 3: Item-Based Objectives**
- Tracks items in inventory or chests
- Uses `InventoryClickEvent` and `PlayerInteractEvent`
- Examples: Food, Flags (item pickup)

**Pattern 4: Kill-Based Objectives**
- Tracks kills via `commitPlayerDeath()`
- Uses life maps (`teamLifeMap`, `playerLifeMap`)
- Examples: TeamDeathMatch, PlayerLives

### 2.3 Event Handling

Goals can implement `Listener` interface for custom event handling:
- `EntityExplodeEvent` (Sabotage uses this)
- `BlockBreakEvent`, `BlockPlaceEvent`
- `InventoryClickEvent`, `PlayerInteractEvent`
- `EntityPickupItemEvent`

---

## 3. Search and Destroy Goal - Detailed Analysis

### 3.1 Concept

**Search and Destroy** is a team-based goal where:
- Each team has **multiple objectives** (bombs/beacons) scattered across the map
- Teams must **defend their own objectives** while **destroying enemy objectives**
- Game ends when **all enemy objectives are destroyed** OR **time runs out** (highest score wins)

### 3.2 Gameplay Mechanics

1. **Setup Phase:**
   - Admin sets multiple objective blocks per team using `/pa <arena> snd set <team>`
   - Each team gets 3-5 objectives (configurable)
   - Objectives can be any block type (beacons, chests, specific blocks)

2. **Gameplay:**
   - Players must find and destroy enemy team objectives
   - Destroying an objective awards points
   - Teams must defend their own objectives
   - Objectives can have "health" (multiple hits required)

3. **Win Conditions:**
   - **Primary:** Destroy all enemy objectives
   - **Secondary:** Highest score when time runs out
   - **Tiebreaker:** Team with most players alive

### 3.3 Implementation Complexity: **MEDIUM**

**Why Medium:**
- Similar to existing `BlockDestroy` goal (can use as reference)
- Needs multiple objectives per team (not just one)
- Requires score tracking system (similar to Domination)
- Needs objective health system (optional but recommended)
- Should support timed matches with score-based win

**Key Components Needed:**
1. **Objective Management:**
   - Map: `Map<ArenaTeam, List<PABlock>>` - Multiple objectives per team
   - Command: `/pa <arena> snd set <team>` - Add objectives
   - Command: `/pa <arena> snd remove <team>` - Remove objectives

2. **Destruction Tracking:**
   - Track which objectives are destroyed
   - Award points on destruction
   - Check win condition: `checkEnd()` when all enemy objectives destroyed

3. **Score System:**
   - Map: `Map<ArenaTeam, Integer>` - Track scores
   - Config: `snd.pointsPerObjective` - Points per destroyed objective
   - Config: `snd.objectivesPerTeam` - Number of objectives per team

4. **Health System (Optional):**
   - Config: `snd.objectiveHealth` - Hits required to destroy (default: 1)
   - Track damage per objective: `Map<PABlock, Integer>`

5. **Visual Feedback:**
   - Particle effects on objectives
   - Boss bar or scoreboard showing remaining objectives
   - Announcements when objectives are destroyed

**Files to Create:**
- `GoalSearchAndDestroy.java` (extends `ArenaGoal`)
- Similar structure to `GoalBlockDestroy.java` but with multiple objectives

**Estimated Lines of Code:** ~400-600 lines

---

## 4. Additional Goal Suggestions

### 4.1 King of the Hill (KotH)

**Concept:** Teams compete to control a central location. The team that holds it longest wins.

**Mechanics:**
- Single capture point (or multiple rotating points)
- Players must stand in capture zone
- Progress bar shows capture progress
- Points awarded per second while controlling
- First team to reach point threshold wins

**Complexity:** **MEDIUM**
- Similar to Domination but with single/moving points
- Uses location-based checking (like Domination)
- Needs capture progress tracking

**Implementation:**
- Extend `ArenaGoal` or create abstract base
- Use `BukkitRunnable` for periodic checking
- Track: `Map<Location, ArenaTeam>` for control
- Config: `koth.captureTime`, `koth.pointsPerSecond`, `koth.winPoints`

---

### 4.2 Capture the Flag (CTF) - Enhanced

**Note:** There's already a "Flags" goal, but this would be a variant.

**Concept:** Classic CTF - capture enemy flag and return to your base while defending your own.

**Mechanics:**
- Each team has a flag at their base
- Players can pick up enemy flag (becomes item in inventory)
- Must return to own base to score
- If flag carrier dies, flag drops at death location
- Can be picked up by either team
- First team to capture X flags wins

**Complexity:** **MEDIUM-HIGH**
- Similar to existing Flags goal but with return mechanic
- Needs item tracking in inventory
- Needs death handling for flag drops
- Needs base location checking

**Implementation:**
- Could extend `AbstractFlagGoal`
- Track flag carriers: `Map<ArenaPlayer, ArenaTeam>` (carrier -> flag owner)
- Handle `PlayerDropItemEvent` for flag drops
- Check `PlayerInteractEvent` at base locations

---

### 4.3 Escort / Payload

**Concept:** One team escorts a moving objective (minecart/boat) along a path while the other team defends.

**Mechanics:**
- Attacking team pushes payload forward
- Defending team tries to stop it
- Payload moves when attackers are near (no defenders)
- Payload has checkpoints
- Win by reaching final checkpoint

**Complexity:** **HIGH**
- Requires entity movement (minecart/boat)
- Needs path/checkpoint system
- Complex proximity checking
- Movement mechanics

**Implementation:**
- Would need custom entity management
- Path system with waypoints
- Proximity checking for both teams
- Movement logic based on team presence

---

### 4.4 Assassination

**Concept:** Each team has a VIP player. Kill the enemy VIP to win, but if your VIP dies, you lose.

**Mechanics:**
- One player per team designated as VIP (random or configurable)
- VIP has special indicator (glowing, name tag, etc.)
- VIP death = team elimination
- Regular players can respawn, VIP cannot (or limited respawns)

**Complexity:** **LOW-MEDIUM**
- Similar to lives system but with special player
- Needs VIP designation system
- Needs special death handling
- Visual indicators (glowing effect, name tag)

**Implementation:**
- Extend `ArenaGoal`
- Track VIPs: `Map<ArenaTeam, ArenaPlayer>`
- Override `commitPlayerDeath()` to check if VIP
- Apply potion effects for visual indicator
- Config: `assassination.vipLives` (default: 1)

---

### 4.5 Resource Control

**Concept:** Teams compete to control resource nodes that generate points over time.

**Mechanics:**
- Multiple resource nodes across map
- Teams capture nodes by standing near them
- Nodes generate points per second for controlling team
- Nodes can be contested (both teams present = no points)
- First team to reach point threshold wins

**Complexity:** **MEDIUM**
- Similar to Domination but with resource generation
- Multiple nodes with individual tracking
- Contested state handling
- Point generation over time

**Implementation:**
- Similar structure to Domination
- Multiple locations with individual control states
- Runnable for point generation
- Config: `resource.nodes`, `resource.pointsPerSecond`, `resource.winPoints`

---

### 4.6 Rush / Breach

**Concept:** Attacking team tries to plant bomb at objectives, defending team tries to defuse.

**Mechanics:**
- Attacking team gets bomb item
- Must plant at enemy objective (interact with block)
- Planting takes time (progress bar, can be interrupted)
- Defending team can defuse planted bomb (also takes time)
- Attacking team wins if bomb explodes, defending wins if all bombs defused or time runs out

**Complexity:** **HIGH**
- Complex interaction system
- Progress bars for plant/defuse
- Multiple objectives
- Timer-based mechanics
- Explosion handling

**Implementation:**
- Needs custom interaction handling
- Progress bar system (similar to Domination claim bar)
- Bomb state tracking: `Map<Location, BombState>` (UNPLANTED, PLANTING, PLANTED, DEFUSING, DEFUSED, EXPLODED)
- Config: `rush.plantTime`, `rush.defuseTime`, `rush.objectives`

---

### 4.7 Last Man Standing (LMS) - Variant

**Concept:** All players spawn, last team/player alive wins. No respawns.

**Mechanics:**
- Simple elimination
- No respawns (or very limited)
- Last team/player standing wins
- Can be team or FFA

**Complexity:** **LOW**
- Very similar to existing PlayerLives/TeamLives
- Just needs respawn disabled
- Simple death tracking

**Implementation:**
- Could be config option for existing Lives goals
- Or simple new goal extending AbstractPlayerLivesGoal
- Override `shouldRespawnPlayer()` to return false

---

### 4.8 Infection / Zombie Mode

**Concept:** One player starts as "infected", infects others on kill. Last uninfected wins.

**Mechanics:**
- One random player starts infected
- Infected players have different appearance (glowing, name color)
- Killing uninfected player converts them
- Last uninfected player/team wins
- Or: Infected team wins if all converted

**Complexity:** **MEDIUM**
- Similar to existing Infect goal but team-based variant
- Needs infection state tracking
- Visual indicators
- Win condition logic

**Implementation:**
- Could extend existing Infect goal
- Or create team variant
- Track: `Set<ArenaPlayer>` infected players
- Apply effects on infection

---

## 5. Implementation Priority & Recommendations

### 5.1 High Priority (Easy Wins)

1. **Search and Destroy** ⭐ (Requested)
   - Complexity: Medium
   - Similar to existing BlockDestroy
   - Clear gameplay loop
   - High player appeal

2. **King of the Hill**
   - Complexity: Medium
   - Popular game mode
   - Similar to Domination (can reuse code)

3. **Assassination**
   - Complexity: Low-Medium
   - Simple mechanics
   - High strategic value

### 5.2 Medium Priority

4. **Resource Control**
   - Complexity: Medium
   - Strategic gameplay
   - Similar to Domination

5. **Last Man Standing Variant**
   - Complexity: Low
   - Simple addition
   - Popular mode

### 5.3 Lower Priority (Complex)

6. **Escort / Payload**
   - Complexity: High
   - Requires entity movement
   - Complex implementation

7. **Rush / Breach**
   - Complexity: High
   - Complex interaction system
   - Multiple states to track

8. **Enhanced CTF**
   - Complexity: Medium-High
   - Similar to existing Flags goal
   - May be redundant

---

## 6. Technical Considerations

### 6.1 Code Reusability

Many suggested goals can leverage existing code:
- **BlockDestroy** → Search and Destroy (block tracking)
- **Domination** → King of the Hill, Resource Control (location capture)
- **Flags** → Enhanced CTF (flag/item tracking)
- **PlayerLives/TeamLives** → Assassination, Last Man Standing (lives system)

### 6.2 Common Patterns to Extract

Consider creating helper classes for:
- **CapturePointManager**: For location-based capture goals
- **ObjectiveManager**: For multi-objective tracking
- **ProgressBarManager**: For interaction progress bars
- **ScoreTracker**: For point-based goals

### 6.3 Configuration Patterns

All new goals should follow existing config patterns:
- `goal.<goalname>.<setting>` format
- Default values in `setDefaults()`
- Config validation in `onThisLoad()`

### 6.4 Event Handling Best Practices

- Use `checkInteract()` for player interactions
- Use `checkBreak()` for block destruction
- Implement `Listener` only if needed for custom events
- Always cancel events when goal handles them

---

## 7. Search and Destroy - Implementation Plan

### 7.1 Phase 1: Basic Structure
- Create `GoalSearchAndDestroy.java`
- Extend `ArenaGoal`
- Implement basic command structure (`snd set <team>`, `snd remove <team>`)
- Store objectives per team

### 7.2 Phase 2: Destruction Logic
- Override `checkBreak()` to detect objective destruction
- Track destroyed objectives
- Award points on destruction
- Implement `checkEnd()` for win condition

### 7.3 Phase 3: Scoring System
- Add score tracking
- Implement timed end with score comparison
- Add scoreboard/boss bar display

### 7.4 Phase 4: Polish
- Add particle effects
- Add announcements
- Add config options
- Add visual feedback

### 7.5 Estimated Timeline
- **Phase 1:** 2-3 hours
- **Phase 2:** 3-4 hours
- **Phase 3:** 2-3 hours
- **Phase 4:** 2-3 hours
- **Total:** 9-13 hours

---

## 8. Conclusion

The PVP Arena goal system is well-architected and extensible. The suggested goals range from simple additions (Assassination, Last Man Standing) to complex implementations (Escort, Rush). 

**Search and Destroy** is a solid choice for implementation:
- ✅ Medium complexity (manageable)
- ✅ Clear gameplay loop
- ✅ Similar to existing goals (can reuse patterns)
- ✅ High player appeal
- ✅ Team-based (fits plugin's strengths)

**Recommendation:** Start with **Search and Destroy** as it's requested and has good complexity-to-value ratio. Then consider **King of the Hill** and **Assassination** as they're popular modes with reasonable implementation effort.

---

## 9. Questions for Clarification

Before implementing Search and Destroy, consider:

1. **Objective Count:** How many objectives per team? (Configurable? Default?)
2. **Objective Health:** Should objectives require multiple hits? (Configurable?)
3. **Respawn:** Should destroyed objectives respawn? (Timed? Never?)
4. **Scoring:** Points per objective? Bonus for destroying all?
5. **Time Limit:** Should there be a time limit with score-based win?
6. **Visual Feedback:** Particle effects? Boss bars? Scoreboard?
7. **Objective Types:** Any block type? Specific blocks? Beacons only?

---

**Document Created:** 2025-12-06  
**Status:** Analysis Complete - Ready for Implementation Planning

