# ScrapeFlix v0.5.0

v0.4.0 üzerine kurulan gerçek site profil editörü ve canlı HTML önizleme sürümüdür.

## v0.5.0 yenilikleri
- Gerçek site profil editörü
- Site HTML'ini editör açıldığında yükleme
- Selector değişikliklerini 250 ms debounce ile canlı uygulama
- İçerik selector'ının yakaladığı kartları anında listeleme
- Başlık, görsel, link ve açıklama selector'larını birlikte test etme
- Yakalanan kart sayısını gösterme
- Geçersiz CSS selector hatasını editörde gösterme
- Görsel ve açıklama içeren kart önizlemesi
- İlk 30 kartı canlı önizlemede gösterme; gerçek tarama limiti 500 olarak korunur
- Otomatik analiz, manuel profil kaydetme ve normal tarama akışı korunmuştur

## Derleme
Android Studio veya Gradle ile `app:assembleDebug` çalıştırılabilir.
