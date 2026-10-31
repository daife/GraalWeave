"""Read the current GUI and player state in Minecraft 26.3; no screenshot."""
import java
from graalweave import client

Minecraft = java.type('net.minecraft.client.Minecraft')


def snapshot():
    mc = Minecraft.getInstance()
    # Older examples use mc.screen; in 26.3 the public getter is mc.gui.screen().
    screen = mc.gui.screen()
    result = {'screen': screen.getClass().getSimpleName() if screen else None,
              'paused': mc.isPaused()}
    if mc.player is not None:
        p = mc.player
        result.update(position=str(p.position()), health=p.getHealth(),
                      hand=str(p.getMainHandItem()))
    return result


if __name__ == '__main__':
    print(client.call(snapshot))
