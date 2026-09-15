## Aether 1.3.0

Installs straight over your current Aether — same signing key, no uninstall.
App version 1.3.0, version code 14, engine core 2.0.0.

### Tor is back, and this time it works

Four new modes under **Settings → Connection → Network backend**:

- **Tor** — Tor alone. Your exit is a Tor exit node.
- **Aether → Tor** — Aether connects first, then Tor is built *inside* that tunnel.
  **This is the one to use if your network blocks Tor:** the network never sees a
  Tor connection, only Aether's obfuscated transport, and the bootstrap runs at
  tunnel speed.
- **Tor → Psiphon** — Psiphon dialled through Tor. Your exit is a Psiphon address
  reached from a Tor one, so sites that block Tor exit nodes open again.
- **Tor → Aether** — the mirror image: Tor first, the Aether tunnel *inside* it.
  Your exit is a WARP address, but your network sees only Tor and cannot tell a VPN
  is in use. **Use it if WARP itself is blocked or throttled where you are** while
  Tor still gets through. It carries normal UDP, and it runs on MASQUE over HTTP/2
  because that is the only carrier Tor can hold.

Plain Tor brings bridges with it: if the network blocks Tor, the app fetches
bridges for your country and runs them through the transport shipped inside the
APK. No CAPTCHA, nothing to paste in — though you can paste in your own bridge
lines if you have some.

**Tor carries TCP only** — everywhere, in every app — so the app puts a small SOCKS
front in front of it: DNS is resolved over TCP *inside* Tor, hostnames are passed to
Tor unresolved (so `.onion` addresses work and nothing is looked up on your device)
and the remaining UDP is dropped. QUIC is dropped with it and apps fall back to TCP.

Tor is slower than the other modes, and the first connect takes a while: Tor
downloads a directory before it can build a circuit. While it does, the
notification shows how far the bootstrap has got. That wait is Tor, not the app.

### Also new

- **MASQUE×2** (`--mim`), a fifth protocol: MASQUE inside MASQUE, two hops, for an
  exit address in a different range than one hop gives.
- **Engine core 2.0.0** (was 1.9.0). Everything 1.2.8 fixed about download speed
  and connect behaviour is unchanged — those numbers were re-checked file by file,
  and upstream's own re-tuning of the same buffers was deliberately not taken.
- **The AI assistant is told which mode you are actually in**, so it stops
  suggesting WARP settings in a mode that has no WARP tunnel. It can read the Tor
  settings and explain them; it cannot change them. New explanations sit next to
  every Tor setting, in both languages.
- Three engine Tor settings are now in **Settings → Tor**: bridge country,
  bootstrap patience and the reachability check. The last one is worth knowing about
  if Tor keeps reporting a failed bootstrap while seeming otherwise fine — the
  engine's default proof target, `check.torproject.org`, is itself blocked on some
  networks.
- Bridges are available in every mode except **Aether → Tor**, where Tor is dialled
  through the tunnel and a bridge would have nothing to hide from. The row says so.

### 🔒 Security audit 1.3.0 — 88 / 100 audited, 93 / 100 shipped

A full mobile-app security audit was run over the shipped tree: the Kotlin app
(87 files, ≈28 200 lines), the Gradle and resource configuration, the manifest, the
vendored Rust engine and its lockfile, the prebuilt Psiphon library and the release
workflow. Full report: [`docs/SECURITY_AUDIT_1.3.0.md`](../docs/SECURITY_AUDIT_1.3.0.md).

| # | Area | Weight | Audited | After the fixes |
| --- | --- | --- | --- | --- |
| 1 | Secrets & key management | 15 | 95 | 95 |
| 2 | Cryptography, TLS & MitM resistance | 14 | 92 | 95 |
| 3 | Data-leak risk (DNS, IPv6, tunnel bypass) | 20 | 88 | 95 |
| 4 | Local storage at rest | 13 | 94 | 94 |
| 5 | Permissions & OS configuration | 8 | 98 | 98 |
| 6 | Logging & diagnostics | 8 | 96 | 96 |
| 7 | Code quality & network configuration | 10 | 78 | 84 |
| 8 | On-device exposure (screen, clipboard) | 6 | 65 | 92 |
| 9 | Supply chain & build integrity | 6 | 72 | 84 |
| | **Weighted total** | **100** | **88** | **93** |

**Verified as correct.** No API key, token or private key is hardcoded anywhere in
the app; your Gemini key and the LAN sharing password are sealed with AES-256-GCM
under a non-exportable Android Keystore key. No weak or obsolete crypto. **No
custom `TrustManager`, no permissive hostname verifier and no MitM path was
found** — every hand-rolled TLS socket verifies the certificate against the host
name, and the app trusts **system CAs only**, so a root certificate installed
through Settings (how an interception proxy works) cannot decrypt the app's own
traffic. Hostnames are never resolved on your device; in Tor modes DNS is answered
over TCP inside Tor and non-DNS UDP is dropped rather than leaked; `::/0` is routed
unconditionally in chained modes. The diagnostics log and the engine's identity
file (which holds the WireGuard private key) are encrypted at rest, and the log
mirror switches off rather than falling back to plaintext if the keystore refuses a
key. Cleartext HTTP is denied app-wide, backups and device transfer are denied
twice over, five permissions are requested with no `QUERY_ALL_PACKAGES` and no
exported provider, every `PendingIntent` is immutable, there is no WebView, and the
app contains **no analytics, no crash-reporting SDK and no tracking library of any
kind**.

**Seven of the ten findings were fixed before this release went out**, which is
what the second score column measures. The kill switch is **on by default** now,
and its lockdown interface routes `::/0` unconditionally — a blackhole has no
connectivity to break, so gating it on the IPv6 switch only ever left a v6 path
open in the window the kill switch exists for. `FLAG_SECURE` covers the surfaces
that show secrets (API key, LAN password, Access token, the open diagnostics log,
the crash report): no screenshot, no screen recording, no recents thumbnail. The
clipboard copies of those secrets are flagged sensitive, so Android 13+ keeps them
out of the paste preview. Both cleartext geolocation fallbacks are gone — the exit
IP now only ever comes from a TLS connection with the certificate checked. CI
gained a `cargo audit` step and Dependabot watches the Gradle dependency set in one
grouped pull request a month; the Psiphon binary has a provenance record.

**Still open, deliberately.** R8 is off: a reflection break in Compose or in the
Psiphon library shows up on a device, not in a unit test, and a broken release is
worse for the people who need this app than an unminified one. CI actions are still
pinned by tag rather than commit SHA. Both are documented in the report.

**Read the fixes as source-level.** They were reviewed and built, and the unit
suite (67 tests) passes, but no one has yet confirmed on a phone that the recents
thumbnail is blank.

**Out of scope:** app signing and update compatibility. 1.3.0 keeps the signing
identity of 1.2.9 on purpose so it installs over your existing app without an
uninstall; no signing item is scored above, which is why this number is not
comparable with the 79 / 100 of the 1.2.9 report.

### Verify what you install

Every release publishes a per-ABI APK with its SHA-256 sum, and the signer
fingerprint is printed in the build log — compare the list at the bottom of this
page with what you see in **About**. Full explanation in
[`docs/SIGNING.md`](../docs/SIGNING.md).

---

<div dir="rtl" align="right">

<h1 dir="rtl" align="right">‏<span dir="ltr">Aether</span> نسخهٔ ۱.۳.۰</h1>

<p dir="rtl" align="right">‏مستقیم روی نسخهٔ فعلی اتر نصب می‌شود — همان کلید امضا، بدون حذف برنامه. نسخهٔ برنامه ۱.۳.۰، کد نسخه ۱۴، هستهٔ موتور ۲.۰.۰.</p>

<h2 dir="rtl" align="right">‏تور برگشت، و این بار کار می‌کند</h2>

<p dir="rtl" align="right">‏چهار حالت تازه در <strong>تنظیمات ← اتصال ← بک‌اند شبکه</strong>:</p>

<ul dir="rtl" align="right">
<li align="right"><strong>تور</strong> — فقط تور. خروجی شما یک گرهٔ خروجی تور است.</li>
<li align="right"><strong>اتر ← تور</strong> — اول اتر وصل می‌شود، بعد تور <em>داخل</em> همان تونل ساخته می‌شود. <strong>اگر شبکهٔ شما تور را بلاک می‌کند، همین را انتخاب کنید:</strong> شبکه هیچ‌وقت یک اتصال تور نمی‌بیند، فقط ترابری مبهم‌سازی‌شدهٔ اتر را، و بوت‌استرپ با سرعت تونل پیش می‌رود.</li>
<li align="right"><strong>تور ← سایفون</strong> — سایفون از داخل تور گرفته می‌شود. خروجی شما یک آی‌پی سایفون است که از آی‌پی تور به آن رسیده‌اید، پس سایت‌هایی که گره‌های خروجی تور را بلاک می‌کنند باز می‌شوند.</li>
<li align="right"><strong>تور ← اتر</strong> — قرینهٔ حالت قبل: اول تور، بعد تونل اتر <em>داخل</em> آن. خروجی شما یک آی‌پی <span dir="ltr">WARP</span> است، ولی شبکهٔ شما فقط تور را می‌بیند و نمی‌تواند بفهمد VPNـی در کار است. <strong>اگر جایی هستید که خودِ ورپ بلاک یا کم‌سرعت شده</strong> و تور همچنان رد می‌شود، از این استفاده کنید. این حالت <span dir="ltr">UDP</span> معمولی را هم حمل می‌کند و روی <span dir="ltr">MASQUE</span> روی <span dir="ltr">HTTP/2</span> کار می‌کند، چون تنها حاملی است که تور می‌تواند نگه دارد.</li>
</ul>

<p dir="rtl" align="right">‏حالت تور پل‌ها را با خودش می‌آورد: اگر شبکه تور را بلاک کند، برنامه برای کشور شما پل می‌گیرد و آن‌ها را از ترابری‌ای که داخل خودِ <span dir="ltr">APK</span> هست عبور می‌دهد. نه کپچایی، نه چیزی که لازم باشد جایی بچسبانید — هرچند اگر پل‌های خودتان را دارید، می‌توانید واردشان کنید.</p>

<p dir="rtl" align="right">‏<strong>تور فقط <span dir="ltr">TCP</span> حمل می‌کند</strong> — همه‌جا و در هر برنامه‌ای — پس برنامه یک فرانت <span dir="ltr">SOCKS</span> کوچک جلوی آن گذاشته: <span dir="ltr">DNS</span> از راه <span dir="ltr">TCP</span> و <em>داخل</em> تور جواب می‌گیرد، نام میزبان‌ها بدون <span dir="ltr">resolve</span> شدن به تور داده می‌شوند (پس نشانی‌های <span dir="ltr">.onion</span> کار می‌کنند و هیچ چیزی روی دستگاه شما لوکاپ نمی‌شود) و بقیهٔ <span dir="ltr">UDP</span> دور ریخته می‌شود. <span dir="ltr">QUIC</span> هم با آن دور ریخته می‌شود و برنامه‌ها به <span dir="ltr">TCP</span> برمی‌گردند.</p>

<p dir="rtl" align="right">‏تور از حالت‌های دیگر کندتر است و اولین اتصال طول می‌کشد: تور پیش از ساختن مدار یک دایرکتوری دانلود می‌کند. در همان حین، اعلان نشان می‌دهد بوت‌استرپ تا کجا رسیده. این انتظار مالِ تور است، نه برنامه.</p>

<h2 dir="rtl" align="right">‏تازه‌های دیگر</h2>

<ul dir="rtl" align="right">
<li align="right"><strong><span dir="ltr">MASQUE×2</span></strong> (سوئیچ <code dir="ltr">--mim</code>)، پروتکل پنجم: <span dir="ltr">MASQUE</span> داخل <span dir="ltr">MASQUE</span>، دو هاپ، برای گرفتن آی‌پی خروجی از رِنجی متفاوت با آنچه یک هاپ می‌دهد.</li>
<li align="right"><strong>هستهٔ موتور ۲.۰.۰</strong> (پیش‌تر ۱.۹.۰). هر چه ۱.۲.۸ در سرعت دانلود و رفتار اتصال درست کرده بود دست‌نخورده مانده — آن اعداد فایل‌به‌فایل دوباره بررسی شدند و تنظیم دوبارهٔ همان بافرها از سمت بالادست عمداً برداشته نشد.</li>
<li align="right"><strong>به دستیار هوش مصنوعی گفته می‌شود شما واقعاً در چه حالتی هستید</strong>، پس دیگر در حالتی که هیچ تونل ورپی ندارد، تنظیمات ورپ پیشنهاد نمی‌دهد. تنظیمات تور را می‌خواند و توضیح می‌دهد؛ عوضشان نمی‌کند. توضیح‌های تازه کنار هر تنظیم تور نشسته‌اند، به هر دو زبان.</li>
<li align="right">سه تنظیم تورِ موتور حالا در <strong>تنظیمات ← تور</strong> هستند: کشور پل، صبر بوت‌استرپ و بررسی دسترس‌پذیری. مورد آخر را بهتر است بدانید: اگر تور مدام شکست بوت‌استرپ گزارش می‌کند ولی همه چیز سالم به‌نظر می‌رسد، هدف پیش‌فرض اثبات موتور یعنی <span dir="ltr">check.torproject.org</span> خودش در بعضی شبکه‌ها بلاک است.</li>
<li align="right">پل‌ها در همهٔ حالت‌ها در دسترس‌اند مگر <strong>اتر ← تور</strong>، که تور آنجا از داخل تونل گرفته می‌شود و پل چیزی برای پنهان کردن ندارد. دلیلش روی همان ردیف نوشته شده.</li>
</ul>

<h2 dir="rtl" align="right">‏🔒 ممیزی امنیتی نسخهٔ ۱.۳.۰ — نمرهٔ ممیزی ۸۸ از ۱۰۰، پس از اصلاح‌ها ۹۳ از ۱۰۰</h2>

<p dir="rtl" align="right">‏یک ممیزی امنیتی کامل روی همان درختی اجرا شد که منتشر می‌شود: برنامهٔ کاتلین (۸۷ فایل، حدود ۲۸٬۲۰۰ خط)، پیکربندی گریدل و منابع، مانیفست، موتور راست همراه فایل قفل وابستگی‌هایش، کتابخانهٔ آمادهٔ سایفون و ورک‌فلوی انتشار. گزارش کامل: <a href="../docs/SECURITY_AUDIT_1.3.0.md"><code dir="ltr">docs/SECURITY_AUDIT_1.3.0.md</code></a></p>

<table dir="rtl">
<thead>
<tr><th align="right">#</th><th align="right">حوزه</th><th align="right">وزن</th><th align="right">نمرهٔ ممیزی</th><th align="right">پس از اصلاح</th></tr>
</thead>
<tbody>
<tr><td align="right">۱</td><td align="right">مدیریت کلیدها و اطلاعات حساس</td><td align="right">۱۵</td><td align="right">۹۵</td><td align="right">۹۵</td></tr>
<tr><td align="right">۲</td><td align="right">رمزنگاری، <span dir="ltr">TLS</span> و مقاومت در برابر <span dir="ltr">MitM</span></td><td align="right">۱۴</td><td align="right">۹۲</td><td align="right">۹۵</td></tr>
<tr><td align="right">۳</td><td align="right">خطر نشت اطلاعات (<span dir="ltr">DNS</span>، <span dir="ltr">IPv6</span>، عبور از تونل)</td><td align="right">۲۰</td><td align="right">۸۸</td><td align="right">۹۵</td></tr>
<tr><td align="right">۴</td><td align="right">ذخیره‌سازی محلی روی دیسک</td><td align="right">۱۳</td><td align="right">۹۴</td><td align="right">۹۴</td></tr>
<tr><td align="right">۵</td><td align="right">دسترسی‌ها و تنظیمات سیستم‌عامل</td><td align="right">۸</td><td align="right">۹۸</td><td align="right">۹۸</td></tr>
<tr><td align="right">۶</td><td align="right">لاگ‌گیری و تشخیص خطا</td><td align="right">۸</td><td align="right">۹۶</td><td align="right">۹۶</td></tr>
<tr><td align="right">۷</td><td align="right">کیفیت کد و پیکربندی شبکه</td><td align="right">۱۰</td><td align="right">۷۸</td><td align="right">۸۴</td></tr>
<tr><td align="right">۸</td><td align="right">افشا روی خودِ دستگاه (تصویر صفحه، کلیپ‌بورد)</td><td align="right">۶</td><td align="right">۶۵</td><td align="right">۹۲</td></tr>
<tr><td align="right">۹</td><td align="right">زنجیرهٔ تأمین و یکپارچگی بیلد</td><td align="right">۶</td><td align="right">۷۲</td><td align="right">۸۴</td></tr>
<tr><td align="right"></td><td align="right"><strong>جمع وزنی</strong></td><td align="right"><strong>۱۰۰</strong></td><td align="right"><strong>۸۸</strong></td><td align="right"><strong>۹۳</strong></td></tr>
</tbody>
</table>

<p dir="rtl" align="right">‏<strong>آنچه درست تأیید شد.</strong> هیچ کلید <span dir="ltr">API</span>، توکن یا کلید خصوصی در هیچ‌جای برنامه هاردکد نشده؛ کلید <span dir="ltr">Gemini</span> شما و رمز اشتراک شبکهٔ محلی با <span dir="ltr">AES-256-GCM</span> زیر کلیدی مهر می‌شوند که در <span dir="ltr">Android Keystore</span> ساخته شده و قابل استخراج نیست. هیچ رمزنگاری ضعیف یا منسوخی به کار نرفته. <strong>هیچ <span dir="ltr">TrustManager</span> سفارشی، هیچ بررسی‌کنندهٔ نام میزبانِ سهل‌گیر و هیچ مسیر <span dir="ltr">MitM</span>ـی پیدا نشد</strong> — هر سوکت <span dir="ltr">TLS</span> دست‌ساز گواهی را با نام میزبان تطبیق می‌دهد و برنامه <strong>فقط</strong> گواهی‌های ریشهٔ سیستم را معتبر می‌داند، پس گواهی ریشه‌ای که از راه تنظیمات نصب شود (همان کاری که پراکسی شنود می‌کند) نمی‌تواند ترافیک خودِ برنامه را باز کند. نام میزبان هرگز روی دستگاه شما <span dir="ltr">resolve</span> نمی‌شود؛ در حالت‌های تور، <span dir="ltr">DNS</span> از راه <span dir="ltr">TCP</span> داخل تور جواب می‌گیرد و <span dir="ltr">UDP</span> غیر-<span dir="ltr">DNS</span> دور ریخته می‌شود نه اینکه نشت کند؛ در حالت‌های زنجیره‌ای مسیر <span dir="ltr">::/0</span> بی‌قیدوشرط اضافه می‌شود. لاگ تشخیصی و فایل هویت موتور (که کلید خصوصی وایرگارد در آن است) روی دیسک رمز می‌شوند، و اگر کی‌استور کلید ندهد، آینهٔ لاگ خاموش می‌شود نه اینکه به متن ساده برگردد. <span dir="ltr">HTTP</span> بی‌رمز در کل برنامه ممنوع است، پشتیبان‌گیری و انتقال دستگاه‌به‌دستگاه دو لایه بسته‌اند، فقط پنج دسترسی خواسته می‌شود بدون <span dir="ltr">QUERY_ALL_PACKAGES</span> و بدون هیچ <span dir="ltr">ContentProvider</span> صادرشده، همهٔ <span dir="ltr">PendingIntent</span>ها تغییرناپذیرند، هیچ <span dir="ltr">WebView</span>ـی وجود ندارد، و برنامه <strong>هیچ ابزار تحلیل رفتار، هیچ <span dir="ltr">SDK</span> گزارش کرش و هیچ کتابخانهٔ ردیابی</strong> ندارد.</p>

<p dir="rtl" align="right">‏<strong>هفت مورد از ده یافته، پیش از انتشار همین نسخه اصلاح شد</strong> — ستون دوم جدول همین را می‌سنجد. کیل‌سوئیچ حالا <strong>به‌طور پیش‌فرض روشن</strong> است، و رابط قفل‌کنندهٔ آن مسیر <span dir="ltr">::/0</span> را بی‌قیدوشرط می‌گیرد: آن رابط یک چاه سیاه است و چیزی برای «خراب‌شدن» ندارد، پس وابسته‌کردنش به کلید <span dir="ltr">IPv6</span> فقط یک مسیر <span dir="ltr">v6</span> را در همان پنجره‌ای باز می‌گذاشت که کیل‌سوئیچ برایش وجود دارد. <span dir="ltr">FLAG_SECURE</span> روی همهٔ صفحه‌هایی گذاشته شد که راز نشان می‌دهند (کلید <span dir="ltr">API</span>، رمز اشتراک شبکهٔ محلی، توکن <span dir="ltr">Access</span>، کنسول بازِ لاگ، و گزارش کرش): نه اسکرین‌شات، نه ضبط صفحه، نه تصویر بندانگشتی «برنامه‌های اخیر». کپی همان رازها به کلیپ‌بورد «حساس» علامت می‌خورد، پس اندروید ۱۳ به بعد آن‌ها را در پیش‌نمایش چسباندن نشان نمی‌دهد. هر دو سرویس بی‌رمز تشخیص موقعیت حذف شدند؛ آی‌پی خروجی حالا فقط از یک اتصال <span dir="ltr">TLS</span> با گواهی تأییدشده می‌آید. در <span dir="ltr">CI</span> مرحلهٔ <span dir="ltr">cargo audit</span> اضافه شد و <span dir="ltr">Dependabot</span> ماهی یک‌بار وابستگی‌های گریدل را در یک پول‌ریکوئست واحد می‌پاید؛ برای باینری سایفون هم سندی از منشأ ثبت شد.</p>

<p dir="rtl" align="right">‏<strong>آنچه آگاهانه باز مانده است.</strong> <span dir="ltr">R8</span> خاموش است: شکستن <span dir="ltr">reflection</span> در <span dir="ltr">Compose</span> یا در کتابخانهٔ سایفون روی دستگاه بیرون می‌زند نه در تست واحد، و یک نسخهٔ خراب برای کسی که به این برنامه نیاز دارد بدتر از یک نسخهٔ کوچک‌نشده است. اکشن‌های <span dir="ltr">CI</span> هم هنوز با تگ پین شده‌اند نه با <span dir="ltr">SHA</span> کامیت. هر دو در گزارش ثبت شده‌اند.</p>

<p dir="rtl" align="right">‏<strong>این اصلاح‌ها در سطح سورس تأیید شده‌اند.</strong> بازبینی و بیلد شده‌اند و ۶۷ تست واحد قبول می‌شود، ولی هیچ‌کس هنوز روی یک گوشی ندیده که تصویر بندانگشتی «برنامه‌های اخیر» سفید است.</p>

<p dir="rtl" align="right">‏<strong>بیرون از دامنهٔ ممیزی:</strong> امضای برنامه و سازگاری به‌روزرسانی. نسخهٔ ۱.۳.۰ عمداً همان هویت امضای ۱.۲.۹ را نگه می‌دارد تا روی برنامهٔ فعلی شما بدون حذف نصب شود؛ هیچ موردی از امضا در بالا نمره نگرفته، و به همین دلیل این عدد با نمرهٔ ۷۹ از ۱۰۰ گزارش ۱.۲.۹ قابل مقایسه نیست.</p>

<h2 dir="rtl" align="right">‏چیزی که نصب می‌کنید را بررسی کنید</h2>

<p dir="rtl" align="right">‏هر ریلیز فایل‌های <span dir="ltr">APK</span> به‌ازای هر <span dir="ltr">ABI</span> را همراه مجموع <span dir="ltr">SHA-256</span> آن‌ها منتشر می‌کند و اثر انگشت امضاکننده در لاگ بیلد چاپ می‌شود؛ فهرست پایین همین صفحه را با آنچه در <strong>درباره</strong> می‌بینید بسنجید. توضیح کامل در <a href="../docs/SIGNING.md"><code dir="ltr">docs/SIGNING.md</code></a></p>

<p dir="rtl" align="right">‏راهنمای دستیار هوش مصنوعی به <a href="../docs/AI_GUIDE.fa.md"><code dir="ltr">docs/AI_GUIDE.fa.md</code></a> منتقل شد.</p>

</div>


---


<div dir="rtl" align="right">


<p dir="rtl" align="right">‏درود به دوستان عزیز 🌹</p>

<p dir="rtl" align="right">‏ایدهٔ اضافه‌کردن قابلیت هوش مصنوعی به برنامه، با دو هدف اصلی شکل گرفت: هدف اول، ساده‌ترکردن کار با تنظیمات مختلف برنامه و هدف دوم، کمک به کاربران برای شناسایی و برطرف‌کردن مشکلات اتصال.</p>

<h2 dir="rtl" align="right">‏هدف اول: راهنمایی دربارهٔ تنظیمات برنامه</h2>

<p dir="rtl" align="right">‏بسیاری از کاربران هنگام کار با برنامه با این مشکل مواجه بودند که تنظیمات متعددی در آن وجود دارد، اما کاربرد هر تنظیم، زمان مناسب استفاده و نحوهٔ پیکربندی آن برایشان کاملاً روشن نیست. به همین دلیل، پس از اضافه‌شدن قابلیت هوش مصنوعی، در کنار هر تنظیم یک آیکون مربوط به <span dir="ltr">Gemini</span> قرار گرفته است. با انتخاب این آیکون، توضیحی ساده و کاربردی دربارهٔ عملکرد آن تنظیم و موارد استفادهٔ آن نمایش داده می‌شود.</p>

<p dir="rtl" align="right">‏اگر توضیحات ارائه‌شده کافی نبود، می‌توانید گزینهٔ «متوجه نشدید؟ از دستیار بپرسید» را انتخاب کنید. با این کار، موضوع موردنظر به چت‌بات منتقل می‌شود و هوش مصنوعی به‌صورت خودکار با این درخواست، گفتگو را ادامه می‌دهد: «این توضیح را به‌خوبی متوجه نشدم؛ لطفاً توضیحات بیشتری ارائه بده.» پس از آن، توضیحات کامل‌تری در محیط چت نمایش داده می‌شود. همچنین می‌توانید در همان گفتگو، هر سؤال دیگری را دربارهٔ برنامه، تنظیمات یا نحوهٔ استفاده از آن مطرح کنید.</p>

<h2 dir="rtl" align="right">‏هدف دوم: مشاور تنظیمات هوش مصنوعی</h2>

<p dir="rtl" align="right">‏این قابلیت برای خود من نیز تا حد زیادی غیرمنتظره بود؛ زیرا انتظار نداشتم هوش مصنوعی بتواند به این شکل عملی و کاربردی در برنامه پیاده‌سازی شود. در بخش تنظیمات و قسمت هوش مصنوعی اتر، بخشی با عنوان «مشاور تنظیمات» قرار دارد. با ورود به این بخش و انتخاب گزینهٔ «بررسی نشست»، هوش مصنوعی لاگ زندهٔ برنامه را در همان لحظه بررسی و تحلیل می‌کند.</p>

<p dir="rtl" align="right">‏پس از پایان بررسی، نتیجه به شما نمایش داده می‌شود. اگر مشکلی در اتصال یا تنظیمات وجود داشته باشد، هوش مصنوعی آن مشکل را توضیح می‌دهد و تنظیمات پیشنهادی و بهینه‌ای را برای برطرف‌کردن آن ارائه می‌کند. با انتخاب دکمهٔ «اعمال»، تنظیمات پیشنهادی در برنامه اعمال می‌شوند. برای فعال‌شدن کامل این تغییرات، لازم است ابتدا یک‌بار اتصال را قطع و سپس دوباره برقرار کنید. تنظیمات جدید در اتصال بعدی فعال خواهند شد.</p>

<p dir="rtl" align="right">‏نکتهٔ قابل‌توجه این است که در حدود ۹۰ درصد مواقع، تنظیمات پیشنهادی هوش مصنوعی بسیار دقیق و کاربردی بوده‌اند و توانسته‌اند مشکل را به‌طور کامل برطرف کنند. به‌نظر من، این قابلیت می‌تواند تحول مهمی در برنامه‌های مشابه ایجاد کند؛ زیرا کاربران با هر سطحی از دانش فنی می‌توانند مشکلات خود را ساده‌تر برطرف کنند و درک بهتری از نحوهٔ کار با تنظیمات برنامه به دست آورند.</p>

<h2 dir="rtl" align="right">‏راهنمای دریافت کلید API و انتخاب مدل هوش مصنوعی</h2>

<p dir="rtl" align="right">‏برای استفاده از قابلیت‌های هوش مصنوعی، ابتدا باید یک کلید <span dir="ltr">API</span> رایگان از سایت <a href="https://aistudio.google.com/">Google AI Studio</a> دریافت کنید. پس از ساخت کلید، آن را در بخش «کلید <span dir="ltr">API</span>» برنامه وارد کنید و سپس گزینهٔ «تست اتصال» را بزنید. اگر کلید <span dir="ltr">API</span> معتبر باشد، پیام موفقیت نمایش داده می‌شود و مدل‌های هوش مصنوعی در برنامه قابل‌استفاده خواهند شد.</p>

<p dir="rtl" align="right">‏<strong>نکتهٔ بسیار مهم:</strong> برای استفاده از هوش مصنوعی در برنامه، حتماً از حالت اتصال ترکیبی <span dir="ltr">Aether → Psiphon</span> استفاده کنید. در حالت اتصال <span dir="ltr">Aether</span> به‌تنهایی، آی‌پی‌های <span dir="ltr">Cloudflare</span> در اختیار شما قرار می‌گیرند و سرویس‌های گوگل، ازجمله سرویس‌های هوش مصنوعی، این آی‌پی‌ها را شناسایی می‌کنند؛ در نتیجه ممکن است نتوانید از قابلیت هوش مصنوعی در برنامه استفاده کنید.</p>

<p dir="rtl" align="right">‏مدل <code dir="ltr">gemini-3.8-flash</code> به‌دلیل استفاده از تعداد توکن بیشتر، معمولاً سریع‌تر به سقف مصرف روزانه می‌رسد. اگر این مدل به محدودیت روزانه رسید، می‌توانید آن را به <code dir="ltr">gemini-3.1-flash-lite</code> تغییر دهید و دوباره از قابلیت‌های هوش مصنوعی استفاده کنید. همچنین می‌توانید مدل‌های زیر را نیز امتحان کنید:</p>

<ul dir="rtl" align="right">
<li><code dir="ltr">gemini-3.1-flash-lite-preview</code></li>
<li><code dir="ltr">gemini-flash-lite-latest</code></li>
</ul>

<p dir="rtl" align="right">‏این نکته را در نظر داشته باشید که مدل <code dir="ltr">gemini-3.8-flash</code> معمولاً زودتر از سایر مدل‌ها به سقف مصرف روزانه می‌رسد؛ بنابراین در صورت مشاهدهٔ خطای محدودیت مصرف، تغییر مدل می‌تواند مشکل را برطرف کند.</p>

<h2 dir="rtl" align="right">‏راهکار هنگام دریافت خطا</h2>

<p dir="rtl" align="right">‏گاهی ممکن است هنگام ارسال درخواست یا پرسیدن سؤال از هوش مصنوعی، پاسخ‌گویی کمی زمان ببرد یا خطایی نمایش داده شود. در چنین شرایطی، ابتدا گزینهٔ «تلاش دوباره» را انتخاب کنید. برای نمونه، ممکن است پیام زیر یا پیامی مشابه آن نمایش داده شود: «به سقف مصرف یا محدودیت سرعت رسیده‌اید؛ کمی صبر کنید و دوباره امتحان کنید.»</p>

<p dir="rtl" align="right">‏اگر پس از یک یا دو بار تلاش همچنان پاسخی دریافت نکردید، مدل هوش مصنوعی را به یکی از مدل‌های معرفی‌شده تغییر دهید. در بسیاری از موارد، تغییر مدل باعث برطرف‌شدن مشکل می‌شود.</p>

<h2 dir="rtl" align="right">‏نکتهٔ مهم دربارهٔ مشاور تنظیمات</h2>

<p dir="rtl" align="right">‏توصیه می‌شود فقط زمانی از مشاور تنظیمات هوش مصنوعی استفاده کنید که با افت محسوس سرعت، مشکل اتصال یا سایر اختلالات مرتبط با عملکرد برنامه مواجه شده‌اید. همچنین می‌توانید در محیط چت از هوش مصنوعی بخواهید لاگ برنامه را بررسی کند. هوش مصنوعی لاگ را تحلیل می‌کند، مشکل احتمالی را توضیح می‌دهد و تنظیمات پیشنهادی را در اختیارتان قرار می‌دهد. پس از آن، می‌توانید تنظیمات پیشنهادی را تنها با انتخاب یک دکمه اعمال کنید.</p>

<h2 dir="rtl" align="right">‏نکتهٔ پایانی دربارهٔ حریم خصوصی</h2>

<p dir="rtl" align="right">‏هیچ اطلاعات حساس یا مهمی از لاگ‌های برنامه برای هوش مصنوعی ارسال نمی‌شود؛ در صورت فعال‌کردن قابلیت هوش مصنوعی، فقط خلاصه‌ای پاک‌سازی‌شده از لاگ‌های فنی و بدون اطلاعات شخصی ارسال خواهد شد. این قابلیت تنها زمانی فعال است که خودتان کلید <span dir="ltr">API</span> شخصی‌تان را وارد و هوش مصنوعی را فعال کنید. در حالت پیش‌فرض، هیچ درخواستی به سرویس‌های هوش مصنوعی ارسال نمی‌شود.</p>

<p dir="rtl" align="right">‏پیش از ارسال نیز اطلاعات حساس مانند کلیدها، توکن‌ها، رمزهای عبور، شناسه‌ها، <span dir="ltr">UUID</span>ها، آدرس‌های عمومی <span dir="ltr">IP</span>، تاریخچهٔ مرور، درخواست‌های <span dir="ltr">DNS</span>، دامنه‌های بازدیدشده، محتوای ترافیک، فایل‌ها، مخاطبان، شناسهٔ دستگاه و موقعیت مکانی از لاگ حذف می‌شوند. برای مطالعهٔ توضیحات کامل دربارهٔ نحوهٔ پاک‌سازی لاگ‌ها و حفظ حریم خصوصی، می‌توانید به <a href="https://github.com/QW-AI-Code/Aether/blob/main/README.fa.md">README فارسی پروژه</a> مراجعه کنید.</p>

<p dir="rtl" align="right">‏⚙️ این مورد را هم فراموش کرده بودم اضافه کنم: شما می‌توانید درخواست خود را با هوش مصنوعی در میان بگذارید تا آن را برایتان انجام دهد. برای مثال، اگر یک <span dir="ltr">DNS</span> یا <span dir="ltr">IP</span> مناسب دارید و می‌خواهید آن را در بخش موردنظر برنامه وارد کنید، کافی است درخواست خود را در چت‌بات ارسال کنید تا هوش مصنوعی آن را در قسمت مربوط اعمال کند.</p>

<p dir="rtl" align="right">‏⚠️ توجه کنید: زبان پاسخ‌های هوش مصنوعی بر اساس زبان انتخاب‌شده در برنامه تعیین می‌شود. اگر زبان برنامه روی انگلیسی تنظیم شده باشد، پاسخ‌های هوش مصنوعی نیز به زبان انگلیسی ارائه می‌شوند و اگر زبان برنامه روی فارسی باشد، پاسخ‌ها به زبان فارسی نمایش داده خواهند شد.</p>

<p dir="rtl" align="right">‏⚠️ گاهی ممکن است هوش مصنوعی در پیشنهادها یا تحلیل‌های خود دچار اشتباه شود. در چنین شرایطی، می‌توانید در چت‌بات موضوع را با او در میان بگذارید؛ برای مثال بنویسید: «تنظیمی که پیشنهاد دادی باعث قطع کامل اتصال شد و دیگر نتوانستم وصل شوم. لطفاً لاگ برنامه یا تنظیمات را بررسی کن و بگو علت این مشکل چه بوده است.» در واقع، همانند سایر چت‌بات‌های هوش مصنوعی، می‌توانید با توضیح نتیجه‌ای که دریافت کرده‌اید، گفت‌وگو را ادامه دهید و درخواست خود را دقیق‌تر مطرح کنید تا در نهایت به نتیجهٔ مطلوب برسید.</p>

<p dir="rtl" align="right">‏اگر تنظیماتی را اعمال کردید و پس از آن برنامه دیگر متصل نشد، ابتدا گزینهٔ «بازنشانی تنظیمات» را انتخاب کنید و سپس با تنظیمات قبلی خود دوباره متصل شوید. بعد، نتیجهٔ به‌دست‌آمده را در چت‌بات برای هوش مصنوعی توضیح دهید تا تنظیمات بهینه‌تر و مناسب‌تری به شما پیشنهاد کند.</p>


</div>


---

<div dir="rtl">

<p dir="rtl" align="right">‏درود دوستان عزیز 🌹</p>

<p dir="rtl" align="right">‏در نسخهٔ <span dir="ltr">1.3.0</span>، هستهٔ اتر به نسخهٔ <span dir="ltr">2.0.0</span> به‌روزرسانی شد. قابلیت اتصال <span dir="ltr">Tor</span> (تور) توسط سازندهٔ هستهٔ اتر، <span dir="ltr">CluvexStudio</span>، به خودِ هسته اضافه شده است و من تنها حالت‌های ترکیبی مختلفِ اتصال <span dir="ltr">Tor</span> با سایر کانکشن‌ها را اضافه کرده‌ام.</p>

<p dir="rtl" align="right">‏<b>نکته دربارهٔ حالت <span dir="ltr">Tor</span>:</b> این حالت امنیت بسیار بالایی دارد، اما سرعت آن پایین است. بنابراین برای دوستانی مناسب است که امنیت برایشان مهم‌تر از سرعت است.</p>

<h2 dir="rtl" align="right">‏نتیجهٔ تست‌های من</h2>

<p dir="rtl" align="right">‏حالت <span dir="ltr">Tor</span> را روی اینترنت آسیاتک و همراه اول تست کردم؛ روی آسیاتک به‌خوبی متصل می‌شد، اما روی همراه اول اتصال به‌سختی برقرار می‌شد و نوسان زیادی داشت. روی آسیاتک نتیجه به این شکل بود:</p>

<table dir="rtl">
<thead>
<tr><th>حالت اتصال</th><th>نتیجه</th></tr>
</thead>
<tbody>
<tr><td><span dir="ltr">Tor</span> (تنها)</td><td>متصل شد</td></tr>
<tr><td>اتر + تور</td><td>متصل شد</td></tr>
<tr><td>تور + سایفون</td><td>متصل شد</td></tr>
<tr><td>تور + اتر</td><td>متصل نشد</td></tr>
</tbody>
</table>

<p dir="rtl" align="right">‏همهٔ این اتصال‌ها با تنظیمات پیش‌فرض انجام شد.</p>

<p dir="rtl" align="right">‏توجه داشته باشید که نتیجه ممکن است برای هر کاربر متفاوت باشد، چون وضعیت <span dir="ltr">DPI</span> از شهری به شهر دیگر، از منطقه‌ای به منطقهٔ دیگر و حتی از سیم‌کارتی به سیم‌کارت دیگر فرق می‌کند. پس اگر برای شما متصل نشد، حتماً با تنظیمات، حالت‌ها و پروتکل‌های مختلف تست کنید.</p>

<h2 dir="rtl" align="right">‏نکتهٔ پایانی — زمان اتصال</h2>

<p dir="rtl" align="right">‏اگر برنامه را برای بار اول نصب کرده‌اید یا به نسخهٔ جدید به‌روزرسانی کرده‌اید، اولین اتصال در هر کانکشنی ممکن است تا ۲ دقیقه زمان ببرد؛ پس کمی صبور باشید. در دفعات بعدی معمولاً کمتر از ۱ دقیقه طول می‌کشد. به‌طور کلی، بسته به <span dir="ltr">DPI</span> شبکهٔ شما، این زمان می‌تواند بین ۱ تا ۳ دقیقه متغیر باشد.</p>

<ul dir="rtl" align="right">
<li><b>اتر (تنها):</b> بسیار سریع‌تر متصل می‌شود.</li>
<li><b>اتر + سایفون:</b> کمی بیشتر زمان می‌برد، چون یک تونل بین دو کانکشن ایجاد می‌شود و طبیعی است که زمان اتصال بالاتر برود.</li>
<li><b>تور:</b> زمان اتصال ۱ تا ۲ دقیقه است، بسته به <span dir="ltr">DPI</span> اپراتور شما.</li>
</ul>

</div>
