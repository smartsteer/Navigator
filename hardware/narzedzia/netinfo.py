"""Pomocnik: sieci z netlisty KiCad → {nazwa: [(ref, pin)]}."""
import re,sys
def nets(path):
    s=open(path).read(); s=s[s.index('(nets'):]
    out={}
    for blk in s.split('(net\n')[1:]:
        n=re.search(r'\(name "([^"]*)"\)',blk).group(1)
        out[n]=re.findall(r'\(ref "([^"]*)"\)\s*\(pin "([^"]*)"\)',blk)
    return out
if __name__=='__main__':
    d=nets(sys.argv[1])
    for k in sys.argv[2:]: print(k,len(d.get(k,[])),d.get(k))
