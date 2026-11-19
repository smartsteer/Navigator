"""Przygotowanie DSN z KiCad do Freeroutingu 1.9: bez płaszczyzn, GND tylko F/In1/B, V12 tylko F/In2/B,
reszta tylko F/B (warstwy wewnętrzne zostają płaszczyznami). Użycie: python3 dsn_prep.py wej.dsn wyj.dsn"""
import re,sys
s=open(sys.argv[1]).read()
s=re.sub(r'    \(plane [^\n]*\n','',s)
s=s.replace('(type power)','(type signal)')
s=s.replace('(class Zasilanie BUCK_SW GND V3V3 V5','(class Zasilanie BUCK_SW V3V3 V5')
s=s.replace('(class Moc12V V12 V12_ZAWORY','(class Moc12V V12_ZAWORY')
s=re.sub(r'(\(circuit\n\s*\(use_via [^)]*\))', r'\1\n        (use_layer F.Cu B.Cu)', s)
extra='''    (class Masa GND
      (circuit
        (use_via Via[0-3]_600:300_um)
        (use_layer F.Cu In1.Cu B.Cu)
      )
      (rule
        (width 400)
        (clearance 150.1)
      )
    )
    (class Plaszczyzna12V V12
      (circuit
        (use_via Via[0-3]_800:400_um)
        (use_layer F.Cu In2.Cu B.Cu)
      )
      (rule
        (width 800)
        (clearance 150.1)
      )
    )
  )
  (wiring'''
s=s.replace('  )\n  (wiring',extra,1)
open(sys.argv[2],'w').write(s)
