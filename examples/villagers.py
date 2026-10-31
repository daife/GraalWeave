"""Inspect nearby villagers; --attack normally attacks librarians wearing diamond helmets.

Enter a world first. Equip an axe and close menus before using --attack.
Only visible targets within normal reach are attacked; this example does not navigate.
"""
import argparse
import java
from graalweave import client, ticks

Minecraft = java.type('net.minecraft.client.Minecraft')
Villager = java.type('net.minecraft.world.entity.npc.villager.Villager')
Profession = java.type('net.minecraft.world.entity.npc.villager.VillagerProfession')
Slot = java.type('net.minecraft.world.entity.EquipmentSlot')
Items = java.type('net.minecraft.world.item.Items')
Hand = java.type('net.minecraft.world.InteractionHand')
Anchor = java.type('net.minecraft.commands.arguments.EntityAnchorArgument$Anchor')
Animation = java.type('net.minecraft.world.item.component.SwingAnimation')
mc = Minecraft.getInstance()


def matches(entity):
    # Java is() is a Python keyword: access the actual method with getattr, not is_().
    return (getattr(entity.getVillagerData().profession(), 'is')(Profession.LIBRARIAN)
            and getattr(entity.getItemBySlot(Slot.HEAD), 'is')(Items.DIAMOND_HELMET))


def nearby():
    return [e for e in mc.level.entitiesForRendering()
            if isinstance(e, Villager) and e.isAlive() and mc.player.distanceTo(e) <= 16]


def snapshot():
    return [{'id': e.getId(), 'name': e.getName().getString(),
             'profession': str(e.getVillagerData().profession().unwrapKey().get().identifier()),
             'head': str(e.getItemBySlot(Slot.HEAD)), 'health': e.getHealth(),
             'distance': mc.player.distanceTo(e), 'matches': matches(e)} for e in nearby()]


def hit():
    if mc.isPaused():
        raise RuntimeError('Close the pause menu before attacking')
    targets = [e for e in nearby() if matches(e)
               and mc.player.isWithinEntityInteractionRange(e, 0.0)
               and mc.player.hasLineOfSight(e)]
    if not targets:
        return None
    if mc.player.getAttackStrengthScale(0.0) < 1.0:
        return {'state': 'cooldown'}
    target = min(targets, key=lambda e: mc.player.distanceTo(e))
    mc.player.lookAt(Anchor.EYES, target.getEyePosition())
    result = {'state': 'hit', 'id': target.getId(), 'health_before': target.getHealth()}
    mc.gameMode.attack(mc.player, target)
    mc.player.swing(Hand.MAIN_HAND, Animation.DEFAULT, True)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--attack', action='store_true', help='attack matching villagers within normal reach')
    args = parser.parse_args()
    print(client.call(snapshot))
    if args.attack:
        for _ in range(120):
            result = client.call(hit)
            if result is None:
                break
            if result['state'] == 'hit':
                print(result)
            ticks.wait(5)
        print(client.call(snapshot))
