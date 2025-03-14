# ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

from Kekik.cli           import konsol
from urllib.parse        import urlparse
from Crypto.Cipher       import AES
from Crypto.Util.Padding import unpad
import requests, base64, json

class InatIstek:
    def __init__(self, aes_key: bytes = b"C3V4HUpUbGDOjxEl"):
        self.aes_key = aes_key

    def hostname_al(self, url: str) -> str:
        parsed_url = urlparse(url)
        if hostname := parsed_url.hostname:
            return hostname

        raise ValueError(f"Geçersiz URL: {url}")

    def istek_yap(self, url: str) -> str | None:
        try:
            hostname = self.hostname_al(url)
        except ValueError as hata:
            raise ValueError(f"URL'den hostname çıkarılamadı: {url}") from hata

        headers = {
            "Cache-Control"    : "no-cache",
            "Content-Length"   : "37",
            "Content-Type"     : "application/x-www-form-urlencoded; charset=UTF-8",
            "Host"             : hostname,
            "Referer"          : "https://speedrestapi.com/",
            "X-Requested-With" : "com.bp.box",
            "User-Agent"       : "speedrestapi"
        }

        key_str = self.aes_key.decode()
        body    = f"1={key_str}&0={key_str}"

        try:
            response = requests.post(url, headers=headers, data=body)
            if response.status_code != 200:
                raise ValueError(f"İstek başarısız. HTTP Durum Kodu: {response.status_code}")

            json_str = self.sifre_coz(response.text)
            return json.loads(json_str)
        except Exception as hata:
            raise ValueError(f"İstek sırasında hata oluştu: {url}") from hata

    def sifre_coz(self, sifreli_metin: str) -> str | None:
        try:
            iv = self.aes_key
            
            parcalar = sifreli_metin.split(":")
            if not parcalar or not parcalar[0]:
                raise ValueError("Geçersiz şifreli metin formatı.")
            
            cipher1       = AES.new(self.aes_key, AES.MODE_CBC, iv)
            ilk_adim_veri = unpad(cipher1.decrypt(base64.b64decode(parcalar[0])), AES.block_size)
            
            ara_parcalar = ilk_adim_veri.decode().split(":")
            if not ara_parcalar or not ara_parcalar[0]:
                raise ValueError("Ara şifre çözme adımı geçersiz çıktı üretti.")
            
            cipher2          = AES.new(self.aes_key, AES.MODE_CBC, iv)
            ikinci_adim_veri = unpad(cipher2.decrypt(base64.b64decode(ara_parcalar[0])), AES.block_size)
            
            return ikinci_adim_veri.decode()
        except Exception as hata:
            raise ValueError(f"Şifre çözme işlemi başarısız. : {hata}") from hata


inat    = InatIstek()
veriler = inat.istek_yap("https://dizibox.rest/amz/index.php")
for veri in veriler:
    baslik = veri.get("diziName") or veri.get("chName")
    if baslik == "@inattvapk":
        continue

    konsol.print(veri)

    detay_url = veri.get("diziUrl") or veri.get("chUrl")
    detay     = inat.istek_yap(detay_url)
    konsol.print(detay)
    break