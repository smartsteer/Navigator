"""Usuwa wskazane sieci z pliku DSN (Freerouting ich nie prowadzi — np. GND, którą robią wylewki + dotrasuj.py).
Użycie: /usr/bin/python3 dsn_bez_sieci.py wej.dsn wyj.dsn GND [INNA…]"""
import re, sys
s = open(sys.argv[1]).read()
for n in sys.argv[3:]:
    s = re.sub(r'\n\s*\(net "?%s"?\n\s*\(pins[^)]*\)\n\s*\)' % re.escape(n), "", s)
    s = re.sub(r'(\(class [^\n]*?) %s(?=[ \n)])' % re.escape(n), r"\1", s)
open(sys.argv[2], "w").write(s)
