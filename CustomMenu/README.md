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
