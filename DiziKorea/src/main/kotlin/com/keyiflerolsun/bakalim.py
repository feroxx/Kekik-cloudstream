# ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

from Kekik.cli    import konsol
from cloudscraper import CloudScraper as Session
# from httpx        import Client as Session
from parsel       import Selector

mainUrl = "https://dizikorea.vip"
oturum  = Session()

oturum.headers.update({
    "X-Requested-With" : "XMLHttpRequest",
    "Referer"          : f"{mainUrl}/",
})

istek = oturum.post(
    f"{mainUrl}/search",
    data = {
        "query" : "kurek"
    }
)

for item in Selector(istek.json()["theme"]).css("ul li"):
    href = item.css("a::attr(href)").get()
    if "/dizi/" not in href and "/film/" not in href:
        continue
    
    konsol.print(href)