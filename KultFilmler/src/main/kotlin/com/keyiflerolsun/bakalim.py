# ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

from Kekik.cli    import konsol
from cloudscraper import CloudScraper
from parsel       import Selector
from re           import findall
from base64       import b64decode

def get_iframe(source_code):
    atob = findall(r"""PHA\+[0-9a-zA-Z+\/=]*""", source_code)[0]
    if padding_needed := len(atob) % 4:
        atob += "=" * (4 - padding_needed)

    iframe = b64decode(atob).decode("utf-8")
    return Selector(iframe).css("iframe::attr(src)").get()

oturum  = CloudScraper()
oturum.headers.update({"User-Agent":"Mozilla/5.0", "Referer":"https://kultfilmler.com/"})

film_link = "https://kultfilmler.com/perfume-the-story-of-a-murderer-koku-bir-katilin-hikayesi/"

istek  = oturum.get(film_link)
secici = Selector(istek.text)

iframeler = [get_iframe(istek.text)]

for bak in secici.css("div.parts-middle"):
    alternatif_link  = bak.css("a::attr(href)").get()
    alternatif_istek = oturum.get(alternatif_link)

    iframeler.append(get_iframe(alternatif_istek.text))

konsol.print(iframeler)
for iframe in iframeler:
    if "vidmoly" in iframe:
        oturum.headers.update({
            "User-Agent"     : "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36",
            "Sec-Fetch-Dest" : "iframe"
        })
        istek = oturum.get(f"https:{iframe}")
        konsol.print(findall(r"file:\"([^\"]+)", istek.text)[0])