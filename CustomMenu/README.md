# Custom Menu (custommenu)
Minecraft menülerini kendi fotoğraf, video, GIF ve müziklerinle kişiselleştir!

İkon ChatGPT tarafından tasarlandı (assets/ klasöründe).

## Durum (dürüst tablo)
| Parça | Durum |
|---|---|
| Ortak çekirdek (common/): ayarlar, kayıt, klasör tarama, dosya kopyalama | Yazıldı, derlenmedi |
| Forge 1.20.1: PNG/JPG gösterme (ana menü, ESC, game over), arka planda yükleme | Yazıldı, TEST EDİLMEDİ |
| Ayar ekranı (mod + 4 ekran), kendi dosya yöneticisi (gezinme, arama, boyut, kopyalama) | Forge 1.20.1'de yazıldı, TEST EDİLMEDİ |
| Küçük önizlemeler | Yok |
| GIF animasyonu (en fazla 480px genişlik, ~48 MB kare belleği) | Forge 1.20.1'de yazıldı, TEST EDİLMEDİ |
| MP3 çalma (JLayer + OpenAL, ekran açıkken çalar, ~3-4 dk üstü kesilir) | Forge 1.20.1'de yazıldı, TEST EDİLMEDİ |
| MP4 video (JCodec, sadece H.264, sessiz; ses için ayrı MP3 seçilir) | Forge 1.20.1'de yazıldı, TEST EDİLMEDİ |
| Ana menü görseli (panorama yerine geçirme, reflection ile) | Yazıldı, TEST EDİLMEDİ |
| Intro ekranı (Mojang yüklemesinden sonra, ana menüden önce; dokununca atlanır) | Forge 1.20.1'de yazıldı, TEST EDİLMEDİ |
| Bitiş ekranı (Ender ejderhası sonrası kredi yazıları) | Görsel yazıların üstüne yarı saydam çizilir (oyunun kendi arka planı opak olduğu için) |
| Sistem dosya seçicisi | Yapılmayacak (kendi yönetici yeterli) |
| Fabric 1.20.1, NeoForge 1.21.1, Fabric 1.21.5, Fabric 26.3 | Sadece mod bilgisi + ikon |
| JAR | Yok (GitHub Actions ile derlenebilir: .github/workflows) |



## Sürüm 1.2.0
- Ses: biçim dosyanın içeriğine bakılarak seçilir (MP3 hatalara toleranslı, OGG, WAV, M4A/AAC). TEST EDİLMEDİ.
- Video: kareler zaman damgasına göre sıralanır, tempo zaman damgasıyla tutulur (ileri-geri takılma düzeltmesi). TEST EDİLMEDİ.
- Video sesi: ekran başına 'Video sesi' ayarı, videonun kendi AAC sesini çalar (JCodec, yansıma ile). TEST EDİLMEDİ.

## Sürüm 1.3.0
- FCL 'geçerli bir ResourcePackInfo yükleyemedi' hatası: modda pack.mcmeta yoktu; Forge 1.20.1 her mod JAR'ında bu dosyayı ister. Eklendi (pack_format 15).
- Tek medya oturumu: ana menü / ESC / bitiş / intro ekranına her girişte video+müzik sıfırdan ve birlikte başlar; çıkınca video oynatıcı, ses kaynağı ve video dokusu bırakılır.
- Video ve ses senkron başlar (video ilk kareyi gösterip sesi bekler). 'Video sesi' açıkken video döngüsünde ses de baştan başlar.
- Ses önbelleği: aynı müziğe tekrar girişte yeniden çözülmez; oyundayken ~1 dk sonra boşaltılır.
- DERLENMEDİ/OYUNDA TEST EDİLMEDİ: Minecraft/Forge bağımlılıkları olmadan sadece çekirdek + AudioPlayer sözdizimi kontrol edildi, GIF çözücü gerçek GIF ile çalıştırıldı.

## Sürüm 1.3.1
- Video sesi hatası (ClassNotFoundException ...EsdsBox): AAC ayar baytları artık dosyadan okunuyor (gerçek bir MP4 ile doğrulandı: 12 10 = AAC-LC 44100 Hz stereo).
- Ses akışlı çalıyor (0,5 sn'lik parçalar, OpenAL tampon kuyruğu): dev bellek dizileri yok, ses neredeyse anında başlar.
- Video: kare dizileri havuzdan, sıralama gerekmiyorsa kopyalama yok, çözme geri kalırsa anahtar kareye atlayıp sese yetişir.
- Intro: ilk 1,5 sn siyah ekran; oyunun açılış yükü bitmeden video/ses başlamaz.
- Derleme hatası (getPointer) önceki sürümde düzeltildi.
- DERLENMEDİ/OYUNDA TEST EDİLMEDİ (sadece sahte API'lerle sözdizimi + ASC okuyucu gerçek MP4 ile).
