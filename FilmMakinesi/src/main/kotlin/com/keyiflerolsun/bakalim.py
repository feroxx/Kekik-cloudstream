# ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

from Kekik.cli    import konsol
from cloudscraper import CloudScraper
from parsel       import Selector
from re           import findall
from base64       import b64decode

oturum  = CloudScraper()
oturum.headers.update({"User-Agent":"Mozilla/5.0", "Referer":"https://filmmakinesi.film/"})

film_link = "https://filmmakinesi.film/film/yaban-kedisi-izle-2022/"

istek   = oturum.get(film_link)
secici  = Selector(istek.text)
iframe  = secici.css("div.player-div iframe::attr(data-src)").get()
konsol.print(iframe)

i_source = oturum.get(iframe)
atob     = findall(r"""aHR0[0-9a-zA-Z+\/=]*""", i_source.text)[0]
if padding_needed := len(atob) % 4:
    atob += "=" * (4 - padding_needed)

m3u_link = b64decode(atob).decode("utf-8")
konsol.print(m3u_link)

i_selector = Selector(i_source.text)
for track in i_selector.css("track"):
    label = track.css("::attr(label)").get()
    src   = track.css("::attr(src)").get()
    konsol.print(f"{label} | {src}")

# m3u_link = findall(r"""contentUrl\": \"([^\"]+)""", i_source.text)[0]
# print(m3u_link)
# oturum.headers.update({"Referer": "https://closeload.filmmakinesi.film/"})
# konsol.print(oturum.get(m3u_link).text)


# thumbnail = findall(r"""closeload\.filmmakinesi\.film\/img\/([^\"]+)\.jpg""", i_source.text)[0]
# posible   = f"https://balancehls6.closeload.com/hls/{thumbnail}.mp4/master.txt"
# oturum.headers.update({"Referer": "https://closeload.filmmakinesi.film/"})
# konsol.print(oturum.get(posible))