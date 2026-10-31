import java
from graalweave import client

Minecraft = java.type('net.minecraft.client.Minecraft')
FabricLoader = java.type('net.fabricmc.loader.api.FabricLoader')


def inspect_player():
    mc = Minecraft.getInstance()
    if mc.player is None:
        return 'No world is open'
    return {'position': str(mc.player.position()),
            'health': mc.player.getHealth()}


print('Player:', client.call(inspect_player))
print('Loaded mods:', [str(mod.getMetadata().getId())
                       for mod in FabricLoader.getInstance().getAllMods()])
