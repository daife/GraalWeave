"""Importable Minecraft 26.3 aiming and melee example; does not navigate.

Set MCP cwd to this file's directory, then execute:
    from smooth_attack import SmoothAttack
    fighter = SmoothAttack()
    print(fighter.attack(target_id, max_hits=1))

Call attack() from the script thread, outside client.call().
"""
import math

import java
from graalweave import client, ticks

Minecraft = java.type('net.minecraft.client.Minecraft')
Hand = java.type('net.minecraft.world.InteractionHand')
Animation = java.type('net.minecraft.world.item.component.SwingAnimation')
EntityHitResult = java.type('net.minecraft.world.phys.EntityHitResult')


def _wrap(angle):
    return (angle + 180.0) % 360.0 - 180.0


class SmoothAttack:
    def __init__(self, degrees_per_tick=8.0):
        if degrees_per_tick <= 0:
            raise ValueError('degrees_per_tick must be positive')
        self.degrees_per_tick = degrees_per_tick

    def _step(self, entity_id):
        mc = Minecraft.getInstance()
        if mc.player is None or mc.gui.screen() is not None or mc.isPaused():
            return {'state': 'unavailable'}
        p = mc.player
        target = mc.level.getEntity(entity_id)
        if target is None or not target.isAlive():
            return {'state': 'target_gone'}

        eye = p.getEyePosition()
        aim = target.getBoundingBox().getCenter()
        dx, dy, dz = aim.x - eye.x, aim.y - eye.y, aim.z - eye.z
        yaw = math.degrees(math.atan2(-dx, dz))
        pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
        yaw_error = _wrap(yaw - p.getYRot())
        pitch_error = pitch - p.getXRot()
        error = max(abs(yaw_error), abs(pitch_error))
        # Ease into the aim point, with a fixed maximum turn per game tick.
        scale = min(0.35, self.degrees_per_tick / max(error, 0.001))
        p.setYRot(p.getYRot() + yaw_error * scale)
        p.setXRot(p.getXRot() + pitch_error * scale)
        result = {'state': 'aiming', 'angle_error': error}
        if error > 0.75:
            return result
        if not p.isWithinEntityInteractionRange(target, 0.0):
            return dict(result, state='out_of_range')

        # Use the game's rendered crosshair result, including intervening blocks
        # and entities. Never replace it with the requested target.
        hit = mc.hitResult
        if (not isinstance(hit, EntityHitResult)
                or hit.getEntity().getId() != entity_id
                or not p.hasLineOfSight(target)):
            return dict(result, state='not_under_crosshair')
        if p.getAttackStrengthScale(0.0) < 1.0:
            return dict(result, state='cooldown')
        health_before = target.getHealth()
        mc.gameMode.attack(p, target)
        p.swing(Hand.MAIN_HAND, Animation.DEFAULT, True)
        return dict(result, state='hit', health_before=health_before,
                    swinging=p.isSwinging())

    def attack(self, entity_id, *, max_hits=1, max_ticks=200):
        """Aim and attack up to max_hits times, then stop; target must be nearby."""
        if max_hits < 1 or max_ticks < 1:
            raise ValueError('max_hits and max_ticks must be positive')
        hits = []
        first_error = None
        for elapsed in range(max_ticks):
            result = client.call(lambda: self._step(entity_id))
            if first_error is None:
                first_error = result.get('angle_error')
            if result['state'] == 'hit':
                hits.append(dict(result, tick=elapsed))
            if (len(hits) >= max_hits
                    or result['state'] in ('unavailable', 'target_gone', 'out_of_range')):
                return dict(result, elapsed_ticks=elapsed + 1,
                            initial_angle_error=first_error, hits=hits)
            ticks.wait(1)
        return dict(result, state='timeout', elapsed_ticks=max_ticks,
                    initial_angle_error=first_error, hits=hits)
