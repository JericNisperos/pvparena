# PVP Arena Health Modification - Master Prompt for Cursor AI

## 🎯 PROJECT OBJECTIVE
Modify the PVP Arena Spigot plugin to:
1. **Preserve player health** when joining lobby (don't reset to 20 hearts)
2. **Preserve player health** when joining arena (don't reset to 20 hearts)
3. **Preserve player health** on respawn (don't reset to 20 hearts)

**Current Problem:** Players with >20 hearts are being reset to 20 hearts when joining lobby/arena/respawning because the plugin sets health to Minecraft's default 20.

**Build Output:** The final JAR should be named `pvparena-cyan.jar`

---

## 📋 PHASE 1: PROJECT SETUP & ANALYSIS

### Step 1.1: Verify Project Structure
```bash
# Confirm we're in the correct directory
pwd
ls -la

# Expected files:
# - pom.xml
# - src/main/java/
# - src/main/resources/
```

**Action:** Verify the project structure and confirm this is the pvparena plugin.

### Step 1.2: Configure Custom JAR Name
Open `pom.xml` and locate the `<build>` section. Modify the final name:

```xml
<build>
    <finalName>pvparena-cyan</finalName>
    <!-- rest of build configuration -->
</build>
```

**Action:** Update pom.xml to set the output JAR name to `pvparena-cyan.jar`

### Step 1.3: Search for Health-Related Code
```bash
# Search for all health modification code
grep -rn "setHealth" src/
grep -rn "setMaxHealth" src/
grep -rn "GENERIC_MAX_HEALTH" src/
grep -rn "CFG_PLAYER_HEALTH" src/
grep -rn "CFG_PLAYER_MAXHEALTH" src/
```

**Action:** Run these searches and list all files that contain health-related code with their line numbers.

### Step 1.4: Identify Key Files
Based on the search results, identify and list:
- Files that modify health when joining lobby
- Files that modify health when joining arena
- Files that handle respawn logic
- Configuration files that define health parameters

**Action:** Create a summary table showing:
| File Path | Line Number | Code Purpose | Modification Needed |

---

## 📋 PHASE 2: MODIFY LOBBY JOIN BEHAVIOR

### Step 2.1: Locate Lobby Join Code
**Search for:** Code that executes when a player joins the lobby.

Common file locations:
- `src/main/java/net/slipcor/pvparena/classes/PACheck.java`
- `src/main/java/net/slipcor/pvparena/listeners/PlayerListener.java`
- `src/main/java/net/slipcor/pvparena/managers/ArenaManager.java`

**Action:** Find the method that handles lobby join and show the current code.

### Step 2.2: Comment Out Health Reset on Lobby Join
**Find code similar to:**
```java
int configHealth = arena.getArenaConfig().getInt(Config.CFG_PLAYER_HEALTH);
if (configHealth > 0) {
    player.setHealth(configHealth);
}

int configMaxHealth = arena.getArenaConfig().getInt(Config.CFG_PLAYER_MAXHEALTH);
if (configMaxHealth > 0) {
    player.setMaxHealth(configMaxHealth);
}
```

**Replace with:**
```java
// MODIFIED: Preserve player's existing health when joining lobby
// Original code set health to configured value, but we want to preserve custom health (>20 hearts)
// int configHealth = arena.getArenaConfig().getInt(Config.CFG_PLAYER_HEALTH);
// if (configHealth > 0) {
//     player.setHealth(configHealth);
// }

// int configMaxHealth = arena.getArenaConfig().getInt(Config.CFG_PLAYER_MAXHEALTH);
// if (configMaxHealth > 0) {
//     player.setMaxHealth(configMaxHealth);
// }
// END MODIFICATION
```

**Action:** Make this modification and confirm the changes.

---

## 📋 PHASE 3: MODIFY ARENA JOIN BEHAVIOR

### Step 3.1: Locate Arena Join Code
**Search for:** Code that executes when a player joins the arena (game start).

**Action:** Find and show the current code that sets player health on arena join.

### Step 3.2: Comment Out Health Reset on Arena Join
**Find and comment out** any code that sets player health to a specific value when the arena starts.

**Action:** Make modifications similar to Phase 3, adding clear comments explaining the change.

---

## 📋 PHASE 4: MODIFY RESPAWN BEHAVIOR

### Step 4.1: Locate Respawn Method
**Search for:** Respawn logic, likely in:
- `src/main/java/net/slipcor/pvparena/managers/SpawnManager.java`
- Any file containing `respawn` method

```bash
grep -rn "respawn" src/ | grep -i "method\|function\|void"
```

**Action:** Find the respawn method and show the current code.

### Step 4.2: Comment Out Health Reset on Respawn
**Find code like:**
```java
public static void respawn(Arena arena, Player player, DamageCause cause) {
    // ... teleport code ...
    player.teleport(spawnLocation);
    
    // Immediate health set (REMOVE THIS)
    player.setHealth(player.getMaxHealth());
    
    // ... rest of code ...
}
```

**Replace with:**
```java
public static void respawn(Arena arena, Player player, DamageCause cause) {
    // ... teleport code ...
    player.teleport(spawnLocation);
    
    // MODIFIED: Preserve player's existing health on respawn
    // Original code reset health to max, but we want to preserve custom health (>20 hearts)
    // player.setHealth(player.getMaxHealth());
    // END MODIFICATION
    
    // ... rest of code ...
}
```

**Action:** Comment out any health reset code in the respawn method.

---

## 📋 PHASE 5: ADDITIONAL CLEANUP

### Step 5.1: Search for Other Health Modifications
```bash
# Find any other places where health might be set
grep -rn "\.setHealth(" src/ | grep -v "^\s*//"
grep -rn "\.setMaxHealth(" src/ | grep -v "^\s*//"
```

**Action:** Review each result and determine if it needs modification. Focus on:
- Player state restoration
- Arena reset functions
- Class selection code

### Step 5.2: Check Configuration Files
Look at: `src/main/resources/config.yml` or similar

**Action:** Verify if there are default health values in config that should be documented.

---

## 📋 PHASE 6: BUILD & TEST

### Step 6.1: Clean Build
```bash
# Clean previous builds
mvn clean

# Compile the project
mvn package
```

**Action:** Build the project and report any compilation errors.

### Step 6.2: Locate Output JAR
```bash
# Find the compiled JAR - should be pvparena-cyan.jar
ls -lh target/*.jar
```

**Action:** Confirm the JAR file `pvparena-cyan.jar` was created successfully and show its location.

### Step 6.3: Create Test Checklist
**Action:** Create a markdown checklist for testing:

```markdown
## Testing Checklist

### Pre-Test Setup
- [ ] Give test player >20 hearts (e.g., 30 hearts)
- [ ] Note player's exact health before testing

### Test 1: Lobby Join
- [ ] Player joins lobby
- [ ] Health remains at 30 hearts (not reset to 20)
- [ ] MaxHealth attribute unchanged

### Test 2: Arena Join  
- [ ] Player joins arena/game starts
- [ ] Health remains at 30 hearts (not reset to 20)
- [ ] MaxHealth attribute unchanged

### Test 3: Respawn
- [ ] Player dies in arena
- [ ] Player respawns
- [ ] Health remains preserved (stays at current value, not reset to 20)

### Test 4: Normal Players (20 hearts)
- [ ] Test with standard 20-heart player
- [ ] Verify everything still works normally
```

---

## 📋 PHASE 7: DOCUMENTATION & SUMMARY

### Step 7.1: Create Change Log
**Action:** Create `CHANGES.md` file documenting:
```markdown
# PVP Arena Cyan - Health Preservation Modifications

## Changes Made

### 1. Lobby Join (File: [filename], Line: [line])
- Commented out health reset code
- Players now preserve their custom health when joining lobby

### 2. Arena Join (File: [filename], Line: [line])
- Commented out health reset code  
- Players now preserve their custom health when joining arena

### 3. Respawn (File: [filename], Line: [line])
- Commented out health reset code
- Players now preserve their custom health on respawn

### 4. Build Configuration (File: pom.xml)
- Changed final JAR name to `pvparena-cyan.jar`

## Reasoning
Players with custom max health (>20 hearts) were being reset to default 
Minecraft health (20 hearts) when joining lobby/arena/respawning. This 
modification preserves custom health throughout all arena interactions.

## Files Modified
- [List all modified files]

## Testing Required
- Test with players having >20 hearts
- Test with standard 20-heart players
- Verify health preservation works on lobby join, arena join, and respawn
```

### Step 7.2: Generate Summary Report
**Action:** Create a final summary showing:

```markdown
## Modification Summary

### Files Modified:
1. pom.xml - Changed JAR name to pvparena-cyan.jar
2. [File 1] - [Purpose]
3. [File 2] - [Purpose]
4. [File 3] - [Purpose]

### Total Lines Changed: [number]
### Build Status: ✅ Success / ❌ Failed
### JAR Location: target/pvparena-cyan.jar

### Next Steps:
1. Copy JAR to test server: plugins/pvparena-cyan.jar
2. Restart server
3. Run through testing checklist
4. Monitor console for errors
5. Test with multiple players
6. Commit changes manually via GitHub UI when satisfied

### Important Notes:
- All health modifications have been commented out (not deleted)
- Original code preserved in comments for reference
- This is a fork, so changes can be committed when ready
```

---

## 📋 PHASE 8: FINAL SUMMARY

### Step 8.1: Generate Final Summary Report
**Action:** Create a final summary showing:

```markdown
## Final Modification Summary

### Custom Build Name:
✅ JAR file: `pvparena-cyan.jar`

### Files Modified:
1. pom.xml - Changed final JAR name
2. [File 1] - [Purpose and changes]
3. [File 2] - [Purpose and changes]
4. [File 3] - [Purpose and changes]

### Total Lines Changed: [number]
### Build Status: ✅ Success / ❌ Failed
### JAR Location: target/pvparena-cyan.jar

### Modifications Summary:
- ❌ Health reset on lobby join (REMOVED)
- ❌ Health reset on arena join (REMOVED)
- ❌ Health reset on respawn (REMOVED)
- ✅ Health preservation throughout all interactions

### Ready for Testing:
1. Copy `target/pvparena-cyan.jar` to test server
2. Place in `plugins/` folder
3. Restart server
4. Test with >20 heart players
5. Verify health is preserved in all scenarios

### Manual Commit Guide:
When you're satisfied with testing:
1. Open GitHub Desktop or GitHub web UI
2. Review all changes in modified files
3. Commit with message: "feat: preserve player health in pvparena-cyan"
4. Push to your fork
```

---

## 🚨 ERROR HANDLING

If any step fails, provide:
1. The exact error message
2. The file and line number where error occurred
3. The code context (10 lines before and after)
4. Suggested fixes

---

## ✅ SUCCESS CRITERIA

- [ ] All grep searches completed
- [ ] All files identified and listed
- [ ] JAR name set to `pvparena-cyan.jar` in pom.xml
- [ ] All health reset code commented out (not deleted)
- [ ] Project compiles without errors
- [ ] JAR file `pvparena-cyan.jar` generated successfully
- [ ] CHANGES.md documentation created
- [ ] Testing checklist provided
- [ ] Summary report generated
- [ ] Ready for manual commit via UI

---

## 💡 TIPS FOR CURSOR AI

- Show file paths and line numbers for all findings
- Include code context (10 lines) when showing modifications
- Ask for confirmation before making irreversible changes
- Provide clear before/after comparisons
- Generate working code, not pseudocode
- Test regex patterns before applying bulk changes

---

**START WITH PHASE 1, STEP 1.1 and proceed through each phase sequentially. After completing each phase, provide a summary and wait for confirmation before proceeding to the next phase.**