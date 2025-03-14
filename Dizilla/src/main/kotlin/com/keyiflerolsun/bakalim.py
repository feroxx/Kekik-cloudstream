# ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

from Kekik.cli    import konsol
# from cloudscraper import CloudScraper as Session
from httpx        import Client as Session
from parsel       import Selector

mainUrl = "https://dizilla.club"
oturum  = Session()

istek   = oturum.get(mainUrl)
secici  = Selector(istek.text)
c_key   = secici.css("input[name=cKey]::attr(value)").get()
c_value = secici.css("input[name=cValue]::attr(value)").get()



oturum.cookies.clear()
oturum.headers.update({
    "Accept"           : "application/json, text/javascript, */*; q=0.01",
    "X-Requested-With" : "XMLHttpRequest",
    "Referer"          : f"{mainUrl}/",
})
oturum.cookies.set("showAllDaFull", "true")
oturum.cookies.set("PHPSESSID", istek.cookies["PHPSESSID"])
istek = oturum.post(
    f"{mainUrl}/bg/searchcontent",
    data = {
        "cKey"       : c_key,
        "cValue"     : c_value,
        "searchterm" : "the"
    }
)
try:
    konsol.print(istek.json()["data"]["result"])
except KeyError:
    konsol.print(istek.json())