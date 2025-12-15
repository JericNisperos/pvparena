# ELO Rating Module - Planning Document

## Summary

This document outlines the plan to create an **ELO Rating Module** for the PVP Arena plugin. The module will track player ELO ratings and calculate rating changes based on match outcomes against opponents.

---

## Quick Reference

### ✅ Per-Arena Module
**Yes, ELO only works on arenas where the module is enabled.**
- Module must be enabled per arena using: `/pa <arenaname> togglemod elorating`
- Each arena can have the module enabled/disabled independently
- ELO ratings can be tracked per-arena or globally (configurable)

### 📝 Files to Modify
**Only 1 file needs modification:**
1. `src/main/java/net/slipcor/pvparena/loadables/ArenaModuleManager.java`
   - Add 1 line: `this.addInternalLoadable(ELORating.class);`
   - Location: `addInternalMods()` method (~line 71)

**No other core files need modification!**

### 📁 Files to Create
1. `src/main/java/net/slipcor/pvparena/modules/ELORating.java` - Main module
2. `src/main/java/net/slipcor/pvparena/modules/elo/ELODatabase.java` - MySQL handler
3. `src/main/java/net/slipcor/pvparena/modules/elo/ELOCalculator.java` - ELO math
4. `plugins/PVPArena/modules/elo/config.yml` - Module configuration

### 🗄️ Storage
- **MySQL database** with separate table (`pvparena_elo_ratings`)
- No modifications to existing statistics system
- Self-contained and isolated

---

## 1. Overview

### 1.1 What is ELO Rating?
ELO is a rating system used to calculate the relative skill levels of players. It was originally designed for chess but is widely used in competitive gaming.

**Key Concepts:**
- Each player has a numerical rating (typically starts at 1000-1500)
- Rating increases when you beat opponents with higher ratings
- Rating decreases when you lose to opponents with lower ratings
- The amount of change depends on the rating difference between players

### 1.2 Module Purpose
- Track ELO ratings per player (per-arena or global)
- Calculate ELO changes after each match
- Display ELO ratings to players
- Provide leaderboards based on ELO

---

## 2. ELO Calculation Formula

### 2.1 Standard ELO Formula

**Expected Score (E):**
```
E = 1 / (1 + 10^((Opponent Rating - Player Rating) / 400))
```

**New Rating:**
```
New Rating = Old Rating + K * (Actual Score - Expected Score)
```

Where:
- **K Factor**: Maximum rating change per match (typically 16, 24, or 32)
- **Actual Score**: 1 for win, 0.5 for draw, 0 for loss
- **Expected Score**: Probability of winning based on rating difference

### 2.2 Team-Based ELO

For team matches, calculate average team rating:
```
Team Rating = Average of all team members' ratings
```

Then apply ELO formula using team ratings instead of individual ratings.

### 2.3 Free-For-All (FFA) ELO

For FFA matches:
- Calculate expected score against each opponent
- Distribute ELO changes based on final rankings
- Winner gains ELO from all losers
- Losers lose ELO to winner (and potentially other higher-ranked players)

---

## 3. Files to CREATE

### 3.1 Main Module Class
**File:** `src/main/java/net/slipcor/pvparena/modules/ELORating.java`
- **Type:** New Java class file
- **Extends:** `ArenaModule`
- **Estimated Size:** ~400-500 lines
- **Key Features:**
  - Module lifecycle management
  - Integration with match end events (`timedEnd()`, `commitEnd()`)
  - Configuration loading from module config file
  - Coordination between database and calculator
  - Player messaging for ELO display

### 3.2 MySQL Database Handler
**File:** `src/main/java/net/slipcor/pvparena/modules/elo/ELODatabase.java`
- **Type:** New Java class file
- **Purpose:** Handle MySQL database operations for ELO ratings
- **Estimated Size:** ~200-300 lines
- **Key Features:**
  - Database connection management
  - Table creation/initialization
  - CRUD operations for ELO ratings
  - Connection pooling (optional but recommended)

**Database Schema:**
```sql
CREATE TABLE IF NOT EXISTS pvparena_elo_ratings (
    id INT AUTO_INCREMENT PRIMARY KEY,
    player_uuid VARCHAR(36) NOT NULL,
    arena_uuid VARCHAR(36) NULL,
    rating DOUBLE NOT NULL DEFAULT 1000,
    matches_played INT NOT NULL DEFAULT 0,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY unique_player_arena (player_uuid, arena_uuid),
    INDEX idx_rating (rating DESC),
    INDEX idx_player (player_uuid)
);
```

**Key Methods:**
- `initializeDatabase()` - Create table if not exists
- `getPlayerRating(playerUUID, arenaUUID)` - Get rating from database
- `updatePlayerRating(playerUUID, arenaUUID, newRating)` - Update rating
- `getTopRatings(limit, arenaUUID)` - Get leaderboard
- `closeConnection()` - Clean up connections

### 3.3 ELO Calculator (Helper Class)
**File:** `src/main/java/net/slipcor/pvparena/modules/elo/ELOCalculator.java`
- **Type:** New Java class file
- **Purpose:** Centralized ELO calculation logic
- **Estimated Size:** ~100-150 lines
- **Key Methods:**
  - `calculateExpectedScore(playerRating, opponentRating)` - Calculate expected win probability
  - `calculateRatingChange(playerRating, opponentRating, actualScore, kFactor)` - Calculate ELO change
  - `calculateAverageTeamRating(team)` - Get average rating for team
  - `distributeTeamRatingChange(team, totalChange)` - Distribute change to team members

---

## 4. Files to MODIFY

### 4.1 Module Registration (REQUIRED - Only 1 file to modify)
**File:** `src/main/java/net/slipcor/pvparena/loadables/ArenaModuleManager.java`
- **Line:** ~66-71 (in `addInternalMods()` method)
- **Change:** Add `this.addInternalLoadable(ELORating.class);`
- **Purpose:** Register the module so it can be loaded and enabled per arena
- **Impact:** Minimal - single line addition

**Example:**
```java
private void addInternalMods() {
    this.addInternalLoadable(BattlefieldJoin.class);
    this.addInternalLoadable(QuickLounge.class);
    this.addInternalLoadable(StandardLounge.class);
    this.addInternalLoadable(StandardSpectate.class);
    this.addInternalLoadable(ELORating.class);  // <-- NEW LINE
}
```

**Note:** We will NOT modify `Config.java` or `Language.java` to keep changes minimal. The module will use its own configuration file and hardcoded messages (or simple string formatting).

---

## 4.2 How Modules Work (Per-Arena)

**Important:** Modules are **per-arena**, not global. This means:

1. **Module Registration:** The module is registered in `ArenaModuleManager` so it's available to all arenas
2. **Module Installation:** Each arena must have the module enabled individually using:
   ```
   /pa <arenaname> togglemod elorating
   ```
3. **Module Scope:** ELO rating will **ONLY** work on arenas where the module is enabled
4. **Per-Arena Configuration:** Each arena can have different ELO settings (if configured per-arena)
5. **Database Storage:** ELO ratings can be tracked per-arena or globally (configurable)

**Benefits:**
- Enable ELO only on specific arenas (e.g., competitive arenas)
- Different arenas can have different ELO settings
- Easy to disable without affecting other arenas
- No impact on arenas that don't use ELO

**Summary of Files to Modify:**
- ✅ **Only 1 file:** `ArenaModuleManager.java` (add 1 line to register module)
- ❌ **No changes to:** `Config.java`, `Language.java`, or any other core files
- ✅ **All other files are NEW:** Module class, database handler, calculator, config file

---

## 5. Implementation Details

### 5.1 Module Lifecycle Hooks

#### 5.1.1 `timedEnd(Set<String> winners)`
**Purpose:** Handle ELO calculation when match ends due to timer
**Logic:**
1. Get all players who participated
2. Identify winners and losers
3. Calculate average team ratings (if team-based)
4. Calculate ELO changes for each player
5. Update player ratings
6. Display ELO changes to players

#### 5.1.2 `commitEnd(ArenaTeam team, ArenaPlayer player)`
**Purpose:** Handle ELO calculation when match ends normally
**Logic:**
1. Determine winners and losers
2. Calculate ELO changes
3. Update ratings
4. Display changes

#### 5.1.3 `initConfig()`
**Purpose:** Initialize module configuration and database
**Logic:**
1. Load module config file (`plugins/PVPArena/modules/elo/config.yml`)
2. Initialize MySQL database connection
3. Create database table if it doesn't exist
4. Set default config values if missing

#### 5.1.4 `announce(String message, String type)`
**Purpose:** Display ELO rating when joining (if enabled)
**Logic:**
1. Check if type is "JOIN" and display enabled in config
2. Extract player from message context (if possible) or use alternative method
3. Get player's current ELO rating from database
4. Send formatted message to player

**Note:** May need to use `parseJoin()` or event listener instead if `announce()` doesn't provide player context.

### 5.2 ELO Calculation Logic

#### 5.2.1 Team-Based Matches
```java
// 1. Get average team rating
double teamRating = calculateAverageTeamRating(team);

// 2. For each opponent team
for (ArenaTeam opponent : opponentTeams) {
    double opponentRating = calculateAverageTeamRating(opponent);
    
    // 3. Calculate expected score
    double expectedScore = 1.0 / (1.0 + Math.pow(10, (opponentRating - teamRating) / 400.0));
    
    // 4. Determine actual score (1 = win, 0 = loss)
    double actualScore = (isWinner) ? 1.0 : 0.0;
    
    // 5. Calculate rating change
    double ratingChange = kFactor * (actualScore - expectedScore);
    
    // 6. Distribute change to all team members
    distributeRatingChange(team, ratingChange);
}
```

#### 5.2.2 Free-For-All Matches
```java
// 1. Get all players sorted by final ranking
List<ArenaPlayer> rankedPlayers = getRankedPlayers();

// 2. For each player, calculate ELO against all opponents
for (int i = 0; i < rankedPlayers.size(); i++) {
    ArenaPlayer player = rankedPlayers.get(i);
    double totalChange = 0.0;
    
    for (int j = 0; j < rankedPlayers.size(); j++) {
        if (i == j) continue;
        
        ArenaPlayer opponent = rankedPlayers.get(j);
        double expectedScore = calculateExpectedScore(player, opponent);
        double actualScore = (i < j) ? 1.0 : 0.0; // Higher rank = win
        
        totalChange += kFactor * (actualScore - expectedScore);
    }
    
    updatePlayerRating(player, totalChange);
}
```

### 5.3 MySQL Database Storage (Selected Approach)

**Decision:** Use separate MySQL database table to avoid modifying existing systems.

#### 5.3.1 Database Configuration
The module will use its own configuration file: `plugins/PVPArena/modules/elo/config.yml`

**Config Structure:**
```yaml
database:
  host: localhost
  port: 3306
  database: pvparena_elo
  username: root
  password: password
  connection_pool_size: 5
  
elo:
  enabled: true
  k_factor: 24
  initial_rating: 1000
  per_arena: false
  display_on_join: true
  
messages:
  rating_display: "&eYour ELO Rating: &a%rating%"
  rating_change: "&eELO Change: %change%"
  rating_gain: "&a+%amount%"
  rating_loss: "&c-%amount%"
```

#### 5.3.2 Database Operations
- **Connection Management:** Use HikariCP or simple connection pooling
- **Async Operations:** Perform database operations asynchronously to avoid lag
- **Error Handling:** Graceful fallback if database is unavailable
- **Migration:** Auto-create table on first load

#### 5.3.3 Advantages of MySQL
- **Scalable:** Handles large player bases efficiently
- **Reliable:** ACID compliance ensures data integrity
- **Queryable:** Easy to generate leaderboards and statistics
- **Separate:** Doesn't interfere with existing plugin systems
- **Standard:** Well-supported and widely used

### 5.4 Rating Display

#### 5.4.1 On Join
- Show current ELO rating when player joins arena
- Format: "Your ELO Rating: 1250"

#### 5.4.2 On Match End
- Show ELO change after match
- Format: "ELO Change: +25" or "ELO Change: -15"
- Show new rating: "New Rating: 1275"

#### 5.4.3 Leaderboard Command
- Add command to view ELO leaderboard
- `/pa <arena> elo top [number]`
- Display top N players by ELO rating

---

## 6. Edge Cases & Considerations

### 6.1 New Players
- **Issue:** New players have no ELO rating
- **Solution:** Assign initial rating (default: 1000) on first match

### 6.2 Incomplete Matches
- **Issue:** Players leave mid-match
- **Solution:** Only calculate ELO for players who completed the match

### 6.3 Draws/Ties
- **Issue:** How to handle draws
- **Solution:** Use actual score of 0.5 for both teams

### 6.4 Team Size Imbalance
- **Issue:** Teams have different numbers of players
- **Solution:** Use average team rating (already handles this)

### 6.5 Rating Decay
- **Issue:** Should inactive players lose rating?
- **Solution:** Optional feature - decay rating over time if not playing

### 6.6 Minimum Matches
- **Issue:** New players with few matches have unreliable ratings
- **Solution:** Display "Provisional" rating until minimum matches played

### 6.7 Rating Caps
- **Issue:** Should there be maximum/minimum ratings?
- **Solution:** Optional config - cap ratings at certain values

---

## 7. Testing Checklist

### 7.1 Basic Functionality
- [ ] Module loads and registers correctly
- [ ] ELO ratings initialize for new players
- [ ] ELO calculates correctly for team wins
- [ ] ELO calculates correctly for team losses
- [ ] ELO calculates correctly for FFA matches
- [ ] ELO changes display after match
- [ ] ELO ratings persist across server restarts

### 7.2 Edge Cases
- [ ] New players get initial rating
- [ ] Players who leave mid-match don't get ELO changes
- [ ] Draws handled correctly (0.5 score)
- [ ] Team size imbalance handled correctly
- [ ] Per-arena vs global ELO works correctly
- [ ] Leaderboard displays correctly

### 7.3 Configuration
- [ ] K-factor changes affect calculations
- [ ] Initial rating config works
- [ ] Per-arena toggle works
- [ ] Display on join toggle works

### 7.4 Performance
- [ ] ELO calculation doesn't lag match end
- [ ] Storage operations are efficient
- [ ] Leaderboard queries are fast

---

## 8. Implementation Phases (Simplified)

### Phase 1: Database Setup & Module Structure (Required)
1. Create `ELODatabase.java` with MySQL connection handling
2. Create database table schema
3. Implement basic CRUD operations (get, update)
4. Create `ELOCalculator.java` with ELO math functions
5. Test database connectivity and operations

### Phase 2: Core Module Implementation (Required)
6. Create `ELORating.java` module class extending `ArenaModule`
7. Implement `initConfig()` to load module config
8. Implement `timedEnd()` hook for timer-based match endings
9. Implement `commitEnd()` hook for normal match endings
10. Test ELO calculation with simple matches

### Phase 3: Team-Based ELO (Required)
11. Implement team rating averaging logic
12. Implement ELO distribution to team members
13. Handle FFA matches (if needed)
14. Test with various team configurations

### Phase 4: User Interface (Recommended)
15. Display ELO rating on join (via `announce()` hook)
16. Display ELO changes after match end
17. Add simple leaderboard command (optional)
18. Test all display features

### Phase 5: Polish & Testing (Recommended)
19. Add error handling for database failures
20. Add connection retry logic
21. Performance optimization
22. Final testing and documentation

---

## 9. Estimated Impact

- **Total Files Created:** 3-4 files
  - `ELORating.java` (~400-500 lines) - Main module
  - `ELODatabase.java` (~200-300 lines) - MySQL handler
  - `ELOCalculator.java` (~100-150 lines) - Calculation logic
  - `config.yml` (module config file)
- **Total Files Modified:** 1 file (only `ArenaModuleManager.java` - single line)
- **Total Lines Added:** ~700-950 lines
- **Dependencies:** MySQL JDBC driver (will need to add to `pom.xml`)
- **Complexity:** Medium-High (requires understanding of ELO math, MySQL, and event system)
- **Risk Level:** Low (isolated module, separate database, minimal core changes)
- **Maintenance:** Self-contained module with its own config and database

---

## 10. Additional Features (Future Enhancements)

### 10.1 Rating Decay
- Gradually decrease rating for inactive players
- Configurable decay rate and time threshold

### 10.2 Provisional Ratings
- Mark new players with provisional status
- Larger K-factor for provisional players
- Display "Provisional" in leaderboard

### 10.3 Rating History
- Track rating changes over time
- Graph rating progression
- Show peak rating

### 10.4 Matchmaking
- Use ELO to balance teams
- Match players with similar ratings
- Optional: Auto-balance teams based on ELO

### 10.5 Seasonal Resets
- Reset ELO ratings periodically
- Track seasonal rankings
- Archive previous season data

---

## 11. MySQL Dependencies

### 11.1 Required Maven Dependency
Add to `pom.xml`:
```xml
<dependency>
    <groupId>mysql</groupId>
    <artifactId>mysql-connector-java</artifactId>
    <version>8.0.33</version>
</dependency>
```

**Alternative:** Use HikariCP for connection pooling (recommended for production):
```xml
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.0.1</version>
</dependency>
```

### 11.2 Database Requirements
- MySQL 5.7+ or MariaDB 10.2+
- Database user with CREATE TABLE and INSERT/UPDATE/SELECT permissions
- Network access to MySQL server (if remote)

### 11.3 Connection String Format
```
jdbc:mysql://host:port/database?useSSL=false&serverTimezone=UTC
```

---

## 12. Simplified Configuration Approach

Since we're avoiding modifications to core files, the module will:
- Use its own `config.yml` file in `plugins/PVPArena/modules/elo/`
- Use hardcoded message strings (or simple formatting) instead of Language.java
- Read config values directly from YAML using Bukkit's Configuration API
- Provide default values if config is missing

**Benefits:**
- Zero modifications to existing core files
- Easy to enable/disable by removing module
- Self-contained configuration
- No risk of breaking existing features

---

**Document Created:** 2025-12-06  
**Last Updated:** 2025-12-06  
**Status:** Simplified - Ready for Implementation  
**Next Step:** Begin Phase 1 - Database setup and module structure

