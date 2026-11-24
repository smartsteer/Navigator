"""Usuwa z DSN bloki (plane …) — wypełnione strefy, których Freerouting 1.9 nie czyta poprawnie.
Użycie: /usr/bin/python3 dsn_bez_plaszczyzn.py wej.dsn wyj.dsn"""
import sys
s = open(sys.argv[1]).read()
out, i = [], 0
while True:
    j = s.find("(plane ", i)
    if j < 0:
        out.append(s[i:])
        break
    out.append(s[i:j])
    d, k = 0, j
    while True:
        if s[k] == "(":
            d += 1
        elif s[k] == ")":
            d -= 1
            if d == 0:
                break
        k += 1
    i = k + 1
s = "".join(out).replace("(type power)", "(type signal)")
open(sys.argv[2], "w").write(s)
