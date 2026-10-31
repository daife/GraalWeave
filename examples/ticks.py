from graalweave import events, ticks

print('Waiting for 20 client ticks...')
ticks.wait(20)
print('Finished')

# Run with --session automation to retain the subscription after this file returns.
joined = events.subscribe('join', lambda: print('Joined a world'))
# Later, in the same session: joined.close()
