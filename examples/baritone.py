"""Import once, then call go(x, y, z) to submit a Baritone goal."""
import sys
import java
from graalweave import client

BaritoneAPI = java.type('baritone.api.BaritoneAPI')
GoalBlock = java.type('baritone.api.pathing.goals.GoalBlock')


def go(x, y, z):
    def submit():
        baritone = BaritoneAPI.getProvider().getPrimaryBaritone()
        baritone.getCustomGoalProcess().setGoalAndPath(GoalBlock(x, y, z))
    client.call(submit)


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('Usage: baritone.py X Y Z')
    go(*map(int, sys.argv[1:]))
