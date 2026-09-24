# Elytra bomb

Code: `ElytraBomb.java`.

Right-click TNT while gliding and it's released as a bomb instead of placed. On the ground,
right-click still places TNT as before.

The momentum part is one line:

```java
bomb.setDeltaMovement(player.getDeltaMovement()
        .scale(Config.BOMB_MOMENTUM_TRANSFER.getAsDouble()));
```

Vanilla's `PrimedTnt` constructor gives it a small upward hop and a random spin, which is what a
block of TNT does when lit where it stands. That gets overwritten, because a bomb coming off an
aircraft should keep whatever the aircraft was doing.

Slowing down like a real bomb needs no code. Vanilla already scales a primed TNT's velocity by
`getAirDrag()` = 0.98 every tick and pulls it down at 0.04:

```java
this.setDeltaMovement(this.getDeltaMovement().scale(this.getAirDrag()));
```

So a bomb released at 1.5 blocks/tick is down to ~1.0 after a second and ~0.67 after two: it carries
forward hard at first, then the throw flattens out while gravity takes over.

It's hooked into both `RightClickItem` and `RightClickBlock` beside the fireball handler, so aiming at
a block mid-flight doesn't place it instead.

## Config (Explosives page)

| Key                    | Default  |                                                                   |
|------------------------|----------|-------------------------------------------------------------------|
| `bombMomentumTransfer` | 1.0      | All your speed. 0.0 drops it straight down; >1.0 throws it ahead  |
| `bombFuseTicks`        | 60 (3 s) | Shorter than vanilla's 80, so a bomb lands before it goes off     |
| `bombCooldownTicks`    | 10       | Half a second between drops                                       |

Blast size isn't separate: it goes through the existing `onEntityJoinLevel` retune, so a bomb is the
same size as any other TNT (`tntBlastRadius`).

## Caveats

- It spawns 0.6 blocks below the player so it doesn't look like it passed through them.
- There's no extra drag beyond vanilla's. Making bombs lag further behind would need a mixin on
  `getAirDrag`.
