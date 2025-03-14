# ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

from Kekik.cli import konsol
from requests  import get
from parsel    import Selector
from re        import findall, search

bolum_lik = "https://belgeselx.com/belgesel/cifte-hayatlara-onculuk-etmek-aciklanamayanlar-william-shatner"
istek     = get(bolum_lik)

alternatifler = findall(r"""<iframe\s+[^>]*src=\\\"([^\\\"']+)\\\"""", istek.text)
konsol.print(alternatifler)

for alternatif in alternatifler:
    iframe_istek = get(alternatif, headers={"Referer": bolum_lik})

    if "new4.php" in alternatif:
        for kaynak in findall(r"""file:\"([^\"]+)\", label: \"([^\"]+)""", iframe_istek.text):
            video_url = kaynak[0]
            quality   = kaynak[1]
            konsol.print(f"video_url » {video_url}\nquality » {quality}\n-------\n")
    else:
        secici = Selector(iframe_istek.text)
        konsol.print(secici.css("iframe::attr(src)").get())