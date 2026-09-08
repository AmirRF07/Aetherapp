## Aether 1.2.9 (r3)

Same version, same signing key: install straight over your current Aether, no uninstall needed.

**Security hardening (audit score 79 -> 93/100)**

- Diagnostics log encrypted on the device with a hardware-backed key.
- The engine's WireGuard private key and WARP identity are sealed while disconnected.
- "Share VPN" now needs a username and password from other devices, and only accepts them from your local network. Both are shown in the Share card.
- A root certificate installed on the device can no longer intercept the app's own traffic.
- The app checks its own signature and shows the APK hash in About, so you can verify your download against the list below.
- New button: copy the log with all addresses and identifiers removed.

**New look when connected**

- The tick is gone. Connected now shows Aether's own animated A: colour-cycling, light sweep, internal scan.


<!-- previous notes -->
# AetherMobile v1.2.9

> Install straight over an older build from the same repository: the signing
> configuration is unchanged. App version <span dir="ltr">1.2.9</span>, version
> code <span dir="ltr">13</span>, engine core <span dir="ltr">1.9.0</span>.

## What's new in v1.2.9

- **Gemini AI, with your own free API key.** Paste a key from Google AI Studio into *Settings → Assistant* and press **Test the API connection**. The app asks that key which models it may use and shows exactly that list - nothing is hard-coded, because a free key, a paid key and a key from a region where a model has not launched all see different models. A model is picked for you automatically.
  - **An AI icon next to every option.** Tap the mark beside any setting for what it is, what it is for and how to use it, in your own language. Every answer is grounded in a factual description of that setting written from the engine's real behaviour, so the model explains something true instead of guessing what "Noize" or "Scan mode" means in a VPN app. That factual description appears instantly, with or without a key, and answers are cached so reopening one costs no quota. The icons can be switched off.
  - **Settings advice from your own log.** On every connect, or on demand, Gemini reads a redacted excerpt of the session log, reports what your operator's inspection appears to be doing to the connection, and proposes the settings that fit it. Credentials of any shape are stripped and every public IPv4 is masked to its /16 before anything leaves the device. Nothing is applied until you press **Apply**, unless you turn automatic apply on yourself.
  - **A chat inside the app**, from the menu or the AI button on the home screen. Ask anything; ask it to change something and it proposes a patch you approve with one tap.
  - **The AI may only change tuning.** Protocol, scan mode, obfuscation, MTU, fragmentation, ECH, DNS, reconnect behaviour, TLS groups, exit country and similar. It can NEVER change the network backend, the upstream proxy, routing rules, a pinned endpoint, which apps are tunnelled, or any credential - the things that decide which traffic is protected and where it goes. That boundary is an allow-list in code, not prompt wording. Applied changes reach the engine on your next connect, and the app says so every time.
  - **Your key is a credential and is treated as one:** sealed with a hardware-backed AES-GCM key from the Android Keystore, never in the plain settings file, never in the diagnostics log, and sent to Google in a request header rather than a URL. Resetting the app settings does not delete it.
  - **The AI needs the tunnel, in the chained `Aether → Psiphon` mode**, and that is a fact rather than a policy: the app deliberately excludes its own package from the VPN, so a normal HTTP client would leave on the operator's network in the clear and fail - while telling that operator this device just tried to reach a blocked AI endpoint. Every AI request is therefore dialled through the tunnel's own local SOCKS5 proxy, with the hostname resolved at the exit and TLS verified on the device. And Google's AI endpoints refuse Cloudflare WARP exit addresses, which is the symptom 1.2.8 shipped notes about, so the chained mode is the one that works. When either condition is missing, every AI screen says which and offers the button that fixes it.
  - **No new dependency.** JSON is the platform's `org.json`; HTTP, TLS and SOCKS5 are hand-rolled over `java.net`, the same way the app already probes its own tunnel. The APK gains no third-party library.
- **The settings icon on the home screen is a shortcut again.** Tapping the icon in the top corner used to open the *whole* settings tree, the same one the menu button in the other corner opens. It now shows only what a shortcut should: the **Tunnel** group (Connection, Transport & anti-DPI, DNS & routing rules, Upstream proxy) and the **reset all settings** action at the bottom. Everything else - This device, App, diagnostics, sharing, About - stays in the menu, where it already was. Nothing was removed from the app: the same pages are one tap away in the menu, and the back gesture now leaves settings from the shortcut instead of dropping you into the full list.
- **Engine (core) upgraded to v1.9.0** (previous: v1.8.0), with this app's own engine patches rebased onto the new sources rather than overwritten:
  - **You can name the two hops of WARP-in-WARP (gool) yourself.** New engine options `--wiw-outer` / `--wiw-inner` / `--wiw-peers` (and `--wiw-scan` to go back to hunting for both). Naming one hop lets the scan find the other, the port has to be written out, and the two hops must be different edges. A malformed address is now reported before an account is provisioned instead of silently falling back to a scan.
  - **MASQUE over HTTP/2 is no longer capped by its own carrier.** The HTTP/2 flow-control windows were the RFC minimum of 64 KB, which limits any download over that transport to roughly 500 KB/s on a 130 ms path however fast the line really is. The windows now follow the device tier, DATA frames may be 64 KB, outbound packets already queued behind one another are sent in one frame instead of one frame each, sending moved to a task of its own (so a busy upload can no longer stall the download beside it), and the HTTP/2 tunnel gets a full 1500-byte inner MTU instead of the 1280 that only QUIC needs.
  - **Engine buffers can be tuned per device** without a rebuild (`AETHER_NETSTACK_TCP_RX`, `AETHER_NETSTACK_TCP_TX`, `AETHER_MASQUE_MTU`), and the engine's `help` output now documents every option together with the environment variable that sets it.
- **Everything 1.2.8 fixed stays exactly as it was.** This was a condition of the upgrade, not an afterthought: core 1.9.0 independently re-sized the same data-plane buffers 1.2.8 spent five rounds getting right, and those upstream numbers were **not** taken. The netstack keeps its CUBIC congestion control, its bounded uplink send buffer and its bandwidth-delay-product receive window, the datagram sockets keep the split receive/send sizing from r6, and the TCP stack stays pinned to the version the app's netstack is written against. Download speed and connect behaviour on every protocol are the 1.2.8 ones.
- **The next automatic core upgrade is safer than this one was.** Pristine upstream copies of *all ten* app-patched engine files are now cached as the merge baseline (1.2.8 cached two), so CI's three-way rebase can never mistake an app patch for an upstream deletion.
- **Version:** app <span dir="ltr">1.2.9</span>, version code <span dir="ltr">13</span>, engine core <span dir="ltr">1.9.0</span>. Installs straight over 1.2.8 from the same repository - the signing configuration is unchanged.
## Verify what you install

Each release ships per-ABI APKs plus their SHA-256 sums, and the signer
fingerprint is printed in the build log. See `docs/SIGNING.md`.

---

<div dir="rtl">

# ‏AetherMobile نسخهٔ ۱.۲.۹

> روی نسخهٔ قدیمی‌تر از همین مخزن مستقیم نصب می‌شود؛ تنظیمات امضا دست‌نخورده است. نسخهٔ برنامه <span dir="ltr">1.2.9</span>، <span dir="ltr">version code 13</span>، هستهٔ موتور <span dir="ltr">1.9.0</span>.

## تازه‌های نسخهٔ ۱.۲.۹

- **هوش مصنوعی Gemini، با کلید API رایگان خودتان.** کلید را از Google AI Studio در «تنظیمات ← دستیار» وارد کنید و **تست اتصال به API** را بزنید. برنامه از خودِ کلید می‌پرسد به چه مدل‌هایی دسترسی دارد و دقیقاً همان فهرست را نشان می‌دهد - هیچ چیزی ثابت نوشته نشده، چون یک کلید رایگان، یک کلید پرداخت‌شده و کلیدی از منطقه‌ای که مدلی در آن عرضه نشده، سه فهرست متفاوت می‌بینند. یک مدل هم خودکار برایتان انتخاب می‌شود.
  - **یک آیکن AI کنار هر گزینه.** روی علامت کنار هر تنظیم بزنید تا بگوید این چیست، چه کاربردی دارد و چطور باید از آن استفاده کرد - به زبان خودتان. پایهٔ هر پاسخ، توصیفی واقعی از همان تنظیم است که از رفتار واقعی موتور نوشته شده، پس مدل چیزی درست را توضیح می‌دهد و حدس نمی‌زند که «Noize» یا «حالت اسکن» در یک برنامهٔ VPN چه معنایی دارد. آن توصیف واقعی بی‌درنگ - با کلید یا بی کلید - نشان داده می‌شود و پاسخ‌ها ذخیره می‌شوند، پس باز کردن دوبارهٔ یک توضیح سهمیه مصرف نمی‌کند. آیکن‌ها قابل خاموش کردن‌اند.
  - **پیشنهاد تنظیمات از دل لاگ خودتان.** در هر بار اتصال، یا هر وقت خودتان بخواهید، جمینای بخشی پاک‌سازی‌شده از لاگ نشست را می‌خواند، گزارش می‌دهد بازرسی اپراتور شما با این اتصال چه می‌کند و تنظیمات متناسب با آن را پیشنهاد می‌دهد. پیش از آنکه چیزی از گوشی بیرون برود، هر چیزی که شکل اطلاعات محرمانه دارد حذف و هر آدرس عمومی <span dir="ltr">IPv4</span> تا <span dir="ltr">/16</span> ماسک می‌شود. تا **اعمال** را نزنید چیزی اعمال نمی‌شود، مگر آنکه خودتان اعمال خودکار را روشن کنید.
  - **یک چت‌بات داخل برنامه**، از منو یا از دکمهٔ AI در صفحهٔ اصلی. هر چیزی بپرسید؛ و اگر بخواهید چیزی را عوض کند، تغییر را پیشنهاد می‌دهد و با یک لمس تأییدش می‌کنید.
  - **هوش مصنوعی فقط تنظیمات بهینه‌سازی را می‌تواند عوض کند.** پروتکل، حالت اسکن، مبهم‌سازی، <span dir="ltr">MTU</span>، تکه‌تکه‌کردن، <span dir="ltr">ECH</span>، <span dir="ltr">DNS</span>، رفتار اتصال مجدد، گروه‌های <span dir="ltr">TLS</span>، کشور خروج و مانند این‌ها. **هرگز** نمی‌تواند حالت شبکه، پراکسی بالادست، قواعد مسیریابی، اندپوینت دستی، اینکه کدام برنامه‌ها از تونل رد می‌شوند یا هیچ اطلاعات محرمانه‌ای را عوض کند - یعنی همان چیزهایی که تعیین می‌کنند کدام ترافیک محافظت می‌شود و کجا می‌رود. این مرز یک فهرست مجاز در کد است، نه متن پرامپت. تغییرهای اعمال‌شده در اتصال بعدی به موتور می‌رسند و برنامه هر بار همین را می‌گوید.
  - **کلید شما یک اطلاعات محرمانه است و همان‌طور با آن رفتار می‌شود:** با کلید سخت‌افزاری <span dir="ltr">AES-GCM</span> از <span dir="ltr">Android Keystore</span> رمزنگاری می‌شود، هرگز در فایل معمولی تنظیمات و هرگز در لاگ عیب‌یابی نمی‌آید، و در هدر درخواست به گوگل فرستاده می‌شود نه در آدرس. بازگرداندن تنظیمات برنامه آن را پاک نمی‌کند.
  - **هوش مصنوعی به تونل و به حالت ترکیبی <span dir="ltr">`Aether → Psiphon`</span> نیاز دارد**، و این یک واقعیت است نه یک سیاست: برنامه عمداً پکیج خودش را از VPN بیرون می‌گذارد، پس یک کلاینت HTTP معمولی از شبکهٔ اپراتور و بدون رمز بیرون می‌رفت و شکست می‌خورد - و در همان مسیر به اپراتور می‌گفت این دستگاه سراغ یک سرویس هوش مصنوعی بلاک‌شده رفته است. بنابراین هر درخواست از پراکسی <span dir="ltr">SOCKS5</span> محلی خودِ تونل رد می‌شود، نام مقصد در نقطهٔ خروج رزولو می‌شود و <span dir="ltr">TLS</span> روی خودِ گوشی بررسی می‌شود. از سوی دیگر سرویس‌های هوش مصنوعی گوگل آدرس‌های خروجی <span dir="ltr">Cloudflare WARP</span> را نمی‌پذیرند - همان مشکلی که در یادداشت ۱.۲.۸ آمد - پس حالت ترکیبی همان حالتی است که کار می‌کند. اگر یکی از این دو شرط نباشد، هر صفحهٔ هوش مصنوعی می‌گوید کدام شرط کم است و دکمهٔ رفعش را نشان می‌دهد.
  - **هیچ وابستگی تازه‌ای اضافه نشد.** ‏JSON با <span dir="ltr">`org.json`</span> خودِ اندروید خوانده می‌شود و <span dir="ltr">HTTP</span>، <span dir="ltr">TLS</span> و <span dir="ltr">SOCKS5</span> دستی روی <span dir="ltr">`java.net`</span> نوشته شده‌اند، همان‌طور که برنامه از قبل تونل خودش را بررسی می‌کند. حجم APK هیچ کتابخانهٔ جانبی تازه‌ای نمی‌گیرد.
- **آیکن تنظیمات در صفحهٔ اصلی دوباره یک میان‌بر است.** زدن آیکن گوشهٔ بالا قبلاً *همهٔ* درخت تنظیمات را باز می‌کرد؛ همان چیزی که دکمهٔ منو در گوشهٔ دیگر باز می‌کند. حالا فقط چیزی را نشان می‌دهد که از یک میان‌بر انتظار می‌رود: گروه **تونل** (اتصال، ترابری و ضدDPI، DNS و قواعد مسیریابی، پراکسی بالادست) و دکمهٔ **بازگرداندن همهٔ تنظیمات** در پایین آن. باقی بخش‌ها - این دستگاه، برنامه، عیب‌یابی، اشتراک‌گذاری و درباره - همان‌جا که بودند، در منو می‌مانند. هیچ چیزی از برنامه حذف نشده است: همان صفحه‌ها با یک لمس از منو در دسترس‌اند و حرکت بازگشت از میان‌بر، مستقیم از تنظیمات بیرون می‌آید و شما را وسط فهرست کامل رها نمی‌کند.
- **ارتقای هستهٔ موتور (Core) به نسخهٔ <span dir="ltr">1.9.0</span>** (نسخهٔ قبلی: <span dir="ltr">1.8.0</span>)، با بازاعمال پچ‌های اختصاصی خودِ برنامه روی سورس جدید - نه بازنویسی آن‌ها:
  - **می‌توانید دو هاپ حالت WARP-in-WARP (گول) را خودتان تعیین کنید.** گزینه‌های تازهٔ موتور: <span dir="ltr">`--wiw-outer`</span> / <span dir="ltr">`--wiw-inner`</span> / <span dir="ltr">`--wiw-peers`</span> و <span dir="ltr">`--wiw-scan`</span> برای بازگشت به جست‌وجوی هر دو. اگر فقط یک هاپ را نام ببرید، اسکن هاپ دیگر را پیدا می‌کند؛ نوشتن پورت الزامی است و دو هاپ باید دو لبهٔ متفاوت باشند. آدرس نامعتبر حالا پیش از ساخت هویت گزارش می‌شود، نه آنکه بی‌صدا به اسکن برگردد.
  - **حالت MASQUE روی HTTP/2 دیگر با سقف خودِ حامل محدود نمی‌شود.** پنجره‌های کنترل جریان HTTP/2 روی حداقل استاندارد یعنی ۶۴ کیلوبایت بودند و همین، دانلود روی این ترابری را در مسیری با تأخیر ۱۳۰ میلی‌ثانیه به حدود ۵۰۰ کیلوبایت بر ثانیه محدود می‌کرد، هر چقدر هم خط زیرین سریع باشد. حالا پنجره‌ها بر اساس ردهٔ دستگاه تعیین می‌شوند، فریم‌های DATA می‌توانند ۶۴ کیلوبایتی باشند، بسته‌های خروجی که پشت هم در صف‌اند در یک فریم فرستاده می‌شوند (نه هر کدام در یک فریم)، ارسال به یک تسک مستقل منتقل شده است (تا آپلود سنگین نتواند دانلود همزمان را متوقف کند) و تونل HTTP/2 یک <span dir="ltr">MTU</span> کامل ۱۵۰۰ بایتی می‌گیرد، به‌جای ۱۲۸۰ که فقط QUIC به آن نیاز دارد.
  - **بافرهای موتور بدون بیلد مجدد قابل تنظیم‌اند** (<span dir="ltr">`AETHER_NETSTACK_TCP_RX`</span>، <span dir="ltr">`AETHER_NETSTACK_TCP_TX`</span>، <span dir="ltr">`AETHER_MASQUE_MTU`</span>) و خروجی <span dir="ltr">`help`</span> موتور حالا هر گزینه را همراه متغیر محیطی معادلش مستند می‌کند.
- **هر چیزی که ۱.۲.۸ درست کرد، دقیقاً دست‌نخورده مانده است.** این شرط ارتقا بود، نه یک یادآوری جانبی: هستهٔ <span dir="ltr">1.9.0</span> مستقلاً همان بافرهای مسیر دادهٔ ۱.۲.۸ را - که پنج دور کار روی آن‌ها انجام شده بود - دوباره اندازه‌گذاری کرده و آن اعداد بالادستی **اعمال نشدند**. کنترل ازدحام CUBIC، بافر ارسال محدودشدهٔ آپلود، پنجرهٔ دریافت متناسب با حاصل‌ضرب پهنای‌باند در تأخیر، تقسیم اندازهٔ بافر دریافت/ارسال سوکت‌های دیتاگرام از <span dir="ltr">r6</span>، و پین‌شدن نسخهٔ پشتهٔ TCP که netstack برنامه بر اساس آن نوشته شده - همه سر جای خود هستند. سرعت دانلود و رفتار اتصال در همهٔ پروتکل‌ها همان ۱.۲.۸ است.
- **ارتقای خودکار بعدیِ هسته از این یکی هم ایمن‌تر است.** نسخهٔ دست‌نخوردهٔ بالادست برای **هر ده** فایل پچ‌خوردهٔ موتور به‌عنوان مبنای ادغام ذخیره شد (در ۱.۲.۸ فقط دو فایل بود)، بنابراین ادغام سه‌طرفهٔ CI هرگز نمی‌تواند یک پچ برنامه را با حذفِ بالادست اشتباه بگیرد.
- **نسخه:** برنامه <span dir="ltr">1.2.9</span>، <span dir="ltr">version code 13</span>، هستهٔ موتور <span dir="ltr">1.9.0</span>. مستقیم روی ۱.۲.۸ از همین مخزن نصب می‌شود؛ تنظیمات امضا دست‌نخورده است.
## چیزی که نصب می‌کنید را بررسی کنید

هر ریلیز فایل‌های APK به‌ازای هر ABI را همراه با مجموع <span dir="ltr">SHA-256</span> آن‌ها منتشر می‌کند و اثر انگشت امضاکننده در لاگ بیلد چاپ می‌شود. <span dir="ltr">`docs/SIGNING.md`</span> را ببینید.

</div>

## Verify your download

Open **About** in the app: it shows the APK SHA-256 and the signing certificate. They must match this list.

| File | SHA-256 |
| --- | --- |
| `Aether-1.2.9-arm64-v8a.apk` | `9fa3e7357e9098df05680234364184e386371d97a9b72a6a076d854f8e974094` |
| `Aether-1.2.9-armeabi-v7a.apk` | `13800ca8362b699e3594c7deb126445c18ca780a699d0f855e1d590584a6e439` |
| `Aether-1.2.9-universal.apk` | `4e2f8a65119648f2a25cd45b40968bd591fd033ac71e6c1cc9b9e5ceeae0c73b` |

Signing certificate SHA-256: `91b3019f60bff82594c342e5123c5cfaf8d14d9bdefb3be5cfd8187459cd7583`

---

درود به دوستان عزیز 🌹
ایدهٔ اضافه‌کردن قابلیت هوش مصنوعی به برنامه، با دو هدف اصلی شکل گرفت: هدف اول، ساده‌ترکردن کار با تنظیمات مختلف برنامه و هدف دوم، کمک به کاربران برای شناسایی و برطرف‌کردن مشکلات اتصال.
هدف اول: راهنمایی دربارهٔ تنظیمات برنامه
بسیاری از کاربران هنگام کار با برنامه با این مشکل مواجه بودند که تنظیمات متعددی در آن وجود دارد، اما کاربرد هر تنظیم، زمان مناسب استفاده و نحوهٔ پیکربندی آن برایشان کاملاً روشن نیست.
به همین دلیل، پس از اضافه‌شدن قابلیت هوش مصنوعی، در کنار هر تنظیم یک آیکون مربوط به Gemini قرار گرفته است. با انتخاب این آیکون، توضیحی ساده و کاربردی دربارهٔ عملکرد آن تنظیم و موارد استفادهٔ آن نمایش داده می‌شود.
اگر توضیحات ارائه‌شده کافی نبود، می‌توانید گزینهٔ «متوجه نشدید؟ از دستیار بپرسید» را انتخاب کنید. با این کار، موضوع موردنظر به چت‌بات منتقل می‌شود و هوش مصنوعی به‌صورت خودکار با این درخواست، گفتگو را ادامه می‌دهد:
«این توضیح را به‌خوبی متوجه نشدم؛ لطفاً توضیحات بیشتری ارائه بده.»
پس از آن، توضیحات کامل‌تری در محیط چت نمایش داده می‌شود. همچنین می‌توانید در همان گفتگو، هر سؤال دیگری را دربارهٔ برنامه، تنظیمات یا نحوهٔ استفاده از آن مطرح کنید.
هدف دوم: مشاور تنظیمات هوش مصنوعی
این قابلیت برای خود من نیز تا حد زیادی غیرمنتظره بود؛ زیرا انتظار نداشتم هوش مصنوعی بتواند به این شکل عملی و کاربردی در برنامه پیاده‌سازی شود.
در بخش تنظیمات و قسمت هوش مصنوعی اتر، بخشی با عنوان «مشاور تنظیمات» قرار دارد. با ورود به این بخش و انتخاب گزینهٔ «بررسی نشست»، هوش مصنوعی لاگ زندهٔ برنامه را در همان لحظه بررسی و تحلیل می‌کند.
پس از پایان بررسی، نتیجه به شما نمایش داده می‌شود. اگر مشکلی در اتصال یا تنظیمات وجود داشته باشد، هوش مصنوعی آن مشکل را توضیح می‌دهد و تنظیمات پیشنهادی و بهینه‌ای را برای برطرف‌کردن آن ارائه می‌کند.
با انتخاب دکمهٔ «اعمال»، تنظیمات پیشنهادی در برنامه اعمال می‌شوند. برای فعال‌شدن کامل این تغییرات، لازم است ابتدا یک‌بار اتصال را قطع و سپس دوباره برقرار کنید. تنظیمات جدید در اتصال بعدی فعال خواهند شد.
نکتهٔ قابل‌توجه این است که در حدود ۹۰ درصد مواقع، تنظیمات پیشنهادی هوش مصنوعی بسیار دقیق و کاربردی بوده‌اند و توانسته‌اند مشکل را به‌طور کامل برطرف کنند.
به‌نظر من، این قابلیت می‌تواند تحول مهمی در برنامه‌های مشابه ایجاد کند؛ زیرا کاربران با هر سطحی از دانش فنی می‌توانند مشکلات خود را ساده‌تر برطرف کنند و درک بهتری از نحوهٔ کار با تنظیمات برنامه به دست آورند.
راهنمای دریافت کلید API و انتخاب مدل هوش مصنوعی
برای استفاده از قابلیت‌های هوش مصنوعی، ابتدا باید یک کلید API رایگان از سایت [Google AI Studio](https://aistudio.google.com/) دریافت کنید. پس از ساخت کلید، آن را در بخش «کلید API» برنامه وارد کنید و سپس گزینهٔ «تست اتصال» را بزنید.
اگر کلید API معتبر باشد، پیام موفقیت نمایش داده می‌شود و مدل‌های هوش مصنوعی در برنامه قابل‌استفاده خواهند شد.
نکتهٔ بسیار مهم: برای استفاده از هوش مصنوعی در برنامه، حتماً از حالت اتصال ترکیبی Aether → Psiphon استفاده کنید. در حالت اتصال Aether به‌تنهایی، آی‌پی‌های Cloudflare در اختیار شما قرار می‌گیرند و سرویس‌های گوگل، ازجمله سرویس‌های هوش مصنوعی، این آی‌پی‌ها را شناسایی می‌کنند؛ در نتیجه ممکن است نتوانید از قابلیت هوش مصنوعی در برنامه استفاده کنید.
مدل gemini-3.8-flash به‌دلیل استفاده از تعداد توکن بیشتر، معمولاً سریع‌تر به سقف مصرف روزانه می‌رسد. اگر این مدل به محدودیت روزانه رسید، می‌توانید آن را به gemini-3.1-flash-lite تغییر دهید و دوباره از قابلیت‌های هوش مصنوعی استفاده کنید.
همچنین می‌توانید مدل‌های زیر را نیز امتحان کنید:

gemini-3.1-flash-lite-preview
gemini-flash-lite-latest

این نکته را در نظر داشته باشید که مدل gemini-3.8-flash معمولاً زودتر از سایر مدل‌ها به سقف مصرف روزانه می‌رسد؛ بنابراین در صورت مشاهدهٔ خطای محدودیت مصرف، تغییر مدل می‌تواند مشکل را برطرف کند.
راهکار هنگام دریافت خطا
گاهی ممکن است هنگام ارسال درخواست یا پرسیدن سؤال از هوش مصنوعی، پاسخ‌گویی کمی زمان ببرد یا خطایی نمایش داده شود. در چنین شرایطی، ابتدا گزینهٔ «تلاش دوباره» را انتخاب کنید.
برای نمونه، ممکن است پیام زیر یا پیامی مشابه آن نمایش داده شود:
«به سقف مصرف یا محدودیت سرعت رسیده‌اید؛ کمی صبر کنید و دوباره امتحان کنید.»
اگر پس از یک یا دو بار تلاش همچنان پاسخی دریافت نکردید، مدل هوش مصنوعی را به یکی از مدل‌های معرفی‌شده تغییر دهید. در بسیاری از موارد، تغییر مدل باعث برطرف‌شدن مشکل می‌شود.
نکتهٔ مهم دربارهٔ مشاور تنظیمات
توصیه می‌شود فقط زمانی از مشاور تنظیمات هوش مصنوعی استفاده کنید که با افت محسوس سرعت، مشکل اتصال یا سایر اختلالات مرتبط با عملکرد برنامه مواجه شده‌اید.
همچنین می‌توانید در محیط چت از هوش مصنوعی بخواهید لاگ برنامه را بررسی کند. هوش مصنوعی لاگ را تحلیل می‌کند، مشکل احتمالی را توضیح می‌دهد و تنظیمات پیشنهادی را در اختیارتان قرار می‌دهد. پس از آن، می‌توانید تنظیمات پیشنهادی را تنها با انتخاب یک دکمه اعمال کنید.
نکتهٔ پایانی دربارهٔ حریم خصوصی
هیچ اطلاعات حساس یا مهمی از لاگ‌های برنامه برای هوش مصنوعی ارسال نمی‌شود؛ در صورت فعال‌کردن قابلیت هوش مصنوعی، فقط خلاصه‌ای پاک‌سازی‌شده از لاگ‌های فنی و بدون اطلاعات شخصی ارسال خواهد شد.
این قابلیت تنها زمانی فعال است که خودتان کلید API شخصی‌تان را وارد و هوش مصنوعی را فعال کنید. در حالت پیش‌فرض، هیچ درخواستی به سرویس‌های هوش مصنوعی ارسال نمی‌شود. پیش از ارسال نیز اطلاعات حساس مانند کلیدها، توکن‌ها، رمزهای عبور، شناسه‌ها، UUIDها، آدرس‌های عمومی IP، تاریخچهٔ مرور، درخواست‌های DNS، دامنه‌های بازدیدشده، محتوای ترافیک، فایل‌ها، مخاطبان، شناسهٔ دستگاه و موقعیت مکانی از لاگ حذف می‌شوند.
برای مطالعهٔ توضیحات کامل دربارهٔ نحوهٔ پاک‌سازی لاگ‌ها و حفظ حریم خصوصی، می‌توانید به [README فارسی پروژه](https://github.com/QW-AI-Code/Aether/blob/main/README.fa.md) مراجعه کنید.
