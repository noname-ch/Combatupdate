# Sword blocking

Code: `SwordBlocking.java`, registered in `CombatUpdate.java`.

Works the same way as `WeaponReach`: a `ModifyDefaultComponentsEvent` sets the `BLOCKS_ATTACKS` data
component on all seven vanilla swords (wood through netherite, including copper). That one component
gives right-click-to-block, the block hand animation, durability cost and the axe-disable interaction
for free.

Tuned to sit below a shield:

|                      | Shield               | Sword  |
|----------------------|----------------------|--------|
| Damage blocked       | 100%                 | 50%    |
| Wind-up              | 0.25 s               | none   |
| Axe-disable duration | ×1.0                 | ×1.5   |
| Blocking arc         | 90°                  | 90°    |
| Durability cost      | threshold 3, 1+1×dmg | same   |

A raised sword halves a frontal hit instead of erasing it, comes up instantly, and stays knocked
aside longer once an axe breaks the guard. The durability formula is the shield's, which bites harder
on a sword because swords have far less durability to spend. Damage types in
`#minecraft:bypasses_shield` go through a raised sword too, and it reuses the shield block/break sounds.

## Caveats

- The numbers are constants in the class, not in `Config.java`, matching `WeaponReach`.
  `ModifyDefaultComponentsEvent` fires during mod loading, so reading config values there isn't
  reliable. Putting them on the config screen would need a damage event handler instead of the
  component.
- Swords use the generic `BLOCK` hold pose, not a dedicated blocking model like the old 1.8 sword.
  That would need a client-side item model.
