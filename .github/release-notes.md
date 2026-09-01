# AetherMobile v1.2.7

> Install straight over an older build from the same repository: the signing
> configuration is unchanged. App version <span dir="ltr">1.2.7</span>, version
> code <span dir="ltr">11</span>, engine core <span dir="ltr">1.8.0</span>.

## What you get in this release

**⚙️ A completely new Settings screen.** Settings is a screen of its own now, laid
out the way your phone's own system apps are: a list of **categories** you tap
into, controls **grouped into cards**, and choices presented as a full-width
**bottom-sheet picker** with the current option ticked. Each category row shows its
current value, so the whole configuration is readable without opening anything.
*Slider icon, top right of the home screen — or ☰ → Settings.* The deep navy
palette is now **pinned**, so the app renders in the same colours on every phone
and every Android version. "Reset all settings" asks for confirmation first.

**🌐 Pick the app's language yourself: English or فارسی.** *Settings → Language*,
with **Follow the phone**, **English** and **فارسی**. Each row is labelled in its
own language, so the one you want is recognisable even when the app is currently in
the other. It covers everything — the status **notification**, the **Quick Settings
tile** and the **home-screen widget** switch with it — and فارسی mirrors the whole
interface **right to left** while `ip:port`, CIDR ranges and proxy URLs stay left to
right. On Android 13+ the choice also appears under *System settings → Apps →
Aether → Language*.

**🚀 A much faster, smoother app.** Settings opens **immediately** instead of
sliding in and then hitching, and scrolling and toggling stay smooth on low-end
hardware: each page builds only the rows on screen, carries a tenth of what one
screen used to carry, the side menu is a light menu rather than a stack of live
panels, and the home screen's animations stop entirely while you are in settings.
Typing in a field is a pure in-memory edit written to disk once when you pause, and
changing one setting repaints one row rather than the whole screen.

**⚡ Keep full speed on a reconnect: "Only reuse a fast endpoint".** Reconnecting
reuses the last endpoint that worked, but "it worked" is not "it is still fast" — a
cached endpoint can answer at three or four times the round-trip time of the best
one available, which alone can **roughly halve download and upload** for the whole
session, and in the chained mode that latency is paid on both hops. The cached
endpoint is now timed as well as tested: over budget, it is passed over and a normal
scan picks a faster one (a few seconds, once) which is then what gets remembered.
Stricter automatically for `Aether → Psiphon` than for plain Aether. **On by
default**: *Settings → Transport & anti-DPI*.

**🔀 Automatic exit-server rotation, so Google opens too.** Some Psiphon servers are
perfectly healthy and still refuse particular destinations: the tunnel is up, the
speed is fine, Telegram loads everything — and Google will not open. The app now
recognises that pattern (**many different destinations** refused in a short window
is filtering, not a hiccup) and **moves the session to another exit server by
itself**. The tunnel, the TUN interface and every local port stay as they are, so it
reads as a brief stall rather than a disconnect, and rotations are rate-limited and
capped per session. Real **UDP gets a clean slate** with the new server instead of
staying downgraded for the rest of the session.

**🛠️ Your DNS, routing, upstream-proxy and Zero Trust settings now take effect.**
Everything on *DNS & routing rules*, *Upstream proxy* and *Zero Trust* is handed to
the engine at start: in-tunnel resolvers, block and bypass lists, domain rules
matched on the name read from a flow's first bytes, the upstream proxy, automatic
identity replacement and the full Zero Trust enrolment. Organization credentials are
read straight from the hardware-backed secure store when the tunnel starts.

**🌍 The chained `Aether → Psiphon` mode.** Plain Aether leaves through
Cloudflare's anycast edge, and on Iranian networks the address you come out on is
very often an Iranian one, which plenty of services refuse exactly as they refuse
your real address. The chained mode swaps the exit while keeping Aether's
obfuscation on the hop that has to survive the local network, so the exit IP is a
real foreign one and **AI services such as Gemini open normally**.
*Advanced → Network backend → `Aether → Psiphon`*, with an optional exit-country
picker (flags included) right below it. Expect a slower connect: two hops warm up.

**🌐 Real UDP through the chain.** A UDP-capable SOCKS5 front owns the port
tun2socks talks to and multiplexes every association onto one remote udpgw stream
through Psiphon, so DNS and QUIC work. If a server refuses that port forward,
port-53 datagrams fall back to DNS-over-TCP through the same tunnel, so name
resolution never depends on it.

**🔬 A fifth self-test step: Device DNS (SOCKS5 UDP).** It speaks the protocol the
device's forwarder actually uses, against the same port, and it gates the
Connected state: if your phone could not resolve a name, the app does not claim to
be connected. *Side menu → Diagnostics → Run self-test.*

**🧱 One row per fact in the connection card.** Protocol, endpoint and latency each
get a full-width row, so `WIREGUARD` and a whole `ip:port` are readable instead of
truncated to `...`.

**📈 A live ping-strength meter** on the latency row: a travelling waveform whose
height and colour follow the last probe, mint / amber / rose, with a quality word.

**📱 The home screen always fits on one screen.** The block is measured against the
room your device has and scaled down by one measured factor if it needs to be, so
there is nothing left to scroll on any phone, in either language, at any system
font size. It is a real layout at a real density, so the result stays perfectly
sharp.

**🌈 The travelling light runs in the primary colours** — one colour per lap, red →
green → blue → yellow, drawn as five graded additive strokes with round joins so
the light is a sharp filament instead of a banded blob.

**✔️ A large tick on the connect button** once you are through, and no competing
ring around it.

**🔀 Upstream proxy, domain rules behind the tunnel, identity replacement.** Chain
Aether through a proxy already running on the phone (an HTTP CONNECT proxy
switches MASQUE to HTTP/2 for you); match Block/Direct domain rules on the name
read from the first bytes of a flow; register a fresh device identity when
Cloudflare refuses the saved one.

**🚀 Engine core 1.8.0.** World-reachable-listener warning, non-blocking HTTP proxy
head read, hardened SOCKS5 auth on the upstream-proxy dialer, and a fix for the
tokio "JoinHandle polled after completion" panic during Gool teardown. No new
engine flags. Verify what you are running in *About → Engine (core) version*.

**🙋 About, reordered.** This edition and its author (the Android app, the GUI and
the chained transports, `github.com/QW-AI-Code`) come first; the upstream Aether
engine (Cluvex Studio) is credited under it.

## Verify what you install

Each release ships per-ABI APKs plus their SHA-256 sums, and the signer
fingerprint is printed in the build log. See `docs/SIGNING.md`.

---

<div dir="rtl">

# ‏AetherMobile نسخهٔ ۱.۲.۷

> روی نسخهٔ قدیمی‌تر از همین مخزن مستقیم نصب می‌شود؛ تنظیمات امضا دست‌نخورده است. نسخهٔ برنامه <span dir="ltr">1.2.7</span>، <span dir="ltr">version code 11</span>، هستهٔ موتور <span dir="ltr">1.8.0</span>.

## در این نسخه چه چیزی به دست می‌آورید

**⚙️ صفحهٔ تنظیمات کاملاً نو.** تنظیمات حالا صفحهٔ مستقل خودش را دارد، با همان چیدمانی که اپ‌های سیستمی گوشی شما دارند: فهرستی از **دسته‌ها** که روی هرکدام می‌زنید و باز می‌شود، کنترل‌ها **داخل کارت‌های گروه‌بندی‌شده**، و انتخاب گزینه‌ها در یک **شیت پایین‌صفحه** تمام‌عرض که گزینهٔ فعلی در آن تیک خورده است. کنار هر دسته مقدار فعلی‌اش نوشته شده، پس کل تنظیمات بدون باز کردن هیچ صفحه‌ای خواندنی است. *آیکن تنظیمات در بالا-راست صفحهٔ اصلی — یا ☰ ← تنظیمات.* پالت **سرمه‌ای تیره** حالا **قفل شده** است، پس برنامه روی هر گوشی و هر نسخهٔ اندروید با همان رنگ‌ها اجرا می‌شود. «بازنشانی همهٔ تنظیمات» هم اول تأیید می‌گیرد.

**🌐 زبان برنامه را خودتان انتخاب کنید: فارسی یا English.** *تنظیمات ← زبان*، با سه گزینهٔ **پیروی از گوشی**، **English** و **فارسی**. عنوان هر ردیف به زبان خودش نوشته شده، پس حتی وقتی برنامه به آن یکی زبان است، ردیفی که می‌خواهید قابل تشخیص است. همه‌جا را می‌گیرد — **نوتیفیکیشن** وضعیت، **تایل تنظیمات سریع** و **ویجت صفحهٔ اصلی** هم با آن عوض می‌شوند — و فارسی کل رابط را **راست‌چین** می‌کند، در حالی که <span dir="ltr">`ip:port`</span>، رنج‌های CIDR و آدرس پراکسی چپ‌چین می‌مانند. روی اندروید ۱۳ و بالاتر، انتخاب شما در *تنظیمات سیستم ← برنامه‌ها ← Aether ← زبان* هم دیده می‌شود.

**🚀 برنامه بسیار سریع‌تر و روان‌تر.** تنظیمات **بدون تأخیر** باز می‌شود به‌جای اینکه اول بیاید و بعد گیر کند، و اسکرول و روشن/خاموش کردن کلیدها روی گوشی‌های ضعیف هم روان می‌ماند: هر صفحه فقط ردیف‌های روی صفحه را می‌سازد و یک‌دهم چیزی را حمل می‌کند که قبلاً یک صفحه حمل می‌کرد، منوی کنار یک منوی سبک است نه انباری از پنل‌های زنده، و انیمیشن‌های صفحهٔ اصلی وقتی داخل تنظیمات هستید کاملاً متوقف می‌شوند. تایپ در فیلدها یک ویرایش کاملاً در حافظه است که یک‌بار و وقتی دست نگه می‌دارید ذخیره می‌شود، و تغییر یک تنظیم فقط همان یک ردیف را بازترسیم می‌کند.

**⚡ حفظ سرعت کامل هنگام اتصال مجدد: «فقط اندپوینت سریع را دوباره استفاده کن».** اتصال مجدد از آخرین اندپوینتی که کار کرده بود استفاده می‌کند، اما «کار کرده بود» با «هنوز سریع است» یکی نیست: یک اندپوینت کش‌شده می‌تواند با سه یا چهار برابر تأخیرِ بهترین اندپوینتِ موجود جواب بدهد، و همین یک مورد می‌تواند **سرعت دانلود و آپلود را تا حدود نصف** برای کل نشست کم کند — در حالت ترکیبی این تأخیر روی **هر دو هاپ** پرداخت می‌شود. اندپوینت کش‌شده حالا علاوه بر سالم بودن، **زمان‌سنجی** هم می‌شود: اگر از بودجه بیشتر بود از آن رد می‌شویم و یک اسکن معمولی اندپوینت سریع‌تری پیدا می‌کند (چند ثانیه، یک‌بار) و همان برای دفعهٔ بعد به خاطر سپرده می‌شود. این بودجه برای <span dir="ltr">`Aether → Psiphon`</span> خودکار سخت‌گیرانه‌تر است. **پیش‌فرض روشن**: *تنظیمات ← ترنسپورت و ضد DPI*.

**🔀 چرخش خودکار سرور خروجی، تا گوگل هم باز شود.** بعضی سرورهای سایفون کاملاً سالم‌اند و با این حال مقصدهای خاصی را رد می‌کنند: تانل بالاست، سرعت خوب است، تلگرام همه‌چیز را باز می‌کند — و گوگل باز نمی‌شود. برنامه حالا این الگو را تشخیص می‌دهد (**رد کردن مقصدهای متعدد و متفاوت** در یک بازهٔ کوتاه یعنی فیلتر کردن، نه یک اختلال گذرا) و **نشست را خودش روی یک سرور خروجی دیگر منتقل می‌کند**. تانل، رابط TUN و همهٔ پورت‌های محلی سر جای خودشان می‌مانند، پس شما یک وقفهٔ کوتاه می‌بینید نه یک قطعی، و تعداد این چرخش‌ها در هر نشست محدود و فاصله‌دار است. **UDP** واقعی هم با سرور تازه **از صفر شروع می‌کند** به‌جای آنکه تا آخر نشست تنزل‌یافته بماند.

**🛠️ تنظیمات DNS، مسیریابی، پراکسی بالادست و Zero Trust شما حالا اعمال می‌شوند.** هر چیزی که در *DNS و قواعد مسیریابی*، *پراکسی بالادست* و *Zero Trust* تنظیم می‌کنید هنگام اجرای موتور به آن داده می‌شود: سرورهای DNS داخل تونل، فهرست‌های مسدود و عبور مستقیم، تطبیق قواعد دامنه بر اساس نامی که از اولین بایت‌های هر جریان خوانده می‌شود، پراکسی بالادست، تعویض خودکار هویت و کل فرایند عضویت Zero Trust. اطلاعات محرمانهٔ سازمانی در لحظهٔ اتصال مستقیماً از حافظهٔ امن سخت‌افزاری خوانده می‌شوند.

**🌍 حالت ترکیبی <span dir="ltr">`Aether → Psiphon`</span>.** اتر به‌تنهایی از لبهٔ <span dir="ltr">anycast</span> کلادفلر خارج می‌شود و روی شبکه‌های ایران آی‌پی خروجی معمولاً یک آی‌پی ایران است، که خیلی از سرویس‌ها دقیقاً مثل آی‌پی واقعی شما آن را رد می‌کنند. حالت ترکیبی **خروجی** را عوض می‌کند و در همان حال مبهم‌سازی اتر را روی هاپی که باید از فیلترینگ محلی رد شود نگه می‌دارد، پس آی‌پی خروجی یک آی‌پی خارجی واقعی است و **سرویس‌های هوش مصنوعی مثل جمینای عادی باز می‌شوند**. *پیشرفته ← <span dir="ltr">Network backend</span> ← <span dir="ltr">Aether → Psiphon</span>*، با انتخاب اختیاری کشور خروجی (همراه پرچم) درست زیرش. اتصال کندتر است، چون دو هاپ باید گرم شوند.

**🌐 عبور واقعی UDP از زنجیره.** یک فرانت <span dir="ltr">SOCKS5</span> با پشتیبانی UDP مالک همان پورتی است که <span dir="ltr">tun2socks</span> با آن حرف می‌زند و همهٔ نشست‌ها را روی یک استریم <span dir="ltr">udpgw</span> از دل سایفون مالتی‌پلکس می‌کند، پس DNS و QUIC کار می‌کنند. اگر سروری آن پورت‌فوروارد را رد کند، دیتاگرام‌های پورت ۵۳ از مسیر <span dir="ltr">DNS-over-TCP</span> روی همان تانل جواب می‌گیرند، پس ترجمهٔ نام هیچ‌وقت به آن وابسته نیست.

**🔬 گام پنجم خودآزمایی: <span dir="ltr">Device DNS (SOCKS5 UDP)</span>.** با همان پروتکلی حرف می‌زند که فورواردر گوشی استفاده می‌کند و روی همان پورت، و **شرط اعلام حالت «متصل»** است: اگر گوشی نتواند نام‌ها را ترجمه کند، برنامه نمی‌گوید وصل شدید. *منوی کنار ← عیب‌یابی ← اجرای خودآزمایی.*

**🧱 هر اطلاعات یک ردیف مستقل در کارت اتصال.** پروتکل، اندپوینت و تأخیر هرکدام یک ردیف تمام‌عرض دارند، پس <span dir="ltr">`WIREGUARD`</span> و یک <span dir="ltr">`ip:port`</span> کامل خوانده می‌شوند و به <span dir="ltr">`...`</span> بریده نمی‌شوند.

**📈 نمایشگر زندهٔ قدرت پینگ** روی ردیف تأخیر: یک موج متحرک که ارتفاع و رنگش از آخرین اندازه‌گیری می‌آید — نعنایی، کهربایی، سرخ — همراه با یک کلمهٔ کیفیت.

**📱 کل صفحهٔ اصلی در یک صفحه جا می‌شود.** بلوک محتوا با فضایی که دستگاه شما دارد اندازه‌گیری می‌شود و اگر لازم باشد با یک ضریب اندازه‌گیری‌شده کوچک می‌شود، پس روی هر گوشی، در هر دو زبان و با هر اندازهٔ فونت سیستم چیزی برای اسکرول کردن نمی‌ماند. چون یک چیدمان واقعی در چگالی واقعی است، نتیجه کاملاً واضح می‌ماند.

**🌈 نور چرخان با رنگ‌های اصلی** — هر دور یک رنگ، **قرمز ← سبز ← آبی ← زرد** — که با پنج استروکِ جمع‌شدنی و اتصال گوشه‌های گرد زده می‌شود تا نور یک رشتهٔ تیز باشد نه یک لکهٔ پله‌پله.

**✔️ یک تیک بزرگ روی دکمهٔ اتصال** وقتی وصل شدید، بدون هیچ حلقهٔ نورانی رقیبی دور خودش.

**🚀 هستهٔ موتور <span dir="ltr">1.8.0</span>.** هشدار برای لیسنر قابل‌دسترس از بیرون دستگاه، خواندن غیرمسدودکنندهٔ هدر پراکسی HTTP، سخت‌سازی احراز هویت <span dir="ltr">SOCKS5</span> در دایلر پراکسی بالادست، و رفع پنیک <span dir="ltr">"JoinHandle polled after completion"</span> در tokio هنگام بستن نشست Gool. نسخهٔ در حال اجرا را از *درباره ← نسخهٔ هسته* ببینید.

## چیزی که نصب می‌کنید را بررسی کنید

هر ریلیز فایل‌های APK به‌ازای هر ABI را همراه با مجموع <span dir="ltr">SHA-256</span> آن‌ها منتشر می‌کند و اثر انگشت امضاکننده در لاگ بیلد چاپ می‌شود. <span dir="ltr">`docs/SIGNING.md`</span> را ببینید.

</div>
