"""Compact API discovery: gw run explore.py [CLASS_NAME [MEMBER_NAME]]."""
import sys
from graalweave import api

if len(sys.argv) > 1:
    result = api.inspect(sys.argv[1], query=sys.argv[2] if len(sys.argv) > 2 else '',
                         exact=len(sys.argv) > 2)
else:
    result = api.classes('Minecraft', mod='minecraft', exact=True)
print(result)
