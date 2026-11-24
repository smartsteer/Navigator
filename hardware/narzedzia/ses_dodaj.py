"""Import SES z Freeroutingu bez kasowania istniejących ścieżek (gdy w DSN były one „fix” — SES zawiera wtedy tylko nowe).
Użycie: /usr/bin/python3 ses_dodaj.py plik.ses plytka.kicad_pcb"""
import subprocess, sys, os
import pcbnew
ses, pcb = sys.argv[1:3]
b = pcbnew.LoadBoard(pcb)
unl = 0
for t in b.GetTracks():
    if not t.IsLocked():
        t.SetLocked(True)
        t.SetFlags(0)
        unl += 1
b.Save(pcb)
# zapamiętane: które były odblokowane — po imporcie odblokowujemy wszystko poza przelotkami EP (te były zablokowane wcześniej)
locked_before = set()
subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "..", "maszyna", "kicad", "ses2pcb.py"), ses, pcb], check=True)
print("zablokowane na czas importu:", unl)
